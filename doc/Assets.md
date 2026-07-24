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
  key. It does not say who authored the bytes.
- An **asset manager** exposes byte streams by resource location.
- A **session asset stack** combines managers in priority order. The first
  manager containing a key wins.
- A **resource pack** is a ZIP or directory containing an `assets/` tree. It is
  data, not executable code.
- A **mod artifact** is a JAR with metadata and possibly code, nested JARs, and
  an `assets/` tree. Staging its bytes does not make its code compatible.
- A **compatibility adapter** maps one exact upstream artifact surface onto
  Minosoft-owned APIs and lifecycle hooks. `ADAPTED` never means that Minosoft
  executed the upstream Minecraft-targeted bytecode.

Minosoft consumes locally installed or user-selected compatibility content.
`JarAssetsManager` and `IndexAssetsManager` do not retrieve missing content from
official Minecraft services. A missing archive, index, or indexed object fails
with an instruction to import a local asset pack or mod.

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

`PlaySession.connect` loads an asset candidate, enumerates its effective
resource locations, parses registered higher-fidelity formats headlessly, and
then publishes the asset manager and immutable content snapshot together.
Preparation or commit failure closes the candidate and preserves the previous
content generation. Readers lease a generation; replaced assets and future GPU
objects are disposed only after its final reader exits. Fabric-shaped resource
reload events expose prepare, apply, complete, and failed phases. Manual shader
and texture reload already cross the render queue, but a live resource-pack
re-bake and renderer-generation swap is still incomplete.

## Asset priority

`AssetsLoader` adds managers in the following lookup order, from highest to
lowest priority:

1. integrated emergency/version overrides;
2. resource packs in the resources profile, with later profile entries winning;
3. integrated pack-format compatibility layers, newest applicable format first;
4. local Minecraft index assets, when enabled;
5. local client-JAR assets, when enabled;
6. generation-owned external asset providers, including adapted mod JARs;
7. integrated defaults.

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

Item `overrides` use vanilla last-match selection for `custom_model_data`,
`damage`, and `damaged`; selected element-backed models retain their cuboid
geometry. The remaining vanilla predicate catalog and modern component-based
item-model dispatch still need explicit compatibility fixtures.

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

At render initialization, the neutral geometry bridge creates retained
`SkeletalModel` instances, preserves direct OptiFine texture paths, and maps
CEM filenames such as `cow.jem` to matching entity IDs. Existing animal and
humanoid renderers prefer that mapped CEM model. Static bone rotation/scale and
face UV rotation survive the bridge. Version/entity aliases now rename bound
geometry, transforms, and expression targets. A per-instance, reflection-free
runtime applies ordered CEM transform/visibility expressions with persistent
`var.*`/`varb.*` state and an initial entity/render variable set. This is still
partial EMF. Native entity parts survive targeted CEM replacement and
`attach:true` roots use isolated child transforms, but the complete alias and
variable catalogs, exact absolute part-property/complex attachment semantics,
live model replacement, and visual reference fixtures remain gates.

## Higher-fidelity compatibility trajectory

The tracked `content-fidelity` pack prepares a metadata and artifact baseline
for Blockbench-related content:

| Component | Role | License/distribution | Prepared state | Missing behavior |
| --- | --- | --- | --- | --- |
| Entity Model Features (EMF) | Fabric replacement for OptiFine CEM geometry and expressions | LGPL-3.0 | Exact 3.0.17 adapter activates; JEM/JPM, live version/entity aliases, bounded per-instance expressions, targeted native-part replacement, isolated attach roots, discovery, and initial skeletal binding are source-native. | Complete part/variable catalogs, exact part-property and complex attachment semantics, live renderer replacement, fallback/visual fixtures, and configuration. |
| Entity Texture Features (ETF) | OptiFine-style random, emissive, and variant entity textures | LGPL-3.0 | Exact 7.0.13 adapter activates; property parsing, deterministic variants, expanded entity/environment plus bounded nested-NBT context, finite material discovery, generation-owned caching, and retained skeletal base/emissive passes with state restoration exist. | Complete ETF predicate parity, player/feature textures, configuration, live reload, and visuals. |
| GeckoLib | Runtime library for mod-authored geo models and keyframe animations | MIT | Exact 4.4.4 adapter activates; geo and animation JSON parse into the neutral model, matching clips attach to geometry, and a source-native API supplies predicate controllers, concurrent replace/add layers, transitions, and generation-owned animatable caches. | Complete easing, events, automatic routing, binary API compatibility, object/block/item/armor coverage, and dependent-mod validation. |
| OptiFine | Original CEM and extended resource-pack implementation | Official-site distribution; no Packwiz redistribution | Catalogued as a format reference only. | Its runtime transforms Mojang/Forge classes that Minosoft does not expose, so the JAR cannot be activated as an ordinary mod. Selected OptiFine content formats require Minosoft-native parsers and render bindings, surfaced through exact EMF/ETF compatibility adapters. |
| Animated Java | Blockbench authoring/export workflow producing resource-pack and data-pack content | Authoring tool; no runtime artifact staged | Resource and data namespaces mount separately into one session content generation. Local sessions run bounded functions, tags, macros, schedules, scoreboards/storage, selectors, an exporter-oriented `execute` subset including feet/eyes anchors and coordinate/entity facing, and display/entity mutation commands. Session-owned display/interaction/passenger trees support load→summon→tick→remove in a reduced fixture pinned to Animated Java 1.10.2. Failed candidate load tags atomically restore command and entity state. This remains an import workflow, not a runtime JAR. | Complete target/attacker relationships and data-manager/UUID utilities, wire interaction callbacks, accept an unmodified raw exporter fixture, and prove remote-server, visual, and repeated GPU-reload behavior. |

