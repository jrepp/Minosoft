<!-- Copyright (C) 2026 Jacob Repp -->

# Iris render-pipeline support boundary

## Decision

Iris remains a source-native compatibility adapter. The pinned
`iris-1.7.2+mc1.20.4` artifact selects Minosoft's canonical render graph,
terrain provider, shader-pipeline registry, and typed targets; upstream Iris
bytecode is not executed and there is no parallel Iris renderer.

One `WorldShaderPipeline` generation is now leased for the complete frame.
Terrain, auxiliary shadow submissions, and final presentation reuse that lease.
A shader reload published from a frame callback can prepare the next graph and
pipeline generation, but it cannot mix the new pipeline into the graph
generation already executing. The old GPU generation retires after the frame
lease closes.

The same frame boundary now captures one immutable `IrisFrameState` before any
world pass executes. Terrain, scene geometry, shadow terrain, and presentation
programs receive values from that snapshot, so a frame cannot mix camera,
matrix, viewport, time, weather, or world values sampled at different points in
the draw. Absolute current and previous camera positions remain double precision
until upload. In addition to the conventional vec3 inputs, each is exposed as
Iris's floor-based `ivec3` integer plus vec3 fractional pair, preserving
sub-block precision at large positive and negative world coordinates. Uploads
are limited to standard Iris uniforms declared by source and retained by the
linked program. OpenGL introspection skips those standard inputs when legal
GLSL optimization removes them; retained host bridge uniforms and samplers
remain strict, and unknown direct inputs still fail preflight.

Dynamic state is separately scoped to each retained draw. A nested immutable
`IrisDrawState` follows the selected-program activation, frame/sampler upload,
and retained host-state synchronization, then publishes only uniforms declared
and retained by that linked program. Entity, projected-shadow, name-tag,
block-entity, and first-person held-item producers supply their identities
without putting mutable entity objects into the shader contract. Terrain and
fullscreen programs explicitly bind the empty state, preventing IDs from a
prior scene draw from leaking across pass boundaries.

Shadow rendering is an explicit auxiliary-view producer contract rather than a
blind replay of the main graph. Terrain selects independent `shadow_solid` and
`shadow_cutout` roots. Entity and block-entity layers publish dedicated shadow
callbacks, and entity callbacks include only physical features that opt into
casting. Projected entity shadows, leashes, labels, hitboxes, overlays, hand,
weather, particles, and sky cannot enter the shadow target. Six shadow scene
variants bridge the current skeletal, player, block/flashing-block, and
block-entity terrain ABIs onto one light-view projection.

Every shader activation made from a semantic world pass now crosses that same
leased `WorldShaderPipeline`. This includes shaders selected inside entity,
block-entity, particle, sky, hand, weather, and world-overlay draw callbacks;
an inner renderer can no longer silently overwrite a pass-level provider
choice. When an override is active, scene geometry without an immutable
contract now fails at the bind boundary with its shader class, pass semantic,
and selected pack instead of silently activating the host program. The
built-in/headless path remains available without a selected plan, and internal
framebuffer composites remain explicitly outside this rule. First-person hand
rendering and framebuffer overlays were moved ahead of the world-composite
pass. GUI/HUD rendering deliberately remains outside the scene-shader semantic.

Every current host scene shader also declares an immutable provider-neutral
program family, its exact physical vertex ABI, and its retained-state ABI.
The state ABI is separate because one physical mesh layout can be drawn by
host shaders with different uniform, texture-array, and uniform-buffer
surfaces. Opaque and translucent
particle graph passes now carry distinct semantics. The registry, rather than
the provider, owns activation of the selected scene program and copies its
negotiated retained host-uniform snapshot on every bind. Main-view bridges
require the complete state ABI; depth-only shadow bridges may declare a
validated subset. Initial synchronization and later setters obey the same
subset, so unchanged values cannot remain uninitialized and excluded values
cannot target optimized-out uniforms. Texture arrays and uniform buffers
resolve through the same selected native program. Internal framebuffer
composites bypass this redirection.

## Pinned upstream grounding

The exact upstream tag is `1.7.2+1.20.4`, commit
`8f668cfe033ef9f128ef0331fff38cc7402032b7`. Its `ProgramId`,
`ProgramArrayId`, `ProgramSet`, and Sodium `IrisTerrainPass` sources establish:

- shadow, shadow-solid, and shadow-cutout programs;
- basic, line, textured, sky, cloud, terrain, block, item, entity, particle,
  hand, weather, water, and Distant Horizons geometry families;
- begin, shadow-composite, prepare, deferred, and composite graphics arrays
  with suffixes through 99, plus final; `setup` is a distinct compute-only
  source array in `ProgramSet`, not a fullscreen graphics family;
- optional compute, geometry, and tessellation stages; and
- distinct Sodium solid, cutout, translucent/water, and shadow terrain passes.

The same pinned bytecode establishes the dynamic state contract rather than
leaving it to shader-pack folklore:

- `FogUniforms` publishes linear `fogStart`/`fogEnd`, vec3 `fogColor`,
  `fogDensity`, integer `fogMode`, and integer `fogShape`;
- `IrisInternalUniforms` publishes the vec4/linear aliases
  `iris_FogColor`, `iris_FogStart`, `iris_FogEnd`, and `iris_FogDensity`; and
- `CameraUniforms` publishes current/previous camera positions plus
  `cameraPositionInt`, `cameraPositionFract`,
  `previousCameraPositionInt`, and `previousCameraPositionFract`. Its bytecode
  computes each integer component with `floor` and each fraction as
  `value - floor(value)`, including for negative coordinates; and
- `CelestialUniforms` derives `shadowAngle`, `sunPosition`, `moonPosition`,
  `shadowLightPosition`, and `upPosition` from the captured model-view matrix.
  It applies negative 90 degrees around Y, the pack's `sunPathRotation` around
  Z, and the current sky angle around X to homogeneous direction vectors at
  radii `+100` and `-100`. Day uses the sun as the shadow light; night uses the
  moon and subtracts half a cycle from `shadowAngle`; and
- `PackDirectives` defaults `sunPathRotation` to zero and obtains it from the
  resolved GLSL `const float sunPathRotation` directive, not from
  `shaders.properties`. Minosoft collects this directive across the active
  resolved program set and rejects inconsistent values before publication; and
- `MatrixUniforms.Previous` initializes both previous matrices to identity,
  publishes that initial state for the first frame, and advances them only
  after the current matrices have been captured;
- `IrisTimeUniforms` captures one local `LocalDateTime` and publishes
  `currentDate=(year, month, day)`, `currentTime=(hour, minute, second)`, and
  `currentYearTime=(elapsed seconds, remaining seconds)` with the actual year
  length;
- `CommonUniforms` sources camera-medium and movement/fire/ground booleans from
  the local player, obtains `eyeBrightness` at the camera entity's eye block in
  block/sky order multiplied by 16, obtains `skyColor` from the camera entity's
  world view, reports HUD visibility and local main-arm handedness, and
  publishes `pi`; and
- `IrisExclusiveUniforms` reports health, hunger, armor, and air as normalized
  survival values with `-1` sentinels outside survival, while their maxima are
  raw `20`, `50`, and the player's maximum health/air. It also reports
  first-person-camera and spectator booleans; and
- `CommonUniforms.renderStage` is exactly
  `GbufferPrograms.getCurrentPhase().ordinal()`. `WorldRenderingPhase` contains
  24 values from `NONE=0` through `HAND_TRANSLUCENT=23`; and
- `entity.properties` and `item.properties` use zero when the mapping file is
  absent and `-1` for an unmapped identifier when it is present. Block-entity
  lookup uses ordered `block.properties` identifier/tag/property rules and
  `-1` when no rule matches. `oldHandLight` defaults true: the main-hand light
  value/color may come from the brighter offhand, while `heldItemId` retains
  main-hand identity. Block items use their default state's luminance and all
  held-light colors default to white; and
- `entityId`, `blockEntityId`, and `currentRenderedItemId` are integer draw
  inputs; `entityColor` is a vec4 overlay input; and `blendFunc` is an `ivec4`
  containing exact OpenGL source/destination RGB/alpha factor enums while
  blending is enabled, otherwise four zeroes.

The planner classifies those pinned families explicitly. Unknown paired roots
are `UNSUPPORTED`; they no longer fall through to a fullscreen composite.
A scene bridge must also name a root that a retained producer can request.
`ProgramId` membership alone is insufficient: pinned Iris declares
`EntitiesGlowing`, but its `ShaderKey` table has no selecting key. Minosoft's
entity-outline mask/composite therefore remains an internal host effect, and a
pack bridge on `gbuffers_entities_glowing` fails preflight rather than compiling
into a dormant program.

## Executable contract

The currently executable adapter subset is deliberately narrow:

| Surface | Accepted now |
| --- | --- |
| Pipeline ownership | One transactional `WorldShaderPipeline` generation per frame |
| Scene bind boundary | Every classified scene-geometry `Shader.use()` inside a semantic world pass delegates to `WorldShaderPipeline.bindScene`; an active override rejects unclassified scene geometry instead of bypassing the pack. Exact built-in activation remains the default when no override plan is selected |
| Internal composites | `FramebufferShader` and its outline/effect subclasses are explicitly internal composites and cannot be mistaken for a gbuffers geometry program merely because their caller is a world pass |
| Frame membership | Sky, terrain, entities, block entities, particles, weather, translucent terrain, hand, and world overlays precede world completion/final composite; HUD remains presentation-side |
| Terrain programs | Paired terrain-family programs selected independently for opaque (`terrain_solid` → `terrain`), cutout (`terrain_cutout` → `terrain`), translucent (`water` → `terrain`), and emissive fallback |
| Shadow | Iris fallback selection for `shadow_solid`/`shadow_cutout` → `shadow`, one graph-owned auxiliary view, an authored/default bounded square RGBA8/DEPTH24 target, immutable pack-distance/plane/FOV projection plus interval-stabilized light view, and explicit terrain/entity/block-entity caster callbacks |
| Presentation | Zero or one pack `final`; a missing final receives a bounded identity `colortex0` presenter |
| Shader buffers | Typed `colortex`, `depthtex`, `shadowcolor`, and `shadowtex` plans with generation-owned OpenGL textures/FBOs, declared output routing, sampler binding, and color ping-pong |
| Main target | Iris-owned logical colortex/depthtex target sized from the host world viewport |
| Terrain bindings | Minosoft view/fog/player-light/texture-array bindings declared in `IrisWorldShaderPipeline`; distinct selected programs are compiled once and shared only by material classes that resolve to the same source. `render.substrate.shaderPrograms.selectedTerrainBinds` records the exact view/material/program selected at submission, independently from the terrain backend's submission ledger |
| Scene contracts | Every current sky/entity/block-feature/particle/weather/hand/overlay shader declares a `SceneProgramFamily`, exact `SceneVertexAbi`, and exact `SceneStateAbi`; Iris 1.7.2 family fallback order is a pure pre-GL contract |
| Specialized geometry | A retained draw may select a more specific program family without changing its vertex or retained-state ABI. Block outlines and hitboxes route through `line` → `basic`; leashes route through `basic`; emissive entity layers route through `spidereyes` → `textured` → `basic`; enchantment glint routes through `armor_glint` → `textured` → `basic`; beacon block entities own a translucent colored-column producer routed through `beaconbeam` → `textured` → `basic`; lightning entities own retained crossed-ribbon geometry routed through `lightning` → `entities` → textured fallbacks; and first-person item geometry retains its material split so opaque/cutout faces use `hand` while translucent ordinary or Gecko faces use `hand_water` → `hand` → textured fallbacks. All match the pinned Iris 1.7.2 keys and fallback order |
| Executable scene bridge | Pack programs explicitly declare bounded vertex/state ABI pairs and the exact retained host-state names they delegate. The reference pack compiles one selected program per current non-terrain state ABI, including conditional variants that share an Iris family root |
| Geometry stages | Optional `.gsh` source is retained in the typed program plan and compiled with its paired vertex/fragment stages. The particle point ABI uses this path for host-equivalent billboard expansion |
| Scene state transfer | Selected-program activation is registry-owned. Main-view bridges receive the full retained uniform snapshot plus texture/uniform-buffer bindings. Shadow bridges declare a validated subset and excluded host setters are suppressed, so depth-only variants cannot receive optimized-out main-view uniforms |
| Frame state | One immutable per-frame snapshot supplies current/inverse/previous model-view and projection matrices; double-precision camera/previous-camera positions and Iris-compatible floor-based integer/fraction pairs; viewport/aspect and near/far planes; frame/world counters and local date/time/year vectors; rain/thunder, eye altitude, sun angle, moon phase, screen brightness, and original linear fog state. It captures the local player's camera medium, movement/fire/ground state, normalized survival health/hunger/armor/air plus maxima, camera mode, spectator state, handedness, HUD visibility, raw and smoothed block/sky eye light in Iris's ×16 coordinate scale, world sky color before camera-fog replacement, and `pi`. Camera-entity blindness, Darkness factor interpolation, and night vision use the exact pinned 1.20.4 duration/tick-delta functions; `darknessLightFactor` separately follows the local-player lightmap pulse. Modern packet-carried Darkness transition state is retained and ticked, and the `-1` infinite-effect sentinel remains active. It also derives the pinned `sunPosition`, `moonPosition`, `shadowAngle`, `shadowLightPosition`, and `upPosition` transform from that snapshot and the immutable plan's resolved `sunPathRotation`. The normalized host fog path reports GL linear mode, spherical shape, and zero exponential density rather than reusing Minosoft's squared internal shader distances. Generation-owned exponential smoothers publish `wetness` and `eyeBrightnessSmooth` from the exact pack-controlled half-life directives and reset transactionally with their shader generation. The local-player block position supplies Iris's fixed 1.20.4 biome key ID, ordered legacy category-tag ordinal, position-aware precipitation ordinal, downfall, and base temperature; missing/modded keys retain Iris's zero fallback rather than host registry indices |
| Identity maps | The planner parses continuation-aware `entity.properties`, `item.properties`, and ordered `block.properties` entries. Entity/item lookup follows Iris's absent-file zero and present-but-unmapped `-1` defaults. An omitted block map installs Iris 1.7.2's exact ordered 100-rule legacy table, while any present block map—including an empty file—replaces it. Block and block-entity rules support direct identifiers, `%` tags, and state-property predicates against current plus legacy session tags |
| Per-draw state | A nested immutable scope reaches every entity feature and shadow caster, the special `minecraft:entity_shadow` and `minecraft:name_tag` features, opaque/translucent block entities, first-person held items, generic world/display items, and each Gecko armor entry. After selected-program activation and retained host synchronization, it uploads `entityId`, `blockEntityId`, `currentRenderedItemId`, the exact living hurt/death or creeper-fuse `entityColor`, and the exact enabled OpenGL `blendFunc` enum tuple. Built-in skeletal/player shaders consume the same overlay state, while empty terrain/fullscreen binds clear stale identities |
| Held items | The frame snapshot retains main/offhand item identifiers, block-item default-state luminance, and white light colors. It uploads both held IDs/light families. Typed `oldHandLight` defaults true and may source only the main-hand light value/color from a brighter offhand without changing item identity |
| Dynamic render stage | `renderStage` is uploaded after every selected terrain/scene/shadow/fullscreen activation from explicit pinned numeric values. Material classes distinguish solid, mipmapped cutout, and translucent terrain; graph semantics distinguish entities, block entities, particles, weather, hand, and overlays; scene families distinguish clouds, custom sky, sun, moon, solid/translucent hand, destroy, outline, and world border. Fullscreen programs receive `NONE`. Sun and moon use distinct provider-neutral families even though both retain the same planet vertex/state ABI and Iris sky-textured fallback root. Diagnostics count only real uploads to linked programs that retain `renderStage`; a declared but optimized-out uniform is not an error or a counted bind |
| Dimension program sets | The planner selects only direct program roots from the active directory. Root programs remain the default; `dimension.properties` supports exact dimension IDs, omitted `minecraft:` namespaces, wildcard fallback, and base fallback; legacy `world0`, `world-1`, and `world1` select Overworld/modded, Nether, and End without mixing base programs. Shared includes and pack options remain pack-wide |
| Conditional programs | Bounded `program.<path>.enabled` boolean expressions are evaluated against validated boolean shader options before stage pairing and compilation. Dimension-qualified paths remain isolated; unknown/non-boolean operands and malformed expressions reject the candidate |
| Pack profiles and option screens | Continuation-aware `profile.*` definitions support sequential inheritance, boolean enable/disable tokens, enumerated `=`/`:` assignments, and `!program.*` suppression. The most-constrained matching profile wins, with authored order breaking ties, and disabled roots are removed before pairing and compilation. `sliders`, `screen`, `screen.columns`, `screen.<id>`, and `screen.<id>.columns` are retained as bounded metadata. The native settings form exposes profile application, stepped slider controls, authored option order, and bounded top-level groups derived from nested screens. Bounded `shaders/lang` parsing merges `en_us` fallback with the requested locale for option comments and option/profile/screen/value labels. Shader forms search across groups, large category sets use a readable selector, and successful apply reloads cross-entry values before continuing. The retained list UI does not claim Iris's exact column-grid or spacer/subscreen navigation |
| Particle ordering | Current `particles.ordering=before|after|mixed`, legacy `particles.before.deferred`, and Iris's deferred-sensitive defaults select explicit graph phases. Mixed mode places only opaque particle producers before deferred and retains translucent producers after it |
| Separate entity draws | `separateEntityDraws` is a typed, default-off property. Opaque and translucent entity/block-entity producers have distinct semantics and graph phases; when enabled, the translucent producers execute after deferred through `gbuffers_entities_translucent` and `gbuffers_block_translucent`, retaining the standard entity/block fallback roots. Composite skeletal features are prepared once and submit only their blended Gecko/emissive layers in the second pass. Gecko block entities similarly retain one animated base draw and expose only blended render layers to the translucent pass |
| Path-tracing geometry suppression | `skipAllRendering` is a typed, default-off property matching Iris 1.7.2's producer boundary: main-view chunk terrain, entities, and block entities are disabled, while sky, particles, weather, hand, overlays, shadows, and fullscreen programs continue. The graph predicate does not mutate producer state or disable the auxiliary shadow callbacks |
| Shadow routing | `shadow.enabled`, `shadowTerrain`, `shadowEntities`, `shadowPlayer`, `shadowBlockEntities`, `shadowLightBlockEntities`, and `shadowTranslucent` are parsed into an immutable routing plan with pinned Iris 1.7.2 defaults. The graph obeys exact terrain/entity/player/block-entity choices; disabling the pass removes its view/target. General entity shadows include players, while the default-false `shadowPlayer` adds a player-only fallback when general entities are disabled. General block-entity shadows include emitters, while default-false `shadowLightBlockEntities` adds a nonzero-block-luminance fallback when general block entities are disabled. Both filters run at physical submission without replay. `shadowTranslucent` defaults true and routes translucent terrain only after opaque/entity shadow depth has been copied from `shadowtex0` to `shadowtex1` |
| Presentation bindings | Standard logical buffer samplers, including `colortex0`; legacy `uTexture` remains a bounded fullscreen alias |
| Reload | Candidate parse, binding/phase/target validation, compile/allocation, publication, and deferred retirement; failure preserves the last-known-good generation. The before-world-render boundary runs before the frame acquires its shader-pipeline lease, detects a changed world identity, and transactionally replaces the program set once; the resulting graph and pipeline generation are then pinned together. A failed identity is logged once rather than retried every frame |
| Terrain interop | The selected built-in or Sodium provider must negotiate all requested vertex semantics and auxiliary-view capability before publication |

The project-owned `iris-reference` fixture satisfies this contract with
terrain, shadow, begin, shadow-composite, prepare, deferred, composite, final,
distinct translucent entity/block-entity programs, and specialized line,
spider-eyes, armor-glint, beacon-beam, lightning, and hand-water programs. Its block programs
cover both block-feature and skeletal-lightmap vertex/state ABIs. It is a
deterministic integration fixture, not evidence that an independent real-world
OptiFine/Iris pack is fully supported. Its final program retains the complete
celestial input family and declares a non-default 18-degree sun path so both
directive propagation and real linked-uniform upload are exercised. It also
retains previous-frame matrices, local calendar vectors, camera medium,
player-state/vital/camera-mode inputs, eye light, sky color, HUD/handedness
state, and `pi` behind unreachable guards so planning, linking, and upload must
all preserve those bindings. Its entity/item/block property maps and retained
dynamic uniforms additionally force planner, linked-program, per-frame held
state, and per-draw identity/blend paths through the same executable contract.
Its default and validation profiles, stepped quality option, and authored main
plus advanced screens additionally exercise profile selection and settings
metadata without changing the rendered reference output.

The main and shadow views now render into generation-owned OpenGL targets
derived from a typed Iris buffer plan rather than the host main-world
framebuffer. The planner resolves `colortex0..15`, `depthtex0..2`,
`shadowtex0..1`, and `shadowcolor0..7`, including formats, sizes, clear
policies/colors, filtering, mipmaps, program sampler reads, ordered
`DRAWBUFFERS`/`RENDERTARGETS` writes, and per-program flips. Color buffers are
double-buffered; depth buffers are single-buffered. The graph places opaque
entity/block-entity and any before-deferred opaque-particle producers ahead of
a dedicated transparency barrier. Depth snapshots populate `depthtex1` at that
barrier before every pre- or post-deferred translucent producer, and
`depthtex2` before hand rendering, when those logical buffers are referenced.

Fullscreen program families are no longer collapsed into one composite.
Explicit graph phases execute `begin*`, `shadowcomp*`, `prepare*`,
`deferred*`, and `composite*` in Iris order, with numeric suffix ordering
through 99. Each pass binds its declared outputs, samples the current readable
buffer side, draws the shared fullscreen mesh, and commits declared flips.
`shadowcomp*` operates on `shadowcolor` targets; the other families operate on
`colortex`. `final` presents the last readable `colortex` side. Packs without
an explicit final receive a bounded identity presentation program after their
composite chain.

The Iris implementation now returns a pack program from `bindScene` only for an
exact program-name, vertex-ABI, state-ABI, and retained-uniform match. The
project reference pack contains 56 such compiled main-view scene variants, covering every
current non-terrain `SceneStateAbi`: basic/light color, generic texture paths,
block/flashing block, billboard text, three skeletal/player states, arm/held
item, particle, sky/cloud/planet/scatter, weather, world border, and damaged
block, plus the compatible line, spider-eyes, armor-glint, beacon-beam,
lightning, and hand-water specializations.
It also contains six compiled shadow variants for skeletal tinted,
skeletal lightmap, player, block, flashing block, and block-entity terrain
state. Terrain remains on its separately negotiated material ABI. Unmarked
programs, unsupported ABI declarations, extra uniforms/samplers, and runtime
host/program uniform mismatches cannot replace geometry.

Terrain selection has its own diagnostic boundary because it does not activate
through an ordinary host `Shader.use()` call. `selectedTerrainBinds` is
incremented only after `bindTerrain` has selected the material-specific source,
bound its Iris-owned output target, activated the compiled pack program,
uploaded the frame snapshot, and bound its declared samplers. The key includes
the render view, terrain material, and selected program root. This distinguishes
“the terrain backend submitted a batch” from “that batch was actually routed
through the expected Iris program.”

## Fail-closed requirements

Preflight now reports all incompatible program families in one error before GPU
publication. It also rejects direct uniforms or samplers for which the selected
wrapper has no upload path. Compute (`.csh`) stages and paired tessellation
control/evaluation (`.tcs`/`.tes`) stages are retained instead of being
ignored. Optional geometry (`.gsh`) stages are retained and compiled, and
known programs require paired vertex/fragment and paired control/evaluation
stages. A tessellation program whose Minosoft host varyings must also cross a
geometry stage still rejects before publication because geometry emission does
not yet identify the source control point for those injected values.

This prevents six false-positive paths:

1. treating `shadowcomp` as a shadow geometry program;
2. treating an unknown `gbuffers_*` program as a fullscreen composite;
3. compiling a program while leaving declared dynamic inputs at implicit zero;
4. executing an old graph with a newly published shader generation mid-frame.
5. claiming a scene program was selected when an inner renderer immediately
   rebound its ABI-specific host shader.
6. routing an entity-outline or other nested fullscreen composite through a
   gbuffers geometry family.
7. accepting a scene bridge solely because its root appears in `ProgramId`
   even though no retained producer can select it.
8. silently activating an unclassified host scene shader while an Iris plan is
   selected.

## Remaining trajectory

Full Iris shader-pack support is not yet established. Continue in this order:

The generation-owned terrain material ABI is now established. Built-in and
Sodium-owned submissions share an 84-byte Minosoft adapter vertex carrying
position, texture coordinate/array layer, packed light/color, signed-short-range
shader block ID plus render type, quad mid-texture coordinate, tangent with
handedness, normal, and center-relative `midBlock` plus luminance. The resolver
snapshots the selected shader fingerprint and ID maps at mesh generation.
Successful Iris generation changes invalidate terrain, and the loading queue
rejects a mesh produced for a retired fingerprint. This is semantic parity with
the pinned Iris XHFP terrain inputs, not a byte-for-byte copy of its compact
40-byte encoding.

The texture-size adapter is also established for transformed packs. Pinned Iris
bytecode proves `atlasSize` follows a bound Minecraft `TextureAtlas`, while
`gtextureSize` follows texture unit zero. Neither draw-wide value is truthful
for Minosoft batches, which select among multiple `sampler2DArray` buckets per
vertex. An adapted stage that declares either uniform must now provide exactly
one `minosoft:texture_array_index` marker. The planner rewrites the use to
`uTextureSizes[int(arrayIndex)]`; raw or ambiguous uses reject before
publication. Static, dynamic, and font arrays publish all 16 physical slot
dimensions, the pipeline snapshots them once per frame and uploads linked
elements once per program per frame, and diagnostics expose both sizes and
upload counts. This closes the internal transformed-pack semantic; it does not
claim that an untransformed third-party pack can consume Minosoft's array
layout.

1. Complete remaining `shaders.properties` directives, arbitrary managed
   resource textures, Distant Horizons inputs, and remaining exclusive
   camera/world values. Add localized option labels/tooltips and exact
   column-grid presentation only if the native settings surface requires that
   UI parity. Unsupported inputs continue to reject before publication rather
   than receiving plausible zeroes.
2. Keep the exact translucent entity/block-entity, emissive-eye, armor-glint,
   beacon-beam, lightning, hand-water, damaged-block, sign-text, and weather
   canaries in the producer-rich live gate. Every named family now has a direct
   Complementary selection and cleanup observation; do not map future producer
   families from reference-pack compilation alone. Add isolated checked pixels
   where route/resource evidence cannot establish blending or depth fidelity.
3. Accept paired fullscreen compute/custom-image/SSBO execution on a real
   OpenGL 4.3+ driver. The typed and capability-gated path exists, but the
   current Apple OpenGL 4.1 backend cannot execute it.
4. Run Complementary's world-space-reflection profile on that non-Apple driver
   and record checked pixels, dispatch/resource diagnostics, invalid/valid
   reload retirement, and steady-state counts. Repeat the combined profile
   matrix before expanding the claim to other independent packs.

Do not mark the Iris functionality rows mapped until that independent pack
passes the combined built-in/Sodium matrix and a producer-rich live scene proves
the exact translucent entity and block-entity binds.

## Verification

Java 17:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.camera.arm.HeldItemMaterialConsumerTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelineRegistryTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisFrameStateTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisRenderStageTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisProgramFallbacksTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlannerTest

./gradlew integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.RendererPipelineTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.draw.EntityDrawerTest \
  --tests de.bixilon.minosoft.gui.rendering.chunk.ChunkRendererTest \
  --tests de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.beacon.BeaconBeamRendererTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.renderer.lightning.LightningBoltRendererTest

