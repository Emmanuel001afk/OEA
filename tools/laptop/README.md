# OEA on a laptop

OEA is an Android launcher, so the configuration that works is **Android on the
laptop, OEA as its Home app**. These scripts build the laptop APK and install it
into either Waydroid (Linux/Wayland) or the Android Studio emulator.

```bash
./tools/laptop/build-apk.sh                  # release x86_64 -> dist/OEA-laptop-x86_64.apk
./tools/laptop/build-apk.sh arm64-v8a        # Apple Silicon emulator

./tools/laptop/install-waydroid.sh           # Waydroid, pre-installed
./tools/laptop/install-emulator.sh           # creates/starts an API 36 AVD
./tools/laptop/package-windows.sh dist/OEA-laptop-x86_64.apk OEA-laptop-windows.zip

# Install other Android apps into the same environment; they appear in OEA's drawer
./tools/laptop/install-waydroid.sh ~/Downloads/app.apk
./tools/laptop/install-emulator.sh ~/Downloads/app.apk
```

On Windows the download to use is `OEA-laptop-windows.zip`: extract it and
double-click `OEA-Laptop-Setup.cmd`. There is no OEA `.exe` because OEA is an
Android launcher; the `.cmd` installs the release APK into an Android emulator
and sets it as Home.

The scripts are idempotent and safe to re-run. `waydroid init -s GAPPS` is the
step that adds Play Store/Play Services; without it, OEA and sideloaded APKs
still work.

Full setup, expectations, display tuning and troubleshooting:
[`docs/oea-on-a-laptop.md`](../../docs/oea-on-a-laptop.md).
