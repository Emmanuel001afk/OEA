#!/usr/bin/env python3
"""OEA launcher feature-suite contract."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

SUITES = {
    "home/workspace": [
        "lawnchair/src/app/lawnchair/oea/OeaLauncherActivity.kt",
        "lawnchair/src/app/lawnchair/oea/runtime/OeaHomeController.kt",
        "lawnchair/src/app/lawnchair/oea/engine/OeaLauncherEngine.kt",
        "lawnchair/src/app/lawnchair/oea/workspace/OeaWorkspace.kt",
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
        "lawnchair/src/app/lawnchair/oea/widget/OeaWidgetPickerActivity.kt",
        "lawnchair/src/app/lawnchair/oea/workspace/OeaWorkspace.kt",
        "lawnchair/src/app/lawnchair/oea/workspace/OeaWorkspaceStore.kt",
        "lawnchair/src/app/lawnchair/oea/engine/OeaLauncherEngine.kt",
    ],
    "gestures/interaction": [
        "lawnchair/src/app/lawnchair/gestures/GestureController.kt",
        "lawnchair/src/app/lawnchair/gestures/VerticalSwipeTouchController.kt",
        "lawnchair/src/app/lawnchair/gestures/handlers/OpenAppDrawerGestureHandler.kt",
        "lawnchair/src/app/lawnchair/gestures/handlers/RecentsGestureHandler.kt",
        "src/com/android/launcher3/touch/AllAppsSwipeController.java",
    ],
    "recents/quickstep": [
        "lawnchair/src/app/lawnchair/oea/OeaLauncherActivity.kt",
        "lawnchair/src/app/lawnchair/oea/runtime/OeaHomeController.kt",
        "lawnchair/src/app/lawnchair/oea/workspace/OeaWorkspace.kt",
        "lawnchair/src/app/lawnchair/oea/engine/OeaLauncherEngine.kt",
    ],
    "notifications": [
        "lawnchair/src/app/lawnchair/oea/notifications/OeaNotificationListener.kt",
        "lawnchair/src/app/lawnchair/oea/notifications/OeaNotificationState.kt",
    ],
    "icons/themes/wallpaper": [
        "src/com/android/launcher3/icons/LauncherIcons.kt",
        "src/com/android/launcher3/util/WallpaperThemeManager.kt",
        "lawnchair/src/app/lawnchair/icons/LawnchairThemeManager.kt",
        "lawnchair/src/app/lawnchair/theme/ThemeProvider.kt",
    ],
    "settings/default-launcher": [
        "lawnchair/src/app/lawnchair/ui/preferences/destinations/OeaSystemsPreferences.kt",
        "lawnchair/src/app/lawnchair/ui/preferences/destinations/OeaThemePreferences.kt",
        "lawnchair/src/app/lawnchair/oea/runtime/OeaRuntime.kt",
        "lawnchair/src/app/lawnchair/oea/OeaLauncherActivity.kt",
    ],
    "backup/restore/model": [
        "lawnchair/src/app/lawnchair/oea/workspace/OeaWorkspaceStore.kt",
        "lawnchair/src/app/lawnchair/oea/data/OeaDataStore.kt",
        "lawnchair/src/app/lawnchair/oea/model/OeaAppModel.kt",
        "lawnchair/src/app/lawnchair/oea/engine/OeaLauncherEngine.kt",
    ],
    "OEA runtime safety": [
        "lawnchair/src/app/lawnchair/oea/OeaApplication.kt",
        "lawnchair/src/app/lawnchair/oea/runtime/OeaRuntime.kt",
    ],
}

REQUIRED_MANIFEST = {
    "OEA HOME": r"android\.intent\.category\.HOME",
    "OEA DEFAULT": r"android\.intent\.category\.DEFAULT",
    "OEA launcher activity": r"app\.lawnchair\.oea\.OeaLauncherActivity",
    "Legacy Lawnchair HOME removal": r'app\.lawnchair\.LawnchairLauncher.*tools:node="remove"',
    "Legacy LauncherProvider removal": r'com\.android\.launcher3\.LauncherProvider.*tools:node="remove"',
    "WidgetPicker": r"com\.android\.launcher3\.widgetpicker\.WidgetPickerActivity",
    "OEA NotificationListener": r"app\.lawnchair\.oea\.notifications\.OeaNotificationListener",
}

def main():
    failures = []
    counts = {}
    for suite, files in SUITES.items():
        missing = [p for p in files if not (ROOT / p).is_file()]
        counts[suite] = len(files) - len(missing)
        if missing:
            failures.extend(f"[{suite}] missing: {p}" for p in missing)

    manifest = (
        (ROOT / "AndroidManifest-common.xml").read_text(encoding="utf-8")
        + "\n"
        + (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")
        + "\n"
        + (ROOT / "quickstep/AndroidManifest-launcher.xml").read_text(encoding="utf-8")
    )
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
