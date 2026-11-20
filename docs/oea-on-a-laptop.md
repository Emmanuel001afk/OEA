# OEA on a laptop

Short answer: **OEA is an Android launcher, not a desktop app.** Today it runs on
a laptop through Android (an emulator or a mirrored phone). A native Windows /
macOS / Linux build would need a port, and the launcher half of OEA is the part
that cannot be ported as-is.

## What the project is today

- One Android APK, `com.oea.launcher` (`minSdk 26`, `targetSdk 36`,
  `compileSdk 36`, Java 21, Kotlin 2.3.20).
- Three Gradle modules: `oea` (the launcher and the arcade shell, Android Views
  and custom canvases), `oea-engine` (OpenGL ES 3 game engine) and `oea-games`
  (the games; Android library, `android.graphics.Canvas` drawing).
- There is no desktop target in `settings.gradle`, no web build and no
  Compose Multiplatform setup.

## Route 1 — Android emulator (the closest thing to "OEA on a laptop")

What you need:

| Item | Requirement |
|---|---|
| Computer | Windows 10/11, macOS 12+, or Linux with KVM; 64-bit |
| Memory | 8 GB minimum, 16 GB comfortable |
| Disk | ~10 GB free (Android Studio + SDK + one emulator image) |
| CPU | Virtualisation enabled in firmware (WHPX/Hyper-V on Windows, KVM on Linux, Hypervisor.framework on macOS) |
| Software | Android Studio (bundles JDK 21), SDK Platform 36, Build-Tools 36.x, platform-tools, emulator |
| Device | AVD with **API 36** and Google APIs; `arm64-v8a` image on Apple Silicon, `x86_64` on Intel/AMD |

Steps:

1. Install Android Studio and, in the SDK Manager, Android 16 (API 36) and the
   latest Build-Tools.
2. Create a Pixel-class AVD with the API 36 system image and start it.
3. Build and install:
   ```bash
   ./gradlew :oea:installDebug
   ```
   or install the APK directly:
   ```bash
   adb install -r oea/build/outputs/apk/debug/oea-debug.apk
   ```
   The shipped ABIs are `arm64-v8a` and `armeabi-v7a`. For an x86_64 emulator,
   build the debug APK with the extra ABI:
   ```bash
   ./gradlew :oea:assembleDebug -PextraAbi=x86_64
   ```
4. Inside the emulator, open OEA and choose it as Home (Settings › Apps › Default
   apps › Home app). The launcher, the arcade, the games and the settings all run.

Honest limits: the emulator has no SIM/Telecom, no real camera quality, no
Bluetooth, no Device Owner, and sensors are simulated. Those OEA features will be
missing or inert. Mouse clicks become taps; there is no touch pressure or
multi-touch beyond what the emulator forwards.

## Route 2 — mirror a real phone (best fidelity)

- Phone with USB debugging on, a data cable, `adb` and [scrcpy](https://github.com/Genymobile/scrcpy)
  on the laptop.
- `scrcpy` shows the phone in a window; the mouse is your finger and the keyboard
  types. Everything works because it is the phone doing the work. This is the
  route to use for testing a real build, not for a laptop-native experience.

## Route 3 — Android on the laptop without Studio

- **Waydroid** (Linux, Wayland) or **Android-x86** in a VM: same APK, more setup,
  same Android-only limits.
- A cloud Android phone (any provider) with the APK sideloaded.

## Route 4 — a native desktop build

This is a different product, not a packaging checkbox. What carries over and what
does not:

**Portable with work**

- The game rules and state machines in `oea-games` are ordinary Kotlin. The 19
  arcade cabinets could share a desktop shell.
- `oea-engine` is OpenGL ES 3: it can run on desktop OpenGL 3.3+ through a small
  GLES compatibility layer (ANGLE/LWJGL), or be replaced with Skia.
- Kotlin/JVM tooling, Gradle, the JVM tests.

**Needs replacing**

- `android.graphics.Canvas` / `Paint` / `Path` drawing in the arcade — port to
  Skia/Compose Canvas or write a thin shim.
- Android lifecycle (`Activity`, `Service`, `BroadcastReceiver`) → desktop windows
  and an app lifecycle.
- `SharedPreferences`/DataStore → a desktop settings store.
- Touch input → mouse, trackpad, keyboard and gamepad, plus window resizing.

**Android-only, no desktop equivalent**

- The HOME role and the app drawer (`PackageManager`, intents, shortcuts).
- Home-screen widgets (`AppWidgetHost`).
- Notification listener, status bar and Quick Settings tiles.
- Telecom call screening, SIM/telephony, Bluetooth.
- Device Owner / DevicePolicy, accessibility services, overlay windows.
- Wallpaper service, live wallpapers, camera2, split screen and OEA's live app
  windows.

Those are the launcher's identity. A desktop OEA that keeps them would have to
reimplement the equivalents of a desktop shell (window manager, dock, file/app
launcher), which is a new product rather than a port.

**Work estimate**

- **OEA Arcade desktop** (hub + 19 cabinets, no launcher): realistic first step.
  Add a Compose Multiplatform desktop target, move the game logic into a shared
  module, replace Canvas drawing, add mouse/keyboard input, package with
  `jpackage` (MSI/DMG/AppImage). This is the version worth building if the aim is
  "play OEA games on my laptop".
- **Full OEA launcher desktop**: large; most of the systems above would be
  stubs or new desktop code. The Android APK would remain the real product.

**Build requirements for a desktop port**

- JDK 21, Gradle 9.x, Kotlin 2.3.x, Compose Multiplatform (or Skia/LWJGL).
- CI on `windows-latest`, `macos-latest` and `ubuntu-latest`.
- Packaging/signing per OS; auto-update is a separate piece of work.
- Users: any 64-bit laptop with 4 GB RAM; OpenGL 3.3+ for the 3D engine; ~300 MB
  disk.

## Recommendation

- To use OEA on a laptop **now**: emulator (Route 1) or scrcpy (Route 2).
- To ship a laptop-native OEA: start with **OEA Arcade desktop** — the games are
  the portable part — and keep the launcher on Android, where Home, widgets,
  calls and device policy actually exist.
