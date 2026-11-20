#!/usr/bin/env bash
# Put OEA on the Android Studio emulator (Linux, macOS, Windows Git Bash/WSL).
#
# Needs Android Studio's command-line tools (sdkmanager, avdmanager) and an
# installed emulator. Creates an API 36 AVD called "oea-laptop" on first run.
#
#   ./tools/laptop/install-emulator.sh
#   ./tools/laptop/install-emulator.sh path/to/app.apk ...   # also install apps
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="oea-laptop"

if [ ! -x "$SDKMANAGER" ]; then
  echo "Android command-line tools not found under $SDK." >&2
  echo "Install Android Studio (SDK Manager > SDK Tools > Android SDK Command-line Tools)." >&2
  exit 1
fi

ARCH="$(uname -m)"
if [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then
  IMAGE="system-images;android-36;google_apis;arm64-v8a"
  ABI="arm64-v8a"
else
  IMAGE="system-images;android-36;google_apis;x86_64"
  ABI="x86_64"
fi

echo "Installing the API 36 system image ($ABI) ..."
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platforms;android-36" "platform-tools" "emulator" "$IMAGE"

if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
  echo "Creating the $AVD emulator ..."
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_tablet
fi

APK="$ROOT/dist/OEA-laptop-$ABI.apk"
if [ ! -f "$APK" ]; then
  "$ROOT/tools/laptop/build-apk.sh" "$ABI"
fi

"$EMULATOR" -avd "$AVD" -gpu auto -no-snapshot >/dev/null 2>&1 &
echo "Waiting for Android to boot ..."
"$ADB" wait-for-device
for _ in $(seq 1 180); do
  if "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then break; fi
  sleep 2
done

echo "Installing OEA ..."
"$ADB" install -r "$APK"
if ! "$ADB" shell cmd package set-home-activity com.oea.launcher/.OeaLauncherActivity >/dev/null 2>&1; then
  "$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
fi
for app in "$@"; do
  echo "Installing $app ..."
  "$ADB" install -r "$app"
done

"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
echo "OEA is installed. If Android asks for a Home app, choose OEA and 'Always'."
