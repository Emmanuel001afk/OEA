# Projection package compile check

The sandbox used for the 2026-10-30 floating-app-projection work had no JDK,
no Android SDK and no Gradle cache, but it could reach GitHub and PyPI. This
directory reproduces the type-level verification that was done there before
pushing (the GitHub Actions "OEA Launcher APK" workflow remains the
authoritative build check).

What it does: compiles the whole `multitask/projection` package, the modified
`multitask/OeaFloatingWindowService.kt`, `OeaFloatingWindows.kt` and
`OeaMultitaskLauncher.kt` with a standalone Kotlin compiler against a real
AOSP-built `android-33` jar, plus small signature-compatible stubs for the
project-internal classes those files touch (`R`, `OeaAppInfo`, `OeaDataStore`,
`OeaKit`). The stubs are NOT part of the app build — they only exist so the
checked files can be compiled in isolation.

Toolchain used (all fetched from allowed hosts):

- JDK runtime: `jdk4py` from PyPI (Temurin 25 runtime)
- Kotlin compiler: `kotlin-compiler` 2.4.21 npm package (official JetBrains
  distribution mirrored to npm)
- `android-33/android.jar`: AOSP-built platform jar from
  `Reginer/aosp-android-jar` (includes hidden APIs; only public APIs are used)

Run:

```bash
KOTLINC=/path/to/kotlinc/package/bin/kotlinc
ANDROID33=/path/to/android-33.jar
$KOTLINC -cp "$ANDROID33" -d /tmp/oecout \
  oea/src/main/java/com/oea/launcher/multitask/*.kt \
  oea/src/main/java/com/oea/launcher/multitask/projection/*.kt \
  tools/projection-compilecheck/stubs/com/oea/launcher/R.kt \
  tools/projection-compilecheck/stubs/com/oea/launcher/model/OeaAppModel.kt \
  tools/projection-compilecheck/stubs/com/oea/launcher/data/OeaDataStore.kt \
  tools/projection-compilecheck/stubs/com/oea/launcher/ui/OeaKit.kt
```

A settings-side harness (the new Settings functions copied verbatim into an
Activity subclass) was compiled the same way during development; it lives
outside the repository because it duplicates source.
