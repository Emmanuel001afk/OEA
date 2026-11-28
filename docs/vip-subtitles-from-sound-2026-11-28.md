# VIP: subtitles from the sound, inside the file, and in another language

*2026-11-28*

The [multi-language subtitle work](vip-multilanguage-subtitles-2026-11-28.md) made VIP
honest about what it could do, and the honesty was the problem: three things were
named as impossible and left there.

| What was missing | Why it was left out | What it does now |
|---|---|---|
| Text tracks **muxed inside** an MKV | "Android's player selects one but never hands it over" | `OeaMatroska` reads them out of the container |
| Subtitles for a clip that has **none** | "no speech-to-text that is accurate" | `OeaAutoCaption` writes them from the soundtrack, on Android 13+ |
| Watching in a language the file is **not** in | "no machine translation without a 30 MB model" | `OeaTranslate` downloads that model, for the languages that have one |

None of the three is a promise that everything is now possible. Each is a
mechanism with a stated boundary, and the boundaries are in this document.

---

## 1. The timing is measured, the words are guessed

This is the design decision everything else follows from.

A recognizer given a long stretch of audio returns a paragraph and no idea where
in that stretch any of it was said. Ask one to transcribe a ninety-minute film in
a single session and you get words with no clock: every cue lands wherever the
implementation feels like putting it, which is what "inaccurate" meant in the
previous document.

So VIP does the clock itself, on the decoded audio, before the recognizer is
involved at all:

```
video/audio track
   → MediaExtractor + MediaCodec            (decode)
   → mix to mono, resample to 16 kHz        (OeaAudio)
   → 20 ms frames, energy per frame          (OeaSpeechRegions)
   → speech regions, merged and split        (OeaSpeechRegions)
   → one recognizer session per region       (OeaAutoCaption)
   → cues: the region's bounds + its words   (OeaCaptions)
```

Consequences worth stating plainly:

- **A cue's start and end are the audio's, not the recognizer's.** They are exact
  to a 20 ms frame plus a 140 ms pad, the same on every phone, and pinned by unit
  tests that build the audio themselves.
- **The recognizer only ever answers "what was said in this stretch".** Its
  opinions about timing are never used, because it does not really have any.
- **The pieces that can drift are pure functions.** Resampling keeps its carry
  between chunks (a naive per-buffer resampler walks about a second per ten
  minutes), and the region detector is fed the same audio in odd-sized chunks in
  a test and must return the same regions to the millisecond.

The detector's rules, all in `OeaSpeechRegions.Settings`:

| Rule | Value | Why |
|---|---|---|
| frame | 20 ms | fine enough to catch a consonant, cheap enough for two hours |
| absolute floor | RMS 220 (≈ −43 dBFS) | tape hiss and room tone never become a cue |
| relative floor | 2.4 × the clip's own tenth percentile | a loud score does not hide the dialogue |
| safety net | at most half the clip's ninetieth percentile | a film with no silence at all still gets cut |
| minimum region | 280 ms | a cough, a door, a click are not lines |
| merge gap | 360 ms | a breath inside a sentence is not the end of it |
| maximum region | 8 s | nobody reads two lines and half a paragraph at once |
| pad | 140 ms | a cue starts just before the consonant |

Cut points are chosen at the **quietest** frame in the middle half of a long run,
never at a fixed interval, so a split lands in the pause rather than mid-word.

## 2. What the words are worth

`SpeechRecognizer` is the only speech engine Android guarantees, and it is the
phone's, not OEA's.

- **Android 13 (API 33) or newer** is required: `EXTRA_AUDIO_SOURCE` is what lets
  an app hand a recognizer a file descriptor instead of the microphone. Below
  that, VIP says so and stops — it does not quietly record the mic.
- **Offline first.** If `isOnDeviceRecognitionAvailable()` is true, VIP creates
  the on-device recognizer and the clip's audio never leaves the phone.
- **If there is no offline recognizer**, the sheet says so *before* the run
  starts, in those words: *"the words come from an online one and the clip's
  audio is sent to it"*. The timing is still measured locally.
