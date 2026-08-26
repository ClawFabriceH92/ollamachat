# OllamaChat

Client Android pour serveur **Ollama** — configurable (URL serveur, modèle), conversations locales, import de documents, verrouillage PIN/biométrie.

## Fonctionnalités

- 💬 Chat avec streaming et **rendu markdown** (titres, listes, tableaux, blocs de code copiables, liens)
- 🗂️ Multi-conversations : créer, renommer, archiver, supprimer, **rechercher** (titre et contenu)
- ✏️ Par message : copier, **modifier et renvoyer**, **régénérer**, supprimer
- 🖼️ Images affichées dans la conversation, redimensionnées à l’import (1280 px max)
- 🧠 Raisonnement (`think`) et traces d’outils repliables
- 🛠️ Outils : recherche web, lecture d’URL, météo, calcul, mémoire persistante, serveurs MCP
  — proposés uniquement aux modèles qui déclarent les supporter
- ⚙️ Réglages : URL serveur + test et découverte réseau, modèles, température, top_p, top_k,
  max tokens, contexte, keep_alive, thème, compaction automatique du contexte
- 📄 Import TXT / PDF / DOCX (texte extrait) + images pour les modèles vision
- 🔒 Verrouillage PIN (PBKDF2 salé, tentatives limitées) ou biométrie, écran masqué dans le multitâche
- 🌍 Interface en français et en anglais
- 🔄 Auto-update via GitHub Releases

## Confidentialité

Tout reste sur l’appareil : conversations, mémoire et réglages ne quittent jamais le téléphone
(sauvegarde système désactivée, extraction de données bloquée). La clé API Brave est chiffrée
avec une clé de l’Android Keystore. Le contenu récupéré sur le web est transmis au modèle comme
de la donnée, jamais comme des instructions.

## Build

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease
```

Tests unitaires et lint :

```bash
./gradlew testDebugUnitTest lintDebug
```

Tests de migration de base (émulateur requis) :

```bash
./gradlew connectedDebugAndroidTest
```

Les schémas Room sont exportés dans `app/schemas/` à chaque build ; ils sont utilisés par les
tests de migration et doivent être commités quand la version de la base change.

CI : GitHub Actions → build et tests sur chaque branche et PR, release « latest » à chaque push sur main.
