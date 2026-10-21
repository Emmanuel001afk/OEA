# EMAGE picker contract and Home dock rectification — 2026-10-21

## What was wrong

### 1. The first-run dock lost EMAGE (the Home regression)

The EMAGE platform pass (2026-10-20) changed Home's first-run dock seeding to
resolve `Intent.CATEGORY_APP_GALLERY` before the generic `ACTION_VIEW image/*`
match. On a real phone that resolution returns whatever gallery Android ranks
first — Google Photos on most devices — so the dock's fifth slot was handed to a
third-party app and EMAGE was evicted into the seeded "OEA" folder. The dock is
Home's most visible surface, and it changed on devices whose users never asked
for a different gallery.

**Fix.** The dock's gallery slot now belongs to OEA's own picture app, resolved
directly from the app catalogue (`OeaImageActivity`). Android's gallery identity
(`CATEGORY_APP_GALLERY`, then `ACTION_VIEW image/*`) remains only as the
fallback for a build that somehow ships without EMAGE. Existing layouts are
untouched: this is seeding only, and re-seeding still never happens after the
first run.

### 2. The picker contract could not return a result

`OeaImageActivity` is `singleTask` with its own task affinity — correct for its
own Recents card and its full-app surface, but fatal for a result contract.
`ACTION_PICK` and `ACTION_GET_CONTENT` are launched with
`startActivityForResult`, and a picker must run **inside the caller's task** or
the caller receives `RESULT_CANCELED` immediately. Every app that tried to pick
a picture through EMAGE got an instant cancellation, so the declared contract
was a dead letter on every device.

**Fix.** A dedicated `OeaEmagePickActivity` now owns the `ACTION_PICK` /
`ACTION_GET_CONTENT` (+ `CATEGORY_OPENABLE`) intent filters:

- `standard` launch mode, no private task affinity — it runs in the caller's
  task, where `setResult` + `finish` actually deliver;
- it renders the same EMAGE screen — `OeaImageApp` already switches to picker
  mode from the launch action, so multi-select, "Use selected", read grants and
  the cancel path behave identically;
- it carries **no** `MAIN`/`LAUNCHER` category, so Android's app list, Home and
  the drawer still show exactly one EMAGE — no duplicate icons;
- `OeaImageActivity` keeps `singleTask`, its affinity, its `VIEW`/`EDIT`/
  `ATTACH_DATA` and `SEND`/`SEND_MULTIPLE` contracts, and its
  `MAIN + LAUNCHER + APP_GALLERY` identity.

### 3. The EMAGE home-screen widget showed the Notes widget

`OeaImageWidgetProvider` inflated `oea_widget_notes` ("reuse layout for
simplicity") although `oea_widget_image.xml` exists and is the widget's declared
`initialLayout` — so the widget showed **"Notes"** as its header after the first
update, and its favourite count was read from a legacy prefs file
(`oea_image_v2`) that the real media index never writes. VIP, Camera and
Calculator widgets had the same wrong-layout defect (Notes, Notes and Clock
layouts respectively).

**Fix.** Each widget now inflates its own layout, and the EMAGE widget reads
its counts from the live media index (`OeaMediaLibrary`), so the number on Home
agrees with the star EMAGE itself shows.

### 4. Smaller EMAGE defects found in review

- The app-bar back arrow finished EMAGE even from inside an album, a search, or
  a narrowed tab. It now steps out hierarchically: close the search → leave the
  album → return to All pictures → only then finish.
- The Albums empty state offered "Allow access" even when access was already
  granted — a dead end that reads as broken. It now offers the permission
  action only when the permission is actually missing, and "Scan again"
  otherwise.
- The Tile shape row label was inverted: a ratio above 1 divides the tile's
  height below its width, so `> 1` is the wide shape — the label said "Tall".
- Changing the grid's column count left day headers spanning the **old** count
  (`OeaSpanLookup` captured the span at creation, so a 4-column grid got
  3-span headers with a tile squeezed beside them) and left tiles decoding at
  the old size (the grid's own size does not change with its span, so the
  layout listener never fired). The lookup now carries a live span that
  `setSpan` updates, and the Columns sheet retargets the decode edge.
- The viewer's neighbour prefetch skipped warming when exactly one page
  remained ahead (`position + 2 < size` was too strict).

## Files changed

- `oea/src/main/java/com/oea/launcher/workspace/OeaWorkspace.kt` — dock seeding.
- `oea/src/main/AndroidManifest.xml` — picker activity entry; PICK/GET_CONTENT
  filters moved off the singleTask entry.
- `oea/src/main/java/com/oea/launcher/miniapps/OeaMiniAppActivities.kt` —
  `OeaEmagePickActivity`.
- `oea/src/main/java/com/oea/launcher/miniapps/OeaImageApp.kt` — hierarchical
  back, honest Albums empty state, tile-shape label, column-change decode edge.
- `oea/src/main/java/com/oea/launcher/miniapps/OeaEmageViewer.kt` — neighbour
  prefetch bounds.
- `oea/src/main/java/com/oea/launcher/ui/OeaAppKit.kt` — live span in
  `OeaSpanLookup`; `setSpan` updates it.
- `oea/src/main/java/com/oea/launcher/widgets/OeaMiniAppWidgets.kt` — EMAGE,
  VIP, Camera and Calculator widgets use their own layouts; EMAGE reads the
  real index.

## Verification checklist

1. Fresh install (or Settings › clear Home data) on a device with Google
   Photos: the first-run dock contains Phone, Messages, Browser, Camera and
   **EMAGE**; no third-party gallery is seeded, and EMAGE is not duplicated in
   the OEA folder.
2. From a test app, `startActivityForResult(ACTION_GET_CONTENT, type
   image/*)` → choose EMAGE → pick one picture → the caller receives
   `RESULT_OK` with a readable URI. Repeat with `EXTRA_ALLOW_MULTIPLE` and
   confirm the multi-selection returns `EXTRA_STREAM` + `ClipData`.
3. Repeat with `ACTION_PICK` and with `CATEGORY_OPENABLE` added; confirm the
   result is delivered and readable after EMAGE closes. Back out of the picker
   and confirm the caller receives `RESULT_CANCELED` (not a crash, not a
   stale result).
4. Confirm the launcher, the drawer, Android's app list and Recents show
   exactly one EMAGE icon — the picker entry adds none.
5. `ACTION_VIEW`/`ACTION_EDIT`/`ACTION_SEND` of an `image/*` still open
   `OeaImageActivity` (full app, own task, editor for EDIT).
6. Add the EMAGE widget to Home: its header reads "EMAGE" (not "Notes") and
   the picture/favourite counts match the app. Same for the VIP, Camera and
   Calculator widgets.
7. In EMAGE: enter an album → tap the back arrow → you leave the album (not
   the app). Search → back closes the search. Favourites tab → back returns to
   All. Back on All finishes EMAGE.
8. More › Columns → 4 (and 2, 5): day headers span the full row, no tile
   squeezes beside a header, and tiles decode at the new size. More › Tile
   shape: the row's value matches the shape that renders (4:3 wide, 3:4 tall).
9. Albums tab with permission granted and an empty library: the empty state
   offers "Scan again", not "Allow access". With permission denied it offers
   "Allow access" and opens Android's dialog.
10. Swipe near the end of a library in the viewer: the last neighbour page is
    pre-warmed like any other.

Device verification is still required — this checkout has no Android emulator
or physical device.
