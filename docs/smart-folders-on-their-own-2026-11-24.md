# Smart folders on their own, and the tiles that were not there — 2026-11-24

Two things were in the request: *what is this* on a Home screen screenshot, and
*"the smart folder identification should be automatically, not detailed or written
down"*.

Both turned out to be the same subject seen from two sides: OEA was writing things
down that it can work out, and drawing nothing where it had no icon.

## 1. What the screenshot showed

The report was a Home screen with two kinds of broken-looking tiles: app tiles
carrying only their label (no artwork), and one tile with an exclamation mark on a
dark plate.

What OEA actually does, from the code:

- `OeaTileView.onDraw` → `drawIcon` returned **immediately** when the drawable was
  `null` (`OeaHomeViews.kt`, `drawIcon`). A tile with no icon drew the label and
  nothing else — a hole exactly the size of an icon, which reads as a broken
  launcher rather than as "not loaded yet".
- `OeaIconController.request()` resolved an icon off the main thread, and when the
  resolve came back `null` it **removed the waiters and never called them**
  (`pending.remove(key)` inside the executor, delivery only `if (bitmap != null)`).
  A tile gets one `request()` per bind: if that one failed, the tile stayed blank
  for the rest of the session even though nothing was permanently wrong. The first
  load of a session can lose that race — the app catalogue is ready before the
  package manager's icon service is.
- `rawIcon()` has no fallback of its own: when neither an icon pack nor
  `PackageManager` yields a drawable it returns `null`, and `render()` is then never
  reached. There is no "last resort" icon anywhere in the chain.

So the blank tiles are OEA's: no icon was ever delivered and no stand-in was drawn.

The exclamation-mark tile is **not** drawn by OEA. No OEA source draws an
exclamation mark — the only `"!"` in the launcher is a calculator operator
(`OeaCalculatorApp.kt`). That glyph therefore arrives with the tile's own icon
resource: an app whose own launcher icon is a warning/attention plate, or a
placeholder supplied by the ROM. To name it on the phone: long-press the tile → the
menu names the app; the same tile in the app drawer confirms whether the glyph is
the app's icon or OEA's.

### What changed

| Where | Before | Now |
| --- | --- | --- |
| `OeaHomeViews.kt` `drawIcon` / `drawIconPlate` | a `null` icon drew nothing | a plate with the app's initial, so a tile is never an invisible hole. Folder previews keep their own gap (`placeholder = false`) |
| `OeaIconController.kt` `request` (`val attempt`, `attempt(1)`) | one resolve; a `null` result stranded every waiting tile for the session | one delayed retry off the main thread; if it fails too, the tiles fall back to the plate |
| `OeaAppModel.kt` (`val uid: Int = 0`, `uid = appInfo.uid`) | the catalogue carried no uid | the Android uid travels from `PackageManager` through the cache to smart folders |

## 2. "Automatically, not detailed or written down"

The old smart folders read a **catalogue typed by hand**: `OeaSmartFolders.CATALOGUE`
listed namespaces (`com.google.`, `com.microsoft.`), a set of Meta package names, an
Adobe/Amazon list and a Transsion one, with package-name exclusions. Consequences:

- a family nobody had written down never got a folder, however obviously it belonged
  together on this phone;
- a package that merely looked like the list got a folder it had no business in;
- every new app family was a code change;
- and a folder made by hand by dropping one app on another was named by turning the
  most common `ApplicationInfo.category` into a word (`CATEGORY_GAME` → "Games",
  `CATEGORY_SOCIAL` → "Social", …) — a guess, written into the layout, stale as soon
  as a member changed.

Both are gone. Families are now identified from facts the phone owns, in a pure,
tested unit, and titles are derived and maintained automatically.

### Identification (`workspace/OeaAppFamilies.kt`)

`discover()` groups apps by, in order:

1. **Shared uid** (`Signal.SHARED_UID`) — apps installed under one Android user id
   are one publisher by the system's own rules. This cannot be claimed by an app
   that does not hold that id.
2. **Publisher namespace** (`Signal.NAMESPACE`, `namespaceOf`) — three package-name
   segments (`com.transsion.*`), never the platform's own (`com.android.*` is
   excluded).
3. **Shared signing certificate** (`Signal.SHARED_SIGNATURE`) — same key, same
   publisher, whatever the namespace. Read only for apps the first two signals left
   apart, once per process, so a phone whose families share a uid never pays for it.
4. Nothing else. A display label never decides membership (`labelsNameAFamily…`
   test: two apps called "Notes" with different publishers stay two apps).

A family needs **two or more** launchable apps (`minSize`), and one package belongs
to at most one family.

