# VIP review — 23 November 2026

Scope: the OEA mini-app people open with a video — `miniapps/OeaVipPlayerActivity.kt`
(2,300 lines), `OeaVideoApp.kt` (the VIP library: albums, playlists, continue-watching),
`OeaVipPip.kt` (OEA's own floating window), `OeaVipAudioService.kt` (background
audio), `OeaVipAudioFx.kt`, `OeaSubtitles.kt`, `OeaVipStore.kt`, `OeaVipSheet.kt`,
`OeaVipDetails.kt`, and the manifest entries the app is reached through.

Every finding is a code fact from this tree, cited by file and line. Everything in
§1 was repaired in this change set; §2 lists what was checked and deliberately left
alone because it is already right; §3 lists what this pass did not change and why;
§5 lists what only a phone can settle.

---

## 1. What was wrong, and what changed

### 1.1 Scrub-preview frames were opened on the UI thread and released from another one

`startRetriever()` built a `MediaMetadataRetriever` and called `setDataSource` on it
inline — once per clip, from `onPrepared`, i.e. on the main thread at the exact
moment playback starts. On a large MP4 that is a file open plus a container parse
inside a frame, and on a slow card or a content provider it is a visible stall.
The same object was then read from the `PREVIEW` executor while `openEntry()` and
`onDestroy()` released it from the main thread: a release landing mid-grab loses the
preview at best and faults inside native code at worst.

`MediaMetadataRetriever` is now owned end to end by one dedicated thread
(`miniapps/OeaVipFrames.kt`, new). `open()`, `frame()` and `close()` all run on that
thread; the player only passes a URI, a target size and a callback. The callback is
posted back to the main thread and suppressed once the window is closed, so a grab
that finishes after the player is gone cannot resurrect a dead view
(`OeaVipFrames.kt:40`, `:61`, `:83`, `:90`). The player keeps the existing
`previewSeq` token to decide whether the bitmap is still wanted
(`OeaVipPlayerActivity.kt:917`), and `dp()` is now measured on the UI thread instead
of inside the worker (`:922`). The `PREVIEW` executor is gone; `OeaVipFrames` owns
its lifecycle per clip (`:802-803`, `:2262`).

### 1.2 A subtitle probe could paint over the next clip

`loadSidecar()` posted a `PROBE` job and applied the result with only an
`isDestroyed` guard. Moving to the next clip during that job (a big file, a subtitle
behind a content provider) meant the previous clip's track was written into
`subtitleTrack` and drawn over the clip now playing — wrong dialogue, no way to know
why. The fix is the token the preview path already used: `subtitleSeq` is incremented
per load and checked before anything is applied (`OeaVipPlayerActivity.kt:944`,
`:967`).

### 1.3 Audio focus was held until the player was destroyed

`requestFocus()` takes `AUDIOFOCUS_GAIN`; the only abandon in the file was in
`onDestroy`. So pausing, finishing a clip, or leaving VIP with the player paused left
VIP holding gain while silent: music that had ducked or paused for the video stayed
down until the whole player was torn down. `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` also
only restored volume on a `GAIN` that might never arrive.

`releaseFocus()` abandons the request and clears the ducked volume, and focus is now
driven by play state rather than by the activity's death: taken when playback starts,
given back when it stops (`OeaVipPlayerActivity.kt:1608`, `:1054`, `:1058`, `:2266`).
`requestFocus()` also no longer keeps a refused request on the books — a refusal used
to leave `focusRequest` non-null, so VIP never asked again for the rest of the clip
(`:1594-1598`).

### 1.4 Background audio had no notification at all on Android 13+

`POST_NOTIFICATIONS` is declared in the manifest and requested by Clock, Game Boost
and Settings — but not by VIP. Android 13+ silently drops a notification the app was
never allowed to post, and that notification *is* the transport controls: the audio
kept playing while the shade, the lock screen and the headset had nothing to press.
VIP now asks at the moment the user turns "Audio keeps playing" on, not at launch —
a permission dialog on a video's first frame is noise
(`OeaVipPlayerActivity.kt:1440`, `:1988`).

### 1.5 The expanded notification lost the album line

`InboxStyle().addLine(title)` in a player notification shows the title a second time
in the expanded shade and replaces the artist line. It is now a `BigTextStyle` of
title + artist (`OeaVipAudioService.kt:213-221`).

### 1.6 A picture-in-picture refusal that returned `false` did nothing

`enterPictureInPictureMode` reports refusal by returning `false` as often as by
throwing — an OEM that hides the feature, a task that has already left the screen.
The code handled only the throw, so on those devices "Picture in picture" closed the
sheet and nothing else happened. A `false` now falls through to OEA's own window,
exactly like a missing `FEATURE_PICTURE_IN_PICTURE`
(`OeaVipPlayerActivity.kt:1419-1429`).

