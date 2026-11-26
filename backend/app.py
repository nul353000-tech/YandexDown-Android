import io
import json
import os
import posixpath
import tempfile
import time
import zipfile
from pathlib import PurePosixPath
from urllib.parse import quote, urlparse

import requests
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

app = FastAPI(title="YandexDown ZIP Cloud Extractor", version="1.0.0")
HTTP = requests.Session()
MAX_ARCHIVE_BYTES = 150 * 1024 * 1024
MAX_EXPANDED_BYTES = 500 * 1024 * 1024
MAX_ENTRIES = 5000
CHUNK = 10 * 1024 * 1024


class ExtractRequest(BaseModel):
    public_key: str = Field(min_length=8, max_length=4096)
    path: str = Field(min_length=1, max_length=2048)
    provider: str
    access_token: str = Field(min_length=10, max_length=8192)


def api_error(response, provider):
    try:
        detail = response.text[:500]
    except Exception:
        detail = ""
    raise HTTPException(response.status_code if response.status_code < 500 else 502,
                        f"{provider}: HTTP {response.status_code} {detail}")


def safe_name(value):
    name = PurePosixPath(value).name.replace("\\x00", "")
    if name in ("", ".", ".."):
        raise HTTPException(400, "Nom de fichier ZIP invalide.")
    return name[:240]


def yandex_archive_download(public_key, path, target):
    meta = HTTP.get(
        "https://cloud-api.yandex.net/v1/disk/public/resources/download",
        params={"public_key": public_key, "path": path},
        timeout=(15, 60),
    )
    if not meta.ok:
        api_error(meta, "Yandex Disk")
    href = meta.json().get("href", "")
    parsed = urlparse(href)
    if parsed.scheme != "https" or not (parsed.hostname or "").endswith(("yandex.net", "yandex.com")):
        raise HTTPException(502, "Yandex a retourné une URL de téléchargement inattendue.")
    total = 0
    with HTTP.get(href, stream=True, timeout=(20, 180)) as response:
        if not response.ok:
            api_error(response, "Téléchargement Yandex")
        with open(target, "wb") as output:
            for chunk in response.iter_content(1024 * 1024):
                if not chunk:
                    continue
                total += len(chunk)
                if total > MAX_ARCHIVE_BYTES:
                    raise HTTPException(413, "ZIP trop volumineux (limite relais 150 Mio).")
                output.write(chunk)
    return total


def google_folder(token, name, parent):
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    metadata = {"name": name, "mimeType": "application/vnd.google-apps.folder"}
    if parent:
        metadata["parents"] = [parent]
    r = HTTP.post("https://www.googleapis.com/drive/v3/files", headers=headers, json=metadata, timeout=60)
    if not r.ok:
        api_error(r, "Google Drive dossier")
    return r.json()["id"]


def upload_google(token, root, rel, file_path, folder_cache):
    parts = [safe_name(p) for p in PurePosixPath(rel).parts if p not in ("", ".", "..")]
    parent = folder_cache.get("")
    if parent is None:
        parent = google_folder(token, safe_name(root), None)
        folder_cache[""] = parent
    current = ""
    for folder in parts[:-1]:
        current = current + "/" + folder
        if current not in folder_cache:
            folder_cache[current] = google_folder(token, folder, parent)
        parent = folder_cache[current]
    name = parts[-1]
    metadata = {"name": name}
    if parent:
        metadata["parents"] = [parent]
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json; charset=UTF-8",
        "X-Upload-Content-Type": "application/octet-stream",
        "X-Upload-Content-Length": str(os.path.getsize(file_path)),
    }
    init = HTTP.post("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable",
                     headers=headers, json=metadata, timeout=60)
    if not init.ok:
        api_error(init, "Google Drive")
    session = init.headers.get("Location")
    if not session:
        raise HTTPException(502, "Google Drive n'a pas retourné de session d'envoi.")
    with open(file_path, "rb") as source:
        result = HTTP.put(session, data=source, headers={"Content-Type": "application/octet-stream"},
                          timeout=(30, 600))
    if result.status_code not in (200, 201):
        api_error(result, "Google Drive envoi")


