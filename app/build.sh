#!/usr/bin/env bash
# Build dsh-approvals.apk with raw Android build-tools (no Gradle).
#
# Works locally and in GitHub Actions. Toolchain resolution:
#   Android SDK : $DSH_ANDROID_SDK, else $ANDROID_HOME, else ~/.dsh/build-tools/android-sdk
#   build-tools : $DSH_ANDROID_BUILD_TOOLS, else $SDK/build-tools/34.0.0
#   JDK 17      : existing $JAVA_HOME (CI setup-java), else ~/.dsh/build-tools/jdk-17*
#   output dir  : $DSH_APK_OUT_DIR, else app/build-out
#
# Signing: set KEYSTORE_BASE64 + KEYSTORE_PASSWORD (+ optional KEY_PASSWORD)
# as repo secrets and pass them through the workflow; without them a throwaway
# debug keystore is generated (signature changes every clean build — fine for
# testing, not for user-facing releases).
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")" && pwd)"

SDK="${DSH_ANDROID_SDK:-${ANDROID_HOME:-$HOME/.dsh/build-tools/android-sdk}}"
BT="${DSH_ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
if [ ! -x "$BT/aapt2" ]; then
  echo "aapt2 not found in $BT" >&2
  echo "install Android build-tools 34.0.0 (see .github/workflows/build-apk.yml) or set DSH_ANDROID_BUILD_TOOLS" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
  LOCAL_JDK=$(ls -d "$HOME"/.dsh/build-tools/jdk-17* 2>/dev/null | head -1)
  if [ -n "$LOCAL_JDK" ]; then export JAVA_HOME="$LOCAL_JDK"; fi
fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! command -v javac >/dev/null; then
  echo "javac not found — install JDK 17 or set JAVA_HOME" >&2
  exit 1
fi

ANDROID_JAR="${DSH_ANDROID_PLATFORM_JAR:-$SDK/platforms/android-34/android.jar}"
OUT="${DSH_APK_OUT_DIR:-$APP_DIR/build-out}"
mkdir -p "$OUT"

echo "== aapt2 link manifest =="
# NB: versionCode/versionName live in AndroidManifest.xml (the manifest wins
# over aapt2 flags); min/target SDK must be flags (aapt2 rejects uses-sdk).
"$BT/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  --min-sdk-version "${DSH_ANDROID_MIN_SDK:-26}" \
  --target-sdk-version "${DSH_ANDROID_TARGET_SDK:-34}" \
  -o "$OUT/base.apk"

echo "== javac =="
rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$APP_DIR/src" -name '*.java')

echo "== d8 dex =="
rm -rf "$OUT/dex"
mkdir -p "$OUT/dex"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --release \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== pack dex =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
jar uf "$OUT/unsigned.apk" -C "$OUT/dex" classes.dex

echo "== zipalign =="
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== sign =="
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  KS="$OUT/release.keystore"
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$KS"
  KSPASS="${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD secret is required when KEYSTORE_BASE64 is set}"
  KEYPASS="${KEY_PASSWORD:-$KSPASS}"
  trap 'rm -f "$KS"' EXIT
else
  KS="$OUT/debug.keystore"
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias dsh \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass dshphone -keypass dshphone \
      -dname "CN=DSH Phone Approval, OU=local, O=local, L=local, ST=local, C=FR"
  fi
  KSPASS="dshphone"
  KEYPASS="dshphone"
  echo "WARNING: no KEYSTORE_BASE64 secret — using a throwaway debug key." >&2
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KEYPASS" \
  --out "$OUT/dsh-approvals.apk" "$OUT/aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --print-certs "$OUT/dsh-approvals.apk"
ls -la "$OUT/dsh-approvals.apk"
echo APK_BUILD_OK
