# Camera review — 23 November 2026

Scope: the OEA Camera as a user meets it — `miniapps/OeaCameraApp.kt`,
`miniapps/OeaCameraEffects.kt`, `camera/*`, and the two host paths the camera
depends on (`OeaMiniAppActivity`, `OeaMediaLibrary`). Findings are code facts
from this tree; the four marked **fixed** were repaired with this document.

Verification limit, stated up front: this workspace has no JDK and no Android
SDK, so nothing here was compiled or run. `./gradlew` cannot execute. Treat
the fixes as reviewed code, not as verified behaviour on a phone.

---

## 1. Fixed

### 1.1 The first run ended on a dead card

`build()` returns the "Camera needs permission" card when `CAMERA` is not
granted. The permission result is delivered to `OeaMiniAppActivity`, which calls
`permissionHook` and then `resumeHook`; the camera set neither in that branch, so
the host called the camera helper that this screen never built. Granting the
permission left the user looking at the same card, and the only way to reach the
viewfinder was to leave the app and come back. The old "re-check on attach"
listener made it worse: if it ever fired, it removed the whole root view.

Now the permission branch installs `permissionHook` **and** `resumeHook` (the
"Open app settings" route returns through `onResume`, not a permission result)
and asks the host to re-render once the permission is really there.

### 1.2 "EV 0" did not reset the camera

`AdvancedCameraHelper.applyExposure` returned immediately when the stored
compensation was 0. The builder it writes into is the long-lived repeating
preview request, so the previous value stayed on it: the chip read "EV 0" while
the preview and every still kept the last exposure. A zero is now written
explicitly.

### 1.3 Deleting a photo deleted one of its two copies

`publish()` copies every still into `Pictures/OEA` for other galleries, and
leaves OEA's own copy in the app's private `Pictures/OEA` for editing and trash.
The sheet's **Delete** only removed the private file. The picture stayed in every
other gallery, and EMAGE's `mergeOwn` (which hides the indexed copy only while a
private file with the same name exists) stopped collapsing the pair, so the
deleted photo came back as a new item on the next scan.

Delete now removes both: the private file and, on Android 10+, the MediaStore row
`publish()` created. A touch-up pass (Night / Portrait / Document / face blur) now
also refreshes that published copy — before, the app showed the edited photo while
the gallery kept the unedited pixels.

### 1.4 Video stretched the preview for the whole clip

`startVideoRecording` set the TextureView's buffer to the *video* size and built a
new preview Surface from it, but left `previewSize` alone. `applyPreviewTransform`
had been computed for the old buffer dimensions and was not recomputed, so the
preview was distorted from the moment recording started until the still session
was rebuilt at stop. The record session now previews through the viewfinder's own
surface at its own size, which is all Camera2 requires of a preview target.

---

## 2. Confirmed, not fixed

### 2.1 Every still is quietly downsampled to ~5 MP

This is the quality defect left in the camera, and it contradicts the module's own
header. A portrait-locked phone always has `JPEG_ORIENTATION = 90`; the HAL reports
that only as an EXIF tag, so `rotationForOrientationTag` returns 90, `needsTurn` is
true, and `needsPixels` is true. The "nothing to develop: the camera's own JPEG is
the best possible file" path (`jpeg != null && !needsPixels`) is therefore
unreachable on every portrait shot. Instead the JPEG is decoded at a 2600 px
working edge, rotated, re-encoded at quality 92 and written — so a 12 MP request
lands on disk as roughly 1950×2600. The captured-photo sheet will show it.

The fix is not a bigger decode cap (a 12 MP `Bitmap` plus its rotated copy is
~96 MB and will be killed on small heaps). It is to keep the camera's own bytes and
write the orientation tag instead of turning the pixels, which also means teaching
the code that decodes directly to honour EXIF orientation: `OeaThumbnails.load`,
`decodeFullImage`, `decodeImage`, `OeaEmageViewer`, `OeaEmageEditor`. Today those
use `BitmapFactory` and would draw a tag-turned still sideways, which is exactly
why this was not changed blind. It needs a build and a device.

Mirrored selfies are the same problem for a different reason: mirroring cannot be
carried by the tag the HAL wrote, so "Mirror selfies" (on by default) always takes
the pixel path.

### 2.2 Camera teardown runs off the camera thread

`AdvancedCameraHelper` documents and mostly enforces one rule: camera objects
belong to the dedicated `OeaCamera2` thread, and every entry point hops onto it
through `onCamera`. Three paths break it — `stop()` (the mini-app's `pauseHook` and
the view's detach listener, both on the UI thread) calls `endRecording()` +
`closeCamera()` directly, `pauseNow()` does the same, and
`onSurfaceTextureDestroyed` closes the camera from the UI thread. They can race a
session callback that is mid-`createSession`, and `quitSafely()` is issued while
work may still be queued. Symptoms would be intermittent: "another app is using the
camera" on the next open, a lost clip, or a crash in a vendor HAL on pause. The
repair is a posted teardown with a bounded join before the thread is released —
ordering work that should be exercised on a device, not changed blind.

### 2.3 OEA's videos never reach the phone's gallery

Stills are inserted into `Pictures/OEA` through MediaStore. Clips are only handed to
`MediaScannerConnection` from the app-private
`Android/data/…/Movies/OEA`, a path Android 10+ does not index, so OEA's recordings
appear inside OEA and nowhere else. The asymmetry looks unintentional. Fixing it
means the same `MediaStore.Video` insert-then-`IS_PENDING=0` dance `publish()` does
for stills.

### 2.4 Small ones

- `setFlash`/`applyPro` write `AE_MODE_ON_AUTO_FLASH` / `ON_ALWAYS_FLASH` into the
  repeating **preview** request, while `createSession` documents that only TORCH
  belongs there. The behaviour matches Google's Camera2Basic sample (pre-capture
  metering needs it), so this is a contradiction between the comment and the code
  rather than a proven fault — but one of the two should change.
- Choosing QR mode without camera permission leaves the QR chip selected and the
  toast as the only feedback; the shutter then opens the scanner.
- The shutter's content description stays "Take a photo" in QR mode.

---

## 3. What to check on a device

1. Fresh install → Camera → Allow → the viewfinder appears without leaving the app.
   Then revoke in Settings, open Camera, "Open app settings", grant, return.
2. Take a shot with EV +3, set EV back to 0 by cycling the chip: the preview returns
   to neutral and the saved file is not brightened.
3. Take a photo, open the sheet, Delete: it disappears from EMAGE **and** from the
   phone's gallery, and it does not return after a rescan.
4. Apply "Brighten the shadows" in the sheet: the gallery copy shows the edit too.
5. Record a 1080p clip: the preview is not stretched while recording, and is still
   correct afterwards.
6. Report the saved file's size from the sheet — that number is finding 2.1.
