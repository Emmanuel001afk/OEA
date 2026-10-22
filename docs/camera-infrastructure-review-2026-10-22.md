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

**Not compiled, not run.** The environment this was written in has no JDK, no
Gradle, no `kotlinc` and no Android SDK, and downloading a toolchain failed
(network access to the release asset host was refused). Everything above was
checked by reading the tree — every API used exists in it, every helper the screen
calls is defined, braces/parens balance, and imports were reconciled — but a
compiler has **not** seen this code. Treat the first build as the real test.

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
* **Effects run on the unrotated pixels**, so the Portrait band is measured in
  buffer space; on a portrait phone the band is horizontal in the file, which
  matches the preview only after rotation is applied by a viewer. Practically
  invisible for a band blur, but it is a real detail.
* **`TEMPLATE_STILL_CAPTURE` fallback**: if a camera refuses the JPEG reader, the
  shutter falls back to the preview snapshot at view resolution instead of failing.
* **No burst review UI**: burst frames are saved individually and the last one
  opens in the sheet.
* **The widget thumbnail is read synchronously** in `onUpdate`; it is a small
  decode and a short list, but a phone with thousands of photos would benefit from
  a metadata index.
