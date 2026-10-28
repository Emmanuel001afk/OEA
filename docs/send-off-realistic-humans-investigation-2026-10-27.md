# Send Off realistic humans — investigation, 2026-10-27

The question: can Send Off's characters stop being "shapes" and become human-like —
real appearance (skin, hair, faces, clothing) and real movement (joints, locomotion,
aiming, facial life)? Benchmarked against what AAA games actually do.

Short answer: **Photoreal AAA humans are not realistically reachable on this engine and
phone budget — that is a different league. "Human-like convincing humans" like the best
mobile battle royales is very achievable, and Send Off already has the right hooks
(`CharSpec`, Character Studio, procedural textures, VFX). The gap is a well-defined set
of engine features: skeletal mesh + skinning, animation clips + blending, a skin shading
model, real hair/cloth meshes, and procedural facial animation.**

## 1. What AAA games actually do

### 1.1 The character asset (UE5 MetaHuman as reference)

- A MetaHuman is a body skeletal mesh + a face rig with ~700–800 facial joints, a
  subsurface skin material with dual-lobe specular and animated wrinkle maps, a
  single-surface eye shader with parallax iris, and a groom system for strand hair
  [5](https://medium.com/@Jamesroha/a-beginners-guide-to-metahumans-in-unreal-engine-5-6-and-5-7-e9b14fadbf3d).
- Facial animation runs on blend shapes (morph targets), organised around FACS action
  units. ARKit's 52 shapes (eyeLookUp, browInnerUp, jawOpen, mouthSmile, mouthPucker…)
  are the de-facto standard; production rigs use 50–80 shapes, mobile rigs can run on
  15–20. Bone-based facial rigs are cheaper and common for mobile/MMO crowd characters
  [1](https://mocaponline.com/blogs/mocap-news/facial-animation-games-guide).
- Mocap is the norm for body animation; studios retarget motion-capture clips onto
  rigs, and use IK (foot IK, pelvis offset) so walk cycles don't "hover" [4](https://toxigon.com/creating-realistic-characters-with-ue5-meta-human-creator).
- Hair: strand-based groom with physics is the most expensive element; real games
  replace groom hair with texture-mapped hair cards at distance [15](https://mocaponline.com/blogs/mocap-news/metahuman-tutorial-unreal-engine-5).
- Cloth: physics simulation (UE Chaos cloth) with baked wrinkles; the wrinkles are the
  visual payoff, the sim is the cost.
- Skin shading: subsurface scattering (SSS) — light passing through skin, visible in
  ears/cheeks when backlit — plus two-sided SSS for thin parts, roughness ~0.4 with a
  roughness map, specular ~0.3–0.5, pores/wrinkles/color variation maps. SSS is the
  single biggest "not wax" factor [13](https://dev.epicgames.com/documentation/en-us/unreal-engine/subsurface-profile-shading-model-in-unreal-engine) [11](https://www.mimicproductions.com/post/what-is-subsurface-scattering-the-secret-behind-realistic-digital-human-skin).
- Rendering trinity: Nanite (virtualized geometry), Lumen (GI), Virtual Shadow Maps,
  TSR upscaling. Important nuance: **Nanite does not skin animated game characters** —
  characters keep LOD meshes; Nanite applies to hero props/static dressing [12](https://nastyrodent.com/what-is-unreal-engine-5/).
- Scaling to crowds: UE 5.8's MetaHuman Crowd runs hundreds of characters on mobile
  with simplified rigs, component-built (head/body/hair/clothing), Mass-orchestrated,
  Nanite-rendered at distance [16](https://dev.epicgames.com/documentation/unreal-engine/unreal-engine-5-8-release-notes) [17](https://www.unrealengine.com/news/unreal-engine-5-8-is-now-available).

### 1.2 What real mobile battle royales do

- Call of Duty: Mobile (Unity) and PUBG Mobile (UE) run photoreal-ish soldiers on
  mid-range phones at 60+ FPS. Their budgets: a hero character ~10,000–20,000 tris on
  mid-range hardware, supporting characters 3,000–5,000, crowd NPCs 500–1,000; POT
  textures 512–2048; and — the number that matters most here — **2–3 bone influences
  per vertex on mobile** (a Mali-G57 measured ~3 ms/frame difference between 2 and 4
  influences with 10 characters on screen) [12](https://triverse.ai/blog/how-to-create-low-poly-models-for-mobile-games) [3](https://truetech.dev/mobile-apps-development/services/animations/mobile-game-character-rigging-animation.html).
- Both games keep the realism promise with: high-contrast HDR-ish look, character LODs,
  and art direction that sells detail (pockets, stitching, camos) rather than raw scan
  fidelity [20](https://www.indiatoday.in/technology/features/story/call-of-duty-mobile-vs-pubg-mobile-two-splendid-battle-royale-games-but-is-the-chicken-dinner-stale-now-1531981-2019-05-22).
- The lesson for Send Off: the target is *believable humans at BR distance*, not
  close-up photoreal faces.

## 2. What Send Off has today (grounded in the code)

`OeaArena.kt` + `CharRig.kt` + `oea-game-engine.md`:

- Characters are **assembled from primitives**: unit spheres (scaled ellipsoids),
  capsule lines for limbs, cones for hair, one ellipsoid per facial feature. Each
  primitive is a separate `r.draw(...)` call — dozens of draw calls per soldier.
- **No skeleton, no skinning, no animation clips.** The pose is procedural math in
  `CharRig.draw()` (walk phase → sine-driven thigh/knee angles, aimHold arm angles).
  Joints don't exist; limbs are independently positioned primitives.
- **No facial animation** — eyes, brows, nose, mouth are static ellipsoids glued to
  the head. No blink, no look-at, no expression.
- Materials are a simple PBR-ish model: base colour + texture + emissive + specular +
  shininess + rim. **No SSS, no normal maps, no clear coat.**
- Hair = sphere chains; clothing = shell ellipsoids that only react to rain/dust tint
  and wind sway. No cloth sim.
- The engine (GLES 3.0 forward renderer) is strong where it matters for worlds
  (instancing, shadow maps, dynamic resolution, device tiering, ADPF) but has **no
  skeletal mesh, animation, morph-target or glTF support**. The engine doc already
  names these as next steps: *"Send Off characters are built from primitives; skeletal
  animation and glTF loading are natural next steps (`Mesh` already accepts arbitrary
  vertices)"* [oea-game-engine.md](oea-game-engine.md).
- What already helps: `CharSpec` (serializable, slider-driven body/face/hair/skin/
  outfit), Character Studio live editing, procedural fabric textures
  (`CharTextures`), `CharFx` weather/wet/dust/wind state, the fluid VFX pipeline, and
  host-authoritative netcode that would carry skeletal pose data fine.

## 3. Feasibility verdict

**"Real human" is two different promises, and they have different answers:**

| Goal | Verdict |
| --- | --- |
| Photoreal, scan-based, film-quality humans (AAA flagship) | Not realistically reachable: multi-million-dollar pipelines, scanned actors, proprietary engines, cinematic budgets. Not a phone-GLES-3.0-custom-engine project. |
| Human-like soldiers that read as real people at play distance — like COD Mobile / PUBG Mobile | **Achievable**, and Send Off is closer than it looks: the character data model, studio, textures and VFX already exist. The work is engine capability + character art pipeline, in a defined order. |

The honest cost framing: AAA realism comes from *data* (scans, mocap, hand-authored
meshes) and *engine features* (SSS, Lumen, cloth sim). Send Off can't buy the data at
AAA scale, but it can generate a convincing procedural human with real mesh topology,
real joints, and a proper skin shader — that is exactly how the current code is
already built, just one level of fidelity down.

## 4. The roadmap — in order of realism-per-effort

### Tier 1 — "read as humans, move like humans" (recommended first)

1. **Skeletal humanoid mesh generated from `CharSpec`.** Replace the primitive rig
   with a real parametric body mesh (limbs as tapered cylinders with joint-friendly
   topology: extra edge loops at elbows/knees, body/face from the existing FaceSpec /
   BodySpec dimensions). One skinned mesh + a few attachments per soldier instead of
   dozens of draw calls. The Character Studio sliders keep working because they already
   drive `CharSpec` — they'd now drive mesh parameters.
2. **Hardware skinning in the vertex shader (GLES 3.0 bone-matrix palette).** 4 bone
   influences/vertex is the mobile standard; ~30–50 bones/character is comfortable.
   Quaternion+offset bone upload halves shader register cost. This unlocks *everything*
   else. CPU skinning is fine for 12 characters as a fallback.
3. **Procedural locomotion on bones + aim poses.** The walk-cycle math that today
   positions primitives becomes real joint rotations (thigh/knee/shoulder/elbow) on
   the skeleton; blend idle/walk/run/aim/crouch/airborne from `Pose`. Reuse the
   existing `phase`/`speed` driving.
4. **Skin shading upgrade.** Keep the current shader but add: SSS approximation
   (subsurface tint toward red in backlit/rim areas — the cheapest big win),
   roughness/specular variation maps, subtle normal detail on skin, clear-coat on
   armor/gear. This alone moves characters from "plastic" to "person".
5. **Hair and clothing as real meshes.** Hair = texture-mapped mesh ribbons/cards
   (the AAA mobile-standard trick [15](https://mocaponline.com/blogs/mocap-news/metahuman-tutorial-unreal-engine-5)); clothing = proper shells with seams; keep `CharFx` weather behavior on the new meshes.

### Tier 2 — "alive"

6. **Animation clips + blending** (idle/walk/run/aim/reload/jump) with a small
   procedural or hand-keyframed clip library; retarget between characters via the
   shared rig (the MetaHuman-retargeting pattern [3](https://nastyrodent.com/ue5-metahuman/)).
7. **Procedural facial animation without mocap:** blinking (random timers), eyes
   tracking the crosshair/nearby players, brow tension with aiming/kills, jaw and
   mouth shapes for hit/kill voice events. 10–20 shapes or bone-driven — mobile-
   standard, not the 52-shape ARKit set [1](https://mocaponline.com/blogs/mocap-news/facial-animation-games-guide).
8. **Cloth-ish motion:** bone-driven coat/hair sway, belly/breast corrective shapes,
   knee/elbow bending bulges via vertex math on the skinned mesh.
9. **LODs + draw-call discipline:** one skinned mesh + helmet + vest + weapon per
   soldier (~5 draw calls × 12 players is fine); LOD0/LOD1 meshes with fewer bones at
   distance; keep `DeviceProfile` tiering to choose.

### Tier 3 — stretch (only if Tier 1–2 land and sell)

10. glTF/FBX hero-character import for special characters (engine already accepts
    arbitrary vertices; glTF loading is a documented next step).
11. Morph-target facial rig + audio-driven visemes for bot voice lines.
12. Physics cloth (CPU simple springs — the FluidGrid team already has the numerics
    skills) and strand-based hair on flagship GPUs only.
13. Real SSS (screen-space profile) on a quality tier; normal-mapped pores/wrinkles.

## 5. Performance guardrails (from the sources above)

- 60 FPS = 16.6 ms frame budget; GPU gets roughly half on mobile. Skinning,
  shadow maps and overdraw all draw from it [12](https://triverse.ai/blog/how-to-create-low-poly-models-for-mobile-games).
- Hero character 10–20k tris mid-range, supporting 3–5k, crowd <1k; 2–3 bone
  influences per vertex on mobile, 4 max; bone palette ≤ 64 per character
  [3](https://truetech.dev/mobile-apps-development/services/animations/mobile-game-character-rigging-animation.html) [5](https://game-developers.org/character-rigging-explained-the-complete-guide-to-skeletons-skinning-ik-fk-game-ready-rigs).
- Textures POT, 512–1024 for characters; 2048 only for hero close-ups.
- Send Off specifics: 12 players max, 30 Hz sim, host-authoritative snapshots — pose
  data is already a small FloatArray; a bone-pose stream is the same order of
  magnitude. Bots can reuse the same rig at LOD1.

## 6. Risks, honestly

- **The sandbox cannot compile** (no JDK/Android SDK — stated in the Character
  Studio doc); every change needs verification on a build machine + real devices,
  especially Mali/Adreno skinning cost.
- **GLES 3.0 ceiling:** morph targets are possible but expensive; SSS must be
  approximated, not physical. Vertex shader register pressure with 50-bone palettes
  needs testing on low tiers.
- **Art pipeline:** no DCC tools here — the procedural-mesh generation path is the
  only one that works without external artists, so Tier 1's "mesh from CharSpec" is
  both a design choice and a constraint.
- **Regression risk:** `CharRig` is shared with Endless Days; the new mesh path must
  be additive (legacy primitive draw stays until games migrate).

## 7. Suggested next step

Start Tier 1, step 1: a real skinned humanoid mesh generated from `CharSpec` with
hardware skinning in the existing GLES 3.0 shader — this is the single change that
makes the rest of the roadmap possible and that immediately ends "shapes" for the
player. Then the skin shader (step 4) and real locomotion (step 3) land together.

## 8. Status after the first implementation pass (2026-10-27)

The skeletal pipeline is written and wired into Send Off's character draw path
(`CharRig.drawSkinned`, called from `OeaArena.drawSoldier` for every soldier —
ground and sky). **Nothing in this section has been compiled** — the sandbox has
no JDK/Android SDK; every item needs a build-machine + device pass before it can
be called shipped.

**Landed (commit b812557, then this pass):**

- **Tier 1.1 — skeletal mesh from `CharSpec`.** `HumanBody` builds ten skinned
  parts from one spec (body, shirt, pants, hair, shoes, outer, headwear, vest,
  pack, face): proportions from the studio sliders, face from the FaceSpec
  dimensions, clothing from the `CharParts` catalog.
- **Tier 1.2 — hardware skinning.** `SkeletalMesh` (stride-20 vertex stream with
  4 bone indices + weights), `Renderer3D.drawSkinned` with a 19-bone uniform
  palette, `SKIN_VERT` doing the linear blend in the shader.
- **Tier 1.3 — procedural locomotion on bones.** `Skeleton.update` derives every
  joint rotation from the same `Pose` fields the primitive rig used (phase,
  speed, airborne, crouch, lean, aim, head yaw), plus bob/breathing sway.
- **Tier 1.4 — SSS-ish skin shader.** `u_subsurface` on the shared lit shader:
  a grazing-angle warm leak-through term, tinted per character and gated on the
  material (skin only). One uniform, no new pass.
- **Tier 1.5 — hair/cloth as meshes.** Hair caps/styles, outer shells, vest,
  pack, headwear are all generated meshes skinned to the skeleton.
- **Tier 2.7 (partial) — facial animation.** The eyes moved into their own
  HEAD-bound skinned mesh so they can blink: `CharRig.faceUpdate(t)` runs a
  2–6 s random blink schedule, the draw squashes the mesh in Y. Eye look-at,
  brow tension and jaw shapes are NOT implemented.
- **Tier 2.8 (partial) — cloth-ish motion.** Hair and outer shells sway with
  the world wind (`CharFx.windX/windZ`, fed by `VfxSystem`).
- **Tier 2.9 — LODs.** `drawSkinned` takes `lod` (0/1/2 by camera distance in
  `drawSoldier`): lod1 skips vest/headwear/pack, lod2 additionally skips
  outer/hair. Body/shirt/pants/shoes always draw.

**Second pass (d725f05 + follow-up):**

- **Tier 2.7 (rest, mostly) — facial animation.** Eye look-at: the face mesh
  rotates slightly toward the aim (head-yaw/aim-pitch glance). Brow tension:
  brows moved into their own HEAD-bound mesh and flattened in Y with a furrow
  driven by aim pitch, run speed and crouch. Jaw/mouth shapes remain open.
- **Tier 2.8 (rest) — joint bulge.** The limb tubes gain a smooth radius bump
  near the joint end (thighs 14%, upper arms 11%, forearms/shins 10%, clothing
  shells 9%): invisible when straight, reads as muscle/cloth deformation when
  the knee or elbow bends. (The joints already blend two bones per vertex.)
- **Tier 2.6 (partial) — reload pose.** `Pose.reload` drops the arms to the
  weapon at waist height and the weapon mesh dips with them. The local player's
  client runs the timer from the weapon's reload time (with the upgrade
  discount); remote players' reload state is not in the snapshot, so they aim
  normally.
- **Tier 2.9 (rest) — DeviceProfile tiering.** The LOD distance thresholds ride
  `QualitySettings.detailDistance` (0.7× on Low → 1.25× on Ultra): weak devices
  cull armor/pack/headwear detail closer in.

**Still open:** jaw/mouth shapes and remote-player reload poses (need snapshot
state), a clip library with real transitions (the current blend is implicit in
the pose math), belly/breast corrective shapes, DeviceProfile tiering for the
skinned-mesh complexity itself, all of Tier 3, and the build/device verification
of everything above.