### 1.7 Subtitle parsing turned a cue number into dialogue, and deleted dialogue that was a number

Three separate faults in one small reader:

* **A byte-order mark became text.** `.srt` files written on Windows start with
  U+FEFF. The mark was not stripped, so `"1"` no longer parsed as a number and the
  cue index was painted as the first line of the first subtitle. `parse()` now strips
  it before anything else (`OeaSubtitles.kt:77-80`).
* **Any line that was a number was discarded.** The body filter removed every line
  where `toIntOrNull() != null`, which deletes real dialogue — "1984", "42", a
  countdown. The filter now uses position instead of content: everything before the
  timing line is a cue number, everything after it is dialogue
  (`OeaSubtitles.kt:96-112`).
* **WebVTT cue identifiers were drawn.** WebVTT allows a name line above the timing;
  everything except the timing line was treated as caption text, so `cue-1` appeared
  on screen (`OeaSubtitles.kt:117-131`).

`OeaSubtitlesTest` (`oea/src/test/java/com/oea/launcher/miniapps/`) pins all of it:
cue numbers, BOM, numeric dialogue, multi-line cues, WebVTT identifiers, millisecond
and short-form timestamps, inline markup, offset shifting with its floor at zero, the
cue lookup, silence between cues, and the refusals (ASS and empty files).

### 1.8 A paused player kept repainting a position that had not moved

`tick()` runs every 240 ms for the whole life of the activity. It formatted two
clock strings and searched the cue list on every beat, including while paused, when
the position cannot change. The labels now repaint when the position moves, not when
the timer fires (`OeaVipPlayerActivity.kt:1152-1165`). Seeking while paused still
repaints, because the position changes.

### 1.9 Small one: a foreground service that stops itself still owes a notification

`publish(null)` and the `ACTION_STOP` branch both call `stopSelf()` without ever
calling `startForeground`. A service started with `startForegroundService` must post a
notification within five seconds even when its first job is to stop, and Android 8
answers otherwise with `RemoteServiceException: Context.startForegroundService() did
not then call Service.startForeground()`. Nothing in VIP reaches that path today
(the notification's buttons arrive as ordinary `startService`, and `update()` always
carries a status), so this is a hazard removed rather than a crash observed:
`ensureForeground()` posts the placeholder on entry to `onStartCommand`, and the real
notification replaces it on the same tick (`OeaVipAudioService.kt:90-92`, `:128-146`).

---

## 2. What was already right (checked, not changed)

