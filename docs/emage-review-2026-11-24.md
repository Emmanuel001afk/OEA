# EMAGE review — 24 November 2026

EMAGE is the biggest mini-app in the launcher and the last one to get this treatment. Most
of it was already good — the virtualised grid, the content observer, the picker contract,
the trash, the zoom view and its session handling were each built or fixed in earlier
passes, and §2 records what was checked and left alone.

What was left is a different class of fault: **the numbers a photo app prints**, and the
one place it cannot open the photos it is shown. A details sheet saying `1/59 s`,
`50/1 mm`, "Auto" for a manual white balance, `6,52400, 3,37900` for a coordinate; an
editor that answers *"Could not load picture"* for a 48-megapixel photo. None of that
crashes, so none of it was ever noticed.

Everything in §1 changed here. §2 is what was checked and already right, §3 what is
deliberately still missing, §4 how it is verified, §5 what only a phone can settle.

The arithmetic and the wording now live in `miniapps/OeaEmageFacts.kt` (one object, 25
tests), which is what made these fixable and pinnable.

---

## 1. What was wrong, and what changed

### 1.1 The editor could not open the photos modern phones take

`OeaEmageEditor` decoded with:

```kotlin
var sample = 1
while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 4096) sample *= 2
```

The intent is a 4096-pixel ceiling. The arithmetic halves *before* comparing, so the
loop only starts sampling when the **half**-size still exceeds 4096 — which makes the
real ceiling **8192**. An 8000×6000 photo (48 MP — a mid-range phone from 2021) therefore
took `sample = 1`: an ARGB_8888 bitmap of 192 MB. Android's per-app heap is 128–512 MB,
so that decode throws `OutOfMemoryError`, `runCatching` swallows it, and the editor says
**"Could not load picture"** for exactly the photos people want to edit. The status also
never said the edit was made at reduced size.

**Changed.** `OeaEmageFacts.sampleSize` decides the `inSampleSize` for every decode, with
an explicit mode:

* **fit** (the editor, a full-screen view) keeps the *long* edge at or below the target —
  8000 px at a 3000-px target is `sample = 4`, a 12 MB bitmap;
* **cover** (a square tile, a wallpaper) keeps the *short* edge at or above the target, or
  the tile is upscaled and soft.

The editor uses `EDIT_EDGE = 3000` (~12 MB at 4:3, twice what any phone needs for a
copy) and now says what it is doing: *"Crop area ready at 2000×1500…"*. The wallpaper
path had the same rule written a third time; it uses the helper with `cover = true`.

### 1.2 One sixtieth of a second was printed as `1/59 s`

```kotlin
exposure.toDoubleOrNull()?.let { it < 1 } == true -> "1/${(1.0 / exposure.toDouble()).toInt()} s"
```

`1 / 0.016666…` is `59.9999…`, and `toInt()` truncates, so the most common shutter speed
there is came out as **1/59 s**. Truncation also invented `1/7999 s` for 1/8000.

Worse, most files store the speed as the *string* `1/250`, which `toDoubleOrNull()` cannot
read — so that branch fell through to `else -> exposure` and printed `1/250` with **no
unit at all**: `Exposure 1/250 • f/1.8 • ISO 100`, where only one of the three has a unit.

**Changed.** `OeaEmageFacts.shutter` rounds the reciprocal (1/60, 1/125, 1/8000), keeps a
stored fraction as a fraction with its unit (`1/250 s`), and writes slow speeds the way a
camera does (`0.5 s`, `2.5 s`, `30 s`).

### 1.3 A focal length of `50/1 mm`

EXIF stores `FocalLength` as a rational: `50/1`, `35/10`. The sheet appended `" mm"` to the
raw string, so a picture taken at 50 mm read **`50/1 mm`** and one at 3.5 mm read
`35/10 mm`.

**Changed.** `focalLength(equivalent35mm, raw)` parses the rational (preferring the 35 mm
equivalent, as a photographer reads), so it says `50 mm` and `3.5 mm`. The aperture had a
smaller version of the same fault — `f/1.8` from `18/10` is now parsed rather than echoed.

### 1.4 A manual white balance was labelled "Auto"

