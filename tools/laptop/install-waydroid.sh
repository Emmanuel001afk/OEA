#!/usr/bin/env bash
# Put OEA on a laptop through Waydroid (Linux, Wayland).
#
# Waydroid must already be installed and initialised. Use GApps at init time if
# you want Play Store apps:
#   sudo waydroid init -s GAPPS
# Without GApps, OEA and any APK you sideload still work.
#
#   ./tools/laptop/install-waydroid.sh
#   ./tools/laptop/install-waydroid.sh path/to/app.apk ...   # also install apps
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK="$ROOT/dist/OEA-laptop-x86_64.apk"

if ! command -v waydroid >/dev/null 2>&1; then
  cat >&2 <<'MSG'
Waydroid is not installed. On a Wayland Linux desktop:
  sudo apt install waydroid        # Debian/Ubuntu, or your distro's package
  sudo waydroid init               # add -s GAPPS if you want Play Store apps
Then run this script again.
MSG
  exit 1
fi

if [ ! -f "$APK" ]; then
  "$ROOT/tools/laptop/build-apk.sh" x86_64
fi

# A session is required for both install and launch.
if ! waydroid status 2>/dev/null | grep -q "RUNNING"; then
  echo "Starting the Waydroid session ..."
  waydroid session start >/dev/null 2>&1 &
  for _ in $(seq 1 30); do
    waydroid status 2>/dev/null | grep -q "RUNNING" && break
    sleep 1
  done
fi

echo "Installing OEA ..."
waydroid app install "$APK"

# Set OEA as the Home app. Newer images accept set-home-activity; older ones
# only offer the chooser, so both are attempted.
if ! waydroid shell cmd package set-home-activity com.oea.launcher/.OeaLauncherActivity >/dev/null 2>&1; then
  waydroid shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
fi

# Any additional APKs passed on the command line become apps in OEA's drawer.
for app in "$@"; do
  echo "Installing $app ..."
  waydroid app install "$app"
done

echo
echo "OEA is installed. Opening the Android desktop ..."
echo "If Android asks which Home app to use, choose OEA and 'Always'."
waydroid show-full-ui
