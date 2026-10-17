# Every surface, one at a time — mini-app repair and effect pass (17 Oct 2026)

The complaint was specific: each app had its own odd problem. A grid that only
found some of the pictures. A video player that threw a black rectangle and a
system seekbar at a film. Buttons that did nothing. Labels that described a
feature the code never had. So this round went app by app, reading each file,
and either made the thing work or stopped saying it worked.

Nothing here adds a dependency: still no ML kit, no network SDK, no image
library, no `androidx.media`.

## EMAGE — depth and the whole library

* The album and library grids paginate with `LIMIT n OFFSET m` instead of
  stopping at the first 300 items, so a 12,000-picture phone shows all 12,000
  and the scroll position survives a refresh.
* Trash restore, the viewer's zoom, rotation and favourites all write back
  through Media Store, and the "simulation" wording that made honest code sound
  like a demo is gone.
* Bundled artwork now renders through `OeaArtwork`, because reading a vector XML
  with `openRawResource` and handing it to a decoder produced nothing.

## VIP — the player, in full

`OeaVipPlayerActivity` is now a real player rather than a surface with controls:
poster until first frame, `what==3`-driven scrubbing, buffered bar, double-tap
seek, an equaliser and reverb sheet over its own audio session, subtitles from
sidecar files (`.srt .vtt .ass .ssa`, remembered per clip, offset and size in
its own settings), a queue with a pill and a sheet, resume-where-you-left-off,
background audio through a media notification with skip/previous icons, Picture
in Picture with remote actions, and a "watch together" share that copies the
current timestamp. SurfaceView, because decoder-side rotation is the tested
path on this API floor.

## Camera — the modes now do something

* **Night** (`OeaCameraEffects`) is a real tone curve: shadows gain up to 2.45×,
  mid-tones a little, highlights untouched, then a contrast curve on the way out
  so the lift does not go milky, and a 3×3 luma blur blended only where the
  frame is dark, because colour noise lives in the shadows.
* **Portrait** blurs by distance from a horizontal band, radius widening as you
  leave it, plus a vignette and a small saturation push on the sharp part. The
  band is the line you tapped to focus — `focusAt` already set AF regions, and
  now also sets `focusBandY`, which the pass reads. There is no depth sensor in
  an app, and the settings note says so instead of implying one.
* **Blur faces** runs `android.media.FaceDetector` over a 640 px RGB_565 copy,
  scales the boxes back up and smudges each one. It is a setting (every shot) and
  a shutter-sheet action (this shot), and the same three effects can be re-run on
  a file already on disk.
* The shutter is off the main thread: the frame is copied, then filtered,
  processed and saved on a single developer thread, with only the result posted
  back. A 12 MP capture used to freeze the UI; now it does not.
* **Time-lapse** gets `setCaptureRate(6)` plus an AE target of 6 fps, so the
  sensor stops delivering 30 frames a second and the file really plays back fast.
* **QR** no longer opens a browser at `api.qrserver.com` when the phone has no
  scanner installed. Reading a code should not mean uploading the picture —
  it now says, in words, that nothing answered.
* **Pro**'s four bars were scenery. They now write into the live repeating
  request — sensitivity (which switches AE off, or the loop just overwrites it),
  exposure compensation in the camera's own index range, white balance from the
  available modes list, and lens focus in dioptres — and the caption above them
  is generated from this camera's characteristics, so it reads "ISO locked by the
  camera" on a device that refuses rather than pretending.
* **HDR** was a preference nobody could set. It is a switch now, called
  "Contrast push (HDR-style)", with the truth next to it: one frame, no second
  exposure, because Camera2 hands the app one image at a time.
* **Burst** existed only in a comment. `1–10` frames per tap, 340 ms apart, each
  developed and saved on its own, only the last one showing the sheet.

## Calendar — backup that is a backup

The export sheet is "Backup and import": copy JSON to the clipboard, share a
hand-built `.ics` (VEVENT per event, escaped, `DTSTART;VALUE=DATE` for all-day,
UTC stamps), share the JSON, or open a document and read either format back
through `ACTION_OPEN_DOCUMENT`, deduped by day, title and time, parsed on a
worker. The moon phase of the selected day sits in the Events header.
ACTION_INSERT-created device events are normalised instead of inheriting an
internal category.

## Messaging — the picker that picks

The contact button opened a picker whose result nobody read. It now goes through
the host's result hook (`REQUEST_CONTACT`), reads the number and drops it into
the To field, and clears on sheet dismissal. The claim that OEA could import an
SMS archive was replaced with the reason it cannot: only the default messaging
app may write that store.

## Calculator — a converter, and rates you own

`OeaUnits` holds ten families as factors against one base unit each (length,
mass, area, volume, speed, time, data, pressure, energy), temperature as an
offset computation rather than a fudged ratio, and a live UI: family chips,
from/to pickers, swap, send-to-display, per-day sheet-free conversion. The
five-entry map that printed "offline rates" is gone; currency is now rates the
user sets, a base to choose, and a screen that says plainly that nobody fetched
them.

## Console — the amplifier amplifies

`OeaAmplifier` reads 48 kHz mono PCM from the microphone, scales it by a live
gain (0.5×–6×), soft-clips instead of wrapping, and writes it to an
`AudioTrack` on a max-priority thread, with a limiter that pulls gain back when
it hears a sustained full-scale squeal. It stops when the screen is not in
front — Android takes the microphone back — and both the note and the EQ note
stopped promising a system-wide bass knob and a background hearing aid.

## Weather — one honest screen

Units convert wind and rain as well as temperature. The hourly rail starts at
this hour rather than midnight, carries rain probability, and the daily list
carries totals, gusts and peak UV. Air quality comes from the air-quality
endpoint, best effort, and is simply absent when that call fails. The footer
shows when the reading was taken, not when the screen was drawn. "Severe weather
placeholder" became **OEA's own advisories**, computed from these numbers and
labelled as not being official warnings, because there is no warning feed this
build reads.

## Voice — no invented wake word

The "offline keyword spotting fallback" does not exist and never did. In its
place: a typed command line that runs the identical parser as the chips and the
recogniser, so a phone with no speech service still gets working commands, and a
note that explains why no model is bundled and what continuous listening here
actually is.

## Games — three apps, each with its own front screen

The aim sliders were shown to every standalone game, including Chess, where
they do nothing. `GameDimension` now gates "Controls & aim" to the games you look
around inside, and each app overrides `controlLegend` / `keyLegend` with its own
gestures: Endless Days lists the four swipes and the WASD/gamepad mirrors, Chess
lists tap-then-square, drag, undo-by-retap, promotion and the 3D orbit, Send Off
lists the two-thumb layout, jump/crouch, ADS and the corner cluster. A legend
that is true beats a slider that is inert.

## What is deliberately *not* claimed

* No QR decoder, no OCR, no on-device transcription, no depth map, no live
  currency, no national weather alerts, no wake word, no background mic, no
  system-wide equaliser. Each of those now has a sentence in its own app saying
  what OEA does instead, rather than a control that pretends.
