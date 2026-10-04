#!/usr/bin/env python3
"""Static completeness gate for the OEA/Lawnchair launcher build.

This is intentionally a structural gate, not a replacement for Android UI testing. It prevents
large launcher subsystems from disappearing from a refactor and verifies that the manifest still
exposes the components required for a normal launcher build. OEA recovery is intentionally a
normal helper rather than a manifest-started Android component.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_FILES = [
    "AndroidManifest-common.xml",
    "AndroidManifest.xml",
    "src/com/android/launcher3/Launcher.java",
    "src/com/android/launcher3/LauncherProvider.java",
    "src/com/android/launcher3/Workspace.java",
    "src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java",
    "src/com/android/launcher3/folder/Folder.java",
    "src/com/android/launcher3/widget/LauncherWidgetHolder.java",
    "src/com/android/launcher3/widget/LauncherAppWidgetHostView.java",
    "src/com/android/launcher3/notification/NotificationListener.java",
    "src/com/android/launcher3/settings/SettingsActivity.java",
    "quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java",
    "lawnchair/src/app/lawnchair/LawnchairLauncher.kt",
    "lawnchair/src/app/lawnchair/LawnchairApp.kt",
    "lawnchair/src/app/lawnchair/oea/OeaLauncherSafetyNet.kt",
]

MANIFEST_REQUIREMENTS = [
    ("HOME category", r'<category\s+android:name="android.intent.category.HOME"'),
    ("DEFAULT category", r'<category\s+android:name="android.intent.category.DEFAULT"'),
    ("launcher activity", r'android:name="app\.lawnchair\.LawnchairLauncher"'),
    ("launcher provider", r'android:name="com\.android\.launcher3\.LauncherProvider"'),
    ("widget picker", r'android:name="com\.android\.launcher3\.widgetpicker\.WidgetPickerActivity"'),
    ("notification listener", r'android:name="com\.android\.launcher3\.notification\.NotificationListener"'),
    ("backup agent", r'android:backupAgent="com\.android\.launcher3\.LauncherBackupAgent"'),
]


def main() -> int:
    failures = []
    for rel in REQUIRED_FILES:
        if not (ROOT / rel).is_file():
            failures.append(f"missing required launcher component: {rel}")

    common = (ROOT / "AndroidManifest-common.xml").read_text(encoding="utf-8")
    specific = (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")
    merged = common + "\n" + specific
    for name, pattern in MANIFEST_REQUIREMENTS:
        if not re.search(pattern, merged):
            failures.append(f"manifest requirement missing: {name}")

    if failures:
        print("OEA launcher completeness gate: FAILED")
        for failure in failures:
            print(f" - {failure}")
        return 1

    print(f"OEA launcher completeness gate: PASS ({len(REQUIRED_FILES)} required components checked)")
    print(f"Manifest contract: PASS ({len(MANIFEST_REQUIREMENTS)} requirements checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
