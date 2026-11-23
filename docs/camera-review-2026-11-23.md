# Camera review — 23 November 2026

Scope: the OEA Camera as a user meets it — `miniapps/OeaCameraApp.kt`,
`miniapps/OeaCameraEffects.kt`, `camera/*`, and the code the camera's pictures
flow through (`OeaMiniAppActivity`, `OeaMediaLibrary`, `OeaThumbnails`,
`OeaEmageViewer`, `OeaEmageEditor`, `OeaMiniAppWidgets`, `OeaThemeStore`).
Every finding is a code fact from this tree, and every one listed under
"What was wrong" was repaired in this change set.

Verification: the branch was compiled and tested by this repository's own
workflow (`OEA Launcher APK`, run
[45264419509](https://github.com/Emmanuel001afk/OEA/actions/runs/45264419509), PR
#148) — static check, Kotlin compile of launcher/engine/arcade, the camera
geometry JVM suite, every guard step and the APK build all green, with the
release publication correctly skipped. What that run cannot tell you is how a
vendor HAL behaves; those items are in §5 and were not observed on a phone.

---

## 1. What was wrong, and what changed

### 1.1 The first run ended on a dead card

`build()` returns the "Camera needs permission" card when `CAMERA` is not
granted. The permission result is delivered to `OeaMiniAppActivity`, which calls
`permissionHook` and then `resumeHook`; the camera set neither in that branch, so
the host called a camera helper that this screen never built. Granting the
permission left the same card on screen, and the only way to the viewfinder was to
leave the app and come back. The "re-check on attach" listener was worse: if it
ever fired, it removed the whole root view.

Now the card installs `permissionHook` **and** `resumeHook` ("Open app settings"
returns through `onResume`, not a permission result) and asks the host to
re-render once the permission is really there.

### 1.2 "EV 0" did not reset the camera

`applyExposure` returned immediately when the stored compensation was 0. The
builder it writes into is the long-lived repeating preview request, so the previous
value stayed on it: the chip read "EV 0" while the preview and every still kept the
last exposure. A zero is now written explicitly.

### 1.3 Deleting a photo deleted one of its two copies

`publish()` copies every still into `Pictures/OEA` for other galleries and leaves
OEA's own copy in the app's private `Pictures/OEA` for editing and trash. The
sheet's **Delete** removed only the private file: the picture stayed in every other
gallery, and EMAGE's merge (which hides the indexed copy only while a private file
with the same name exists) stopped collapsing the pair, so the deleted photo came
back as a new item on the next scan. Delete now removes both copies on Android 10+.

### 1.4 A touch-up pass left the gallery showing the old photo

"Brighten the shadows", "Blur every face", "Soften the background" and "Flatten
into a scan" rewrite the private file in place. The published copy kept the
unedited pixels, so OEA showed one picture and every other gallery another. The
published copy is now rewritten in step. The pass also writes at 3000 px instead of
2400, matching what the capture pipeline itself decodes, so a touch-up on a
full-resolution still no longer costs a quarter of its pixels.

### 1.5 Video stretched the preview for the whole clip

`startVideoRecording` set the TextureView's buffer to the *video* size and built a
new preview Surface from it, but left `previewSize` alone. `applyPreviewTransform`
had been computed for the old buffer dimensions and was not recomputed, so the
preview was distorted from the moment recording started until the still session was
rebuilt at stop. The record session now previews through the viewfinder's own
surface at its own size, which is all Camera2 requires of a preview target.

### 1.6 Every still was quietly downsampled to ~5 MP

The module header promises "the camera's own JPEG" at up to 12 MP, but that path
was unreachable on a portrait-locked camera. `JPEG_ORIENTATION = 90` arrives only
as an EXIF tag, so `needsTurn` was true, `needsPixels` was true, and the "nothing to
develop" branch (`jpeg != null && !needsPixels`) could never run. Every photo was
decoded at a 2600 px working edge, rotated, re-encoded at quality 92 and written:
a 12 MP request landed on disk as roughly 1950×2600.

There were two faults, and both are fixed:

* the aspect comparison was made between the request and the buffer's *sideways*
  shape, so a 4:3 sensor frame could never answer a 3:4 portrait request. It is now
  made in the buffer's own orientation (`OeaCameraOptics.bufferMatchesRequest`,
  with a regression test);
* turning the pixels was treated as "work". A rotation that exists only in the
  JPEG's own tag now costs nothing: the delivered bytes are written untouched and
  the tag is kept, so the file is full resolution.

Mirrored selfies still take the pixel path — mirroring is not expressible in the
tag the HAL wrote — so "Mirror selfies" (on by default) still costs a turn, and
with it the decoder's working size.

### 1.7 Camera teardown ran off the camera thread

`AdvancedCameraHelper` documents one rule: camera objects belong to the dedicated
`OeaCamera2` thread, and entry points hop onto it through `onCamera`. Three paths
broke it — `stop()` (the mini-app's `pauseHook` and the view's detach listener, both
on the UI thread) called `endRecording()` + `closeCamera()` directly, `pauseNow()`
did the same, and `onSurfaceTextureDestroyed` closed the camera from the UI thread.
They could race a session callback that was mid-`createSession`, and `quitSafely()`
was issued while work could still be queued: an intermittent "another app is using
the camera" after a pause, or a lost clip.

Now:

* a pause posts the teardown onto the camera thread and **keeps the thread**, so a
  resume's open is queued behind the pause's close and the two cannot overlap;
