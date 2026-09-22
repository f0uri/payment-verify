#!/usr/bin/env bash
# بناء APK بدون Gradle باستخدام أدوات Android SDK مباشرة (aapt2 + javac + d8 + apksigner)
# الاستخدام: ANDROID_HOME=/path/to/sdk JAVA_HOME=/path/to/jdk17 ./scripts/build-apk.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# على GitHub Actions وبعض البيئات ANDROID_HOME معرّف مسبقاً؛ وإلا نستخدم المسار الافتراضي
: "${ANDROID_HOME:=/usr/local/lib/android/sdk}"
export ANDROID_HOME
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"

# تثبيت مكونات SDK الناقصة تلقائياً (CI)
SDKM=""
[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] && SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
command -v sdkmanager >/dev/null 2>&1 && SDKM="$(command -v sdkmanager)"
if [ -n "$SDKM" ] && { [ ! -x "$BT/aapt2" ] || [ ! -f "$PLATFORM" ]; }; then
  echo "▶ تثبيت مكونات Android SDK الناقصة..."
  yes | "$SDKM" --licenses >/dev/null 2>&1 || true
  "$SDKM" "platforms;android-34" "build-tools;34.0.0" >/dev/null
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
OUT=build; rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"
SRC=app/src/main

echo "▶ 1/6 ترجمة الموارد (aapt2 compile)"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res/res.zip"

echo "▶ 2/6 ربط الموارد وإنشاء R.java (aapt2 link)"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay "$OUT/res/res.zip"

echo "▶ 3/6 ترجمة Java"
"$JAVAC" -encoding UTF-8 --release 11 -classpath "$PLATFORM" -d "$OUT/classes" \
  $(find "$OUT/gen" "$SRC/java" -name "*.java")

echo "▶ 4/6 تحويل إلى DEX (d8)"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")
( cd "$OUT/dex" && zip -q ../app-unsigned.apk classes.dex )

echo "▶ 5/6 محاذاة (zipalign)"
"$BT/zipalign" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "▶ 6/6 التوقيع (apksigner)"
KS=debug.keystore
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Payment Verify Debug,O=Dev,C=MA" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/payment-verify.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$OUT/payment-verify.apk"

cp "$OUT/payment-verify.apk" ./payment-verify.apk
echo "✅ تم: $(pwd)/payment-verify.apk ($(du -h payment-verify.apk | cut -f1))"
