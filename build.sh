#!/usr/bin/env bash
# Сборка Fable Player без Gradle: aapt2 + javac + d8 + zipalign + apksigner
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"

rm -rf build
mkdir -p build/gen build/obj build/dex

echo "[1/5] Ресурсы (aapt2)"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/app.unsigned.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml -R build/res.zip \
    --java build/gen --auto-add-overlay

echo "[2/5] Компиляция Java"
javac --release 11 -classpath "$PLATFORM" -d build/obj \
    $(find src build/gen -name '*.java')

echo "[3/5] DEX (d8)"
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 \
    --output build/dex $(find build/obj -name '*.class')
(cd build/dex && zip -qj ../app.unsigned.apk classes.dex)

echo "[4/5] zipalign"
"$BT/zipalign" -f 4 build/app.unsigned.apk build/app.aligned.apk

echo "[5/5] Подпись"
if [ -f release.keystore ] && [ -f keystore.pass ]; then
    # Релизный ключ (для RuStore и публикации). Пароль — в keystore.pass (не в git).
    PASS="$(cat keystore.pass)"
    "$BT/apksigner" sign --ks release.keystore --ks-key-alias fable \
        --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
        --out FablePlayer.apk build/app.aligned.apk
    echo "Подписано РЕЛИЗНЫМ ключом (release.keystore)"
else
    # Запасной вариант: отладочный ключ
    if [ ! -f debug.keystore ]; then
        keytool -genkeypair -keystore debug.keystore -storepass android \
            -keypass android -alias debug -dname "CN=Fable Debug" \
            -keyalg RSA -keysize 2048 -validity 10000
    fi
    "$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
        --out FablePlayer.apk build/app.aligned.apk
    echo "Подписано отладочным ключом (debug.keystore)"
fi

echo "Готово: $(pwd)/FablePlayer.apk"