```kotlin
when (exif.getAttributeInt(TAG_WHITE_BALANCE, -1)) {
    1 -> "Auto"; 2 -> "Fluorescent"; 3 -> "Tungsten"; 4 -> "Daylight"; 9 -> "Fluorescent"
```

`TAG_WHITE_BALANCE` has exactly two values: **0 = automatic, 1 = manual**. The table above
is the *light source* tag's vocabulary, so a photo with a manual white balance was shown
as **"Auto"** and the other branches could never match anything. (The field was also never
displayed: `Photo.whiteBalance` was computed and then dropped on the floor.)

**Changed.** `whiteBalance(code)` returns Auto / Manual / —, and the sheet has the row.

### 1.5 `ISO —` on the phones taking the picture

The sheet read only `TAG_ISO_SPEED_RATINGS`, the EXIF 2.2 field. Modern cameras (every
Android camera I can name) write `PhotographicSensitivity` instead, so the ISO of a photo
taken on the phone showing it was **"—"**.

**Changed.** The reader falls back to `PhotographicSensitivity` (by tag name, because the
constant is spelled differently in the framework and AndroidX readers). The exposure line
is now built by `exposureLine()`, which **drops the fields the file does not carry** instead
of printing `— • — • —`, and says "No camera settings recorded" when there are none — the
screenshot and scanned-document case.

### 1.6 Coordinates that no map accepts, and an altitude in the phone's language

```kotlin
"%.5f, %.5f".format(latitude, longitude)
```

No locale, so on a German, French, Spanish, Portuguese or francophone-African phone the
details sheet showed **`6,52400, 3,37900`** — four numbers where two were meant — and the
"Coordinates copied" row put exactly that on the clipboard, where a maps app cannot use it.
Altitude had the same fault in a smaller way (`"%.0f m".format(…)`).

**Changed.** Coordinates are always `Locale.US` with five decimals (about a metre),
because a coordinate is *data* someone copies. Altitude is prose and follows the device
language, via one documented rule: **data uses a dot, prose follows the phone**. That rule
is what `OeaEmageFacts` is built around, and it is tested in both directions under
`Locale.GERMANY` (`1,4 MB` beside `6.52400, 3.37900`).

### 1.7 Sizes that said "1024 KB" and "0 KB"

`formatSize` chose its unit from the *unrounded* value and then rounded inside it:

* 1 048 500 bytes → `1024 KB` (a kilobyte value a megabyte wide);
* 512 bytes → `0 KB` (a file that appears to be empty);
* 1023 bytes → `1 KB` (a file shown as larger than it is).

**Changed.** `OeaEmageFacts.size` frames by the **rounded** value, so 1 048 500 bytes is
`1.0 MB`, anything under a kilobyte is counted in bytes (`512 B`), and nothing ever reads
`1024 KB` or `1024.0 MB`. `OeaMediaLibrary.formatSize` delegates, so every album header,
trash row and sheet in the app inherits the fix.

### 1.8 Multi-spot metering had no word

`metering` handled 1, 2, 3 and 5; EXIF also defines 4 (multi-spot) and 6 (partial), which
fell through to "—". **Changed.** The table is complete, including 255 ("Other").

### 1.9 A slideshow of nothing divided by zero

`Slideshow.schedule()` posts `index = (index + 1) % items.size`. With an empty pool —
reachable if the pool is emptied while the menu is open, or from a caller that passes an
empty list — that is `ArithmeticException: divide by zero` on the main thread, i.e. a
launcher crash whose message names the slideshow. **Changed.** `show()` refuses an empty
pool with a toast.

---

## 2. What was already right

* **The grid.** A virtualised `RecyclerView`, square-to-tall tiles at the device density,
  payload-based selection updates, and a decode rule that is genuinely correct for a
  cover-cropped tile (`long/sample >= target` on both edges). I checked all four sampling
  sites in the app; this one and the viewer's are right for their jobs, and only the
  editor's and the wallpaper's were routed through the new helper.
* **The picker contract.** A `ACTION_PICK`/`GET_CONTENT` host returning a URI and never
  opening an internal screen, including the multi-select path.
