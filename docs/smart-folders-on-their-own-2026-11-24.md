# Smart folders on their own, and the icons that never loaded — 2026-11-24

Two things were asked for on the same Home screen: *what is this* about tiles that
looked broken, and smart folders that identify themselves instead of being
*"detailed or written down"*.

This document is the second pass. The first one shipped a namespace-based family for
Google, a uid/certificate-based family for everything else, and a letter plate on any
tile whose icon had not arrived. Both of those were wrong on a real phone, and the
correction is what this file now describes.

## 0. What the first pass got wrong

**Families.** Grouping by *shared Android uid* and *shared signing certificate*
sounded like a stronger version of "publisher", and it is not:

- every preinstalled app on a phone is signed with the same platform key, so a
  certificate group is "half the phone";
- many preinstalled apps share one of Android's reserved uids
  (`android.uid.system` and friends), so a uid group is "the other half".

On the reporting phone that produced a folder holding the Camera, a "Camera close"
entry and the SIM Toolkit. Nothing about those apps says they belong together; the
phone merely signed and installed them the same way.

**Icons.** A tile with no icon got a plate with the app's initial. That is a
workaround drawn over the fault: the tile is an app, and the only thing that belongs
in it is that app's icon.

## 1. What the screenshot actually showed

- `OeaTileView.drawIcon` returned immediately when the drawable was `null` — a tile
  with no icon drew its label and nothing else.
- `OeaIconController.request()` resolved icons off the main thread and, when a
  resolve came back `null`, **removed its waiters and never called them**. A tile
  gets one request per bind, so one failed resolve left it blank for the session.
- More importantly, **a render that produced no pixels was cached like any other**.
  An adaptive icon whose background and foreground layers are both empty (some
  preinstalled apps ship exactly that), or a themed tint over nothing, produced a
  fully transparent bitmap. `load()` wrote it to the disk cache, and from then on
  read it back for ever: the hole survived restarts because nothing ever looked at
  what was in the file.

