# Camera infrastructure review — 22 October 2026

OEA already had two camera implementations and they were not the same one. The
engine shipped a working, lifecycle-safe Camera2 scanner
(`oea-engine/.../net/qr/Qr.kt`), the games hub used it, and the Camera app — the
part users actually think of as "the camera" — was a second, weaker
implementation built on the preview texture. Meanwhile the torch was implemented
five more times in five other places, each with `cameraIdList.firstOrNull()`.

This document is the comparison, the list of what was broken, what changed, how
it was checked, and what is still limited. It deliberately does not claim a build
or a device run: neither is available in the environment this was written in
(see [Verification](#verification)). Every claim below is either a code fact you
can check in this tree, or is marked as unverified.

---

## 1. What the camera is made of

| Layer | Where | What it does |
| --- | --- | --- |
| Entry | `OeaCameraActivity : OeaMiniAppActivity` (`miniapps/OeaMiniAppActivities.kt`) | Registered as the `camera` mini-app (`OeaMiniApps.kt`), `Style.FullBleed`, also answers `IMAGE_CAPTURE` / `VIDEO_CAPTURE` |
| Screen | `miniapps/OeaCameraApp.kt` | View hierarchy, settings, capture pipeline, Camera2 helper |
| Pixel work | `miniapps/OeaCameraEffects.kt` | Night, Portrait, Document, face blur |
| Chrome | `camera/OeaCameraChrome.kt` (**new**) | Scrims, horizon level, recording pill, zoom readout, timer countdown, haptics |
| Math | `camera/OeaCameraOptics.kt` (**new**) | Rotation, EXIF orientation, aspect choice, cover scale, crop, focus slider |
| Torch | `camera/OeaTorch.kt` (**new**) | One owner for the LED, one source of truth for its state |
| Thumbnails | `ui/OeaThumbnails.kt` | Cache + correct-size decodes for the last-photo button and the saved-photo sheet |
| Reference pipeline | `oea-engine/.../net/qr/Qr.kt` (`QrScannerView`, `QrCodes`) | The engine's own Camera2 preview + YUV analysis + ZXing |
| Widget | `widgets/OeaMiniAppWidgets.kt` (`OeaCameraWidgetProvider`), `res/layout/oea_widget_camera.xml` | Home-screen entry |

---

## 2. What was actually wrong

These are code facts from the tree as it was before this change, not opinions.

### 2.1 The preview was sideways and stretched

`AdvancedCameraHelper` created a `SurfaceTexture` surface and set a 1280×720
buffer, then never called `TextureView.setTransform`. Consequences, all of them
visible on a real portrait phone:

* the sensor buffer was drawn unrotated, so the world was 90° over;
* it was stretched to the window's shape, so faces were the wrong width;
* the front camera was not mirrored.

The engine's `QrScannerView` already did this correctly
(`applyTransform`, sensor/display difference, cover scale). The camera now uses
the same maths, extracted into `OeaCameraOptics` so it can be tested on a JVM.

### 2.2 A "photo" was a screenshot of the preview

The shutter read `textureView.bitmap` and saved that. So every still was
preview-resolution (1280×720-ish), already distorted by 2.1, and carried no EXIF.
This is the single biggest quality defect in the whole app.

Now: `TEMPLATE_STILL_CAPTURE` into an `ImageReader(JPEG)` at the largest
aspect-matching size under a 12 MP ceiling, `JPEG_ORIENTATION` written from the
sensor/display rotation, `JPEG_QUALITY 94`. The texture snapshot remains only as
a fallback for a camera that refuses the capture (and, in that case, the snapshot
is now the *transformed* one, so it is at least upright).

### 2.3 `saveBitmap` published the file twice, and the EXIF was empty

The old path inserted a `MediaStore` row **and** ran a scanner over the same
path, and never wrote orientation, so a portrait photo displayed rotated in other
apps. Now the file is written once, EXIF is written before publishing
(orientation, make/model, timestamp, and GPS when the "Location tag" switch is
on **and** location permission is granted), and only then is it published once.

### 2.4 Tap-to-focus froze the preview

`focusAt` called `session.capture(...)` with a builder that carried the AF
trigger and nothing else. A one-shot capture request replaces nothing, but the
code also never re-armed the repeating request, so the preview could stall on
devices that treat the next frame as the last. The metering rectangle was also
placed in view pixels rather than sensor coordinates.

Now: the trigger is sent on a copy of the live preview request, the AF rectangle
is converted from view space through the preview rotation, the zoom crop and the
front-camera mirror, and after 1.2 s the trigger is cleared and continuous focus
restored.

### 2.5 The zoom crop ignored the lens

`setZoom` built a crop rectangle from the full active array without consulting
`SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`, while the UI hard-coded 1×/2×/4× plus a
slider to 8×. On a phone with 4× maximum the slider lied; on some devices the
crop rect also ignored the active-array offset.

Now the crop is clamped to the lens's own maximum and centred on the active
array; presets and slider share one state, and pinch updates both.

### 2.6 The Pro focus slider was inverted

`focusPercent = 100` produced distance `0` dioptres, i.e. infinity — dragging
towards "close" pushed the lens to the horizon. Unsupported hardware was not
checked at all. Now `OeaCameraOptics.focusDioptres` maps 0 → auto and 100 → the
lens's near point, and the caption under the bars is generated from
`CameraCharacteristics` (`proStatus()`), so the screen says what *this* lens can
do: "ISO 100–3200 · EV -2 to 2 · white balance: 6 modes · no manual focus".

### 2.7 Pro settings leaked out of Pro mode

Setting manual ISO switches `CONTROL_AE_MODE` off. Leaving Pro mode did not
switch it back, so every later photo in Photo/Video stayed manually exposed.
Fixed: leaving Pro resets the four sliders and hands AE back to the camera.

### 2.8 Settings that were stored and never used

`KEY_SHUTTER_SOUND`, `KEY_LOCATION`, `KEY_ASPECT`, `KEY_HDR`, the burst count and
`AspectMode.RATIO` were read or written but changed no behaviour. Now:

* shutter sound plays a real `MediaActionSound.SHUTTER_CLICK` (and stays silent
  when the switch is off);
* location writes GPS into EXIF when permission allows;
* aspect picks the preview **and** still size and crops the saved file
  (`OeaCameraOptics.cropRect`) — a crop, never a stretch;
* "Contrast push" is labelled honestly: one frame, contrast lifted, no second
  exposure, because Camera2 gives the app one image per request;
* burst writes N frames, each saved separately, 340 ms apart, with a per-capture
  watchdog so a camera that accepts a request and never answers cannot hang the
  shutter.

### 2.9 `stop()` deleted the recording

`onHostPaused` ended the recorder **and deleted the in-progress file**. A phone
call during a recording lost the whole clip. `endRecording(keepFile = true)` now
returns the file, and the screen publishes it (scanner + toast) even when the
user never pressed stop.

### 2.10 Video had no orientation and no audio

`setOrientationHint` was never called, so a portrait clip played on its side
forever. `RECORD_AUDIO` was never requested, so every clip was mute with no
explanation. Both fixed; the bit rate now follows the resolution; time-lapse uses
the documented `setCaptureRate` + fixed-FPS-range pair and plays back 5× fast.

### 2.11 QR mode needed an app that no phone has

QR mode launched `Intent.ACTION_VIEW` with `zxing://scan`-style extras — that is
the standalone *Barcode Scanner* client, not a system capability. On a stock
phone it either threw or showed a "no app found" dialog. Meanwhile the engine had
a complete scanner on the same classpath, already used by the games hub for
invite codes.

Now QR mode opens OEA's own `QrScannerView`: Camera2 preview, YUV analysis, ZXing
decode on a worker, torch toggle, and a "From a picture" path that decodes a
chosen image through the same `QrCodes.decode`. Results are shown in an OEA sheet
with Copy / Open link (only for http(s)) / Search, and are copyable to the
clipboard. Nothing is uploaded anywhere.

### 2.12 Document mode handed off to the system camera

It launched `ACTION_IMAGE_CAPTURE` if the phone had one, which meant a document
photo was just a photo plus a different toast. Now Document mode captures through
the same still pipeline and runs a real flattening pass: luminance, black/white
points from the frame's own 2nd/98th percentile histogram, a mild concave curve to
keep ink while pushing paper to white (`OeaCameraEffects.document`).

### 2.13 Leaving the screen could leave the sensor open

There was no `onSurfaceTextureDestroyed`, no listener removal and no resume path.
Now the camera is closed on `onSurfaceTextureDestroyed`, on view detach, and on the
host's `onPause` (via `pauseHook`), and reopened on `onResume` (`resumeHook`) and
on view attach. `onDisconnected` and `onError` are surfaced as a tappable card
("Another app took the camera · tap to try again") instead of a black rectangle.

### 2.14 The widget promised controls it never drew

The camera widget's text read "Pro controls · Filters · Grid · Timer · Video · QR
· Doc · Last photo thumbnail + EMAGE integration" while rendering one paragraph
of text on `oea_widget_notes`, and its dedicated layout
(`res/layout/oea_widget_camera.xml`) had no root id and reused `notes_content`
while the provider asked for `widget_root` / `notes_title`.

Now `oea_widget_camera.xml` is a real layout (thumbnail, title, mode line, hint),
the provider renders the newest `Pictures/OEA` photo as a ≤128 px bitmap
(`setImageViewBitmap`, small enough to cross a Binder call) and opens EMAGE from
the thumbnail and the camera everywhere else. Saving a photo calls
`OeaWidgetStudio.refreshWidgets(context, "camera")`.

### 2.15 Five torches, none of which agreed

| Place | Before | Now |
| --- | --- | --- |
| `gesture/OeaGestures` | `cameraIdList.first()`, state remembered in its own prefs | `OeaTorch.toggle` |
| `power/OeaQuickControls` | `cameraIdList.first()`, local `torchOn` boolean | `OeaTorch` (`available` / `toggle`), label reads real state |
| `voice/OeaSpeechEngine` | `cameraIdList.first()`, on/off guessed from `contains("on")` | `OeaTorch`, whole-word on/off with "off" winning, toggle when neither is said |
| `phone/OeaInCallService` | picked the first camera with a flash | `OeaTorch.set` on each pulse |
| `OeaCameraApp` | torch inside the preview request | preview request in Torch mode while the camera is open, `OeaTorch` when it is not |

`OeaTorch` chooses the first **rear** camera that actually has a flash, falls back
to any camera with a flash, and registers a `CameraManager.TorchCallback` so
`isOn()` reflects the hardware — including a change made from Android's own Quick
Settings tile. `choose()` is plain data and is unit-tested.

---

## 3. What was deliberately *not* changed

* **No second camera stack.** The engine's `QrScannerView` is reused, not
  rewritten, and camera code was not moved into `oea-engine` (it needs launcher
  UI types, and the engine must stay launcher-independent).
* **No depth features.** There is no depth sensor and no ML model in OEA, so
  Portrait stays a focus-band blur that says so, and Night stays a tone curve.
* **No fake HDR.** "Contrast push" is named after what it does and explains the
  one-frame limit in the settings subtitle.
* **No relaxed mini-app ids.** `OeaMiniApps.open(host, "emage")` keeps its strict
  unknown-id behaviour.

---

## 4. Verification

**Built, not device-tested.** The environment this was written in had no JDK, no
Gradle, no `kotlinc` and no Android SDK, and downloading a toolchain failed, so
the first version of this work went to CI without a compiler having seen it. CI
then found three real compile errors — an `AspectMode` passed where a ratio was
expected, `CameraDevice.ERROR_*` qualified through the wrong class (they live on
`CameraDevice.StateCallback`), and a test that compared `IntArray`s by identity —
all fixed in follow-up commits.

The current head **builds**: `:oea:assembleRelease` succeeds, the `Oea Launcher
APK` workflow is green, the APK is produced and uploaded, and the launcher's own
JVM unit tests run in that workflow (24 tests across launcher, engine and games,
including the two camera test classes below). That is a compile and unit-test
result — the camera code itself has **not** been run against a camera. The
checklist below is what a phone should confirm.

What *is* testable without a phone is the arithmetic and the torch decision, and
both now have JVM tests:

* `oea/src/test/java/com/oea/launcher/camera/OeaCameraOpticsTest.kt` — rotation
  for rear/front at every display rotation (always a quarter turn), EXIF tag
  mapping, aspect size choice with and without a pixel budget, cover scale,
  crop rects (wide/tall/matching), the focus-slider direction, and rubbish input.
* `oea/src/test/java/com/oea/launcher/camera/OeaTorchChoiceTest.kt` — rear-with-
  flash preferred, rear-without-LED skipped, any-flash fallback, no-flash → no
  torch.

### Device checklist

1. **Upright preview** — open the camera in portrait; the horizon should be
   horizontal and a face the right width. Rotate the phone: it stays upright.
   Switch to the front camera: mirrored, still upright.
2. **A real photo** — take a picture, then check its dimensions in EMAGE or in the
   file list: it should be the sensor's size (not 1280×720) and open upright in a
   non-OEA gallery.
3. **Tap-to-focus** — tap off-centre; the square appears where you tapped and the
   preview keeps moving afterwards (it must not freeze).
4. **Zoom** — pinch past the phone's maximum; the image stops instead of cropping
   into a grey border. The 1×/2×/4× chips and the slider agree.
5. **Pro** — drag ISO to 100%: the picture gets brighter/noisier and the caption
   names the range this phone reports. Leave Pro: the picture returns to auto
   exposure on the next shot.
6. **Flash** — Auto fires when dark, On always, Torch lights the LED while the
   viewfinder is open. Lock the phone, then unlock: the LED is off.
7. **Timer + burst** — 3 s timer, burst 3: three files, 340 ms apart.
8. **Aspect** — 16:9 then 4:3: the preview reframes (no stretch) and the saved
   files have different shapes.
9. **Video** — record 5 s in portrait, play it in another app: upright, and with
   sound if you granted the microphone. Time-lapse: 30 s of wall clock should
   produce a short, fast file.
10. **Leave mid-recording** — press Home while recording; a clip should survive in
    `Movies/OEA` and appear in the gallery.
11. **QR** — scan a code with the phone offline; no app download is prompted. Try
    "From a picture" with a screenshot of a code.
12. **Document** — photograph a printed page in Document mode: white paper, dark
    ink, colour gone.
13. **Torch everywhere** — turn the flashlight on from a Home gesture, then open
    Quick controls: the row says "On". Turn it off, then ask the voice assistant
    to turn it off: it stays off and the reply is "Flashlight off".
14. **Widget** — add the Camera widget: it shows the newest photo; tapping the
    picture opens EMAGE, tapping the text opens the camera; take a photo and the
    widget updates.
15. **Camera busy** — open the camera, then open another app's camera; OEA shows
    "Another app took the camera · tap to try again" and recovers on tap.
16. **Zoom that admits its limit** — on a 2× sensor, the 4× chip is dimmed and does
    nothing, the slider stops at 2×, and the readout says "This lens stops here"
    if you pinch past it.
17. **Timer** — press the shutter with a 3 s timer: a large 3, 2, 1 counts down over
    the viewfinder, the shutter is disabled while it runs, and tapping the number
    cancels without taking a picture.
18. **Recording HUD** — start a clip: the shutter becomes a stop square, a pill at
    the top counts up, the mode chips dim, and leaving the screen stops and saves.
19. **Level and mirror** — turn on the horizon level in settings: a line across the
    still modes turns green within a degree of level and tilts with the phone. Turn
    "Mirror selfies" off, take a front-camera photo, and the file is flipped
    relative to the preview instead of matching it.
20. **Last shot** — take a photo: the shutter row's left slot becomes that photo; a
    3-frame burst updates it three times. Delete it from EMAGE, return: the slot
    falls back to the library icon.
21. **Crop guide** — choose 1:1: the viewfinder shades the part of the frame that
    will be cropped and outlines what will be kept.

---

## 5. Remaining limitations (stated, not hidden)

* **No exposure bracketing.** HDR-style processing is a single frame; true
  multi-exposure HDR needs several capture requests and alignment, which is a
  project of its own.
* **Portrait is not depth.** The blur band follows the focus tap; it does not
  measure distance.
* **Face blur is `FaceDetector`**, which finds frontal faces on a small copy. It
  is a real privacy pass, not a guarantee; profile shots can be missed.
* **Video recording does not rebuild the still analysis path** while recording —
  the JPEG reader is detached for the duration and recreated afterwards.
* **Front-camera video is not mirrored** (stills are, via the preview transform;
  `setOrientationHint` handles only rotation).
* **Effects no longer run on the unrotated pixels** (fixed below, §6): the still
  is turned upright before any pass, and the orientation tag is reset to 1 so a
  viewer does not turn it twice.
* **`TEMPLATE_STILL_CAPTURE` fallback**: if a camera refuses the JPEG reader, the
  shutter falls back to the preview snapshot at view resolution instead of failing.
* **Burst review is the last frame only**: the shutter row's left slot shows the
  newest file and a burst saves each frame individually, but there is no grid to
  compare them.
* **The widget thumbnail is read synchronously** in `onUpdate`; it is a small
  decode and a short list, but a phone with thousands of photos would benefit from
  a metadata index.

---

## 6. The viewfinder pass (second round, same day)

The first round fixed what was *broken*. This round is about what was *missing or
doubtful* in the camera as a screen: the controls said less than they knew, and a
few conveniences a camera is expected to have were not there at all. Everything
below is wired to real behaviour — no control exists that does not do something.

### 6.1 New chrome, in `camera/OeaCameraChrome.kt`

A small file of drawn parts, each independent of the camera session so one can
fail without taking the viewfinder down:

| Part | What it is | Wired to |
| --- | --- | --- |
| `OeaCameraChrome.scrim(forTop)` | gradient behind the top and bottom bands | added under both bands, so white controls stay readable against a bright sky |
| `OeaLevelView` | horizon line, tilts with the phone, turns green inside 1° | lives only in Photo / Portrait / Night / Pro / Document; its `OrientationEventListener` is enabled only while it is visible and attached |
| `OeaRecordingPill` | blinking dot + `0:07` clock | started by `startRecording`, stopped by `stopRecording`, by an error callback, and by detach |
| `OeaZoomBadge` | `2.0×` readout | shown for ~1.1 s after any zoom change (pinch, chip, slider) |
| `OeaCountdownOverlay` | the self-timer as a number | driven by `OeaCountdownOverlay.start(seconds)`; `onFinished` runs the burst, `onCancelled` re-enables the shutter |
| `OeaCameraChrome.buzz` | 12 ms haptic | shutter press, mode change, camera switch, timer cancel |

### 6.2 What the controls now say

* **Zoom is lens-aware.** `AdvancedCameraHelper.maxZoomFor(front)` reads
  `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` for the chosen camera *without opening it*, so
  the chip row is built against the real ceiling: chips past it are dimmed and
  disabled, the slider's maximum is `(ceiling − 1) × 100`, and a pinch is clamped to
  the same number. Switching camera re-reads the ceiling and pulls a stale zoom back
  inside it. The old row offered 1×/2×/4× and a slider to 8× on every phone, which
  on a 2× sensor made half the row decorative.
* **The shutter shows state.** Press it and it scales; in video it is a red dot, and
  while recording it becomes the stop square every camera uses. A second tap while
  a capture is in flight is ignored (`capturing` / `shotsOutstanding`), which also
  covers a burst frame that has not been handed back yet.
* **Bursts run.** `burstCount` frames are taken `BURST_STEP_MS` apart; the shutter
  is dimmed for the whole run and restored when the last frame returns.
* **Saving is visible.** A "Saving…" pill sits under the recording pill between the
  capture callback and the finished file, so a slow effect pass no longer looks like
  a dropped shutter.
* **The camera switch disappears** when the phone has only one lens, instead of
  offering a button that toasts a refusal.
* **The shutter row previews the last frame** (`LastShotButton`): a real thumbnail of
  the newest file that counts a burst and falls back to the library icon when there
  is nothing to show.
* **The saved-photo sheet tells the truth**: the shape the picture will be seen in
  (EXIF-aware), its size in MB, plus "Open EMAGE" and "Delete" — the delete also
  refreshes the widgets.
* **The top chips are icons plus their value** (aspect, flash, timer, grid, filter)
  with real content descriptions, and each mode chip carries its own icon and the
  sentence that describes the mode.
* **The viewfinder draws the crop** it will actually keep: `GridOverlayView` shades
  the dead band for any aspect other than the sensor's own, so 1:1 is never a
  surprise after the fact.
* **The permission screen is a screen**, not a black column: icon, explanation, and
  — once Android has stopped offering its own dialog
  (`KEY_PERMISSION_ASKED` + `shouldShowRequestPermissionRationale`) — an
  "Open app settings" button instead of a "Allow" button that would do nothing.

### 6.3 Pixel correctness this round

* **Stills are turned upright before they are developed.** `saveFrame` reads
  `JPEG_ORIENTATION`, turns (and mirrors) the pixels with `turnPixels`, then runs
  night / portrait / document / filter / face blur / crop on upright pixels, and
  finally writes orientation `1`. The visible consequence: a Portrait shot's blur
  band now follows the face in the file as well as in the preview, and a "4:3"
  request no longer slices the top and bottom off a portrait photo (`displayAspect`
  inverts the ratio for a quarter turn).
* **A selfie is saved the way the preview showed it.** The front preview is mirrored;
  the sensor JPEG is not. `saveFrame(..., mirror = mirrorSelfie)` mirrors the pixels
  when the new "Mirror selfies" preference is on (default on), so the file and the
  preview agree instead of being opposites.
* **`OeaCameraEffects.applyToFile` uses the same rule.** For the capture-once path
  (a picture chosen from elsewhere), the file's pixels are turned upright using its
  EXIF tag, the pass runs, and the tag is reset to `ORIENTATION_NORMAL` so no viewer
  turns it a second time.
* **One filter matrix.** `toneMatrix(filter)` is the single definition used by both
  the saved file and the viewfinder tint; the tint's colour and strength are
  *derived* from that matrix (a mid grey pushed through it) rather than hand-picked.

### 6.4 Preferences added

Both have a row in the camera settings sheet, and both take effect immediately:

* **Mirror selfies** (`KEY_MIRROR_SELFIE`, default on) — as above.
* **Horizon level** (`KEY_LEVEL`, default on) — turning it off hides the level at
  once, without leaving the camera.

### 6.5 Verification for this round

* `tools/check-kotlin.py` — brackets balanced, `OeaKit` calls resolve, OEA symbols
  declared, manifests consistent (222 sources).
* `OeaCameraOpticsTest` — two new JVM tests:
  `exifTagsBecomeTheRotationTheyDescribe` (all eight EXIF tags → rotation + mirror)
  and `croppingTurnsWithUprightPixels` (`displayAspect(4/3, 90) == 3/4`, and 0 stays
  0).
* The CI step "Verify the camera and the torch" now also asserts this round's
  wiring: `OeaLevelView`, `OeaRecordingPill`, `OeaZoomBadge`,
  `OeaCountdownOverlay`, `OeaCameraChrome.scrim(`, `maxZoomFor`, `zoomCeiling`,
  `mirror = mirrorSelfie`, `turnPixels`, `rotationForOrientationTag` in both the
  camera and the effects file, `setAspect(`, `toneMatrix(`, `setMirrorSelfies` and
  `setLevelEnabled`.
* Still **not device-tested**: no Android device or emulator in the environment.
  The checklist in §4 items 16–21 is what to run first.
