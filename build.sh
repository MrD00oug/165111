#!/usr/bin/env bash
# Compila o Tripper Meteor sem Gradle: aapt2 -> javac -> d8 -> apksig
set -euo pipefail
cd "$(dirname "$0")"

TOOLS=${TOOLS:?defina TOOLS (pasta com aapt2)}
ANDROID_JAR=${ANDROID_JAR:?defina ANDROID_JAR}
JADX_JAR=${JADX_JAR:?defina JADX_JAR (traz D8 e apksig)}
KS=${KS:-keystore.p12}
VERSION=${VERSION:-0.1}

rm -rf build && mkdir -p build/res build/classes build/dex build/gen

echo "== ícone"
python3 tools/make_icon.py res >/dev/null

echo "== recursos (aapt2)"
"$TOOLS/aapt2" compile --dir res -o build/res/
"$TOOLS/aapt2" link -o build/base.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 --version-name "$VERSION" \
  --java build/gen build/res/*.flat

echo "== java"
javac -nowarn --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" \
  -d build/classes $(find src build/gen -name '*.java') > build/javac.log 2>&1 || { grep -v "^Picked up" build/javac.log; echo "ERRO DE COMPILAÇÃO"; exit 1; }
test -f build/classes/com/lucas/trippermeteor/MainActivity.class

echo "== dex (d8)"
java -cp "$JADX_JAR" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --output build/dex $(find build/classes -name '*.class') 2>&1 | grep -v "^Picked up" || true

echo "== empacotar"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -X ../unsigned.apk classes.dex)

echo "== assinar"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass tripper123 -keypass tripper123 \
    -alias tripper -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Tripper Meteor, O=Lucas, C=BR" 2>&1 | grep -v "^Picked up" || true
fi
javac -nowarn -cp "$JADX_JAR" -d build/tools tools/Signer.java 2>&1 | grep -v "^Picked up" || true
java -cp "$JADX_JAR:build/tools" Signer build/unsigned.apk "build/TripperMeteor-$VERSION.apk" "$KS" tripper123 tripper 2>&1 | grep -v "^Picked up"
ls -la build/*.apk
