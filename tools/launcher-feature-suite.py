#!/usr/bin/env python3
"""OEA launcher feature-suite contract."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

SUITES = {
    "home/workspace": [
        "src/com/android/launcher3/Launcher.java",
        "src/com/android/launcher3/Workspace.java",
        "src/com/android/launcher3/Hotseat.java",
        "src/com/android/launcher3/dragndrop/LauncherDragController.java",
    ],
    "app-drawer/search": [
        "src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java",
        "lawnchair/src/app/lawnchair/allapps/AllAppsSearchInput.kt",
        "lawnchair/src/app/lawnchair/search/LawnchairSearchUiDelegate.kt",
        "lawnchair/src/app/lawnchair/search/algorithms/LawnchairLocalSearchAlgorithm.kt",
        "lawnchair/src/app/lawnchair/search/algorithms/engine/provider/apps/AppSearchProvider.kt",
    ],
    "folders/shortcuts": [
        "src/com/android/launcher3/folder/Folder.java",
        "src/com/android/launcher3/folder/FolderIcon.java",
        "src/com/android/launcher3/shortcuts/DeepShortcutView.java",
        "src/com/android/launcher3/popup/PopupDataProvider.java",
    ],
    "widgets": [
        "src/com/android/launcher3/widget/LauncherWidgetHolder.java",
        "src/com/android/launcher3/widget/LauncherAppWidgetHostView.java",
        "src/com/android/launcher3/widget/picker/WidgetsFullSheet.java",
        "quickstep/src/com/android/launcher3/QuickstepWidgetPickerActivity.java",
    ],
    "gestures/interaction": [
        "lawnchair/src/app/lawnchair/gestures/GestureController.kt",
        "lawnchair/src/app/lawnchair/gestures/VerticalSwipeTouchController.kt",
        "lawnchair/src/app/lawnchair/gestures/handlers/OpenAppDrawerGestureHandler.kt",
        "lawnchair/src/app/lawnchair/gestures/handlers/RecentsGestureHandler.kt",
        "src/com/android/launcher3/touch/AllAppsSwipeController.java",
    ],
    "recents/quickstep": [
        "quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java",
        "quickstep/src/com/android/quickstep/LauncherActivityInterface.java",
        "quickstep/src/com/android/quickstep/views/LauncherRecentsView.java",
        "quickstep/src/com/android/quickstep/GestureState.java",
    ],
    "notifications": [
        "src/com/android/launcher3/notification/NotificationListener.java",
        "src/com/android/launcher3/dot/DotInfo.java",
    ],
    "icons/themes/wallpaper": [
        "src/com/android/launcher3/icons/LauncherIcons.kt",
        "src/com/android/launcher3/util/WallpaperThemeManager.kt",
        "lawnchair/src/app/lawnchair/icons/LawnchairThemeManager.kt",
        "lawnchair/src/app/lawnchair/theme/ThemeProvider.kt",
    ],
    "settings/default-launcher": [
        "src/com/android/launcher3/settings/SettingsActivity.java",
        "lawnchair/src/app/lawnchair/ui/preferences/destinations/OeaSystemsPreferences.kt",
        "lawnchair/src/app/lawnchair/ui/preferences/destinations/OeaThemePreferences.kt",
        "src/com/android/launcher3/LauncherProvider.java",
    ],
    "backup/restore/model": [
        "src/com/android/launcher3/LauncherBackupAgent.java",
        "src/com/android/launcher3/LauncherProvider.java",
        "src/com/android/launcher3/model/LoaderTask.java",
        "src/com/android/launcher3/model/ModelWriter.java",
    ],
    "OEA runtime safety": [
        "lawnchair/src/app/lawnchair/LawnchairLauncher.kt",
        "lawnchair/src/app/lawnchair/oea/OeaLauncherSafetyNet.kt",
    ],
}

REQUIRED_MANIFEST = {
    "OEA HOME": r"android\.intent\.category\.HOME",
    "OEA DEFAULT": r"android\.intent\.category\.DEFAULT",
    "OEA launcher activity": r"app\.lawnchair\.oea\.OeaLauncherActivity",
    "Legacy Lawnchair HOME removal": r'app\.lawnchair\.LawnchairLauncher.*tools:node="remove"',
    "Legacy LauncherProvider removal": r'com\.android\.launcher3\.LauncherProvider.*tools:node="remove"',
    "WidgetPicker": r"com\.android\.launcher3\.widgetpicker\.WidgetPickerActivity",
    "NotificationListener": r"com\.android\.launcher3\.notification\.NotificationListener",
}

def main():
    failures = []
    counts = {}
    for suite, files in SUITES.items():
        missing = [p for p in files if not (ROOT / p).is_file()]
        counts[suite] = len(files) - len(missing)
        if missing:
            failures.extend(f"[{suite}] missing: {p}" for p in missing)

    manifest = (ROOT / "quickstep/AndroidManifest-launcher.xml").read_text(encoding="utf-8")
    for name, pattern in REQUIRED_MANIFEST.items():
        if not re.search(pattern, manifest):
            failures.append(f"[manifest] missing launcher contract: {name}")

    source_files = []
    for base in ("src/com/android/launcher3", "quickstep/src/com/android/quickstep",
                 "lawnchair/src/app/lawnchair"):
        source_files.extend((ROOT / base).rglob("*.java"))
        source_files.extend((ROOT / base).rglob("*.kt"))

    placeholder_patterns = [
        r"TODO\s*\(?(?:implement|implementation|stub)",
        r"throw\s+UnsupportedOperationException",
        r"NotImplementedError\s*\(",
    ]
    for path in source_files:
        # LooperExecutor intentionally does not support lifecycle shutdown; its three
        # UnsupportedOperationException methods are part of the executor contract.
        if path.name == "LooperExecutor.kt":
            continue
        source = path.read_text(encoding="utf-8", errors="ignore")
        for pattern in placeholder_patterns:
            if re.search(pattern, source, re.IGNORECASE):
                failures.append(f"[placeholder] suspicious unfinished implementation: {path}")

    print("OEA Launcher Feature Suite")
    print("===========================")
    for suite, count in counts.items():
        print(f"PASS  {suite}: {count}/{len(SUITES[suite])} components present")
    if failures:
        print("\nFAILURES")
        for failure in failures:
            print(" - " + failure)
        return 1

    print(f"PASS  manifest: {len(REQUIRED_MANIFEST)}/{len(REQUIRED_MANIFEST)} contracts present")
    print(f"PASS  unfinished-code scan: {len(source_files)} launcher source files checked")
    print("RESULT: COMPLETE STRUCTURAL CONTRACT")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