### Naming (`titleFor`, `commonLabelPrefix`, `sharedFirstWord`)

`"Google Maps" + "Google Photos"` → **Google**; `com.transsion.*` → **Transsion**;
`com.facebook.*` → **Facebook**; nothing derivable → **Apps**. Labels only *name* a
group the identifiers already agreed on. Generic words ("Mobile", "Apps", "Pro")
are never taken as a title.

### The name maintains itself (`OeaSmartFolders.KEY_WRITTEN_TITLE`)

A family folder remembers the title **OEA** wrote. When the family changes, OEA
replaces its own title and leaves yours alone: rename a family folder once and that
name survives every later reconcile. This is the "not written down" part — the
folder's identity is its contents, and its name follows them until you take it over.

### The hand-made folder is plain (`OeaWorkspace.suggestFolderName`)

Dropping one app on another now names the folder **Folder** ("OEA" when it holds only
OEA's own apps). No category is guessed, no word is written into the layout.

### Settings

`Settings › Home screen › Smart folders` lists the families **this phone was found to
have** on the last reconcile (persisted by the pass itself), not a catalogue. The
explanation is one short paragraph instead of a paragraph about identifiers. Per
family: create-or-not, and "reset my choices" to forget apps you moved out.

## 3. Rules that still hold

- A family folder appears only once two or more of its apps are installed, and
  follows installs and uninstalls.
- Moving, adding and removing apps by hand wins; apps taken out are remembered
  (`excluded_<family>`) and never re-added.
- Deleting a family folder switches that family off (`enabled_<family> = false`);
  it can be re-enabled in Settings.
- A folder whose family disappears is **dissolved into its members' icons** rather
  than dropped, so no app vanishes from Home when recognition changes (this also
  covers the folders created by the old catalogue ids).
- The pass still short-circuits on an unchanged membership signature
  (`cachedSignature`), so Home's normalise path costs one string compare when
  nothing changed.
- All state stays in the `oea_smart_folders` preferences file.

## 4. Verification

**JVM unit tests** — `oea/src/test/java/com/oea/launcher/workspace/OeaAppFamiliesTest.kt`
(10 tests): uid grouping, distinct uids never folded, namespace grouping without a
shared uid, one app is not a family, `namespaceOf` rejecting `com.android.*` and
short names, the certificate grouping leftovers only (and never being read when uids
already answer), labels naming but never deciding, ids safe for preference keys.

**CI guards** — `Verify smart folders are identified from the phone, not written
down` in `.github/workflows/oea-apk.yml`:

- the uid travels from `appInfo.uid` into `OeaAppModel` and discovery uses
  `SHARED_UID` / `SHARED_SIGNATURE` / `namespaceOf`;
- `GET_SIGNING_CERTIFICATES` is still in the discovery path;
- `OeaSmartFolders` calls `OeaAppFamilies.discover(` and **may not contain**
  `CATALOGUE`, `com.google.`, `com.microsoft.` or `com.transsion` — the written list
  cannot come back quietly;
- `KEY_WRITTEN_TITLE` still exists (a user's name must stay replaceable-proof);
- `OeaWorkspace` contains no `CATEGORY_GAME` / `CATEGORY_SOCIAL` (no guessed folder
  names) and still has `suggestFolderName`;
- `OeaHomeViews` has `drawIconPlate`; `OeaIconController` still calls `attempt(1)`.

`python3 tools/check-kotlin.py` → 326 sources OK.

### CI runs

| Run | Result |
| --- | --- |
| `45426757208` | **red** — `e: OeaIconController.kt:159:44 Unresolved reference 'attempt'`: the new retry was written as a lambda that calls itself while declaring itself. A local function may; a lambda initialising that function cannot. |
| `45427568541` | **green** — `build-apk` and `build-laptop-apk`; 383 unit tests across launcher, engine and games, `OeaAppFamiliesTest` included; the smart-folder guard step and every earlier guard passed. |

### Device checklist

1. Home after a reboot: no tile is an empty hole; any icon that has not resolved
   shows its initial until it does, then replaces itself without a reload.
2. Install a second app that shares a uid with an installed one (two apps from one
   publisher): a family folder appears at the bottom of page 1 with a derived name.
3. Rename that folder, then uninstall one of its apps: the folder keeps your name.
   Reset the family in Settings and re-add: the derived name returns.
4. Uninstall every app of a family: its folder leaves Home and the apps that remain
   are still on Home as icons.
5. Settings › Home screen › Smart folders lists the families found on this phone
   (not a canned list), and toggling one off then on recreates/dissolves the folder.
6. Long-press the exclamation-mark tile and note which app it is — that glyph is not
   OEA's (see §1), so the answer belongs with that app or the ROM.
