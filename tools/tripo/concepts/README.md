# Send Off — Tripo character concepts

This folder holds the 2D references used to generate Send Off characters with
Tripo3D. **The target game is `Send Off`** (OEA's standalone competitive
shooter, engine id `arena`), not Chess, Endless Days or the launcher.

## `send-off-soldier-concept.png`

- Purpose: image-to-3D reference for the local player's hero soldier in
  `OeaArena` / `CharacterStudio`.
- Style: tactical battle-royale soldier, dark armoured vest, gloves, cargo
  pants, combat boots, plain white background, studio lighting, full body.
- Pose: facing directly forward, relaxed A-pose (arms ~45°, hands empty,
  legs slightly apart) so Tripo's rig-check can find a biped and a T/A-pose
  mesh can be skinned cleanly.
- Prompt used:

  > Full body 3D game character concept art of a tactical battle royale
  > soldier for the game Send Off, wearing a dark armored vest, gloves, cargo
  > pants, and combat boots, standing straight facing directly forward in an
  > A-pose with arms out at 45 degrees and legs slightly apart, empty hands,
  > plain solid white background, studio lighting, stylized Free Fire and Call
  > of Duty Mobile character style, full head to toe visible

## Reference sheets for image-to-model (generated from the shipped mesh)

`reference_sheet.py` renders the reference set straight out of the `.oeac` the
game loads, so the art direction and the shipped asset cannot drift apart:

```bash
python3 tools/tripo/reference_sheet.py hero
python3 tools/tripo/reference_sheet.py hero --tile 900x1350   # bigger tiles
```

It writes:

| file | use |
|---|---|
| `send-off-<id>-front.png` | front orthographic |
| `send-off-<id>-three-quarter.png` | form read |
| `send-off-<id>-side.png` | depth |
| `send-off-<id>-back.png` | the view image-to-3D always invents |
| `send-off-<id>-tpose-front.png` | **submit this one for rigging** |
| `send-off-<id>-tpose-side.png` | T-pose depth |
| `send-off-<id>-head-front.png` | face detail for the texture |
| `send-off-<id>-head-three-quarter.png` | face form |
| `send-off-<id>-sheet.png` | all six body views on one page |
| `send-off-<id>-head-sheet.png` | both head views |

White background, orthographic, no weapon, no props: a perspective beauty shot
makes the head too large and the feet too small, and anything held in a hand
comes back as geometry.

## Local characters (no Tripo required)

`concept_to_oeac.py` samples skin/hair/vest/shirt/pants/boot colours from
`send-off-soldier-concept.png` and builds rigged humans on a 1.80 m,
seven-and-a-half-head skeleton, in OEA's 19-bone bind pose. The shapes live in
`soldier.py` (anatomy) and `humanoid.py` (lofting); each identity has its own
palette and physical kit (cap, mask, helmet, hood, backpack), so bots are
recognisable rather than recolours.

```bash
python3 tools/tripo/concept_to_oeac.py --all        # all six + manifest.json
python3 tools/tripo/concept_to_oeac.py --variant bot_heavy
python3 tools/tripo/preview_oeac.py                 # single character, front view
python3 tools/tripo/preview_lineup.py               # six-character lineup
```

### Looking at a mesh

`render_oeac.py` is the tool you want when a character looks wrong. It is a
shaded, depth-buffered software renderer with an orthographic camera at any
angle, and it can pose the skeleton with a port of the engine's `Skeleton.update`:

```bash
python3 tools/tripo/render_oeac.py hero --views front,side,back --white
python3 tools/tripo/render_oeac.py hero --yaw 30 --pose run --tile 400x700
python3 tools/tripo/render_oeac.py hero --yaw 0 --zoom 4.6 --centre-y 1.66   # head
```

Poses: `bind`, `tpose`, `aim`, `run`, `crouch`, `reload`. The old preview filled
every triangle with one flat colour and had no depth buffer, which is why a mesh
can look fine there and boxy in the game — do not judge geometry with it.

## Asset notes

* Bind pose: arms hang 8° out, not 45°. The old A-pose put the mesh's elbow
  24 cm from the rig's elbow.
* Bone naming: a bone is named for the joint it *starts* at. `SHOULDER` is the
  upper arm, `ARM` the forearm, `FORE` the hand. Weighting one joint too far
  down makes the upper arm swing with the elbow.
* Cloth and the skin under it must use the *same* weight curve, or they shear
  apart at a bend and skin shows through the sleeve.
* There is no deltoid blob: the sleeve's own loft rises over the joint and *is*
  the shoulder. See `docs/send-off-character-anatomy-2026-11-23.md` for the six
  approaches that were tried and why each one failed.
