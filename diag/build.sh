#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
BT="$ANDROID_HOME/build-tools/34.0.0"; JAR="$ANDROID_HOME/platforms/android-34/android.jar"
rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 30 --version-code 1 --version-name 1.0
javac -encoding UTF-8 --release 11 -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
keytool -genkeypair -keystore d.ks -storepass android -keypass android -alias d -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=diag" >/dev/null
"$BT/apksigner" sign --ks d.ks --ks-pass pass:android --key-pass pass:android --out build/diag-piles.apk build/aligned.apk
