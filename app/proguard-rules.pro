# ── Lisibilité ────────────────────────────────────────────────────────────────
# Le gain vient du retrait de code mort (material-icons-extended à lui seul),
# pas du renommage. Garder les noms rend les rapports d'erreur exploitables et
# supprime tout risque lié à la réflexion par nom.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses

# ── PDFBox ───────────────────────────────────────────────────────────────────
# Charge polices et ressources par nom de classe.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.**
-dontwarn javax.**

# ── SQLCipher ────────────────────────────────────────────────────────────────
# Couche JNI : les méthodes natives sont résolues par nom.
-keep class net.zetetic.database.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ── Room ─────────────────────────────────────────────────────────────────────
# Entités et DAO sont lus par le code généré et par la validation de schéma.
-keep class com.trucdecomptable.ollamachat.data.db.** { *; }

# ── Surface utilisée par les tests instrumentés ───────────────────────────────
# Ces tests s'exécutent contre l'APK minifié : ce qu'ils appellent doit rester.
-keep class com.trucdecomptable.ollamachat.security.** { *; }
-keep class com.trucdecomptable.ollamachat.data.backup.** { *; }
-keep class com.trucdecomptable.ollamachat.OllamaChatApp { *; }
-keep class com.trucdecomptable.ollamachat.AppContainer { *; }
-keep class com.trucdecomptable.ollamachat.MainActivity { *; }

# ── Réseau ───────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
