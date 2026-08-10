<!-- Copyright (C) 2026 Jacob Repp -->

# Minosoft content and asset system

This document describes how Minosoft resolves Minecraft content, which
resource-pack features it renders today, and where higher-fidelity content
requires a compatibility adapter. It documents current source behavior; it is
not a statement of full Minecraft, Fabric, OptiFine, or GeckoLib compatibility.

The active compatibility baseline is Minecraft Java Edition 1.20.4. Older
versions remain supported through versioned assets and data fixers, so new
content code must not assume that every session uses the 1.20.4 layout.

## Terms and boundaries

- A **resource location** such as `example:block/machine` is a namespaced lookup
  key. It is a reference, not a claim about who authored the bytes.
- An **asset manager** returns bytes for a resource location.
- A **session asset stack** is a list of managers in priority order. The first
  manager that has a key wins.
- A **resource pack** is a ZIP or directory with an `assets/` tree. It is data,
  not executable code.
- A **mod artifact** is a JAR with metadata, possibly code, nested JARs, and an
  `assets/` tree. Staging its bytes does not make its code run.
- A **compatibility adapter** maps one exact upstream artifact onto Minosoft's
  own APIs and lifecycle hooks. `ADAPTED` means Minosoft reads that artifact's
  surface, not that Minosoft ran the upstream Minecraft-targeted bytecode.

Minosoft only consumes locally installed or user-selected content. The
`JarAssetsManager` and `IndexAssetsManager` never fetch missing content from
official Minecraft services. If a local archive, index, or indexed object is
missing, Minosoft fails with an instruction to import a local asset pack or mod.

## End-to-end flow

```text
profile / selected compatibility trajectory
            |
            v
AssetsLoader creates a candidate SessionAssetsManager
            |
            +-- integrated overrides
            +-- profile resource packs
            +-- versioned Minosoft compatibility assets
            +-- local index and client-JAR assets
            +-- generation-owned external mod assets
            +-- integrated defaults
            |
            v
model, texture, sound, language, font, and UI loaders
            |
            v
CPU parse/decode -> model bake / texture preparation -> render-thread upload
```

`PlaySession.connect` loads an asset candidate, lists its resource locations,
parses registered higher-fidelity formats headlessly, and publishes the asset
manager and an immutable content snapshot together. If preparation or commit
fails, the candidate is closed and the previous content generation stays in
place. Readers lease a generation; replaced assets and GPU models are disposed
only after the final reader exits.

Fabric-shaped resource-reload events expose prepare, apply, complete, and failed
phases. `/reload content` reparses CEM, ETF, and Gecko content, bakes and
uploads a candidate on the render thread, then atomically swaps skeletal
model/entity lookups and tells live entity renderers to recreate their
instances. Referenced static textures join the same transaction: Minosoft
rebuilds affected resolution buckets into candidate GPU handles while shader
coordinates stay stable, then publishes them with the model generation or rolls
back. New texture keys can enter a content-fidelity reload; missing assets
still reject the candidate without advancing the generation. Content-owned
shader slots are leased by stable array/layer coordinates, so retired holes are
reused without renumbering live meshes and dead trailing layers shrink on the
next transaction. Pre-existing resource-pack slots stay permanent. General
resource-pack reload and atomic shader reload remain incomplete.

The bounded debug form `render.reload-content {"rejectAt":"after-upload"}` or
`{"rejectAt":"after-publication"}` runs the real allocation and rollback path
without changing mounted files. It reports whether the active generation and
all typed live OpenGL counts stayed unchanged. This is a diagnostic contract,
not a content-pack extension.

## Asset priority

`AssetsLoader` adds managers in the following lookup order, from highest to
lowest priority:

1. integrated emergency/version overrides;
2. explicit caller-supplied priority assets, when present;
3. resource packs in the resources profile, with later profile entries winning;
4. integrated pack-format compatibility layers, newest applicable format first;
5. local Minecraft index assets, when enabled;
6. local client-JAR assets, when enabled;
7. generation-owned external asset providers, including adapted mod JARs;
8. integrated defaults.

The ordinary client does not supply priority assets. Its
`LocalMinecraftAssets` selection defaults from the resources profile, retaining
independent support for the locally imported Mojang index and client JAR.
Callers may override that selection without mutating the profile. Integration
tests use this seam to select `LocalMinecraftAssets.NONE` and mount a small
Minosoft-authored stand-in, so renderer and credits checks do not depend on a
Mojang asset index or client JAR.

The checked-in exact fixtures and generic fallback are a deterministic
bootstrap, not the completeness boundary. The target is a complete
Minosoft-authored stand-in whose normal renderer bootstrap does not reach the
generic fallback; local Mojang sources remain a supported compatibility lane.
Set `MINOSOFT_CONTENT_FORGE_ROOT` to an absolute content-forge output directory
to mount those out-of-source assets below the exact stand-in fixtures and
enable their provenance, JSON, PNG, and Blockbench producer gate. Without that
setting, the external gate skips and the tests stay hermetic.

The play parent materializes verified Packwiz resource-pack artifacts into the
selected trajectory immediately before launch. Managed packs have deterministic
filename ordering, while existing manual profile packs retain higher priority.
Third-party JARs and ZIPs stay in the configured out-of-source,
content-addressed modpack store; only their manifests belong under `modpacks/`.

An adapted mod JAR currently sits below local client-JAR assets. This is enough
for unique mod namespaces such as `techreborn:*`, but it does not allow a mod
asset provider to replace a vanilla key. Any future priority change must be an
explicit asset-stack decision with resource-pack regression coverage.

## Resource-pack layout

Minosoft uses the normal namespaced Java Edition layout:

```text
pack root
├── pack.mcmeta
└── assets/
    └── <namespace>/
        ├── blockstates/<name>.json
        ├── models/block/<name>.json
        ├── models/item/<name>.json
        ├── textures/<path>.png
        ├── textures/<path>.png.mcmeta
        ├── sounds.json
        ├── sounds/<path>.ogg
        ├── font/<name>.json
        └── lang/<locale>.json
```

The profile accepts ZIP and directory resource packs. Packwiz-managed resource
packs must be hash-pinned and contain both `pack.mcmeta` and an `assets/` tree.

## Rendering pipeline compatibility for technical artists

This section tells a technical art team what they can safely rely on in each
rendering pipeline. "Rely on" means the feature is implemented, tested, and
stable in the current baseline. If a feature is not listed, treat it as
experimental until it is checked. The compatibility baseline is Minecraft
Java Edition 1.20.4.

### Entity pipeline

Model formats you can rely on:

- **Native `.smodel`** — Minosoft's own JSON skeletal format, used for the
  shipped player, zombie, cow, pig, and sheep rigs.
- **OptiFine CEM** — `.jem` geometry and `.jpm` part files, loaded through the
  Entity Model Features (EMF) adapter.
