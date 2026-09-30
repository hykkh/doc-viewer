#!/bin/bash
# Puts the LibreOffice engine into the app tree. The native library (~196MB)
# exceeds GitHub's file limit, so it is taken from the official LibreOffice
# Viewer build on F-Droid (MPL-2.0) instead of being committed.
set -euo pipefail
cd "$(dirname "$0")/.."
VER_CODE=131   # LibreOffice Viewer 26.2.6.3, arm64-v8a
APK=build/lo_$VER_CODE.apk
mkdir -p build
[ -f "$APK" ] || curl -L -o "$APK" "https://f-droid.org/repo/org.documentfoundation.libreoffice_$VER_CODE.apk"
TMP=build/lo_x; rm -rf "$TMP"; mkdir -p "$TMP"
unzip -q "$APK" 'lib/arm64-v8a/*' 'assets/*' -d "$TMP"
mkdir -p app/src/main/jniLibs/arm64-v8a app/src/main/assets
cp "$TMP"/lib/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
rm -rf app/src/main/assets/{share,program,unpack}
cp -r "$TMP"/assets/{share,program,unpack} app/src/main/assets/
# fontconfig cache must live in our own data dir
sed -i 's#/data/data/org.documentfoundation.libreoffice/fontconfig#/data/data/com.hprograms.docviewer/fontconfig#' \
  app/src/main/assets/unpack/etc/fonts/fonts.conf
rm -rf "$TMP"
echo "LibreOffice engine in place."
