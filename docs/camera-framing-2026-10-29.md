# Camera framing and optional studio guides

- Camera2 JPEG_ORIENTATION now receives degrees, not EXIF enum values. Development reads the delivered EXIF tag to avoid double-rotating JPEGs from HALs which rotate pixels themselves.
- TextureView's implicit buffer stretch is undone before the rotate/cover transform. Crop guides and saved crops use window orientation, independent of sensor mounting. The aspect quick chip updates the guide; Full stills crop to the viewfinder shape.
- Portrait no longer blurs a horizontal band. It protects conservative detected head/torso envelopes with smooth feathering and gentle background softening. With no face it leaves the image alone. This is NOT full human segmentation: hair, hands, occlusion, profiles and multiple overlapping people still need physical-device review. FaceDetector probe widths are even as required by Android.
- Settings → Screen & Frame Studio: independently hide quick chips, zoom rail and mode rail. Settings and shutter remain reachable; pinch zoom remains available.
- Five optional composition features: Frame Echo (frozen translucent preview reference), Two-Destination Frame (square/portrait guides), Caption Nest, Edge Breathing Room and Subject Orbit. They are preview-only and never burned into the photograph. Echo refreshes on settings changes and releases its bitmap on detach. These are OEA-specific tools/names, not a claim of worldwide novelty.

## Verification

Static Kotlin checks pass. Android Gradle tests cannot run in this workspace: no Java/JAVA_HOME is installed. Added pure geometry regression coverage for portrait, landscape and square output.

Before release, build and run on physical front/rear cameras with 90°/270° sensor mounts. Verify portrait/landscape preview, saved JPEG and EXIF in another viewer, square/wide/Full crops, mirrored selfies, rotation lock, burst, video, hidden controls and guide persistence. Inspect portraits with glasses, textured backgrounds, hair, dark/light skin and no face. Privacy face blur is best effort and must not be treated as guaranteed anonymisation.

## Follow-up investigation

Additional fixes before PR:

- Preview is sized to the selected output frame instead of cropping the full-screen preview a second time in a different axis from the JPEG. Focus indicator includes the preview's layout offset; studio guides follow its bounds.
- TextureView snapshots already include its display transform: removed a second transform which distorted fallback captures and Frame Echo and leaked the intermediate bitmap.
- Save orientation now uses delivered EXIF consistently, resets orientation after developing upright pixels, and composes mirrored EXIF with the selfie preference using XOR.
- Capture shape is sampled on the UI thread at shutter time, so rotating while developing cannot change the crop.
- Near-square captures no longer skip a required crop due to a coarse 0.01 aspect tolerance. Regression tests cover near-square and portrait crop shape/centering.
- The hidden mode scroll container collapses, not just its child; obsolete band-focus state and UI message were removed. Frame Echo retries once the camera is ready.
- PR workflow now runs the camera optics JVM suite before the APK build.

Remaining verification limits: no local Android SDK/JDK or physical camera; static inspection is not a guarantee of zero issues. The five studio tools are implemented composition aids, not proven globally unprecedented inventions. Portrait remains conservative face-guided softening, not full human masking. Video uses hardware-supported recording sizes and is not post-cropped like still photos. Verify vendor HAL orientation/preview behavior and video framing on devices before release.
