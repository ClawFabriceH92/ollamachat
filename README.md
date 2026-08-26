# OllamaChat

Client Android pour serveur **Ollama** — conversations locales et chiffrées, outils, import de documents, verrouillage PIN/biométrie.

## Fonctionnalités

- 💬 Chat avec streaming et **rendu markdown** (titres, listes, tableaux, blocs de code copiables, liens)
- 🗂️ Multi-conversations : créer, renommer, archiver, supprimer, **rechercher** (titre et contenu)
- ✏️ Par message : copier, **modifier et renvoyer**, **régénérer**, supprimer
- 🖼️ **Plusieurs images par message**, affichées dans la conversation et redimensionnées à l’import
- ⚡ **Mode turbo** : bascule d’un geste dans la barre du chat, pour privilégier la vitesse
  (modèle maintenu en mémoire, compaction et outils coupés, contexte réduit aux 6 derniers
  messages, modèle plus léger au choix). Tes réglages ne sont pas modifiés — c’est une couche
  de surcharge, couper le turbo les restaure tels quels
- ⏱️ **Conversations éphémères** : effacement automatique après un délai sans activité
  (5 min à 24 h), réglable par conversation, avec compte à rebours visible. Le compteur
  repart à chaque message
- 🧠 Raisonnement (`think`) et traces d’outils repliables
- 🛠️ Outils : recherche web, lecture d’URL, météo, calcul, mémoire persistante, **serveurs MCP**
  — proposés uniquement aux modèles qui les déclarent
- 📦 **Gestion des modèles du serveur** : télécharger (avec progression) et supprimer depuis l’app
- ⚙️ Réglages : URL serveur + découverte réseau, température, top_p, top_k, max tokens,
  contexte, keep_alive, thème, couleurs dynamiques, compaction automatique du contexte
- 📄 Import TXT / PDF / DOCX + images pour les modèles vision
- 🔒 Verrouillage PIN (PBKDF2 salé, tentatives limitées) ou biométrie, écran masqué dans le multitâche
- 🌍 Interface en français et en anglais
- 🔄 Auto-update via GitHub Releases

## Confidentialité

Tout reste sur l’appareil.

- La **base de données est chiffrée** (SQLCipher) avec une clé aléatoire enveloppée par
  l’Android Keystore. La migration depuis une base en clair vérifie chaque table avant de
  supprimer l’ancien fichier.
- La sauvegarde système et le transfert d’appareil sont désactivés : rien ne part vers le cloud.
- La clé API Brave est chiffrée par le Keystore et masquée à l’écran.
- Le contenu récupéré sur le web est transmis au modèle comme de la donnée, jamais comme
  des instructions.
- Un **journal de diagnostic** local (réseau, outils, base) est consultable dans les réglages ;
  il ne contient aucun contenu de conversation et n’est jamais envoyé automatiquement.

### Conversations éphémères

Réglable par conversation depuis le menu du chat. La suppression a lieu **pendant que
l’application tourne** — au démarrage puis toutes les 30 secondes. C’est une mesure d’hygiène,
pas une protection contre quelqu’un qui a le téléphone en main ; c’est écrit tel quel dans
l’interface.

### Sauvegarde et changement de téléphone

Puisque la sauvegarde système est désactivée, l’export chiffré est le seul moyen de transférer
ses données : **Réglages → Données → Exporter mes données**. L’archive (conversations, messages,
mémoires, images) est chiffrée en AES-256-GCM avec une clé dérivée de la phrase de passe.
Sans cette phrase, le fichier est irrécupérable. L’import **ajoute** au contenu existant,
il n’efface jamais rien.

## Build

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease
```

Tests unitaires (dont Robolectric) et lint :

```bash
./gradlew testDebugUnitTest lintDebug
```

Tests instrumentés — migrations, chiffrement, démarrage (émulateur requis) :

```bash
./gradlew connectedDebugAndroidTest
```

Les schémas Room sont exportés dans `app/schemas/` à chaque build.

## Intégration continue

Sur chaque branche et chaque PR :

1. **instrumented** — émulateur API 34 : tests instrumentés, puis installation de l’APK
   release minifié avec vérification qu’il démarre (`scripts/smoke-release-apk.sh`).
2. **build** — tests unitaires, lint, APK release signé.

Les deux jobs échouent si le nombre de tests exécutés tombe anormalement bas. Un push sur
`main` publie la release « latest » et supprime les APK périmés.
