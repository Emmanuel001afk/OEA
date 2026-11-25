# Send Off — using Tripo3D for a genuinely good character

_Investigation and action plan, 23 November 2026_

## The short answer

Yes, Tripo3D can give Send Off a much better character than the current one, but **not by calling Tripo from the game**. Use it as an **offline art pipeline**: generate a soldier image/3D model in Tripo Studio or through the Tripo API, rig it there, export a GLB, convert that GLB into a compact OEA character asset, and ship the converted asset inside the one OEA APK.

The current soldier is not “bad because of the shader alone” — it is a set of ellipsoids, tubes and rings generated from `CharSpec`. (An earlier draft of this plan also blamed the skinning maths; that was wrong, see §1.1.) Tripo solves the *art* problem; the engine needs a **loader** for the art, which it now has.

Recommended route:

> **One hero soldier first** (the local player in Character Studio), bots stay on the current procedural rig → prove it on a device → then decide whether to roll AI-generated models out to bots.

---

## 1. What Send Off actually does today

Grounded in the code on this branch:

| Area | Current state |
| --- | --- |
| Game identity | Send Off is its own standalone launcher entry (`arena`), inside the single OEA APK. See `docs/send-off-independent-identity-and-look-2026-10-27.md`. |
| Renderer | OEA Engine, OpenGL ES 3.0, no glTF/GLB/FBX importer. `Texture.asset()` loads bitmaps; `GameStorage.file()` gives the game a private file folder. |
| Character data | `CharSpec` holds gender, face, body, skin, hair and layered outfit sliders. Saved as JSON in `CharacterStudio` via `GameStorage`. |
| Character geometry | `oea-engine/.../graphics/HumanBody.kt` builds ~10 skinned parts from `CharSpec`: body, face, brows, shirt, pants, hair, shoes, outer, headwear, vest, pack. The parts are procedural ellipsoids/tubes/rings, not authored human meshes. |
| Skeleton | `Skeleton.kt`: fixed 19 bones (pelvis → spine → chest → neck → head, arms, legs). Poses are procedural math from `CharRig.Pose` (phase, speed, aim, crouch, airborne, reload). |
| Skinning | `Renderer3D.drawSkinned` + `Shaders.SKIN_VERT`: 20-float vertex stride (pos, normal, uv, colour, 4 bone ids, 4 weights), `u_bones[19]`, up to 4 influences. The shader applies `u_bones[i]` directly, and `Skeleton.update()` publishes `poseWorld × inverseBind` — so `u_bones` is already a skin matrix, not a joint matrix. See the correction in §1.1. |
| Live studio | `CharacterStudio` edits `CharSpec`; every `spec.touch()` bumps `revision`; `CharRig.ensureForm()` rebuilds `HumanBody` on the next frame. |
| Send Off draw path | `OeaArena.drawSoldier()` picks a rig via `rigFor()`: local player uses the studio rig; bots are cached by colour/id. It drives `rigPose` and calls `drawSkinned()` per soldier, with LOD by distance. |
| Look target | `docs/images/send-off-character-studio.png` and `send-off-arena.png` are **design renders**, not screenshots of the running game (the docs say so). The shipped code produces the procedural version, which is why the character looks far worse than the target image. |
| Toolchain | No JDK/Android SDK in this sandbox, and the earlier character docs state the skinned pipeline was never compiled here. Nothing in this plan is “verified on device” until you build it. |

### 1.1 Bind-relative skinning — already correct (this section used to be wrong)

An earlier revision of this document claimed there was "no inverse-bind matrix
anywhere" and that the rig was therefore stretched by the height of the head
chain. That is not true on this branch, and the claim has been removed so nobody
spends a day chasing it:

* `Skeleton.kt` builds a bind pose, inverts it, and publishes
  `poseWorld × inverseBind` into `matrices`, which is what the shader binds to
  `u_bones[19]`. The shader's `skin * vec4(a_pos, 1.0)` is then a correct
  bind-relative transform.
