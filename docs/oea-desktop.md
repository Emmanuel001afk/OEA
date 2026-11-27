# OEA Desktop: native Windows launcher

OEA Desktop is the desktop build of OEA's launcher experience. It runs directly
on Windows 10/11 x64 and does **not** start Android or an emulator. The Android
app remains the Android launcher; the desktop build maps the same launcher
ideas—catalog, ranked search, pinned apps, recent launches, categories and
themes—to Windows APIs.

## Install and use

The Windows build produces an interactive installer and a portable executable.
Install OEA from the installer or run the portable `.exe`. The app opens as a
normal resizable window: OEA does not replace the Windows taskbar, Start menu or
system shell.

OEA discovers launchable shortcuts from the current user's and all users'
Start Menus and desktops, plus registered Windows Store apps. Search, category
filters, favorites and recent launches operate on that native catalog. Selecting
an app asks the Windows shell to open its discovered shortcut or app ID. The
quick-tools row opens Windows Calculator, File Explorer, Settings, Terminal and
Task Manager.

Pins, recent apps and the accent selection remain in the current Windows user
profile. The app-list index is refreshed on demand; use the refresh button after
installing or removing an app.

## What is shared with Android OEA

The desktop build follows OEA's app-catalog/search/favorites interaction model
and carries over the drawer's accent-insensitive search ranking (label prefix,
word prefix, initials, substring and app-ID matches, with recency as a tie
breaker). It uses the OEA glass/dark visual language, but it does not try to run
Android `Activity`, `PackageManager`, widget-host, Telecom, SIM or notification
APIs on Windows. Those features require separate desktop implementations and
are not silently faked as working.

This first desktop target is the launcher and host-app integration, not a port
of every Android mini-app or first-party Android game. Installed Windows apps
and games can be pinned and launched from the OEA catalog. The Android app and
its emulator/Waydroid route remain separate alternatives.

## Build and verify

```bash
cd desktop
npm ci
npm test
npm run dist:win
```

`dist:win` creates an installer and a portable `.exe` under
`dist/desktop-installer`. The `OEA Desktop Windows` GitHub Actions workflow runs
the tests and packages both executables on Windows, uploading them as the
`oea-desktop-windows` workflow artifact. The app is currently Windows-first;
other desktop platform adapters are future work.

For the separate Android APK/emulator scripts, see
[OEA on an Android laptop](oea-on-a-laptop.md).