./gradlew test integrationTest
```

The focused tests and broad unit/integration gate passed on Java 17. The scene
routing integration additionally proves that a provider need only return its
selection: the registry activates it and copies an unchanged host uniform into
the selected native program before drawing. The broad
gate also covers constructor-bypassing dummy render contexts: they retain exact
built-in shader activation instead of requiring a production pipeline
registry. The supervised `creative-creature-catalog` stack
was then relaunched with the project reference pack and left supervised.
`render.substrate` reported one 24-pass graph, Iris shader generation 1,
fingerprint
`aab9f0dbd04a46aa41c48d1fd36a373208501023e3360eb1584d65712e61883a`,
one Sodium terrain owner, and no active or retired shader leases. The reference
pack contains generic and solid terrain programs, shadow/final programs, and
21 real-GL compiled main-view scene variants plus six shadow scene variants.
The live OpenGL context held 62 programs
with no live shader objects after linking. Main-view
opaque/cutout/translucent and shadow opaque/cutout submissions were all present.
The graph also exposed `particles_opaque` and `particles_translucent` as distinct
semantics. `render.substrate.shaderPrograms` reported selected basic, cloud,
skeletal entity, arm, and sky-color binds with an empty fallback map.
It also reported `frameState` equal to the active render frame. At frame 16,773
the cumulative frame-uniform count was 1,494,372, the selected shadow terrain
roots were exactly `opaque=shadow_solid` and `cutout=shadow_cutout`, and
`shadow/SKELETAL/SKELETAL_TINTED` had 680,090 live binds. All selected scene
binds retained an empty fallback map. This proves the immutable snapshot
reaches active real-GL programs and that physical entity geometry crosses the
shadow ABI rather than a main-camera shader. Block/flashing-block, player,
lightmapped skeletal, and block-entity terrain variants have real-GL compile
and exact planner coverage; the current live scene did not draw those caster
types. The other compiled variants were not drawn by that scene, so their proof is
successful real-GL compilation plus exact planner coverage rather than a
producer-rich draw observation. A post-gate sample at frame 36,812 retained
the same fingerprint, 62 live programs, zero live shader objects, zero
fallback binds, zero active/retired shader leases, both material-specific
shadow roots, and all five main/shadow terrain submissions while frame-state
uploads and the skeletal shadow bind count continued advancing. The client
remained joined and render-ready.

A superseding real-GL sample on supervised trajectory
`creative-creature-catalog`, client generation 28, reported fingerprint
`d6629f6feee9c67b778a7f1ed870163b259cfc9f391a59fc3211c863b204ff2b`
and a 29-pass graph. The graph contained distinct begin, shadow,
shadow-composite, prepare, deferred, and composite boundaries in that order.
At frame 6,887 every fullscreen family had executed 6,888 times. Diagnostics
reported five logical buffers backed by seven physical textures, 20 total live
textures, seven live framebuffers, 67 linked programs, zero live shader
objects, zero fallback scene binds, and zero active or retired shader leases.
The generation-25 capture `/tmp/minosoft-iris-ordered-pipeline.png` retained
terrain, entities, hand, crosshair, and hotbar after all five ping-pong stages.
Subsequent generations changed only diagnostics/mipmap handling and retained
the same pack fingerprint, buffer/program counts, and draw path. The
current-generation log contained no OpenGL, shader-loading, exception, or fatal
entry. The client was left joined and render-ready.

Generation 29 added direct terrain-selection evidence. At frame 19,148 the
Sodium backend's current-frame ledger contained main opaque, cutout, and
translucent submissions plus shadow opaque and cutout submissions.
`selectedTerrainBinds` independently reported advancing counts for
`main/opaque/gbuffers_terrain_solid`, `main/cutout/gbuffers_terrain`,
`main/translucent/gbuffers_terrain`, `shadow/opaque/shadow_solid`, and
`shadow/cutout/shadow_cutout`. Every fullscreen family had advanced 19,149
times, `fallbackSceneBinds` remained empty, and the linked-resource baseline
remained 20 textures, seven framebuffers, 67 programs, and zero live shader
objects. This closes the ambiguity between terrain submission and pack-program
activation; it does not close the independent-pack or producer-rich-scene
gates. The creative creature catalog was also reachable through normal
keyboard input after restoring its Tab routing, but the connected remote test
account correctly disabled one-click spawn without server command permission.
A complete remote producer canary therefore needs a permissioned fixture server
or the existing bounded local-content acceptance path, not a debug-channel
command interpreter.

Generation 34 added the dimension and execution-control boundaries. Focused headless tests
select all three legacy `worldN` directories, exact and wildcard
`dimension.properties` mappings, omitted `minecraft:` namespaces, base
fallback, inactive-stage isolation, dimension-specific fingerprints, bounded
conditional program expressions, dimension-qualified enablement, current and
legacy particle ordering, and deferred-sensitive defaults. The 14-test
render-pipeline integration gate additionally places only opaque particles
before deferred in mixed mode. The supervised Overworld client then activated
generation 34 while retaining parent PID 90436 and server PID 90506. At frame
4,520 `render.substrate` reported
`shaderProgramDirectory=null` for the base-directory reference pack, the same
`d6629f...ff2b` artifact fingerprint, `shaderParticlesOrdering=after` (the
documented default when deferred programs exist), a 29-pass graph, all five
fullscreen families at 4,520 executions, all five expected terrain program
bindings, an empty scene fallback map, 20 live textures, seven live
framebuffers, 67 linked programs, and zero live shader objects. Both live
particle producers were correspondingly after deferred. The client remained
joined and render-ready. This proves base-directory/default-order selection is
observable and did not regress the live graph; custom/legacy dimension
transitions and non-default particle modes are automated contract evidence
until exercised in a real multi-dimension/live-property fixture.

Generation 39 added typed shadow-producer routing. The focused gate now contains
26 Iris tests and the 15-test render-pipeline integration gate includes a pure
caster/submission-plan assertion. At frame 429 the supervised client reported
`shaderShadowRouting={enabled:true,terrain:true,entities:true,blockEntities:true}`;
the terrain backend and selected-program counters independently contained main
opaque/cutout/translucent plus shadow opaque/cutout, all five fullscreen
families had executed 429 times, scene fallbacks remained empty, and the stable
20-texture/seven-framebuffer/67-program/zero-shader-object baseline held. A
superseding steady-state sample at frame 13,981 retained the same routing and
resource counts with every fullscreen family at 13,981 executions.
Parent PID 90436 and server PID 90506 were unchanged; generation-39 client PID
60358 remained joined and render-ready. Non-default whole-producer shadow modes
are automated routing evidence; a live fixture still needs to exercise each
disabled combination.

Generation 42 added the exact `separateEntityDraws` producer split and activated
the reference pack's property in the supervised client without replacing parent
PID 90436 or server PID 90506. Client PID 74998 reported a 30-pass graph with
opaque entities and block entities before deferred, then distinct
`entities_translucent` and `block_entities_translucent` phases before particles,
weather, translucent terrain, hand, overlays, and composite. A transactional
shader reload published shader generation 3 with fingerprint
`6cd9f9fc90005769eddf79e5dab08e419b165a5605dcdf1a0d0d14211a5806ec`,
29 compiled main-view scene programs, six shadow scene programs, 75 linked
programs, zero live shader objects, zero fallback scene binds, and no active or
retired shader leases. All five fullscreen families and all five terrain/shadow
material roots advanced after publication. The `3456x1910` capture at frame
2,093 completed successfully, and the client remained joined and render-ready.
The focused gate now contains 29 Iris tests, 18 renderer-pipeline integration
tests, and ten entity-drawer integration tests. Exact translucent program binds
still need a producer-rich live scene containing a blended entity and Gecko
block entity; compilation, graph routing, ABI selection, and fallback behavior
are automated contract evidence until that scene is available.

Generation 46 then exercised `skipAllRendering` transactionally against the
same live reference pack. With shader generation 3 and
`shaderSkipAllRendering=true`, frames 914 through 945 advanced all five
fullscreen families and both shadow terrain programs by 31 executions. Shadow
skeletal draws, sky, clouds, and hand draws also advanced, while the selected
main terrain/entity/block-entity bind maps contained no entries. Restoring the
checked-in default published shader generation 4 with the original
`6cd9f9fc90005769eddf79e5dab08e419b165a5605dcdf1a0d0d14211a5806ec`
fingerprint, `shaderSkipAllRendering=false`, 20 live textures, seven live
framebuffers, 75 live programs, zero live shader objects, zero fallback scene
binds, and zero active or retired leases. Parent PID 90436 and server PID 90506
remained unchanged; client PID 97470 stayed joined and render-ready.
The subsequent supervised source publication advanced to generation 47, client
PID 99532, and re-established the same 30-pass/default-fingerprint state with
20 textures, seven framebuffers, 75 programs, zero shader objects, and no
active or retired leases; the client remained joined and render-ready.

Generation 50 added exact per-draw specialization for the pinned Iris 1.7.2
line, leash, spider-eyes, and armor-glint families. The focused gate contains
30 Iris tests, 19 renderer-pipeline integration tests, ten entity-drawer tests,
and ten entity render-effect tests. It proves that a family override changes
only program selection, preserves the physical vertex and retained-state ABIs,
and is cleared after one bind. A shader reload published shader generation 3
with fingerprint
`53e6e3884925238c3271d22e7c9c42b35571e7724cc9c718408fba3a92382313`.
At frame 2,996 the 30-pass live graph reported 39 compiled main-view scene
programs, six shadow scene programs, 20 textures, seven framebuffers, 86 linked
programs, zero live shader objects, no fallback scene binds, and no active or
retired shader leases. The block-outline producer had selected
`gbuffers_line/POSITION_COLOR/COLOR` 15,652 times, while terrain, shadow,
entity, hand, cloud, and sky counters continued advancing. This is real producer
evidence for line routing. Spider-eyes and armor-glint have exact fallback,
ABI, real-GL compilation, and retained-draw integration coverage, but the
current scene did not contain a visible emissive or enchanted producer, so live
bind evidence for those two families remains open. Leashes intentionally use
the pinned basic-program route. Parent PID 90436, server PID 90506, and client
PID 19126 remained live; the client was left joined and render-ready.

The next supervised source generation added the three remaining explicit
producer families without treating their geometry as generic entity or block
draws. `BeaconBlockEntity` now owns a translucent, height-bounded beam renderer
with stained-glass color segments, retained local-space prisms, scrolling
texture coordinates, and the exact `BEACON_BEAM/POSITION_TEXTURE/BEACON_BEAM`
contract. `LightningBoltRenderer` owns deterministic retained crossed ribbons
with the exact `LIGHTNING/POSITION_COLOR/LIGHTNING` contract. First-person
Gecko items preserve each baked render layer and select `HAND_WATER` only for
explicit translucent/additive layers; their opaque skeletal base and opaque
layers remain on `HAND`. The focused Java 17 gate passed 30 Iris unit tests,
19 render-pipeline tests, ten entity-drawer tests, two beacon tests, and one
lightning test.

At frame 972 the still-supervised `creative-creature-catalog` client reported
shader generation 2, graph generation 17, and fingerprint
`c222dedbbf5b942a50e6937f2bbd5258aa1a02fd5ec974d852c1bdc9d4ef8a90`.
The 30-pass graph compiled all 56 main scene variants and six shadow variants,
including `gbuffers_beaconbeam`, `gbuffers_lightning`, and
`gbuffers_hand_water`. All five fullscreen families had advanced 972 times;
all five main/shadow terrain selections were active; the resource ledger held
20 textures, seven framebuffers, 105 linked programs, and zero live shader
objects; active and retired shader leases were both zero; and
`fallbackSceneBinds` was empty. This is real-GL compile, target, sampler, ABI,
and steady-state ownership evidence for the new families. The observed scene
selected sky, cloud, and ordinary hand programs but contained no active beacon,
lightning bolt, or non-opaque Gecko held-item layer, so exact live bind
counters for those three producers remain an explicit canary gate rather than
an inferred claim. The client was left joined and render-ready.

A longer soak then caught a pass-wrapper defect that the early empty block-
entity phase could not expose. At frame 4,294 the old generation had accumulated
320 `BLOCK/BLOCK_FEATURE/BLOCK` and 125 `TERRAIN/TERRAIN/TERRAIN` fallback
binds even though its nested block-entity renderers later selected exact block
and skeletal programs. Both keys came from `ChunkRenderer` prebinding its
terrain `ChunkShader` at the collection layer before invoking renderers that
own their own shaders. The opaque and translucent block-entity phases now have
no wrapper shader, matching `EntityDrawer`; each block entity is solely
responsible for its exact scene contract. A focused `ChunkRendererTest`
preserves that invariant. At frame 1,143 the replacement client returned
`fallbackSceneBinds={}` while exact entity, block-outline, sky, cloud, hand,
all five main/shadow terrain paths, and skeletal shadow counters advanced.
The stable ledger still held 20 textures, seven framebuffers, 105 linked
programs, zero shader objects, and no active or retired leases.

The same hot-reload sequence exposed a separate shutdown-thread ownership
failure: the process-wide Fabric scope attempted `glDeleteProgram` after its
OpenGL context was no longer current. Active Iris presentations now remain
owned by `IrisRendererHook.unload`, which retires them through normal
`RenderContext.unload` on the render thread; the process-scope close only seals
the controller against new attachments. A subsequent generation-4 to
generation-5 source replacement logged `FABRIC_PACK_INACTIVE`, activated the
new joined/render-ready client, and emitted no native OpenGL fatal. Raw
lifecycle logs remain out of source control.

The complete Java 17 `test integrationTest` gate also passed after these two
soak fixes.

The subsequent producer audit grounded two apparent gaps against the pinned
jar rather than guessing from enum names. `ProgramArrayId.Setup` feeds only
`ProgramSet.getSetup()` compute sources (`setup*.csh`), so the existing
compute-capability rejection remains the correct macOS/OpenGL-3.3 behavior.
`ProgramId.EntitiesGlowing` falls back to `Entities`, but no pinned
`ShaderKey` selects it; Minosoft now rejects even a syntactically valid scene
bridge on that dormant root. Ordinary held-item construction now routes each
quad by the texture's declared transparency: opaque and cutout buckets retain
`HAND`, while the separately blended translucent bucket selects `HAND_WATER`.
The reference pack already exposes the exact `HELD_ITEM/HELD_ITEM` bridge in
both roots.

Focused Java 17 tests passed for the material splitter, pinned fallback catalog,
reference pack, and unreachable-scene rejection; the complete `test
integrationTest` gate then passed. Source hot reload replaced the client while
keeping the supervised parent/server alive. The replacement client remained
joined and render-ready with the stable
`c222dedbbf5b942a50e6937f2bbd5258aa1a02fd5ec974d852c1bdc9d4ef8a90`
reference fingerprint, a 30-pass graph, 56 main and six shadow scene programs,
20 textures, seven framebuffers, 105 linked programs, zero live shader
objects, an empty fallback map, and zero active or retired shader leases.

The pinned fog/render-stage addition then passed the complete Java 17 `test
integrationTest` gate. Its first real-GL candidate exposed an important
link-time distinction: several fixture programs declared `renderStage` without
using it, so GLSL removed the uniform and the former strict setter stopped the
client. `NativeShader.hasUniform` now gives OpenGL a cached linked-program
introspection boundary. Only declared standard Iris frame/stage inputs use it;
retained host bridge uniforms, samplers, and unknown pack inputs remain
fail-closed. A focused regression covers both an optimized-out fog input and
an optimized-out `renderStage`.

The replacement supervised client activated reference fingerprint
`83ed05e72eeb0a2989ce539e387e3e3aa4d118fbc8aa29f5192c6d8f56ed28a4`.
After the final cached-location correction triggered a base hot reload, client
PID 84961 reached joined/render-ready state while parent PID 82091 and server
PID 82127 stayed fixed. At frame 582, shader generation 2 exposed real retained
`renderStage` uploads for
fullscreen `NONE=0`, sky `SKY=1`, terrain solid `8`, cutout-mipped `9`,
translucent `17`, hand solid `16`, clouds `20`, entity-line `11`, outline-line
`14`, and both material-specific shadow roots. All five main/shadow terrain
bindings were active, the scene fallback map was empty, the ledger remained 20
textures, seven framebuffers, 105 linked programs, zero live shader objects,
and no active or retired shader leases. The post-selection log contained no
shader, missing-uniform, OpenGL, or fatal entry; an existing network
`ByteBufferUnderflowException` warning remains unrelated to rendering. The
client remains running.

The next source generation added Iris's large-coordinate camera precision
contract. `NativeShader` now exposes an exact `ivec3` setter, backed by
`glUniform3i`; no unrelated integer-vector surface was invented. The immutable
frame state retains the absolute camera as `Vec3d` and derives the four pinned
integer/fraction uniforms only at upload. A focused negative-coordinate test
proves `-12.25` becomes `-13 + 0.75`, matching the pinned `CameraUniforms`
bytecode rather than Kotlin/Java truncation toward zero. The `iris-reference`
final program retains all four uniforms through an unreachable guard, and its
stable fingerprint advanced to
`f1500a37c46ba03e2af2ed1f08012eb4bc38ea9cfbb4abac3110372fef0a0dd8`.

The focused Iris test suite and complete Java 17 `integrationTest` gate passed.
Source hot reload kept parent PID 82091 and server PID 82127 alive and
published joined/render-ready client PID 90512. At frame 1,284,
`render.substrate` reported that exact new fingerprint, graph generation 17,
shader generation 2, 192,548 cumulative frame-uniform uploads, all five
main/shadow terrain program selections, representative sky/cloud/entity/hand/
line stage binds, and an empty scene fallback map. The real-GL ledger retained
20 textures, seven framebuffers, 105 linked programs, zero live shader objects,
and no active or retired shader leases. The current-generation log contains no
shader, missing-uniform, OpenGL, or fatal entry; the pre-existing network
underflow and unrelated missing optional texture warnings remain. The client
was left running.

The subsequent shader-activation audit closed the remaining implicit-bypass
case. All current scene producers already declare a `SceneProgramFamily`,
`SceneVertexAbi`, and `SceneStateAbi`, and all world registrations use semantic
layers. `ShaderPipelineRegistry` now rejects a `SCENE_GEOMETRY` bind with no
effective contract whenever a non-built-in plan is selected. A focused
render-pipeline integration test proves the rejection message identifies the
shader, semantic, and pack while `FramebufferShader` still bypasses as an
internal composite. The complete Java 17 `test integrationTest` gate passed
with that invariant enabled. The supervised source reload kept parent PID 82091 and
server PID 82127 alive and published joined/render-ready client PID 94745.
After 1,116 frames, the exact `f1500a...dd8` pack still reported 56 main and
six shadow variants, sun and moon stage binds, all five main/shadow terrain
selections, an empty fallback map, 20 textures, seven framebuffers, 105 linked
programs, zero live shader objects, and no shader leases. No missing-contract,
shader, uniform, OpenGL, or fatal log entry appeared. This is positive live
evidence that the current producer set contains no hidden host-program bypass;
it does not expand Iris semantics to the deliberately presentation-side HUD.

The next generation implemented the pinned celestial contract. The planner
now resolves `const float sunPathRotation` from active GLSL source into the
immutable pipeline plan, defaults it to zero, and rejects conflicting
directives deterministically. `IrisFrameState` applies the exact pinned
Y/Z/X transform order to publish sun, moon, shadow-light, shadow-angle, and up
vectors from one frame snapshot. Focused tests cover day/night selection,
non-zero path rotation, the default, and conflict rejection; the complete Java
17 `test integrationTest` gate passed.

Because source hot reload does not treat the integration fixture as JVM source,
the already-running client initially retained the previous pack revision. A
transactional `mods.iris.reload-shaders` then read the configured project path
and published fingerprint
`54142e994cbe98bccb4158831cca5b530ef89651cf363b0edcb417db6a627a89`.
At frame 2,044, graph generation 18 and shader generation 3 reported
`shaderSunPathRotation=18.0`, 56 main and six shadow scene variants, all five
main/shadow terrain selections, retained sun/moon stage binds, and an empty
fallback map. The real-GL ledger returned to 20 textures, seven framebuffers,
105 linked programs, zero live shader objects, and no active or retired shader
leases. The supervised parent/server remained fixed, and the joined,
render-ready client was left running.

The following frame-state generations added the pinned previous model-view and
projection matrices, the exact local calendar vectors, local player medium and
movement/fire/ground flags, survival vitals, camera mode, spectator state, raw
eye light, sky color, HUD visibility, main-arm handedness, and `pi`.
`NativeShader`/OpenGL gained the required `ivec2` upload boundary rather than
coercing integer calendar or light coordinates through floats. Focused tests
cover temporal upload, leap-aware calendar construction, all player values,
and the exact `LightLevel(block=5, sky=14) -> ivec2(80, 224)` mapping. The
complete Java 17 `test integrationTest` gate passed after the final slice.

A transactional live reload then published exact reference fingerprint
`a4913100ed0214fb2eac77f307d4d492c62b85096fc6d45c807f1e042dbf8d90`.
At frame 2,531, graph generation 18 and shader generation 3 retained 56 main
and six shadow variants, all five fullscreen families, all five main/shadow
terrain routes, and an empty fallback map. Actual selections included
block-entity skeletal lightmap, particle, entity, hand, line, cloud, sky,
planet, terrain, and both shadow skeletal state ABIs. The real-GL baseline
remained 20 textures, seven framebuffers, 105 programs, zero live shader
objects, and no active or retired leases. Parent PID 82091 and server PID 82127
remained fixed; joined/render-ready client PID 27133 was left running.

The final correctness audit removed the former `wetness = rainStrength` alias:
pinned Iris smooths that value with pack-controlled wetness/dryness half-lives.
That intermediate generation therefore failed `wetness` preflight rather than
publishing a false direct alias; the later smoothing generation below
supersedes that temporary gap. The audit also separated `skyColor` from
Minosoft's camera-medium/blindness fog replacement and samples the underlying
world sky calculation. Source hot reload kept parent PID 82091 and server PID
82127 fixed and published joined/render-ready client PID 30059. At frame 1,450
the same exact `a4913100...8d90` fixture retained the
30-pass/56+6-program graph, all five terrain/shadow routes, all fullscreen
families, producer-rich scene traffic, the empty fallback map, 20 textures,
seven framebuffers, 105 programs, zero shader objects, and no leases.

The next frame-state generation closes the pinned visual-effect family.
`StatusEffectInstance` now preserves the vanilla `-1` infinite-duration
sentinel and ticks the packet-carried `FactorCalculationData` state used by
Darkness. The packet decoder no longer discards that serialized transition,
and a missing older packet field retains the integrated Darkness effect's
22-tick default. Shared visual math supplies exact 1.20.4 blindness duration
fade, night-vision tick phase, and Darkness lightmap pulse; this also corrects
the native lightmap's former wall-clock and inverted-amplitude night-vision
approximation. `IrisFrameState` samples camera-entity
`blindness`/`darknessFactor`/`nightVision` and the local-player
`darknessLightFactor` into one immutable snapshot.

Thirty-eight focused tests and the complete Java 17 `test integrationTest`
gate passed. The supervised source watcher kept parent PID 82091 and server PID
82127 fixed and published joined/render-ready client PID 36094. A transactional
shader reload published exact fixture fingerprint
`07e0228e39c85cbb4414bbb5f41efb77d4049adccb81a10367d7ba912ff7ba2c`.
At frame 7,593, graph generation 18 and shader generation 3 retained the
30-pass graph, 56 main and six shadow variants, every fullscreen family, all
five main/shadow terrain routes, producer-rich scene selections, and an empty
fallback map. The real-GL baseline remained 20 textures, seven framebuffers,
105 programs, zero live shader objects, and no active or retired leases. The
client remains running.

The following generation closes the pack-controlled global smoothing family.
Pinned Iris 1.7.2 inspection establishes defaults of 600, 200, and 10 for
`wetnessHalflife`, `drynessHalflife`, and `eyeBrightnessHalflife`
respectively. Iris converts each directive to seconds by multiplying by 0.1,
retains `exp(-ln(2) * deltaSeconds / halfLifeSeconds)` of the previous value,
uses the wetness half-life while rain is rising and the dryness half-life while
it is falling, and applies the eye half-life independently to block and sky
brightness before truncating each result to the `ivec2` ABI. A zero half-life
is an explicit immediate transition. `IrisFrameSmoothing` owns this state per
`IrisWorldShaderPipeline` generation, initializes the first sample directly,
and resets on transactional shader reload; a rejected candidate cannot mutate
the active generation's accumulator. Planner validation rejects non-finite,
negative, or generation-conflicting directives.

Focused planner, frame-state, registry, and exact-transition tests passed,
followed by the complete Java 17 `test integrationTest` gate. The source
watcher retained parent PID 82091 and server PID 82127 and published
joined/render-ready client PID 41652. A transactional reload selected exact
fixture fingerprint
`d5869fc8f0e8c8a579f0cfbccaaccefab7d0422828d0ccca23dbf60dcf1c841a`;
`render.substrate` at frame 4,749 reported graph generation 18, shader
generation 3, and exact smoothing directives 40/12/4. The 30-pass graph,
56 main and six shadow variants, all five fullscreen families, all five
main/shadow terrain routes, producer-rich scene traffic, and empty fallback
map remained intact. The real-GL baseline remained 20 textures, seven
framebuffers, 105 programs, zero live shader objects, and no active or retired
leases. The client remains running.

The next frame-state generation closes Iris 1.7.2's camera-biome family.
Pinned `BiomeUniforms`, `BiomeCategories`, `MixinBiomes`, mapped Minecraft
`BiomeKeys`, and the 1.20.4 tag ABI establish that `biome` is Iris's fixed
vanilla key-initialization index, not a runtime registry ID. The 65 keys run
from `the_void = 0` through `end_barrens = 64`; unknown and modded keys inherit
Iris's zero map default. `biome_category` uses Iris's ordered legacy tag probe
and exact 0–18 enum ordinals, including its intentional plains fallback.
`biome_precipitation` is none/rain/snow = 0/1/2 at the local player's block
position, while `rainfall` and `temperature` expose the biome's downfall and
base temperature. The snapshot merges current and legacy biome tag managers
so Minosoft's multi-version sessions retain their existing tag behavior, but
the shader-facing numeric ABI remains pinned to the selected Iris 1.20.4
adapter.

Forty-three focused pipeline tests and the complete Java 17
`test integrationTest` gate passed. The source watcher retained parent PID
82091 and server PID 82127 and published joined/render-ready client PID 49028.
A transactional reload selected exact fixture fingerprint
`bdfe740ecf5c2ef351e20cb6e4f8e10204cb459843905f11be499943ff141347`.
At frame 402, graph generation 18 and shader generation 3 retained the 30-pass
graph, 56 main and six shadow variants, every fullscreen family, all five
main/shadow terrain routes, producer-rich scene selections, and an empty
fallback map. The fixture's linked final program retains all five biome
uniforms. The real-GL baseline remained 20 textures, seven framebuffers, 105
programs, zero live shader objects, and no active or retired leases. The
client remains running.

The dynamic-state generation then added continuation-aware entity/item/block
ID maps, immutable nested draw scopes, both held-item/light families,
`oldHandLight`, `entityColor`, and exact integer `blendFunc` uploads. Sixty-three
focused pipeline tests and the complete Java 17 `test integrationTest` gate
passed. The first live process was no longer present, so the documented
supervisor relaunched `creative-creature-catalog`. Adding the existing draw
diagnostics to `render.substrate` then exercised a client-generation
replacement. A strict per-call OpenGL diagnostic run rejected that provisional
acceptance: the retained `fog` callback reactivated/referenced the host shader
during selected-program synchronization, so its `uFogStart` location did not
belong to the current Iris program and generated `GL_INVALID_OPERATION`.
`FogManager.uploadTo` now writes the retained snapshot to the native target
supplied by the uniform transaction without rebinding the host program. The
focused pipeline gate passed again after that correction. The integration
regression `retained fog synchronization uploads to the selected native
program` records the selected program's fog writes and proves that the host
program receives none.

The clean supervisor launch retained parent PID 73619 and server PID 73667;
client PID 73802 reached joined/render-ready state and a transactional shader
reload selected exact fixture fingerprint
`b2dd4a08efe187925d4ba6e0bc27c28d48b27eeacc7f09c223b11250ead10ca8`.
At frame 1,917, graph generation 18 and shader generation 3 retained the
30-pass graph, 56 main and six shadow variants, every main/shadow terrain
selection, producer-rich scene traffic, and an empty fallback map. The linked
programs had received 2,137,521 dynamic uniform uploads. Actual state keys
included 48,780 creature draws with `entityId=7` and 29,268 block-entity draws
with `blockEntityId=41`; mapped and deliberately unmapped entity draws remained
distinguishable. OpenGL held 20 textures, seven framebuffers, 105 linked
programs, and zero live shader objects. The current launch produced no shader,
uniform, OpenGL, or fatal rendering entry after the corrected reload. Unrelated
network entity-data decoder warnings continue in the same long-lived client log
and are not render-pipeline acceptance evidence.
`render.substrate` now exposes `drawUniformUploads` and bounded
`drawStateBinds` from the existing immutable pipeline diagnostics. It also
reported the then-current 24-byte terrain layout, which made the missing
per-vertex material boundary explicit rather than claiming full pack support.
A final health sample at frame 17,484 still reported graph generation
18, shader generation 3, all five terrain routes, an empty fallback scene map,
49,958,310 draw-uniform uploads, 20 textures, seven framebuffers, 105 programs,
and zero live shaders. Parent PID 73619, server PID 73667, and client PID 73802
remained supervised and ready for that checkpoint.

The subsequent terrain-material checkpoint inspected the pinned Iris 1.7.2
`XHFPTerrainVertex` contract and implemented the equivalent semantics in
Minosoft's float-oriented mesh ABI. `IrisTerrainMaterialResolver` captures the
shader fingerprint, block ID map, ordered tag matcher, render type, block
center, and luminance. Quad emission derives one mid-UV, normal, and tangent
basis for blocks, fluids, and world text. `ChunkMeshes` records the material
generation; shader attach/reload/dimension replacement invalidates terrain, and
`MeshLoadingQueue` drops and requeues stale-generation results before GPU
publication. The 84-byte layout is intentionally uncompressed because the
existing Minosoft VAO contract consumes typed float/vector fields; this does
not claim Iris's compact 40-byte XHFP representation.

Focused planner/registry tests and the terrain material, baked-face, and chunk
renderer integration tests passed. The complete Java 17 `:test
:integrationTest` gate then passed. A supervised reload on client PID 88440
selected fingerprint
`0b512ef87718b67999dc7edd33cb8976cfc10322708dadfbf27ec5561fa1f984`
and advanced graph generation 18 and shader generation 3. At frame 5,435,
`render.substrate` reported terrain layout
`minosoft:terrain/iris-material`, stride 84, and all nine semantics:
position, texture coordinate, texture layer, packed light/color, block ID,
mid-texture coordinate, tangent, normal, and `midBlock`. Main opaque, cutout,
and translucent plus shadow opaque and cutout routes were all advancing through
the Sodium-owned terrain adapter. The 30-pass graph retained 56 main and six
shadow variants, an empty fallback scene map, 20 textures, seven framebuffers,
105 linked programs, zero live shader objects, and no active generation leases.
No shader, uniform, OpenGL, or fatal render entry followed the current-client
startup or shader reload. The supervisor still reports parent PID 73619, server
PID 73667, and client PID 88440 joined and render-ready. The multi-array texture
atlas semantic is now the earliest open render-pipeline boundary.

The texture-size checkpoint then inspected pinned Iris 1.7.2
`CommonUniforms.addDynamicUniforms`: `atlasSize` queries a tracked
`TextureAtlas`, whereas `gtextureSize` queries the dimensions of the texture
currently bound to unit zero. Minosoft's mixed array/layer batches cannot map
that ABI to one uniform without lying. `IrisShaderPackPlanner` now requires an
adapted stage using either name to identify its actual array-index variable,
removes the draw-wide declaration, and rewrites all uses to the generation-owned
`uTextureSizes[16]` table. A missing or duplicate marker rejects the candidate.
`OpenGlTextureManager` reports the exact dynamic, font, and eight static bucket
dimensions; `IrisTextureArrayState` snapshots them once per frame and uploads
only retained elements once per linked program per frame. `render.substrate`
exposes `textureArraySizes` and `textureArrayUniformUploads`.

Planner tests cover successful `atlasSize`/`gtextureSize` rewriting and the
unmarked rejection; frame-state tests cover exact indexed uploads and
optimized-out elements. The full Java 17 `:test :integrationTest` gate passed.
After source hot reload, client PID 97253 accepted a transactional shader reload
with fingerprint
`9cc3c20643321cd6ae9eab6c7a2d7aeeb4693d86a4adc9344fc352c9922fc1a2`.
At frame 3,111 diagnostics reported live array dimensions for sampler slots
1–10: dynamic 64, font 1024, and static 16/32/64/128/256/512/1024/2048.
The once-per-program cache produced 960 linked array-element uploads across the
first ten post-reload frames rather than repeating the table for every entity
draw. The same sample retained all five terrain/shadow routes, the 84-byte
terrain material ABI, the 30-pass graph, 56 main and six shadow programs, an
empty fallback map, 20 textures, seven framebuffers, 105 programs, zero shader
objects, and no leases. No shader, uniform, OpenGL, or fatal render entry
followed the current-client startup or reload. Parent PID 73619, server PID
73667, and client PID 97253 remain supervised and ready. Raw third-party shader
source still needs the broader vertex/sampler transformation and independent
pack gate; this checkpoint deliberately fails closed rather than assigning a
last-bound or largest-bucket value.

The next entity-state checkpoint pins the 1.20.4 overlay texture and Iris
`EntityPatcher` contract. Hurt or dying living entities now resolve to
`entityColor=(1,0,0,77/255)` from `hurtTime`/`deathTime`; the resolver also
retains the texture's quantized white-overlay curve for future producers.
Entity feature scopes carry that value to selected programs, and the built-in
skeletal, lightmap-skeletal, and player fragment shaders apply the same RGB
mix. The former one-second CPU damage-color interpolation was removed, so a
selected shader no longer receives both a red host tint and an Iris overlay.
The reference entity program consumes `entityColor` visibly rather than
retaining it only behind an unreachable ABI guard.

Generic world/item-display features now publish `currentRenderedItemId` along
with their outer entity ID. Each source-native Gecko armor entry adds a nested
item scope while retaining the entity overlay and identity. Producer
integration tests exercise a real registry item and a damaged pig; pure state
tests pin transparent, hurt, full white-progress, precedence, nesting, and
blend-enum values. The focused planner/draw-state/entity tests and the complete
Java 17 `:test :integrationTest` gate passed. At this checkpoint Minosoft still
had no entity-fire geometry producer or generic vanilla armor/trim producer;
the later flame checkpoint below closes the first gap while the second remains.
The reference-pack fingerprint for this source generation is
`94b90775eb66c0d7503b7b9e7c03e0b4acbbb76974b3411f05ee21b5ab31759b`.

The first supervised entity-state launch exposed an important ordering defect:
a uniform setter could inspect the preceding feature's scene binding before
`Shader.use()` selected its own program, then upload `entityColor` into the
selected shadow program even though that program had optimized the uniform
out. `ShaderUniform.upload` now activates/selects the host shader first and only
then tests the selected bridge's accepted-uniform set. The scene-routing
integration test pins this cross-feature sequence and proves the excluded
uniform is not uploaded. The focused suite and the complete Java 17 `:test
:integrationTest` gate passed after the correction.

A fresh supervised launch with the reference pack selected the exact
fingerprint above and accepted a transactional shader reload at frame 1,332.
At frame 9,573, `render.substrate` retained graph generation 18, shader
generation 3, the 30-pass graph, 56 main and six shadow scene programs, five
logical buffers backed by seven textures, all five main/shadow terrain routes,
the 84-byte terrain material ABI, and an empty fallback scene map. Both
`gbuffers_entities/SKELETAL/SKELETAL_TINTED` and
`shadow/SKELETAL/SKELETAL_TINTED` had advanced through 926,050 selected binds,
demonstrating sustained entity and Gecko armor caster traffic across the
corrected boundary. GPU state reported 20 textures, seven framebuffers, 105
programs, zero live shader objects, and no active generation leases. No
post-start/reload shader, uniform, `GL_INVALID`, or fatal render entry followed
the corrected launch; startup still emits unrelated empty-vertex-buffer
warnings. The supervisor remains ready with parent PID 17480, server PID 17507,
and client PID 17622.

The next producer checkpoint closes entity fire. Pinned Minecraft 1.20.4
`EntityRenderDispatcher.renderFire` bytecode defines the retained layout:
entity width multiplied by 1.4, height normalized by that scale, half-width
starting at 0.5 and shrinking by 0.9 per layer, a 0.45 vertical step, 1.4 quad
height, 0.03 depth step, and base depth
`-0.3 + floor(normalizedHeight) * 0.02`. The two vanilla fire sheets alternate
by layer and horizontal UV orientation flips every two layers. Pinned Iris
1.7.2 `MixinEntityRenderDispatcher` bytecode wraps that draw with the temporary
`minecraft:entity_flame` identity and restores the enclosing entity afterward.

`EntityFlameFeature` now owns that exact fullbright, camera-facing retained
geometry for every entity renderer. It is visible only while the synchronized
entity fire flag is set and participates in both the main entity pass and the
physical shadow caster pass. Its built-in shader and selected-pack bridge share
the immutable `POSITION_TEXTURE/ENTITY_FLAME` contract. The reference pack maps
`minecraft:entity_flame` to entity ID 10. Geometry tests pin layer count,
dimensions, texture alternation, depth, and two-layer UV flipping; the entity
drawer integration test pins the special ID and zero living overlay.

The first live reload of this generation exposed 2,048
`BLOCK/BLOCK_FEATURE/BLOCK` fallbacks. This was not caused by the flame
producer: block-display, falling-block, and primed-TNT features retain a block
vertex ABI but can execute under the entity semantic, which correctly selects
the Iris entity family. The reference entity program now carries both block and
flashing-block bridges. A fresh generation reset the fallback map to empty,
proving the cross-semantic producer instead of suppressing the diagnostic.

The final reference fingerprint is
`92fbf1e70f911f87ba764e074d580941ee42c3966bd5df2870a65225f450e734`.
It compiled 71 main-view and seven shadow scene variants. Because the current
remote command tree was unavailable, the client debug channel gained the
bounded `render.prepare-entity-flame` canary: it toggles one retained visible
non-player fire flag, reports the previous value, and supports exact-ID
restoration without changing server state. `client.entities` exposes the same
fire state. The operation was advertised by `core.capabilities`, selected
visible sheep ID 2710, and reported `previous=false`.

At frame 1,583, `render.substrate` recorded 150
`gbuffers_entities/POSITION_TEXTURE/ENTITY_FLAME` selections, 150
`shadow/POSITION_TEXTURE/ENTITY_FLAME` selections, and 150
`gbuffers_entities/entity=10/blockEntity=0/item=0` draw-state binds. The
fallback map was empty; all five main/shadow terrain routes were advancing;
generation resources had no active or retired leases. OpenGL retained 20
textures, seven framebuffers, 122 programs, and zero shader objects. The
follow-up canary call reported `previous=true`, restored the sheep to
`enabled=false`, and `client.entities` independently confirmed `onFire=false`.
A repeat after the final synchronized-flag setter reload exercised the same
fingerprint on client PID 46739: visible sheep ID 3392 accumulated 2,120 main
flame selections, 2,120 shadow flame selections, and 2,120 entity-ID-10 binds
before explicit restoration. The fallback map remained empty and the same
20/7/122/0 texture/framebuffer/program/shader-object baseline held.

The focused geometry, planner, and entity-drawer tests passed, followed by the
complete Java 17 `:test :integrationTest` gate. The current-generation log has
no shader, uniform, `GL_INVALID`, OpenGL, or fatal render fault after selection;
startup still reports the already tracked missing optional armor trim/ETF
textures and empty-buffer warnings. Recurring network-side invalid-namespace
and oversized-VarLong decode warnings are outside this render checkpoint and
remain visible rather than being classified as shader failures. Source hot
reload preserved parent PID 17480 and server PID 17507, activated
joined/render-ready client PID 46739, and left the supervised stack running.

The next producer checkpoint closes the non-Gecko vanilla humanoid armor gap.
`VanillaArmorFeature` reuses the existing `PLAYER_SKELETAL/PLAYER` ABI rather
than introducing a parallel shader. Its outer and inner meshes use the player
part encoding, copy the already evaluated host pose by transform name, and
select only the head, torso/arms, leggings, or boot parts for the equipped
slot. Model instances are resolved lazily because retained entity renderers can
be constructed before dynamic models are registered and baked. Base armor is a
physical opaque caster; leather overlays, paletted armor trims, and enchanted
glint are separate equal-depth non-caster decoration draws. Every draw nests
the equipped item identity inside the entity scope and restores player shader
and render-system state afterward.

Trim generation follows the vanilla atlas `paletted_permutations` contract:
the source RGB selects the corresponding key-palette entry, source and palette
alpha multiply, and unknown colors become transparent. Sixteen 1.20.4 armor
patterns, normal material palettes, and the matching darker iron/gold/diamond/
netherite palettes are declared for outer and leggings templates. Texture
registration follows each armor item's actual slot, avoiding nonexistent
layer-two declarations such as turtle armor. Focused tests pin material/path
selection, slot masks, palette color and alpha behavior, mismatched-palette
rejection, and name-based pose transfer independent of transform buffer IDs.
The complete Java 17 `:test` and `integrationTest` suites passed in 12 and 17
seconds respectively.

The reference item map assigns iron boots, leggings, chestplate, and helmet IDs
20 through 23. Its resulting fingerprint is
`83337e85d9a5973014e880f9cfdfd96d89c0a0dba0b518a9a09774c51bc5a986`.
The bounded client-only `render.prepare-vanilla-armor` canary equips one retained
humanoid with an enchanted gold-trimmed iron set, reports both prior stacks and
resolved base/trim/glint state, and restores the exact prior equipment on
`enabled=false`. It is advertised by `core.capabilities` and does not mutate
server authority.

On client PID 68387, local player ID 7680 reported four retained armor entries
and four retained decoration entries. At frame 1,461, `render.substrate`
recorded 4,000 `gbuffers_armor_glint/PLAYER_SKELETAL/PLAYER` selections,
48,045 ordinary `gbuffers_entities/PLAYER_SKELETAL/PLAYER` selections, and
14,140 `shadow/PLAYER_SKELETAL/PLAYER` selections. The linked draw-state map
contained all four exact armor item IDs in both the ordinary entity and
armor-glint programs; the fallback scene map remained empty. The restore call
returned every armor slot to its observed null value, two camera cycles
returned the client to first person, and the supervised parent/server/client
remained joined and render-ready.

One explicit shader reload earlier in the same working session coincided with
a `ConcurrentModificationException` while `ChunkRenderer` submitted terrain;
the supervisor replaced that client. Inspection found that
`VisibleMeshes.clear()` was the only list mutator not using the same
`VisibleMeshes.lock` held by terrain submission and the other mutators. It now
holds that lock for the complete clear and invalidation transaction. The
hot-reloaded client PID 73202 then survived ten consecutive explicit
`mods.iris.reload-shaders` calls while remaining joined and render-ready.
Shader generation advanced from 2 through 12 and graph generation reached 27.
At frame 247 all five main/shadow terrain routes had advanced, the fallback map
was empty, and OpenGL retained 20 textures, seven framebuffers, 122 linked
programs, and zero live shader objects. No current-client concurrent-modification,
shader, uniform, `GL_INVALID`, or fatal render entry followed those reloads.
This bounded acceptance addresses the observed unguarded-clear failure path;
it is not an exhaustive proof against every possible concurrency interleaving.
An independent real-world shader pack, the remaining Iris
uniform/property/image catalog, and the outstanding specialized producer
canaries remain required gates.

The next standard-input checkpoint closes Iris 1.7.2's `playerMood` and
`constantMood` boundary without substituting fabricated zeroes. Pinned
`CommonUniforms` bytecode establishes both as per-tick clamped floats, while
the pinned Iris local-player mixin and vanilla 1.20.4
`BiomeAmbientSoundsHandler` establish the underlying accumulator: one random
block probe per client tick, sky-light recovery at
`skyLight / maxLight * 0.001`, dark-space accumulation at
`(1 - blockLight) / tickDelay`, and a vanilla mood reset when the configured
sound fires. Iris's constant accumulator consumes that same probe but clamps
at one rather than resetting.

`BiomeMoodSettings` now decodes the registry codec's `effects.mood_sound`
contract. The world-owned, headless-safe `BiomeMoodState` advances both
accumulators at the session's 20 Hz tick, plays the configured positional
ambient sound at the vanilla threshold and offset when audio exists, resets on
world clear, and publishes immutable values into `IrisFrameState`. The shader
pipeline uploads only linked declarations and rejects out-of-range frame
values. The reference fixture declares and bounds both uniforms, and
`render.substrate.frameInputValues` exposes their current values without
changing the debug operation.

Focused codec, tracker, frame-upload, and planner coverage passed 56 tests.
The complete Java 17 `:test integrationTest` gate then passed in 21 seconds.
The updated reference-pack fingerprint is
`bf8498b1245745666b587bcfd2452336d6c91e22b6442d2b0d764acbeecf1ba7`.
On hot-reloaded client PID 85109, `render.substrate` reported live nonzero
`playerMood` and `constantMood` values of `3.3333333E-4` before an explicit
shader reload and `1.6666666E-4` afterward. Shader generation advanced to 3
and graph generation to 18; all five main/shadow terrain routes advanced, the
fallback scene map remained empty, and the retained OpenGL baseline stayed at
20 textures, seven framebuffers, 122 linked programs, and zero shader objects.
The current-client log contains no concurrent-modification, shader/uniform,
`GL_INVALID`, unsupported-Iris, or fatal render fault. Parent PID 17480,
server PID 17507, and joined/render-ready client PID 85109 remain supervised
and running. Recurring network-side invalid-namespace and oversized-VarLong
warnings remain outside this render checkpoint.

The following identity-map checkpoint closes Iris 1.7.2's absent
`block.properties` behavior. Pinned `IdMap` bytecode shows that Iris installs
`LegacyIdMap` only when the file is absent; a present file, even if empty,
fully replaces the fallback. `IrisLegacyBlockIds` now carries the exact ordered
100 rules for the legacy stone, light source, material block, color, fluid,
leaf, glass, vegetation, fire, and lily-pad groups. The table intentionally
retains the pinned artifact's signed `-123` emerald-block ID rather than
silently correcting it to the historical unsigned value.

Planner tests pin the exact rule count, group ordering, representative color
and wood expansions, signed emerald value, final lily-pad entry, and the
present-empty suppression rule. A real-registry integration test proves stone,
red wool, emerald block, and lily pad reach the terrain material resolver with
IDs 1, 35, -123, and 111. Explicit pack mappings remain authoritative; the
project reference pack therefore keeps its chest rule and unchanged
`bf8498b1245745666b587bcfd2452336d6c91e22b6442d2b0d764acbeecf1ba7`
fingerprint.

The focused planner/terrain gate passed, followed by the complete Java 17
`:test integrationTest` gate in 23 seconds. Hot-reloaded client PID 89463
remained joined and render-ready. An explicit shader reload advanced shader
generation 2 to 3 and graph generation 17 to 18. At frame 436, all five
main/shadow terrain routes were advancing through the 84-byte material layout,
the fallback map was empty, and OpenGL retained 20 textures, seven
framebuffers, 122 linked programs, and zero shader objects. The current-client
log contains no concurrent-modification, shader/uniform, `GL_INVALID`,
unsupported-Iris, or fatal render fault. Parent PID 17480 and server PID 17507
remain unchanged and supervised.

The next entity-overlay checkpoint closes the remaining nonzero vanilla
white-progress producer for Minecraft 1.20.4. A scan of every concrete mapped
living-entity renderer found only `CreeperEntityRenderer` overriding the
animation counter consumed by the overlay texture. Its pinned behavior
interpolates the previous/current client fuse counters, divides by
`fuseTime - 2` (28 ticks by default), returns zero on even ten-step bands, and
otherwise clamps the result to `0.5..1.0`.

`CreeperFuseAnimation` now retains those client fuse counters and advances them
from the synchronized ignited/fuse-speed state. `LivingEntity` exposes the
render-interpolated white-overlay producer; `Creeper` supplies the exact
counter, and `IrisEntityOverlay` combines it with the already pinned
hurt/death precedence and quantized overlay-texel curve. Because every built-in
skeletal/player feature and selected Iris entity bridge consumes the same
immutable draw state, the result is not a shader-only alias.

Four focused counter/cadence/clamp tests and a real-entity drawer integration
test pin the producer through the selected-program boundary. The bounded
client-only `render.prepare-creeper-overlay` canary holds one retained creeper
at fuse tick 20 and temporarily makes its renderer eligible for a supervised
draw while leaving native visibility authoritative outside the canary. It
restores the exact prior fuse and visibility overrides. The
`render.substrate.shaderPrograms.entityColorBinds` ledger records only uploads
to linked programs that actually retain `entityColor`.

On client PID 99123, creeper ID 13605 produced
`whiteOverlayProgress=20/28=0.71428573` and exact quantized
`entityColor=(1,1,1,128/255)`. After 50 frames, both
`gbuffers_entities` and `gbuffers_entities_translucent` recorded
`rgba=255,255,255,128` binds. The restore returned the white progress/color to
zero and removed the visibility override. An explicit shader reload advanced
shader generation 2 to 3 and graph generation 17 to 18 while retaining the
unchanged reference fingerprint, 71 main plus seven shadow programs, all five
terrain routes, an empty fallback map, and the 20 texture/seven
framebuffer/122 program/zero shader-object baseline. The complete Java 17
`:test integrationTest` gate passed in 21 seconds. No current-client
concurrent-modification, shader, uniform, `GL_INVALID`, unsupported-Iris, or
fatal render entry followed startup or reload. Parent PID 17480, server PID
17507, and joined/render-ready client PID 99123 remain supervised and running.

The following pack-settings checkpoint pins Iris 1.7.2's profile and option
screen semantics from the configured artifact. `ProfileSet` inspection proves
that `!program.<root>` suppresses a program, `profile.<name>` inherits
sequentially, `!OPTION` and plain boolean `OPTION` set false/true,
`OPTION=value` and `OPTION:value` assign enumerated values, later tokens
override earlier tokens, and the matching profile with the most constrained
options wins with authored order resolving ties. `ShaderProperties` inspection
pins `sliders`, the main `screen`/`screen.columns`, and named
`screen.<id>`/`screen.<id>.columns` metadata.

`IrisShaderPackPlanner` now parses those bounded definitions before program
generation. The automatically selected effective profile removes its disabled
program roots before stage pairing and publishes its name in the immutable
plan. Profile and subscreen cycles, missing profiles/screens, unknown options,
invalid values, and out-of-range column counts reject the candidate.
`ShaderPackSettings` carries
the same profiles, slider identities, authored entry order, and subscreens to
the source-native Iris settings form. Selecting a profile atomically applies
its option values; slider-authored options use stepped controls and named
subscreens become native settings categories. The host list presentation
records authored column counts but does not claim a pixel-identical Iris grid.

The project reference pack now selects profile `default` and has fingerprint
`1ad7c37b9e7d0058214c076b783de2c54796010b5d593b3372b257c0e4ed6065`.
The focused planner gate passed 34 tests, including inherited program
suppression, most-specific selection, stable tie order, metadata retention, and
fail-closed validation. The complete Java 17 `:test integrationTest` gate
passed in 21 seconds. That gate exposed an asynchronous headless-fixture
warning despite its green result: Objenesis-created test worlds had not
restored the non-null `BiomeMoodState` invariant now consumed by frame capture.
`WorldTestUtil` restores that production invariant, and a subsequent complete
`integrationTest` passed in 13 seconds with no mood-capture exception.

After the final source hot reload, joined/render-ready client PID 17269 received
an explicit `mods.iris.reload-shaders`; shader generation advanced 2 to 3 and
graph generation 17 to 18. Frame 120 reported selected profile `default`, 71 main
plus seven shadow programs, all five terrain routes advancing, an empty scene
fallback map, and 20 textures, seven framebuffers, 122 linked programs, and
zero shader objects. The current-client log after its 09:44:07 start contains
no concurrent-modification, shader/uniform, `GL_INVALID`, unsupported-Iris, or
fatal render fault. Parent PID 17480 and server PID 17507 remain unchanged and
supervised.

## Custom and noise texture checkpoint

Pinned Iris 1.7.2 bytecode establishes a distinct custom-texture boundary.
`texture.<stage>.<sampler>=<path>` binds a local PNG only within `setup`,
`begin`, `shadowcomp`, `prepare`, `gbuffers`, `deferred`, or `composite`;
adjacent `<path>.mcmeta` texture metadata supplies `blur` and `clamp`, both
false by default. `texture.noise` replaces the noise source, while an absent
custom source still provides `noisetex` from deterministic `Random(0)` pixels.
The default resolution is 256 and a consistent `const int
noiseTextureResolution` directive replaces it. The upstream raw
1D/2D/3D/rectangle families and custom-image declarations have separate upload
ABIs and are not aliases of the PNG path.

`IrisShaderPackPlanner` now reads shader-pack files as bytes first. GLSL and
properties remain bounded text inputs, while PNG bytes participate directly in
the generation fingerprint and never enter define/option scanning. The planner
accepts bounded local PNG dimensions and metadata, creates stage-scoped
sampler identities, makes a custom sampler override the same ordinary buffer
alias, and rejects traversal, resource-backed `namespace:path` textures, raw
textures, custom images, unsupported targets, malformed metadata, and
inconsistent noise resolutions before publication.

`IrisOpenGlCustomTextures` owns every supported PNG/noise texture name for one
shader generation, uploads sized formats with explicit filter/wrap/no-mipmap
state, and retires them on candidate failure or close.
`IrisOpenGlRenderTargets.bindSamplers` allocates ordinary buffers and custom
textures in one sequence after the host texture units and checks the combined
count against `GL_MAX_TEXTURE_IMAGE_UNITS`; the two families therefore cannot
collide. Diagnostics count these resources and report bounded provenance as
`customShaderTextures`, while `programSamplers` reports each resolved sampler
identity.

The project reference final program now retains a generated 64×64 `noisetex`
sample with a sub-8-bit dither contribution. Its executable fingerprint is
`503d6c51fa0d4a99538ef15624ad30ad9f205d896c383c128b63b1a424a18b11`.
All 37 focused planner tests passed, including real PNG bytes and metadata,
generated/custom noise, resource-usage override, binary-safe hashing, and the
explicit rejection matrix. The complete Java 17 `:test integrationTest` gate
then passed in 20 seconds.

The first fresh managed launch intentionally had no selected shader pack and
proved that `mods.iris.reload-shaders` fails closed rather than inventing one.
The stack was relaunched through the supported `MINOSOFT_SHADER_PACK` boundary
with the project reference pack. On joined/render-ready client PID 999,
`render.substrate` reported `noisetex=generated@64x64/linear/repeat`,
`final=colortex0=colortex0,noisetex=noise`, eight physical shader textures,
21 total live textures, seven framebuffers, 122 linked programs, and zero live
shader objects. An explicit transactional reload published the same
fingerprint, advanced shader generation 2 to 3 and graph generation 17 to 18,
retired eight old texture names, and returned to the exact 21/7/122/0 live
baseline with zero active or retired leases. All five main/shadow terrain
routes and every fullscreen family continued advancing, and the fallback scene
map remained empty. No shader, uniform, `GL_INVALID`, unsupported-Iris, or
fatal render entry followed the active-pack startup or reload. Supervisor PID
824, server PID 853, and client PID 999 remain running.

This closes local 2D PNG, PNG metadata, custom noise, generated noise, combined
sampler-unit allocation, and generation ownership. Resource-backed PNGs,
raw/custom image textures, 1D/3D/rectangle targets, compute-only `setup`, and an
independent community shader-pack visual matrix remain explicit trajectory
gates.

## Independent official example checkpoint

The independent-pack gate now accepts an explicit local path without adding
third-party content to the repository:

```sh
MINOSOFT_IRIS_TEST_PACK=/path/to/Iris-Example-Shaderpack \
MINOSOFT_IRIS_TEST_OPTIONS='OPTION=value;OTHER=false' \
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
./gradlew :test \
  --tests 'de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlannerTest.external shader pack crosses the complete planner contract when configured'
