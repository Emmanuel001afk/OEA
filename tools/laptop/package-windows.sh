#!/usr/bin/env bash
# Bundle the laptop APK with the double-click Windows setup.
#
#   ./tools/laptop/package-windows.sh dist/OEA-laptop-x86_64.apk OEA-laptop-windows.zip
#
# The zip contains the release APK, the setup scripts and the guide, so a
# Windows user extracts one archive and double-clicks OEA-Laptop-Setup.cmd.
set -euo pipefail

APK="${1:?usage: package-windows.sh <apk> <output.zip>}"
OUT="${2:?usage: package-windows.sh <apk> <output.zip>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/OEA-laptop-windows"
mkdir -p "$PKG"

cp "$APK" "$PKG/$(basename "$APK")"
cp "$ROOT/tools/laptop/windows/OEA-Laptop-Setup.cmd" "$PKG/"
cp "$ROOT/tools/laptop/windows/OEA-Laptop-Setup.ps1" "$PKG/"
cp "$ROOT/docs/oea-on-a-laptop.md" "$PKG/README.md"

OUT_DIR="$(dirname "$OUT")"
mkdir -p "$OUT_DIR"
OUT_ABS="$(cd "$OUT_DIR" && pwd)/$(basename "$OUT")"
( cd "$STAGE" && zip -qr "$OUT_ABS" OEA-laptop-windows )
echo "Packaged $OUT_ABS"