def dropbox_create_folder(token, path):
    response = HTTP.post("https://api.dropboxapi.com/2/files/create_folder_v2",
                         headers={"Authorization": f"Bearer {token}",
                                  "Content-Type": "application/json"},
                         json={"path": path, "autorename": False}, timeout=60)
    if response.status_code == 409 and "path/conflict/folder" in response.text:
        return
    if not response.ok:
        api_error(response, "Dropbox création dossier")


def upload_dropbox(token, root, rel, file_path, folder_cache):
    parts = [safe_name(root)] + [safe_name(p) for p in PurePosixPath(rel).parts]
    current = ""
    for folder in parts[:-1]:
        current += "/" + folder
        if current not in folder_cache:
            dropbox_create_folder(token, current)
            folder_cache.add(current)
    remote = "/" + "/".join(parts)
    headers = {
        "Authorization": f"Bearer {token}",
        "Dropbox-API-Arg": json.dumps({"path": remote, "mode": "add", "autorename": True, "mute": True}),
        "Content-Type": "application/octet-stream",
    }
    with open(file_path, "rb") as source:
        result = HTTP.post("https://content.dropboxapi.com/2/files/upload",
                           headers=headers, data=source, timeout=(30, 600))
    if not result.ok:
        api_error(result, "Dropbox")


def onedrive_create_folder(token, parent_id, name):
    if parent_id == "root":
        url = "https://graph.microsoft.com/v1.0/me/drive/root/children"
    else:
        url = f"https://graph.microsoft.com/v1.0/me/drive/items/{parent_id}/children"
    response = HTTP.post(url, headers={"Authorization": f"Bearer {token}",
                                       "Content-Type": "application/json"},
                         json={"name": name, "folder": {},
                               "@microsoft.graph.conflictBehavior": "rename"}, timeout=60)
    if not response.ok:
        api_error(response, "OneDrive création dossier")
    return response.json()["id"]


def upload_onedrive(token, root, rel, file_path, folder_cache):
    parts = [safe_name(root)] + [safe_name(p) for p in PurePosixPath(rel).parts]
    parent = folder_cache.get("")
    if parent is None:
        parent = onedrive_create_folder(token, "root", parts[0])
        folder_cache[""] = parent
    current = ""
    for folder in parts[1:-1]:
        current += "/" + folder
        if current not in folder_cache:
            folder_cache[current] = onedrive_create_folder(token, parent, folder)
        parent = folder_cache[current]
    filename = parts[-1]
    if os.path.getsize(file_path) == 0:
        url = f"https://graph.microsoft.com/v1.0/me/drive/items/{parent}:/{quote(filename, safe='')}:/content"
        with open(file_path, "rb") as source:
            response = HTTP.put(url, data=source,
                                headers={"Authorization": f"Bearer {token}",
                                         "Content-Length": "0"}, timeout=60)
        if response.status_code not in (200, 201):
            api_error(response, "OneDrive fichier vide")
        return
    url = f"https://graph.microsoft.com/v1.0/me/drive/items/{parent}:/{quote(filename, safe='')}:/createUploadSession"
    init = HTTP.post(url, headers={"Authorization": f"Bearer {token}",
                                  "Content-Type": "application/json"},
                     json={"item": {"@microsoft.graph.conflictBehavior": "rename"}},
                     timeout=60)
    if not init.ok:
        api_error(init, "OneDrive session")
    upload_url = init.json().get("uploadUrl")
    if not upload_url:
        raise HTTPException(502, "OneDrive n'a pas fourni d'URL d'envoi.")
    size = os.path.getsize(file_path)
    with open(file_path, "rb") as source:
        start = 0
        while start < size:
            data = source.read(CHUNK)
            end = start + len(data) - 1
            response = HTTP.put(upload_url, data=data, headers={
                "Content-Length": str(len(data)),
                "Content-Range": f"bytes {start}-{end}/{size}",
            }, timeout=(30, 600))
            if response.status_code not in (200, 201, 202):
                api_error(response, "OneDrive envoi")
            start = end + 1


def ensure_yandex_folder(token, path):
    r = HTTP.put("https://cloud-api.yandex.net/v1/disk/resources",
                 headers={"Authorization": f"OAuth {token}"},
                 params={"path": path}, timeout=60)
    if r.status_code not in (201, 409):
        api_error(r, "Yandex Disk dossier")