The exclamation-mark tile in the screenshot is **not** OEA's. No OEA source draws an
exclamation mark (the only `"!"` in the launcher is the calculator's factorial key),
so that glyph arrives with the tile's own icon resource. Long-press the tile to see
which app it is.

### What changed

| Where | Before | Now |
| --- | --- | --- |
| `OeaIconController.load` (`isBlock`/`isBlank`) | a decoded bitmap was an icon | a bitmap with no pixels is deleted from the disk cache and never re-used or written |
| `OeaIconController.render` — adaptive branch | empty layers were masked, giving an empty tile | the icon is drawn as it ships, then Android's `defaultActivityIcon` |
| `OeaIconController.render` — legacy branch | an empty probe was wrapped in a shape, giving a plate with no icon | `defaultActivityIcon`, instead of a plate |
| `OeaIconController.request` | one resolve; a `null` result stranded every waiting tile | one delayed retry off the main thread |
| `OeaHomeViews.drawIcon` | a `null` icon drew nothing (and, in the first pass, a letter plate) | nothing is faked: the plate is gone, because the engine now always has an icon |

## 2. "Automatically, not detailed or written down"

The original smart folders read a **catalogue typed by hand** — `com.google.`,
`com.microsoft.`, a list of Meta packages, Adobe/Amazon/Transsion, with exclusions.
A family nobody had written down never got a folder; a package that merely looked
like the list did. And a folder made by dropping one app on another was named by
turning the most common `ApplicationInfo.category` into a word ("Games", "Social", …)
and writing it into the layout.

### Identification: one signal, and it is the one a folder means (`workspace/OeaAppFamilies.kt`)

A family is **one publisher namespace with two or more launchable apps installed**:

- the namespace is the first three package-name segments — `com.google.android.*`,
  `com.microsoft.office.*`, `com.transsion.*`;
- `com.android.*` is refused outright: that is the operating system, not a publisher;
- a namespace needs a third segment (`com.example` alone is not one);
- one package is one app, however many launcher activities it exposes;
- nothing else. Not the label (a label can be localised or faked), not the uid, not
  the signing certificate — see §0 for why those two are not "publisher" on Android.

Two apps under different publishers therefore stay two apps, and the Camera never
joins the SIM Toolkit in a folder.

### Naming (`titleFor`, `commonLabelPrefix`, `sharedFirstWord`)

`"Google Maps" + "Google Photos"` → **Google**; `com.transsion.phoenix` +
`com.transsion.phonemaster` → **Transsion**; nothing derivable → **Apps**. Labels
only *name* a group the namespaces already agreed on; generic words ("Mobile",
"Apps", "Pro") are never taken as a title.

### The name maintains itself (`OeaSmartFolders.KEY_WRITTEN_TITLE`)

A family folder remembers the title **OEA** wrote. When the family changes, OEA
replaces its own title and leaves yours alone: rename a family folder once and that
name survives every later reconcile. The folder's identity is its contents; its name
follows them until you take it over.

### The hand-made folder is plain (`OeaWorkspace.suggestFolderName`)

Dropping one app on another now names the folder **Folder** ("OEA" when it holds only
OEA's own apps). No category is guessed, no word is written into the layout.

### Settings

`Settings › Home screen › Smart folders` lists the families **this phone was found to
have** on the last reconcile (persisted by the pass itself), not a catalogue. Per
family: create-or-not, and "reset my choices" to forget apps you moved out.

## 3. Rules that still hold

- A family folder appears only once two or more of its apps are installed, and
  follows installs and uninstalls.
- Moving, adding and removing apps by hand wins; apps taken out are remembered
  (`excluded_<family>`) and never re-added.
- Deleting a family folder switches that family off (`enabled_<family> = false`);
  it can be re-enabled in Settings.
- A folder whose family disappears is **dissolved into its members' icons**, so no
  app leaves Home when recognition changes — including folders created by the earlier
  uid/certificate ids, which is how the mixed folder above goes away and its apps
  come back as icons.
- The pass short-circuits on an unchanged membership signature (`cachedSignature`).
- All state stays in the `oea_smart_folders` preferences file.

## 4. Verification

**JVM unit tests** — `oea/src/test/java/com/oea/launcher/workspace/OeaAppFamiliesTest.kt`
(9 tests): a publisher namespace with two apps is a family; **platform apps that
share the system keys never form a folder** (Camera + SIM Toolkit + Settings); one
app is not a family; a namespace needs a third segment; two apps of one publisher are
a family and a stranger is not; titles come from the apps when they agree and from
the publisher when they do not; labels never decide membership; one package is one
app even with two activities; ids are safe for preference keys.

**CI guards** — `Verify smart folders are identified from the phone, not written down`
in `.github/workflows/oea-apk.yml`:

- `namespaceOf` exists, refuses `com.android.*` (`parts[1] == "android"`), and
  `OeaSmartFolders` calls `OeaAppFamilies.discover(`;
- `OeaSmartFolders` may not contain `CATALOGUE`, `com.google.`, `com.microsoft.`,
  `com.transsion` or `getPackageInfo`;
- `OeaAppFamilies` may not contain `SHARED_UID` or `SHARED_SIGNATURE`, and
  `OeaAppModel` may not carry a `uid`;
- `KEY_WRITTEN_TITLE` still exists (a user's name must remain untouchable);
- `OeaWorkspace` contains no `CATEGORY_GAME` / `CATEGORY_SOCIAL` and still has
  `suggestFolderName`;
- `OeaIconController` has `isBlank(`, `defaultActivityIcon`, `deleteDisk(disk)` and
  `attempt(1)`; `OeaHomeViews` has no `drawIconPlate` and no letter initial.

`python3 tools/check-kotlin.py` → 334 sources OK.

### CI runs

| Run | Result |
| --- | --- |
| `45426757208` | **red** — `e: OeaIconController.kt:159:44 Unresolved reference 'attempt'`: the retry was a lambda that called itself while declaring itself. A local function may; a lambda cannot. Fixed in `1ef063b`. |
| `45427568541` | **green** — first pass, `build-apk` + `build-laptop-apk`, 383 unit tests. |
| `45428650096` | **green** — first pass with the run table recorded. |
| `45462468720` | **green** — this pass: `build-apk` + `build-laptop-apk`, 382 unit tests (9 of them `OeaAppFamiliesTest`, including the Camera + SIM Toolkit regression), the namespace guard and every earlier guard. |

### Device checklist

1. Home after a reboot: every tile shows its app's icon; no blank squares, no
   letters. If one was blank before this build, it fills in without a reload — the
   old transparent entry in the icon disk cache is deleted the first time it is read.
2. No folder exists that mixes unrelated preinstalled apps (Camera, SIM Toolkit,
   Settings). If the previous build made one, it is dissolved and its apps return to
   Home as icons.
3. Install a second app from a publisher already on the phone (`com.<publisher>.*`):
   a family folder appears with a derived name.
4. Rename that folder, uninstall one member: your name stays. Reset the family in
   Settings: the derived name returns.
5. Settings › Home screen › Smart folders lists the families found on this phone, and
   toggling one off then on dissolves/recreates the folder.
6. Long-press the exclamation-mark tile and note which app it is — that glyph is not
   OEA's (see §1).
