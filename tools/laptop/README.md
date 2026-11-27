# Android OEA on a laptop (optional emulator route)

These scripts run the **Android OEA app** on a laptop by installing it into
Waydroid (Linux/Wayland) or an Android Studio emulator. For the native Windows
app launcher that discovers and opens desktop programs directly—with no
emulator—see [`OEA Desktop`](../../docs/oea-desktop.md).

```bash
./tools/laptop/build-apk.sh                  # release x86_64 -> dist/OEA-laptop-x86_64.apk
./tools/laptop/build-apk.sh arm64-v8a        # Apple Silicon / ARM64 Android image

./tools/laptop/install-waydroid.sh           # Waydroid, pre-installed
./tools/laptop/install-emulator.sh           # creates/starts an API 36 AVD
./tools/laptop/package-windows.sh dist/OEA-laptop-x86_64.apk OEA-laptop-windows.zip

# Install other Android apps into the same environment; they appear in OEA's drawer
./tools/laptop/install-waydroid.sh ~/Downloads/app.apk
./tools/laptop/install-emulator.sh ~/Downloads/app.apk
```

On Windows, `OEA-laptop-windows.zip` is specifically the Android-emulator
bundle: extract it and double-click `OEA-Laptop-Setup.cmd`. It installs the
Android release APK and selects OEA as Android Home. It is not required by OEA
Desktop.

The scripts are safe to re-run. `waydroid init -s GAPPS` is the step that adds
Play Store/Play Services; without it, OEA and sideloaded APKs still work.

Full Android-on-laptop setup, expectations, display tuning and troubleshooting:
[`docs/oea-on-a-laptop.md`](../../docs/oea-on-a-laptop.md).