```

`MINOSOFT_IRIS_TEST_OPTIONS` is optional. The test is skipped when the pack
path is absent, so normal builds stay offline and reproducible. The accepted
input was the official `IrisShaders/Iris-Example-Shaderpack` repository at
commit `915c67d5f16584d1a1ec1f71b10c4f4af22939e1`; it remained in a temporary
out-of-source checkout.

That pack exposed two planner assumptions and one source boundary. Optional
`.csh` and graphics-stage roots are considered only after the selected profile
and `program.*.enabled` conditions are applied, so inactive optional stages do
not block a valid configuration. Active compute stages retain a typed dispatch,
while active tessellation stages require a paired control/evaluation chain and
triangle patch state. Terrain selection now follows `gbuffers_terrain_*`
through `gbuffers_terrain`,
`gbuffers_textured_lit`, `gbuffers_textured`, and `gbuffers_basic`, including
programs classified as Iris `BASIC`.

`IrisLegacyShaderTransformer` then converts the bounded GLSL 1.20 fixed-function
surface used by the example to GLSL 330 core. The current executable adapters
cover terrain packed UV/color/light and array textures, host lightmap/fog,
cloud geometry/state, textured sky geometry/state, `gl_FragData`, and the
legacy final `texture` alias to `colortex0`. Modern/adapted source is unchanged,
and unknown legacy scene ABIs are not assigned speculative bridges.

The real-OpenGL command was:

```sh
MINOSOFT_JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
MINOSOFT_SHADER_PACK=/tmp/minosoft-iris-example.syHujU \
./play.sh dev --modpack fabric-stack \
  --trajectory creative-creature-catalog
```

Joined/render-ready client PID 46684 reported fingerprint
`8df6549db5584d597e0529f657dd0b5b0c108c38be95736c1b3b00beaeaf305a`.
The combined Iris/Sodium graph executed 24 passes. All three visible terrain
materials selected `gbuffers_textured`, clouds selected
`gbuffers_clouds/CLOUD/CLOUD`, and final presentation bound
`colortex0=colortex0`. The pack owned nine logical shader buffers backed by 18
physical shader textures plus generated 256×256 noise. OpenGL retained 31
textures, six framebuffers, 38 linked programs, and zero shader objects; shader
and terrain generations had zero active or retired leases. A 3456×1910 world
capture at frame 743 proved non-empty pack output, though the scene was very
dark and is not accepted as color/lighting parity.

Diagnostics also define the next work without ambiguity. The official example
selected only two compiled scene bridges. Entity skeletal/lightmap/tinted,
arm/hand, line, planet sun/moon, particles, sky-basic, and generic textured-lit
draws remained in `fallbackSceneBinds`. The pack has no shadow or composite
roots. Full support therefore requires exact adapters for those physical
vertex/state ABIs, followed by an independent shadow/composite-rich pack and
reload/unload visual baselines; this checkpoint does not satisfy those gates.

Focused transformer/fallback/planner tests and the external commit gate passed.
The complete Java 17 `:test integrationTest` gate passed in 21 seconds. The
supervised stack remains joined and render-ready on parent PID 46577, server
PID 46598, and client PID 46684 with the independent pack selected.

## Independent example scene-bridge closure

The next pass removed every observed main-view scene fallback from the same
official example without changing the pack. `gbuffers_textured` now exposes
exact bridges for generic textured quads, skeletal tinted and lightmapped
geometry, and first-person arm geometry. `gbuffers_basic` covers retained
color/light and basic sky, while `gbuffers_skytextured` covers planets. When a
legacy pack has no particle root, the planner derives `gbuffers_particles`
from its textured fragment and supplies exact retained-point decoding plus a
point-to-quad geometry stage. Mixed terrain/scene roots are accepted only when
the transformer emitted the explicit terrain-bridge marker.

Scene program resource names include both physical host ABIs, which made link
and upload failures attributable. This exposed and fixed duplicate tint
declarations, stage-mismatched lightmap uniform blocks, optimized-out fog
members, and a generated basic-sky `uSkyColor` type mismatch. The host sky
shader owns RGBA state, so the generated bridge now declares `vec4` and forwards
it unchanged; uploading RGBA to the former `vec3` declaration was the source of
the per-frame OpenGL `1282 INVALID_OPERATION`.

Hot-reloaded client PID 64040 selected the same fingerprint
`8df6549db5584d597e0529f657dd0b5b0c108c38be95736c1b3b00beaeaf305a`.
At frame 155, `render.substrate` reported 11 compiled main-view variants and
live selections for:

- `gbuffers_basic/POSITION_COLOR/COLOR`
- `gbuffers_basic/SKY_POSITION/SKY_COLOR`
- `gbuffers_clouds/CLOUD/CLOUD`
- `gbuffers_particles/PARTICLE_POINT/PARTICLE`
- `gbuffers_textured/ARM_SKELETAL/ARM`
- `gbuffers_textured/POSITION_TEXTURE/GENERIC_TEXTURE`
- `gbuffers_textured/SKELETAL/SKELETAL_LIGHTMAP`
- `gbuffers_textured/SKELETAL/SKELETAL_TINTED`

The fallback map was empty. Opaque, cutout, and translucent terrain all selected
`gbuffers_textured`; OpenGL retained 31 textures, six FBOs, 47 programs, and
zero shader objects with no pipeline leases. No OpenGL, shader, or fatal entry
followed the new generation's 13:14:55 pipeline-selection marker. A 3456×1910
capture at frame 227 was non-empty and no longer contained the repeated error
overlay. It remains intentionally outside a color/lighting-parity claim because
the example output is very dark.

The optional external-pack test and complete unit suite passed on Java 17. The
complete integration suite then passed in 12 seconds. A combined filtered
`:test integrationTest` invocation also ran the unit suite successfully but
reported no matching integration tests because Gradle applies `--tests` to both
tasks; the unfiltered integration run is the accepted result.

This closes the official example's observed main-view scene coverage. It does
not close the overall Iris trajectory: the example contains no shadow or
composite roots. The next direct acceptance target is an out-of-source
independent pack with both families, followed by its reload/unload visual and
resource baseline. Raw/custom images, non-2D targets, resource-backed textures,
and compute-capable stages remain separate fail-closed gates.

## Photon shadow/composite runtime checkpoint

The second independent target is Photon at commit
`15458c0937f8647c37eb6a501bef5eb3bf3da31b`. The checkout remained clean and
out of source. Its default overworld program set contains an active
`deferred4_a.csh` compute stage; the Apple OpenGL backend cannot execute that
profile and continues to reject it before publication. The accepted
compatibility configuration is explicit:

```sh
MINOSOFT_IRIS_TEST_PACK=/tmp/minosoft-photon \
MINOSOFT_IRIS_TEST_OPTIONS='SH_SKYLIGHT=false;ENVIRONMENT_REFLECTIONS=false;CLOUD_SHADOWS=false' \
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
./gradlew test \
  --tests 'de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlannerTest.external shader pack crosses the complete planner contract when configured'
```

Photon exposed three runtime ownership defects that the smaller project and
official-example packs did not exercise.

First, Photon keeps its complete Iris buffer catalog inside one GLSL block
comment. Shader inspection had stripped that catalog and allocated every color
target as RGBA8. The planner now preserves metadata-bearing comments through
option preprocessing, unwraps them only for buffer-directive parsing, and
keeps them commented in executable shader source. This avoids the former
`composite4.fsh` syntax failure while retaining the declared
`R11F_G11F_B10F`, `RGBA16`, `RGBA16F`, `RGB16F`, `RG16F`, resized-buffer,
load/clear, mipmap, and 2048² shadow contracts.

Second, the host framebuffer presenter assumed every fullscreen shader sampled
the old Minosoft framebuffer and emitted meaningful alpha. Photon final writes
RGB and samples its current `colortex0`. Generic source-alpha blending treated
the missing alpha as zero, and the presenter then overwrote texture unit zero
with the old black host framebuffer. `FramebufferShader` now declares whether
presentation blending and the host framebuffer binding apply.
`IrisWorldShaderPipeline` disables both for the pack final while leaving
built-in presentation unchanged.

Third, Photon uses explicit no-op `gbuffers_clouds` and
`gbuffers_skybasic` roots to suppress vanilla geometry. Filtering those roots
made Minosoft draw its host fallbacks over the pack atmosphere.
`IrisLegacyShaderTransformer.suppressedScene` retains them as exact
`CLOUD/CLOUD` and `SKY_POSITION/SKY_COLOR` scene programs whose fragment stage
discards. Already adapted scene programs bypass legacy rewriting, preventing
duplicate bridge declarations in the project reference pack.

The managed launch used:

```sh
MINOSOFT_SHADER_PACK=/tmp/minosoft-photon \
MINOSOFT_SHADER_OPTIONS='SH_SKYLIGHT=false;ENVIRONMENT_REFLECTIONS=false;CLOUD_SHADOWS=false' \
./play.sh dev client --local-world --world-generator flat \
  --modpack fabric-stack \
  --trajectory iris-photon-runtime-sampler16-2026-07-27
```

On a 3456×1910 Apple OpenGL client, `render.substrate` reported the Iris owner,
28 graph passes, 20 logical buffers backed by 42 physical shader textures, 25
compiled scene variants, 25 advancing deferred/composite programs, main and
shadow terrain selections, and the Photon final. Live buffer diagnostics
showed the authored formats and policies rather than RGBA8 defaults.
`gbuffers_clouds/CLOUD/CLOUD`,
`gbuffers_skybasic/SKY_POSITION/SKY_COLOR`,
`gbuffers_skytextured/PLANET/PLANET`, and
`gbuffers_hand/ARM_SKELETAL/ARM` were selected with
`fallbackSceneBinds={}`. The captured frame showed Photon atmosphere and
terrain; the blocky host clouds visible before no-op retention were absent.

This is direct shadow/composite/final and observed-scene routing evidence. It
does not claim Photon's default compute-enabled profile, pixel parity with
Iris on Mojang's renderer, a producer-rich entity/weather/particle scene, or a
reload/unload steady-state GPU baseline. Those remain the next acceptance
gates.

## Complementary Unbound r5.8.1 managed-pack checkpoint

The Fabric stack now pins the official untouched Complementary Unbound r5.8.1
Modrinth archive (`R6NEzAwj`, version `VMHXIk50`) as a shader-pack artifact,
not a resource pack or executable mod. `Play` validates one staged
`shaderpacks/*.pw.toml` entry, requires a ZIP containing `shaders/`, includes
its bytes in the immutable pack fingerprint, and publishes
`MINOSOFT_SHADER_PACK` plus bounded `MINOSOFT_SHADER_OPTIONS`. Explicit parent
environment values retain precedence.

The accepted Apple OpenGL configuration is:

```text
RP_MODE=0;SHADOW_QUALITY=-1
```

`RP_MODE=0` keeps absent PBR companion maps neutral. Shadows are disabled
because Minosoft's retained terrain/scene texture-array table can occupy all 16
fragment samplers exposed by the current Apple driver; adding Complementary's
shadow samplers would exceed that hard limit. This is an explicit managed
profile constraint, not a claim that Complementary shadows are unsupported on
all backends.

The pack exposed three compatibility gaps after Photon. Modern fullscreen
vertex sources still contained fixed-function `ftransform`,
`gl_TextureMatrix`, and `gl_MultiTexCoord1` references inside both main code
and shared helper functions. The fullscreen bridge now converts all of those
to the exact retained position/UV inputs. Complementary custom uniforms use
Iris's numbered four-argument `smooth(id,value,fadeUp,fadeDown)` form and
reference gameplay effects, camera vector components, biome constants, and
the newer neutral `endFlashIntensity`; the generation-owned evaluator now
resolves those inputs from the immutable frame snapshot. Finally, the pack
declares `clouds=off` after property preprocessing. The planner now retains
`gbuffers_clouds` as the existing exact `CLOUD/CLOUD` discard bridge, so the
native cloud producer remains topologically present but cannot render a second
cloud layer.

With the exact managed archive, the focused transformer/planner/frame-state
suite and optional external-pack contract passed on Java 17. The supervised
client reached joined/render-ready state and sustained thousands of frames.
At frame 3,420 after the cloud-directive fix, `render.substrate` reported:

- 27 graph passes and one Iris shader/presentation owner;
- 30 compiled main-view scene programs and eight advancing
  deferred/composite programs;
- 10 logical buffers backed by 21 physical shader textures;
- the authored custom cloud-water and noise textures;
- one selected opaque terrain route and no fallback scene binds;
- selected cloud, sky, planet, and hand routes, with the cloud program retaining
  no pack sampler because it is the exact discard bridge;
- zero active or retired shader-generation leases.

A 3456×1910 final-frame capture proves Complementary atmosphere, terrain,
hand, HUD, and final presentation are visible. The topology and sampler
diagnostics rule out a concurrent host fallback or second presentation pass.
The remaining high-frequency stipple in volumetric clouds and terrain is
therefore not recorded as duplicate rendering; it remains a temporal/history,
sampling, or pack-parity investigation. The current checkpoint also does not
claim Complementary shadow/PBR parity, cross-driver references, compute/custom
image families, or a reload/unload steady-state GPU baseline.

## Complementary shadow and compact-sampler checkpoint

The earlier no-shadow constraint is superseded. OpenGL texture arrays remain
physical host slots, but transformed pack programs no longer reserve or declare
every sparse slot. `IrisTextureArrayLayout` captures the exact static,
static-plus-dynamic, or font subset required by each retained producer ABI.
`IrisLegacyShaderTransformer` rewrites the physical switch cases to dense
program-local indices, and `IrisOpenGlRenderTargets` allocates pack samplers
around only those occupied hardware units. This fixed the real
`gbuffers_water` link failure where 11 sparse host slots plus seven pack
samplers exceeded Apple's 16-fragment-sampler limit.

Two further compatibility fixes were found only by compiling the official
archive with shadows enabled. GLSL 330 `texture(sampler2DShadow, ...)` returns
a scalar, while legacy `shadow2D` returned a vector; the core transformer now
retains component semantics by expanding non-`.x` uses. Complementary's modern
`shadow.vsh` also retains fixed-function `ftransform` and pack-owned shadow
matrices. The transformer now substitutes the exact retained position path
without redeclaring a matrix inside one compiled specialization.

The same `shadow` source now exposes guarded retained scene variants for
skeletal/lightmapped entities, players, block/flashing-block features, and
entity flames. The terrain branch remains selected for terrain submissions;
scene specializations reuse the pack fragment and its radial shadow
distortion. Modern `gbuffers_entities` additionally exposes the exact
`POSITION_TEXTURE/ENTITY_FLAME` main-view ABI. These are negotiated program
specializations, not an additional render traversal, so host fallback draws do
not overlap the pack geometry.

The official archive passed the optional external planner test with:

```text
RP_MODE=0;SHADOW_QUALITY=1
```

The supervised Apple OpenGL client reached joined/render-ready state and
survived an explicit transactional shader reload. Its active plan reported 28
passes, 14 logical buffers, 27 physical shader textures, 34 compiled main-view
scene variants, six shadow scene variants, eight advancing fullscreen
programs, and allocated `shadowcolor0/1` plus `shadowtex0/1`. Main opaque,
shadow opaque, and shadow cutout terrain counters all advanced.

A fixed local-authority Naturalist rattlesnake then selected
`gbuffers_entities/SKELETAL/SKELETAL_TINTED` and
`shadow/SKELETAL/SKELETAL_TINTED`. The bounded entity-fire canary selected
`gbuffers_entities/POSITION_TEXTURE/ENTITY_FLAME` and
`shadow/POSITION_TEXTURE/ENTITY_FLAME` in the same frames. After restoring the
fire flag, `fallbackSceneBinds={}` remained empty. This is direct evidence that
the prior host fallback and pack shadow draw are no longer fighting over the
same entity surface. The managed manifest now selects
`RP_MODE=0;SHADOW_QUALITY=1` by default. PBR companion-map parity, pixel
references, other drivers, and producer-rich block-entity/player shadow scenes
remain explicit follow-up gates.

## Complementary producer-complete ABI checkpoint

The official-pack gate now audits the complete producer contract rather than
only the routes observed in one scene. With `RP_MODE=0;SHADOW_QUALITY=1`, it
requires an executable main-view bridge for all 24 non-terrain
`SceneStateAbi` values. When entity shadows are enabled, it also requires the
six physical caster states used by entity, skeletal, player, block-feature,
flashing-block, and flame producers. The exact Complementary archive passes
that gate; terrain remains covered by its separate typed material contract.

Modern `gbuffers_entities` now retains exact billboard-text and player
specializations. The player bridge preserves skin-part masks, part inflation,
pose transforms, packed texture coordinates, and tint; armor glint continues
to select the dedicated pack family. Modern `gbuffers_block` retains flashing
block features. `gbuffers_textured` retains clip-space generic overlays and
the world-border producer. `gbuffers_skybasic` explicitly discards the host
sun-scatter pass because Complementary owns the atmospheric sun; keeping that
producer as an ABI-matched no-op prevents a second additive sun rendering.

The resulting real-GL generation compiled 47 main-view scene programs and six
shadow variants. It retained 40 textures, seven framebuffers, 99 programs, and
zero shader objects, while all eight fullscreen programs advanced and
`fallbackSceneBinds={}` remained empty. Live canaries selected:

- `gbuffers_entities/PLAYER_SKELETAL/PLAYER` and
  `shadow/PLAYER_SKELETAL/PLAYER` from third-person player rendering;
- `gbuffers_entities/BILLBOARD_TEXT/BILLBOARD_TEXT` beside
  `gbuffers_entities/SKELETAL/SKELETAL_TINTED` and its shadow counterpart for a
  named Naturalist rattlesnake; and
- `gbuffers_textured/POSITION_TEXTURE_2D/GENERIC_TEXTURE_2D` for the
  first-person fire overlay.

The local acceptance fixture now carries bounded named-entity and fire-overlay
functions. `LocalDisplayEntityFactory` maps their `CustomName`,
`CustomNameVisible`, and `Fire` data into the same synchronized entity state
used by production rendering, and its focused integration tests pass. Static
planning plus successful real-GL linking establishes the unavoidably
environmental sun-scatter and world-border specializations; a live
world-border placement and timed sun sample remain useful broader canaries,
not missing ABI routes.

This closes pack-selected coverage for the current scene-state catalog. It
does not establish pixel parity: PBR companion-map delivery, temporal and
volumetric history, flashing-block post-texture equivalence, cross-driver
references, and producer-rich block-entity scenes remain explicit fidelity
gates.

## Complementary Integrated PBR+ and temporal-history checkpoint

The managed default is now:

```text
RP_MODE=1;SHADOW_QUALITY=1
```

Complementary describes mode 1 as Integrated PBR+, its calculated-PBR path
recommended for ordinary resource packs. This is the strongest profile that
does not require a seusPBR or labPBR companion resource pack. The exact
official archive passes the producer-complete external contract in that mode:
all 24 non-terrain `SceneStateAbi` routes and all six physical entity-shadow
caster states have executable specializations. Modern entity and hand bridges
now provide the face-midpoint symbols retained by this profile. Their neutral
values make the calculated-PBR path executable for current host meshes; they
do not claim the exact per-face midpoint data or normal/specular companion-map
delivery required for full material parity.

The fullscreen target runtime no longer resets every double-buffered logical
target to its primary physical texture at frame start. It retains the
current/alternate physical role across frames, which is Minosoft's dynamic
binding equivalent of Iris's final alternate-to-main copy-back. Focused tests
cover multi-frame history and direct/single-buffered writes. In the settled
real-GL capture, the former dense black volumetric-cloud stipple was absent.
Small static outlined sky specks remained; at world time zero they are
consistent with Complementary's procedural `gbuffers_skybasic` stars and are
not evidence of a second host sky traversal. This is a checked improvement,
not cross-renderer pixel parity.

The supervised Apple OpenGL client sustained real frames with 28 graph passes,
47 compiled main-view variants, six shadow variants, 15 logical buffers, 42
live textures, seven framebuffers, 99 programs, zero live shader objects, one
Iris presentation owner, and `fallbackSceneBinds={}`. A named Naturalist
rattlesnake selected billboard text, skeletal main, and skeletal shadow pack
programs. The fire canary selected entity-flame main/shadow and the
first-person generic-texture overlay route. The route was checked, but the
captured frame did not establish visible fire-overlay pixels. Third-person
player main/shadow routes also selected correctly. At that checkpoint the
player body remained invisible with Iris presentation disabled, establishing
that the defect was upstream of Iris routing; the uniform-block checkpoint
below supersedes the unresolved pixel gate.

An explicit shader reload exceeded the debug request's reply deadline but
completed transactionally: graph generation advanced from 18 to 19 and shader
generation from 3 to 4, the full 47+6 program set returned, fallback binds
remained empty, and live resources returned to 42 textures, seven
framebuffers, 99 programs, and zero shader objects. Preparing `fabric-stack`
without process option overrides resolved immutable pack view
`285c1d360d1f74ebc9b2d40fabdedffe86eab7e7822983d0a7d1b5d1d914a4d7`
with Complementary selected. Remaining fidelity gates are true per-face
midpoint and companion normal/specular delivery, checked fire/local-player
pixels after their host producer defects are fixed, richer block-entity
scenes, pixel references, and another driver/profile.

## Skeletal uniform-block and checked-pixel checkpoint

The missing player and creature bodies shared one OpenGL program-input defect,
not an entity-specific model or Iris fallback. Scene-state synchronization
checks `NativeShader.hasUniform` before copying a retained host value into a
selected program. `OpenGlNativeShader.hasUniform` previously queried only
`glGetUniformLocation`; OpenGL uniform blocks are intentionally absent from
that namespace and require `glGetUniformBlockIndex`. Consequently
`uSkeletalBuffer` was skipped for both built-in and pack-selected skeletal
programs. They kept the default binding point zero and interpreted unrelated
uniform-buffer bytes as bone matrices. Direct-matrix producers such as the
first-person arm remained visible, which is why the failure appeared confined
to retained bodies.

OpenGL program-input discovery now maintains separate ordinary-uniform and
uniform-block caches, checks both namespaces, and binds uniform blocks through
the block-index cache. The shared contract explicitly defines `hasUniform` as
covering both input classes. Two adjacent buffer defects were also closed:
`glBindBufferRange` now receives bytes rather than float elements, and the
partial skeletal upload ends at the final packed float rather than copying one
extra element.

The rebuilt supervised Apple OpenGL client then produced checked 3456×1910
pixels through official Complementary Unbound r5.8.1 with the managed
`RP_MODE=1;SHADOW_QUALITY=1` profile:

- the third-person local player body was visible, its retained model reported
  504 vertices and six transforms, and the root sample projected inside the
  clip volume (`x=0`, `y=-0.84617805`, `z=2.9301481`, `w=2.95`);
- the main and shadow routes selected
  `gbuffers_entities/PLAYER_SKELETAL/PLAYER` and
  `shadow/PLAYER_SKELETAL/PLAYER`;
- after an ordinary source hot reload, a fixed local-authority named
  Naturalist rattlesnake rendered its 252 selected-texture vertices and
  selected billboard, skeletal main, and skeletal shadow pack programs; and
- both captures retained one Iris presentation owner with 47 main variants,
  six shadow variants, 28 passes, and `fallbackSceneBinds={}`.

The before/after captures distinguish route selection from actual
rasterization: the pre-fix frame contained only the player hitbox, while the
post-fix frames contain the complete player and Gecko skeletal surfaces.
`compileKotlin` and `installDist` completed on Java 17. Focused buffer-bound
tests cover the byte-range conversion and inclusive packed-transform endpoint.
This closes the previously recorded host player/creature pixel gate. It does
not close exact Naturalist articulation seams, companion normal/specular maps,
fire-overlay pixels, cross-driver references, or richer block-entity scenes.
The retained cuboid face-midpoint gap is superseded by the checkpoint below.

## Source-native face-midpoint checkpoint

Complementary Integrated PBR+ derives face-local material coordinates from
Iris's `mc_midTexCoord`: the transformed texture coordinate minus the constant
UV center of the emitted quad. Supplying the current vertex UV as that center
kept programs executable but collapsed both the signed and absolute offsets to
zero, preventing the calculated-PBR path from seeing a real face domain.

The retained mesh layouts now carry one exact UV center with every vertex of a
source quad:

- generic and lightmapped skeletal meshes append a float2 center after their
  existing texture selector;
- player and first-person arm meshes append the same float2 after their packed
  part/transform/normal word; and
- the shared packed block-feature builder appends a quantization-aware float2
  center, covering ordinary and flashing block features plus opaque and
  translucent first-person held-item meshes.

The modern entity, block, and hand bridges consume those physical attributes
and reproduce Complementary's authored `midCoord`,
`sign(texCoord-midCoord)`, and `abs(texCoord-midCoord)` values. At this
checkpoint generic texture, flame, and billboard producers retained neutral
coordinates because their layouts did not represent a cuboid face. The later
producer-derived textured-quad checkpoint establishes the generic/flame
supersession without rewriting this historical acceptance result.

Focused midpoint, transformer, skeletal-buffer, and official external-pack
tests pass on Java 17. The supervised Apple OpenGL client rebuilt and linked
the untouched Complementary Unbound r5.8.1 archive with the managed
`RP_MODE=1;SHADOW_QUALITY=1` profile: 47 main variants, six shadow variants,
28 graph passes, one Iris presentation owner, and
`fallbackSceneBinds={}`. Checked post-layout captures contain the local player
and the 252-vertex Naturalist snake; both main and shadow route counters
advanced. The active client returned to first-person view and remained
joined/render-ready.

This closes exact retained cuboid midpoint delivery for skeletal entities,
players, arms, block features, and held items. It does not claim companion
normal/specular texture delivery, tangent-space parity for every producer,
Naturalist articulation parity, visible fire-overlay pixels, richer
block-entity scenes, or another driver.

## Sampler-neutral LabPBR companion checkpoint

The static OpenGL texture-array boundary now resolves the LabPBR files adjacent
to every ordinary file-backed diffuse texture:

```text
textures/.../name.png
textures/.../name_n.png
textures/.../name_s.png
```

Normal and specular companions are scaled to the diffuse frame dimensions.
Missing or invalid files fail closed to the LabPBR neutral channel values
`(128,128,255,255)` and `(0,0,0,255)`. A focused real-PNG decode fixture proves
that authored RGBA channels survive asset lookup and image decoding. The later
animation checkpoint below supersedes the original exact-size-only boundary.

The three images occupy fixed vertical pages in the same array layer:
diffuse at page zero, normal at page one, and specular at page two. The
physical array height is three times the logical bucket resolution, but the
sampler index and layer index remain unchanged. This avoids multiplying the
static array-layer count and keeps the existing 16-unit sampler budget.
Host-facing UVs cover only the diffuse page, while transformed pack
`normals`/`specular` calls add the fixed page offset. Logical `textureSize`
continues to report the resource-pack texture size rather than the physical
three-page allocation. Dynamic and font arrays have no companion pages and
return neutral material values.

The shader rewrite covers `texture`, `textureLod`, `textureGrad`,
`texelFetch`, and `textureSize` for the modern normal/specular sampler aliases
used by the untouched Complementary archive. Specialization uses syntactic
helper-call sentinels rather than comments because planner inspection
deliberately strips comments before GL compilation. The first real-GL attempt
exposed that distinction by leaving a comment-marker identifier in GLSL; the
helper-call form rebuilt and linked successfully.

The same live pass exposed a separate route gap rather than a material failure.
Lightmapped skeletal block entities were requesting the `gbuffers_block`
family, whose retained adapter previously exposed only the cuboid block ABI.
Modern block programs now compile skeletal tinted and skeletal lightmap
bridges, retaining block-entity IDs, bone transforms, per-face midpoints,
lightmap state, and companion texture access. The supervised Complementary
generation compiled 51 main-view scene routes and six shadow routes.
`gbuffers_block/SKELETAL/SKELETAL_LIGHTMAP` advanced in live frames while
`rejectedSceneBinds={}` and `fallbackSceneBinds={}` remained empty.

`render.substrate` now reports the exact compiled main/shadow route sets and
separates a candidate rejected for missing retained uniforms from a semantic
family with no compiled candidate. This made the block-entity cause observable
without changing the render path. Focused transformer/material tests and the
official external-pack contract pass on Java 17. A 3456×1910 framebuffer
capture reached frame 14,201 through the material-page generation; an
independent development session later issued an explicit supervisor shutdown,
so that lifecycle event is not classified as a renderer failure. The rebuilt
supervisor then exposed one inverse semantic edge:
`ENTITIES_TRANSLUCENT/BLOCK/BLOCK_FEATURE/BLOCK`. Physical block-feature
meshes now retain the block program family even when an entity pass owns the
draw, and translucent entity/block semantics both select
`gbuffers_block_translucent` when the pack separates those draws. The focused
fallback-order test passes. The replacement client reached frame 7,948 with
all eight fullscreen stages advancing and both rejection/fallback maps empty;
parent 57687, server 57720, and client 59078 remained supervised,
joined, and render-ready.

This checkpoint establishes static authored normal/specular discovery,
decoding, storage, transformed sampling, neutral fallback, and real-GL linking.
The next checkpoint supersedes its animation and size restrictions.

## Animated and scaled LabPBR companion checkpoint

Pinned Iris 1.7.2 bytecode establishes that a companion sprite keeps its own
`.png.mcmeta` frame order, per-frame duration, and interpolation setting. When
the diffuse sprite is animated, the companion selects from its own sequence at
the diffuse sprite's total elapsed timeline position; when the diffuse sprite
is static, the companion advances independently. Iris also scales a
companion's complete frame grid to the diffuse frame size before slicing it:
integral scale factors use nearest-neighbor sampling and non-integral factors
use bilinear sampling.

Minosoft now retains the diffuse animation's wrapped total timeline separately
from its current-frame offset. Static texture arrays register animated normal
and specular pages by texture identity, advance them on the asynchronous sprite
clock, and upload their current mip chains on the render thread. Registration
is transactional across resource-pack replacement: candidate animations do
not replace the live set until publication completes, and unload clears the
set. Static diffuse textures still drive independently animated companions.
The companion loader applies the pinned whole-grid scaling rule, then slices
the scaled grid into authored frames and preserves its own timing and
interpolation.

Focused tests cover wrapped diffuse time, synchronized companion frame
selection, independently scaled static companions, and a scaled animated frame
grid. The LabPBR material/transformer/fallback suite and the untouched
Complementary Unbound r5.8.1 external planner contract pass on Java 17, and
integration-test sources compile.

A new managed real-OpenGL session on trajectory
`complementary-unbound-rp1-2026-07-27` selected the exact immutable
Complementary archive with `RP_MODE=1;SHADOW_QUALITY=1`. At frame 1,803 it
reported 51 main scene routes, six shadow routes, all five terrain/shadow
material routes, all eight fullscreen stages advancing once per frame, and
empty rejected/fallback maps. The 84-byte terrain ABI retained all nine
material semantics. GPU state held 42 textures, seven framebuffers, 103
programs, and zero shader objects. A checked 3456×1910 capture at frame 4,299
confirmed the selected pack remained the sole presentation owner. The managed
client remained supervised, joined, render-ready, and above 70 FPS.

The managed pack does not currently contain an authored animated `_n` or `_s`
fixture, so live evidence proves the real-GL upload path remains stable but
does not claim checked animated material pixels. A managed authored-PBR pixel
reference, logical one-page UV parity for pack-side atlas arithmetic,
page-boundary filter/wrap parity, complete tangent-space parity, and another
driver remain open.

## Logical material-page UV checkpoint

The vertical material store is now hidden completely from pack-authored UV
arithmetic. Static host vertices carry physical page-zero coordinates, while
the vertex adapters expose logical one-page coordinates to the pack. Diffuse
sampling maps logical Y back into page zero; normal and specular sampling map
the same logical coordinate into pages one and two. Dynamic and font arrays
remain identity mappings because they have no companion pages.

This split is specialized per physical array slot after the planner knows
which slots are static. It applies to the modern terrain and scene adapters and
to the legacy terrain, textured-scene, textured-sky, planet, and generated
particle paths. Shadow, weather, damaged-block, beacon, hand, armor-glint,
world-border, generic overlay, entity, block-feature, and skeletal routes all
establish the array identity before converting their authored UV and midpoint
values. Generated marker calls must be eliminated during specialization;
focused tests cover both static and dynamic cases and reject any remaining
coordinate, gradient, size, or logical-UV marker.

The fragment bridge preserves each GLSL access form:

- `texture` and `textureLod` map logical coordinates to the selected physical
  page;
- `textureGrad` additionally divides Y derivatives by three;
- diffuse `texelFetch` keeps logical integer coordinates, while companion
  fetches add one or two logical page heights;
- `textureSize` reports one logical page for static arrays and the unchanged
  physical size for dynamic/font arrays;
- static normalized sampling applies `fract` to logical Y before choosing a
  page, so the array's repeat mode wraps within that page instead of crossing
  from diffuse into normal/specular data.

The current static array uses nearest magnification and nearest mip selection,
so page-edge filtering cannot blend adjacent material pages. If the host later
offers linear static filtering, it will require page-edge padding or an
equivalent page-local filtering rule before that mode can be claimed.

A layer-range alternative was tested and rejected against the actual content
set rather than retained as an abstract option. The 16×16 static bucket
contains 1,841 textures. Three layer ranges would require 5,523 array layers,
but the Apple OpenGL driver exposes `GL_MAX_ARRAY_TEXTURE_LAYERS=2048`.
Vertical pages are therefore required on this validated backend.

The focused transformer suite, full Java 17 unit suite, exact untouched
Complementary Unbound r5.8.1 planner contract with
`RP_MODE=1;SHADOW_QUALITY=1`, and integration-test source compilation pass. A
hot-reloaded client generation reached frame 3,490 with 51 main scene routes,
six shadow routes, all eight fullscreen programs and all five terrain/shadow
submissions advancing, empty rejected/fallback maps, the nine-semantic
84-byte terrain ABI, and 42 textures, seven framebuffers, 103 programs, and
zero live shader objects. A fresh 3456×1910 framebuffer capture retained
textured terrain and a single Iris presentation owner.

An authored animated-PBR checked-pixel fixture, complete tangent-space parity,
and another OpenGL driver remain open. Logical one-page UV, explicit
gradient/size/fetch behavior, current nearest-filter page isolation, and
page-local repeat are no longer open boundaries.

## Managed animated LabPBR framebuffer checkpoint

The Fabric stack now mounts a second content fixture,
`iris-labpbr-render`, above its ordinary resource packs. Its resource side has
seven files: pack metadata plus synchronized two-frame `sand.png`,
`sand_n.png`, and `sand_s.png` sheets and their animation metadata. All three
use explicit 16×16 frames, a 20-tick frame time, no interpolation, and the
sequence `[0,1]`. The data side supplies bounded local prepare/remove functions
for a 5×5 sand platform; the current remote-server run used the already-visible
desert sand and did not invoke those local-only functions. The fixture manifest
is
`b50f61efb12cbacd1b731045b8ab6cb16117cca1cf27d6cfcefc5723423939a7`.

`IrisLabPbrRenderFixtureTest` verifies the complete resource/data manifest, PNG
dimensions, both authored RGBA phases, every animation descriptor, and the
bounded scene function. The production texture loader and animator remain
covered by the focused material/timeline tests rather than widening the
internal loader API for the integration source set.

`render.substrate.materialAnimations` makes the live half of the gate
observable. It reports the number of animated static textures and companion
channels, the exact selected companion resources, and monotonic advance/upload
counters. On the rebuilt managed client at frame 1,219 it reported:

```text
textures=1
channels=2
resources=[minecraft:textures/block/sand_n.png,
           minecraft:textures/block/sand_s.png]
advances=2438
uploads=2438
```

The exact two-times-frame counts prove both companion channels advanced and
uploaded on every rendered frame. During the same interval all five main and
shadow terrain routes advanced and both rejected/fallback maps remained empty.

A fixed top-left framebuffer region `[32,560,256,128]` sampled twelve times
through `visual.sample` alternated in the authored material phase pattern.
Low-phase luminance clustered near `0.6220..0.6231`; high-phase luminance
clustered near `0.6369..0.6380`. Complementary's temporal processing changes
the exact region hash every frame, so the bounded luminance clusters—not an
incorrect zero-tolerance hash assertion—are the stable live signal.

The deliberately different diffuse frames identify the phase visibly while
the diagnostics prove that the authored normal and specular pages advance and
upload on that same timeline. This closes the managed animated-PBR
framebuffer/upload fixture. It does not claim that the measured luminance delta
comes only from a normal or specular channel. An isolated companion-only
visual response, complete tangent-space parity, and another driver remain
open.

## Complementary LabPBR companion-output checkpoint

The earlier managed Complementary profile used `RP_MODE=1`. That mode is
Complementary's Integrated PBR+ path: it was valuable executable shader-pack
evidence, but it did not activate authored LabPBR `_n` and `_s` resources. The
Fabric stack now selects `RP_MODE=3;SHADOW_QUALITY=1`, which defines the pack's
`CUSTOM_PBR` and POM path. The exact Complementary Unbound r5.8.1 archive passes
the external planner with that option set.

The first real-GL attempt correctly failed transactionally rather than
publishing an incomplete generation. `gbuffers_block` required the calculated
PBR varyings `tangent`, `binormal`, `viewVector`, and `vTexCoordAM`, which the
mode-1 path had not retained. The modern entity, block, and hand bridges now
publish those values. Retained cuboid producers continue to use their actual
per-face UV midpoint; producers without a tangent attribute receive a stable
orthonormal basis derived from the retained normal. This is sufficient to run
the real pack while exact producer-derived tangent parity remains an explicit
geometry gate.

The managed `iris-labpbr-render` fixture is now a four-phase, constant-diffuse
acceptance resource. Diffuse, normal, and specular sheets are each 16×64 with
four 16×16 frames, a 20-tick frame time, no interpolation, and frame order
`[0,1,2,3]`. Every diffuse frame is `(216,196,138,255)`. The companion phases
are:

```text
0 neutral:                 normal=(128,128,255,255), specular=(0,0,0,255)
1 normal only:             normal=(255,255,128,255), specular=(0,0,0,255)
2 specular/emissive only:  normal=(128,128,255,255), specular=(255,0,0,128)
3 combined:                normal=(255,255,128,255), specular=(255,0,0,128)
```

`IrisLabPbrRenderFixtureTest` verifies all four RGBA phases, animation
descriptors, data functions, and the complete fixture manifest
`b45f544728e23287230c7f31895af0b7cd352f0caa3dcdb4f7a8442407a659fb`.
The animation boundary now records the last frame index actually uploaded by
each companion channel. Both `render.substrate.materialAnimations` and
`visual.sample` expose bounded immutable state records; `visual.sample`
captures those records on the render queue beside the framebuffer sample, so
phase and pixel are one observation without exposing texture bytes or mutable
animation objects.

A fixed `[800,500,256,128]` framebuffer region sampled 24 times on the
supervised Apple OpenGL client produced:

```text
phase 0 neutral:                 mean=0.09802164  range=0.09793417..0.09823847
phase 1 normal only:             mean=0.09882709  range=0.09876168..0.09887583
phase 2 specular/emissive only:  mean=0.10297694  range=0.10292873..0.10302385
phase 3 combined:                mean=0.10353220  range=0.10343269..0.10374233
```

The four ranges do not overlap even though diffuse pixels never change. This
is direct checked-pixel evidence that authored normal and specular/emissive
companions reach Complementary's true LabPBR output. The same generation
retained 28 passes, 51 compiled main-view routes, six shadow routes, all five
terrain routes, and empty candidate-rejection and host-fallback maps.

One live reload also exposed a render/session race unrelated to shader
translation: lightning frame-state capture iterated the world entity set while
the network thread changed it. `IrisFrameStateClock.capture` now takes
`EntityManager.lock` for that bounded search, and the focused regression passes.
This prevents that `ConcurrentModificationException` from being confused with
an intermittent shader failure when another supervised session changes state.

This checkpoint closes isolated authored `_n` and `_s` output on the validated
profile and driver. It does not close exact cuboid tangent parity, page-edge
linear filtering, another driver, or the broader unsupported Iris catalog.

The rectangle-like apparent duplicate rendering was then isolated without
guessing at render-target ownership. `visual.prepare-reference` gained
independent, non-mutating entity and particle suppression. A four-way checked
capture showed:

```text
entities hidden, particles hidden:   rectangles absent
entities shown,  particles hidden:   rectangles absent
entities hidden, particles shown:    rectangles reproduced
entities shown,  particles shown:    rectangles reproduced
```

The water was only reflecting the bad particle quads. The point-to-quad bridge
contained two independent defects. First, `ParticleRenderer` extracted camera
right/up from `viewProjectionMatrix`; projection FOV and aspect scaling
therefore entered world-space offsets. It now extracts the orthonormal rows
from `viewMatrix`. A focused matrix test proves the chosen axes remain unit and
orthogonal while the projection-combined rows differ. On the next supervised
generation, the screen-sized panels collapsed to bounded billboards, but their
transparent texels still wrote dark rectangles.

Pinned Iris 1.7.2 bytecode supplies the second contract:
`ShaderKey.PARTICLES` and `PARTICLES_TRANS` both use
`AlphaTests.ONE_TENTH_ALPHA` (`GREATER 0.1`). Complementary has no authored
`gbuffers_particles`, so Minosoft synthesizes that route from
`gbuffers_textured`; the synthesized fragment had retained the pack body but
not the particle draw-state alpha test. The first bounded correction evaluated
the selected texture-array texel multiplied by retained vertex alpha before
invoking the authored fragment. A subsequent real-OpenGL capture with entities
and particles both enabled contained no dark particle rectangles.

Pinned `ShaderProperties`, `ProgramDirectives`, `ShaderCreator`, and
`CommonTransformer` bytecode then established the general contract. An
`alphaTest.<program>` override belongs to the authored `ProgramSource`, survives
fallback resolution, replaces the route default when present, and evaluates
the final attachment-zero alpha after the authored fragment body. Minosoft now
records a typed immutable `IrisAlphaTest` on each program, recognizes
`off`/`false`, `GL_ALWAYS`, and every pinned comparison function, ignores
malformed entries as Iris does, and wraps the selected core fragment after
inspection/state specialization. The synthesized particle route inherits a
resolved source override or otherwise records `GREATER 0.1`; it no longer
depends on the temporary sample-before-pack wrapper.

Focused planner and transformer tests cover comparisons, `NEVER`, explicit
`ALWAYS`, the single unlocated output form, attachment-zero selection, malformed
input, and resolved particle fallback inheritance. The exact external
Complementary gate passes with `RP_MODE=3;SHADOW_QUALITY=1`. Supervised client
PID 30530 exposed the effective map through `render.substrate`: the pack's
weather, sky-textured, sky-basic, water, beacon, damaged-block, spider-eyes,
and clouds overrides plus the particle default. At frame 5,096 it retained 51
main routes, six shadow routes, all five terrain submissions, empty rejected
and fallback maps, 42 textures, seven framebuffers, 103 programs, and zero live
shader objects. A 3456×1910 frame-5,524 capture contained neither the prior
oversized panels nor dark particle rectangles. This closes the observed “two
fighting renderings” failure and the general pack-defined alpha-test gap while
leaving the then-open tangent, page-edge filtering, another-driver, and broader
unsupported-Iris gates to the checkpoints and trajectory below.

## Producer-derived cuboid tangent checkpoint

The retained cuboid paths no longer synthesize an arbitrary tangent from only
the face normal. Generic/lightmapped skeletal models, player and first-person
arm meshes, block features, and first-person held-item meshes now derive one
normalized tangent from the emitted face positions and UV gradients. The
fourth component retains mirrored-UV handedness, so the shader reconstructs
the bitangent with the same orientation as the authored texture domain.
Degenerate UVs fall back to a bounded orthonormal basis rather than producing
NaN vertex data.

The tangent is part of each producer's immutable vertex layout. Entity and
skeletal block paths transform its direction through the selected bone matrix;
block-feature and held-item paths transform it through their model matrix.
Player, generic skeletal, block, and hand bridges pass both the transformed
direction and the retained handedness into their LabPBR basis. Generic
textures, flames, billboards, and particles still use the stable normal-derived
basis because those non-cuboid ABIs do not expose a face tangent.

Focused tests prove increasing-U direction and positive handedness as well as
the mirrored-UV direction/negative-handedness case. Transformer tests pin every
new vertex location and transformed PBR call. The untouched Complementary
Unbound r5.8.1 planner gate passes with
`RP_MODE=3;SHADOW_QUALITY=1`, and the managed LabPBR fixture remains valid.

The supervised hot reload published client PID 36725 while parent PID 99510
and server PID 99537 remained unchanged. At frame 7,911 the real Apple OpenGL
client had compiled 51 main and six shadow routes and was actively selecting
skeletal entity, skeletal block-entity, physical block-feature, arm, held-item,
terrain, and shadow programs with empty rejected/fallback maps. The live ledger
held 42 textures, seven framebuffers, 103 linked programs, and zero shader
objects. A checked 3456×1910 capture at frame 13,556 retained a single Iris
presentation owner without the prior particle rectangles or a new shader/link
failure.

This closes producer-derived tangent direction and mirrored handedness for the
retained cuboid content paths. Page-edge linear filtering if static textures
ever opt into it, another driver, non-cuboid producer-specific bases, and the
broader unsupported Iris catalog remain explicit.

## Global and raw custom-texture checkpoint

Pinned Iris 1.7.2 source at commit
`8f668cfe033ef9f128ef0331fff38cc7402032b7` establishes two related
properties. `texture.<stage>.<sampler>` remains stage-scoped, while
`customTexture.<sampler>` is global and uses the same local-PNG or raw
descriptor grammar. Raw targets are `TEXTURE_1D`, `TEXTURE_2D`,
`TEXTURE_3D`, and `TEXTURE_RECTANGLE`; their default filtering is blur plus
clamp, with adjacent `.mcmeta` texture flags allowed to override both. Iris
rewrites a raw alias only when the declared GLSL sampler target matches.

The immutable texture plan now retains that distinction. Global bindings are
eligible in every executable stage, local PNG and raw bytes participate in the
generation fingerprint, and raw descriptors validate target-specific token
counts, dimensions, formats, types, and exact byte lengths. Sampler discovery
recognizes ordinary and integer 1D/2D/3D/rectangle families. The OpenGL
realizer creates the matching target, applies the retained nearest/linear and
repeat/clamp state, disables mip levels, and uploads under explicit
one-byte unpack alignment so tightly packed R/RG/RGB rows cannot inherit
unrelated driver state. Every created name remains generation-owned and is
retired through the existing candidate/reload boundary.

Focused planner coverage proves global PNG reuse across composite and final,
global raw metadata, all four targets, typed-alias matching, exact rejection,
and runtime-contract validation. All 54 focused planner cases passed with one
optional external case skipped; the untouched Complementary Unbound r5.8.1
archive then passed that external case with
`RP_MODE=3;SHADOW_QUALITY=1`. The complete Java 17 unit and integration suites
also passed.

The supervised hot reload published client PID 46048 while parent PID 99510
and server PID 99537 remained unchanged. At frame 9,418,
`render.substrate` reported 51 main and six shadow routes, all five
main/shadow terrain submissions, empty rejected and fallback maps, and
42 textures, seven framebuffers, 103 linked programs, and zero shader
objects. A checked 3456×1910 capture was clean. Complementary's active
configuration uses stage-scoped local PNGs and custom noise; its global block
atlas declaration is gated behind a disabled world-space-reflection option,
so this live observation is a regression gate rather than checked-pixel raw
texture evidence.

This closes local global/stage PNGs and raw 1D/2D/3D/rectangle upload on the
pinned contract. At this checkpoint resource-backed `namespace:path` textures
remained gated because Minosoft's source-native block textures are arrays
rather than a truthful vanilla atlas; the following checkpoint supersedes the
exact Complementary block-atlas part of that gap. Writable `image.*` custom
images remain fail-closed:
the Apple backend cannot satisfy Iris's OpenGL 4.2 custom-image capability.
Those boundaries, compute stages, another driver, and broader pack/profile
coverage remain explicit trajectory work.

The final restored profile used only `RP_MODE=3;SHADOW_QUALITY=1`. Supervised
client generation 11 remained joined and render-ready after more than 11,000
frames. At frame 11,060, fingerprint
`9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`
reported 51 compiled main routes, six shadow routes, 66,360 selected particle
binds, all five main/shadow terrain submissions, empty rejected/fallback maps,
two advancing authored material channels, and the stable
42-texture/7-framebuffer/103-program/0-shader ledger. Entity, particle, cloud,
and world-border reference suppression were all restored to false, and the
supervised client was left running.

## Source-native managed block-atlas checkpoint

Pinned Iris treats a custom-texture value containing `:` as a managed resource
lookup. For `minecraft:textures/atlas/blocks.png`, vanilla returns its runtime
block-atlas texture rather than bytes from a PNG asset. Minosoft cannot bind
that name directly: source-native content is grouped by resolution into
`sampler2DArray` buckets, and every layer contains aligned diffuse, normal, and
specular pages. Building a placeholder 2-D atlas would therefore be
semantically false.

The texture plan now retains a bounded namespaced resource descriptor without
allocating a new OpenGL name. An inactive descriptor is valid generation
metadata; any active resource that lacks a recognized source-native transform
still fails before publication. Physical custom-texture counts exclude these
non-uploaded descriptors, and diagnostics identify them as managed resources
instead of reporting invented dimensions or sampling state.

Complementary's world-space reflection path exposes a direct lossless bridge.
Its shadow program already stores `textureBounds` for each reflected voxel face
in a four-word SSBO. The high 16 bits of `data.y` and the alpha byte of the
packed face color were unused. The source-native transform keeps the pack's
logical UV origin/radius, stores Minosoft's 16-bit texture layer and array
bucket in those spare fields, and restores both in `getFaceData`. Reflected
`textureSize` and `textureLod` calls then select the retained bucket/layer
through the existing compacted `uTextures[]` table. Diffuse sampling continues
to apply Minosoft's material-page coordinate transform, so the bridge does not
sample the normal/specular pages accidentally.

Focused tests pin face packing/unpacking, array/layer sampling, namespace/path
validation, no physical upload for managed resources, active unknown-resource
rejection, and the platform environment. The exact Complementary Unbound
r5.8.1 archive passes the external planner gate with
`RP_MODE=3;SHADOW_QUALITY=1;WORLD_SPACE_REFLECTIONS=1`.

The same audit found an important capability boundary. Pinned Iris publishes
`MC_OS_MAC`; Complementary uses it to disable this path because Apple OpenGL
does not expose the required custom-image feature. Minosoft now publishes that
macro on macOS. Consequently the option-enabled archive plans truthfully on
the supervised machine while the internal reflection path remains disabled.
Running it on a non-Apple backend still requires generation-owned `image.*`,
compute/SSBO resources, barriers/clears, and cross-driver pixel evidence. This
checkpoint closes the exact atlas-to-array metadata and sampling transform, not
those writable-resource capabilities or arbitrary namespaced textures.

The hot-reloaded supervised client published PID 70253 while parent PID 99510
and server PID 99537 remained unchanged. Generation 34 remained joined and
render-ready past frame 3,046 with 28 passes, 51 main scene routes, six shadow routes, all five
terrain/shadow submissions, empty rejected/fallback maps, and the stable
42-texture/seven-framebuffer/103-program/zero-shader ledger. The active runtime
profile remained `RP_MODE=3;SHADOW_QUALITY=1`; the option-enabled
world-space-reflection configuration was a planner/capability gate rather than
a false Apple-OpenGL runtime claim.

## Custom-image, shader-storage, and paired-compute checkpoint

The writable-resource boundary is now an implemented, capability-gated
generation contract. `image.*` properties plan typed 1-D, 2-D, 3-D, absolute,
and framebuffer-relative images with Iris pixel/internal/type formats,
optional sampler aliases, and per-frame clear policy. `bufferObject.0..8`
plans absolute or framebuffer-relative shader-storage buffers. Property option
macros are resolved before dimensions are validated, which is required by
Complementary's `COLORED_LIGHTING`-sized reflection image.

The OpenGL generation validates image units, texture dimensions, SSBO binding
indices, and `GL_MAX_SHADER_STORAGE_BLOCK_SIZE` before publication. Images
require OpenGL 4.2 plus deterministic `glClearTexImage` support from OpenGL 4.4
or `ARB_clear_texture`; storage buffers and compute require OpenGL 4.3.
Candidate allocation is transactional, relative resources resize as one
candidate set, all resources are zeroed deterministically, and resource-tracker
create/delete accounting owns retirement. Active image uniforms receive
`glBindImageTexture`, sampler aliases receive their matching texture target,
SSBOs receive their declared global binding, and explicit memory barriers
surround image/storage consumers.

Framebuffer-relative images and storage buffers use the prepared Iris main
target dimensions, including Minosoft `worldScale`, rather than the OS-window
dimensions. On a frame-size change the main targets resize first, the writable
resource candidate set follows that exact size, and only then can `setup*.csh`
run. This keeps resource dimensions, relative compute dispatch, and
`viewWidth`/`viewHeight` on one world-framebuffer contract.

Active `.csh` files now plan and compile as compute-only OpenGL programs.
Absolute `workGroups` and framebuffer-relative `workGroupsRender` dispatches
use the declared local size, runtime dispatch counts are checked against the
driver's indexed group limits, and each numbered fullscreen compute program
runs immediately before the graphics program with the same Iris family name.
Pinned `IrisRenderingPipeline` bytecode establishes that `setup*.csh` runs
after full target clear at pipeline initialization and again only after a
framebuffer resize. Minosoft retains that distinct `SETUP` phase and dispatches
it on the first captured frame and each size change, after target/custom
resource preparation and before `begin` programs. The same fail-closed
uniform/sampler contract covers both compute families.

Pinned Iris 1.7.2 bytecode establishes two additional compute boundaries.
`shadow*.csh` runs at the shadow-map dimensions after the shadow depth clear
but before the shadow color clear and caster draw. `final*.csh` runs at the
main target dimensions immediately before the final graphics program.
Minosoft now preserves those distinct `SHADOW` and `FINAL` families, retains a
compute-only fullscreen phase in the render graph, and dispatches each family
at that exact boundary.

Writable render-target image uniforms are also generation-owned. The planner
recognizes Iris `colorimg0..15` and `shadowcolorimg0..7`, includes their target
descriptors even when no sampler references the same buffer, and binds the
currently selected physical double-buffer side as level zero, non-layered,
`GL_READ_WRITE`, with the target's actual internal format. Target images
occupy the first image units and custom `image.*` resources follow in the same
checked driver budget. Any active target image requires OpenGL 4.2; compute
and shader-storage resources continue to require OpenGL 4.3. Barriers cover
target-image-only programs as well as custom image/storage consumers.

Pinned Iris `indirect.<program>=<buffer> <offset>` dispatch is implemented as
the third compute-dispatch mode. The immutable compute plan retains a
four-byte-aligned non-negative byte offset into a declared `bufferObject.0..8`.
Fixed buffers prove the complete 12-byte dispatch command is in bounds during
planning; relative buffers repeat that bound check against their realized size
after each transactional resize. Execution binds the generation-owned SSBO as
`GL_DISPATCH_INDIRECT_BUFFER` and calls `glDispatchComputeIndirect`; the
existing all-resource barrier includes command-buffer visibility before and
after the dispatch.

The 60-case focused planner suite and render-graph integration suite passed.
Two explicit archive gates then passed against untouched Complementary Unbound
r5.8.1:

- the Apple environment with
  `RP_MODE=3;SHADOW_QUALITY=1;WORLD_SPACE_REFLECTIONS=1` proves the pack's
  `MC_OS_MAC` guard removes custom images, storage buffers, and compute; and
- the simulated non-Mac environment with
  `RP_MODE=3;SHADOW_QUALITY=1;COLORED_LIGHTING=128;WORLD_SPACE_REFLECTIONS=1`
  proves that `wsr_img`, `wsr_lod_img`, `bufferObject.0`, and
  `shadowcomp.csh` cross planning and the complete host binding contract.

This second gate is headless planner evidence, not a claim that Apple OpenGL
executed compute. A real OpenGL 4.3+ driver, checked world-space-reflection
pixels, and valid/invalid GPU retirement cycles remain required.

### Paired tessellation checkpoint

Paired `.tcs`/`.tes` is now an executable, capability-gated graphics-stage
contract rather than a file-extension toggle. `ShaderProgramSource` retains raw
and inspected control/evaluation sources, includes both stages in option
preprocessing, fingerprinting, active uniform/sampler/resource discovery, and
reports the retained chain through
`render.substrate.shaderPrograms.programStages`. Missing partners and missing
positive patch counts reject during immutable plan construction.

The shared shader carrier accepts vertex, optional paired control/evaluation,
optional geometry, and fragment sources. OpenGL requires 4.0, validates the
requested patch count against `GL_MAX_PATCH_VERTICES`, compiles and attaches the
complete stage order transactionally, and publishes only after link succeeds.
The dummy integration backend accepts the same immutable descriptor without
pretending to execute GL. Selected staged programs apply
`glPatchParameteri(GL_PATCH_VERTICES, 3)`; uploaded triangle buffers switch from
`GL_TRIANGLES` to `GL_PATCHES` only when their primitive width and total
vertex/index count form complete three-control-point patches.

The compatibility transform preserves authored tessellation layouts and stage
interfaces, lifts each stage to at least GLSL 4.00 core, and declares the common
fixed matrix/fog aliases in the stage that consumes them. More importantly, it
detects Minosoft-injected vertex-to-fragment varyings and adds a private
control-stage array plus triangle-domain evaluation interpolation so texture
array/layer, light index, and fog state survive vertex → TCS → TES → fragment.
The bridge does not fabricate authored pack varyings: those continue to link
under their authored names and types.

The supported patch topology is deliberately the one Minosoft can currently
submit exactly: three-control-point triangle meshes with a triangle evaluation
domain. A staged program that needs injected host varyings to cross a geometry
stage rejects before publication; arbitrary patch widths/domains and an exact
geometry-emission bridge remain open. Focused planner, transformer, immutable
plan, dummy compile, and pure draw-state tests pass. The untouched Complementary
Unbound r5.8.1 non-Mac high-feature planner gate also passes, but that archive
contains no `.tcs`/`.tes`; it is regression evidence, not a staged GPU test.

The window boundary now negotiates a context capable of reaching those paths
instead of always requesting the historical minimum. Normal non-Apple startup
tries OpenGL 4.4, then 4.3, then preserves the documented 3.3 compatibility
baseline. Apple startup tries its 4.1 maximum before 3.3. Explicit legacy-quad
mode remains a single OpenGL 3.0 compatibility request. This makes
tessellation, custom-image clear, and compute/SSBO capabilities available when
the driver owns them while retaining the existing capability-gated rejection
on fallback contexts.

`OpenGlTessellationPixelIT` is the opt-in production-path GPU predicate. With
`MINOSOFT_OPENGL_TESSELLATION_TEST=true`, its isolated GLFW worker requests the
same context sequence, compiles Minosoft's real
vertex/control/evaluation/fragment carrier, selects a three-control-point
program, uploads the ordinary position-only triangle buffer, draws it as
`GL_PATCHES`, and reads the center RGBA pixel. The fragment color exists only
as an evaluation-stage output. On the local Apple OpenGL 4.1 driver the checked
pixel was `(32, 128, 223, 255)` within two byte values per RGB channel, all four
stage objects were retired after link, and the tracked program/buffer/VAO
ledger returned to zero. This closes the evaluation-dependent local GPU
predicate. A second driver, arbitrary patch topologies, and an exact
tessellation-plus-geometry host-varying bridge remain open.

The first Apple hot-reload after target-image binding exposed one platform
boundary: querying `GL_MAX_IMAGE_UNITS` on the OpenGL 4.1 context emitted
`GL_INVALID_ENUM` even though every active program had zero image uniforms.
The binder now takes a zero-image fast path and performs no image-capability
query unless a program actually declares a target or custom image. That fix
removed the error. After the tessellation carrier/diagnostics change, managed
base hot reload published client PID 39589 while parent PID 99510 and server PID
99537 remained unchanged. The client stayed joined and render-ready.

At frame 5,759, a production Iris shader reload had advanced shader generation
2 → 3 while preserving fingerprint
`9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`.
Diagnostics retained 28 passes, 51 main and six shadow scene routes, all five
terrain/shadow routes, empty rejected/fallback maps, and all eight fullscreen
graphics stages. `programStages` reported the expected vertex/fragment chains
plus the generated particle geometry chain; Complementary contains no
tessellation roots. The GPU ledger returned to 40 live textures, seven
framebuffers, 103 programs, and zero shader objects. The current process log
contains zero OpenGL errors, fatal lines, or shader compile/link failures.

The one-shot debug caller's five-second deadline expired just before that
reload published; the follow-up generation/resource snapshot is the acceptance
evidence, not the timed-out response. This is the truthful Apple
platform-disabled Complementary runtime regression gate; the isolated
tessellation fixture above supplies the staged pixel claim. The managed client
was left running.

During the subsequent capability-diagnostics checkpoint, the competing shared
session explicitly stopped the original supervisor at
`2026-07-28T13:55:41Z`. The same `fabric-stack` and
`complementary-unbound-rp1-2026-07-27` trajectory was restored without changing
pack content. Replacement client PID 51362 reached sustained rendering and
reported the negotiated driver boundary directly through
`gpuResources.capabilities`: `4.1 Metal - 90.5`, vendor `Apple`, renderer
`Apple M4 Max`, OpenGL 4.0 true, 4.2/4.3/4.4 false, and
`ARB_clear_texture` false. The derived Iris gates therefore report
tessellation true and render-target images, deterministic custom images,
compute, and shader-storage buffers false.

At frame 687 the restored process retained the same fingerprint
`9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`,
graph generation 17, shader generation 2, 28 passes, 51 main plus six shadow
scene routes, empty rejected/fallback maps, and 40/7/103/0 live
texture/framebuffer/program/shader names. This is the durable explanation for
why paired tessellation can execute locally while the implemented
compute/image/SSBO path still requires a non-Apple OpenGL 4.3+ acceptance
machine. The restored managed client was left running.

## Internal-target outline ownership checkpoint

The scene-producer audit found one route that could draw geometry twice even
with an empty Iris fallback map. `EntityOutlineRenderer` first bound its own
single-color mask framebuffer, then called the ordinary skeletal/block/item
host shaders from inside the `WORLD_OVERLAY` semantic. The registry correctly
selected an Iris scene program, but that selection also rebound the Iris main
target. The supposed mask draw therefore became a second world draw and left
the private outline mask empty.

`ShaderPipelineRegistry.withInternalTarget` now establishes the narrow
exception required by renderer-owned auxiliary targets. Inside the scope,
shader activation and pre-bind uniform setters use the exact host shader and
cannot invoke the selected scene program's framebuffer routing. The prior
scene binding is restored after the scope, so the next normal entity draw
selects Iris and synchronizes the current retained host state as before.
`EntityOutlineRenderer` scopes only mask geometry; its normal entity draw
remains Iris-routed, and its edge expansion remains an explicitly internal
framebuffer composite. `GUIShader` also declares its existing
presentation-side exclusion explicitly rather than relying on the default
scene-geometry scope.

The focused integration predicate proves that an internal-target bind does not
call `WorldShaderPipeline.bindScene`, writes its outline uniform to the host
native program, restores Iris routing afterward, and transfers the updated
host snapshot on the next selected bind. The entity flag predicate proves the
client-only glowing canary preserves the fire and invisibility bits.

The durable live canary is:

```sh
./play.sh debug request render.prepare-entity-outline \
  '{"enabled":true}' --role client \
  --trajectory complementary-unbound-rp1-2026-07-27 --json

./play.sh debug visual capture /tmp/minosoft-outline-enabled.png \
  --role client --trajectory complementary-unbound-rp1-2026-07-27 --json

./play.sh debug request render.prepare-entity-outline \
  '{"enabled":false,"entityId":1666}' --role client \
  --trajectory complementary-unbound-rp1-2026-07-27 --json
```

On the rebuilt Complementary client, the canary selected visible villager
1666 at frame 555. `client.entities` reported its glowing flag and the checked
3456×1910 capture showed one outlined entity without a second opaque body.
The active pack retained empty rejected/fallback maps and
40/7/103/0 live texture/framebuffer/program/shader names. Restoration returned
the same entity to `glowing=false`; parent PID 51209, server PID 51235, and
joined/render-ready client PID 57578 remained supervised.

## World-information and player-vector checkpoint

A direct audit of the immutable managed
`iris-1.7.2+mc1.20.4.jar` found a missing frame-input family rather than a
renderer ownership problem. `IrisExclusiveUniforms.WorldInfoUniforms`
registers `bedrockLevel`, `cloudHeight`, `heightLimit`,
`logicalHeightLimit`, `hasCeiling`, `hasSkylight`, and `ambientLight`.
`IrisExclusiveUniforms` separately registers `playerLookVector` and
`playerBodyVector`. An audit of the untouched managed Complementary Unbound
r5.8.1 archive found four `cloudHeight` references and two
`playerLookVector` references, making those active pack inputs rather than
catalog-only compatibility names.

`DimensionProperties` now retains the server-provided `logical_height` and
`has_ceiling` fields and checks that logical height is positive and no greater
than physical height. `IrisFrameStateClock` snapshots the entire dimension
family once per frame: minimum Y, authored cloud plane, physical/logical
height, ceiling/skylight flags, and the raw dimension ambient-light value.
Dimensions without clouds publish Iris's non-finite no-cloud sentinel without
calling their unsupported host cloud-height implementation. The same snapshot
publishes the current camera-entity rotation vector and, for living camera
entities, the render-interpolated rotation vector. These match the pinned
`getRotationVecClient()` and `getRotationVec(tickDelta)` producers. Direct
uniform uploads and custom-uniform expressions consume only that immutable
snapshot.

The project reference pack declares all nine inputs, so runtime-contract
preflight will regress if any input loses its host upload. Focused dimension,
frame-state, and planner tests pass. The official external-pack acceptance
gate also passes directly against the managed Complementary archive. The full
Java 17 `:test :integrationTest` gate passed.

After supervised source hot reload, client PID 65080 remained joined and
render-ready under parent PID 51209 and server PID 51235. At frame 677,
`render.substrate.frameInputValues` reported:

- `bedrockLevel=-64`, `cloudHeight=192`, `heightLimit=384`, and
  `logicalHeightLimit=384`;
- `hasCeiling=0`, `hasSkylight=1`, and `ambientLight=0`;
- finite normalized look/body vectors, including
  `playerLookVector=(-0.9756639,-0.094066866,-0.19806927)`.

The active generation retained the official fingerprint
`9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`,
51 main plus six shadow scene programs, more than 18 million actual linked
frame-uniform uploads, and empty rejected/fallback maps. An explicit Iris
reload advanced generation 2 to 3 and returned to exactly 40 live textures,
seven framebuffers, 103 programs, and zero shader objects. As in the earlier
Apple reload checkpoint, the one-shot caller reached its deadline before the
generation published; the immediately following generation, route, input, and
resource snapshot is the acceptance result. A 3456×1910 capture at frame 1490
showed the Complementary-composited world with no second opaque scene draw.
The managed client was left running.

This closes the complete pinned world-information family and the two player
direction vectors. Remaining catalog work at that checkpoint was
selected-block identity/position, `cloudTime`, output color-space reporting,
Distant Horizons planes/distance when that producer exists, and the non-Apple
OpenGL 4.3+ compute/image/SSBO acceptance lane. The next checkpoint closes the
first three items.

## Selected-block, cloud-time, and output-color checkpoint

Pinned `iris-1.7.2+mc1.20.4.jar` bytecode establishes three additional
exclusive inputs. `cloudTime` is the renderer tick clock plus the current
partial tick, scaled by `0.03`. `currentColorSpace` is the ordinal of Iris's
output color-space enum; Minosoft currently owns only an sRGB presentation
surface and therefore publishes the truthful `SRGB=0` value.
`currentSelectedBlockId` is the selected block's shader material ID, while
`currentSelectedBlockPos` is its block center relative to the camera. No
eligible target uses Iris's `0` and `vec3(-256)` sentinels.

`IrisFrameStateClock` now captures the cloud clock and queries
`BlockOutlineRenderer.irisSelectedTarget`, which applies the same enabled,
outline-capable, border, reach, and gamemode policy as the visible host block
outline. It does not perform another ray cast or invent a shader-only
selection. The immutable frame snapshot carries the raw block state and
camera-relative center. `IrisWorldShaderPipeline.beginFrame` resolves the
state through the generation-owned block material map before smoothing or
publication. Direct uniforms and custom expressions consume the same resolved
snapshot, and `render.substrate.frameInputValues` exposes bounded scalar
components for live acceptance.

The live canary exposed and closed a material-map preprocessing defect.
Complementary conditionally repeats IDs such as `block.10232` for modern and
legacy state syntax under `#if MC_VERSION >= 11300`. The prior planner parsed
the unprocessed file, so ordinary Java-properties last-key semantics retained
the legacy `sand:variant=sand` branch and modern sand resolved to zero. Pinned
Iris preprocesses every material properties file with the effective shader
options and environment before `Properties.load`. The planner now does the
same for `block.properties`, `item.properties`, and `entity.properties`.
A focused planner test pins that branch selection and prevents the modern
rules from being overwritten by inactive legacy text.

On hot-reloaded client PID 72304, the camera was temporarily aimed at the sand
directly below the player. At frame 271 the live snapshot reported:

- `currentSelectedBlockId=10232`;
- `currentSelectedBlockPos=(-0.41076538,-2.03,-0.1199233)`;
- a progressing nonzero `cloudTime` and `currentColorSpace=0`;
- 51 main plus six shadow programs with empty rejected/fallback maps.

The exact prior yaw/pitch was restored after the canary. The following
snapshot returned selection to `currentSelectedBlockId=0` and
`currentSelectedBlockPos=(-256,-256,-256)`, proving both selected and absent
paths on the production renderer.

Focused frame/planner tests, the untouched managed Complementary external-pack
gate, and the full Java 17 `:test :integrationTest` gate pass. An explicit
shader reload advanced graph generation 17 → 18 and shader generation 2 → 3
with the same official fingerprint. The caller again reached its bounded
deadline before publication, but the immediate follow-up retained 51+6
routes, empty rejection/fallback maps, and exactly 40 live textures, seven
framebuffers, 103 programs, and zero shader objects. A checked 3456×1910
capture at frame 5,048 showed one active Complementary-composited world without
a duplicate scene draw. Parent PID 51209, server PID 51235, and joined,
render-ready client PID 72304 remain supervised.

This closes the pinned selected-block, cloud-clock, and currently reachable
output-color inputs. Distant Horizons uniforms remain gated on an actual DH
producer, and compute/image/SSBO execution still requires the documented
non-Apple OpenGL 4.3+ acceptance lane.

## Ordinary translucent held-item live-contract checkpoint

The earlier held-item implementation separated ordinary item-model quads by
their declared texture transparency, but a selected Complementary program name
could not prove which host family initiated the bind. Complementary does not
ship a distinct active `gbuffers_hand_water` root for this profile, so both a
correct `HAND_WATER` request and an incorrect `HAND` request can select
`gbuffers_hand`.

`WorldShaderPipelineDiagnostics.selectedSceneContracts` now records a bounded
counter key containing the requested graph semantic, requested scene family,
selected shader-pack root, and host vertex/state ABI. The debug snapshot
therefore distinguishes the host contract from pack fallback selection without
exposing shader source or OpenGL names. The new
`render.prepare-translucent-held-item` canary equips one exact client-only
vanilla stack and restores the exact prior main-hand stack.

On source generation 14, joined/render-ready client PID 11362 selected the
official Complementary fingerprint
`9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`.
Live ice, honey-block, slime-block, glass, and tinted-glass canaries all
advanced:

- `HAND/HAND/gbuffers_hand/HELD_ITEM/HELD_ITEM` for opaque/cutout faces;
- `HAND/HAND_WATER/gbuffers_hand/HELD_ITEM/HELD_ITEM` for translucent faces.

This is a face partition, not a replay of the complete item model. A checked
3456×1910 frame showed one held ice model, and the same snapshot retained empty
scene rejection/fallback maps with exactly 40 textures, seven framebuffers,
103 linked programs, and zero shader objects. Every canary restored the prior
empty hand; HUD and cloud presentation were restored after capture.

The focused held-item/registry suite, the untouched Complementary external-pack
gate with `RP_MODE=3;SHADOW_QUALITY=1`, and the full Java 17
`:test :integrationTest` gate pass. This closes the ordinary live
`HAND_WATER` producer gate without claiming that all specialized producer
canaries are complete. Distant Horizons inputs still require an actual DH
producer, while compute/image/SSBO execution still requires the documented
non-Apple OpenGL 4.3+ lane.

## World-border producer checkpoint

The world-border renderer already requested the exact
`TEXTURED/WORLD_BORDER/WORLD_BORDER` scene contract, but it lacked a bounded
remote-world canary and its distance calculation silently assumed that every
border was centered at world origin. A translated border centered on the
player therefore reported a large negative distance, forcing the renderer's
fade strength to its maximum.

`WorldBorder.getDistanceTo` now computes the minimum signed distance to the
four translated border faces after clamping those faces to the absolute world
limits. Focused tests pin the center, near-face, outside, and world-limit cases.
The modern `gbuffers_textured` transformer test also pins the border's animated
UV offset, packed texture selection, tint delivery, and transformed diffuse
sample path.

`render.prepare-world-border` provides the corresponding client-only live
canary. It accepts a finite radius from 2 through 64 blocks, centers a static
border on the local player, and saves the exact previous center, `BorderArea`
object, and `WorldBorderRenderer.referenceSuppressed` value for restoration.
It reports both configurations and the current signed distance so acceptance
can distinguish correct translated geometry from a merely advancing draw
counter.

On hot-reloaded joined/render-ready client PID 54356, a radius-64 canary
reported distance 64 and advanced:

- `WORLD_OVERLAY/TEXTURED/gbuffers_textured/WORLD_BORDER/WORLD_BORDER`;
- 285 selected scene binds;
- no rejected or host-fallback scene binds;
- the official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

The supervised parent/server PIDs remained 51209/51235 across the base
generation swap. The first restoration exposed a second independent defect:
`prepareDrawAsync` stopped preparing a far-away border, but the old retained
mesh remained registered and `draw` continued submitting it. The renderer now
uses the complete `skip` predicate in both its graph-layer skip callback and
its draw guard. After restoration, the selected world-border contract counter
remained exactly 175 across consecutive snapshots two seconds apart.

With stale submission removed, a clean host-presentation capture showed the
expected animated forcefield stripe geometry while the same radius through
Complementary produced a dark wall. Pinned Iris source at tag
`1.7.2+1.20.4` resolves `ShaderKey.TEXTURED` with
`AlphaTests.NON_ZERO_ALPHA`, a `GREATER 0.0001` test, unless an authored
`alphaTest.<program>` directive overrides it. Minosoft had implemented the
authored override but not the ShaderKey default. Fully transparent forcefield
texels therefore still executed Complementary's fragment shader and wrote its
auxiliary translucent buffers, darkening the complete quad.

`IrisAlphaTestDefaults` now applies that exact unambiguous default to retained
`GENERIC_TEXTURE_2D` and `WORLD_BORDER` scene variants. Explicit authored
tests, including `off`, still win. The transformer test pins both the
`GREATER 1.0E-4` marker and the sampled attachment-zero alpha evaluation.
After hot reload, the radius-64 capture no longer reproduced the solid black
enclosure, the route remained selected, and resource/rejection/fallback
baselines stayed clean. Another controller moved the camera between the
enabled and disabled frames, so this checkpoint does not claim a same-pose
pixel-difference threshold. Repeat the A/B in an isolated client before
closing exact pixel parity.

The border was restored to its exact prior center and area, normal
HUD/cloud/entity/particle presentation was restored, and the official untouched
Complementary planner contract with `RP_MODE=3;SHADOW_QUALITY=1` passes. The
same exact external archive also passed the complete `:test :integrationTest`
suite. A subsequent live `mods.iris.reload-shaders` exceeded only its bounded
wire deadline: publication completed, graph/shader generations advanced from
17/2 to 18/3, the exact fingerprint and all 51 main plus six shadow scene
variants remained selected, rejection/fallback maps remained empty, and the
OpenGL texture/framebuffer/program/shader ledger remained 40/7/103/0.

## Lightning producer checkpoint

The lightning shader contract and retained renderer already existed, but prior
acceptance proved only compilation because the supervised scene contained no
lightning entity. `render.prepare-lightning` now closes that gap without a
synthetic draw. It creates one initialized client-only `LightningBolt` at a
bounded one-to-16-block distance, adds it under a reserved negative ID through
the production `WorldEntities` manager, and removes the exact saved instance
on restore. Renderer creation, feature retention, entity-layer collection,
scene selection, and cleanup therefore remain the ordinary game paths.

Hot-reloaded joined/render-ready client PID 73522 selected:

- `ENTITIES_TRANSLUCENT/LIGHTNING/gbuffers_lightning/POSITION_COLOR/LIGHTNING`;
- 320 total route binds with no rejected or host-fallback scene binds;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

After explicit removal at frame 526, the route counter settled at 320 and
remained exactly 320 at frame 700. This proves the retained renderer was
retired instead of continuing a stale second draw. The focused
`LightningBoltRendererTest` passes on Java 17 and pins the real retained feature
plus `LIGHTNING/POSITION_COLOR/LIGHTNING` contract.

## Beacon and block-entity producer checkpoint

The beacon program previously had compile and headless renderer coverage but no
live producer evidence. `render.prepare-beacon` now finds an empty position in
a loaded nearby chunk whose next eight blocks do not contain a full opaque
state. It places the registry's real beacon state through `World.set`, verifies
that `BlockEntityDataProvider` created a `BeaconBlockEntity`, sets its bounded
level to four, and later restores the exact prior air state. Existing blocks
and block entities are never replaced.

This deliberately retains the production lifecycle: the single-block update
invalidates the section through `ChunkRendererChangeListener`, asynchronous
meshing retains the block entity, and the ordinary opaque/translucent
block-entity phases invoke its specialized renderer. On hot-reloaded
joined/render-ready client PID 77788, Complementary selected:

- `BLOCK_ENTITIES_TRANSLUCENT/BEACON_BEAM/gbuffers_beaconbeam/POSITION_TEXTURE/BEACON_BEAM`;
- `BLOCK_ENTITIES/ENTITY/gbuffers_block/SKELETAL/SKELETAL_LIGHTMAP` for the
  scene's ordinary retained block entities;
- no rejected or host-fallback scene binds;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

The canary restored air at frame 809. Its route counter finished while the
already-scheduled section remesh retired, settled at 8,045 by frame 953, and
remained exactly 8,045 through frame 1,531. Focused Java 17 tests pass for
beacon level/contract behavior, block-entity creation and removal, entity-block
remesh invalidation, and per-renderer block-entity shader binding. The checked
frame was camera-obstructed by the concurrent controller, so this checkpoint
claims production route and cleanup evidence rather than a stable beacon pixel
reference.

## Sign text and emissive-terrain checkpoint

Sign glyphs are not a block-entity scene draw in Minosoft. The
`SignBlockEntityRenderer` bakes them into the owning section's `TEXT` mesh,
which the terrain backend submits as `EMISSIVE_ADDITIVE`. Initial live
acceptance found a real sign block entity with four non-empty glowing lines and
the expected renderer, but `render.substrate` reported zero visible text meshes
and no emissive terrain selection. The operation's bounded line diagnostics
confirmed all 17 front-side glyph primitives, while the resolved block model
remained `SignBlockEntityRenderer`; the loss was therefore upstream of Iris
program selection.

Two production gaps caused that result:

- `BlockDataS2CP` updated mutable block-entity NBT without emitting a world
  update, so sign text changes did not inherently invalidate terrain.
- `ChunkMeshingQueue` ignored a second invalidation while a section was still
  queued. The first request identity therefore retained the pre-NBT terrain
  revision, cancelled as stale during snapshot capture, and was removed without
  a replacement build.

`Chunk.applyBlockEntityData` now owns NBT application and emits
`BlockEntityDataUpdate`; `ChunkRendererChangeListener` invalidates the exact
section. Queued invalidations now replace `latestRequests[position]` before
deduplicating the physical queue item, so the one queued build consumes the
latest terrain revision. The focused `ChunkRendererTest` pins this coalescing
invariant, while `ChunkTest` pins update/no-update behavior for present and
missing block entities.

On the final hot-reloaded joined/render-ready client PID 16092,
`render.prepare-sign-text` placed a real registry oak sign at
`(-175,63,-153)`, applied `IRIS / SIGN / TEXT / ROUTE` to both glowing sides,
and confirmed the section retained the same state and entity. Complementary
then reported:

- one visible `TEXT` mesh with 204 vertices;
- a current-frame `EMISSIVE_ADDITIVE` terrain submission;
- `minosoft:main/emissive_additive/gbuffers_terrain`, selected ten times by
  frame 1,753;
- empty rejected and fallback scene-bind maps;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

Restoring the exact air state removed the visible text mesh. The emissive bind
counter finished at 99 while the already-scheduled remesh retired, then
remained exactly 99 through frame 2,928. The concurrent controller changed
camera/visibility and therefore the ordinary terrain buffer population during
this run; shader-owned resource counts, exact route counters, retained mesh
state, and the sampled block restoration are the durable acceptance signals.

## Damaged-block producer checkpoint

`gbuffers_damagedblock` previously had planner, transformer, and contract
coverage but no direct live producer acceptance. `render.prepare-block-break`
now searches only loaded client space within a bounded six-block horizontal and
four-block vertical neighborhood, selects an existing state with a baked model,
and publishes one `BlockBreakAnimationEvent` under reserved ID `-2000000002`.
It does not change that block or send a server packet. The normal event
listener builds the retained breaking mesh, selects the destroy-stage texture,
and submits through the polygon-offset world-overlay layer.

On hot-reloaded joined/render-ready client PID 28154, the canary selected smooth
sandstone at `(-175,62,-157)`, retained exactly one instance at progress
`4/9`, and Complementary selected:

- `WORLD_OVERLAY/DAMAGED_BLOCK/gbuffers_damagedblock/DAMAGED_BLOCK/DAMAGED_BLOCK`;
- `gbuffers_damagedblock/DAMAGED_BLOCK/DAMAGED_BLOCK` as the exact scene route;
- empty rejected and fallback scene-bind maps;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

Removal at frame 142 reported zero retained break instances. The route counter
finished at 450 while the queued render request retired, then remained exactly
450 through frame 253. This closes the damaged-block producer and retained
cleanup checkpoint without relying on camera control from the competing
session.

## Weather producer checkpoint

The weather family had compile, transformer, graph-phase, and shader-contract
coverage but no direct live producer evidence in the supervised clear-weather
scene. `render.prepare-weather` now applies one full-strength client-only rain
state. The current flat-world biome exposes no precipitation, so the canary
sets only `WeatherOverlay.referencePrecipitationOverride=RAIN`; the existing
weather state, overlay render predicate, rain texture, randomized strip mesh,
intensity/offset uniforms, `WEATHER` graph phase, and shader binding remain the
production path. Restore changes the saved weather and precipitation gate only
when both still match the canary, avoiding overwrite of a concurrent
authoritative update.

On hot-reloaded joined/render-ready client PID 30610, enable at frame 34
reported source precipitation `null`, effective precipitation `rain`, dimension
weather support, and applied/current rain `1.0`. By frame 45 Complementary
selected:

- `WEATHER/WEATHER/gbuffers_weather/WEATHER/WEATHER`;
- `gbuffers_weather/WEATHER/WEATHER` as the exact scene route;
- empty rejected and fallback scene-bind maps;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

Restore at frame 151 reported `rain=0`, effective precipitation `null`, no
remaining override, and `restored=true`. The weather route counter finished at
600 while the queued render request retired, then remained exactly 600 through
frame 248. This closes the direct weather producer and cleanup checkpoint
without moving the camera or changing server authority.

## Emissive entity-eyes producer checkpoint

The `ENTITY_EYES` family had authored ETF/EMF and Gecko emissive-material
loading, retained translucent-layer draws, scene-contract tests, exact
Complementary compilation, and cleanup coverage, but the supervised scene did
not contain an authored emissive companion. `render.prepare-entity-eyes`
selects one retained non-player `SkeletalFeature`, keeps its existing geometry
and material resolution, and temporarily uses the resolved base material as
the emissive companion consumed by `SkeletalFeature.drawEmissive`. It also
pins only that renderer's acceptance visibility. Normal rendering leaves the
override false and remains entirely asset-driven.

On hot-reloaded joined/render-ready client PID 34570, the canary selected
glow-squid entity 24151 with one retained mesh at frame 116. By frame 185
Complementary had selected:

- `gbuffers_spidereyes/SKELETAL/SKELETAL_TINTED` 345 times;
- `ENTITIES_TRANSLUCENT/ENTITY_EYES/gbuffers_spidereyes/SKELETAL/SKELETAL_TINTED`
  as the exact requested contract;
- empty rejected and fallback scene-bind maps;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

Restore at frame 231 returned both the emissive-base and visibility overrides
to their exact prior values. Already queued draws retired at 575 binds; the
counter then remained exactly 575 through frame 810. The canary never changed
the entity, registered assets, or server authority. This closes the direct
emissive-eye producer selection gap while leaving authored emissive resource
selection as the normal path.

## Primed-TNT flashing-block producer checkpoint

The official Complementary gate compiled the `FLASHING_BLOCK` main and shadow
bridges, but no prior live canary proved a primed-TNT entity actually selected
them. `render.prepare-primed-tnt` now adds one reserved-ID, client-only
`minecraft:tnt` through `WorldEntities`. The production renderer factory,
`PrimedTNTEntityRenderer`, retained TNT block mesh, `FlashingBlockFeature`
progress/color uniforms, entity graph layer, and physical shadow traversal are
the only draw path. Disable removes that exact entity without server mutation.

The first live cycle exposed an independent producer defect: the physics
constant was `-0.04` and the integrator subtracted it, launching the canary from
Y 63.25 to Y 374.84. `PrimedTNTPhysics.GRAVITY` is now positive `0.04`, matching
the existing subtractive integrator. Focused integration coverage proves the
first velocity and position delta are downward.

On hot-reloaded joined/render-ready client PID 38131, the corrected canary
started at Y 63.25 and settled on Y 63 before removal. Complementary selected:

- `ENTITIES_TRANSLUCENT/BLOCK/gbuffers_block/BLOCK_FEATURE/FLASHING_BLOCK`;
- `ENTITIES_TRANSLUCENT/BLOCK/shadow/BLOCK_FEATURE/FLASHING_BLOCK`;
- empty rejected and fallback scene-bind maps;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 103 programs, and zero shader
  objects.

This closes the flashing-block producer selection gap while retaining normal
entity lifecycle, block-model material delivery, animated flash state, shadow
casting, and cleanup. Already queued draws retired at 1,650 main and 2,195
shadow binds; both counters remained stable through frame 1,046.

## Leash light-color producer checkpoint

The retained leash implementation already had audited vanilla ribbon geometry,
EMF-adjusted mob anchors, player/knot/generic holder anchors, endpoint light
interpolation, and a `LEASH/POSITION_COLOR_LIGHT/LIGHT_COLOR` contract. It had
no direct Complementary producer evidence. `render.prepare-leash` temporarily
sets one retained living entity's synchronized client leash holder to the local
player and pins only that renderer's acceptance visibility. Restore is
conditional and returns the exact previous holder and visibility override.

The first live canary on client PID 46087 revealed 64 host fallbacks under:

```text
ENTITIES/LEASH/POSITION_COLOR_LIGHT/LIGHT_COLOR
```

Complementary contained and linked `gbuffers_basic`, but the modern transformer
handled only `gbuffers_line`; the basic program therefore published no retained
scene bridge. The modern branch now applies the same color/light-color/sky
vertex specializations and core fragment-output conversion to
`gbuffers_basic`. Focused transformer tests pin the lightmap UBO, packed-light
input, bridge declaration, and core output conversion.

On hot-reloaded joined/render-ready client PID 53666, the active generation
compiled:

- `gbuffers_basic/POSITION_COLOR/COLOR`;
- `gbuffers_basic/POSITION_COLOR_LIGHT/LIGHT_COLOR`;
- `gbuffers_basic/SKY_POSITION/SKY_COLOR`.

A retained iron golem attached to the local player then selected
`ENTITIES/LEASH/gbuffers_basic/POSITION_COLOR_LIGHT/LIGHT_COLOR` 168 times by
frame 169. Restore at frame 214 returned both holder and visibility override to
null. Rejected and fallback maps were empty, the exact Complementary
fingerprint remained active, and the three newly executable variants moved the
expected stable shader ledger from 40 textures/seven framebuffers/103
programs/zero shaders to 40/7/106/0. Already queued leash draws retired at 258
binds, then remained exactly 258 through frame 654.

This closes a real host-shader fallback rather than merely adding acceptance
coverage.

## Translucent-particle producer checkpoint

The particle renderer already split opaque and translucent CPU buffers and
graph passes according to texture transparency or vertex alpha, but only the
opaque semantic had direct live Complementary evidence.
`render.prepare-translucent-particle` now queues one real `minecraft:sneeze`
particle. Its authored alpha is 102/255, so normal `TextureParticle` selection
places it in the translucent mesh without an acceptance-only material flag.
The canary pins only movement, physics, and lifetime so it remains observable;
disable marks the exact instance dead and the normal `ParticleTicker` removes
it. `ParticleRenderer.hasParticle` safely observes both the queue and retained
list under their existing lock order, and focused integration coverage proves
queued, retained, and dead/removal states.

On hot-reloaded joined/render-ready client PID 64524, enable at frame 86
reported the exact sneeze type retained with a 400-tick lifetime. Complementary
selected:

- `PARTICLES_TRANSLUCENT/PARTICLE/gbuffers_particles/PARTICLE_POINT/PARTICLE`;
- empty rejected and fallback scene-bind maps;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 106 programs, and zero shader
  objects.

Disable at frame 240 marked the particle dead while the current translucent
mesh still held its already prepared frame. Queued draws retired at 924 binds,
then remained exactly 924 through frame 454. This closes the post-deferred
translucent-particle producer and cleanup checkpoint.

## Chunk-border basic-color producer checkpoint

The modern `gbuffers_basic` fix established an executable
`POSITION_COLOR/COLOR` bridge, but the only retained producer using the default
`BASIC` family—the F3+G chunk-border renderer—was disabled in the supervised
profile. `ChunkBorderRenderer.referenceEnabledOverride` now provides a
render-only acceptance gate. Normal rendering leaves it null and follows the
profile. The ordinary async chunk/section grid build, `LineMeshBuilder`, world
overlay layer, and generic `ColorShader` remain the complete path.

On hot-reloaded joined/render-ready client PID 65599, enable at frame 122
reported configured `false`, override/effective `true`, and no mesh before the
first async preparation. By frame 198 Complementary selected:

- `WORLD_OVERLAY/BASIC/gbuffers_basic/POSITION_COLOR/COLOR`;
- empty rejected and fallback scene-bind maps;
- the exact official Complementary fingerprint
  `9b4bec9ea25a5270ab46453aff12ea2d99916ab792c282b781ca8cf72619f57b`;
- exactly 40 live textures, seven framebuffers, 106 programs, and zero shader
  objects.

Restore at frame 237 returned the override to null and effective state to the
unchanged configured false value. The retained mesh retired through normal
async cleanup; the route counter settled at 115 and remained exactly 115
through frame 572. This closes the generic basic-color producer and cleanup
checkpoint.

## Per-render-target blend checkpoint

Iris/OptiFine `shaders.properties` blending is now an executable pipeline
contract rather than an ignored pack hint. `blend.<program>` retains either an
explicit four-factor color/alpha function or `off`;
`blend.<program>.<colortexN|shadowcolorN>` retains a per-output override.
Factors are bounded to the documented Iris set, including
`SRC_ALPHA_SATURATE`, malformed factors/targets fail before publication, and
valid directives for option-disabled outputs remain dormant instead of
rejecting the pack. The planner publishes
`IRIS_FEATURE_PER_BUFFER_BLENDING` only when the current context exposes
OpenGL 4.0 and pipeline realization rejects indexed overrides without that
capability.

The exact Complementary WSR gate exposed a separate output-selection defect.
After preprocessing, `gbuffers_water` contains a base `DRAWBUFFERS:03` followed
by option-specific `036` and `03648` declarations. The planner previously chose
the first surviving declaration, silently dropping the normal and reflection
attachments. It now uses the final active `DRAWBUFFERS` or `RENDERTARGETS`
declaration, matching the pack's authored override structure. The external-pack
test with `WORLD_SPACE_REFLECTIONS=1` pins both `colortex4` and `colortex8` in
the resulting write list.

At runtime, program-level overrides use the complete four-factor blend
function. Programs with per-output overrides resolve every attached draw
buffer from the explicit buffer rule, then the program rule, then the host
state, and apply that state with indexed OpenGL blending. Scene and
renderer-owned internal-target boundaries restore the cached host blend state,
so a pack override cannot leak into a later fallback, HUD, or private
framebuffer draw. The `blendFunc` uniform reports the resolved program/host
function rather than stale global GL state. Capability negotiation follows the
actual Iris feature boundary: OpenGL 4.0 uses the core indexed-blend entry
point, while an older context with `ARB_draw_buffers_blend` uses its extension
entry point. `IRIS_FEATURE_PER_BUFFER_BLENDING` and runtime acceptance derive
from that same predicate.

The fresh supervised trajectory
`complementary-unbound-blend-2026-07-28` launched the exact managed
Complementary Unbound r5.8.1 archive with
`WORLD_SPACE_REFLECTIONS=1` on Apple M4 Max OpenGL 4.1. Client PID 81981,
under stable parent/server PIDs 75013/75036, reported:

- configured-generation fingerprint
  `4ebc1d4c919b644b3bdaead5dd73bebcea4705bc9209fc11f4de47d1aeae7769`;
- `gbuffers_water` outputs
  `colortex0,colortex3,colortex6,colortex4,colortex8`;
- retained blend rules `colortex4=off,colortex8=off`;
- 646 indexed applications of each rule by frame 647;
- empty rejected and fallback scene-bind maps;
- no OpenGL error after the current render loop began;
- 42 live textures, seven framebuffers, 106 programs, and zero shader objects.

A bounded `mods.iris.reload-shaders` request exceeded its caller deadline but
completed transactionally in the same client: shader generation advanced from
2 to 3, the two application counters restarted and reached 458 each, and the
42/7/106/0 resource ledger was unchanged. Focused planner, blend resolution,
capability, draw-state, registry-boundary tests, the renderer-pipeline
integration test, and the exact external-pack gate pass on Java 17. This
accepts program/per-target blend syntax and the real Apple indexed-blend path;
a checked water pixel A/B and a second driver remain open.

The supervised source rebuild replaced client PID 81981 with PID 84653 while
retaining parent/server PIDs 75013/75036. Acceptance therefore re-resolved the
render-ready endpoint instead of sampling the stale process. At frame 1,041 the
replacement retained the same fingerprint and water outputs, both explicit
rules had advanced to 1,040 applications, rejection/fallback maps were empty,
and the resource ledger remained 42/7/106/0. Capability diagnostics reported
both OpenGL 4.0 core and `ARB_draw_buffers_blend` present and derived
`irisPerBufferBlending=true`.

## Authored settings and transactional option checkpoint

Shader-option discovery no longer lets multiline whitespace consume the next
CRLF `#define`. Define and const-option patterns accept horizontal whitespace
inside one physical line only, and a focused CRLF fixture proves two adjacent
boolean definitions remain two independently selectable options. Authored
settings validation also accepts both Iris's `[profile]` and the
`<profile>` spelling used by Complementary Unbound. The external-pack gate now
loads the complete settings model, not only the executable program plan, so an
unknown option-screen entry fails before a managed pack is accepted.

`mods.iris.configure-options` exposes a bounded live acceptance surface for
authored option values. It validates every name and value against the selected
pack, merges the requested values into the adapter's current option map, and
runs the ordinary shader resource-reload transaction. A failed candidate
restores the exact option string while the active shader generation remains
published. The operation is intentionally not a second shader-loading path.

Complementary's `WORLD_SPACE_REFLECTIONS=1` is a useful planner and indexed
blend gate, but it is not a clean Apple OpenGL pixel baseline: the pack
requires Advanced Color Tracing and presents its own full-screen diagnostic
when that dependency is unavailable. On render-ready client PID 9754, a live
configuration request for `WORLD_SPACE_REFLECTIONS=-1` exceeded the caller's
short deadline while compilation was in progress, then completed
transactionally. The resulting shader generation 3 reported fingerprint
`211f61d4359b8734192266bf126c2f5b1f058901964ac3ddf936665b332c663e`,
water outputs `colortex0,colortex3`, empty rejection/fallback maps, and the
stable 40-texture/seven-framebuffer/106-program/zero-shader resource family.
This clean option state is the recommended Apple visual baseline; the
WSR-enabled state remains the exact planner/per-target-blend feature gate.
An unknown-option request returned `invalid_request` and left generation 3,
the fingerprint, and both empty rejection/fallback ledgers unchanged.
Source supervision later replaced PID 9754 with PID 14779 and restored the
process-environment WSR-on configuration. Re-resolving the endpoint and
repeating the same bounded transaction returned the replacement to generation
3, fingerprint `211f61d...663e`, `colortex0,colortex3`, empty ledgers, and the
same 40/7/106/0 resource family. Live acceptance must therefore resolve the
current trajectory endpoint after source reload instead of retaining a client
PID or assuming debug-option state survives process replacement.

## Storage block-entity producer checkpoint

`render.prepare-storage-block-entity` places exactly one bounded storage type
(`chest`, `trapped_chest`, `ender_chest`, or `shulker_box`) into nearby loaded
air through `World.set`, applies the ordinary block action for an open or
closed lid, and restores the exact air state on disable. It never replaces an
existing block or bypasses block-entity construction, chunk collection, the
retained skeletal renderer, or Iris draw-state upload.

On the clean Complementary generation above, every catalog entry created its
exact production class and increased
`terrain.visibleMeshes.blockEntities` from two to three. The selected contract
was
`BLOCK_ENTITIES/ENTITY/gbuffers_block/SKELETAL/SKELETAL_LIGHTMAP`.
Chest and trapped-chest draws used material ID 5008, ender chest exercised
5012, and shulker box exercised the distinct 5016 draw-state key. Rejection and
fallback maps stayed empty. Every disable response reported `restored=true`,
`currentBlock=null`, and the visible block-entity total returned to two.

The shulker capture also crossed the real final framebuffer with transient GUI
and entity-hitbox overlays removed. The existing steep camera pose left the
opened lid partially clipped at the upper edge, so this checkpoint claims a
real pixel crossing but not a checked storage-model reference. A fixed camera
crop and a second driver remain open visual gates.

## Producer-derived particle basis checkpoint

Particle billboards already reached `gbuffers_particles`, but their shader
bridge still published `normal=(0,1,0)` and an identity TBN regardless of the
camera-facing geometry. That made the route executable without making its
directional lighting faithful. The generated geometry stage now derives the
surface basis from the exact axes used to expand each retained point:

- tangent is normalized camera-right, matching increasing texture U;
- bitangent is negative camera-up, matching increasing texture V;
- normal is `cameraRight × cameraUp`, matching the emitted triangle winding;
- the resulting TBN intentionally retains negative handedness because the
  texture V domain and geometric up direction are opposite.

The focused billboard test proves the axes remain orthonormal without
projection scaling, the emitted triangle normal matches the derived surface
normal, and `(tangent × bitangent) · normal = -1`. Transformer coverage pins
the generated GLSL and rejects the former world-up fallback. The complete
transformer suite and untouched Complementary external-pack gate pass.

Source supervision replaced the client with PID 18083. After re-resolving that
endpoint and transactionally restoring `WORLD_SPACE_REFLECTIONS=-1`, shader
generation 3 retained fingerprint
`211f61d4359b8734192266bf126c2f5b1f058901964ac3ddf936665b332c663e`
and the 40/7/106/0 resource family. A client-only sneeze particle selected:

- `PARTICLES_OPAQUE/PARTICLE/gbuffers_particles/PARTICLE_POINT/PARTICLE`;
- `PARTICLES_TRANSLUCENT/PARTICLE/gbuffers_particles/PARTICLE_POINT/PARTICLE`;
- empty rejected and fallback scene-bind maps.

The final-framebuffer pair shows the enabled particle as a bounded translucent
billboard at the crosshair with no screen-sized or dark rectangle. Disable
marked the exact particle dead and removed it from the retained set; its
translucent route counter settled at 1,032 and remained exactly 1,032 through
the later checked frame. This closes the particle-specific non-cuboid basis
gap. At this checkpoint generic textured quads, flames, billboard text,
weather, and other non-cuboid producers still required producer-specific
basis audits rather than being covered by the particle claim; the subsequent
billboard-text and shared textured-quad checkpoints close the first three.
Weather remains intentionally separate because its selected program does not
consume a material surface basis.

## Producer-derived billboard-text basis checkpoint

Entity name tags and text displays use a retained local XY font mesh transformed
by `uMatrix`. Their modern Iris bridge previously replaced that information
with local positive Z plus the generic normal-derived tangent. The bridge now
derives increasing-U and increasing-V axes from the transformed local X and Y
directions. Its normal uses `bitangent × tangent`, matching the font quad's
top-left, bottom-left, bottom-right triangle winding, and its PBR tangent keeps
the corresponding negative handedness. This works for camera-facing name tags
and fixed/rotated text displays without changing their mesh ABI.

`render.prepare-billboard-text` supplies a reversible live canary when the
current camera has culled every non-player entity. It selects the nearest
retained entity renderer, saves its exact custom name, name-visible value, and
render-only visibility override, publishes a bounded client-only name through
ordinary tracked data, and restores all three values on disable. It does not
add debug geometry or mutate server authority.

On replacement client PID 22786, cat ID 8515 selected
`ENTITIES_TRANSLUCENT/ENTITY/gbuffers_entities/BILLBOARD_TEXT/BILLBOARD_TEXT`
through clean Complementary generation 3. Rejection and fallback maps stayed
empty. Disable restored `name=null`, `nameVisible=false`, and the prior
visibility override. The route counter settled at 16,715 and remained exactly
16,715 through frame 3,971. Focused transformer coverage and the untouched
external-pack gate pass. Existing named-Naturalist captures already establish
billboard rasterization; this checkpoint closes the producer-derived basis and
reversible route gate, not a new checked text pixel reference.

## Producer-derived generic textured-quad basis checkpoint

The shared `POSITION_TEXTURE` mesh previously retained position, UV, texture,
and tint only. Its modern entity and flame bridges therefore published a
world-up normal and synthesized tangent regardless of the producer, while the
beacon bridge published an identity TBN. This made the draws executable but
made directional and companion-map sampling unrelated to their actual faces.

`SimpleTextureMeshBuilder` now appends one float3 normal and float4
tangent/handedness. Its world-space `addQuad` derives a stable basis from the
first three producer positions and UVs, orthogonalizes the tangent, preserves
mirrored-UV handedness, and provides bounded degeneracy fallbacks. The three
world producers use that quad boundary:

- projected entity shadows retain positive world Y with texture U along world
  X and V along world Z;
- local entity-flame sheets retain their own face and mirrored-UV orientation,
  then transform both directions through `uMatrix`;
- every beacon prism side retains its own face normal, increasing-U tangent,
  and vertical texture direction, then transforms the basis through `uMatrix`.

Generic 2-D and first-person fire overlays continue to use the same buffer
layout with a neutral basis that their screen-space shader does not consume.
No extra render traversal or geometry copy was added. Modern generic,
entity-flame, and beacon specializations now consume locations 4/5; the
legacy textured-scene generic specialization also publishes its derived TBN
instead of leaving it undefined. Shadow-map flame specialization keeps the
same position/UV-only contract because its depth caster does not sample a
surface basis.

Focused math coverage pins the horizontal shadow, vertical beacon, and
mirrored flame cases. The complete transformer suite, exact untouched
Complementary WSR-enabled external-pack gate, complete root unit suite, focused
shadow/beacon integrations, and `compileKotlin` pass on Java 17.

Source supervision replaced the client with PID 28283. The process initially
restored its WSR-on environment; the bounded WSR-off transaction exceeded only
the caller deadline and then published graph/shader generations 18/3 with
fingerprint
`211f61d4359b8734192266bf126c2f5b1f058901964ac3ddf936665b332c663e`.
On that clean generation, a forced-visible cat plus the ordinary flame,
projected-shadow, billboard, and beacon canaries selected:

- `ENTITIES_TRANSLUCENT/TEXTURED_LIT/gbuffers_entities/POSITION_TEXTURE/GENERIC_TEXTURE`;
- `ENTITIES/ENTITY/gbuffers_entities/POSITION_TEXTURE/ENTITY_FLAME`;
- `ENTITIES/ENTITY/shadow/POSITION_TEXTURE/ENTITY_FLAME`;
- `BLOCK_ENTITIES_TRANSLUCENT/BEACON_BEAM/gbuffers_beaconbeam/POSITION_TEXTURE/BEACON_BEAM`;
- `ENTITIES_TRANSLUCENT/ENTITY/gbuffers_entities/BILLBOARD_TEXT/BILLBOARD_TEXT`.

Both rejection and fallback maps remained empty with the stable 40/7/106/0
texture/framebuffer/program/shader ledger. Cleanup restored the cat fire flag,
removed the beacon back to air, and restored the cat name and visibility
override. Across frames 2,714 through 3,343, flame main/shadow remained exactly
11,980, beacon remained 12,670, and billboard remained 12,520; the ordinary
generic projected-shadow route continued advancing.

A concurrent controller opened the pause screen, changed the camera, and
re-enabled debug overlays during the live visual attempt. The checked route,
cleanup, and resource evidence is valid because every debug operation
re-resolved PID 28283, but this checkpoint deliberately makes no same-pose
pixel claim. Repeat the enabled capture in an isolated client before using it
as a reference image.

## Producer-derived lightning ribbon basis checkpoint

Complementary's `gbuffers_lightning` fragment reads `normal` to derive its
up-facing lighting terms. Minosoft's retained crossed-ribbon producer
previously exposed only the shared `POSITION_COLOR` fields, so the modern
bridge substituted world positive Y for every jagged segment. That was a valid
route but not the surface the producer submitted.

`LightningMeshBuilder` now isolates lightning from generic line/color meshes
and appends one float3 face normal at location 2. For each jagged segment it
derives both crossed-ribbon normals from the exact width axis and segment
direction:

- the X-width ribbon retains normalized `(0, -deltaZ, deltaY)`;
- the Z-width ribbon retains normalized `(-deltaY, deltaX, 0)`;
- invalid or degenerate directions use bounded orientation-matching
  fallbacks.

All four vertices of each ribbon share the corresponding face normal. The
modern lightning bridge transforms it through `uMatrix` and derives a stable
orthonormal TBN instead of publishing world-up plus identity. The built-in
lightning shader still consumes locations 0/1 and safely ignores the appended
attribute; generic lines keep their existing smaller mesh layout.

Focused tests prove vertical and jagged normals are unit length and
perpendicular to both the ribbon width and segment direction. The complete
transformer suite, exact untouched Complementary WSR-enabled external gate,
and focused real-renderer integration pass on Java 17.

Source supervision replaced the client with PID 33580. After re-resolving it
and completing the expected deadline-exceeded WSR-off transaction, graph/shader
generations 18/3 retained fingerprint
`211f61d4359b8734192266bf126c2f5b1f058901964ac3ddf936665b332c663e`.
A bounded client-only bolt selected
`ENTITIES_TRANSLUCENT/LIGHTNING/gbuffers_lightning/POSITION_COLOR/LIGHTNING`
with empty rejection/fallback maps and the stable 40/7/106/0 resource family.
Removing the exact entity stopped that route at 654 binds across frames 781
through 1,029.

The concurrent controller again re-enabled HUD/hitbox presentation within the
two frames between reference preparation and capture, while retaining the
camera inside another entity. This checkpoint therefore claims the exact
linked producer route, transformed normal ABI, cleanup, and resource
stability, but not a checked lightning pixel reference.

## Producer-derived textured-quad face-domain checkpoint

The shared world `POSITION_TEXTURE` quad boundary now retains the UV center of
the submitted face in addition to its producer-derived normal and tangent.
`SimpleTextureMeshBuilder.addQuad` averages all four submitted UV corners and
stores that same float2 at location 6 for every vertex. Direct `addVertex`
callers receive the current UV as a bounded zero-radius fallback, preserving
screen-space callers that do not consume the world material domain.

Modern generic-entity and entity-flame bridges pass this retained midpoint to
`minosoftPrepareEntityUv`. Complementary can therefore derive nonzero
`midCoord`, signed face direction, absolute face radius, and local calculated-
PBR coordinates for projected shadows and the individual flame sheets instead
of collapsing every sample to the face center. The beacon bridge retains the
same location-6 producer ABI for compatible specializations; Complementary
r5.8.1's selected beacon program does not currently consume the corresponding
entity calculated-PBR varyings. Billboard text remains on its distinct
transformed-font-plane ABI.

Focused horizontal-shadow, vertical-beacon, mirrored-flame, midpoint, and
transformer tests pass, as do the exact untouched Complementary archive with
`WORLD_SPACE_REFLECTIONS=1` and focused real shadow/flame/beacon integrations.
Source supervision replaced the client with PID 36336. After re-resolving the
endpoint and completing the expected caller-deadline WSR-off transaction, the
clean graph/shader generation 18/3 retained fingerprint
`211f61d4359b8734192266bf126c2f5b1f058901964ac3ddf936665b332c663e`.
A forced-visible item plus ordinary flame, billboard, projected-shadow, and
beacon producers selected:

- `ENTITIES_TRANSLUCENT/TEXTURED_LIT/gbuffers_entities/POSITION_TEXTURE/GENERIC_TEXTURE`;
- `ENTITIES/ENTITY/gbuffers_entities/POSITION_TEXTURE/ENTITY_FLAME`;
- `ENTITIES/ENTITY/shadow/POSITION_TEXTURE/ENTITY_FLAME`;
- `ENTITIES_TRANSLUCENT/ENTITY/gbuffers_entities/BILLBOARD_TEXT/BILLBOARD_TEXT`;
- `BLOCK_ENTITIES_TRANSLUCENT/BEACON_BEAM/gbuffers_beaconbeam/POSITION_TEXTURE/BEACON_BEAM`.

Rejection and fallback maps remained empty with 40 textures, seven
framebuffers, 106 programs, and zero shader objects. Cleanup restored the
item's exact fire, name, name-visible, and visibility state and restored the
beacon position to air. Flame main/shadow remained exactly 1,080, beacon
remained 1,155, and billboard remained 2,050 across frames 1,955 through
1,976; the asynchronous beacon remesh returned the visible block-entity count
from two to one. Because the concurrent controller can change camera and
presentation state, this checkpoint claims the linked producer domains,
cleanup, and resource stability, not a new same-pose pixel reference.

## Producer-derived leash ribbon normal checkpoint

Complementary's `gbuffers_basic` fragment skips directional lighting only for
its `GBUFFERS_LINE` specialization. Leashes select the non-line basic program,
which consumes the vertex `normal` in `DoLighting`; Minosoft's light/color
bridge previously supplied positive world Y for every crossed ribbon face.
That made the route executable while lighting every leash segment as a
horizontal surface.

`EntityLeashProjector.RibbonQuad` now retains the normalized front-winding
normal derived from its submitted `(first0, first1, second1)` triangle.
Collapsed vertical or zero-length segments receive distinct positive-Z and
positive-X finite fallbacks for the two crossed ribbon orientations.
`LightColorMeshBuilder` appends that float3 at location 3, and only the
`LIGHT_COLOR` specialization consumes it. Ordinary `POSITION_COLOR` debug
lines keep their existing ABI and harmless world-up compatibility value
because Complementary excludes them from the lighting branch.

Focused leash geometry and complete transformer tests, the exact untouched
Complementary WSR-enabled archive, and the real leash-feature integration pass
on Java 17. Source supervision replaced the client with PID 40205. After
re-resolving the endpoint and returning to clean WSR-off generation 18/3, a
reversible cat-to-player leash selected
`ENTITIES/LEASH/gbuffers_basic/POSITION_COLOR_LIGHT/LIGHT_COLOR` 1,236 times
with empty rejection and fallback maps and the stable 40/7/106/0 shader
resource family. Cleanup restored the exact prior holder and visibility
override; the route remained exactly 1,236 across frames 2,292 through 2,320.
Concurrent camera ownership again prevents a checked same-pose pixel claim.

## Producer-derived world-border face-normal checkpoint

Complementary's `gbuffers_textured` fragment always runs its world lighting
path and consumes `normal`. The shared textured fallback correctly uses a
camera-plane normal for screen-space overlays, but the `WORLD_BORDER`
specialization previously inherited that same positive-Z value for all four
vertical force-field faces.

`WorldBorderMeshBuilder` now appends one inward, front-winding float3 normal to
each face: positive Z for north, negative Z for south, positive X for west,
and negative X for east. The world-border specialization alone consumes
location 3 and overwrites the screen-overlay fallback after submitting its
ordinary position/UV/color state. Screen-space `POSITION_TEXTURE_2D` meshes
keep their unchanged camera-plane contract.

Focused face-direction and complete transformer tests plus the exact untouched
Complementary WSR-enabled archive pass. Source supervision replaced the client
with PID 42072. After re-resolving it and restoring clean WSR-off generation
18/3, the radius-64 reversible canary selected
`WORLD_OVERLAY/TEXTURED/gbuffers_textured/WORLD_BORDER/WORLD_BORDER` with
empty rejection/fallback maps and 40/7/106/0 shader resources. Restoring the
exact prior center, radius, and presentation gate stopped the route at 615
binds across frames 1,146 through 1,150. This checkpoint establishes the
consumed face direction and cleanup; concurrent camera ownership still leaves
the isolated same-pose border pixel reference open.

The subsequent ABI audit found no other active Complementary material program
fed by a fabricated surface direction. Remaining constant normals belong to
sky-color geometry or screen-space `POSITION_TEXTURE_2D` overlays; weather,
damaged-block, beacon, and glint programs in the selected archive do not
consume a host surface normal. Non-Apple WSR execution and cross-driver pixels
remain capability/evidence gates rather than missing producer attributes.

## Face-addressed fullscreen block-atlas checkpoint

The exact non-Apple Complementary high-feature planner gate exposed a boundary
that the earlier source-native reflection bridge did not cover. With
`COLORED_LIGHTING=128;WORLD_SPACE_REFLECTIONS=1`, `world0/composite.fsh`
actively uses `atlasSize`, the global `textureAtlas` resource alias, and the
legacy `tex` alias from its inlined Integrated PBR material logic. A fullscreen
composite has no draw-local texture-array index, so selecting any one
`uTextureSizes[]` entry would be incorrect for mixed-resolution reflected
faces. The existing fail-closed rejection was therefore valid, but the
source-native WSR bridge was incomplete.

The reflection face SSBO was already retaining an eight-bit array and 16-bit
layer beside its logical UV bounds. The transformer now treats that face
identity as the authority for fullscreen sampling and dimensions:

- option-preprocessed stage inspection applies face-data rewriting only where
  the relevant reflection code is active;
- `textureAtlas` LOD samples and active WSR `tex` samples route through compact
  `uTextures[]` switches using the recovered array/layer;
- `atlasSize` and `gtextureSize` in the reflected-material scope derive from
  that same array rather than a fabricated draw-wide atlas;
- generated fullscreen sampling helpers retain the static three-page LabPBR
  coordinate/size mapping during physical-array specialization; and
- fullscreen/final programs that retain `uTextures` compile with the all-array
  layout, bind it on activation, and reserve those physical units from pack
  samplers.

This does not relax the general texture-size rule: an unmarked scene or terrain
stage with a genuinely ambiguous draw-wide `atlasSize` still rejects before
publication. Focused transformer tests cover injected sampling, face-addressed
size replacement, `texture2DLod`, compact static-array specialization, and the
planner-visible fullscreen contract.

The complete `IrisLegacyShaderTransformerTest` and
`IrisShaderPackPlannerTest` suites pass on Java 17. The untouched
Complementary Unbound r5.8.1 archive also crosses the complete host-contract
gate under the exact simulated non-Mac configuration
`COLORED_LIGHTING=128;WORLD_SPACE_REFLECTIONS=1`, retaining `wsr_img`,
`wsr_lod_img`, `bufferObject.0`, and `shadowcomp.csh`. The external test now
models the pack's real prerequisite: WSR writable resources are expected only
when colored lighting is positive and the Mac guard is absent.

`OpenGlIrisCustomResourceComputeTest` is an opt-in production-path fixture. It
creates a hidden GLFW context, realizes the actual Iris custom image and SSBO
owners, dispatches a compute shader, reads both sentinels, and verifies tracked
retirement. On Apple it opens the requested 4.1 context and skips at the
expected missing image/compute/SSBO capability boundary. Run it on a non-Apple
OpenGL 4.3+ worker with:

```sh
MINOSOFT_OPENGL_IRIS_COMPUTE_TEST=true \
JAVA_HOME=/path/to/java17 \
./gradlew :test -x :debug-core:test \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.OpenGlIrisCustomResourceComputeTest
```

The supervised Apple client was rebuilt after this bridge and left running as
PID 62545 under parent PID 62404. After the expected caller-deadline reload
response, WSR-off graph/shader generations 18/3 published fingerprint
`e10ceeca1d1c04bb72783df25fbf082106215ba1aa639f0105932db2a82b8e2d`.
Frames advanced from 402 to 476 with empty custom-resource,
rejected-scene, and fallback-scene maps; zero shader leases; and the clean
40-texture/seven-framebuffer/106-program/zero-shader ledger. This proves the
new source-native planner path does not regress the Apple fallback generation.

That real-driver dispatch plus checked WSR pixels and valid/invalid generation
retirement remains the next acceptance gate; the planner, ownership, program
ordering, source-native sampling, and binding contracts are established.

## Pinned-frame semantic ownership checkpoint

The render-producer audit found that every built-in world element enters the
graph through `withScene`, but the registry still had one generic escape hatch:
a source-level frame callback could bind any `SCENE_GEOMETRY` host shader while
no semantic was active. Because the frame already pinned the Iris generation,
that bind silently restored the host program and bypassed the pack without
appearing in either the selected or fallback route ledgers.

`ShaderPipelineRegistry.bindShader` now fails closed for that exact state. A
scene-geometry shader bound during an Iris-owned pinned frame must be inside a
semantic pass or an explicit renderer-owned `withInternalTarget` scope.
Resource loading outside the frame remains legal, as do `INTERNAL_COMPOSITE`
HUD/presentation shaders and the built-in/headless pipeline with no active
shader-pack plan. A future Fabric/mod producer must therefore declare its graph
semantic instead of accidentally drawing a second host-shaded copy into the
pack frame.

The focused registry predicate covers the rejected unscoped bind and the
accepted semantic, internal-target, and internal-composite paths. The exact
Complementary Unbound r5.8.1 high-feature planner/transformer gate also passes
with `WORLD_SPACE_REFLECTIONS=1`, `COLORED_LIGHTING=128`, and the non-Mac
environment branch.

Source hot reload published client PID 65877 while the existing parent/server
remained supervised. At frames 370 through 731 the client stayed joined and
render-ready with fingerprint
`e10ceeca1d1c04bb72783df25fbf082106215ba1aa639f0105932db2a82b8e2d`,
54 main and six shadow scene programs, progressing entity/block-entity,
particle, sky, cloud, hand, and shadow contracts, all eight fullscreen stages,
empty rejection/fallback maps, zero shader leases, and a stable
40-texture/seven-framebuffer/107-program/zero-shader ledger. No unscoped scene
bind was exposed by the producer-rich live frame.

## Lexical specialized-family and item-entity checkpoint

The next producer canary invalidated an assumption in the earlier route
evidence. `Shader.use(family)` kept a specialization only for its first bind.
Every subsequent uniform setter calls `use()` before uploading, and skeletal
mesh helpers call it again before drawing. Those legitimate re-entrant binds
therefore selected the shader's default family. A route counter could show
`ENTITY_EYES`, `ARMOR_GLINT`, or `HAND_WATER` even though the actual mesh draw
used the ordinary entity/hand program.

`Shader.withProgramFamily` now holds the family override lexically around the
complete uniform-and-mesh submission. Re-entrant binds within that scope
retain the same effective contract, and the override is removed in `finally`.
All specialized call sites now use that boundary: entity hitbox lines,
skeletal emissive/additive layers, player emissive and glint passes, vanilla
armor and armor glint, ordinary and Gecko first-person translucent item
layers, and ordinary item-entity geometry. The one-shot overload was removed
so a new producer cannot repeat the same partial-bind error.

The host-contract inventory also found that ordinary item models used
`BlockShader`, forcing dropped and item-display entities through
`gbuffers_block`. Pinned Iris 1.7.2 bytecode shows `ProgramId.Item` has no
`ShaderKey`; it is a dormant program ID, not the vanilla dropped-item route.
Minosoft now preserves the existing `BLOCK_FEATURE/BLOCK` vertex/state ABI but
lets `ItemFeature` request `SceneProgramFamily.ENTITY`. Modern entity programs
carry the matching block-feature bridge, including retained array/layer UV,
normal/tangent, model matrix, tint, outline, entity ID, and PBR face inputs.
Actual moving blocks and primed TNT continue to request the block family.

Focused fallback and transformer suites, held-item material coverage, vanilla
armor support, `compileKotlin`, and the exact non-Mac high-feature
Complementary Unbound planner/transformer gate pass.

`render.prepare-item-entity` is the reversible production canary. On
hot-reloaded client PID 73811 it inserted client-only diamond entity
`-2000000004`. At frame 181 the only block-feature item contracts were:

- `ENTITIES_TRANSLUCENT/ENTITY/gbuffers_entities/BLOCK_FEATURE/BLOCK`; and
- `ENTITIES_TRANSLUCENT/ENTITY/shadow/BLOCK_FEATURE/BLOCK`.

There was no parallel `BLOCK/gbuffers_block` bind. Removal at frame 784 stopped
both counters at 6,050 through frames 798 and 812. Rejected/fallback maps
remained empty, leases remained zero, and the stable real-GL ledger was
40 textures, seven framebuffers, 111 programs, and zero shader objects.
After removing the dormant `ITEM` family from the selectable catalog, source
hot reload published client PID 75129. A second enable/sample/disable cycle at
frames 714–740 retained only the same entity-family main/shadow contracts with
empty rejection/fallback maps; the joined/render-ready client was left
running.

Bind evidence is no longer the terminal acceptance surface. The production
OpenGL vertex buffer now calls `ShaderPipelineRegistry.recordDraw` immediately
before `glDrawArrays`/`glDrawElements`. The registry snapshots the active
semantic, effective contract, fallback, and selected shader; the Iris pipeline
publishes bounded `submittedSceneDraws` and `submittedSceneVertices` maps keyed
by semantic/family/program/vertex/state ABI. Internal targets, HUD/composites,
terrain's independent submission path, and zero-vertex buffers do not enter
this scene ledger.

On the rebuilt client PID 76397, a further diamond cycle proved the final
driver-bound contract rather than only its bind history. At frame 476 both
main and shadow entity-family item routes reported 11 draws/66 vertices. At
disable frame 1,033 they reached 568 draws/3,408 vertices each, then remained
exactly unchanged through frames 1,046 and 1,059. No block-family item draw was
recorded. Rejection/fallback maps stayed empty, shader leases stayed zero, and
the 40/7/111/0 resource ledger remained stable.

## Driver-bound emissive-eye and armor-glint checkpoint

The lexical-family correction invalidated bind-only acceptance for specialized
skeletal layers, so both existing reversible canaries were replayed against the
new OpenGL submission ledger. On client PID 76397,
`render.prepare-entity-eyes` selected retained villager 3954. The active
Complementary generation submitted
`ENTITIES_TRANSLUCENT/ENTITY_EYES/gbuffers_spidereyes/SKELETAL/SKELETAL_TINTED`;
the route reached 895 draws/451,080 vertices and remained unchanged across
frames 8,804 and 8,869 after exact emissive/visibility restoration. Rejection
and fallback maps stayed empty.

The first armor replay exposed a real false positive. The requested
`ARMOR_GLINT` family had no compiled Complementary bridge for Minosoft's
physical `PLAYER_SKELETAL/PLAYER` mesh, so actual submissions were recorded as
`ENTITIES/ARMOR_GLINT/host:PlayerShader/PLAYER_SKELETAL/PLAYER` and the Iris
fallback ledger advanced. `MODERN_GLINT_VERTEX_BODY` now declares and
implements the complete player ABI: retained bone transforms, feature and skin
part masks, inflation, explicit packed glint texture array/layer, tint, and the
canonical player-state uniform surface. The exact non-Mac high-feature
Complementary planner/transformer gate and focused transformer suite pass.

The failed intermediate bridge was automatically source-reloaded before its
canonical uniform metadata was corrected, so Iris validation intentionally
rejected that candidate and the supervisor stopped the client. The corrected
trajectory was relaunched with the same `fabric-stack` modpack and
Complementary pack. Client PID 80773 compiled
`gbuffers_armor_glint/PLAYER_SKELETAL/PLAYER` alongside the two skeletal
variants and submitted 1,244 checked draws/268,704 vertices through the exact
pack program with empty rejection/fallback maps.

That run also exposed stale retained decoration state: clearing all armor made
the owner invisible before `VanillaArmorFeature.update` could empty its four
entries, while the separately registered decoration feature continued drawing
those entries. The owner now remains updateable for one final synchronization
frame whenever retained entries exist. A focused cleanup predicate test passes.
After source reload, client PID 81788 again selected only
`ENTITIES/ARMOR_GLINT/gbuffers_armor_glint/PLAYER_SKELETAL/PLAYER`. Restore
cleared the client-only stacks, then the driver-bound route stopped at 2,568
draws/554,688 vertices across frames 1,118 and 1,176. Rejection/fallback maps
were empty. Parent PID 80637 and server PID 80660 remained ready, and the
joined/render-ready client was left running.

## Complete main-view scene ABI submission checkpoint

Program compilation and selection are not sufficient evidence that a producer
reaches the driver with the intended contract. `render.substrate` therefore
publishes four machine-readable coverage sets derived from actual main-view
`recordSceneDraw` calls:

- `submittedMainSceneVertexAbis`
- `submittedMainSceneStateAbis`
- `unsubmittedCompiledSceneVertexAbis`
- `unsubmittedCompiledSceneStateAbis`

The missing sets are the compiled scene-key ABI sets minus ABIs observed at
`glDrawArrays` or `glDrawElements`. Terrain is deliberately excluded because
it has its own backend submission ledger and material-specific route counters;
fullscreen families are generation-owned render-graph passes rather than scene
contracts.

Reversible production-path canaries on Complementary Unbound r5.8.1 first
proved these individual driver-bound routes on client PID 81788:

- held water:
  `HAND/HAND_WATER/gbuffers_hand/HELD_ITEM/HELD_ITEM`, stopped at 821
  draws/59,112 vertices;
- lightning:
  `ENTITIES_TRANSLUCENT/LIGHTNING/gbuffers_lightning/POSITION_COLOR/LIGHTNING`,
  stopped at 766/101,112;
- weather:
  `WEATHER/WEATHER/gbuffers_weather/WEATHER/WEATHER`, stopped at 509/24,432;
- beacon:
  `BLOCK_ENTITIES_TRANSLUCENT/BEACON_BEAM/gbuffers_beaconbeam/POSITION_TEXTURE/BEACON_BEAM`,
  stopped at 684/32,832;
- billboard text:
  `ENTITIES_TRANSLUCENT/ENTITY/gbuffers_entities/BILLBOARD_TEXT/BILLBOARD_TEXT`,
  stopped at 614/69,996;
- primed TNT main and shadow:
  `ENTITIES_TRANSLUCENT/BLOCK/gbuffers_block/BLOCK_FEATURE/FLASHING_BLOCK`
  and its `shadow` counterpart, each stopped at 1,106/39,816;
- world border:
  `WORLD_OVERLAY/TEXTURED/gbuffers_textured/WORLD_BORDER/WORLD_BORDER`,
  stopped at 700/16,800;
- damaged block:
  `WORLD_OVERLAY/DAMAGED_BLOCK/gbuffers_damagedblock/DAMAGED_BLOCK/DAMAGED_BLOCK`,
  stopped at 803/28,908; and
- translucent particle:
  `PARTICLES_TRANSLUCENT/PARTICLE/gbuffers_particles/PARTICLE_POINT/PARTICLE`,
  stopped at 805/805.

Three phase-dependent producers received bounded renderer-only gates so they
could be proved without changing synchronized world state. The fixed-sky
canary reuses the already-loaded End texture through the production skybox
renderer. Client PID 86603 submitted
`SKY/SKY_TEXTURED/gbuffers_skytextured/SKY_TEXTURE/SKY_TEXTURE` and stopped at
795/28,620 after restoration, while ordinary `SKY_BASIC` drawing resumed. The
first-person fire canary does not change player fire state; client PID 87725
submitted
`WORLD_OVERLAY/TEXTURED/gbuffers_textured/POSITION_TEXTURE_2D/GENERIC_TEXTURE_2D`
and stopped at 598/7,176. The sun-scatter canary preserves the production
matrix, mesh, position, intensity, and shader route while bypassing only its
time/weather visibility gate, then restores that gate exactly.

For the terminal same-generation coverage check, client PID 89664 ran the
entity-flame, leash, vanilla-armor, item-entity, translucent-held-item,
billboard, primed-TNT, beacon, lightning, weather, world-border, block-break,
translucent-particle, fire-overlay, fixed-sky, and sun-scatter canaries. At
frame 3,635 the submitted main-view state ABI set was:

`ARM`, `BEACON_BEAM`, `BILLBOARD_TEXT`, `BLOCK`, `CLOUD`, `COLOR`,
`DAMAGED_BLOCK`, `ENTITY_FLAME`, `FLASHING_BLOCK`, `GENERIC_TEXTURE`,
`GENERIC_TEXTURE_2D`, `HELD_ITEM`, `LIGHTNING`, `LIGHT_COLOR`, `PARTICLE`,
`PLANET`, `PLAYER`, `SKELETAL_LIGHTMAP`, `SKELETAL_TINTED`, `SKY_COLOR`,
`SKY_TEXTURE`, `SUN_SCATTER`, `WEATHER`, and `WORLD_BORDER`.

The submitted main-view vertex ABI set was:

`ARM_SKELETAL`, `BILLBOARD_TEXT`, `BLOCK_FEATURE`, `CLOUD`, `DAMAGED_BLOCK`,
`HELD_ITEM`, `PARTICLE_POINT`, `PLANET`, `PLAYER_SKELETAL`, `POSITION_COLOR`,
`POSITION_COLOR_LIGHT`, `POSITION_TEXTURE`, `POSITION_TEXTURE_2D`, `SKELETAL`,
`SKY_POSITION`, `SKY_TEXTURE`, `SUN_SCATTER`, `WEATHER`, and `WORLD_BORDER`.

Both unsubmitted compiled sets were empty. Rejected and fallback maps were
empty, shader leases were zero, and the GPU ledger remained 40 textures,
seven framebuffers, 112 linked programs, and zero shader objects. Every canary
restored its exact prior client state. The independent terrain ledger continued
to advance opaque/cutout `gbuffers_terrain`, translucent `gbuffers_water`, and
opaque/cutout `shadow` routes; all fullscreen program families also continued
through the pinned graph.

This closes driver-bound coverage for the currently compiled main-view scene
ABI surface, not the complete Iris trajectory. A real multi-dimension
transition, independent shader-pack acceptance, and a non-Apple OpenGL 4.3
pixel gate for custom images, compute programs, shader-storage buffers, and
world-space reflections remain open.

## Non-Apple OpenGL 4.3 CI gate

The repository's Ubuntu CI matrix now owns the previously manual backend gate.
Its Linux-only steps install Mesa and Xvfb, force the llvmpipe OpenGL 4.5 and
GLSL 4.50 profiles, download Complementary Unbound r5.8.1 from its pinned
Modrinth URL, and verify the manifest's SHA-512 before using the archive.
Inside the same X server, Gradle runs:

- `OpenGlIrisCustomResourceComputeTest`, which realizes the production custom
  image and SSBO owners, dispatches a compute shader, reads image and storage
  sentinels, and proves resource retirement; and
- the exact external-pack planner test with
  `WORLD_SPACE_REFLECTIONS=1;COLORED_LIGHTING=128` and the non-Mac environment,
  which requires the WSR images, buffer 0, and `shadowcomp.csh` contract.

`MINOSOFT_OPENGL_IRIS_COMPUTE_REQUIRE_CAPABILITIES=true` changes the fixture's
capability boundary from an assumption to an assertion. A Linux runner that
falls back below clearable images, compute, or shader-storage support therefore
fails instead of silently skipping. The opt-in local Apple invocation continues
to skip at its correctly reported OpenGL 4.1 boundary. `actionlint`, YAML
parsing, the local Apple boundary invocation, and the exact non-Mac
Complementary planner gate pass. An observed Ubuntu workflow result and
checked world-space-reflection pixels remain the acceptance evidence still
needed to mark the cross-driver lane complete.

## Real dimension-transition acceptance hook

The managed server exposes all three 1.20.4 worlds, but the connected client's
command tree is empty. A normal slash-command attempt therefore emitted an
unsigned command packet and then failed locally with the expected empty-stack
diagnostic; it did not move the player and cannot serve as reproducible
dimension evidence.

The Fabric debug bridge now registers `world.teleport-player` as a narrow
server-thread operation. It selects one exact connected name/UUID, or the sole
connected player when omitted; requires an already loaded target dimension;
accepts only finite coordinates within ±30,000,000; retains the current
yaw/pitch unless finite overrides are supplied; and returns exact previous and
current dimension/position/rotation records. This intentionally avoids a
general command executor while making Overworld → Nether/End → Overworld
restoration deterministic. Focused input-boundary tests pass. The operation
will appear after the next debug-server bridge reload; live shader generation,
program-directory, resource-retirement, route, and pixel evidence remain open.

## Complementary three-dimension live transition

The first real dimension probe exposed a lifecycle violation rather than a pack
mapping problem. `BEFORE_WORLD_RENDER` originally ran inside the first graph
pass, after `ShaderPipelineRegistry.withFramePipeline` pinned the previous
generation. Candidate preparation therefore reused the old pipeline and its
fail-closed scene-semantic guard correctly rejected a host geometry shader
bind. The event now runs immediately before frame-pipeline acquisition.
Candidate compilation/publication and graph rebuild complete first; the same
draw then acquires the replacement generation and executes its rebuilt graph.
The `AFTER_WORLD_RENDER` cleanup remains paired on normal and exceptional
paths.

Complementary's End `deferred1` then exposed a separate transformed-source
gap: its fullscreen vertex reads legacy `gl_Fog.start`, which becomes
`fogStart`, but only fragment stages synthesized the normalized core fog
declaration. Modern fullscreen vertex transformation now declares any
referenced core fog input exactly once. The focused
`IrisLegacyShaderTransformerTest` passes.

Trajectory `complementary-unbound-blend-2026-07-28` hot-reloaded client PID
3653 while retaining parent PID 99277 and server PID 99310. With
Complementary Unbound r5.8.1, one uninterrupted live cycle published:

| World | Program directory | Graph generation | Shader generation |
| --- | --- | ---: | ---: |
| Overworld | `world0` | 18 | 3 |
| Nether | `world-1` | 19 | 4 |
| End | `world1` | 20 | 5 |
| restored Overworld | `world0` | 21 | 6 |

Every checkpoint reported zero active leases, zero retired generations awaiting
leases, empty rejected/fallback bind maps, and the same 40 live textures, seven
framebuffers, 111 linked programs, and zero shader objects. Created/deleted
counts advanced at every transition while the live counts returned to that
baseline, proving old generation retirement instead of accumulation. The final
source/documentation reload activated client PID 4960 without replacing the
parent or server. That client and the server both report the restored Overworld pose
`(-172.32293423405733, 63.0, -128.02169906198708)`, yaw `175.9983`, pitch
`13.878889`; it selected `world0` with the same resource/rejection baseline,
all managed readiness fields remain true, and the client was left running.
Nether and End framebuffer captures were also produced outside the repository
for visual inspection.

This closes the real legacy three-directory dimension transition gate. It does
not close independent shader-pack acceptance or the observed non-Apple OpenGL
4.3 pixel gate.

## Complementary translucent-shadow terrain checkpoint

Pinned Iris 1.7.2 treats `shadowTranslucent` as a default-true terrain producer
choice. Its shadow renderer draws opaque/cutout terrain and entity/block-entity
casters first, copies the resulting depth for `shadowtex1`, and only then draws
translucent terrain into `shadowtex0`. The former Minosoft plan rejected
`shadowTranslucent=true` and copied shadow depth after every caster, so both
shadow depth samplers described the same final depth.

The immutable shadow plan now retains the default-true
`translucentTerrain` choice. Program selection requires the generic `shadow`
fallback for that material, and the graph executes this exact order:

1. opaque and cutout terrain;
2. enabled entity and block-entity caster producers;
3. `SHADOW_BEFORE_TRANSLUCENT`, copying `shadowtex0` to `shadowtex1`; and
4. translucent terrain through the selected shadow program.

`render.substrate.shaderPrograms.depthSnapshots` counts the main and shadow
copy boundaries independently, while shadow routing, selected terrain binds,
and the terrain submission ledger expose the planned and executed material
path.

Trajectory `complementary-unbound-blend-2026-07-28` hot-reloaded client PID
10760 while parent PID 99277 and server PID 99310 remained stable. At frame 298
the untouched Complementary Unbound r5.8.1 `world0` generation reported:

- `shaderShadowRouting.translucentTerrain=true`;
- `shadowTerrainPrograms.translucent=shadow`;
- `depthSnapshots.shadow_before_translucent=299`;
- a current-frame `minosoft:shadow/translucent` submission and 299 selected
  `minosoft:shadow/translucent/shadow` binds;
- 90 visible translucent meshes containing 201,288 vertices;
- empty rejected and fallback scene-bind maps; and
- zero active/retired leases with 40 live textures, seven framebuffers, 111
  linked programs, and zero shader objects.

The capture `/tmp/minosoft-iris-shadow-translucent.png` was inspected locally
after that same generation. Focused planner/fallback/render-target tests pass,
including the exact main/shadow depth-buffer mapping. Main compilation passes.
The later producer-complete checkpoint restored `compileIntegrationTestKotlin`
and passed the focused `RendererPipelineTest`/`ChunkRendererTest` integration
matrix.

This closes the pinned `shadowTranslucent` producer, fallback, depth-separation,
and live Complementary execution boundary. It does not claim an authored
translucent-shadow pixel reference or independent cross-driver parity.

## Exact entity and block-entity shadow fallbacks

The former planner parsed `shadowPlayer` and `shadowLightBlockEntities` only to
reject their fallback-only configurations, then discarded both values for
accepted plans. It also incorrectly defaulted `shadowPlayer` to true.

`IrisShadowDirectives` now retains the exact pinned defaults and fallback
semantics:

- general entity shadows include the player;
- `shadowPlayer=true` adds only the player when general entities are disabled;
- general block-entity shadows include light emitters; and
- `shadowLightBlockEntities=true` adds only block entities whose retained block
  state has nonzero luminance when general block entities are disabled.

The graph schedules one entity or block-entity layer when either corresponding
choice is enabled. `EntityDrawer` filters physical features by `PlayerEntity`;
the chunk block-entity loop filters by `BlockState.luminance`. Main-view
submission is unchanged and neither path repeats preparation. The debug
shadow-routing object exposes all four retained values.

Focused planner tests cover player-only and light-block-entity-only choices;
graph gates cover their disabled/enabled combinations. The managed
Complementary client hot-reloaded to PID 14699 with stable parent/server PIDs.
Its default live plan now correctly reports `entities=true, player=false`,
`blockEntities=false, lightBlockEntities=false`, empty rejected/fallback maps,
advancing main/shadow depth snapshots, and the unchanged
40-texture/7-framebuffer/111-program/0-shader ledger. Checked live fallback-only
toggles remain the acceptance gate for per-class submission counts.

## Complementary shadow projection directives

The active Complementary source declares `shadowDistance=192.0`, but the former
frame capture derived a radius from Minosoft's camera far plane and clamped it
to 160. Its shadow render-resource descriptor was also independently hard-coded
to 1024 even when the parsed logical shadow buffers selected another
`shadowMapResolution`.

The immutable shadow plan now retains bounded preprocessed
`shadowDistance`, `shadowNearPlane`, `shadowFarPlane`, `shadowMapFov`, and
`shadowIntervalSize` values with pinned Iris defaults. Orthographic projection
uses the authored distance and planes; perspective projection uses the authored
FOV and Iris's legacy 0.05/256 depth range. The interval path applies Iris's
float-narrowed signed-remainder/half-cell camera-center rule. The light view
now uses the pinned Iris baseline transform in the same order: translate
`-100` on Z, rotate 90 degrees around X, rotate the derived sky angle around Z,
then apply `sunPathRotation` around X. Minosoft's render-origin-relative
vertices receive only the corresponding origin correction before the snapped
camera translation. The graph resource descriptor now takes its size from the
same `shadowtex0` buffer plan used for OpenGL allocation, removing the duplicate
fixed-size truth.

Focused tests prove 192/0.25/384/FOV-75 planning, a 2048 target propagated to
the graph resource plan, orthographic and perspective matrix terms, and the
positive/negative interval-center behavior. Managed client PID 20525 reports
the live Complementary values `distance=192`, `nearPlane=0.05`,
`farPlane=256`, `mapFov=null`, and `intervalSize=2`. At frame 993 both main and
shadow depth snapshots had advanced 994 times; all three shadow terrain routes
advanced 994 times; rejected/fallback maps were empty; and the stable ledger
remained 40 textures, seven framebuffers, 111 programs, and zero shader
objects. The inspected
`/tmp/minosoft-iris-shadow-interval-2.png` capture retains the newly resolved
ground/building shadow contrast rather than the earlier overexposed field.

A subsequent focused fixture evaluates Iris's own dawn camera and interval
inputs through Minosoft's matrix convention with a tighter-than-upstream
threshold. The supervised stack hot-reloaded to client PID 23797 while retaining
parent PID 99277 and server PID 99310. At frame 2,147 Complementary reported its
authored `sunPathRotation=-40`, the same 192/0.05/256/no-FOV/2 shadow contract,
all three shadow terrain submissions and both depth snapshots at 2,148, empty
rejection/fallback maps, and the unchanged 40/7/111/0 resource ledger. The
inspected `/tmp/minosoft-iris-shadow-baseline-view.png` is nonempty, keeps the
saved player pose exactly, and shows coherent daylight sky, water, terrain, and
building contrast after the baseline transform.

Pinned Iris also accepts the legacy block-comment aliases `SHADOWRES`,
`SHADOWHPL`, and `SHADOWFOV` before applying modern const declarations. The
planner now preserves those comments through option preprocessing, validates
one value per legacy family, and gives `shadowMapResolution`,
`shadowDistance`, and `shadowMapFov` the same later override precedence.
Focused coverage proves both a legacy-only 1536/96/FOV-68 plan and modern
2048/192/FOV-75 overrides. The untouched Complementary r5.8.1 archive also
passes the complete external planner contract with
`RP_MODE=3;SHADOW_QUALITY=1`.

Complementary itself declares `shadowDistanceRenderMul=1.0` and
`entityShadowDistanceMul=0.125`. Those authored values now remain in the
immutable generation and expose 192-block terrain and 24-block entity/block-
entity distance limits. The distance-culler component uses section bounds for
terrain, interpolated entity bounds for entity features, and Iris's exact
block-position ±1 box for block entities; negative multipliers retain the
existing host-distance gate, zero culls the corresponding class, and a
distinct entity box is created only under Iris's pinned multiplier rules.

Client PID 26800 reported the exact 1.0/0.125 and 192/24 values with the stable
40/7/111/0 resource ledger and all six main/shadow terrain submissions. At
frame 2,820 no currently visible ordinary entity intersected the 24-block box,
so no entity-shadow contract was selected. A reversible primed-TNT canary four
blocks from the unchanged player pose then selected both
`ENTITIES_TRANSLUCENT/BLOCK/gbuffers_block/.../FLASHING_BLOCK` and
`ENTITIES_TRANSLUCENT/BLOCK/shadow/.../FLASHING_BLOCK`; nearby skeletal
entities also resumed shadow selection. Rejected and fallback maps remained
empty, and disabling the canary removed the exact client-only entity.

The auxiliary view no longer reuses only the main camera's collected
producers. Terrain shadow submissions traverse each unique loaded section
inside the authored distance box and bypass main-view occlusion-query state.
Block entities traverse the same loaded-section set. Entity preparation builds
a distinct shadow collection for eligible bounds even when the normal
visibility result is frustum- or occlusion-culled; a drawable shared by main
and shadow collections is still prepared exactly once. This preserves one
main draw and one auxiliary draw instead of replaying geometry in the main
target.

On hot-reloaded client PID 29305, the only non-player inside Complementary's
24-block entity box was a zombie at `(-175.5,39,-130.5)` whose normal
visibility was explicitly `out_of_frustum`. Nevertheless,
`ENTITIES/ENTITY/shadow/SKELETAL/SKELETAL_TINTED` advanced to 140,425 binds by
frame 5,163. All six main/shadow terrain submissions and both depth snapshots
advanced in the same frame; rejection/fallback maps were empty and the
40/7/111/0 ledger was unchanged. The inspected
`/tmp/minosoft-iris-shadow-independent-visibility.png` remained nonempty and
coherent at the saved player pose.

This closes the const-directive projection, interval stabilization, and
baseline light-view rotation, render-origin correction, and resource-size
consistency boundary used by Complementary. It also closes the legacy
resolution/projection aliases, authored distance-box component, and
main-frustum-independent collection for already loaded terrain, entities, and
block entities.

## Pinned Iris shadow-culling modes and light frustum

Source inspection at Iris commit
`8f668cfe033ef9f128ef0331fff38cc7402032b7` establishes four culling choices.
An explicit `shadow.culling=false` uses only authored distance boxes;
`shadow.culling=true` uses the advanced light frustum; `reversed` adds a
voxel-distance core and distance-box exterior to that advanced fringe. The
default uses the distance box only when the primary `shadow` geometry program
uses voxel images, otherwise it is advanced. At the pinned commit the
primary-shadow geometry path is the only caller that establishes this
voxelization bit; the lower-level custom-image setter has no caller and is not
treated as an executable source.

`IrisShadowCullingVolume` now implements those choices as immutable frame
state. It derives the normalized left/right/bottom/top/near/far planes from the
transposed projection-view product, classifies the back and silhouette edge
planes against the world-space shadow-light direction, and extrudes them with
the same bounded 13-plane construction as Iris. Render-origin-relative host
bounds are adapted to the world-space planes at the culling boundary.
Advanced distance zero culls the complete class. Reversed mode immediately
accepts its inner voxel box, rejects beyond the outer authored distance box,
and applies the advanced planes only in between.

Terrain uses section bounds. Entities use an independent culling volume only
when Iris's nonnegative, non-unit entity multiplier rules require it. Block
entities retain the terrain section traversal but also apply Iris's exact raw
block-position distance box. Complementary therefore produces an advanced
192-block terrain volume and a distinct advanced 24-block entity volume while
retaining its 24-block block-entity distance gate.

One live attempt exposed an important generation boundary: placing producer
preparation inside the semantic draw scope let a particle uniform upload
activate a scene shader before a graph pass and correctly tripped the
outside-pass guard. `ShaderPipelineRegistry.withFramePipeline` now publishes
one immutable active frame across asynchronous preparation, but exposes the
semantic frame pipeline only while the render graph submits draws. Candidate
replacement therefore cannot mix a plan, frame state, or culling volume during
preparation, and preparation cannot masquerade as a scene submission.

Focused `IrisShadowCullingTest`, `IrisShaderPackPlannerTest`, and
`ShaderPipelineRegistryTest` coverage proves inclusive distance bounds,
default voxel selection, camera and light-extruded advanced casters, zero
distance, reversed core/fringe/exterior behavior, independent entity
multipliers, block-entity distance, property parsing, and primary-geometry
voxel detection. The untouched Complementary Unbound r5.8.1 archive passes the
external planner contract with `RP_MODE=3;SHADOW_QUALITY=1`.

Managed client PID 59744 reported `cullingMode=default`,
`voxelizationDetected=false`, `terrain=advanced`, `entities=advanced`,
`distinctEntities=true`, and `blockEntityDistance=24`. By frame 59 it had
submitted 458 physical skeletal entity shadow draws while every main/shadow
terrain route advanced. Rejection and fallback maps were empty, leases were
zero, and the ledger remained 40 textures, seven framebuffers, 111 programs,
zero shader objects, and one renderbuffer. The inspected world capture
`/tmp/minosoft-iris-advanced-shadow-culling-world.png` is nonempty and shows
one coherently shaded scene rather than a duplicated main-view submission.

`compileKotlin`, integration-source compilation, the focused unit gate, and the
focused renderer/chunk integration matrix pass. Loading shadow casters beyond
Minosoft's host render distance, a perspective-specific checked visual, an
independent cross-driver pixel reference, and any future upstream custom-image
voxelization call path remain open.

## Split Gecko block-entity shadow layers

Pinned Iris `ShadowRenderer` renders block entities through one buffer source,
flushes that source before copying the pre-translucent shadow depth, and warns
against a second translucent-entity flush afterward. Its
`MixinBeaconRenderer` separately cancels beacon beams while shadows are active.
This matters in Minosoft because Gecko render layers are retained as a base
block-entity draw plus a distinct main-view translucent/additive draw.

`BlockEntityRenderer.drawShadow` now represents the upstream single-submission
boundary. It always performs the base draw and appends a split translucent
draw only when the renderer explicitly declares `castsTranslucentShadow`.
`GeckoLibBlockEntityRenderer` opts in exactly when its baked model contains a
non-opaque Gecko render layer. Beacon beams and ordinary block-entity renderers
retain the default base-only choice. `ChunkRenderer.drawBlockEntities` invokes
this method inside the existing block-entity draw-state scope after the
existing luminance, distance-box, and frame-pinned light-frustum checks.
There is no new graph element, later translucent shadow phase, second animation
advance, or duplicate main submission.

`BlockEntityRendererTest` proves both sides of the contract: a renderer may
have a translucent main pass without casting it, while an explicit opt-in
draws base then translucent exactly once. The untouched Complementary Unbound
r5.8.1 archive again passes its complete external planner contract with
`RP_MODE=3;SHADOW_QUALITY=1`. Hot-reloaded client PID 87999 retained
Complementary fingerprint `e10ceeca...e2d`, all six main/shadow terrain routes,
empty rejected/fallback maps, zero leases, and the stable 40-texture,
seven-framebuffer, 111-program, zero-shader-object ledger. Its visible skeletal
block entities continue to submit
`BLOCK_ENTITIES/ENTITY/gbuffers_block/SKELETAL/SKELETAL_LIGHTMAP`.

Complementary authors `shadowBlockEntities=false` in this active profile, so
that live run is intentionally not claimed as a positive block-entity shadow
pixel. Overriding the third-party directive merely to increment a counter
would contradict the pack contract. The focused opt-in test supplies the new
path evidence. The later focused independent-pack checkpoint supplies the
positive live block-entity submission without overriding Complementary.
Integration-source compilation and the focused
`RendererPipelineTest`/`ChunkRendererTest` matrix pass.

## Producer-complete Complementary execution checkpoint

Compilation is not accepted as producer support. The client diagnostics compare
the active generation's compiled main-scene vertex/state ABIs with ABIs that
have submitted physical vertices. Reversible debug preparations exercise
phase-, state-, and content-dependent producers through their production
renderer paths, then restore the exact prior state.

On trajectory `complementary-unbound-blend-2026-07-28`, client PID 89479 and
shader generation 2 retained the untouched Complementary Unbound r5.8.1 archive
with fingerprint `e10ceeca...e2d`. The preparations covered flame, billboard
text, outline, entity eyes, leash, vanilla armor, creeper overlay, translucent
held items, world border, fixed sky, sun scatter, fire overlay, weather,
lightning, primed TNT, dropped items, beacon beam, skeletal storage block
entities, glowing sign text, block breaking, translucent particles, and chunk
borders. Each successful preparation was disabled before the next one.

At frame 2,526 the active generation reported:

- empty `unsubmittedCompiledSceneVertexAbis` and
  `unsubmittedCompiledSceneStateAbis`;
- physical main-view submissions for every compiled terrain-independent
  contract, including beacon beam, billboard text, block feature, damaged
  block, entity flame/eyes, held item, leash light color, lightning, player,
  sky texture, sun scatter, weather, and world border;
- current-frame main and shadow submissions for opaque, cutout, and translucent
  terrain, with 404 visible terrain meshes and 597,168 vertices;
- `before_translucent=2526` and
  `shadow_before_translucent=2526` depth snapshots;
- every selected fullscreen program advancing once per frame;
- physical shadow submissions for skeletal entities, players, flame sheets,
  dropped-item/block features, and flashing TNT while respecting the pack's
  authored `shadowPlayer=false` fallback and
  `shadowBlockEntities=false` choice;
- empty rejected and fallback scene-bind maps; and
- 40 live textures, one renderbuffer, seven framebuffers, 111 linked programs,
  and zero live shader objects.

Cleanup returned rain and thunder to zero and preserved the exact player pose
`(-172.32293423405733, 63.0, -128.02169906198708)`,
`yaw=175.9983`, `pitch=13.878889`. The managed parent/server/client remained
ready after the sweep.

The stale integration fixture that attempted one-shot
`fallback.use(SceneProgramFamily.ENTITY_EYES)` specialization now uses the
production lexical `withProgramFamily` scope. `compileIntegrationTestKotlin`
passes, and focused `RendererPipelineTest` plus `ChunkRendererTest` integration
runs pass, covering nested route rebinding, fail-closed unclassified draws,
internal render targets, fog synchronization, hand/overlay ordering, depth
snapshots, fullscreen families, particle ordering, separate entity draws,
skip-all-rendering, shadow directives, terrain-layout rejection, and
block-entity phases.

This closes live execution coverage for every compiled main-scene ABI in the
active official pack, all populated terrain routes, selected physical shadow
routes, and the ordered fullscreen chain. GUI/HUD remains deliberately
presentation-side after Iris final composition, matching the pinned Iris
boundary rather than bypassing the pack with a duplicate world draw.

## Positive independent block-entity shadow checkpoint

Complementary's authored `shadowBlockEntities=false` is not a valid positive
test for the block-entity caster path. A focused project-owned pack now declares
the opposite contract without changing third-party settings:

- terrain, translucent-terrain, entity, player, and light-only block-entity
  shadows are disabled;
- general block-entity shadows are enabled;
- the only shadow scene bridge is
  `SKELETAL/SKELETAL_LIGHTMAP`; and
- a minimal terrain fallback plus final pass keep the production graph
  executable.

`IrisShaderPackPlannerTest` proves that exact immutable plan. The separate
`GeckoLibBlockEntityRendererTest` constructs the actual production renderer and
proves opaque Gecko layers stay base-only while translucent and additive layers
set both `hasTranslucentPass` and `castsTranslucentShadow`. Together with
`BlockEntityRendererTest`, this covers the actual opt-in decision and the
base-then-translucent single-submission order.

The Iris debug provider now exposes a session-only, transactional
`mods.iris.select-pack` operation. It accepts only exact normalized paths
already known to the controller or discovered beneath the configured
`shaderpacks` directory; it cannot load an arbitrary filesystem path.
Previously selected paths remain in the bounded controller catalog so an
acceptance switch can return to its source pack. The response includes the
previous path and option map, allowing exact option restoration. Candidate
planning/linking still uses the shared shader resource-reload transaction: an
attempt to select the concurrently incomplete broad reference fixture failed
linking and left the active Complementary generation untouched.

On managed client generation 6, the focused pack published shader generation 3
with fingerprint `07348e60...bda1`. Its routing diagnostics reported
`blockEntities=true`, every other shadow producer false, and exactly one
compiled shadow scene route: `shadow/SKELETAL/SKELETAL_LIGHTMAP`. A reversible
real chest preparation entered through `World.set`, the normal block-entity
factory/cache, and the retained skeletal renderer. At frame 2,254 diagnostics
reported:

- `BLOCK_ENTITIES/ENTITY/shadow/SKELETAL/SKELETAL_LIGHTMAP` physical
  submissions;
- 10,185 selected binds for that exact shadow program;
- a progressing `shadow_before_translucent` snapshot;
- no rejected scene binds; and
- 21 textures, one renderbuffer, seven framebuffers, 37 linked programs, and
  zero live shader objects.

Because terrain and entity shadows were disabled in the pack, that selected
shadow traffic cannot be attributed to another caster class. Restoring the
prepared chest returned the block state to air. The bounded selector then
returned in-process to the previously known official archive, and a
transactional option reload restored `RP_MODE=3;SHADOW_QUALITY=1`. The final
bounded-catalog reload reached client generation 8 on Complementary fingerprint
`e10ceeca...e2d`, empty
rejected/fallback maps, and the stable 40-texture, one-renderbuffer,
seven-framebuffer, 111-program, zero-shader-object ledger. Parent PID 54146 and
server PID 54167 stayed stable, and the exact player pose remained
`(-172.32293423405733, 63.0, -128.02169906198708)`,
`yaw=175.9983`, `pitch=13.878889`.

This closes the positive pack-authored base block-entity shadow submission and
the production Gecko split-layer opt-in/order boundary. A checked translucent
Gecko block-entity shadow pixel and cross-driver reference remain broader visual
parity gates, not route-ownership gaps.

## Fallback-only player and light-block-entity checkpoints

The same checked fixture now exposes three immutable
`SHADOW_CASTER_MODE` profiles. Mode `0` retains the general block-entity case
above. Mode `1` sets only `shadowPlayer=true`; mode `2` sets only
`shadowLightBlockEntities=true`. Terrain, translucent terrain, general entities,
general block entities, and the other fallback caster are false in both focused
modes. Planner coverage proves the exact directives and retains both compiled
shadow bridges, `PLAYER_SKELETAL/PLAYER` and
`SKELETAL/SKELETAL_LIGHTMAP`.

The first player-only live attempt exposed a real ownership gap rather than a
shader-program failure. `LocalPlayerRenderer` disabled its skeletal model
feature in first person, and `FeatureManager.collectShadow` honored that same
main-camera flag, so the auxiliary collector had no local body to submit.
`EntityRenderFeature.mainViewEnabled` now separates main-camera presentation
from true feature enablement. `LocalPlayerRenderer` toggles only that main-view
flag, while `collectShadow` checks the independent `isShadowVisible` contract.
`EntitiesRenderer` also forwards its independently calculated auxiliary
visibility into `FeatureManager.update`, which advances only actual shadow
drawables when the main camera rejected them; an off-frustum or first-person
caster therefore does not submit a frozen skeletal pose. The focused integration
test proves a main-view-suppressed feature keeps updating and remains in the
shadow collection, while a genuinely disabled feature still fails closed.

On managed hot-reload generation 9, mode `1` published fingerprint
`c63e2b14...bcb`. At frame 8,933 diagnostics reported only the player caster
class enabled and physically submitted:

- `ENTITIES/ENTITY/shadow/PLAYER_SKELETAL/PLAYER`;
- 3,160 selected binds, 210 draws, and 105,840 vertices;
- no block-entity shadow selection; and
- no rejected scene binds.

Mode `2` then published fingerprint `75f9e957...458`. A reversible production
ender-chest canary was placed at `(-173, 63, -131)`. Its retained default block
state has positive luminance, so it is eligible for the pinned light-only
fallback without weakening the runtime filter. At frame 9,305 diagnostics
reported only:

- `BLOCK_ENTITIES/ENTITY/shadow/SKELETAL/SKELETAL_LIGHTMAP`;
- 825 selected binds, 55 draws, and 5,940 vertices; and
- no player/entity/terrain shadow selection or rejected scene bind.

After the auxiliary-animation fix, supervised client generation 10 repeated
mode `1` against the final source. At frame 1,529 the isolated player route had
9,480 selected binds, 632 physical draws, and 318,528 submitted vertices with no
other shadow contract or rejection. The client restored the exact prior air
state, returned in-process to the official Complementary archive, and
transactionally restored `RP_MODE=3;SHADOW_QUALITY=1`. Final shader generation 6
reached fingerprint `e10ceeca...e2d`; all eight fullscreen stages and six
terrain/shadow material routes advanced, rejection and fallback ledgers were
empty, and the live ledger was 40 textures, one renderbuffer, seven framebuffers,
111 programs, and zero shader objects. Parent PID 54146, server PID 54167, and
client PID 39943 remained live. The exact pose
`(-172.32293423405733, 63.0, -128.02169906198708)`,
`yaw=175.9983`, `pitch=13.878889`, plus authoritative rain state were unchanged.

These checkpoints close both pinned fallback-only caster routes at physical
submission. A checked translucent Gecko block-entity shadow pixel remains a
visual-parity gate, not a route-ownership gap.

## Current world-producer completion audit

The completion requirement is the pinned Iris world-pipeline boundary, not
blindly applying world shaders to GUI pixels. Every world draw must be owned by
an explicit semantic and selected pack program, or fail before physical
submission. Terrain and fullscreen phases must use the same pinned generation;
every supported shadow directive must own its exact physical caster subset.
GUI/HUD is composed after Iris final presentation, matching upstream Iris and
avoiding a second world submission.

Current source inspection establishes:

- every `LayerSettings` world registration calls `registerSemantic`; no world
  renderer requests `PipelineSemantic.AUTO`;
- every scene shader defaults to `SCENE_GEOMETRY`, while only framebuffer and
  GUI composites declare `INTERNAL_COMPOSITE`;
- a pinned Iris frame rejects scene geometry outside an explicit semantic or
  internal-target scope; and
- terrain, sky/cloud, entity, block-entity, particle, weather, hand, and
  world-overlay semantics all have explicit Iris fallback-family and
  render-stage mappings.

Historical bind evidence was not treated as sufficient after the player-shadow
changes. On client PID 39943, Complementary shader generation 6 and graph
generation 21 therefore repeated the complete reversible producer sweep. All 22
preparations succeeded and restored:

- entity flame, billboard text, outline, entity eyes, leash, vanilla armor,
  creeper overlay, and translucent held item;
- world border, fixed sky texture, sun scatter, fire overlay, and weather;
- lightning, primed TNT, dropped item, beacon, storage block entity, glowing
  sign text, block breaking, translucent particle, and chunk border.

At frame 10,038, fingerprint `e10ceeca...e2d` reported all 19 compiled
main-scene vertex ABIs and all 24 compiled main-scene state ABIs in the physical
submission ledger. Both `unsubmittedCompiledSceneVertexAbis` and
`unsubmittedCompiledSceneStateAbis` were empty. Pack programs owned opaque,
cutout, translucent, and emissive-additive terrain; opaque, cutout, and
translucent shadow terrain; and all eight deferred/composite fullscreen stages.
The rejected and fallback scene-bind maps were empty.

The live resource ledger remained 40 textures, one renderbuffer, seven
framebuffers, 111 programs, and zero shader objects. Cleanup left no reserved
negative-ID canary entity, retained the exact player pose
`(-172.32293423405733, 63.0, -128.02169906198708)`,
`yaw=175.9983`, `pitch=13.878889`, and left parent PID 54146, server PID 54167,
and client PID 39943 ready. Weather was clear after the sweep; the weather
canary reported successful conditional restoration rather than overwriting a
concurrent authoritative state.

Together with the independent general block-entity, player-only, and
light-block-entity-only checkpoints, this proves every current world producer
and pinned shadow caster subset reaches an Iris-owned program on the final
source. Remaining checked-pixel, cross-driver, compute-capable-driver, and
terrain-performance work measures fidelity, portability, or optimization; it
does not represent a host-shader escape path.

## Bliss as the Fabric-stack default

The combined `fabric-stack` now uses the same exact Bliss 2.1.0 Modrinth
archive, URL, and SHA-512 identity as the focused `distant-horizons-bliss`
pack. Complementary Unbound remains an external compatibility reference but is
no longer the combined pack's managed default. `play.sh modpack prepare` and
`modpack inspect` resolved one Bliss shader pack in the immutable view, and the
dedicated exact-archive DH planner contract plus `PlayUtilityTest` passed.

The supervised client hot-reloaded to the new default and reported Bliss shader
generation 2 with 72 compiled main-view and seven compiled shadow scene
programs. Both fixed-pose water checkpoints selected `gbuffers_water` on every
checked frame with its authored four outputs, active program/per-buffer blend
state, and empty rejected/fallback bind maps. This verifies default selection
and executable routing, not finished pixels.

At the same source-water slab and fixed presentation time, the surface gained
clear authored ripples but both the above-water and submerged captures showed a
large red/green mottled pattern. The shader-off control was nearly black, which
isolates the mottling to the Bliss pipeline while retaining a separate base
water/lighting visibility defect. The translucent-terrain GPU histogram had a
33 ms median upper bound and 66 ms p95 upper bound; captured cadence remained
single-digit FPS. Water fidelity and performance therefore remain open before
Bliss can be treated as a visual-quality baseline despite being the selected
default.

## Bliss custom-texture bootstrap fix

The red/green field was not authored water or volumetric fog. A fullscreen
cutoff sweep first localized its appearance to the bloom/tonemap tail, and
direct `colortex5`/`colortex3` diagnostic presenters exposed corrupted bloom
input rather than corrupt scene lighting. Bliss declares
`texture.composite.colortex6=texture/blueNoise.png`, then writes and flips
`colortex6` as its bloom ping-pong target. Minosoft had treated the stage custom
texture as a permanent sampler override, so later bloom blur passes continued
reading blue noise instead of their produced render target.

The planner now processes fullscreen programs in numeric execution order and
deactivates a stage custom render-target alias after that target's first flip,
matching Iris's `flippedAtLeastOnce` behavior. A synthetic first-flip regression
and the untouched Bliss 2.1.2 external archive gate pass. On hot-reloaded Apple
OpenGL, `render.substrate` reported the custom `colortex6` bootstrap for the
early composites and `colortex6=colortex6` for `composite9` and `composite10`.
Matched surface and underwater captures removed the former red/green field.

This fix does not close the rest of the Bliss submission. The surface remains
too dark, the submerged view remains nearly featureless fog, and measured
cadence remains single digit. Those are independent fidelity/performance
defects and must retain separate matched-workload diagnosis.

## External references

- [Iris program order](https://shaders.properties/current/reference/programs/overview/)
- [Iris gbuffers families and fallbacks](https://shaders.properties/current/reference/programs/gbuffers/)
- [Iris dimension program sets](https://shaders.properties/current/reference/miscellaneous/dimension_properties/)
- [Iris program enablement and particle ordering](https://shaders.properties/current/reference/shadersproperties/ordering/)
- [Iris rendering and blend directives](https://shaders.properties/current/reference/shadersproperties/rendering/)
- [LabPBR material standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)
- [LabPBR implementation requirements](https://shaderlabs.org/wiki/LabPBR_Implementation_Requirements)
