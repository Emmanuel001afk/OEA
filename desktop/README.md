# OEA Desktop

OEA Desktop is a Windows-first desktop app launcher. It runs directly on the
host OS—there is no Android emulator or Android SDK in this app. The Electron
main process is the OS adapter; the isolated renderer is the OEA home surface.

## What it does

- Finds Start Menu and desktop `.lnk` / `.url` shortcuts and registered Windows
  Store apps.
- Opens apps through the Windows shell, without executing a command assembled
  from renderer-supplied text.
- Provides OEA-style app search, category filters, pins, recent launches, a
  desktop-friendly home view and accent themes.
- Opens Windows Calculator, File Explorer, Settings, Terminal and Task Manager
  from quick tools.
- Saves pins, recent apps and appearance settings locally in the current
  Windows user's app data.

The catalogue search ranking is a JavaScript port of the Android drawer's
`OeaSearchIndex` behavior (accent-insensitive normalization, prefix/word/initial
matches, package/app-id fallback and recency tie-breaking). Host discovery and
launching live behind a separate Windows adapter; Android-only features such as
SIM, Telecom, phone widgets and Android Home roles are not represented as fake
Windows features.

## Development

Requires Node.js 22.12 or newer and npm.

```bash
cd desktop
npm ci
npx install-electron --no   # fetch Electron for this OS/architecture
npm test
npm start
```

The browser preview uses a small sample catalog; native app discovery and
launching are available in the packaged Windows app.

## Build

On Windows 10/11 x64:

```powershell
cd desktop
npm ci
npm test
npm run dist:win
```

`dist:win` creates an interactive installer and a portable `.exe` under
`../dist/desktop-installer`. The portable build runs directly; the installer
can add Start Menu and desktop shortcuts. The package is unsigned, so Windows
SmartScreen may show its normal first-run warning until the release is signed.

The `OEA Desktop Windows` GitHub Actions workflow runs the tests and packages
both `.exe` files on a Windows runner for pushes, pull requests, or a manual
workflow dispatch. Download the `oea-desktop-windows` artifact from the
completed workflow run. This Windows runner is also the supported build path
when Electron's release-asset download host is unavailable on the local machine.

## Security boundaries

The BrowserWindow enables context isolation and sandboxing and disables Node
integration. Only narrow preload methods are exposed. The main process stores discovered app
IDs and paths, validates each launch against that in-memory catalog, and ignores
arbitrary command strings from the renderer. User preferences are bounded and
written under Electron's per-user `userData` directory.