- **The spoken language is a separate setting** from the subtitle language
  (`OeaVipStore.captionLanguageTag`). A viewer who reads Yoruba subtitles over
  English films has one of each, and conflating them transcribes every film in
  the wrong language.
- **Refusals are named, not swallowed.** `ERROR_INSUFFICIENT_PERMISSIONS` becomes
  "the recognizer wants the microphone permission even though VIP hands it the
  clip's own audio"; an empty on-device answer becomes "its language pack may not
  be installed".

A written track is an ordinary `OeaSubtitles.Track` the moment it exists: it can
be drawn, offset, paired with a second language, saved as `.srt`, or translated.

**What it is not:** a studio subtitle. Names, accents, hard words and overlaps
will be wrong somewhere, every time. The cues say where they came from — "written
from the sound by the phone's own recognizer" — and the timings, unlike the
words, are exact.

## 3. The tracks inside the MKV

`OeaMatroska` walks the container's element tree: `Segment` → `Tracks` for what
each track is, then every `Cluster` → `SimpleBlock`/`BlockGroup` for what it says.

| Track type | Read? | How |
|---|---|---|
| `S_TEXT/UTF8`, `S_TEXT/ASCII` | yes | the payload is the line |
| `D_WEBVTT/*` | yes | the payload minus its tags |
| `S_TEXT/ASS`, `S_TEXT/SSA` | yes | the ninth field of the block; styling dropped, words kept |
| `S_HDMV/PGS`, `S_VOBSUB` | no | a bitmap, not text: turning a picture of a word into a word is a different job |
| laced blocks | no | subtitle frames are almost never laced, and splitting one wrongly shifts every later cue |

Timing comes from the cluster timecode plus the block's signed 16-bit offset, and
a cue's length from `BlockDuration` when the file declares one; otherwise the next
cue on the same track ends it, clamped to 0.6–7 s so one line cannot sit on
screen through a scene change.

An embedded track is remembered like any other: its origin is
`embedded:<absolute path>#<track number>`, so the language you picked is the one
that opens next time, re-found by walking the container again rather than by
pretending it is a file.

**Not read:** text tracks inside an **MP4/MOV** (`tx3g`). They need the sample
tables (`stts`/`stsz`/`stsc`/`stco`) walked rather than a block stream, and they
are rare in the films people download; the honest answer is that an MP4 with
burned-in-free soft subtitles still needs a `.srt` beside it or a run of the
written-from-sound path.

## 4. Translation, with the model named

`OeaTranslate` uses ML Kit's on-device translator.

- **58 languages**, listed as codes and resolved through the same catalogue the
  rest of VIP uses, so a badge and a label always agree.
- **≈30 MB per language**, downloaded from Play services the first time that
  language is asked for, then kept on the phone. The sheet says this before the
  first one.
- **The film never leaves the phone.** Translation of a *track* is a text problem;
  nothing is uploaded.
- **Through English.** ML Kit translates to and from English, so French → Yoruba
  is really French → English → Yoruba, and reads like it. The sheet says so.
- **Not every VIP language has a model.** Yoruba, Igbo, Hausa, Amharic and Zulu
  are in VIP's subtitle catalogue and not in ML Kit's translator. Those languages
  are simply not offered as targets; nothing pretends otherwise.
- **The timing is untouched.** `translated()` copies every cue's start and end and
  replaces only the words — the one property of a translation that a test can
  guarantee, and does.

## 5. Verification

Unit tests, all run on JVM in CI (`OeaAudioTest`, `OeaSpeechRegionsTest`,
`OeaCaptionsTest`, `OeaMatroskaTest`, `OeaTranslateTest`):