- **GeckoLib** — `.geo.json` geometry plus `.animation.json` and `.rp_anim.json`
  clips, loaded through the GeckoLib adapter.

Geometry features:

- Nested bones with pivots, rotation, scale, and attach points.
- Box elements with per-face UV, mirroring, and inflation.
- Static transforms and named pivots.

Animation features:

- Per-bone rotation, translation, and scale keyframes.
- Once, loop, and hold clip modes.
- Linear, smooth (Catmull-Rom), and stepped interpolation.
- Easing functions, including the GeckoLib 4.4.4 built-in set.
- GeckoLib controllers with predicates, layers, and triggerable clips.
- Vanilla walking animation and baby scaling.

Materials and lookups:

- ETF random, variant, and emissive textures, plus blink masks.
- Emissive textures render on the entity-eyes pass.
- Armor (outer/inner, leather, trims, glint) and held items.
- Display entities with transforms, billboarding, and text.
- Terrain-conforming shadows and leashes.

Known limits:

- The EMF rig-alias catalog covers only the five shipped rigs (player, zombie,
  cow, pig, sheep). Content that needs other aliases is not yet routed.
- Binary GeckoLib/Mojang compatibility is out of scope; content must go through
  the native parsers.
- Entities are drawn one instance at a time; there is no instanced batching.
- Gecko/Bedrock box UVs follow Bedrock's negative-Z front slot and mirrored U;
  they do not use vanilla Java's north/south assignment.

### Terrain pipeline

Block models you can rely on:

- Vanilla JSON blockstates: `variants`, `multipart`, and weighted applies.
- Vanilla JSON models: `parent`, `elements`, `rotation`, faces with `uv`,
  `rotation`, `tintindex`, and `cullface`.
- Animated textures via `.png.mcmeta`, including rectangular frames.
- Fluid rendering with flowing UV rotation and waterlogged blocks.

Lighting and color:

- Smooth per-vertex lighting with ambient occlusion (AO) on solid and fluid
  faces.
- Per-vertex packed block/sky light instead of a baked lightmap texture.
- Biome tinting for grass, foliage, and water with bilinear blending.
- Emissive terrain textures render additively.

Near versus distant terrain:

- **Near terrain** is meshed by Minosoft out to the seam (about 12 chunks),
  with full model features, AO, and light.
- **Distant terrain** runs through the Distant Horizons runtime with its own
  projection and a much larger render distance. At LOD scale it uses fixed
  per-face shading, not AO, and simplified per-vertex light.

Known limits:

- Distant terrain has no distance-driven foliage simplification; cutout foliage
  uses the same detail as ordinary terrain.
- The distant LOD lattice is fixed at 4x4 with adaptive refinement; the 8/16
  coarsening rung is not used because no morphing transition mesh exists.
- Underwater local lighting is incomplete in the built-in path; use a shader
  pack such as Complementary for correct underwater light.

### Cloud pipeline

Native clouds:

- One authored texture, `minecraft:environment/clouds.png`, exactly 256x256,
  used as an alpha coverage mask.
- 0 to 10 cloud layers (default 3), each on a height band and with its own
  drift speed.
- Flat or 3D box clouds, with side culling between neighboring cells.
- Time-of-day, moon-phase, and weather-driven cloud color.

Shader-pack clouds:

- A shader pack can replace clouds entirely (for example Complementary's
  volumetric clouds) or declare `clouds=off` to keep the native producer present
  but invisible.

Known limits:

- The cloud texture is a coverage mask, not an RGB sprite. There are no authored
  per-layer textures, opacity curves, or animation sheets.
- The native cloud layer has minimal distance culling; it only skips rendering
  at very short view distance or below the cloud band.

### Sodium and Iris pipeline

Sodium (terrain optimization):

- Sodium-style smooth lighting and AO on solid and fluid faces.
- Packed light and color per vertex, bridged to the chunk shader through a
  shared 84-byte terrain vertex layout.
- Biome blending with a section-local tint cache and bilinear sampling.
- Switching Sodium on selects it as the sole terrain provider; toggling it off
  restores the built-in provider.

Iris (shader packs):

- GLSL 330 core. Legacy GLSL 1.20 packs are automatically rewritten.
- Standard Iris program layout: `gbuffers_*` scene programs plus fullscreen
  `deferred`, `composite`, and `final` programs.
- Shadow passes with `shadowtex0..1` and `shadowcolor0..7`.
- Cloud passes and pack-owned uniforms such as `cloudTime` and `cloudHeight`.
- Texture bindings `colortex0..15` and `depthtex0..2`, plus per-program PNGs and
  custom textures.
- LabPBR-style `_n` (normal) and `_s` (specular) companion textures, stored as
  vertical pages in the same texture array layer.

Verified shader packs:

- Complementary Unbound r5.8.1
- Photon
- The official Iris example pack
- Bliss 2.1.0 (including Distant Horizons `dh_terrain`/`dh_water`/`dh_shadow`
  routes)

Known limits:

- Compute, image, and SSBO shaders only run on OpenGL 4.2/4.3+. The Apple
  OpenGL 4.1 driver cannot run them.
- Apple exposes only 16 fragment samplers, so program-local bindings are
  compacted and static material textures share array samplers.
- Bliss 2.1.2 has dark water, featureless underwater fog, and low frame rates;
  these are known open defects.
- Unknown uniforms fail preflight rather than silently receiving zero.

## Vanilla block and item models

### Blockstates

| Feature | Status | Current behavior |
| --- | --- | --- |
| `variants` | Supported | Property keys are parsed against the session's version-aware block registry. |
| Weighted variant arrays | Supported | A baked weighted render is selected from the declared entries. |
| `multipart` | Supported | Unconditional, AND, and OR/property conditions are composed. |
| `x`, `y`, and `uvlock` on applies | Supported | State rotations are applied during baking. |
| Custom model-loader declarations | Unsupported | Fabric/Forge loader IDs are not dispatched to an extension registry. |

### Model objects

Models resolve under `assets/<namespace>/models`. Block models are loaded
through `models/block/`; item models through `models/item/`. Parent models are
resolved recursively and the child inherits or replaces fields according to
the native loader.

| Field | Status | Notes |
| --- | --- | --- |
| `parent` | Supported | Parent geometry, textures, display transforms, GUI light, and AO value are inherited. Cycles are not diagnosed explicitly. |
| `textures` and `#variables` | Supported | Namespaced PNG paths and inherited aliases resolve before texture creation. Alias-cycle diagnostics are incomplete. |
| `display` | Supported | Third/first person hands, GUI, head, ground, fixed, and Minosoft's world position are represented. |
| `elements` | Supported | Cuboids are normalized from the 16-unit model grid and baked into quads. |
| `gui_light` | Supported | `side`/`front` state is retained for item/block presentation. |
| `ambientocclusion` | Supported | The inherited value is retained on each baked face, so multipart models preserve the originating model's AO choice. The global lighting profile can still disable AO for the whole renderer. |

