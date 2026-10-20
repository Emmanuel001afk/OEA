# Character Studio, physics VFX and launcher polish — 2026-10-16

This round adds the native character/VFX architecture requested after the
independent-games work, plus the device-facing polish for OEA's built-in apps.
The sandbox still has no JDK/Android SDK, so compilation and device/GLES
behaviour remain to be verified on a build machine. Static source checks are
run at the end of the change.

## Character Studio

The reusable engine data model is in `oea-engine/.../graphics/CharSpec.kt`:

- `CharSpec` is serializable JSON and revisioned. Changing a field and calling
  `touch()` causes the rig to resolve a new `CharForm` on the next draw.
- `CharGender`: masculine, feminine, neutral. Neutral uses proportions rather
  than a forced binary mesh.
- `FaceSpec`: width/length, jaw, chin, cheekbones, brow, eyes, nose, mouth and
  ears. These are actual rig dimensions, not labels.
- `BodySpec`: height, shoulders, hips, chest depth, waist, limb thickness, neck
  and muscle.
- `SkinSpec`: twelve skin tones, eye colours, freckles, scars, war paint and a
  skin-pore texture key.
- `HairSpec`: six styles (short, ponytail, bun, long, buzz, mohawk), sixteen
  hair colours and facial hair.
- `OutfitSpec`: top, bottom, outer layer, footwear, headwear and back item.
  Each `OutfitLayer` has an independent part id, colour, trim and texture key.
- `CharParts` is the data table for all part catalogs. `CharTextures` caches
  procedural cotton, knit, fleece, tactical, canvas, leather, rubber, metal
  and skin-pore textures and regenerates them after a GPU context loss.

`CharacterStudio` in `oea-games` is the in-game editor. Send Off opens it from
Pause → Character studio. It is a live turntable over the real soldier, with
Body / Face / Hair / Skin / Outfit tabs, cycle controls, slider controls,
Randomize, Reset and Done. It saves to the game's private `GameStorage`; the
same `CharSpec` is passed to the same `CharRig` that draws the playable local
character, so changes apply immediately rather than to a preview-only dummy.

`CharRig` remains source-compatible with Endless Days and older Send Off call sites:
legacy material arguments still work. When given a `CharSpec`, it adds facial
masses, variable skeleton scales, clothing shells, texture-backed materials,
headwear, facial hair, markings and back items. `CharFx` adds live interaction
state: rain wetness, dust accumulation, wind vector and cold.

## Physics VFX pipeline

`oea-engine/.../fx/FluidGrid.kt` is a phone-sized semi-Lagrangian Eulerian
volume:

- velocity, density and temperature fields;
- buoyancy and ambient wind forces;
- analytic turbulence plus vorticity confinement for curls;
- density/heat dissipation;
- terrain heightfield obstacles and solid AABBs for walls/roofs;
- smoke/fog/fire injection and momentum impulses;
- trilinear velocity/density sampling for particles.

`VfxSystem.kt` is the game-facing orchestrator:

- fire and smoke columns inject hot gas and emit carried particles;
- sparks have gravity, drag, terrain bounce and skitter-to-rest;
- magic bursts add pressure to the volume and a real dynamic light;
- impacts kick dust and sparks;
- explosions combine gas, impulse, light and particles;
- muzzle flashes light the weapon/face area and push hot air;
- rain, snow and dust are weather emitters; `CharFx.update` wets/dirties kit
  and supplies wind to hair and clothing;
- eight strongest nearby `PointLight`s are sent to the renderer each frame.

The renderer now uploads those lights to the forward shader. Their coloured
attenuation contributes diffuse and specular light to every fragment, including
skin, clothes and terrain. Sun shadow mapping remains active for cast shadows;
point-light shadows would require a cube shadow atlas and are deliberately the
next GPU-tier upgrade rather than pretending an additive billboard is a shadow.

Send Off (the stable `arena` game id) wires the system to terrain, houses, shooting, impacts, weather and
haunted fog. It registers the particle systems during the world pass and keeps
old muzzle/dust systems intact for compatibility.

## Built-in launcher apps

- Calendar now requests `READ_CALENDAR`/`WRITE_CALENDAR`, reads Android's
  Calendar Provider when allowed, and merges device events read-only into month,
  week, day and agenda views. OEA's own events remain private and editable.
- Camera, Image, VIP and Messaging have complete launcher icons and Android
  intent identity. Camera advertises image/video capture; Image and VIP accept
  image/video view/get-content intents; Messaging accepts `smsto`/`sms` links.
- Messaging has a default-SMS delivery receiver and SMS role-compatible
  manifest entry. The receiver assembles multipart SMS into Android's system
  SMS provider (the UI reads that same provider), records delivery status and
  keeps the explicit role flow.
- OEA Console already provides real device information, permission/access
  doctors, security analysis and accessibility setup. Its existing
  `OeaAssistantService` is retained; the manifest has a clear accessibility
  entry and the mini-app host now rerenders after runtime permissions change.
- Every mini-app has a round icon, a task affinity, its own launcher entry and
  a stable component identity. The icon vectors were redrawn as consistent
  dark/light branded cards rather than minimal placeholder marks.

## Themes and wallpapers

`OeaThemeStore.exportTheme` writes a portable `.oea-theme.json` containing
settings and embedded JPEG/PNG picture bytes. Themes can be exported through
Android's document provider to Downloads, Drive or another upload destination;
`importTheme` accepts the same file, copies images into OEA-private storage and
creates a new editable theme. The Themes app exposes Import on the home page
and Export/upload on every theme detail page. Existing SAF image import,
private theme copies, applying to Home/lock and generated OEA scenes remain
intact.

## Static verification

- AndroidManifest parses as XML after permission, role, intent, receiver and
  roundIcon changes.
- Kotlin brace/parenthesis/bracket balance uses a string/comment-aware lexer
  across the repository.
- New public contracts were checked against call sites: `CharRig` keeps
  trailing defaults; `ParticleSystem.emit` named parameters match; `Hud.note`
  is used as a parent mutator (not as a View); `GameStorage` keys are private
  to the game; renderer point-light uniforms match shader arrays.
- No local build is possible in this sandbox; run `./gradlew :oea:assembleDebug`
  on a machine with the configured Android
  toolchain and test permissions, Calendar provider, default SMS flow,
  camera intents and GLES point-light/VFX performance on physical devices.