| File | Tests | What they pin |
|---|---|---|
| `OeaAudioTest` | 11 | mono averaging, resampling at 48/44.1 kHz, the carry across 977-sample chunks, little-endian bytes, a real WAV header |
| `OeaSpeechRegionsTest` | 9 | two sentences on their own bounds, a cough dropped, a breath not splitting a line, a monologue cut at ≤ 8 s, a loud music bed, streaming equals one-shot, the last line kept |
| `OeaCaptionsTest` | 11 | capitalisation, punctuation, `[MUSIC]`/`(applause)`/`♪` dropped, two-line wrapping, region timing preserved, gaps not invented, duplicates merged |
| `OeaMatroskaTest` | 11 | a Matroska file built byte by byte in the test: UTF-8 track, SDH name, forced track, `BlockDuration`, an ASS dialogue field, bitmap and video tracks left alone, origins |
| `OeaTranslateTest` | 9 | every offered language has a name, Chinese/Norwegian/legacy codes, unsupported languages refused, timings preserved, blank answers keeping their words |

51 new tests, 93 subtitle tests in total. The VIP CI step runs them by name, and
28 new guard checks keep the shape: the timing comes from regions
(`grep 'OeaSpeechRegions.Detector'`), the recognizer is never asked to open the
microphone (`! grep 'AudioRecord('`), the deprecated inject-source extra is not
used (`! grep 'EXTRA_AUDIO_INJECT_SOURCE'`), embedded tracks are found on the
probe thread (`grep 'OeaMatroska.read(clip)'` inside the `PROBE.execute` block's
file), the offset is applied once, and translation never runs on the UI thread
(`grep 'PROBE.execute'` around `OeaTranslate.translate`).

### Device checklist

1. **An MKV with muxed subtitles** (`mkvmerge -i` shows `S_TEXT/UTF8`): open it —
   the language appears in the sheet with its badge, cues are drawn, and choosing
   it re-opens on the same choice next time.
2. **An MKV with only PGS**: no track is offered, and the sheet still says
   "no subtitles in this clip" rather than showing a broken row.
3. **Android 12 and below**: "Write subtitles from the sound" refuses with the
   Android 13 sentence. Nothing is recorded.
4. **Android 13+ with an offline pack** (Settings › Speech services › offline
   recognition): a two-minute clip of clear speech produces cues whose first line
   starts within ~200 ms of the first word, and the sheet never says "online".
5. **The same clip with no offline pack**: the sheet says the audio will be sent
   to an online recognizer *before* it starts.
6. **Stop**: pressing Stop mid-run leaves no partial track and the temporary PCM
   in `cache/` is gone (`vip-caption-*.pcm`).
7. **A silent clip** (a title card): the run ends with "no speech was found",
   not an empty track.
8. **Twenty-minute run from a playhead**: cues begin at or after that position
   and nothing before it appears.
9. **Translate an `.srt` beside a film into French**: the model downloads once,
   the cues stay where they were, and the pill switches to `FR`.
10. **Translate into Yoruba**: not offered anywhere in the sheet.
11. **Leave the player mid-run**: the run stops, no notification, no thread left
    reading a file that is closed.

## Sources

- `RecognizerIntent` — `EXTRA_AUDIO_SOURCE` and its channel/rate/encoding
  companions, API 33; `EXTRA_SEGMENTED_SESSION`; the deprecated
  `EXTRA_AUDIO_INJECT_SOURCE`.
  <https://developer.android.com/reference/android/speech/RecognizerIntent>
- `SpeechRecognizer` / `RecognitionListener` — `createOnDeviceSpeechRecognizer`,
  `isOnDeviceRecognitionAvailable`, `onSegmentResults` (all API 33), and the
  error codes used above.
  <https://developer.android.com/reference/android/speech/SpeechRecognizer>
- ML Kit on-device translation — 50+ languages, ≈30 MB per model, downloaded
  through Play services, English as the pivot.
  <https://developers.google.com/ml-kit/language/translation/android>
  and `com.google.mlkit:translate:17.0.3` in the release notes
  <https://developers.google.com/ml-kit/release-notes>
- Matroska element IDs (`Segment`, `Tracks`, `TrackEntry`, `Cluster`,
  `SimpleBlock`, `BlockGroup`, `BlockDuration`), from the Matroska specification
  <https://www.matroska.org/technical/elements.html>
