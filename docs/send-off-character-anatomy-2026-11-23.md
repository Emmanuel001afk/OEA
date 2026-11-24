# Send Off — anatomy rewrite and Tripo reference set

**Date:** 2026-11-23
**Branch:** `arena/f9221963-oea`
**Scope:** the shipped character assets, the renderer used to inspect them, and
the reference imagery the Tripo3D image-to-model pipeline consumes.

---

## The complaint, in one line

The characters did not look human. They looked like boxes and cylinders with
spheres hung on the ends, and the user's screenshot of the front view made that
unavoidable: a lollipop head on a thin neck, two tubes for arms ending in
ping-pong balls, a slab for a torso, a skirt with legs coming out of it.

## What was actually wrong

Four separate faults, each of which alone would have kept the old code from
ever reading as a person:

1. **The rig was not the mesh.** `concept_to_oeac.OFFSETS` put the elbow at
   y = 0.98 with a 7 cm forearm, while the mesh drew the arm from y = 1.40 down
   to y = 1.02. The arm swung around a point 24 cm from its own elbow. No amount
   of surface detail fixes a skeleton that does not match its skin.
2. **Bone roles were off by one joint.** A bone in this engine is named for the
   joint it *starts* at: `SHOULDER` at the shoulder, `ARM` at the elbow, `FORE`
   at the wrist. The old generator weighted the upper arm to `ARM`, so the whole
   upper arm swung forward whenever the elbow bent — the puppet-on-a-stick look.
3. **The preview renderer hid the mesh.** `preview_oeac.py` filled every
   triangle with one flat colour and no depth buffer. A decent mesh and a bad
   one looked identical through it. Several of the "boxy" impressions in earlier
   review rounds were renderer artefacts, not geometry.
4. **Primitives instead of anatomy.** A human silhouette is made of things that
   *taper* and of masses — deltoid, pectoral, quadriceps, calf, glute, jaw — that
   sit on the outside of the taper. A cylinder with a sphere on the end has
   neither.

## The new pipeline

```
concept palette ──► soldier.py ──► humanoid.py ──► .oeac ──► the phone
   (sampled from        anatomy        lofts,        (19 bones,
    the concept PNG)    stations       welded        same stride
                                       normals)      SkeletalMesh uses)
```

* **`tools/tripo/humanoid.py`** — the mesh kernel. Cross-sections are lofted
  along a path with a *swept frame* at every station (tangent from the
  neighbouring stations, depth axis = world +Z projected perpendicular). That
  is what lets a sleeve curve over a deltoid and still run straight down the
  arm without shearing. Cross-sections are super-ellipses, so the torso can be
  a rounded rectangle rather than an egg. Normals are welded per vertex, so the
  surface shades smoothly instead of faceting.
* **`tools/tripo/soldier.py`** — the anatomy. `BIND` is a real 1.80 m skeleton
  (7.5 heads, 0.30 m upper arm, 0.26 m forearm, 0.42 m thigh, 0.415 m shin) on
  the engine's 19-bone hierarchy, and everything is built *around* those joint
  positions. Masks: biceps forward of the bone, triceps behind it, quadriceps
  forward of the femur, gastrocnemius behind the tibia, jaw narrower than the
  cheekbones, eyes at half the head's height.
* **`tools/tripo/render_oeac.py`** — the eyeball. Orthographic camera at any
  yaw/pitch, depth buffer, Lambert + hemisphere + rim lighting, 2× supersampling,
  and a faithful port of `Skeleton.update` so any pose can be checked offline.
* **`tools/tripo/concept_to_oeac.py`** — still samples the concept PNG for
  palette, still writes the manifest, now delegates the shapes.

## What it looks like now

| | before | after |
|---|---|---|
| triangles / character | 3.9 k | 11.6 k (`hero`) |
| limb cross-sections | circles | anatomically biased super-ellipses |
| hands | one sphere | palm + four fingers + opposed thumb |
| feet | two boxes | last, toe box, heel block, lugged sole |
| head | ellipsoid + sphere jaw | 12-station skull loft, brow, nose with wings, lids, lips |
| shoulders | ends of two tubes | one continuous rise out of the trapezius |
| torso | stack of rings | chest/waist/hip profile with a flat back |
| asset bytes, all six | 3.0 MB | 5.8 MB |

The triangle count went up threefold and the assets from 3.0 MB to 5.8 MB for
all six. That is the honest price of a lofted surface instead of a box: the old
mesh was cheap because it was nothing. A `hero.oeac` is 726 KB, of which 139 KB
is the index buffer; the vertex buffer is one 20-float record per vertex with no
duplicates — welding on the full attribute set fuses nothing, so there is no
slack left to reclaim. Both files are still two orders of magnitude under
`CharacterAsset.MAX_VERTICES` / `MAX_INDICES`.

## Things that were tried and were wrong

Recorded because each looked plausible on paper and cost real iterations:

* **A sleeve shell stitched to the torso's own vertices.** Facet counts and
  twists never line up; it spikes into a cape.
* **A sleeve that starts above the shoulder joint with a small radius.** Reads
  as an epaulette on a stick.