* `Skeleton(inverseBindMatrices)` accepts a 19-bone inverse-bind set from a
  converted asset, so an imported rig and the procedural pose share one path.
* `render_oeac.py` implements the same maths offline, which is how the poses in
  this repo were checked: the joints bend where the mesh draws them.

The character still looked wrong, but for art reasons — the mesh was drawn 24 cm
from its own elbow, arms were weighted to the wrong joint, and the shapes were
primitives. That work is written up in
`docs/send-off-character-anatomy-2026-11-23.md`.

---

## 2. What Tripo3D is and what it gives you

Tripo is a browser studio **and** a separate developer API. They have separate accounts/billing; a Studio subscription is not an API add-on.

- Base URL for the API: `https://openapi.tripo3d.ai/v3`; auth is `Authorization: Bearer <api_key>`. Keys are created in the Tripo console. Store the key in an environment variable, never in this repository or the APK. ([Introduction](https://developers.tripo3d.ai/en/docs/introduction))
- Everything is asynchronous: `POST` returns a `task_id`; poll `GET /v3/tasks/{task_id}` about every 2 seconds (docs warn not to exceed 1 request/second); download `output.model_url` quickly — the docs say model URLs expire after 5 minutes. ([Quick start](https://developers.tripo3d.ai/en/docs/quick-start))
- Useful endpoints: text-to-image, image-to-model, multiview-to-model, texture, retopology/decimate, rig-check, auto-rig, retarget, format convert, file upload. ([Introduction](https://developers.tripo3d.ai/en/docs/introduction))
- For a game character, use the **P Series**: `P2-20260801` is the game-oriented model; `face_limit` can be set around 3,000–5,000 for supporting characters and higher for a hero. P2 supports PBR textures and quads. Avoid `export_orientation` during generation if you plan to rig/retarget afterwards — set orientation in the final convert step instead. ([Image-to-model P Series](https://developers.tripo3d.ai/en/docs/generation-image-to-model/p))
- `text-to-image` has a `t_pose` template and a `figure` template; that is the cleanest way to get a full-body reference for 3D conversion. ([Text-to-image](https://developers.tripo3d.ai/en/docs/generation-text-to-image))
- Rigging flow: `POST /animations/rig-check` → check `riggable` and `rig_type == "biped"` → `POST /animations/rig` with `model: "v3.0-20260909"`, `rig_type: "biped"`, `spec: "mixamo"` (or `tripo` for a custom pipeline) → `POST /animations/retarget` with presets such as `preset:idle`, `preset:walk`, `preset:run`, `preset:shoot`, `preset:hurt`, `preset:jump`. ([Rig check](https://developers.tripo3d.ai/en/docs/animations-rig-check), [Auto rig](https://developers.tripo3d.ai/en/docs/animations-rig), [Retarget](https://developers.tripo3d.ai/en/docs/animations-retarget))
- Retopology: `/v3/mesh/decimate` with `model: "v2.0"` or `"v3.0"` and `face_limit: 5000` can reduce a mesh while baking textures. ([Retopology](https://developers.tripo3d.ai/en/docs/mesh-decimate))
- Licensing: Tripo’s own help says free-tier rights stay with Tripo / are public and non-commercial, while paid users get private models and commercial use. Studio and API billing are separate. **Ship only assets generated under terms that allow commercial use, keep the generation records, and make sure your reference image is yours.** ([Commercial use help](https://www.tripo3d.ai/help/privacy-policy/how-to-use-tripo-models-commercially), [Commercial use FAQ](https://www.tripo3d.ai/help/privacy-policy/can-i-use-models-commercially))

### What Tripo does *not* solve

It does not give you an OEA‑compatible runtime model. Its output is a GLB (or FBX/GLTF/OBJ/STL through convert), usually with a different skeleton, more bones, PBR texture maps, animations and a different bind pose. OEA has no GLB reader. Someone has to convert that GLB into OEA’s vertex stream and bone palette. That is the real integration work.

---

## 3. The pipeline I recommend

### Phase 0 — decide the scope

Pick one:

1. **Hero-only (recommended first).** One AI-made soldier replaces the local player in Character Studio. Bots keep the current rig. Lowest risk; biggest visual win where the player looks most closely.
2. **A small character pack.** 3–6 AI-made soldiers (different genders/kits), selected by a `modelId` in `CharSpec`; bots choose by id. Needs netcode to carry the id, and several converted assets in the APK.
3. **Runtime generation.** Player generates a character in-game through the API. **Do not do this for OEA as designed**: it puts a secret API key on a device or needs your own backend, breaks the “no accounts / no game servers / no third-party services” promise in `README.md`, costs money per player, needs download/storage/privacy handling, and still needs a GLB loader. Keep Tripo offline.

Character Studio sliders cannot deform a fixed AI mesh the way they deform `HumanBody`. For a hero model, either freeze the body/face sliders and expose model presets + colours, or keep the procedural rig as the “custom” option and offer AI models as skins. This is a product decision you should make before generating assets.

### Phase 1 — make the reference image

Easiest path for one character: use **Tripo Studio** in the browser, no code. Use the API later if you want repeatability.

A ready Send Off reference now exists at
`tools/tripo/concepts/send-off-soldier-concept.png` — full body, white
background, forward-facing A-pose, empty hands. Use that image directly for
`image-to-model`; the prompt that produced it is recorded in
`tools/tripo/concepts/README.md`. You only need the text-to-image call below if
you want a different character variant.

For the API, a good first request is `POST /v3/generation/text-to-image`:

```json
{
  "model": "seedream_v5",
  "size": "2K",
  "template": "t_pose",
  "prompt": "Full-body front view of a realistic military soldier, symmetric relaxed A-pose, visible from head to boots, plain light grey studio background, no weapon, no logos, no text, no cropped limbs, high detail fabric and face"
}
```

Then upload the chosen PNG (`POST /v3/files`, ≤20 MB) and call `image-to-model`. For a better face and back, use `image-to-multiview` first and `multiview-to-model` with front/back/side views. Never use a copyrighted character or real person’s likeness as the source image.

### Phase 2 — generate, rig, animate, optimize

Use P2 for game-friendly geometry:

```json
POST /v3/generation/image-to-model
{
  "input": "<file_token>",
  "model": "P2-20260801",
  "face_limit": 5000,
  "texture": true,
  "pbr": true,
  "texture_quality": "standard",
  "export_uv": true
}
```

Then, after polling each task:

```json
POST /v3/animations/rig-check      {"input": "<model_task_id>"}
POST /v3/animations/rig            {"input": "<model_task_id>", "model": "v3.0-20260909", "rig_type": "biped", "spec": "mixamo", "out_format": "glb"}
POST /v3/animations/retarget       {"input": "<rig_task_id>", "animations": ["preset:idle","preset:walk","preset:run","preset:shoot","preset:hurt"], "animate_in_place": true}
```

Download the GLB from each successful task immediately. If the mesh is too heavy, run `/v3/mesh/decimate` before rigging (or use a lower `face_limit` on regeneration). Target 512–1024 px texture maps for soldiers; 2048 only for the hero close-up. Keep `model_seed`/`texture_seed` once you like a result so you can reproduce it.

Store the downloaded files under a tool-only folder, for example `tools/tripo/assets/`, **not** in the shipped APK yet. Keep a small JSON manifest with the task ids, model version, seeds, date and licence/plan used.

### Phase 3 — convert GLB to an OEA character asset

This is the code work needed in the repo:

1. Write a small offline converter (Python is enough: a GLB is a JSON chunk + a binary chunk) that reads:
   - positions, normals, UVs, indices,
   - `JOINTS_0` / `WEIGHTS_0`,
   - node hierarchy and `inverseBindMatrices`,
   - animation samplers/channels,
   - material base-colour texture / normal / ORM maps as images.
2. Triangulate, keep max 4 influences per vertex, quantize positions/normals/weights, and write a compact custom format, e.g. `soldier_p2_body.oeac` with sections for meshes, bones, bind matrices and clips. Keep total hero asset ~1–3 MB, not a 2K PBR set from Tripo unchanged.
3. Convert textures to 512/1024 PNG/WebP bitmaps that `Texture.asset()`/`Texture.fromBitmap()` can load. OEA’s lit shader has one base texture and no normal-map slot, so bake normal/roughness detail into the colour/albedo where it matters, or add a normal-map uniform later if the visual gain justifies it.
4. Add a runtime loader under `oea-engine/.../graphics/` that produces `SkeletalMesh` parts and a `Skeleton` from the custom file. Use `GameStorage.file()` for optional downloaded assets; bundle the default assets in `assets/` for zero network at runtime.
5. Fix skinning to use inverse bind matrices: `finalBone[i] = poseWorld[i] * inverseBind[i]`. For Tripo animations, either retarget its clip keyframes onto OEA’s 19-bone `Skeleton`, or extend the skeleton to the imported bones and drive it from the clip. The existing `Skeleton.update()` procedural pose can remain the fallback.
6. Wire it into `CharRig` behind a flag such as `modelId: String?`:
   - `null` → current `HumanBody` path (unchanged for bots and Endless Days).
   - non-null → imported meshes, with `CharSpec` only driving tint/eye colour/name where it still makes sense.
7. In `OeaArena.rigFor()`, give the local player the imported hero rig. If you later add more models, put a stable `modelId` in `CharSpec` and snapshots so every client resolves the same bundled asset; never send mesh data over the network.
8. Keep LOD: one skinned body + helmet/vest/weapon is fine; hide small attachments at distance as `drawSoldier()` already does.

### Phase 4 — verify

- Build with a real JDK/Android SDK (`./gradlew ...`) — not possible in this sandbox (`java` is absent, `ANDROID_HOME` unset).
- Test on low/mid/high Android devices: FPS, frame time, memory, skinning correctness, sun shadows, transparent parts, ADS pose, death/sky poses, Character Studio live preview.
- Verify the Character Studio no longer regenerates an imported mesh on every slider move; it should update material tints/preset ids only.
- Confirm the shipped asset’s licence/plan and source image rights; note any store AI-disclosure requirement.

---

## 4. Do not do these

- Do not put `TRIPO_API_KEY` in Kotlin sources, `local.properties`, assets, logs or an APK. Use an environment variable on the build/asset machine.
- Do not call Tripo at runtime for every player or on match start. OEA’s promise is no accounts/servers/third-party services; keep generation offline.
- Do not ship free-tier Tripo output without checking the current terms.
- Do not expect a text prompt alone to reproduce the `send-off-character-studio.png` render exactly. That image is a design render. Generate several references, pick the best, then convert and verify on a device.
- Do not throw away the procedural rig yet: it is shared with Endless Days and is the fallback for low-tier devices and bots.

---

## 5. What you do next, practically

1. Decide: hero-only first (recommended) or a pack of models.
2. Create a Tripo account with commercial rights for generation, and optionally an API key stored only as `TRIPO_API_KEY` in your shell. Studio is enough for the first model.
3. Generate a T-pose/A-pose soldier reference, then image-to-3D with `P2-20260801`, `face_limit: 5000`, PBR on. Rig-check → biped rig v3 → retarget idle/walk/run/shoot.
4. Put the exported GLB in `tools/tripo/assets/` in this repo and tell me the path. I can then build the GLB→OEA converter, the inverse-bind skinning fix, and the `CharRig`/`OeaArena` hookup on this branch.
5. Build and test on a phone; adjust polycount/texture size and studio behaviour from what you see.

Nothing in this plan blocks a no-API-key improvement, and that has now been
done: the procedural characters were rebuilt on a real 1.80 m skeleton by
`tools/tripo/soldier.py` + `tools/tripo/humanoid.py`, and the result shipped in
the `.oeac` catalog. See `docs/send-off-character-anatomy-2026-11-23.md`. Those
assets are also what the Tripo reference sheets are rendered from
(`tools/tripo/reference_sheet.py`), so the model Tripo returns is a refinement of
the shipped silhouette rather than a different character.

---

## 6. Status after the first implementation pass (23 November 2026)

The engine-side work is now on this branch. **Nothing here has been compiled or
run on a phone** — there is no JDK/Android SDK in this sandbox — so it is a
verified-by-inspection and Python-smoke-test pass only.

### Landed

- **Inverse-bind skinning fix.** `Skeleton.kt` now derives local joint offsets
  from a bind pose and publishes `poseWorld × inverseBind` matrices, which is
  what `SKIN_VERT` expects. This also fixes the procedural rig's stretched
  head/limbs: at rest, every bone matrix is identity and the mesh stays where
  `HumanBody` authored it. `SkeletonTest` guards the contract.
- **Imported bind poses.** `Skeleton(inverseBindMatrices)` accepts a 19-bone
  inverse-bind set from a converted GLB, so the same procedural pose drives an
  AI character with different joint positions.
- **`.oeac` character format + loader.** `CharacterAsset.kt` reads a compact
  binary asset (meshes, 19 bones, inverse binds, optional baked clips, base
  colour textures) from `assets/` or a game-private file. It builds
  `SkeletalMesh` parts and `Material`s; no glTF parser or network at runtime.
- **GLB → `.oeac` converter.** `tools/tripo/glb_to_oeac.py` reads a rigged GLB,
  maps Mixamo/Tripo joint names onto OEA's 19 bones, folds node transforms into
  inverse binds, normalises to 1.8 m with feet at y=0, extracts base-colour
  textures and samples animation clips as bind-relative matrices. It has a
  dependency-free smoke test: `python3 tools/tripo/test_glb_to_oeac.py` (passes).
- **Auto-rig for unrigged models.** `tools/tripo/autorig.py` fits OEA's 19-bone
  skeleton to a standing humanoid that has no skin at all — it measures the up
  axis, scale, crotch, arm axis and leg axis from the vertices, places the
  joints, computes distance-based weights with a per-bone influence radius, and
  flips inward-wound shells. Runs through the same converter (`--auto-rig`, or
  automatically when the GLB has no skin) and writes the identical `.oeac`. Test:
  `python3 tools/tripo/test_autorig.py` (passes, builds its own unrigged GLB).
  **This is how the shipped hero got its rig**: see
  `docs/send-off-imported-soldier-rig-2026-11-24.md`.
- **Additive Send Off hookup.** `CharacterStudio.rig(asset)` accepts an
  imported asset; `OeaArena.onCreate()` tries a private file at
  `characters/hero.oeac`, then `assets/characters/hero.oeac`, falling back to
  the procedural rig. Only the local player uses it; bots stay procedural.
- **Send Off concept reference.** `tools/tripo/concepts/send-off-soldier-concept.png`
  is a full-body, white-background A-pose tactical soldier, recorded with its
  prompt and purpose in `tools/tripo/concepts/README.md`.
- **Anatomy-first character generator.** Because Tripo access is restricted
  here, the shipped characters are generated locally: `tools/tripo/humanoid.py`
  lofts station lists with swept frames, and `tools/tripo/soldier.py` supplies
  the anatomy — a 1.80 m, 7.5-head skeleton, tapered limbs with muscle bias,
  lofted skull and hands, and one continuous sleeve that *is* the shoulder.
  `tools/tripo/concept_to_oeac.py` samples the concept PNG for palette and writes
  `oea-engine/src/main/assets/characters/*.oeac`. They are image-derived and
  game-rigged, **not** AI-generated 3D, and remain replaceable by a real Tripo
  GLB. Write-up and the rejected designs:
  `docs/send-off-character-anatomy-2026-11-23.md`.
- **Six identified soldiers + catalog.** `concept_to_oeac.py --all` emits
  `hero` (Ranger 01) and five bot identities — Ranger, Medic, Bulwark, Scout
  and Wraith — each with its own palette and physical kit (cap, mask, helmet,
  backpack or hood). `assets/characters/manifest.json` is the single source of
  truth loaded by `CharacterCatalog.kt`; `OeaArena` uses the catalog for the
  studio hero and for local bots, falling back to the procedural rig whenever
  an asset is missing. `preview_lineup.py` renders all six for review.
- **Asset checker.** `python3 tools/check-character-assets.py` reads every
  bundled `.oeac` the way `CharacterAssetLoader` does and fails on the faults
  that are otherwise invisible until someone looks closely: bone ids out of
  range, weights that do not sum to 1, a missing texture, a shell wound inward
  (invisible with back-face culling), a mesh drawn away from the joint its
  weights name, or a pose that does not actually move the limb.
- **Reference set for image-to-model.** `tools/tripo/reference_sheet.py hero`
  renders front / three-quarter / side / back / T-pose front / T-pose side /
  two head views / two contact sheets into `tools/tripo/concepts/`.
- **3D weapon meshes.** `weapons_to_oeam.py` generates `.oeam` files for all
  13 weapons with receiver/stock/barrel/grip/magazine/optic detail and painted
  vertex colours. `WeaponAssetLoader.kt` loads them; `OeaArena` uses a white
  material for the painted meshes and keeps the old procedural boxes as a
  fallback. `preview_weapons.py` renders a verification sheet.
- **Imported hero.** The committed `3d.glb` (a 10,040-vertex soldier, no rig) is
  now the local player: `characters/hero_rigged.oeac` plus its 512 px albedo,
  and the procedural `characters/hero.oeac` is gone. The five bots remain
  procedural. `concept_to_oeac.py --all` skips `hero` rather than overwriting it.
- **Static check:** `python3 tools/check-kotlin.py` passes (306 sources).

### How to use it

1. Generate/rig/export a GLB from Tripo (phases 1–2 above). A rig is optional —
   `autorig.py` fits one if the export has no skin.
2. Convert it:

   ```bash
   python3 tools/tripo/glb_to_oeac.py soldier_rigged.glb \
     --out oea-engine/src/main/assets/characters/hero_rigged.oeac \
     --textures oea-engine/src/main/assets/characters/hero_rigged
   ```

   `--auto-rig` forces the fitted-skeleton path; it also runs by itself when the
   GLB has no `skins`. `--texture-size 512` (the default) re-encodes the albedo
   down from a 4096 px source.

   Or point `--out` at a staging folder and copy both outputs to a device's
   OEA private `games/arena/characters/` folder for testing:
   `hero.oeac` plus the extracted `hero/` texture folder.

3. Build and run on a phone. The studio model becomes the imported soldier;
   bots use their catalog identities, and Endless Days keeps the procedural
   path.

To regenerate the stopgap art without Tripo:

```bash
python3 tools/tripo/concept_to_oeac.py --all
python3 tools/tripo/weapons_to_oeam.py
python3 tools/tripo/preview_lineup.py
python3 tools/tripo/preview_weapons.py
```

### Still open

- No real Tripo GLB has been converted yet — the smoke test uses a synthetic
  rig. Real exports may have different joint names, nested node transforms,
  sparse accessors or PBR textures that need converter tweaks.
- Nothing has been checked on a phone. The `.oeac` files are verified
  structurally (`tools/check-character-assets.py`) and rendered offline from
  every angle in six poses, which is not the same thing.
- Character Studio sliders no longer deform an imported mesh; they can only
  retint it through `CharSpec`. Decide whether to expose preset models instead.
- Baked clips are parsed but not played; the imported hero and bots use the
  procedural pose. Add clip playback if you want Tripo's walk/shoot clips.
- The six soldiers and 13 weapons are hand-authored low-poly stopgaps. A real
  Tripo GLB for any identity can replace that entry's `.oeac` without touching
  game code, as long as it keeps OEA's 19-bone rig.
- Android build, device FPS/memory, shader correctness and all visual
  comparisons against the design renders remain unverified here (no JDK or
  Android SDK in this sandbox).