### Elements and faces

| Field | Status | Notes |
| --- | --- | --- |
| `from`, `to` | Supported | Values are converted from model units to world units. The vanilla `-16..32` legality range is not enforced. |
| `rotation.origin`, `axis`, `angle`, `rescale` | Supported with compatibility caveats | Rotation is baked. Minosoft does not enforce vanilla's historical angle whitelist, so malformed or extension-authored angles may also load. |
| `shade` | Supported | Selects directional face shade or unshaded color. |
| face `texture` | Supported | Direct and `#variable` references load through the session texture manager. |
| face `uv` | Supported | Explicit UV rectangles and geometry-derived fallback UVs are baked. |
| face `rotation` | Supported | Quarter-turn UV rotations are applied. |
| face `tintindex` | Supported | The render path consumes registered tint values. |
| face `cullface` | Supported | The declared direction rotates with the blockstate and selects the neighbor used for culling. Boundary geometry remains separate because it also describes neighbor occlusion and lighting. Faces without `cullface` are not culled merely because they touch a block boundary. |

### Items and special renderers

Generated/layered item textures and display transforms are supported. A
dedicated item model takes precedence over a block item's world-block fallback.
Ordinary inherited block models remain three-dimensional.

`minecraft:builtin/entity` is a special boundary. Chests and shulker boxes have
precise Minosoft renderers; other known vanilla block-item families receive a
visible particle-texture fallback and structured audit output. That fallback is
diagnostic, not model parity.

For standalone content work, `./play.sh content audit --trajectory NAME`
captures the active generation's actual missing blockstate, model, and
file-backed texture lookups. The schema-versioned JSON is lexically sorted,
deduplicated, bounded, and fingerprinted without runtime timestamps. Every
entry supplies the exact resource-pack target path and its logical consumers,
so it can be used as a deterministic queue for `ContentPackAdapter`, loose
content directories, or higher-priority resource packs. The default artifact
lives under `.run/content-audits/` and must not be committed as source.
Supplying `--stage NAME` writes into a stable, lexically ordered trajectory
stage directory. The standalone manifest reads that directory cumulatively on
its next composition and generates conservative blockstate/model scaffolds plus
deterministic compatibility textures: `water_overlay.png`,
`enchanted_glint_entity.png`, `shadow.png`, and `vignette.png`. It also supplies
a transparent 8x8 `etf_nose.png` compatibility placeholder; the player renderer
replaces that material with a runtime-derived nose only when ETF skin content
requests one. Every other audited missing texture target is rasterized by a
deterministic library keyed by its resource path: spawn eggs, banner and wall
banners, candle cakes, glass panes, beds, shulker boxes, doors, wood and hyphae
bark, hanging signs, heads and skulls, coral wall fans, infested stone,
potted plants, waxed copper stages, and generic block/item tiles. Repeated
composition reproduces byte-identical PNGs and the cumulative stage inventory
therefore converges toward zero missing texture targets.

Item `overrides` use vanilla last-match selection. For the audited 1.20.4
catalog, stack-backed support covers `custom_model_data`, `damage`, `damaged`,
crossbow `charged`/`firework`, elytra `broken`, and light-block `level`. An
explicit render context supplies `lefthanded`, `cooldown`, bow/crossbow
`pull`/`pulling`, `brushing`, fishing-rod `cast`, shield `blocking`, trident
`throwing`, and goat-horn `tooting`. First-person and GUI selection build this
context from live player/session state. Living entities keep elapsed use ticks
from the tracked active-hand flag and equipment identity, resetting on
hand/stack replacement or stop; this also drives remote bow/crossbow/brush
progress.

Bundle `filled` uses registry-backed stack capacities and the vanilla nested
bundle/beehive rules. Clock `time` and compass/recovery-compass `angle` consume
live world, dimension, position, and spawn/lodestone/death-target state through
a render-context-owned smoothing runtime. `trim_type` uses the fixed 1.20.4 trim
material indices. Initialize and respawn packets keep the local player's last
death position for the recovery compass. Unknown or wrong-item predicates
contribute negative infinity, matching vanilla rather than accidentally matching
zero or negative thresholds. Selected element-backed models keep their cuboid
geometry. World and display item features reselect live providers each update
and retire or rebuild their mesh when an override threshold changes; held and
GUI items resolve on each draw.

Every provider registered by the audited 1.20.4 catalog now has a native
implementation. Session contexts select separate 1.19.4 and 1.20.4 catalogs,
so 1.20's `brushing` and `trim_type` providers fail closed in 1.19.4. Earlier
releases retain a named compatibility catalog pending per-release audits.
Modern component-based item-model dispatch and rendered reference fixtures
remain explicit compatibility gates. See the
[item-model predicate evidence](agents/evidence/2026-07-24-item-model-predicates.md).

## Textures

### Static PNGs

Textures resolve as `assets/<namespace>/textures/<path>.png`. The decoder
accepts RGB and RGBA PNGs and falls back to ImageIO for PNG layouts unsupported
by the fast decoder.

Static textures are grouped into OpenGL 2D-array buckets at 16, 32, 64, 128,
256, 512, 1024, and 2048 pixels. Smaller or rectangular images occupy the next
bucket and carry a UV endpoint so unused storage is not sampled. Images larger
than 2048 in either dimension are rejected by the static array uploader.
Dynamic arrays, such as skins, grow to the required power of two up to the
driver's maximum texture size.

High-resolution packs therefore do not need to stay at 16×16. Keep related
textures at a consistent density and stay within the 2048 static limit. The
renderer creates mip levels for ordinary static textures.

### `.png.mcmeta` animation

Minosoft looks for `<texture>.png.mcmeta` beside the PNG and deserializes its
`animation` object. Frames are cut from a row-major grid and run at 20 ticks per
second. The common vertical strip is a one-column grid.

| Property | Status | Current behavior |
| --- | --- | --- |
| `frametime` | Supported | Supplies the default duration for integer frame entries. |
| integer `frames` entries | Supported | Select a strip index using the default duration. |
| object `frames` entries | Supported | `index` selects a frame; `time` overrides its duration and otherwise falls back to `frametime`. |
| `interpolate` | Supported | CPU-side interpolation blends the current and next frame before upload. |
| `height` | Supported | Overrides the frame height. |
| `width` | Supported | Overrides the frame width. Multi-column sheets are sliced left-to-right, then top-to-bottom. |
| omitted `frames` | Supported | All complete grid frames play in row-major order. |
| `texture.blur`, `texture.clamp` | Parsed only | Values are retained in metadata but do not currently change OpenGL sampler state. |

Invalid frame indices fall back to the first decoded frame and emit a warning.
Frame dimensions and tick durations must be positive, and the source image must
divide exactly into the declared frame geometry. Malformed metadata falls back
to a non-animated texture after diagnostics.

## Entity and skeletal content

