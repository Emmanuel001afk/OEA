# Multi-language subtitles in VIP — 2026-11-28

## What was asked

> "Build a multilanguage subtitle system, meaning VIP can subtitle a video played on
> VIP. Search and confirm if you can do it accurately."

Two questions sit inside that, and they have different answers:

* **Many subtitle tracks, in many languages, for a video VIP is playing** — yes, and
  this document is what shipped. Every part of it is deterministic: a file name is
  matched against a rule, a language is a table lookup, a cue is read at a time. There
  is nothing to be "roughly" right about, and it is covered by 42 unit tests.
* **VIP inventing subtitles for a video that has none** (speech-to-text, then
  translation) — no, not accurately, and this document says exactly where the line is
  and why. VIP now displays, names, switches, pairs and exports subtitle files in any
  language; it does not transcribe a soundtrack or machine-translate a script.

That second answer is not a limitation of effort. It is three platform facts, checked
below: Android's player cannot hand over the text track muxed inside a video, Android's
speech recogniser is not a file transcriber, and translation means shipping or
downloading a model (≈30 MB per language) or uploading the film to somebody's server —
neither of which OEA does.

## What VIP did before

One sidecar file per clip, chosen by whichever extension happened to match first:

1. `siblingFile()` tried `.srt`, then `.vtt`, then `.ass`, and returned the first that
   existed. `Movie.srt` beat `Movie.en.srt` only by luck of alphabetical listing; a
   phone set to English could open the German track with no way to say otherwise.
2. Nothing in the system knew what language a track was. The pill said `812 cues`, which
   tells you nothing you can act on.
3. `paintSubtitle()` looked the cue up at `positionMs + subtitleOffset` on a track that
   had **already** been shifted by `subtitleOffset`, so the delay was applied twice: a
   five-second nudge moved the words ten.
4. The queue passed a single `EXTRA_SIDECAR` path on the Intent. When playback advanced
   to the next episode, that path still pointed at the first episode's subtitle file, and
   the previous clip's cues could be painted over the next one.
5. Right-to-left languages were drawn by Android's default heuristics with no isolates,
   so a full stop at the end of an Arabic line, or a Latin name inside it, landed on the
   wrong side. Chinese, Japanese and Korean were drawn at Latin sizes.

## The system now

### A language catalogue that filenames can actually reach

`OeaSubtitleLanguages` holds 114 languages — the ones subtitle files ship in, plus every
language OEA's own copy is written in, so Yorùbá, Hausa, Igbo and Kiswahili are
first-class choices rather than "other". Each entry carries its BCP-47 tag, English name,
native name, ISO 639-2 aliases and whether it reads right to left.

`resolve()` accepts what people actually type into a filename:

| Written in the file name | Resolved |
| --- | --- |
| `en`, `ENG`, `English`, `en-GB`, `en-US` | English |
| `fre`, `fra`, `fr-CA`, `Français` | French |
| `pt-BR`, `pt-br`, `Brazilian Portuguese` | Brazilian Portuguese (a separate entry: the track is not the same) |
| `zh`, `zh-CN`, `zh-Hans` | Chinese (Simplified) |
| `zh-TW`, `zh-HK` | Chinese (Traditional) |
| `iw`, `in`, `ji`, `no` | Hebrew, Indonesian, Yiddish, Norwegian — the codes Android still hands out |
| `Yorùbá`, `yoruba`, `yor` | Yorùbá · Yoruba |
| `Klingon`, `1080p`, `x264` | nothing, and it says so |

Names are matched accent- and case-insensitively (`Normalizer` folds `Yorùbá` to
`yoruba`), which is what makes a file called `Movie.yoruba.srt` match an entry written in
the language's own spelling.

### Files that belong to a clip, and files that do not

`OeaSubtitleFiles` is the planner: names in, candidates out, no filesystem, so every rule
is a unit test. A file belongs to `Movie.mkv` when its name is the clip's name, then a
separator, then words that are all languages, flags or noise:

| Beside `Movie.mkv` | Result |
| --- | --- |
| `Movie.srt` | this clip, language not named (the words decide) |
| `Movie.en.srt`, `Movie.english.srt`, `Movie.eng.vtt`, `Movie.en-US.srt` | English |
| `Movie.fr.srt`, `Movie.yoruba.srt`, `Movie.pt-BR.srt` | French, Yorùbá, Brazilian Portuguese |
| `Movie.en.forced.srt` | English, **forced** (only the dialogue that needs it) |
| `Movie.en.sdh.srt`, `Movie.en.cc.srt` | English, **SDH** |
| `Movie.hi.srt` | Hindi — `hi` is a language, not "hearing impaired" |
| `Subs/Movie.de.srt`, `Subtitles/Movie.ja.vtt` | German, Japanese — one folder down |
| `Movie.2019.1080p.x264-GROUP.en.srt` beside that exact release | English |
| `Movie.Trailer.srt`, `Other.en.srt`, `Movie.srt.bak` | **not** this clip's dialogue |