* **A flat cap over the joint.** Reads as a shoulder pad.
* **A ball over the joint.** Reads as a ball.
* **A path that rises over the shoulder then drops to the arm.** Creases.
* **A sleeve tube that stops below the shoulder line.** Leaves a bare strip of
  skin at the tip, because the angle between the arm and the shoulder line is
  90° and a tube can never cover the top of the joint.
* **What works:** one tube that starts *inside* the torso shell, bulges to the
  acromion, and tapers down the arm. The junction is hidden inside the body, so
  there is nothing to seam, and the deltoid *is* the top of the sleeve.
* **Leaving the skin's top ring at the shoulder joint.** The sleeve covers the
  joint, but the arm's own first cross-section sits a few millimetres *above* the
  sleeve's top surface there, so it punched through as two pink wedges at the
  shoulder tips — visible only at a 3× zoom, and only after the rest of the mesh
  got good enough for the eye to reach it. The skin loft now starts a tenth of
  the way down the upper arm, uncapped, entirely inside the sleeve. A shirted
  shoulder *is* cloth: there is no skin to draw above the sleeve, so drawing none
  is correct rather than a compromise.

Two rendering traps, too:

* A **global** winding fix cannot repair a mesh made of many shells — the torso's
  volume swamps a sleeve's. A **signed-volume** test per shell cannot see an
  *open* tube at all, because its signed volume is ~0 whichever way it winds.
  The test that works is direction: does the shell's surface face away from its
  own centroid? That answers correctly for capped and open shells alike.
* An **open tube** is invisible in a wireframe and fatal with back-face culling:
  the hole reads as a window onto whatever is behind it. Here it was bare skin,
  and it looked like a torn shoulder.

## Reference imagery for Tripo3D

`tools/tripo/reference_sheet.py` renders the set from the shipped `.oeac`, so
the reference and the asset cannot drift apart:

| file | purpose |
|---|---|
| `send-off-hero-front.png` | front orthographic |
| `send-off-hero-three-quarter.png` | form read |
| `send-off-hero-side.png` | depth |
| `send-off-hero-back.png` | the side image-to-3D always invents |
| `send-off-hero-tpose-front.png` | **the pose to submit for rigging** |
| `send-off-hero-tpose-side.png` | T-pose depth |
| `send-off-hero-head-front.png` | face detail for the texture |
| `send-off-hero-head-three-quarter.png` | face form |
| `send-off-hero-sheet.png` | all six views on one page |
| `send-off-hero-head-sheet.png` | both head views |

Rules the set follows, and why: plain white background (no gradient for the
model to mistake for scenery), no weapon and no props (they become geometry),
orthographic not perspective (perspective makes the head too large and the feet
too small), and the arms out in a T-pose for the rigging submission — Tripo's
rig-check finds a biped far more reliably from a T-pose than from a relaxed
A-pose.

Submit `send-off-hero-tpose-front.png` and `send-off-hero-tpose-side.png` to
`POST /v3/generation/image-to-model` with `P2-20260801`, `face_limit 5000`,
`texture: pbr`, `export_uv: true`; then `rig-check` → `rig` (`biped`, `spec` of
`mixamo`) → `retarget`. The rest of that pipeline, including the API shapes and
the local `glb_to_oeac.py` conversion step, is already specified in
`docs/send-off-tripo3d-character-plan-2026-11-23.md` and is unchanged.

## Verification

Structural only — no JDK or Android SDK in this environment, so nothing here is
a device test.

```
STATIC CHECK OK — 306 sources
RULES OK — 12 modes, 12 exposed in the lobby
HOST/JOIN OK
GAMEPLAY RULES OK
CONTROL LAYOUT OK - 14 controls on 6 screen shapes
glb_to_oeac smoke test passed
weapons_to_oeam smoke test passed
```

Plus `tools/check-character-assets.py`, which is now part of the suite:

```
CHARACTER ASSETS OK — 6 characters, 19 bones, normalised weights, outward winding, poses behave
```

It reads each `.oeac` the way `CharacterAssetLoader` does — the loader throws on
a bad file and the game silently falls back to the procedural rig, which looks
plausible enough that nobody notices the catalogue was never used — and fails on
the things
that are invisible until someone looks closely: a bone id outside 0–18, weights
that do not sum to 1, a missing texture, a shell wound inward (invisible with
back-face culling), a bone that owns no geometry, geometry drawn more than 0.30 m
from the joint its weights name, a bind pose that is not ~1.80 m tall, or a
T-pose and a crouch that do not visibly widen and lower the character.

On top of that, all six characters and all six poses (`bind`, `tpose`, `aim`,
`run`, `crouch`, `reload`) were rendered and inspected — the joints bend where
the mesh draws them, which was the fault that started this.

## Files

* added `tools/tripo/humanoid.py`, `tools/tripo/soldier.py`,
  `tools/tripo/render_oeac.py`, `tools/tripo/reference_sheet.py`,
  `tools/check-character-assets.py`
* rewrote `tools/tripo/concept_to_oeac.py` (shapes), `tools/tripo/preview_lineup.py`
  (now shaded), and every `oea-engine/src/main/assets/characters/*.oeac` +
  `manifest.json`
* regenerated the images under `tools/tripo/concepts/`
