# YandexDown Android

Build dependency retry after temporary Maven rate limiting.

Application Android native Kotlin / Jetpack Compose pour parcourir les liens publics Yandex Disk et télécharger leurs fichiers.

## Fonctionnalités présentes

- Interface française claire.
- Import d'un lien depuis le presse-papiers à l'ouverture (détection au premier plan) et bouton Coller.
- Réception des liens partagés depuis les autres applications Android.
- Résolution des redirections HTTP simples depuis Bitly, TinyURL, Rebrandly, Cuttly, T.ly, is.gd, shorturl.at et rb.gy vers un lien Yandex ; extraction native des destinations HTTPS explicites dans les paramètres d'URL.
- Saisie manuelle toujours disponible.
- Validation des liens HTTPS Yandex Disk.
- Lecture des métadonnées via l'API officielle Yandex Disk.
- Connexion au compte Yandex privé avec jeton OAuth et stockage local chiffré via Android Keystore.
- Parcours et téléchargements de fichiers privés Yandex après autorisation.
- Transfert direct sans fichier temporaire d'un fichier public Yandex vers le Yandex Disk privé connecté.
- Affichage des fichiers et dossiers partagés.
- Historique local des 100 derniers liens publics consultés : titre, URL et date, réouverture depuis l'application et effacement manuel.
- Lecteur vidéo intégré Media3 pour les vidéos publiques compatibles avec les conteneurs et codecs décodés par Android.
- Visionneuse photo intégrée pour JPEG, PNG, WebP, BMP, HEIC/HEIF et AVIF selon la version Android et le décodeur disponible.
- Prise en charge sans liste blanche d’extensions : tous les fichiers renvoyés par Yandex Disk sont affichés, notamment les vidéos (MP4, MKV, AVI, MOV, WebM, etc.) et les archives (ZIP, RAR, 7Z, TAR, GZ, BZ2, XZ, ISO, etc.). Les transferts utilisent un flux binaire générique afin de ne pas refuser les extensions inconnues ; les limites de taille et les règles propres à chaque fournisseur cloud restent applicables.
- Prise en charge des images sans liste blanche d’extensions : JPG/JPEG, PNG, GIF, WebP, HEIC, TIFF, BMP, SVG et autres fichiers image sont affichés et transférés comme fichiers binaires, sans conversion automatique.
- Navigation dans les sous-dossiers.
- Pagination des dossiers (jusqu'à 10 000 éléments par parcours).
- Téléchargement d'un dossier complet sous forme d'archive ZIP.
- File de transferts persistante via WorkManager et DownloadManager : notifications système, reprise réseau gérée par Android, contrôle de taille et comparaison MD5 lorsque Yandex fournit cette empreinte ; SHA-256 local conservé dans le résultat.
- Files cloud persistantes Dropbox et OneDrive via WorkManager : reprise des sessions d'envoi au dernier offset confirmé, contrôle du MD5 source, taille distante et hash distant lorsque le fournisseur le renvoie.
- File pCloud persistante via WorkManager : progression suivie par uploadprogress et intégrité contrôlée via checksumfile ; l'API pCloud ne documente pas de reprise par offset, donc une interruption relance l'envoi complet.
- Noms de fichiers nettoyés avant enregistrement.
- Gestion des erreurs HTTP et messages renvoyés par Yandex.
- Compilation APK debug via GitHub Actions.

### Limites de lecture multimédia

La lecture vidéo repose sur Media3/ExoPlayer et les décodeurs de l'appareil. MP4, WebM, Matroska/MKV, MPEG-TS et d'autres conteneurs sont pris en charge lorsque les codecs audio/vidéo correspondants sont disponibles. Il n'existe pas de garantie universelle « tous formats » : certains AVI, FLV, MOV, codecs exotiques, fichiers endommagés ou protégés peuvent ne pas être lus. La visionneuse photo dépend aussi des capacités Android ; les GIF animés ne sont pas annoncés comme animés dans cette version.

## Installation de la version de test

1. Ouvrir l'onglet Actions : https://github.com/nul353000-tech/YandexDown-Android/actions
2. Ouvrir le dernier workflow Android debug APK terminé avec succès.
3. Télécharger l'artefact YandexDown-debug.
4. Extraire l'archive ZIP et installer l'APK sur Android.

## Fonctions natives ajoutées dans cette version

- Écran natif « Outils de liens intégrés » : saisie, collage, extraction des paramètres de destination HTTPS (url/target/dest/redirect), résolution HTTP simple et copie du résultat sans ouvrir le navigateur.
- Liste de domaines de raccourcisseurs pris en charge pour éviter les requêtes arbitraires ; l'URL envoyée à Bypass.city est signalée à l'utilisateur.
- Diagnostic natif de l'API Bypass.city : lecture de la disponibilité et du catalogue des services.
- Résolution Bypass.city par API : l'application lit le schéma OpenAPI publié à l'exécution, vérifie le champ URL et les paramètres obligatoires, puis appelle uniquement l'opération documentée si le schéma est compatible.
- Connexion Google Drive avec Google Identity Services et autorisation limitée à `drive.file`.
- Transfert direct d'un fichier public Yandex Disk vers Google Drive via une session d'envoi Drive resumable, sans stockage permanent local.
- Extraction ZIP dans toutes les destinations prises en charge : pCloud utilise son API native ; Google Drive, Dropbox, OneDrive et Yandex Disk privé utilisent le relais cloud YandexDown. L’archive est téléchargée depuis Yandex vers le relais, extraite dans un espace temporaire, puis les fichiers sont envoyés dans le compte connecté en conservant l’arborescence. Les fichiers temporaires sont supprimés à la fin.

## Configuration des fournisseurs cloud

- **Google Drive** : créer un projet Google Cloud, activer Drive API et enregistrer le client OAuth Android associé au package fr.yandexdown et à l'empreinte SHA-1 du certificat de signature.
- **Dropbox** : créer une application Dropbox, activer les scopes de contenu en écriture, enregistrer le redirect URI yandexdown://oauth/dropbox et saisir l'App key dans l'application. Le flux mobile utilise PKCE.
- **OneDrive** : enregistrer une application dans Microsoft Entra, autoriser les comptes personnels et/ou professionnels nécessaires, ajouter le redirect URI mobile yandexdown://oauth/onedrive, accorder les permissions déléguées Files.ReadWrite et User.Read, puis saisir l'Application (client) ID.
- **pCloud** : enregistrer une application pCloud, déclarer yandexdown://oauth/pcloud comme redirect URI et saisir l'App ID. Le serveur de données européen ou américain est choisi à partir de la réponse OAuth.

Chaque utilisateur doit autoriser explicitement son compte dans le navigateur système. Les identifiants d'application sont propres au projet YandexDown et ne peuvent pas être devinés ni remplacés par de faux identifiants.

## Configuration Google Drive

L'intégration Google Drive nécessite un projet Google Cloud avec l'API Drive activée et un client OAuth Android configuré pour le package `fr.yandexdown` et l'empreinte SHA-1 du certificat qui signe l'APK installé. L'utilisateur doit ensuite autoriser l'accès depuis l'écran natif « Comptes cloud et connexions ». Le jeton d'accès est chiffré localement avec Android Keystore ; il est de courte durée et peut devoir être renouvelé.

## Fonctions encore à développer

Ces fonctions ne doivent pas être considérées comme opérationnelles tant qu'elles ne sont pas livrées et testées :
- Intégration du SDK MEGA officiel Android (SDK natif C++/JNI) et transfert cloud-to-cloud MEGA.
- Les liens Yandex protégés par mot de passe ne sont pas déverrouillables via les endpoints publics documentés de l’API REST. L’application ne prétend donc pas pouvoir télécharger directement ces liens protégés sans mécanisme officiel Yandex ; les liens publics non protégés restent traités directement.
- Résolution avancée des pages intermédiaires JavaScript des services Linkvertise, Lootlinks, Work.ink, Cuty.io, Boost.ink, Adfoc.us et Rekonise.
- Reprise pCloud au dernier octet : non disponible dans l'API officielle uploadfile ; la file persistante relance un envoi complet avec nopartial et vérification checksumfile.
- Récupération de médias depuis des emplacements auxquels Android accorde explicitement l'accès.

Note importante sur le stockage : les téléchargements manuels sont enregistrés dans Téléchargements/YandexDown via le service Android. Les transferts directs Yandex vers Google Drive ou Yandex Disk ne créent pas de fichier permanent local. Le mode cloud-only par défaut pour toutes les opérations reste à finaliser.

## Connexion au compte Yandex privé

1. Créer une application dans le portail Yandex OAuth : https://oauth.yandex.com/client/new
2. Activer les droits Disk nécessaires dans la configuration de l'application.
3. Copier l'identifiant de l'application dans « Comptes cloud et connexions ».
4. Appuyer sur « Autoriser Yandex dans le navigateur », valider sur Yandex, puis récupérer le jeton affiché sur la page de vérification et le coller dans l'application.
5. Le jeton est chiffré avec une clé Android Keystore avant son stockage. Il peut être supprimé avec « Déconnecter Yandex ».

## Sécurité et limites

- L'accès privé nécessite l'authentification légitime du propriétaire du compte et les autorisations correspondantes.
- Un mot de passe de partage est différent du mot de passe du compte.
- Les raccourcisseurs qui redirigent directement en HTTP(S) sont suivis ; les pages intermédiaires nécessitant JavaScript peuvent nécessiter une action humaine.
- Les fichiers vidéo et archives ne sont pas filtrés par extension : les formats courants et inconnus sont transmis comme données binaires. La prise en charge effective d’un format dépend ensuite du service cloud destinataire, de ses limites de taille et de ses propres règles.
- L’extraction ZIP est proposée pour Google Drive, Dropbox, OneDrive, pCloud et Yandex Disk privé. pCloud utilise son API native. Les autres destinations utilisent le service relais `backend/` hébergé sur Render : le ZIP est téléchargé depuis Yandex, extrait dans un répertoire temporaire du serveur, puis chaque fichier est envoyé par l’API officielle du fournisseur. Les fichiers temporaires sont supprimés en fin de requête. Limites du relais : archive source de 150 Mio maximum, 500 Mio décompressés maximum, 5000 entrées maximum ; les ZIP chiffrés nécessitant un mot de passe sont refusés. Le service Render gratuit peut se mettre en veille et les transferts longs dépendent des limites du fournisseur et de l’hébergeur. Ce relais n’est pas un stockage permanent.
- Aucun CAPTCHA, paiement, contrôle anti-robot ou contrôle d'accès n'est contourné.
- Les jetons Yandex et Google sont chiffrés avec Android Keystore. Les jetons d'accès Google sont de courte durée et peuvent devoir être renouvelés.
- Pour l’extraction via le relais (Google Drive, Dropbox, OneDrive et Yandex privé), le jeton d’accès du fournisseur est transmis temporairement au serveur Render en HTTPS afin d’effectuer l’envoi. Le code du relais ne l’écrit pas dans un fichier ni dans une base de données ; il l’utilise en mémoire pendant la requête. Le ZIP et les fichiers extraits sont stockés temporairement dans le répertoire temporaire du serveur puis supprimés à la fin du traitement. Ne lance l’opération qu’avec un compte cloud que tu autorises à utiliser ce relais.
- Les transferts Yandex vers Dropbox, OneDrive et pCloud utilisent leurs API officielles via un relais réseau dans l'application ; les octets ne sont pas conservés dans un fichier local permanent. Dropbox et OneDrive disposent d'une file WorkManager avec reprise de session et vérification de l'intégrité distante lorsqu'un hash est fourni. pCloud dispose d'un worker persistant avec suivi uploadprogress et vérification checksumfile ; après interruption, il recommence l'envoi complet car l'API officielle ne fournit pas d'offset de reprise documenté. Le SDK MEGA doit être compilé et lié nativement avant d'activer ce fournisseur.
- Android n'autorise pas l'accès arbitraire au cache privé d'autres applications.

## Résolution Bypass.city

Le résolveur externe conservé est Bypass.city : https://bypass.city/ et liste des sites pris en charge : https://bypass.city/supported. Bypass.vip est retiré du projet.

Bypass.city est appelé depuis l'interface native via son API : l'application lit son schéma OpenAPI avant d'envoyer une requête, applique une temporisation conservatrice et refuse l'appel si les champs requis ne sont pas documentés ou nécessitent des paramètres non configurés. Aucun CAPTCHA, anti-bot, paiement, contrôle d'accès ou validation humaine n'est contourné.

## Références techniques

- Yandex Disk REST API: https://yandex.com/dev/disk-api/doc/en/
- Android DownloadManager: https://developer.android.com/reference/android/app/DownloadManager
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- pCloud API — extraction d’archives : https://docs.pcloud.com/methods/archiving/extractarchive.html et suivi : https://docs.pcloud.com/methods/archiving/extractarchiveprogress.html
- Cloudflare Workers limits (comparaison des limites des environnements serverless) : https://developers.cloudflare.com/workers/platform/limits/
- Google Drive API: https://developers.google.com/drive/api/guides/manage-uploads
- Microsoft Graph — Upload sessions: https://learn.microsoft.com/graph/api/driveitem-createuploadsession
- Dropbox API v2 — Upload sessions: https://docs.dropboxapi.com/dropbox-api/api-reference/user-endpoints/files/upload-session-start
- Dropbox API: https://www.dropbox.com/developers/documentation/http/documentation
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager
