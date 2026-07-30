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
content generation. Readers lease a generation; replaced assets and GPU models
are disposed only after their final reader exits. Fabric-shaped resource reload
events expose prepare, apply, complete, and failed phases. `/reload content`
now reparses CEM, ETF, and Gecko content, bakes and uploads a candidate on the
render thread, then atomically swaps skeletal model/entity lookups and flags
live entity renderers to recreate their instances. Referenced static textures
participate in the same transaction: affected resolution buckets are rebuilt
into candidate GPU handles while existing shader coordinates remain stable,
then published with the model generation or rolled back. New texture keys can
therefore enter a content-fidelity reload; missing assets still reject the
candidate without advancing the generation. Content-owned shader slots are
leased by stable array/layer coordinate: retired holes are reused without
renumbering live meshes, and dead trailing layers shrink on the next
transaction. Pre-existing resource-pack slots remain permanent. General
resource-pack reload and atomic shader reload remain incomplete. The bounded
debug acceptance form `render.reload-content {"rejectAt":"after-upload"}` or
`{"rejectAt":"after-publication"}` runs the real allocation/rollback path
without changing mounted files. It reports whether the active generation and
all typed live OpenGL counts stayed unchanged; this is a diagnostic contract,
not a content-pack extension.

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

Item `overrides` use vanilla last-match selection. For the audited 1.20.4
catalog, stack-backed support covers `custom_model_data`, `damage`, `damaged`,
crossbow `charged`/`firework`, elytra `broken`, and light-block `level`.
An explicit render context supplies `lefthanded`, `cooldown`, bow/crossbow
`pull`/`pulling`, `brushing`, fishing-rod `cast`, shield `blocking`, trident
`throwing`, and goat-horn `tooting`. First-person and GUI selection build this
context from live player/session state. Living entities retain elapsed use
ticks from the tracked active-hand flag and equipment identity, resetting on
hand/stack replacement or stop; this also drives remote bow/crossbow/brush
progress.
Bundle `filled` uses registry-backed stack capacities and the vanilla nested
bundle/beehive rules. Clock `time` and compass/recovery-compass `angle` consume
live world, dimension, position, spawn/lodestone/death-target state through a
render-context-owned smoothing runtime. `trim_type` uses the fixed 1.20.4 trim
material indices. Initialize and respawn packets retain the local player's last
death position for the recovery compass.
Unknown or wrong-item predicates contribute negative infinity, matching
vanilla rather than accidentally matching zero or negative thresholds.
Selected element-backed models retain their cuboid geometry. World and display
item features reselect live providers each update and retire/rebuild their mesh
when an override threshold changes; held and GUI items resolve on each draw.

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

In Creative mode, proportional `Items`, `Creatures`, and JEI-owned `Recipes`
tabs use more of a fullscreen viewport without stretching vanilla inventory
slots. The creature tab searches the active session entity registry and
previews every entity with an actual baked skeletal route, including routed CEM
and GeckoLib content. It retains raw production quads and bone IDs, evaluates
the model's real clips on the CPU, including advancing `query.anim_time`/`q.anim_time`
for expression channels, provides an animation selector, supports
drag rotation, can hold the authored default rotation, and slowly orbits while
idle. Selected test clips loop locally, including production one-shots, without
changing runtime clip semantics. It does not instantiate a
client-only entity. Exact entity identity remains part of texture selection
when several species share geometry. `Spawn` uses the normal server `/summon`
command and is disabled when absent from the server-granted command tree;
woolly and sheared sheep are fixed, non-injectable state presets.

Skeletal matrices use column-vector joint order
`T(pivot) · R · S · T(-pivot)`. Gecko/Bedrock static and animated rotations are
converted from their clockwise model convention before applying that hierarchy.
Gecko box UVs retain Bedrock's negative-Z front slot and mirrored U direction
instead of using vanilla Java's reversed north/south assignment. Hinged
zero-thickness planes snap to nearby pivots and overlap the joint by 0.05 source
pixels without changing their authored UV density. These invariants are shared
by production rendering and preview playback.

