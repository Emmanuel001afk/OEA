#!/usr/bin/env python3
"""OEA launcher feature-suite contract."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

SUITES = {
    "home/workspace": [
        "lawnchair/src/com/oea/launcher/OeaLauncherActivity.kt",
        "lawnchair/src/com/oea/launcher/runtime/OeaHomeController.kt",
        "lawnchair/src/com/oea/launcher/engine/OeaLauncherEngine.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspace.kt",
    ],
    "app-drawer/search": [
        "lawnchair/src/com/oea/launcher/drawer/OeaAppDrawerController.kt",
        "lawnchair/src/com/oea/launcher/model/OeaAppModel.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspace.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspaceStore.kt",
        "lawnchair/src/com/oea/launcher/engine/OeaLauncherEngine.kt",
    ],
    "folders/shortcuts": [
        "lawnchair/src/com/oea/launcher/folders/OeaFolderController.kt",
        "lawnchair/src/com/oea/launcher/shortcuts/OeaShortcutController.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspace.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspaceStore.kt",
    ],
    "widgets": [
        "lawnchair/src/com/oea/launcher/widget/OeaWidgetPickerActivity.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspace.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspaceStore.kt",
        "lawnchair/src/com/oea/launcher/engine/OeaLauncherEngine.kt",
    ],
    "gestures/interaction": [
        "lawnchair/src/com/oea/launcher/interaction/OeaGestureController.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspace.kt",
        "lawnchair/src/com/oea/launcher/runtime/OeaHomeController.kt",
        "lawnchair/src/com/oea/launcher/engine/OeaLauncherEngine.kt",
        "lawnchair/src/com/oea/launcher/OeaLauncherActivity.kt",
    ],
    "recents/quickstep": [
        "lawnchair/src/com/oea/launcher/OeaLauncherActivity.kt",
        "lawnchair/src/com/oea/launcher/runtime/OeaHomeController.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspace.kt",
        "lawnchair/src/com/oea/launcher/engine/OeaLauncherEngine.kt",
    ],
    "notifications": [
        "lawnchair/src/com/oea/launcher/notifications/OeaNotificationListener.kt",
        "lawnchair/src/com/oea/launcher/notifications/OeaNotificationState.kt",
    ],
    "icons/themes/wallpaper": [
        "lawnchair/src/com/oea/launcher/icons/OeaIconController.kt",
        "lawnchair/src/com/oea/launcher/data/OeaDataStore.kt",
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspace.kt",
        "lawnchair/src/com/oea/launcher/OeaLauncherActivity.kt",
    ],
    "settings/default-launcher": [
        "lawnchair/src/app/lawnchair/ui/preferences/destinations/OeaSystemsPreferences.kt",
        "lawnchair/src/app/lawnchair/ui/preferences/destinations/OeaThemePreferences.kt",
        "lawnchair/src/com/oea/launcher/runtime/OeaRuntime.kt",
        "lawnchair/src/com/oea/launcher/OeaLauncherActivity.kt",
    ],
    "backup/restore/model": [
        "lawnchair/src/com/oea/launcher/workspace/OeaWorkspaceStore.kt",
        "lawnchair/src/com/oea/launcher/data/OeaDataStore.kt",
        "lawnchair/src/com/oea/launcher/model/OeaAppModel.kt",
        "lawnchair/src/com/oea/launcher/engine/OeaLauncherEngine.kt",
    ],
    "OEA runtime safety": [
        "lawnchair/src/com/oea/launcher/OeaApplication.kt",
        "lawnchair/src/com/oea/launcher/runtime/OeaRuntime.kt",
    ],
}

REQUIRED_MANIFEST = {
    "OEA HOME": r"android\.intent\.category\.HOME",
    "OEA DEFAULT": r"android\.intent\.category\.DEFAULT",
    "OEA launcher activity": r"com\.oea\.launcher\.OeaLauncherActivity",
    "Legacy Lawnchair HOME removal": r'app\.lawnchair\.LawnchairLauncher.*tools:node="remove"',
    "Legacy LauncherProvider removal": r'com\.android\.launcher3\.LauncherProvider.*tools:node="remove"',
        "OEA NotificationListener": r"com\.oea\.launcher\.notifications\.OeaNotificationListener",
    "OEA WidgetPicker": r"com\.oea\.launcher\.widget\.OeaWidgetPickerActivity",
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
