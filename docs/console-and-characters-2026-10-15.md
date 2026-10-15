# Console completion + character anatomy (2026-10-15, round 3)

This round lands two groups of work and one process lesson, after
`docs/stability-and-games-roundup-2026-10-15.md` (PR #54, merged).

## 1. The CI failure on PR #54, proven from evidence

What was known: `build-apk` failed after 1m43s with empty logs through the
API. What was proven: the workflow's own step results
(`actions/jobs/<id>/steps`) showed every grep-verification step passed and the
failure was inside **Build standalone OEA Launcher APK** — a compile failure,
not a workflow check. The runner-side cause of empty logs is separate: log
downloads redirect off api.github.com and the sandbox reaches GitHub + codeload
only.

Root cause found by statically cross-checking every symbol my earlier commit
referenced: `OeaArena.kt` called `env.set(envNight)` / `env.set(envDay)`;
`Environment`'s copy method is named `copyFrom`. Three call sites fixed in
`oea-games/src/main/java/com/oea/games/arena/OeaArena.kt`. CI on the fix commit
went green in 3m11s, which also re-validated the whole PR #54 batch (unit
tests + all four APKs).

Secondary workflow fix in the same commit: the *Comment Gradle error on PR*
step had been "succeeding" while posting nothing, because the job had only
`contents: write`. `pull-requests: write` was added so future failure logs
actually reach the PR.

## 2. OEA Console completion (`OeaConsoleApp.kt`)

The command surface was already broad (device/package/overlay/multitask/dnd/
lowmode/island/settings/appops/shell/doze/security/performance/notification/
appearance). Completion work, guided by "genuinely competitive":

- `help <topic>` now gives per-operation documentation — usage, what it does,
  why access is needed, how to authorize, how to verify — for 21 topics.
  `help` alone keeps the overview and now lists the topic scheme.
- Output is shareable: **Copy output** and **Share output** buttons on the
  console sheet (clipboard via `ClipboardManager`, share via `ACTION_SEND`),
  both guarded when output is empty.
- New commands:
  - `launch <package>` — opens an app's launcher activity and reports the real
    result (with freezer cross-reference hints).
  - `device network` — ConnectivityManager snapshot: transports, validated
    internet, metered, reported downstream, roaming. No extra permissions.
  - `diagnostics summary` — OEA's own fault store broken down per subsystem;
    honest wording that it is not adb logcat.

## 3. Character anatomy pass (`CharRig.kt`, shared rig)

The rig already had articulated thigh/knee/ankle chains, a chest/riding head,
run cycles and aim poses. This pass targets the details that read as
"professional character" versus "stylized mannequin":

- **Ears**: two skin-tone ellipsoids placed outward at head level, following
  a new per-pose `headYaw` (head-only turn, currently used subtly — the field
  is wired through the pose so games can animate glances without touching the
  body).
- **Palms**: a squashed ellipsoid past each wrist. Drawn unconditionally
  (hands are non-negotiable), sized to tuck under a held weapon.
- **Glove cuffs + boot cuffs + waist seam**: use a new optional `accent`
  material (team-tone darker). Cuff also helps hide forearm/hand seam; seam
  hides the shirt/pants colour switch at the belt.
- Rig API stays source-compatible (`accent` and `headYaw` are optional).

Adoption:
- Shot Out (`OeaArena.kt`): soldier rigs now run `detail = true` (both draw
  sites) with `accent = MathX.scale(color, 0.55f)` per team colour; all three
  environment presets applied via `Environment.copyFrom`.
- Runner (`OeaRunner.kt`): hero rigs get a dark-lerped team accent; ghost rigs
  pass their translucent material as accent so cuffs/seam stay ghosted.

Chess is unaffected (Chess 3D uses piece meshes, not the humanoid rig).

## 4. Verification honesty

- **Verified by CI (real compiler)**: PR #54 — the entire previous batch,
  after the `copyFrom` fix, `build-apk` green (3m11s) including unit tests and
  all four `assembleRelease` targets.
- **Verified statically here**: brace-balance, symbol-existence and
  reference-check audits on every file this round touched; team-colour accent
  call sites; rig call sites (Runner 4 rig constructions, Arena 2 pose sites,
  one ghost).
- **Not testable in this sandbox**: on-device look of the new anatomy (needs
  the renderer running) — proportions chosen conservatively to stay inside the
  existing silhouette, and the simulator/runner quality presets still apply.
- The remaining known-untestable items from the previous roundup stand
  unchanged (recents black-screen on the user's firmware, launcher-default
  flow on that build, weather location panel on real network).
