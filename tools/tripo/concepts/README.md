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

## Local stopgap characters (no Tripo required)

`concept_to_oeac.py` reads this PNG, samples its skin/hair/vest/shirt/pants/boot
colours, and builds rigged low-poly humans in OEA's 19-bone bind pose. Each
identity has its own palette and physical kit (cap, mask, helmet, hood or
backpack), so bots are recognisable rather than simple recolours.

```bash
python3 tools/tripo/concept_to_oeac.py --all       # all six + manifest.json
python3 tools/tripo/concept_to_oeac.py --variant bot_heavy
python3 tools/tripo/preview_oeac.py                # single-character preview
python3 tools/tripo/preview_lineup.py             # six-character lineup
```

Generated identities (see `oea-engine/src/main/assets/characters/manifest.json`):

| Id | Name | Role | Kit |
| --- | --- | --- | --- |
| `hero` | Ranger 01 | local player | vest, A-pose, no headgear |
| `bot_ranger` | Ranger | assault | green cap |
| `bot_medic` | Medic | support | white/gray face mask |
| `bot_heavy` | Bulwark | heavy | helmet + backpack |
| `bot_scout` | Scout | recon | tan hood |
| `bot_night` | Wraith | stealth | black helmet + mask |

`character-lineup.png` and `local-mesh-preview.png` in this folder show the
result. These are **stopgaps**, not AI 3D: clean A-pose rigs, image-derived
colours and simple camo/fabric textures, but no photoreal scan detail.

## 3D weapons

`weapons_to_oeam.py` authors compact `.oeam` meshes for all 13 Send Off weapons
(pan, pistols, SMG, rifles, sniper, LMG, launcher, mortar, howitzer). Each mesh
carries its own paint in vertex colours and is loaded by `WeaponAssetLoader`;
the original procedural boxes remain as a fallback if a file is missing.

```bash
python3 tools/tripo/weapons_to_oeam.py
python3 tools/tripo/preview_weapons.py
```

Output: `oea-engine/src/main/assets/weapons/*.oeam` and
`weapon-preview.png`. The generator uses only the Python standard library,
so it can be re-run offline on any machine.

## Rules and bot design sheet

`preview_rules.py` parses `ArenaRules.kt` and renders every mode's objective,
score limit and clocks, plus the three bot personalities, into
`rules-preview.png`. Because the values are parsed from the Kotlin table, the
sheet cannot drift from the game — re-run it after changing a mode.

```bash
python3 tools/tripo/preview_rules.py
python3 tools/check-rules.py    # consistency + no stale HUD literals
```

## Next step

Feed this PNG to Tripo `POST /v3/generation/image-to-model` with
`model: "P2-20260801"`, `face_limit: 5000`, `texture: true`, `pbr: true`, then
rig-check → v3 biped rig → retarget, and convert the downloaded GLB with
`tools/tripo/glb_to_oeac.py`. Do not commit the raw Tripo API key or the API
response URLs; download the GLB and keep only the converted `.oeac` plus its
textures in the shipped game.
