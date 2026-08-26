#!/usr/bin/env bash
# Installs the shipped, R8-minified APK on the running emulator and checks it
# starts. Opening the app builds the DI container and opens the encrypted
# database, which is the code path R8 is most likely to have broken.
set -euo pipefail

PACKAGE="com.trucdecomptable.ollamachat"
APK="app/build/outputs/apk/release/app-release.apk"

[ -f "$APK" ] || { echo "APK release introuvable : $APK"; exit 1; }

adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb logcat -c || true
adb install -r "$APK"

adb shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
sleep 10

if ! adb shell pidof "$PACKAGE" >/dev/null 2>&1; then
  echo "Le processus n'a pas survécu au démarrage :"
  adb logcat -d -b crash | tail -60
  exit 1
fi

if adb logcat -d -b crash | grep -q "$PACKAGE"; then
  echo "Crash enregistré au démarrage :"
  adb logcat -d -b crash | tail -60
  exit 1
fi

echo "APK release installé et démarré sans crash."
