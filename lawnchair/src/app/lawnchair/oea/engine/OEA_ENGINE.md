# OEA Engine

OEA is being migrated toward an independent launcher engine instead of making Launcher3's internal model the OEA feature boundary.

## Ownership

The engine owns:

- installed launchable application inventory through Android LauncherApps
- profile-aware application identity
- deterministic local app search and ranking
- app launching through LauncherApps
- independent workspace persistence
- package/profile change refresh
- engine lifecycle and health state

Launcher3/Lawnchair is currently only the compatibility HOME surface.

## Design rules

1. The engine must not call Launcher3 LauncherModel, BgDataModel, ModelWriter, or Launcher3's database.
2. Android platform APIs remain the authority for installed apps, users/profiles, widgets, shortcuts and HOME role behavior.
3. Search is local and deterministic first. Network and AI providers are optional layers.
4. A broken optional provider must never prevent HOME from rendering.
5. Workspace persistence is versionable and independent so migration can be tested without destroying the existing launcher database.
6. UI migration happens only after the engine passes independent inventory/search/workspace/launch tests.

## Planned engine modules

- AppCatalog
- SearchEngine + provider registry
- WorkspaceStore + migration/versioning
- FolderStore
- WidgetHostController
- ShortcutController
- IconResolver/cache
- GestureRouter
- LauncherState
- ProfileController
- ThemeController
- Backup/restore
- Diagnostics/recovery

## Research-derived requirements

Open-source launcher implementations consistently separate application discovery/search from the home surface, persist home placement independently, and treat widgets, folders, gestures and search as separate subsystems. Android's LauncherApps API is the correct platform inventory/launch boundary for a third-party launcher. Android 15+ private-space support also makes profile-aware inventory and HOME-role behavior mandatory for a complete modern launcher.

Nova Launcher is treated as a behavioral benchmark, not a source-code dependency because its implementation is proprietary.

## Migration stages

1. Engine foundation: inventory, search, launch, workspace persistence, lifecycle and recovery.
2. Independent home state: pages, folders, dock and placement mutations.
3. Independent rendering: app grid and drawer powered by engine state.
4. Widgets and shortcuts.
5. Gestures, themes, icon pipeline and backup/restore.
6. Replace the Launcher3 workspace/model dependency only after each stage is verified on-device.
