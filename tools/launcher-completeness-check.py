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
    ("OEA HOME category", r'<category\s+android:name="android.intent.category.HOME"'),
    ("OEA DEFAULT category", r'<category\s+android:name="android.intent.category.DEFAULT"'),
    ("OEA launcher activity", r'android:name="app\.lawnchair\.oea\.OeaLauncherActivity"'),
    ("legacy Lawnchair HOME removal", r'<activity android:name="app\.lawnchair\.LawnchairLauncher"[^>]*tools:node="remove"'),
    ("legacy LauncherProvider removal", r'<provider android:name="com\.android\.launcher3\.LauncherProvider"[^>]*tools:node="remove"'),
    ("launcher package visibility", r"<queries>[\s\S]*android.intent.action.MAIN[\s\S]*android.intent.category.LAUNCHER[\s\S]*</queries>"),
    ("HOME task isolation", r'android:taskAffinity=""'),
]


def main() -> int:
    failures = []
    for rel in REQUIRED_FILES:
        if not (ROOT / rel).is_file():
            failures.append(f"missing required launcher component: {rel}")

    build_gradle = (ROOT / "build.gradle").read_text(encoding="utf-8")
    identity_requirements = [
        ("GitHub application ID", r"""applicationId\s+['"]com\.oea\.launcher['"]"""),
        ("nightly application ID", r"""applicationId\s+['"]com\.oea\.launcher\.nightly['"]"""),
        ("Play application ID", r"""applicationId\s+['"]com\.oea\.launcher\.play['"]"""),
    ]
    for name, pattern in identity_requirements:
        if not re.search(pattern, build_gradle):
            failures.append(f"application identity requirement missing: {name}")
    forbidden_identity = "com.aria." + "launcher"
    if forbidden_identity in build_gradle:
        failures.append("Aria application identity must not remain in OEA build configuration")

    # The application ID is an Android identity boundary. Do not allow an Aria
    # package identifier to reappear in any checked-in OEA source/config file.
    # This catches regressions outside build.gradle (manifests, scripts, CI, etc.).
    ignored_dirs = {".git", ".gradle", "build", ".idea"}
    text_suffixes = {
        ".gradle", ".gradle.kts", ".kt", ".java", ".xml", ".properties",
        ".json", ".toml", ".yaml", ".yml", ".py", ".sh",
    }
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in text_suffixes:
            continue
        if any(part in ignored_dirs for part in path.parts):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if forbidden_identity in content:
            failures.append(
                f"Aria application identity must not remain in OEA source/config: {path.relative_to(ROOT)}"
            )

    oea = (ROOT / "quickstep/AndroidManifest-launcher.xml").read_text(encoding="utf-8")
    for name, pattern in MANIFEST_REQUIREMENTS:
        if not re.search(pattern, oea):
            failures.append(f"OEA manifest requirement missing: {name}")

    application_tag = oea.split("<application", 1)[1].split(">", 1)[0]
    if 'android:theme="@style/Theme.OeaHome"' in application_tag:
        failures.append("OEA theme must be applied to OeaLauncherActivity, not the application")

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