Vanilla Java resource packs do not define arbitrary living-entity geometry.
Minosoft has a native skeletal format under
`assets/<namespace>/models/**/*.smodel`. It supports nested elements, pivots,
texture maps, rotation/translation/scale keyframes, loop modes, and explicit GPU
resource ownership. Models are registered by Minosoft entity renderers before
the skeletal loader's load/bake/upload phases.

`.smodel` remains Minosoft's internal serialized renderer format. Third-party
files no longer deserialize into it directly. Registered headless parsers
produce an immutable neutral representation for bones, cubes, box/per-face UV,
pivots, static transforms, materials, clips, and expression bindings.

The current content generation discovers and parses:

- OptiFine CEM `.jem` roots and referenced `.jpm` parts;
- GeckoLib `.geo.json` and `.animation.json`;
- ETF/OptiFine random-entity `.properties`.

In Creative mode, the `Items`, `Creatures`, and JEI `Recipes` tabs use more of
the fullscreen viewport without stretching the vanilla inventory slots. The
creature tab searches the session's entity registry and previews every entity
with a real baked skeletal route, including CEM and GeckoLib content. The
preview keeps the production quads and bone IDs, advances real clips on the CPU
(including `query.anim_time`/`q.anim_time`), offers an animation selector, and
lets you drag to rotate. It can hold the authored default rotation and orbits
slowly while idle. Selected test clips loop locally, including production
one-shots, without changing runtime clip behavior. It does not create a
client-only entity, and exact entity identity still drives texture selection
when several species share geometry. `Spawn` uses the normal server `/summon`
command and is disabled when the server does not grant it. Woolly and sheared
sheep are fixed presets; they cannot be injected as new states.

Skeletal matrices use column-vector joint order
`T(pivot) · R · S · T(-pivot)`. Gecko/Bedrock static and animated rotations are
converted from their clockwise model convention before applying that hierarchy.
Gecko box UVs retain Bedrock's negative-Z front slot and mirrored U direction
instead of using vanilla Java's reversed north/south assignment. Hinged
zero-thickness planes snap to nearby pivots and overlap the joint by 0.05 source
pixels without changing their authored UV density. These invariants are shared
by production rendering and preview playback.

At render initialization, the neutral geometry bridge creates retained
`SkeletalModel` instances, keeps direct OptiFine texture paths, and maps CEM
filenames such as `cow.jem` to matching entity IDs. Existing animal and
humanoid renderers prefer that mapped CEM model. Static bone rotation/scale and
face UV rotation survive the bridge. Version and entity aliases rename bound
geometry, transforms, and expression targets. A per-instance, reflection-free
runtime applies ordered CEM transform and visibility expressions with
persistent `var.*`/`varb.*` state. The EMF 3.0.17 public input-name catalog and
compatibility spellings are recognized. The bounded expression VM covers the
audited arithmetic, conditional, keyframe/interpolation, random, angle, curve,
and named-easing methods. Part-property reads resolve live aliased pivots,
rotations, scales, visibility, and box-hidden state; missing part reads follow
the pinned zero fallback. Writes are ordered absolute assignments and reject
missing targets. `visible=false` suppresses the part subtree, while
`visible_boxes=true` suppresses only the target part's cubes and keeps its
children renderable.

Ordered `render.shadow_*` and `render.leash_offset_*` assignments feed the
owner-scoped native shadow and leash features. Shadow size, opacity, horizontal
offsets, distance fading, baby scaling, and EMF's 32-block size cap are applied.
A bounded vertical scan projects shadows across lit, full-collision terrain
surfaces with shape bounds and vertical/light falloff; block and chunk revisions
invalidate stationary projections after world changes. Projected quads sample
the active resource pack's `minecraft:textures/misc/shadow.png` with the audited
inverted two-radius UV mapping, fixed level-zero clamped sampling, and standard
source-alpha blending.

Leash offsets add to the vanilla local mob anchor and rotate with body yaw
without conflating the protocol's leash holder with vehicle state. Generic
holders use the standing-eye-height anchor, fence knots use their distinct
`+0.2` anchor, and player holders use main-arm plus normal, swimming, and
elytra/riptide pose formulas. The mesh is an audited pair of crossed 24-segment
ribbons with quadratic sag and alternating vanilla colors, not a chain of
generic line prisms. Mobs derive the vanilla body-control pose into separate
previous/current yaw history; native and adapted skeletal roots and the mob-side
leash anchor consume its partial-tick interpolation. Each leash segment
independently interpolates endpoint block and sky light and passes the packed
value through the renderer lightmap. Exact player-holder body-yaw dynamics and
rendered-reference comparison remain acceptance gates. The renderer publishes
its observable entity, player, time, health, swing, movement, attachment, and
visibility state.

Catalogued values that do not yet have a Minosoft source default safely, so
their exact live semantics are still a gate. Entity-specific aliases cover the
currently shipped player, zombie, cow, pig, and sheep rigs without leaking a
generic humanoid mapping into unrelated entities. This is still partial EMF.
Native entity parts survive targeted CEM replacement and `attach:true` roots use
isolated child transforms. Raw `nbt(key,query)` expressions share ETF's bounded
nested-NBT matcher, including escaped raw arguments, existence/inversion,
integer ranges, wildcard list paths, and string/pattern/regex queries. Live
content reload replaces the retained model and rejects new instances of the old
model while allowing already-retained instances to finish. Broader entity
aliases, exact live semantics for every catalogued variable, complex attachment
semantics, non-default shadow sampler-metadata overrides and reference-render
acceptance, exact player-holder body-yaw dynamics, diagnostic parity, and visual
reference fixtures remain gates.

### Display entities

Minecraft 1.20.4 item, block, and text displays share one native Minosoft
renderer boundary. Item models use the predicate selection described above;
block displays use the normal baked block-model path; text displays support
wrapping, alignment, background, opacity, shadow, see-through depth state, and
packed-light overrides.

The shared display renderer applies billboard constraints and retained
translation, quaternion rotation, and scale. Transformation, absolute
terrain-projected shadow radius/strength, and text background/opacity follow
the display interpolation start/duration metadata. Negative start deltas begin
partway through the transition. Position and rotation changes follow
`teleport_duration`, capped to the vanilla `0..59` tick range with
shortest-path yaw interpolation.

