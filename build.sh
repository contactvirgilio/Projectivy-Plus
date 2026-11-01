#!/usr/bin/env bash
# Builds LiteHome.apk with only a JDK, android.jar and dx.jar (no Gradle, no aapt).
# Usage: ANDROID_JAR=/path/android.jar DX_JAR=/path/dx.jar ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?set ANDROID_JAR to an API 29 android.jar}"
: "${DX_JAR:?set DX_JAR to dx.jar (or swap the dex step for d8)}"

rm -rf build && mkdir -p build/classes build/apk
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes src/io/lite/tvhome/*.java
java -jar "$DX_JAR" --dex --min-sdk-version=21 --output=build/apk/classes.dex build/classes
python3 tools/manifest.py build/apk

[ -f litehome.keystore ] || keytool -genkeypair -keystore litehome.keystore -storepass litehome \
  -keypass litehome -alias litehome -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Lite Home" >/dev/null

(cd build/apk && zip -q -X -0 ../unsigned.apk resources.arsc && zip -q -X -r ../unsigned.apk AndroidManifest.xml classes.dex res)
cp build/unsigned.apk LiteHome.apk
jarsigner -keystore litehome.keystore -storepass litehome -sigalg SHA256withRSA \
  -digestalg SHA-256 LiteHome.apk litehome >/dev/null
echo "Built $(pwd)/LiteHome.apk ($(stat -c%s LiteHome.apk) bytes)"
