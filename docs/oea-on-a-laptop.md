# Android OEA on a laptop (optional emulator route)

> **For the native Windows launcher with no emulator, see [OEA Desktop](oea-desktop.md).**
> This guide is only for running the Android OEA app inside Android on a laptop.

**OEA Android is the launcher. Android is the operating system underneath it.**
Its Home screen, app drawer, folders, dock, widgets, search, arcade and games
use Android APIs — `PackageManager`, the HOME intent, `AppWidgetHost`, and
`TelecomManager`. This optional route runs Android (Waydroid or an emulator) on
the computer and installs the Android app there.

So the configuration that actually works is:

> **Android runs on the laptop. OEA is installed as its Home app.**
> Everything OEA does — and every Android app you install — then works the same
> way it does on a phone, with the mouse as a finger and the keyboard as a
> keyboard.

That is a real, supported setup, not a workaround. It is what this document
sets up, with scripts in `tools/laptop/`.

## Native Windows app or Android emulator?

OEA Desktop now provides the native Windows launcher experience as a separate
app. It discovers and opens Windows programs directly, with no Android runtime;
see [OEA Desktop](oea-desktop.md). This guide does not install that desktop
build. It covers the Android OEA APK for people who specifically want Android
and the phone-only OEA features on their computer.

For this Android route, the artifacts are:

- **`OEA-laptop-x86_64.apk`** — a minified, resource-shrunk release build with
  x86_64 native libraries added for laptop Android.
- **`OEA-laptop-windows.zip`** — the APK plus the existing `.cmd` and
  PowerShell setup files for creating an Android emulator.

## Pick your Android-on-laptop environment

| Your laptop | Use | Why |
|---|---|---|
| Linux with Wayland (GNOME/KDE/Sway) | **Waydroid** | Fastest, integrates with the desktop, Android 11/13 images, GApps optional |
| Windows 10/11, macOS, Linux | **Android Studio emulator** | One install, API 36, x86_64 or arm64, best for trying OEA |
| Any PC, no Android Studio | **Android-x86 / LineageOS in a VM** | Full Android install, no Google account required |
| A spare phone and a cable | **scrcpy mirror** | Highest fidelity; the phone does the work |

All four run the same OEA APK. The laptop download is an x86_64 **release**
build, because laptop Android is usually x86_64 and the normal phone APK only
carries `arm64-v8a`/`armeabi-v7a` native libraries (WebRTC). On Apple Silicon,
the default arm64 APK is the right one.

## The two-minute version

### Windows: Android emulator