All four pinned artifacts now preflight as `ADAPTED` at the artifact layer.
That label means their exact metadata surfaces select Minosoft-owned adapters;
it does not execute upstream bytecode and it is not a full behavioral claim.
The inspection output separately reports mapped, partial, and unmapped
functionality. EMF, ETF, and GeckoLib remain partial until their runtime and
visual gates below pass.

A durable headless fixture loads one CEM/ETF/Gecko content set against both
Minecraft 1.19.4 and 1.20.4 protocol identities. It verifies version-specific
part aliases, CEM expression evaluation, Gecko clip evaluation, and ETF
variant/emissive discovery. This is a data/runtime compatibility gate, not
rendered visual or live-reload acceptance.

Prepare and inspect the trajectory with:

```sh
./play.sh modpack prepare content-fidelity --trajectory content-fidelity-main
./play.sh modpack inspect content-fidelity --trajectory content-fidelity-main
```

The upstream Minecraft-targeted bytecode remains inactive. The acceptance
ladder in `modpacks/content-fidelity/ladder.tsv` separates artifact adaptation,
format ingestion, renderer behavior, and full compatibility claims.

## Adapter design contract

Higher-fidelity support should be split into format ingestion and runtime
binding:

1. **Probe** immutable artifacts and resource packs without loading code.
2. **Parse** CEM, ETF, GeckoLib, or Animated Java files into format-specific
   immutable DTOs with source locations and useful validation failures.
3. **Normalize** geometry, bones, materials, animation channels, and conditions
   into a Minosoft-owned intermediate model.
4. **Bind** normalized bones to a version-aware entity or display-entity
   renderer. Missing parts must be diagnostic and recoverable.
5. **Bake** CPU geometry and animation state away from live GPU objects.
6. **Apply** textures, meshes, and renderer registrations on the render thread
   through one generation-owned scope.
7. **Dispose** callbacks, caches, meshes, textures, and GPU buffers before the
   generation classloader is released.

Retained skeletal models implement the first GPU-lifetime half of this
contract: retirement prevents new instances, active instances keep the old
mesh alive, and the final release unloads uploaded buffers or drops an
unuploaded candidate. Content models retain their exact ETF catalog/cache lease
through the same boundary. Atomic live candidate bake/swap and repeated
real-OpenGL accounting remain required.

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

| Gate | Required evidence |
| --- | --- |
| Artifact | Exact URL/hash, license, environment, dependencies, nested JARs, mixins, and access wideners are recorded. |
| Parser | Valid fixtures, malformed-input diagnostics, namespace resolution, and schema/version cases pass without OpenGL. |
| Geometry | Pivots, parent/child transforms, cubes, UVs, mirroring, inflation, rotations, and visibility match a reference fixture. |
| Animation | Loop/hold/once, interpolation/easing, concurrent channels, variables, and time advancement have deterministic tests. |
| Materials | Base, emissive, random/variant, translucent, and high-resolution textures bind to explicit render passes. |
| Runtime | Entity/display lookup, world lifecycle, off-screen culling, and headless operation are defined. |
| Reload | Candidate failure preserves the old model; successful apply frees all old registrations and GPU resources. |
| Visual | A pinned resource pack or mod fixture is captured from several views and animation timestamps, not one screenshot. |

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
