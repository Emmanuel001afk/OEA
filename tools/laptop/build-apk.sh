#!/usr/bin/env bash
# Build the OEA launcher APK for a laptop Android environment.
#
#   ./tools/laptop/build-apk.sh            # x86_64 (Waydroid, Android-x86, Intel/AMD emulators)
#   ./tools/laptop/build-apk.sh arm64-v8a  # Apple Silicon emulators
#
# The normal OEA APK ships only arm64-v8a and armeabi-v7a native libraries.
# Android on a laptop is usually x86_64, so the extra ABI is what makes OEA
# install there. Release builds for phones are untouched.
set -euo pipefail

ABI="${1:-x86_64}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

case "$ABI" in
  x86_64|arm64-v8a|armeabi-v7a) ;;
  *)
    echo "Unknown ABI: $ABI (use x86_64 or arm64-v8a)" >&2
    exit 2
    ;;
esac

echo "Building OEA with the $ABI ABI ..."
./gradlew :oea:assembleDebug -PextraAbi="$ABI" "$@"

APK="$(find oea/build/outputs/apk/debug -type f -name '*.apk' -print -quit)"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "No debug APK was produced." >&2
  exit 1
fi

mkdir -p dist
OUT="dist/OEA-laptop-$ABI.apk"
cp "$APK" "$OUT"
echo
echo "Built $OUT"
echo "Install it inside the Android-on-laptop environment, then set OEA as Home."