`view_range` applies to every display type at the vanilla 64-block base scale.
Finite nonnegative `width` and `height` define the visibility AABB; zero on
either axis disables frustum-box rejection while retaining range rejection.
Entity flag `Glowing` (`0x40`) enables the shared entity-outline pass.
`glow_color_override` selects a display outline color but does not enable it;
without an override, scoreboard/player team color and then white provide the
fallback. Block, item, text-display, EMF/ETF skeletal, Gecko skeletal/layer,
Gecko armor, and emissive geometry can contribute to the texture-mask pass,
including when occluded. Invisible glowing geometry is excluded from normal
entity layers. Headless registry and exact Animated-Java-export integration
tests cover those semantics. A live OpenGL probe covers shader/framebuffer
initialization, graph placement, and endpoint cleanup. The exact Animated Java
export additionally passes a deterministic default-pose render plus four
summon/reload/remove generations with bounded fixture GPU objects; checked
default/walk references and both uploaded-candidate and published-candidate
rollback paths preserve exact pixels and balanced live GPU counts.
Typed per-context GPU counters are available through
`render.substrate.gpuResources`; they cover buffers, vertex arrays, textures,
renderbuffers, framebuffers, shaders, programs, and queries. See
the [display semantics evidence](agents/evidence/2026-07-24-animated-java-display-semantics.md).

## Higher-fidelity compatibility trajectory

The tracked `content-fidelity` pack prepares a metadata and artifact baseline
for Blockbench-related content:

### Entity Model Features (EMF)

**Role:** Fabric replacement for OptiFine CEM geometry and expressions

**License/distribution:** LGPL-3.0

**Prepared state:** The exact 3.0.17 adapter is active. All behavior below is
source-native and verified by headless tests plus a checked native-zombie
reference that drives one CEM replacement with ETF's selected `rule_index`,
settled pixels, rollback, and recovery.

Capabilities:

- Geometry: `.jem`/`.jpm` parsing, the five shipped native-rig alias sets, and
  targeted native-part replacement with `attach:true` roots and isolated child
  transforms.
- Expressions: the full public input-name catalog (apart from intentional
  `nan`), the audited numeric/boolean method surface, raw `nbt(key,query)`
  predicates, live partial-tick clocks/frame counters, and degree/radian plus
  body-relative rotations.
- Pose inputs: vanilla limb interpolation, movement projection, dimensions,
  position/health/hurt/death/equipment/use/attachment/locomotion/tame/aggressive/
  anger, bounded fluid/ground and stopped-arrow probes, rain wetness from the
  pinned fixed-seed altitude and frozen-biome temperature samplers, hovered
  state, and the selected ETF rule index.
- Contexts: first-person, held-item, item-frame, GUI, head, and shoulder.
- Part behavior: live aliased part-property reads, ordered absolute writes,
  missing-target rejection, subtree visibility versus local-box hiding, and
  generation-leased texture slots.
- Shadow and leash: owner-scoped `render.shadow_*` and `render.leash_offset_*`
  output, bounded terrain-conforming projection from the resource-pack shadow
  texture, and crossed 24-segment leash ribbons with per-segment lightmap
  interpolation.
- Reload: transactional model reload that swaps lookups and retires old
  instances. Broader entity aliases, non-world CEM paths, and some visual
  references remain gates.

**Missing behavior:** Broader entity aliases, non-world CEM paths, special-case limb/entity parity, complex attachment semantics, exact player-holder body-yaw dynamics, non-default shadow sampler overrides, diagnostic parity, additional visual fixtures, and configuration.

### Entity Texture Features (ETF)

**Role:** OptiFine-style random, emissive, and variant entity textures

**License/distribution:** LGPL-3.0

**Prepared state:** The exact 7.0.13 adapter is active. Property parsing,
variant selection, emissive suffixes, two-stage blink timing, and
generation-owned selection state are source-native and headless-tested.

Capabilities:

- Selection: deterministic weighted variants with expanded entity/environment
  plus bounded entity/client-player/vehicle NBT context, existence/inversion/
  range/wildcard-path/pattern queries, negated string sets, semantic
  Minecraft-version ranges, calendar/world/client values, biome tags,
  active-mod IDs, equipment keywords, general mob variants, panda genes, llama
  inventory strength, horse jump/movement attributes, percent health, and
  predicate-gated vertical block identifiers.
- Block predicates: audited `blocks` and `blockSpawned` inspect the current and
  immediately lower block with ETF's colon-separated block-state grammar;
  `blockAboveSolid`/`blockBelowSolid` use Minosoft's full-opacity flag.
- Context: regional difficulty follows the vanilla 1.20.4 formula, and the prior
  `textureRule`/`textureSuffix` are exposed to dependent feature rules.
- Materials: emissive suffixes, a 2,048-entity LRU per texture, independent
  skeletal body/feature materials, additive emissive passes, and stable-slot
  texture compaction.
- Player skins: 64x64 ETF-marked skins derive blink/blink2, matching emissives,
  all eight coat styles and lengths, fat-coat inflation, coat emissives, leggings
  suppression, forced lower-skin opacity, villager noses, animated glint masks,
  and ETF-only base transparency.
- Reload: content and native entity models swap catalog/materials
  transactionally. Broader non-skeletal and block-entity features plus
  independent visual references remain gates.

**Missing behavior:** Broader non-skeletal and block-entity features, the rest of ETF configuration, independent variant/emissive/blink references, and wider driver coverage.

### GeckoLib

**Role:** Runtime library for mod-authored geo models and keyframe animations

**License/distribution:** MIT

**Prepared state:** The exact 4.4.4 adapter is active. Geometry, animation
clips, easing, and controllers are source-native and headless-tested.

Capabilities:

- Geometry: `.geo.json` parsing, including sound, particle, and
  custom-instruction keyframes, into the neutral model. Matching clips attach to
  geometry by namespace and basename.
- Easing: the pinned 4.4.4 built-in easing catalog plus owner-scoped custom
  easing, with first easing arguments evaluated exactly. Blockbench/Bedrock
  `math.*` trig uses degrees; bare EMF trig stays radian-based.
- Controllers: predicate/layer controllers with transitions, bounded declared
  numeric/boolean tracked-data inputs, immutable random/entity-type/nearby-player
  host state, bounded host-event-to-trigger mappings, state-driven speed/easing
  overrides, triggerable animations, and typed per-controller keyframe handlers.
- Clips: a bounded `RawAnimation` runtime for default/play-once/hold/loop clips,
  tick waits, repeat expansion, cross-stage delta carry, keyframe delivery,
  stable finished-animation identity, explicit reset, and triggered base reload.
- Routes: stable content identities cover entities, block entities, items, and
  armor. Routed geometry renders on living entities, block entities,
  world/item-display entities, first-person held items, and equipped armor slots
  with generation-baked opaque/translucent/additive texture passes.
- Reload: content reload snapshots and quiescent registration ownership. Binary
  GeckoLib/Mojang API compatibility and the remaining Molang language remain
  gates.

**Missing behavior:** Binary API compatibility, the remaining Molang language, GUI item views, exact armor-to-parent-bone fitting, checked remote controller/seam pixels and sound timing, renderer overlays, additional dependent mods, broader visual parity, and repeated visible multi-entity validation.

### OptiFine

**Role:** Original CEM and extended resource-pack implementation

**License/distribution:** Official-site distribution; no Packwiz redistribution

**Prepared state:** Catalogued as a format reference only.