* **Background audio is legally and correctly declared**: the service carries
  `android:foregroundServiceType="mediaPlayback"` (`AndroidManifest.xml:888`) and the
  matching `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission (`:40`), and `publish()`
  passes `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` on API 29+.
* **Platform PiP is declared properly** on the player:
  `supportsPictureInPicture="true"`, `resizeableActivity="true"`, and a
  `configChanges` list that already covers `orientation|screenSize|screenLayout|
  smallestScreenSize` — so entering PiP does not recreate the activity and kill
  playback (`AndroidManifest.xml:380-391`).
* **Dragging the scrubber does not seek per frame.** `OeaScrubBar`'s `onSeek` only
  calls `seekTo()` on the final callback; while the finger is down it shows a preview
  frame instead (`OeaVipPlayerActivity.kt:624-640`). This was the first thing I
  suspected and it is not a defect.
* **Resume and continue-watching are keyed on the same URI the player is handed.**
  `VipScreen.play()` passes `OeaMediaLibrary.externalUri(item)`, and the player saves
  progress under that same URI; every `Item` in the library is built either from
  `ContentUris.withAppendedId` (`OeaMediaLibrary.kt:521`) or from
  `FileProvider.getUriForFile` (`:583-587`), and `externalUri` returns `item.uri`
  unchanged for both (`:930-946`) — `getUriForFile` is deterministic for the same
  file and root. The two keys therefore agree today, which is why "Continue watching"
  resumes where the player stopped.
* **Audio effects degrade instead of lying.** Every `Equalizer`/`LoudnessEnhancer`/
  `PresetReverb` construction and query is wrapped, and the sheet shows a section as
  unavailable when the device has no implementation (`OeaVipAudioFx.kt:31-46`).
* **The floating window releases audio focus** on stop (`OeaVipPip.kt:128`), which is
  the behaviour §1.3 restores to the player.
* **Timing parse is careful**: one-to-three digit fractions are padded to
  milliseconds, and an end time that is not after its start falls back to a four
  second cue instead of a cue that never shows (`OeaSubtitles.kt:155-159`).

---

## 3. What this pass did not change, and why

* **No `MediaSession`.** VIP's background audio uses a hand-rolled notification plus
  `ACTION_MEDIA_BUTTON` receiver. That works, but the system's media resumption
  carousel, the lock-screen player and Bluetooth AVRCP metadata all read a
  `MediaSession`, which VIP does not publish — so the phone shows no title or artist
  on a car screen. Replacing the receiver with a session changes which component
  handles every media button; with no device here to check double-handling or headset
  ordering, that is a change to make with a phone in hand, not blind.
* **The notification has no artwork.** `status()` returns `art = null`
  (`OeaVipPlayerActivity.kt:1633-1640`); `Status.art` is wired through to
  `setLargeIcon` but nothing ever fills it. Adding it means reading embedded album art
  or a poster frame off the UI thread for every background-audio session — a feature,
  scheduled after the session work above, not a repair.
* **Speed changes go through `playbackParams`.** Whether the pitch is corrected is a
  property of the device's decoder, not of VIP; nothing here can promise it.
* **The service is nudged every four seconds** while it is up
  (`OeaVipPlayerActivity.kt:1179`). That is deliberate — it keeps the progress bar
  moving without a per-second IPC — and a seek updates the shade on the next beat.
* **`maybeShowGestureHint()` uses a different preferences file** (`oea_vip`) from the
  store (`oea_vip_v3`), so the one-time hint flag sits apart from the settings. It is
  harmless and moving it would show the hint a second time to everyone who has seen
  it, so it stays.
* **Audio-only detection is by file extension** (`AUDIO_ONLY` in the companion). A
  video container with no video track is still treated as video; the player copes, it
  just does not hide the surface.

---

## 4. Verification

The repair was compiled, unit-tested and packaged by this repository's own workflow:
[`OEA Launcher APK`, run 45276126005](https://github.com/Emmanuel001afk/OEA/actions/runs/45276126005)
on PR #152, merged as `4db980c`. Every step listed below came back green, and
publishing the release was correctly skipped because the branch was not `main`.

* `Static check (Kotlin structure, app registration, manifests)` —
  `tools/check-kotlin.py`: 299 sources, brackets balanced, manifests consistent
  (locally: `STATIC CHECK OK — 299 sources`).
* `Compile Kotlin (launcher, engine, arcade)` — the real Kotlin compiler over the
  player, the new `OeaVipFrames`, the service and the subtitle reader.
* `VIP subtitle regression tests` — `:oea:testDebugUnitTest --tests
  'com.oea.launcher.miniapps.OeaSubtitlesTest'`, twelve tests over the parser.
* `Verify the VIP work` — twenty-one guard checks that fail the build if any of the
  above is undone: the frame thread and its `close()`, the absence of
  `MediaMetadataRetriever` in the player, the subtitle sequence token, the three focus
  calls in their exact branches, the notification-permission request, `BigTextStyle`
  with no `InboxStyle`, the `ensureForeground()` call, the PiP `false` fallback, the
  unchanged-position guard in `tick()`, and the three subtitle-parser facts.
* Both APK builds (`Build the single OEA APK`, laptop APK) and the existing camera,
  search and responsiveness guards, which this change does not touch.

What ran here and not on CI: the parser behaviour was additionally checked against a
line-by-line model of the new `parseSrt`/`parseVtt`/`at` logic before the tests were
written, and every guard was executed locally against the working tree. No Kotlin
compiler and no Android SDK exist in this environment, so "it compiles" is the CI
job's claim, not mine.

---

## 5. What to check on a device

1. **Scrub preview**: drag the scrubber on a 1 GB+ file on a slow SD card — the
   preview should keep up and playback should not hitch when it starts.
2. **Subtitles**: an `.srt` with a BOM, one with a numeric line of dialogue, and a
   `.vtt` with cue identifiers — none of them should show a number or a cue name.
   Then skip clips mid-load: the next clip must never show the previous cues.
3. **Audio focus**: play music, open a video in VIP, pause it, and leave. The music
   should come back on its own rather than staying ducked until VIP is closed.
4. **Background audio on Android 13+**: turn "Audio keeps playing" on, accept the
   notification prompt, start a track, press Home. The shade should keep the title,
   the album line expanded, and the four buttons working.
5. **Picture in picture**: on a device that reports the feature but refuses it
   (some OEM builds), the button should still produce OEA's own window.
6. **Battery**: a 30-minute audio session with the player hidden — VIP nudges the
   service every four seconds; watch whether the process shows up unexpectedly.