At render initialization, the neutral geometry bridge creates retained
`SkeletalModel` instances, preserves direct OptiFine texture paths, and maps
CEM filenames such as `cow.jem` to matching entity IDs. Existing animal and
humanoid renderers prefer that mapped CEM model. Static bone rotation/scale and
face UV rotation survive the bridge. Version/entity aliases now rename bound
geometry, transforms, and expression targets. A per-instance, reflection-free
runtime applies ordered CEM transform/visibility expressions with persistent
`var.*`/`varb.*` state. The EMF 3.0.17 public input-name catalog and compatibility
spellings are recognized. The bounded expression VM covers the audited
arithmetic, conditional, keyframe/interpolation, random, angle, curve, and
named-easing methods. Part-property reads resolve live aliased pivots,
rotations, scales, visibility, and box-hidden state; missing part reads follow
the pinned zero fallback. Writes are ordered absolute assignments and reject
missing targets. `visible=false` suppresses the part subtree, while
`visible_boxes=true` suppresses only the target part's cubes and leaves its
children renderable. Ordered `render.shadow_*` and `render.leash_offset_*`
assignments feed owner-scoped native shadow and leash features. Shadow size,
opacity, horizontal offsets, distance fading, baby scaling, and EMF's 32-block
size cap are applied. A bounded vertical scan projects shadows across lit,
full-collision terrain surfaces with shape bounds and vertical/light falloff;
block/chunk revisions invalidate stationary projections after world changes.
Projected quads sample the active resource pack's
`minecraft:textures/misc/shadow.png` with the audited inverted two-radius UV
mapping, fixed level-zero clamped sampling, and standard source-alpha blending.
Leash offsets add to the vanilla local mob anchor and rotate with body yaw
without conflating the protocol's leash holder with vehicle state. Generic
holders use the standing-eye-height anchor, fence knots use their distinct
`+0.2` anchor, and player holders use main-arm plus normal, swimming, and
elytra/riptide pose formulas. The mesh is the audited pair of crossed
24-segment ribbons with quadratic sag and alternating vanilla colors rather
than a chain of generic line prisms. Mobs derive the vanilla body-control pose
into separate previous/current yaw history; native and adapted skeletal roots
and the mob-side leash anchor consume its partial-tick interpolation. Each
leash segment independently interpolates endpoint block and sky light and
passes the packed value through the renderer lightmap. Exact player-holder
body-yaw dynamics and rendered-reference comparison remain acceptance gates.
The renderer publishes its
observable entity, player, time, health, swing, movement, attachment, and
visibility state.
Catalogued values that do not yet have a Minosoft source default safely, so
their exact live semantics are still a gate. Entity-specific aliases cover the
currently shipped player, zombie, cow, pig, and sheep rigs without leaking a
generic humanoid mapping into unrelated entities. This is still partial EMF.
Native entity parts survive targeted CEM replacement and
`attach:true` roots use isolated child transforms. Raw `nbt(key,query)`
expressions share ETF's bounded nested-NBT matcher, including escaped raw
arguments, existence/inversion, integer ranges, wildcard list paths, and
string/pattern/regex queries. Live content reload replaces the retained model
and rejects new instances of the old model while allowing already-retained
instances to finish. Broader entity aliases, exact live semantics for every
catalogued variable, complex attachment semantics,
non-default shadow sampler-metadata overrides and reference-render acceptance,
exact player-holder body-yaw dynamics, diagnostic parity, and visual reference
fixtures remain gates.

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