**Missing behavior:** The OptiFine JAR cannot run as an ordinary Minosoft mod because it transforms Mojang/Forge classes Minosoft does not expose. Support means native Minosoft parsers and bindings for selected OptiFine formats through the exact EMF/ETF adapters, not a native OptiFine binary patcher.

### Animated Java

**Role:** Blockbench authoring/export workflow producing resource-pack and data-pack content

**License/distribution:** Authoring tool; no runtime artifact staged

**Prepared state:** Resource and data namespaces mount separately through
session-owned managers while content snapshots retire independently. The exact
Blockbench 5.1.4 plus official Animated Java 1.10.2 exporter combination
reproducibly generates a pinned, unmodified upstream armor-stand fixture (13
resource files, 109 data files, complete output manifest); the managed pack
validates and stages that exact fixture.

Capabilities:

- Functions: bounded functions, tags, storage/entity macros, schedules,
  scoreboards/storage, selectors, an exporter-oriented `execute` subset
  (score comparisons, entity-data and function predicates, feet/eyes anchors,
  facing, `on target`/`on attacker`), and display/entity mutation commands.
  `return`, `return fail`, and `return run` terminate the owning function even
  through an `execute` condition; a called function keeps its own return
  boundary.
- Display trees: session-owned display/interaction/passenger trees support
  load, summon, tick, and remove in the reduced 1.10.2 fixture.
- Templates: pinned compiler templates are fingerprinted; tests prove
  entity-backed callback dispatch and the exporter's signed four-word
  UUID-to-string path. Local attack/interact packets populate interaction
  records and run the matching advancement reward handler as the player.
- Rollback: a failed candidate load atomically restores command and entity
  state.
- Lifecycle: model discovery, load/summon/walk/remove, repeated runtime
  replacement, rejected-load recovery, stable identity, CPU cleanup, and
  real-OpenGL positive/rejected/recovery loops pass. Broader upstream
  blueprints plus remote-server, glowing-outline, and other-driver references
  remain gates.

**Missing behavior:** Broader upstream blueprints, then remote-server behavior, glowing outline pixels, and another driver or platform reference.

All four pinned artifacts now preflight as `ADAPTED` at the artifact layer.
That label means their exact metadata surfaces select Minosoft-owned adapters;
it does not execute upstream bytecode and it is not a full behavioral claim.
The inspection output separately reports mapped, partial, and unmapped
functionality. EMF, ETF, and GeckoLib remain partial until their runtime and
visual gates below pass.

The exact Animated Java authoring/export procedure, hashes, generated-output
manifest, and acceptance boundary are recorded in the
[Blockbench export evidence](agents/evidence/2026-07-24-animated-java-blockbench-export.md).
The exporter result and Minosoft's headless manifest/model discovery,
load/summon/walk/remove lifecycle, repeated runtime replacement, rejected-load
rollback/recovery, stable entity identity, and exactly-once CPU-generation
cleanup pass against the unmodified output. The exact managed fixture also
passes a real-OpenGL default-pose/four-generation summon/remove loop with
pixel-stable joint ordering and no cumulative fixture-object growth. Checked
default/walk plus rejected-reload/recovery references now pass; glowing/team
references, remote-server behavior, and another driver remain separate gates.
The display-state boundary is
recorded in the
[display semantics evidence](agents/evidence/2026-07-24-animated-java-display-semantics.md).
The accounting and teardown substrate is recorded in
[OpenGL resource-accounting evidence](agents/evidence/2026-07-24-opengl-resource-accounting.md).

A durable headless fixture loads one CEM/ETF/Gecko content set against both
Minecraft 1.19.4 and 1.20.4 protocol identities. It verifies version-specific
part aliases, CEM expression evaluation, Gecko clip evaluation, and ETF
variant/emissive discovery. This is a data/runtime compatibility gate, not
rendered visual or live-reload acceptance.

A separate pinned Naturalist fixture crosses the production local data-pack
authority, exact owner-declared dependent-mod entity materialization, retained
Gecko renderer, and content reload path. The real-GL scenario observes the
snake's one 252-vertex selected-texture body pass, all four independent
controllers, and the exact nearby-player rattle loop advancing across reload.
Retained controller diagnostics are immutable, bounded to 64 records while
preserving total count/truncation, and fail closed after owner retirement.
This closes a local controller/topology gate; checked remote controller/seam
pixels, packet sound timing, off-screen resume, and Naturalist renderer
overlays remain.

The explicitly named continuation gates—EMF neutral/JEM/JPM/alias/expression/
binding/reload, ETF selection/variant/emissive/blink/cache, Gecko ingestion and
dependent-mod controller API, Animated Java predicates/displays/data packs,
and the shared headless/multi-version/transaction/GPU lifecycle—are mapped to
current source and acceptance records in the
[gate-completion audit](agents/evidence/2026-07-26-content-fidelity-gate-completion.md).
The artifact catalogs remain `partial` relative to full upstream-project
parity; that broader boundary is intentionally not relabeled by the bounded
gate decision.

A separate managed 1.20.4 living fixture now crosses the production resource
pack, data pack, local summon, native zombie renderer, CEM composition, ETF
selection, EMF expression, content transaction, and OpenGL cleanup paths. Its
settled 750×1150 crop matches with zero tolerance before two bounded rejected
reloads and after accepted recovery. Each rejection retires 21 buffers, 11
vertex arrays, and one texture with zero live delta. The pinned EMF name is
`rule_index`; ETF's `textureRule`/`texture_rule` spelling is confined to ETF
property conditions. See the
[EMF/ETF render evidence](agents/evidence/2026-07-26-emf-etf-render-reference.md).

### Source-native Gecko dependent-mod binding

An adapted mod must bind behavior to a complete `SkeletalContentIdentity`
(geometry resource, `GECKOLIB` format, and geometry identifier). Do not route
by basename alone: one `.geo.json` document may contain several geometries.

Register the independent surfaces before the initial model load, or
before `/reload content` when adding them at runtime:

```kotlin
val identity = SkeletalContentIdentity(
    ResourceLocation.of("example:geo/clockwork_bird.geo.json"),
    SkeletalContentFormat.GECKOLIB,
    "geometry.clockwork_bird",
)

val entityRoute = GeckoLibEntityModelRegistry.register(
    owner = "example-mod",
    entity = ResourceLocation.of("example:clockwork_bird"),
    identity = identity,
)

val blockRoute = GeckoLibBlockEntityModelRegistry.register(
    owner = "example-mod",
    block = ResourceLocation.of("example:clockwork_nest"),
    identity = identity,
)

val itemRoute = GeckoLibItemModelRegistry.register(
    owner = "example-mod",
    item = ResourceLocation.of("example:clockwork_bird"),
    identity = identity,
)

val armorRoute = GeckoLibArmorModelRegistry.register(
    owner = "example-mod",
    item = ResourceLocation.of("example:clockwork_helmet"),
    identity = identity,
)

val controllers = GeckoLibControllerBindingRegistry.register("example-mod", identity) { content ->
    listOf(
        GeckoLibControllerDefinition(
            name = "locomotion",
            initialClip = "animation.clockwork_bird.idle",
            trackedDataInputs = listOf(
                GeckoLibTrackedDataInput("example.perched", index = 19),
            ),
            hostStateInputs = listOf(
                GeckoLibHostStateInput(
                    "example.nearby_player",
                    GeckoLibHostStateQuery.NearbyPlayer(
                        range = 4.0,
                        horizontalExpansion = 4.0,
                        verticalExpansion = 2.0,
                    ),
                ),
            ),
            predicate = GeckoLibAnimationPredicate { state, current ->
                val target = if (state.data["example.perched"] == 1.0) {
                    "animation.clockwork_bird.perch"
                } else if (state.moving) {
                    "animation.clockwork_bird.walk"
                } else {
                    "animation.clockwork_bird.idle"
                }
                if (target == current) GeckoLibControllerDecision.Keep
                else GeckoLibControllerDecision.Play(target)
            },
        ),
        GeckoLibControllerDefinition(
            name = "attack",
            triggerableAnimations = mapOf(
                "attack" to "animation.clockwork_bird.attack",
            ),
            eventTriggers = mapOf(
                GeckoLibHostEvents.entityAnimation(EntityAnimations.SWING_MAIN_ARM) to "attack",
            ),
        ),
    )
}

val effects = GeckoLibRuntimeEffectRegistry.register("example-mod", identity) { context ->
    when {
        context.event.type != SkeletalAnimationEventType.SOUND ->
            GeckoLibRuntimeEffectResolution.PassThrough
        context.event.payload == "wing_flap" ->
            GeckoLibRuntimeEffectResolution.Play(
                ResourceLocation.of("example:entity.clockwork_bird.wing_flap"),
            )
        else -> GeckoLibRuntimeEffectResolution.Ignore
    }
}

val normal = ResourceLocation.of("example:textures/entity/clockwork_bird.png")
val alarm = ResourceLocation.of("example:textures/entity/clockwork_bird_alarm.png")
val baseTextures = GeckoLibEntityTextureRegistry.register(
    owner = "example-mod",
    identity = identity,
    definition = GeckoLibEntityTextureDefinition(
        fallback = normal,
        textures = setOf(normal, alarm),
        selector = GeckoLibEntityTextureSelector { state ->
            if (state.aggressive || state.boolean(19)) alarm else normal
        },
    ),
)

val layers = GeckoLibRenderLayerRegistry.register(
    owner = "example-mod",
    identity = identity,
    definitions = listOf(
        GeckoLibRenderLayerDefinition(
            name = "eyes",
            texture = ResourceLocation.of("example:textures/entity/clockwork_bird_eyes.png"),
            blend = GeckoLibRenderLayerBlend.ADDITIVE,
            fullBright = true,
        ),
    ),
)
```

Controller host state is declaration-driven just like tracked data. The current
immutable query DTOs cover bounded random integers, exact entity-type checks,
and nearby-player range/AABB checks. The entity renderer resolves only declared
inputs; headless managers accept an explicit resolver. Input names must be
unique across tracked and host declarations, and retained query DTOs contain no
adapter callback that could cross content generations.

Animation sound and particle `effect` values are handler-defined aliases in
GeckoLib; they are not inherently Minecraft resource locations. A runtime
effect resolver returns `Play` to map an alias, `Ignore` to reproduce a handler
that deliberately does nothing, or `PassThrough` only when the payload is
already meant to be parsed as a resource location. Entity, item, armor, and
block-entity consumers bind the resolver generation when the retained instance
is created. Closing the owner makes that old binding fail closed, so a retained
old model cannot invoke a replacement adapter's resolver.

Remote entity routes also need a native factory definition. This is separate
from the geometry route because the server chooses the numeric protocol ID:

```kotlin
val remoteTypes = FabricRemoteRegistrySync.register(
    owner = "example-mod",
    values = listOf(
        FabricRemoteEntityDefinition(
            identifier = ResourceLocation.of("example:clockwork_bird"),
            width = 0.5f,
            height = 0.6f,
        ),
    ),
)
```

When Fabric API is active, Minosoft participates in the standard Fabric
Registry Sync v0 configuration handshake. It advertises
`fabric:registry/sync/direct`, reassembles the bounded direct payload, decodes
grouped namespaces and delta-coded raw IDs, and transactionally replaces the
receiving session's entity-ID table. Known vanilla types keep their native
factories; owner-scoped dependent-mod definitions materialize generic living or
non-living types. An unknown remote entity type rejects the transaction instead
of allowing a later spawn packet to use the wrong factory. The current boundary
covers entity IDs only. Other synchronized registries, mod-specific tracked
data beyond exact source-native adapter mappings, binary Gecko/Mojang APIs, and
checked live remote pixels remain separate gates.

Close registrations in reverse order when the owning adapter unloads. Closure
immediately prevents retained instances from calling the old controller or
base-texture/render-layer predicates. Base-texture definitions must declare a
non-empty candidate set containing their fallback; a selector returning
anything else uses that fallback. `state.int(index)` and
`state.boolean(index)` are bounded accessors over exact protocol-level tracked
indices and should be used only for a pinned dependent-mod/version mapping.
Controller predicates declare their own numeric/boolean inputs with
`GeckoLibTrackedDataInput`; the living renderer reads only those indices and
places their finite values in `GeckoLibAnimationState.data`. Missing,
non-numeric, or non-finite values use the declared default. Conflicting
definitions for one input name reject controller construction instead of
silently reinterpreting the wire value.

Geometry inflation changes retained vertex bounds, not box-UV atlas layout.
For Gecko content the binder follows pinned 4.4.4 behavior and floors the
uninflated source cube size before deriving the six box-UV rectangles. This is
kept separately as `SkeletalElement.boxUvSize`; using inflated `from`/`to`
dimensions here would stretch atlas coordinates and sample across neighboring
regions. Gecko-compatible zero-thickness cubes still retain all six authored
faces because those faces may separate under bone animation.

`eventTriggers` maps a namespaced host event to a trigger declared by the same
controller. An undeclared trigger rejects controller construction. Incoming
entity-animation packets are appended to a 64-entry per-entity sequence
journal; render consumers keep independent cursors and receive only events at
most two entity ticks old. The oldest entries are discarded on overflow, so a
renderer does not retain unbounded network history or replay a stale attack
after a long absence. Dispatch crosses the same quiescent registration boundary
as controller predicates: closing the owner immediately suppresses retained
event callbacks. This path currently covers protocol `EntityAnimations`;
arbitrary Gecko gameplay events still require an explicit source-native
adapter mapping.

Base and layer textures plus their meshes remain owned by the content
generation and are released with the retired model. Replacement registrations
cannot drive meshes baked for an earlier registration. Run `/reload content`
after changing definitions or textures so their candidates join the atomic
model/texture publication.

