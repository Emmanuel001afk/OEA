# Console review — 24 November 2026

The Console is OEA's command centre: diagnostics, device information, storage, memory, battery,
thermals, privileges, the command system and the doctors. It is good at being a **diagnostics
console**. It was not a **phone master** and it was not an **antivirus**, and it said so in the
places where it mattered:

* "is this phone safe?" opened the Play Store. That is an external platform doing the work —
  unavailable offline, absent on a phone without Google services, and a button that does nothing
  on a build like this one;
* OEA's own scanner could only match a **SHA-256 list the user had to import from somewhere
  else**. Out of the box it detected EICAR and nothing else, and its install watcher bailed out
  with "No malware signatures installed";
* the storage sheet could clear OEA's own cache and its diagnostic logs, and nothing else.

This pass builds both missing halves on the phone, with nothing fetched, uploaded or consulted:
a **rules engine** that audits every installed app, and the **phone-master tools** that were
missing from storage. §3 is what it deliberately does not do.

---

## 1. What was wrong

### 1.1 The security story was an advertisement for someone else's scanner

The security sheet's prominent rows were *"Google Play Protect"* — which opened the Play Store —
and a note explaining that OEA "cannot run or control its scan". Everything the phone itself
could answer was missing: who signed each app, where it came from, which dangerous capabilities
it holds *together*, whether an app with Accessibility has no icon.

### 1.2 Nothing to detect with, and nothing watching

`OeaSignatureEngine` is an honest exact-file SHA-256 matcher — but it has no definitions of its
own. `OeaSecurityScanJob` (the install watcher) required `security/signatures.txt` to exist and
notified *"No malware signatures installed"* otherwise, so the one thing an antivirus must do
continuously — check what arrives — did nothing at all on a fresh install.

### 1.3 The permission review counted, it did not reason

`scanSecurity` flagged any non-system app holding **two or more** sensitive permissions, with
one generic sentence. Two ordinary permissions produced the same finding as the actual
banking-trojan shape (read the texts *and* send them), a debug-signed sideload looked exactly
like a store install, and there was no verdict, no severity, no allow list, and no record of
when the last scan happened.

### 1.4 The phone-master half was a storage readout

`showStorage` measured `cacheDir`, the diagnostic log and the wallpaper cache, and printed
`StatFs` numbers. It could not answer the questions a cleaner is for — *what is using the space,
what can be removed, how big is each app's cache* — because no part of that had been built.

---

## 2. What was built

### 2.1 An offline rules engine — `security/OeaThreatEngine.kt`

Eleven rules, pure Kotlin, compiled into the APK, each returning a finding with a severity and a
sentence that names the *shape*, not just the permission:

| rule | what it means | severity |
| --- | --- | --- |
| `sms-both-ways` | can read **and** send texts — the standard route to SMS-delivered bank codes | high |
| `accessibility-plus` | enabled Accessibility together with overlay, text or notification access | high |
| `overlay-installer` | draws over other apps **and** can install apps (the dropper shape) | high |
| `admin-plus` | device administrator with text or screen access (the screen-lock extortion shape) | high |
| `notifications-and-sms` | reads notifications and text messages | medium |
| `mic-and-calls` | microphone and call history | medium |
| `debug-signed` | signed with a public debug certificate, so the signature proves nothing | medium |
| `hidden-privileged` | no launcher icon, yet holds Accessibility, notification access or admin | medium |
| `sideloaded` | no store recorded as the installer | low |
| `old-target` | built for Android 7 or older, so newer permission rules never applied | low |
| `contacts-camera-catalog` | contacts, camera and the full app list | low |

`assess()` produces findings, a score and a verdict; `verdictOf` is deliberately not a sum —
three low findings are still low, and only a high finding makes an app high-risk.
`assessInstalled()` skips system apps and OEA itself, honours an allow list, sorts worst-first
and returns counts. The object imports nothing from Android, so all of it is unit-tested.

### 2.2 Gathering the facts — `security/OeaAppAudit.kt`

Everything the rules need, from the phone: the permissions an app **holds** (not just declares),
whether it can draw over other apps (App Ops, with the Android 8/9 fallback call), whether its
Accessibility or notification listener is actually **enabled** (`Settings.Secure`), whether it is
an **active device administrator**, the **subject of its signing certificate** (parsed from the
X.509 bytes), who installed it, its target SDK, its APK size, and whether it has a launcher
icon. It also finds APK files sitting in storage that nothing has installed yet, keeps the allow
list, and remembers the last scan.

### 2.3 The console's protection screen

`Device protection` replaces the Play Store advertisement as the primary answer:

* a verdict banner ("High-risk apps found" / "Apps need a look" / "All clear") whose wording
  never claims a phone is safe;