def upload_yandex(token, root, rel, file_path):
    parts = [safe_name(root)] + [safe_name(p) for p in PurePosixPath(rel).parts]
    parent = ""
    for folder in parts[:-1]:
        parent += "/" + folder
        ensure_yandex_folder(token, parent)
    destination = parent + "/" + parts[-1]
    info = HTTP.get("https://cloud-api.yandex.net/v1/disk/resources/upload",
                    headers={"Authorization": f"OAuth {token}"},
                    params={"path": destination, "overwrite": "false"}, timeout=60)
    if not info.ok:
        api_error(info, "Yandex Disk préparation")
    href = info.json().get("href")
    if not href or not href.startswith("https://"):
        raise HTTPException(502, "Yandex Disk n'a pas fourni d'URL d'envoi.")
    with open(file_path, "rb") as source:
        sent = HTTP.put(href, data=source, headers={"Content-Type": "application/octet-stream"},
                        timeout=(30, 600))
    if not sent.ok:
        api_error(sent, "Yandex Disk envoi")


@app.get("/health")
def health():
    return {"ok": True, "service": "yandexdown-zip-extractor", "version": "1.0.0"}


@app.post("/v1/extract")
def extract_archive(payload: ExtractRequest, request: Request):
    provider = payload.provider.strip().lower()
    if provider not in ("google drive", "dropbox", "onedrive", "yandex disk privé"):
        raise HTTPException(400, "Destination non prise en charge par le relais.")
    started = time.time()
    root = "YandexDown_Extracted"
    with tempfile.TemporaryDirectory(prefix="yd-zip-") as temp:
        archive = os.path.join(temp, "source.zip")
        extracted = os.path.join(temp, "files")
        os.makedirs(extracted, exist_ok=True)
        archive_size = yandex_archive_download(payload.public_key, payload.path, archive)
        try:
            with zipfile.ZipFile(archive) as zf:
                entries = zf.infolist()
                if len(entries) > MAX_ENTRIES:
                    raise HTTPException(413, "Archive trop complexe (plus de 5000 entrées).")
                total = 0
                files = []
                for entry in entries:
                    if entry.is_dir():
                        continue
                    mode = (entry.external_attr >> 16) & 0o170000
                    if mode == 0o120000:
                        raise HTTPException(400, "Les liens symboliques dans les ZIP sont refusés.")
                    raw = entry.filename.replace("\\", "/")
                    parts = PurePosixPath(raw).parts
                    if not parts or raw.startswith("/") or any(p in ("..", "") for p in parts):
                        raise HTTPException(400, "Chemin dangereux détecté dans le ZIP.")
                    total += entry.file_size
                    if total > MAX_EXPANDED_BYTES:
                        raise HTTPException(413, "Archive décompressée trop volumineuse (limite 500 Mio).")
                    target = os.path.realpath(os.path.join(extracted, *parts))
                    if not target.startswith(os.path.realpath(extracted) + os.sep):
                        raise HTTPException(400, "Chemin de sortie ZIP invalide.")
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    with zf.open(entry) as src, open(target, "wb") as dst:
                        while True:
                            block = src.read(1024 * 1024)
                            if not block:
                                break
                            dst.write(block)
                    files.append((raw, target))
        except zipfile.BadZipFile:
            raise HTTPException(400, "Le fichier sélectionné n'est pas une archive ZIP valide.")
        except RuntimeError as error:
            raise HTTPException(400, "Archive chiffrée ou mot de passe requis.") from error
        if not files:
            raise HTTPException(400, "Aucun fichier à extraire dans cette archive.")
        google_folders = {}
        dropbox_folders = set()
        onedrive_folders = {}
        for rel, local in files:
            if provider == "google drive":
                upload_google(payload.access_token, root, rel, local, google_folders)
            elif provider == "dropbox":
                upload_dropbox(payload.access_token, root, rel, local, dropbox_folders)
            elif provider == "onedrive":
                upload_onedrive(payload.access_token, root, rel, local, onedrive_folders)
            else:
                upload_yandex(payload.access_token, root, rel, local)
        return {"ok": True, "provider": provider, "archive_bytes": archive_size,
                "files_extracted": len(files), "expanded_bytes": total,
                "destination": root, "elapsed_seconds": round(time.time() - started, 1),
                "temporary_files_deleted": True}
