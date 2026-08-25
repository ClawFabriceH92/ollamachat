# OllamaChat

Client Android pour serveur **Ollama** — configurable (URL serveur, modèle), conversations locales, import de documents, verrouillage PIN/biométrie.

## Fonctionnalités

- 💬 Chat avec streaming, historique local (Room)
- 🗂️ Multi-conversations : créer, renommer, archiver, supprimer définitivement
- ⚙️ Réglages : URL serveur + test de connexion, liste des modèles, température, top_p, top_k, max tokens, contexte, keep_alive, thème
- 📄 Import de documents : TXT / PDF / DOCX (texte extrait) + images si le modèle est vision
- 🔒 Verrouillage PIN 4 chiffres ou biométrie
- 🔄 Auto-update via GitHub Releases

## Build

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease
```

CI : GitHub Actions → release "latest" à chaque push sur main.
