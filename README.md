# LocalIA

App Android (Kotlin + Compose) pour faire tourner un modèle `.litertlm` (ex. Qwen3 1,7B)
100 % hors ligne, avec le moteur officiel LiteRT-LM de Google.

## Fonctions
- Import du modèle depuis le gestionnaire de fichiers (copié dans l'app, ~2 Go d'espace requis)
- Accélération GPU avec repli automatique sur CPU
- Réponses en direct (streaming) + bouton stop
- Mode réflexion (Qwen3) activable, bloc « Réflexion » repliable
- Réglages : température, top-K, instruction système, backend
- RAM libre affichée en permanence + vitesse (tok/s)

## Compiler l'APK sans PC (GitHub)
1. Crée un dépôt GitHub et envoie tout le contenu de ce dossier.
2. Onglet **Actions** → « Build APK » se lance tout seul (ou bouton *Run workflow*).
3. À la fin (~5 min), ouvre le run → **Artifacts** → `LocalIA-apk` → installe l'APK.

## Avec Android Studio
Ouvrir le dossier → Run. (minSdk 31 = Android 12+, téléphone arm64.)

## Conseils 6 Go de RAM
- Ferme les autres apps lourdes avant de charger le modèle.
- Si le GPU plante ou est lent : Réglages → Accélération → CPU.
- Le 1er chargement est long (compilation), les suivants sont plus rapides grâce au cache.
- Le mode réflexion consomme beaucoup de tokens : laisse-le désactivé pour discuter.