That last row is the point of the strictness: painting a trailer's or another film's
subtitles over a picture is worse than finding nothing.

The search itself lives in `OeaMediaProbe.subtitleFilesFor()`: the clip's own folder plus
`Subs/`, `Subtitles/` and `Captions/`, capped at 500 names per folder. It runs on the
player's probe thread, never on the tap that started playback, and `OeaVideoApp` now
passes one absolute path per queue entry (`EXTRA_QUEUE_PATHS`) instead of one subtitle
path for the whole queue — which is what fixes the leak onto the next episode.

**The Android boundary is real and stated in the UI.** An app can only list a folder it
can read: OEA's own `Movies/OEA` and `Pictures/OEA` trees, and anything reached through
the system document picker. A MediaStore row arrives as a `content://` URI with no folder
to walk, so for a video in `WhatsApp Video/` VIP finds nothing beside it and says so —
which is why "Pick a subtitle file" exists, and why a picked file is remembered per clip.

### Language, per clip and per person

* **Preferred language** — `auto` (the phone's own language, resolved when used, so a
  viewer who switches the phone to Yoruba gets Yoruba) or any catalogue entry. It decides
  which track opens when a clip has several.
* **Per-clip memory** — the track you chose for an episode is the one that opens next
  time, committed as the file's URI. A hand-picked file wins over anything found beside
  the clip and can be forgotten from the same sheet.
* **A second language at once** — the original above, the one you read below. Off by
  default; never two copies of the same cues.
* **The pill** now says `EN`, or `EN + YO` when two are drawn. The cue count moved into
  the sheet, where a count belongs.

### Drawing

* The offset is applied once (to the cues, in `shifted()`), and the lookup is at the
  position on the clock. The double-offset bug from the old `paintSubtitle()` is gone.
* Direction comes from the words, not from the label: `OeaSubtitles.isRightToLeft()` reads
  the first strong character, and `display()` wraps a right-to-left line in U+202B/U+202C
  isolates so trailing punctuation and embedded Latin names land where a reader expects.
  A file called `Movie.ar.srt` and a file called `subs.srt` that happens to be Arabic are
  drawn the same way.
* Chinese, Japanese and Korean get 12% more size for the same physical line.
* Two tracks are composed by `OeaSubtitles.together()`, which returns null when neither
  has anything to say — the bar hides instead of holding an empty bubble.

### Writing one back out

`OeaSubtitles.toSrt()` turns a track back into SubRip, and the subtitle sheet offers
**Save this language as .srt** through Android's create-a-document sheet — so a language
that exists only beside this film can go to another player, another phone, or a
translator. The writer is round-trip tested: `parse(toSrt(track))` gives back the same
cues.

### Where it shows up

* **Player** — the subtitle sheet lists every file with its language, flags, badge and
  cue count; picks the primary; picks a second; picks the preferred language (the clip's
  own languages first, then all 114); loads a file; saves one; forgets a hand-picked one.
* **VIP library** — each clip's absolute path is handed to the player with the queue.
* **Details sheet** — "Subtitles beside it" now names the languages and their flags
  (`English • Français, forced • Yorùbá`) instead of the first file it found.

## What "accurate" means here

| Question | Answer | Why |
| --- | --- | --- |
| Reads `.srt` and WebVTT | **Yes** | Parsers kept from the previous review, now also tested for round trips |
| Names the language from the file name | **Yes**, for the conventions above | A lookup, not a guess; unknown names stay unknown |
| Names the language from the words | **Only at script level** | Arabic, Hebrew, Cyrillic, Greek, Devanagari, Thai, Hangul, Kana/Han, Ethiopic… Latin text is left unnamed on purpose |
| Shows several languages, remembers your choice | **Yes** | Deterministic; per clip and per language |
| Reads the subtitle track muxed inside the video | **No** | `MediaPlayer` can *select* a timed-text track and deliver it to `OnTimedTextListener` — one format (SubRip), and the app still has to draw every cue itself. Every other container (PGS, VobSub, ASS-in-MKV) is not reachable at all. VIP draws its own cues; a second decoder is not something OEA ships |
| Makes subtitles for a video that has none (speech-to-text) | **No** | See below |
| Translates an existing track into another language | **No** | See below |

### Why not speech-to-text

