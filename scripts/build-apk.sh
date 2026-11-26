#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-/usr/lib/android-sdk}"
PLATFORM="${ANDROID_PLATFORM:-android-23}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
BUILD_TOOLS="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ROOTFS_DAT="${ROOTFS_DAT:-$ROOT/assets/rootfs.dat}"

# The Android SDK build-tools are x86_64-only. On arm64 use the Debian
# arm64 aapt/zipalign/apksigner from PATH; d8 is a Java launcher and is fine.
AAPT="${AAPT:-$(command -v aapt || true)}"
ZIPALIGN="${ZIPALIGN:-$(command -v zipalign || true)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
D8="${D8:-$BUILD_TOOLS/d8}"
ZIP="${ZIP:-$(command -v zip || true)}"

for tool in AAPT D8 ZIPALIGN APKSIGNER ZIP; do
  if [ -z "${!tool}" ] || { [ ! -x "${!tool}" ] && ! command -v "${!tool}" >/dev/null 2>&1; }; then
    echo "missing build tool: $tool=${!tool}" >&2
    exit 1
  fi
done

if [ ! -f "$ANDROID_JAR" ]; then
  echo "missing android.jar: $ANDROID_JAR" >&2
  echo "set ANDROID_HOME / ANDROID_PLATFORM" >&2
  exit 1
fi

if [ ! -f "$ROOTFS_DAT" ]; then
  echo "missing rootfs: $ROOTFS_DAT" >&2
  echo "download rootfs.dat from Releases or set ROOTFS_DAT" >&2
  exit 1
fi

if [ "$ROOTFS_DAT" != "$ROOT/assets/rootfs.dat" ]; then
  ln -sfn "$ROOTFS_DAT" "$ROOT/assets/rootfs.dat"
fi

rm -rf "$ROOT/build"
mkdir -p "$ROOT/build/classes" "$ROOT/build/dex"

mapfile -t JAVA_SOURCES < <(find "$ROOT/app/src" -name '*.java' -print | sort)
if [ "${#JAVA_SOURCES[@]}" -eq 0 ]; then
  echo "no Java sources found" >&2
  exit 1
fi

javac -source 1.8 -target 1.8 -cp "$ANDROID_JAR" \
  -d "$ROOT/build/classes" "${JAVA_SOURCES[@]}"

mapfile -t CLASS_FILES < <(find "$ROOT/build/classes" -name '*.class' -print | sort)
"$D8" --min-api 23 --lib "$ANDROID_JAR" \
  --output "$ROOT/build/dex" "${CLASS_FILES[@]}"

"$AAPT" package -f \
  -M "$ROOT/app/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  -A "$ROOT/assets" \
  -F "$ROOT/build/app-unsigned.apk"

(
  cd "$ROOT/build"
  "$ZIP" -q -j app-unsigned.apk dex/classes.dex
)

"$ZIPALIGN" -f 4 \
  "$ROOT/build/app-unsigned.apk" \
  "$ROOT/build/app-aligned.apk"

if [ -n "${KEYSTORE:-}" ]; then
  : "${KS_PASS:?set KS_PASS}"
  : "${KEY_ALIAS:?set KEY_ALIAS}"
  : "${KEY_PASS:?set KEY_PASS}"

  "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "env:KS_PASS" \
    --key-pass "env:KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$ROOT/build/IISHKO-Selfhost.apk" \
    "$ROOT/build/app-aligned.apk"

  "$APKSIGNER" verify --verbose \
    "$ROOT/build/IISHKO-Selfhost.apk"
else
  cp "$ROOT/build/app-aligned.apk" "$ROOT/build/IISHKO-Selfhost-unsigned.apk"
  echo "unsigned APK: $ROOT/build/IISHKO-Selfhost-unsigned.apk"
fi

echo "APK: $ROOT/build/IISHKO-Selfhost.apk"