1. Download **`OEA-laptop-windows.zip`** from the
   [`oea-laptop-latest`](https://github.com/Emmanuel001afk/OEA/releases/tag/oea-laptop-latest)
   release and extract it.
2. Double-click **`OEA-Laptop-Setup.cmd`**.
3. If Android Studio is not installed, the script offers to install it with
   `winget`. Open Android Studio once, install **Android SDK Command-line Tools
   (latest)** in SDK Manager, then double-click the setup again.
4. The script downloads the emulator image, creates `oea-laptop`, starts it,
   installs OEA and sets it as Home. First run takes a while; later runs reuse
   everything. It also puts an **OEA** shortcut on the desktop; `Start-OEA.cmd`
   starts the Android device and opens OEA on later runs.

### Linux and macOS: two commands

```bash
# 1. Build the release laptop APK (x86_64 by default; arm64-v8a on Apple Silicon)
./tools/laptop/build-apk.sh

# 2a. Linux + Waydroid (Waydroid already installed and initialised)
./tools/laptop/install-waydroid.sh

# 2b. Or Android Studio emulator: creates an API 36 AVD if needed
./tools/laptop/install-emulator.sh
```

Both scripts install OEA, try to set it as the Home app, and open Android. If
Android asks **"Which app should open as Home?"**, choose **OEA** and **Always**.
That is the whole trick.

Prebuilt laptop downloads are attached to the **`oea-laptop-latest`** release
by the post-merge main build: the release APK (`OEA-laptop-x86_64.apk`) and the
Windows bundle (`OEA-laptop-windows.zip`). `oea-latest` remains the phone
release.

## After OEA is Home

You land on OEA's own Home screen, in a window you can resize or maximise. From
there, the parts that work with no phone hardware:

- **The arcade and its 20 cabinets** — Car Racing, Life Path, Pool, Snooker,
  Darts, Archery, Blocks, Word Finder, Whot, Ludo, Solitaire, Tap Match, Tic
  Tac Toe, Hide & Seek, Slide Puzzle, Ping Pong, Farm, Manners & Etiquette, Job
  Interview and Chess — plus **Endless Days** and **Send Off** as standalone
  games. Every game keeps its own entry, as on the phone.
- **The app drawer, folders, dock, search and themes** — they enumerate and
  launch whatever Android apps are installed.
- **Home-screen widgets** from apps that provide them.
- **The multitask view, app freezer, permission manager, file and system tools.**
- **Any APK you sideload** into the environment. It appears in the drawer
  immediately, exactly as on a phone.

Install apps as you go:

```bash
./tools/laptop/install-waydroid.sh ~/Downloads/Signal.apk   # Waydroid
./tools/laptop/install-emulator.sh ~/Downloads/Signal.apk   # emulator
```

## What needs more than a bare Android install

These are properties of the Android environment, not of OEA, and each has a
one-line fix:

| Want | Do this |
|---|---|
| Play Store, Google sign-in, Play Services apps | Initialise Waydroid with GApps (`sudo waydroid init -s GAPPS`); the emulator image already has Google APIs |
| Banking / DRM / Play Integrity apps | They may refuse a rooted or emulated device; that is their check, not OEA |
| Apps shipped only as ARM | Use an emulator/VM with ARM translation, or the arm64 emulator on Apple Silicon |
| Phone calls and SMS | Need a modem: a USB LTE dongle, a SIP/Telecom app, or mirror a real phone with scrcpy |
| Camera | Works with the emulator's virtual camera or a host webcam passed to the VM; Waydroid camera support is device-dependent |
| Bluetooth | Pass the host adapter through to the VM; not available inside Waydroid |

Phone-only OEA surfaces (the Phone app, call blocker, SIM directory, torch)
open but have nothing to talk to without a modem. That is expected on a laptop.

## Make it feel like a laptop

- **Resize OEA's window.** `OeaLauncherActivity` is declared
  `resizeableActivity="true"` and `screenOrientation="nosensor"`, so freeform and
  desktop Android window it correctly instead of forcing a phone shape.
- **Change density, not resolution, if icons look large:**
  ```bash
  waydroid shell wm density 240            # or 200/280; 160 is "default"
  waydroid shell wm size 1280x800          # optional, if the window is huge
  ```
  On the emulator: `adb shell wm density 240`.
- **Multi-window Android** (Waydroid): `waydroid prop set persist.waydroid.multi_windows true`
  then restart the session — OEA stays Home while other Android apps open as
  laptop windows.
- **Mouse and keyboard** are native Android input: click = tap, drag = swipe,
  wheel scrolls lists, arrow keys and Tab move focus, Enter activates, Esc goes
  back.

## If it does not work

| Symptom | Fix |
|---|---|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | You installed the phone APK. Use `dist/OEA-laptop-x86_64.apk` (or `arm64-v8a` on Apple Silicon). |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | An older OEA with a different signature is installed: `adb uninstall com.oea.launcher` (or `waydroid app remove com.oea.launcher`), then install again. |
| Android boots to its own launcher | Open Settings › Apps › Default apps › Home app › **OEA**, or run `adb shell cmd package set-home-activity com.oea.launcher/.OeaLauncherActivity`. |
| Black screen after setting OEA as Home | Resize the window once, or `adb shell am force-stop com.oea.launcher` and press Home. |
| Games run but the 3D engine is slow | Enable GPU/host rendering: emulator `-gpu host`; Waydroid uses the host GPU by default; Android-x86 needs Mesa with GLES 3. |

## How the native desktop version differs

OEA Desktop uses the host OS to discover and launch Windows apps, while the
Android version continues to use Android's PackageManager and HOME role. The
shared launcher model is search, categories, favorites, recents and themes; the
OS integration is a separate adapter. Android-only features—SIM, Telecom,
phone widgets and Android notification services—need their own desktop
implementations and are not claimed to work in the native desktop build.

## Files

- `tools/laptop/build-apk.sh` — builds the release `dist/OEA-laptop-<abi>.apk`
  with the extra ABI (`-PextraAbi`).
- `tools/laptop/install-waydroid.sh` — installs OEA (and optional APKs) into
  Waydroid, sets Home, opens the UI.
- `tools/laptop/install-emulator.sh` — creates/starts an API 36 AVD, installs
  OEA and optional APKs, sets Home.
- `tools/laptop/windows/OEA-Laptop-Setup.cmd` — the Windows double-click setup
  (`OEA-Laptop-Setup.ps1` next to it does the work).
- `tools/laptop/windows/Start-OEA.cmd` — starts the Android device and opens
  OEA; the setup puts a shortcut to it on the desktop.
- `tools/laptop/package-windows.sh` — bundles the APK, the Windows setup and
  this guide into `OEA-laptop-windows.zip`.