* only a detach retires the thread, and the teardown retires it after closing;
* `start()`, `onSurfaceTextureAvailable` and `onSurfaceTextureDestroyed` are queued
  through `onCamera` for the same reason;
* `closeCamera` is guarded, because a destroyed surface can still arrive on the UI
  thread while a queued teardown runs.

### 1.8 OEA's videos never reached the phone's gallery

Stills were inserted into `Pictures/OEA`. Clips were only handed to
`MediaScannerConnection` from the app-private `Android/data/…/Movies/OEA`, a path
Android 10+ does not index, so a recording existed inside OEA and nowhere else
while a photo taken a second later appeared everywhere. `publishVideo` now does the
same insert-then-`IS_PENDING=0` dance against `MediaStore.Video` into `Movies/OEA`.

### 1.9 Small ones

* Choosing QR mode without camera permission left the QR chip selected with a toast
  as the only feedback. `openQrScanner` reports failure and the previous mode is put
  back.
* The shutter's content description said "Take a photo" in QR mode.
* `createSession` claimed "AUTO/ON flash is never a repeating-preview decision"
  while `setFlash` sends exactly that. The behaviour matches Google's Camera2
  sample (the LED only fires for the still capture; the preview request is what
  lets AE meter for it) and is kept; the comments now say so instead of the
  opposite.

---

## 2. Where the EXIF orientation is honoured

A tag-turned still is only correct if everything that draws it turns it too. That
is now one object, `ui/OeaPhotoDecode.kt`, and these are its callers:

| Path | File |
| --- | --- |
| Gallery grid tiles | `OeaThumbnails.decodeImage` |
| Full-screen viewer, camera sheet preview | `OeaThumbnails.decodeFullImage` |
| Wallpaper from a photo | `OeaEmageViewer.applyWallpaper` |
| Editor source | `OeaEmageEditor` |
| Home-screen camera widget | `OeaMiniAppWidgets.thumbnail` |
| Theme image / wallpaper engine | `OeaThemeStore.decodeScaled` |

OEA's own files also bypass `ContentResolver.loadThumbnail`: a FileProvider hands
out bytes exactly as stored, so a provider thumbnail would either ignore the tag or
apply it twice. MediaStore media keeps the pre-rotated provider thumbnail.

Pictures whose pixels were already turned (an older OEA photo, a filtered shot,
anything from another camera) carry tag 1 and pass through untouched, which is why
this change cannot rotate existing pictures.

---

## 3. What this pass did not change

* **Night, Portrait, Document, filters and HDR still run at 1600 px** inside the
  capture pipeline (`WORK_MAX`). That is a deliberate shutter-latency trade, not an
  oversight, but it does mean a mode photo is not a 12 MP photo.
* **Mirrored selfies cost a turn** (§1.6).
* **Every still and clip on disk is still duplicated** — one app-private copy for
  editing and trash, one published copy for other galleries. That is the existing
  contract, and 1.3/1.4 now keep the pair honest instead of half-deleting it.
* **Mirrored EXIF tags are approximated horizontally.** Tags 2/5/7 are mirrored
  left-to-right (the only mirror the camera itself writes); tag 4 rotates.
* **QR "From a picture" and the games' QR scan** ignore EXIF. A code is found either
  way.

---

## 4. Verification

* **CI, on this branch**: static check, `Compile Kotlin (launcher, engine,
  arcade)`, `Camera geometry regression tests`, `Run engine and game unit tests`,
  all seventeen guard steps and the APK build — green
  ([run 45264419509](https://github.com/Emmanuel001afk/OEA/actions/runs/45264419509)).
  The `Verify the camera and the torch` step gained guards for each fix above, so
  the wiring cannot quietly regress.
* **Locally, before pushing**: every changed file parsed under the Kotlin 2.4 front
  end (no syntax errors, only unresolved Android/AndroidX symbols — this workspace
  has no SDK); `OeaCameraOpticsTest` was executed on a JVM against stub JUnit —
  18/18 green, including the new
  `aTurnedJpegThatAlreadyHasTheShapeNeedsNoPixelWork`; the camera guard step was
  extracted from the workflow and executed (90 lines); `tools/check-kotlin.py`
  passed; the workflow YAML still parses.

What none of that covers is the phone: preview orientation and flash behaviour on
a particular HAL, timing on pause/resume, and the resolution figure in the
captured-photo sheet. That is §5.

---

## 5. What to check on a device

1. Fresh install → Camera → Allow → the viewfinder appears without leaving the app;
   then revoke in Settings, open Camera, "Open app settings", grant, return.
2. Take a shot with EV +3, cycle the chip back to EV 0: preview and saved file both
   return to neutral.
3. Take a portrait photo; the sheet's size line should read about `3000×4000`, not
   `1950×2600`. Open it in EMAGE (grid, full screen, editor) and on the home widget:
   upright in all four.
4. Delete from the sheet: gone from EMAGE **and** the phone's gallery, and not back
   after a rescan. Apply "Brighten the shadows": the gallery copy shows the edit.
5. Record 1080p: preview not stretched while recording; the clip appears in the
   phone's gallery as well as in OEA.
6. Pause during a recording and return: no "another app is using the camera", clip
   intact. Repeat with the torch on, with the app switcher, and with the screen
   locked mid-capture.
7. Set a camera photo as wallpaper: upright.
8. Flash: AUTO in a dim room should not strobe while framing. If a phone does
   strobe, keep `AE_MODE_ON` on the repeating request and send the flash decision
   only with the still (§1.9).