A headless or non-rendered object can create its controller manager from the
exact leased `ContentFidelitySnapshot`. Use
`GeckoLibInstancedAnimatableInstanceCache` when the object itself owns one
timeline, or `GeckoLibSingletonAnimatableInstanceCache` when one registered
object represents multiple network/item instance IDs. Singleton entries are
access-order bounded; eviction and cache closure close each manager, clear its
typed data tickets, and prevent further callbacks. Manager snapshots preserve
compatible controller progress, data, and update time across a replacement
content generation.

Block routes are keyed by the block identifier carried by the block entity's
current state. Item routes cover world item entities, item-display entities,
and first-person held items. Armor routes are deliberately separate from item
routes so an equipped stack can select wearable geometry without changing its
ordinary item presentation. GUI item views and exact attachment of armor bones
to a parent entity rig remain explicit follow-up gates.

This is a Minosoft source API. A JAR compiled against GeckoLib and Mojang class
signatures still requires a dedicated exact adapter or a separately scoped
binary bridge.

Prepare and inspect the trajectory with:

```sh
./play.sh modpack prepare content-fidelity --trajectory content-fidelity-main
./play.sh modpack inspect content-fidelity --trajectory content-fidelity-main
```

The upstream Minecraft-targeted bytecode remains inactive. The acceptance
ladder in `modpacks/content-fidelity/ladder.tsv` separates artifact adaptation,
format ingestion, renderer behavior, and full compatibility claims.

## Adapter design contract

Higher-fidelity support is split into format ingestion and runtime binding:

1. **Probe** artifacts and resource packs without loading code.
2. **Parse** CEM, ETF, GeckoLib, or Animated Java files into format-specific
   immutable DTOs with source locations and useful validation failures.
3. **Normalize** geometry, bones, materials, animation channels, and conditions
   into a Minosoft-owned intermediate model.
4. **Bind** normalized bones to a version-aware entity or display-entity
   renderer. Missing parts must fail with a useful diagnostic and stay
   recoverable.
5. **Bake** CPU geometry and animation state away from live GPU objects.
6. **Apply** textures, meshes, and renderer registrations on the render thread
   through one generation-owned scope.
7. **Dispose** callbacks, caches, meshes, textures, and GPU buffers before the
   generation classloader is released.

Retained skeletal models and `/reload content` implement the first transactional
GPU generation. Retired models stop accepting new instances but keep running on
live instances. New candidates upload before publication, then lookup maps swap
as one commit, and the last old instance unloads the old buffers. Content models
keep their ETF catalog and cache lease through the same boundary.

Static texture updates work the same way: Minosoft prepares replacement GPU
handles for the affected size buckets, keeps every published shader
array/layer coordinate stable, and switches lookups and handles together with
the model commit. A rollback restores the prior names and handles and releases
candidate resources; a successful commit releases the replaced handles. New
texture keys append without consuming extra sampler units. Stable
array/layer leases wait for retained models to release the old generation
before reclaiming. Dead internal holes are reused before appending, dead
trailing layers are removed on the next transaction, and permanent
resource-pack coordinates are never reclaimed. If a bucket upload fails,
Minosoft deletes the incomplete handle and earlier candidate handles. Created,
deleted, live, and active-handle diagnostics exist for a real-driver acceptance
run, and repeated real-OpenGL acceptance is still required.

An adapter must declare:

- the exact artifact IDs and versions it recognizes;
- every Fabric preflight blocker it handles;
- accepted resource paths and schema versions;
- entity/model lookup and fallback rules;
- animation clock, interpolation, expression, and variable semantics;
- render passes and OpenGL state it establishes/restores;
- cache ownership and invalidation on resource reload;
- headless behavior when no renderer is present;
- focused fixtures plus a live visual/reload acceptance scenario.

Prefer direct parsers for documented data formats. Do not execute upstream
Minecraft-targeted entrypoints merely to obtain a parser, and do not mark a
whole library compatible when only one data format has been adapted.

The vanilla foundation and first third-party ingestion/runtime slice are
implemented independently of upstream executable code. Durable decisions and
remaining gates are recorded in the
[foundation evidence](agents/evidence/2026-07-24-content-fidelity-foundation.md)
and [native adapter evidence](agents/evidence/2026-07-24-content-fidelity-native-adapters.md).

## Acceptance matrix

A format moves from staged to adapted only after all applicable gates pass:

### Artifact gate

Exact URL/hash, license, environment, dependencies, nested JARs, mixins, and
access wideners are recorded.

### Parser gate

Valid fixtures, malformed-input diagnostics, namespace resolution, and
schema/version cases pass without OpenGL.

### Geometry gate

Pivots, parent/child transforms, cubes, UVs, mirroring, inflation, rotations,
and visibility match a reference fixture.

### Animation gate

Loop/hold/once, interpolation/easing, concurrent channels, variables, and time
advancement have deterministic tests.

### Materials gate

Base, emissive, random/variant, translucent, and high-resolution textures bind
to explicit render passes.

### Runtime gate

Entity/display lookup, world lifecycle, off-screen culling, and headless
operation are defined.

### Reload gate

Candidate failure preserves the old model; successful apply swaps model,
routing, material/cache, and texture generations together; retained readers
delay disposal; repeated reload returns real GPU/resource counts to baseline.

### Visual gate

A pinned resource pack or mod fixture is captured from several views and
animation timestamps, not one screenshot.

## Source map

The primary implementation entry points are:

- `assets/AssetsLoader.kt` and `assets/session/SessionAssetsManager.kt` for stack
  construction;
- `assets/model/generation/` for discovery, immutable snapshots, leases, and
  transactional generation ownership;
- `assets/datapack/` for data-namespace discovery, function/tag loading, SNBT,
  macros, scheduling, scoreboard/storage state, bounded execute/command
  evaluation, transactional load, and the generation-leased local runtime;
- `local/datapack/` for selectors and the headless command bridge to
  session-owned display/interaction entities, including lifecycle rollback;
- `assets/model/skeletal/` and `assets/model/texture/entity/` for neutral
  CEM/Gecko/ETF data and headless parsers;
- `assets/multi/PriorityAssetsManager.kt` for first-match resolution;
- `gui/rendering/models/loader/` for block, item, fluid, and skeletal loading;
- `gui/rendering/models/block/` for vanilla JSON model parsing and baking;
- `gui/rendering/system/base/texture/` and `gui/rendering/textures/` for PNG and
  animation handling;
- `gui/rendering/skeletal/` for Minosoft-native skeletal models;
- `modding/loader/fabric/` for metadata preflight, exact adapters, ownership,
  and diagnostics;
- `modpacks/` for reproducible third-party artifact manifests and acceptance
  ladders.

Focused tests live beside these packages under `src/test/java` and
`src/integration-test/kotlin`. Real asset, packet, and model fixtures belong in
`src/integration-test/resources`.