`SpeechRecognizer` is built around the microphone. Since Android 13 it accepts
`RecognizerIntent.EXTRA_AUDIO_SOURCE` for a file, but only through a recogniser that
implements it (usually Google's, with an **offline language pack already installed**), the
format is 16 kHz PCM, the result is text without word timings — so cue start and end
would have to be invented — and on a phone without that recogniser it does nothing at
all. On-device Whisper quality needs `small` (≈250 MB) or `base` (≈80 MB) plus a native
runtime, i.e. a model bigger than most of OEA's APK for subtitles in one language at a
time. Shipping that and calling it accurate would be a promise the phone cannot keep.

### Why not translation

Machine translation of a subtitle file needs either an on-device model or a server. The
usual on-device option is ML Kit's translator: about **30 MB per language**, downloaded
through Google Play services, sentence-by-sentence and context-blind; OEA would carry a
proprietary Play Services dependency and download tens of megabytes to translate one film.
The alternative — a cloud translation API — means uploading the viewer's film or its whole
script, with no account system and no key in an APK that ships unsigned by a hobby
project. So VIP draws the language the file was written in, and says so in the sheet.
A translated `.srt` dropped beside the film is simply another language track, and that is
the supported path.

## Verification

* `python3 tools/check-kotlin.py` — 338 sources, brackets balanced, kit calls resolve, OEA
  symbols declared, manifests consistent: **STATIC CHECK OK**.
* 42 unit tests across three classes:
  * `OeaSubtitleLanguagesTest` (12) — codes, aliases, regions, legacy spellings, names,
    badges, right-to-left, script naming, and a test that walks **every** catalogue entry
    and every alias it declares, so a language nothing can select cannot ship.
  * `OeaSubtitleFilesTest` (10) — the naming conventions above, case, dotted release
    names, subfolders, forced/SDH/`hi`, the rejections, sorting and "best".
  * `OeaSubtitlesTest` (20, 8 of them new) — language from name, language from script,
    flags and origin surviving an offset, right-to-left isolates, bilingual composition,
    and the `.srt` round trip.
* Guard checks in `.github/workflows/oea-apk.yml` keep the shape of the system: the
  catalogue and planner must exist, discovery must happen on the probe thread, the offset
  must not be applied twice, and the player must still hand both languages to
  `OeaSubtitles.together`.

### Device checklist

1. Put `Film.mkv`, `Film.en.srt`, `Film.fr.srt` and `Film.yor.srt` in `Movies/OEA`; play
   it from VIP. The pill reads your phone language's badge; the sheet lists three files
   with native names; switching is instant and does not touch the frame.
2. Pick Yorùbá, close the player, replay the clip: Yorùbá opens. Try the same with the
   phone set to French and no per-clip choice — French opens.
3. Choose the second language `EN` under `YO` (or the reverse) and check both lines sit
   under each other at the right size.
4. Play `Film.ar.srt` (or any Arabic track): the line is centred, its full stop is on the
   left, and a Latin name inside it is not split.
5. Slide the time offset to +5.0 s and confirm the words move five seconds, not ten.
6. Play an episode queue and let it auto-advance: the next episode must not show the
   previous one's subtitles.
7. Open a WhatsApp/Download video (no folder access): the sheet says no files were found,
   and "Pick a subtitle file" loads one, remembers it per clip, and keeps its language.
8. **Save this language as .srt** → save → open the file in another player: same cues,
   same times.
9. Long-press a clip → details: the subtitle row names the languages and flags.

## Files

* `oea/src/main/java/com/oea/launcher/miniapps/OeaSubtitleLanguages.kt` — new: catalogue,
  code/name resolution, script reading, direction, badges.
* `oea/src/main/java/com/oea/launcher/miniapps/OeaSubtitleFiles.kt` — new: which file
  belongs to which clip, in which language, with which flags, in what order.
* `oea/src/main/java/com/oea/launcher/miniapps/OeaSubtitles.kt` — tracks carry language,
  flags and origin; direction-aware drawing; two tracks at once; SubRip writer.
* `oea/src/main/java/com/oea/launcher/miniapps/OeaMediaProbe.kt` — folder walk in the
  clip's folder and `Subs`/`Subtitles`/`Captions`.
* `oea/src/main/java/com/oea/launcher/miniapps/OeaVipStore.kt` — preferred and second
  language, stored as tags with `auto` and `off` as real values.
* `oea/src/main/java/com/oea/launcher/miniapps/OeaVipPlayerActivity.kt` — per-clip
  discovery, language sheet, second language, RTL/CJK drawing, offset fix, `.srt` export.
* `oea/src/main/java/com/oea/launcher/miniapps/OeaVipDetails.kt` — details list every
  language instead of the first file.
* `oea/src/main/java/com/oea/launcher/miniapps/OeaVideoApp.kt` — one path per queue entry.
* `oea/src/test/java/com/oea/launcher/miniapps/` — the three test classes named above.
* `.github/workflows/oea-apk.yml` — the two new test classes and the guard checks.

## Sources

* Android `MediaPlayer` — `addTimedTextSource`, `OnTimedTextListener`, `selectTrack`, and
  the "currently supported format is SubRip" note:
  <https://developer.android.com/reference/android/media/MediaPlayer>
* `SpeechRecognizer` and file input (`EXTRA_AUDIO_SOURCE`, Android 13+, on-device model
  required, recogniser-dependent):
  <https://github.com/jamsch/expo-speech-recognition#transcribing-audio-files>
* ML Kit on-device translation — 50+ languages, ≈30 MB per language model, Play Services:
  <https://developers.google.com/ml-kit/language/translation/android>
* Whisper model sizes used for the on-device comparison (`base` ≈ 80 MB, `small` ≈ 250 MB):
  <https://github.com/openai/whisper>
