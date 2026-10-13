# OEA Launcher

OEA is an independent Android launcher and Android-skin layer built around its own engine and workspace.

## Architecture

The active launcher path is:

`OeaLauncherActivity → OeaHomeController → OeaRuntime → OeaLauncherEngine → OeaWorkspace`

OEA owns the home surface, app model, workspace persistence, drawer, dock, folders, shortcuts, gestures, themes, and system-feature integrations. The engine is initialized lazily, loads the app catalogue away from the UI thread, retains the last good workspace on failure, and publishes its state for future engine-owned features.

See [Launcher engine and live activities](docs/launcher-engine-and-live-activities.md) for the lifecycle boundaries, stability rules, Dynamic Island-style notification design, platform constraints, and verification checklist.

## OEA Systems

- App freezer using Android Device Policy package suspension when OEA has the required authority.
- Call blocker using Android's call-screening role with exact/prefix/suffix rules and contact exceptions.
- Split-pair launcher using Android multi-task/adjacent activity flags; Android controls the final split presentation.
- Game Boost monitoring selected games with usage access, optional DND handling, and an optional session overlay.
- Launcher navigation remains integrated with Android's own Home and Recents/Overview surfaces.

## Home and app drawer

- Persistent home pages, dock, folders and drag/drop.
- App search and All Apps search.
- Grid, vertical-list and horizontal All Apps layouts.
- Long-press app actions, hiding and app-freezer access.
- Wallpaper-derived launcher theme colors.
- Native Android launcher shortcuts.
- Home-only live-activity capsule for charging, supported calls, media sessions, and compatible notification actions (requires notification access for app notification content).

## Independence

OEA does not build, run, or import Launcher3/Lawnchair source code. The repository contains only the standalone `oea` application module and OEA-owned launcher code.

## Build

`./gradlew :oea:assembleRelease`

The resulting application ID is `com.oea.launcher`.
