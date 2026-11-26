# YandexDown Android

Application Android native Kotlin / Jetpack Compose pour parcourir les liens publics Yandex Disk et télécharger leurs fichiers.

## Fonctionnalités présentes

- Interface française claire.
- Import d'un lien depuis le presse-papiers à l'ouverture (détection au premier plan) et bouton Coller.
- Réception des liens partagés depuis les autres applications Android.
- Saisie manuelle toujours disponible.
- Validation des liens HTTPS Yandex Disk.
- Lecture des métadonnées via l'API officielle Yandex Disk.
- Connexion au compte Yandex privé avec jeton OAuth et stockage local chiffré via Android Keystore.
- Parcours et téléchargements de fichiers privés Yandex après autorisation.
- Affichage des fichiers et dossiers partagés.
- Navigation dans les sous-dossiers.
- Pagination des dossiers (jusqu'à 10 000 éléments par parcours).
- Téléchargement d'un dossier complet sous forme d'archive ZIP.
- Téléchargement de fichiers via le gestionnaire Android (notifications système et reprise gérée par Android).
- Noms de fichiers nettoyés avant enregistrement.
- Gestion des erreurs HTTP et messages renvoyés par Yandex.
- Compilation APK debug via GitHub Actions.

## Installation de la version de test

1. Ouvrir l'onglet Actions : https://github.com/nul353000-tech/YandexDown-Android/actions
2. Ouvrir le dernier workflow Android debug APK terminé avec succès.
3. Télécharger l'artefact YandexDown-debug.
4. Extraire l'archive ZIP et installer l'APK sur Android.

## Fonctions encore à développer

Ces fonctions ne doivent pas être considérées comme opérationnelles tant qu'elles ne sont pas livrées et testées :
- Transfert cloud-à-cloud vers Google Drive, Dropbox, OneDrive, MEGA et pCloud.
- Flux OAuth Yandex entièrement intégré avec retour automatique dans l'application (l'identifiant d'application Yandex doit être créé et renseigné une fois ; cette version utilise le flux de jeton officiel avec collage du jeton retourné).
- OAuth Google Drive et destination cloud sans stockage permanent sur le téléphone.
- Gestion des mots de passe de liens partagés, uniquement si Yandex expose un mécanisme officiel.
- Résolution de raccourcisseurs / interstitiels tiers compatibles.
- File de transferts persistante, vérification d'intégrité et reprise applicative.
- Récupération de médias depuis des emplacements auxquels Android accorde explicitement l'accès.

Note importante sur le stockage : cette version télécharge les fichiers dans Téléchargements/YandexDown via le service Android. Le mode cloud-only vers Google Drive n'est pas encore disponible.

## Connexion au compte Yandex privé

1. Créer une application dans le portail Yandex OAuth : https://oauth.yandex.com/client/new
2. Activer les droits Disk nécessaires dans la configuration de l'application.
3. Copier l'identifiant de l'application dans « Comptes cloud et connexions ».
4. Appuyer sur « Autoriser Yandex dans le navigateur », valider sur Yandex, puis récupérer le jeton affiché sur la page de vérification et le coller dans l'application.
5. Le jeton est chiffré avec une clé Android Keystore avant son stockage. Il peut être supprimé avec « Déconnecter Yandex ».

## Sécurité et limites

- L'accès privé nécessite l'authentification légitime du propriétaire du compte et les autorisations correspondantes.
- Un mot de passe de partage est différent du mot de passe du compte.
- Aucun CAPTCHA, paiement, contrôle anti-robot ou contrôle d'accès n'est contourné.
- Les jetons OAuth devront être protégés avec Android Keystore et les flux OAuth recommandés.
- Les API des fournisseurs déterminent si un transfert direct cloud-à-cloud est possible.
- Android n'autorise pas l'accès arbitraire au cache privé d'autres applications.

## Références techniques

- Yandex Disk REST API: https://yandex.com/dev/disk-api/doc/en/
- Android DownloadManager: https://developer.android.com/reference/android/app/DownloadManager
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Google Drive API: https://developers.google.com/drive/api/guides/manage-uploads
- Dropbox API: https://www.dropbox.com/developers/documentation/http/documentation