| Component | Role | License/distribution | Prepared state | Missing behavior |
| --- | --- | --- | --- | --- |
| Entity Model Features (EMF) | Fabric replacement for OptiFine CEM geometry and expressions | LGPL-3.0 | Exact 3.0.17 adapter activates; JEM/JPM, five shipped native-rig alias sets, the full public expression input-name catalog (apart from intentional `nan`), the audited numeric/boolean method surface, raw `nbt(key,query)` predicates, centralized live partial-tick clocks/frame counters, degree/radian and body-relative rotations, vanilla limb interpolation, movement projection, dimensions, position/health/hurt/death/equipment/use/attachment/locomotion/tame/aggressive/anger inputs, bounded fluid/ground and stopped-arrow probes, dimension/biome/heightmap-aware rain wetness using the pinned fixed-seed altitude and frozen-biome temperature samplers, hovered state, selected ETF rule index, an explicit first-person/held/item-frame/GUI/head/shoulder context carrier, live aliased part-property reads, ordered absolute writes, distinct subtree visibility versus local-box hiding, missing-target rejection, targeted native-part replacement, isolated attach roots, discovery, skeletal binding, owner-scoped native shadow/leash output consumption, bounded terrain-conforming projection using the resource-pack shadow texture with audited UV/clamp/LOD/blend behavior, audited local mob/EMF and generic/knot/player leash anchors with crossed 24-segment ribbon geometry, previous/current vanilla mob body-control interpolation shared by skeletal roots and leash anchors, per-segment endpoint block/sky lightmap interpolation, generation-leased texture slots, and transactional model reload are source-native. A checked native-zombie reference proves one targeted CEM replacement driven by ETF's selected `rule_index`, exact settled pixels, rollback, and recovery. | Broader entity aliases; CEM invocation from non-world render paths; special-case limb/entity parity; complex attachment semantics; exact player-holder body-yaw dynamics; non-default shadow sampler-metadata overrides; diagnostic parity; additional visual fixtures; and configuration. |
| Entity Texture Features (ETF) | OptiFine-style random, emissive, and variant entity textures | LGPL-3.0 | Exact 7.0.13 adapter activates; property parsing, deterministic variants, expanded entity/environment plus bounded entity/client-player/vehicle NBT context, NBT existence/inversion/range/wildcard-path/pattern queries, negated string sets, semantic Minecraft-version ranges, calendar/world/client values, biome tags, active-mod IDs, equipment keywords and item IDs, general mob variants, panda genes, llama inventory strength, horse jump and movement attributes, percent health, spawner false for ordinary entities, and predicate-gated vertical block identifiers exist. Audited `blocks` and ETF's misleadingly named `blockSpawned` both inspect the current and immediately lower block, including ETF's colon-separated block-state subset grammar; `blockAboveSolid`/`blockBelowSolid` consume Minosoft's `solid_render`-derived full-opacity flag, matching the pinned `isOpaqueFullCube` call. Regional difficulty follows the vanilla 1.20.4 client formula, and generation-owned selection state exposes the prior `textureRule` and `textureSuffix` to dependent feature rules. Configured emissive suffixes, two-stage blink timing, generation-owned 2,048-entity LRU selection state per texture, independent skeletal body/feature materials, and additive emissive passes exist. ETF-marked 64×64 player skins derive blink/blink2, matching emissive textures, all eight coat styles and lengths, moved-coat base edits, fat-coat inflation, coat emissives, leggings suppression, forced lower-skin opacity, legacy/controller villager noses, five textured nose sources, removal semantics, nose emissives, marker-selected animated glint masks, and profile-controlled ETF-only base transparency without upstream code. World and first-person views share the derived base/blink material state. Content and native entity models participate in transactional catalog/material replacement and stable-slot texture compaction. A checked health-selected zombie rule reaches the CEM expression context and remains exact through rejected and accepted real-GL transactions. | Broader non-skeletal and block-entity feature bindings, the rest of ETF configuration, independent variant/emissive/blink visual references, and wider driver coverage. |
| GeckoLib | Runtime library for mod-authored geo models and keyframe animations | MIT | Exact 4.4.4 adapter activates; geo and animation JSON—including sound, particle, and custom-instruction keyframes—parses into the neutral model. Matching clips attach to geometry, and the pinned built-in easing catalog plus first easing argument evaluate with 4.4.4 behavior. Blockbench/Bedrock `math.*` trig expressions use degrees while bare EMF trig remains radian-based, and controller transitions retain their evaluated source pose. Owner-scoped custom easing registration reaches both source-native controllers and retained playback. Predicate/layer controllers support transitions, bounded declared numeric/boolean protocol tracked-data inputs, immutable bounded random/entity-type/nearby-player host-state inputs, bounded host-event-to-trigger mappings, state-driven animation speed/easing overrides, triggerable animations, typed per-controller keyframe handlers that preserve locator and `pre_effect_script` data, and generation-owned caches. A bounded `RawAnimation` stage builder/runtime covers default/play-once/hold/loop clips, tick waits, repeat expansion, cross-stage delta carry, keyframe delivery, stable finished-animation identity, explicit reset, and triggered base-animation reload. Generic object animatables have a headless manager facade with typed data tickets, first-tick/update state, controller triggers, reload snapshots, quiescent registration ownership, a shared instanced cache, and a bounded per-ID singleton cache. Custom loop names survive JSON normalization and resolve through an owner-scoped repeat/advance/hold registry. Stable content identities and owner-scoped routes cover entities, block entities, items, and armor without retaining a content generation. Routed geometry now renders on living entities, block entities, world/item-display entities, first-person held items, and equipped armor slots. Owner-scoped, generation-baked opaque/translucent/additive texture passes support state predicates and optional full-bright rendering. Dependent-mod base-texture definitions independently declare every bakeable candidate and select only among that set using bounded entity identity/name/baby/aggressive and tracked-data state; generation tokens prevent replacement callbacks from driving retained old models. Compatible named controller layers preserve clip/raw-queue progress and fired-event position across retained entity, item, and armor replacement; routed block-entity section caches rebuild after publication. Finalized transforms feed native positional sound and registered particle factories, locator positions resolve after bone transforms, and custom instructions reach an owner-scoped listener. Handler-defined sound/particle aliases can be mapped, suppressed, or passed through by a content-identity-bound resolver; retained entity/item/armor/block-entity bindings fail closed after owner replacement. Retained geometry/clip/base/layer-texture candidates swap transactionally, with generation-leased stable texture slots. Entity animation packets enter a bounded per-entity sequence journal, and retained living consumers replay only fresh events through owner-scoped controller triggers. The pinned Naturalist adapter supplies 32 dependent-mod routes, 25 content identities and texture resolvers, and all 33 remote entity definitions. Its native zebra renderer is bridged from the exact adult horse layer into neutral geometry; the unrelated ostrich mesh is excluded while shipped zebra clip bone names remain attachable. Its snake primary controller selects stopped default, move, climb, and sleep from exact pinned tracked indices; a separate controller maps main/off-arm swing events to the exact non-looping 0.25-second attack clip. Independent tongue and rattle controllers reproduce the pinned random-versus-age play-once predicate and exact rattlesnake/nearby-player loop predicate. The exact sound handler maps only tongue `hiss` and suppresses the current `idle` plus handler-less `rattle` aliases. Live server-spawned rattlesnakes resolve their exact geometry and source texture through rejection/recovery with balanced candidate GL objects; the retained snake is one 252-vertex selected-texture pass with no duplicate fallback, emissive, or Gecko overlay body. | Binary API compatibility; the remaining Molang language; GUI item views; exact armor-to-parent-bone fitting; checked remote Naturalist controller/seam pixels and sound timing; source renderer overlays/held-food substitutions; additional dependent mods; broader visual parity; and repeated visible multi-entity validation. |
| OptiFine | Original CEM and extended resource-pack implementation | Official-site distribution; no Packwiz redistribution | Catalogued as a format reference only. | The OptiFine JAR cannot be activated as an ordinary Minosoft mod because it transforms Mojang/Forge classes that Minosoft does not expose. Support therefore means native Minosoft parsers and render/runtime bindings for selected OptiFine formats, surfaced through exact EMF/ETF adapters—not a native implementation of OptiFine's binary patcher. |
| Animated Java | Blockbench authoring/export workflow producing resource-pack and data-pack content | Authoring tool; no runtime artifact staged | Resource and data namespaces mount separately through session-owned managers while content snapshots retire independently. Local sessions run bounded functions, tags, storage/entity macros, schedules, scoreboards/storage, selectors, an exporter-oriented `execute` subset including score comparisons, entity-data and function predicates, feet/eyes anchors, coordinate/entity facing, and `on target`/`on attacker`, plus display/entity mutation commands. `return`, `return fail`, and `return run` terminate the owning function even when reached through an `execute` condition; a called function still owns its own return boundary. Session-owned display/interaction/passenger trees support load→summon→tick→remove in a reduced fixture pinned to Animated Java 1.10.2, including the exporter's tween-guard command form. Its exact pinned compiler templates are fingerprinted, and upstream-shaped tests prove entity-backed callback dispatch plus the exporter's signed four-word UUID-to-string path. Local attack/interact packets populate interaction records and execute the matching advancement reward handler as the player. Failed candidate load tags atomically restore command and entity state. The exact Blockbench 5.1.4 and official Animated Java 1.10.2 exporter combination reproducibly generates a pinned, unmodified upstream armor-stand fixture with 13 resource files, 109 data files, and a complete output manifest. The managed pack validates/stages that exact fixture. Minosoft passes model discovery, load/summon/walk/remove, repeated runtime replacement, rejected-load recovery, stable identity, CPU cleanup, and real-OpenGL positive/rejected/recovery loops. On the documented macOS/Apple M4 Max target, checked 550×550 default and static walk-frame-10 crops match with zero tolerance before/after normal reload and both rejected-reload checkpoints. Each rejection retires 16 candidate buffers and eight vertex arrays with zero live delta, preserves the active generation, and leaves mounted functions callable. Stable entity-identity draw ordering prevents independently collected bones from flickering at coplanar joints. Entity removal plus full cloud-grid replacement and forced collection produces no new GPU finalizer warning. This remains an import workflow, not a runtime JAR. | Add broader upstream blueprints where they expand the surface, then prove remote-server behavior, glowing outline pixels, and another driver/platform reference. |

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

Retained skeletal models and `/reload content` implement the first transactional
GPU generation: retirement prevents new instances, active instances keep the
old mesh alive, candidates are uploaded before publication, lookup maps swap as
one commit, and the final old-instance release unloads buffers or drops an
unuploaded candidate. Content models retain their exact ETF catalog/cache lease
through the same boundary. Static texture updates prepare replacement GPU
handles for affected size buckets, preserve all published shader
array/layer coordinates, and switch lookup plus handles with the model commit.
Rollback restores prior names/handles and releases candidate resources; success
releases replaced handles. New texture keys append without consuming new
sampler units. Stable array/layer leases defer reclamation until retained
models release the old content generation. Dead internal holes are reused
before append, dead trailing layers are removed on the next transaction, and
permanent resource-pack coordinates are never reclaimed. Failed bucket upload
deletes its incomplete handle and earlier candidate handles; created, deleted,
live, and active-handle diagnostics are available for a real-driver acceptance
run. Repeated real-OpenGL acceptance remains required.

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
| Reload | Candidate failure preserves the old model; successful apply swaps model, routing, material/cache, and texture generations together; retained readers delay disposal; repeated reload returns real GPU/resource counts to baseline. |
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
