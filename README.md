# YandexDown Android

Application Android native Kotlin/Jetpack Compose pour analyser les liens Yandex Disk et évoluer vers un gestionnaire de téléchargement et de transferts cloud.

## État actuel

**Prototype initial (0.1.0)**

Implémenté :
- Projet Android Kotlin + Jetpack Compose.
- Interface claire en français.
- Validation basique d'un lien Yandex Disk.
- Lecture des métadonnées d'une ressource publique via l'API REST officielle Yandex Disk.
- Workflow GitHub Actions qui compile un APK debug et le publie comme artefact.

Pas encore implémenté :
- Téléchargement effectif des fichiers et dossiers.
- OAuth Yandex et navigation dans le disque privé du compte connecté.
- Gestion des mots de passe de liens partagés (à confirmer selon les capacités officielles de l'API).
- Intégrations OAuth et transferts vers Google Drive, Dropbox, OneDrive, MEGA et pCloud.
- Transfert cloud-à-cloud, file d'attente persistante et reprise.
- Résolution de liens tiers Bypass.vip / Bypass.city.
- Récupération du cache privé d'autres applications (non accessible normalement à Android).
- Historique avancé, tests instrumentés et vérification d'intégrité.

Ne pas présenter ces éléments comme fonctionnels avant leur implémentation et leurs tests.

## Compilation

Le workflow GitHub Actions utilise JDK 17 et Gradle 8.9 :
- GitHub > Actions > Android debug APK.
- Télécharger l'artefact `YandexDown-debug`.
- Installer l'APK debug sur un appareil Android compatible.

Compilation locale :
```bash
gradle --no-daemon assembleDebug
```

## Sécurité et limites

- Les dossiers privés ne sont accessibles qu'après authentification du propriétaire du compte et selon les autorisations accordées.
- Un mot de passe de partage est distinct du mot de passe du compte. Il ne doit être envoyé qu'à un endpoint officiel qui le prend explicitement en charge.
- Les CAPTCHA, contrôles anti-robot, paiements et restrictions d'accès ne sont pas neutralisés.
- Ne jamais stocker les mots de passe ou jetons OAuth en clair. Utiliser Android Keystore et les flux OAuth recommandés.
- Le transfert direct cloud-à-cloud dépend des API de chaque fournisseur. Un relais distant peut être nécessaire et doit être sécurisé.
- Android n'autorise pas l'accès arbitraire au cache privé d'autres applications.

## Références

- Yandex Disk REST API: https://yandex.com/dev/disk-api/doc/en/
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Google Drive API: https://developers.google.com/drive/api/guides/manage-uploads
- Dropbox API: https://www.dropbox.com/developers/documentation/http/documentation