* tiles for apps checked, high risk and to-review;
* one card per app, worst first, with every finding's reason in plain English, and three
  actions — **App info**, **Uninstall**, **Allow** (which re-scans immediately);
* **installers waiting**: APKs in storage, deletable only through Android's own confirmation;
* **Watch new installs**, an on/off row for the install watcher;
* the optional **file signature scan** kept as the code-level check the rules cannot do, and
  **Android security settings**;
* a note explaining exactly how the rules work and that nothing is uploaded, downloaded or
  delegated to any external service.

An **Allowed apps** screen now exists too — the allow list is reversible, which a list you can
only add to is not. And **Offline by design** states what works with no network at all, plus the
only two places in the whole launcher that ever use one (the weather fetch and the latency probe)
— neither of which the console or its protection touches.

### 2.4 Install watch that works on a fresh phone

`OeaSecurityScanJob` now applies the rules to every newly installed or updated package **first**,
with no imported list and no network, and notifies only when the verdict is high or review,
naming the worst finding. The optional SHA-256 list still runs afterwards when one is installed.
The "No malware signatures installed" notification is gone: protection no longer begins with a
download.

### 2.5 The phone-master half

* **App caches** — per-app cache and data sizes from Android's own `StorageStatsManager`, top
  eight, each opening that app's storage page. Usage access is required by Android for this;
  without it the sheet explains why it is empty and offers the switch instead of showing zeros.
* **Large files** — the biggest files OEA can see, over 50 MB, with a delete that goes through
  `MediaStore.createDeleteRequest`, i.e. Android's own confirmation dialog. OEA never deletes a
  user file on its own authority.
* The existing, honest cleanup of OEA's own cache and diagnostic logs is kept as it was, and the
  sheet now has a **Phone master** section instead of one lone row.

---

## 3. What this deliberately does not do

* **No quarantine.** An ordinary app cannot move another app's APK or disable it. The honest
  actions are App info, Uninstall and Allow, which is what the sheet offers.
* **No app-code inspection without a list.** Reverse-engineering DEX on a phone is not something
  to do casually in a launcher; the SHA-256 list stays the route to code-level checking, and it
  is optional.
* **No cloud, no feed, no Play Protect, no update channel.** The rules ship with the build. A new
  rule needs a new build — that is the price of never phoning home, and it is stated in the UI.
* **No "boost" theatre.** No fake RAM freeing, no killing background apps to show a number go up,
  no clearing other apps' caches (Android does not allow it and the UI says so).
* **No firewall, no VPN, no web filtering.** Out of scope for this pass.

---

## 4. Verification

* **`OeaThreatEngineTest` — 24 methods**: each dangerous combination flagged on its own; a
  declaration without a grant is not a grant; install-time permissions count as held; a screen
  reader with Accessibility and nothing else is not flagged; the dropper, extortion and
  notification+SMS shapes; debug versus release versus unreadable certificates; a hidden app
  with and without privileged access; store versus sideload versus the system installer; Android
  7 versus current targets; system apps and OEA itself skipped; the allow list removing an app
  and counting it; the worst-possible app tripping **every** rule exactly once; a clean utility
  app producing no rows; worst-first ordering; three low findings staying low; and the wording,
  which must never contain "safe" for a flagged phone.
* **`Verify the console work` — 33 checks** in CI: the engine's public surface and all eleven
  rules, the engine importing no Android class and no network API, the audit's entry points, the
  console's five new screens and three new sections, **no** `com.android.vending`, no `market://`,
  no `getLaunchIntentForPackage` in the console, no cleartext traffic in the manifest, and the
  install watcher applying the rules with no signature database present.
* `tools/check-kotlin.py` — `STATIC CHECK OK — 318 sources`.
* Every guard was extracted from the workflow and run locally before pushing.

## 5. What to check on a device

1. **Open Console › Security › Antivirus & app audit.** It scans without any setup and shows a
   verdict. On a phone with a few sideloaded apps it should list them as "Worth a look" with the
   sideloading reason.
2. **Turn on Watch new installs**, then install any APK from a file manager. The notification
   must name the app and the worst reason it matched — with no signature list imported.
3. **Allow** one of the findings: the app disappears from the list, the Allowed apps screen shows
   it, and "Check again" puts it back.
4. **Storage & maintenance › App caches.** With Usage access, sizes appear and each row opens
   that app's storage page. Revoke Usage access: the sheet must explain, not show 0 MB.
5. **Storage & maintenance › Large files.** A video over 50 MB should be listed with its size;
   Delete must open Android's confirmation, not delete silently.
6. **Airplane mode.** Everything above must still work — the console has no network path.
7. **Security › Offline by design.** It should name the two launcher features that do use a
   connection (weather and the latency probe) and nothing else.