* **The viewer's zoom.** `OeaZoomImageView` keeps the point under the fingers pinned
  (`tx' = c − (c − tx)·grow`), clamps the pan to the picture, and hands a horizontal swipe
  back to the pager only at the edge. The `FIT_CENTER` regression that unhooked the matrix
  is still guarded by a comment in `Pages`.
* **Delete.** The Android 11+ `createDeleteRequest` flow, with a pending record that
  survives the system dialog being dismissed.
* **EXIF reading itself** — `ExifInterface` on an input stream, GPS rationals parsed by
  hand, `MediaExtractor` for clips — is honest work that only needed its *output* fixed.

---

## 3. What this pass did not change, and why

* **The editor's tone model** (brightness as a ±50 offset, contrast 50–150 %) is coarse
  but predictable, and changing its units would change every saved edit's look. It is
  documented in the sheet's labels instead.
* **No non-destructive edit history.** The editor saves a new copy by design ("never
  overwrites the source"), and an edit stack would be a different feature, not a fix.
* **No face/place recognition, no cloud anything.** Unchanged, and stated in the app.
* **Editing at a reduced size** is deliberate: a 3000-px copy is what the editor can hold
  twice over. It is now *visible* ("Crop area ready at 2000×1500") instead of silent.
* **Video and audio detail sheets** share `OeaMediaProbe` but not this pass; their
  `frameRateLabel` and bitrate text were left exactly as they were.

---

## 4. Verification

* **`OeaEmageFactsTest` — 25 methods**: the 48 MP decode stays under 40 MB; fit keeps the
  long edge inside the target and cover keeps the short edge outside it (including the
  panorama that needs no sampling at all); the two modes disagree where they should;
  nonsense sizes decode whole; `512 B` / `1023 B` / `1 KB` / `1.0 MB` / `2.34 GB`; German
  `1,4 MB`; `12.0 MP`; `1/60 s`, `1/125 s`, `1/8000 s`, `1/250 s`, `0.5 s`, `2.5 s`, `30 s`
  and every dash; `f/1.8` from `18/10`; `50 mm` and `3.5 mm` from rationals; the exposure
  line with 3, 2, 1 and 0 fields; `ISO 100`; coordinates with a dot under `Locale.GERMANY`
  (and a negative latitude); altitude `12 m` / `-4 m` / null; every white-balance and
  metering code, including the ones that used to fall through.
* **`Verify the EMAGE work` — 30 checks** in CI: the facts object and its signatures, the
  editor routing through `sampleSize` with the `(sample * 2) >= 4096` line gone, the
  wallpaper using `cover = true`, the probe using the shared shutter / aperture /
  coordinates / white balance / metering, the `PhotographicSensitivity` fallback, the
  sheet's `exposureLabel` and white-balance row, no second `megapixels` helper, `formatSize`
  delegating with no `"%.0f KB".format(kb)`, and the slideshow's empty-pool guard.
* `tools/check-kotlin.py` — `STATIC CHECK OK — 312 sources`.

No Android SDK or Kotlin compiler here, so CI compiles it; the expectations were
cross-checked against a Python model of the same arithmetic (rounding, unit framing and
both sampling modes) before being written down, and every guard was run locally first.

## 5. What to check on a device

1. **A 48 MP photo in the editor.** It must open, and the message must say the resolution
   it is editing at. Save, then open the copy: it is a new file in `Pictures/EMAGE` and the
   original is untouched.
2. **The details sheet on a photo from the phone's own camera.** Exposure reads
   `1/xx s • f/x • ISO n` with units on every field it has; white balance is present and
   says Auto or Manual; the focal length has no `/` in it.
3. **The details sheet on a screenshot or a scan.** It must say "No camera settings
   recorded", not three em dashes.
4. **Coordinates on a German phone.** Still `6.52400, 3.37900`, and "Coordinates copied"
   pastes into a maps app as a valid pair.
5. **A small file.** Anything under 1 KB reads in bytes; a 1 048 500-byte file reads
   `1.0 MB`, never `1024 KB`; album headers and trash rows agree with the sheet.
6. **Sizes on a German phone.** `1,4 MB` — prose follows the phone.
7. **Wallpaper.** Apply a large photo as wallpaper: it still fills the screen and does not
   look soft.
8. **Slideshow with an empty album.** It must refuse with a message rather than crash.
