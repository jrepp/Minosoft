<!-- Copyright (C) 2026 Jacob Repp -->

# Content-fidelity native adapter evidence

## Outcome

OptiFine still cannot run as an ordinary Minosoft mod. The implemented path
supports selected OptiFine/Blockbench content through Minosoft-owned parsers and
renderer services. Exact Entity Texture Features 7.0.13, Entity Model Features
3.0.17, and GeckoLib 4.4.4 metadata now select adapters for Minecraft 1.20.4.
The adapters do not link or execute their Mojang-targeted entrypoints, mixins,
access wideners, or nested libraries.

Real pack inspection now reports `activation=adapted` for all four pinned
artifacts. Feature catalogs remain honest: ingestion is mapped while missing
runtime, API, rendering, reload, and configuration surfaces remain partial or
unmapped.

## Pinned grounding

- Entity Model Features tag `v3.0.17`, commit
  `b4081c6fca06caf93049def7d1c753f11cca7ce9`, is the source of truth for the
  expression input-name and model-part mapping audit.
- The staged 1.20.4 artifact is
  `entity_model_features_1.20.4-fabric-3.0.17.jar`; the adapter requires its
  exact metadata shape and never invokes its entrypoints.
- `VariableRegistry`, `EMFModelOrRenderVariable`, `MethodRegistry`, and
  `EMFModelMappings` ground the current parity audit. Their public
  float/boolean inputs, audited numeric methods, render-output names, and the
  five currently shipped native rig maps now have source-native
  representations. The live context matches the audited partial-tick clock,
  frame-counter, degree/radian rotation, body-relative head-yaw, vanilla
  walking-animation interpolation, movement-projection, dimension, position,
  health, hurt/death timers, equipment/use, attachment, locomotion, tame/aggressive/anger, and
  bounded fluid/ground semantics. Raw `nbt(key,query)` syntax reaches the shared bounded
  ETF matcher. Live aliased part-property reads and ordered absolute writes now
  match the pinned field contract, including zero-valued missing reads,
  missing-target rejection, and distinct `visible`/`visible_boxes` hierarchy
  behavior. The ETF material selection carries its rule index into the next CEM
  evaluation frame. Hover state follows the session target, and wet state
  follows water or dimension/biome/heightmap-qualified rain exposure, including
  the pinned fixed-seed altitude and frozen-biome temperature samplers. An
  explicit render-path carrier supplies first-person, held, item-frame, GUI,
  head, and shoulder flags without globals. Stopped arrows intersecting a
  collidable block supply `is_in_ground`; consequently every audited public
  input has a source and only the intentional IEEE `nan` constant uses the
  catalog fallback. Non-world CEM callers, special-case limb/entity inputs, exact diagnostic/logging
  behavior, complex attachments, and the full entity model-map surface remain
  outside that claim.

## Implemented contracts

- `AssetsManager.list` enumerates the effective namespaced resource view for
  directory, ZIP/file, client-JAR, index, and priority managers.
- `ContentFidelityLoader` discovers CEM JEM roots, referenced JPM parts,
  GeckoLib geometry/animation files, and ETF random-entity properties from that
  normal priority view. One malformed input rejects the entire candidate.
- `ContentGenerationStore` prepares before commit, preserves the active value
  on preparation/commit failure, leases readers, and defers cleanup until the
  final lease exits.
- `PlaySession` publishes loaded assets and their immutable content snapshot in
  one generation. Headless sessions execute the same parse path.
- CEM ingestion retains hierarchy, boxes, box/per-face UV, pivots, rotation,
  scale, axis inversion, mirroring flags, inflation, attachments, external JPM
  merge, and compiled expression bindings.
- The expression VM is bounded and reflection-free. Arithmetic, conditions,
  lazy branches, keyframe interpolation, angle/curve helpers, the audited
  named-easing and numeric/boolean methods, variables, and caller-owned
  deterministic random input are tested. A per-skeletal-instance evaluator
  preserves ordered `var.*`/`varb.*` state. It snapshots each retained
  transform's live pivot, rotation, scale, visibility, and box-hidden fields,
  resolves aliases for reads and writes, applies assignments as ordered
  absolute values, and rejects unknown write targets. Missing part reads
  evaluate to zero, matching the pinned EMF fallback. `visible=false`
  suppresses the entire subtree; `visible_boxes=true` suppresses only the
  target's local cube mesh while its children keep rendering. Transform and
  visibility outputs apply after neutral animation. Its raw `nbt(key,query)`
  extraction preserves
  EMF's escaped comma/parenthesis/backslash arguments and resolves against the
  current entity. Version/entity part aliases are resolved during binding for
  geometry, transforms, and expression targets; collisions fail instead of
  silently overwriting parts.
- The EMF 3.0.17 public float/boolean input-name catalog, its documented
  misspelling aliases, and the `e` constant are represented in a
  renderer-independent context. A centralized live bridge supplies clamped
  partial ticks, EMF-wrapped entity/world/frame clocks, paused-frame behavior,
  degrees for head inputs and radians for entity/player rotations,
  body-relative wrapped head yaw, persistent vanilla walking-animation speed
  and swing (including riding suppression and baby scaling), yaw-relative
  movement projection, dimension, player/entity positions and distance,
  health and partial-tick hurt/death animation timers, handed equipment/use/blocking, attachment, locomotion,
  tame/aggressive/anger, and bounded fluid-column/ground-distance probes.
  Catalogued inputs without a trustworthy Minosoft or world-entity-render
  source default to zero/false while unknown names still fail. Alias registrations are
  entity-specific for the five native skeletal rigs currently shipped:
  player, zombie, cow, pig, and sheep. The quadruped `leg1..4` mapping targets
  Minosoft's hind/front transform names. Ordered `render.shadow_size`,
  `render.shadow_opacity`, shadow offsets, and leash offsets are retained with
  EMF defaults and can feed later expressions in the same frame. An
  owner-scoped bridge now applies those outputs to native translucent
  terrain-conforming shadows and segmented leashes. It preserves EMF's distance
  fade and 32-block shadow-size cap, scales baby-mob shadows, and projects over
  lit full-collision outline surfaces with bounded vertical scanning and
  vertical/light falloff. World block/chunk revisions invalidate stationary
  projections after terrain changes. Projected quads use the active resource
  pack's `minecraft:textures/misc/shadow.png`, the audited inverted two-radius
  UV mapping, fixed level-zero clamped sampling, and standard source-alpha
  blending. Modern attach packets populate leash-holder state rather than
  vehicle state.
- The leash path is grounded in mapped Minecraft 1.20.4
  `MobEntityRenderer.renderLeash`/`renderLeashPiece`, `Entity.getLeashOffset`,
  `Entity.getLeashPos`, `LeashKnotEntity.getLeashPos`, and
  `PlayerEntity.getLeashPos`, plus the pinned EMF 3.0.17 `MixinEntity` and
  render-variable state. EMF outputs add to the vanilla mob-local offset before
  body-yaw rotation. Minosoft distinguishes the vanilla generic
  standing-eye-height holder, fence-knot `+0.2` holder, and main-arm
  normal/swimming/elytra-riptide player pose formulas. It emits two crossed
  24-segment ribbons with the audited `0.025` width, direction-dependent
  quadratic sag, and alternating `0.5/0.4/0.3` versus `0.35/0.28/0.21` colors.
  Mobs apply the vanilla moving/stationary/rider body-control rules to separate
  previous/current body and head-yaw history. Native and adapted skeletal roots
  plus the mob-side leash anchor consume the same partial-tick body pose.
  Ribbon vertices independently interpolate endpoint block and sky light with
  vanilla truncation and sample the shared lightmap through a packed-light
  mesh/shader path. CPU tests cover body control, interpolation, anchors, sag,
  colors, light values, ribbon count, and the degenerate vertical case;
  dummy-GPU tests cover attachment, endpoint-light rebuild, shader/lightmap
  binding, EMF-offset replacement, retirement, and unload. Exact player-holder
  body-yaw dynamics and rendered-reference acceptance remain leash gates.
  Non-default shadow sampler-metadata overrides and shadow reference-render
  acceptance remain shadow gates.
- ETF ingestion retains ordered predicates, deterministic weighted variants,
  string/wildcard/regex/boolean/numeric/range/custom conditions, stable cache
  ownership, configured emissive suffixes, blink properties, and
  base/emissive/blink/blink2 frame state. Skeletal geometry is pre-baked per
  referenced material, so body and feature textures such as saddles or armor
  select independently and restore render state after explicit full-bright
  emissive passes. Native entity models are retained reload candidates as well
  as content-added models. Runtime context includes
  biome/weather/name/height/time, health/max-health/percent-health, light,
  temperature, dimension/difficulty/hardcore, target and client-player state,
  distance, calendar values, Minecraft semantic version, team,
  villager/type data, general mob variants, color/tame/movement state,
  equipment keywords and IDs, panda genes, llama inventory strength, horse
  jump and movement attributes, biome tags, pack-scoped active-mod IDs,
  predicate-gated vertical block identifiers, and depth/value-bounded nested
  entity, client-player, and vehicle NBT. Ordinary live entities expose
  `spawner=false`. String predicates accept positive sets plus `!` exclusions;
  Minecraft-version predicates accept exact or inclusive dotted ranges. NBT
  predicates share existence/inversion, integer-range, wildcard list-path,
  raw/string, wildcard, and bounded-regex semantics with EMF expressions.
  Pinned 7.0.13 bytecode establishes that `blocks` and misleadingly named
  `blockSpawned` both inspect the current and immediately lower block; the
  native context retains both identifiers and ETF's colon-separated
  `property=value` subset representation. Solid vertical probes use the
  `solid_render`-derived full-opacity flag matching the pinned
  `isOpaqueFullCube` call. Regional difficulty follows
  Minecraft 1.20.4's client formula with the client-visible inhabited-time
  default. The generation-owned selection cache also publishes the prior
  rule index and suffix while selecting dependent feature materials. Its
  per-texture and prior-selection maps use the pinned implementation's
  2,048-entity LRU bound and clear on generation closure.
- Gecko generic object animatables use the exact CPU snapshot and active
  controller registration to create a headless manager. Typed data tickets,
  first-tick/update time, controller triggers, compatible reload snapshots,
  quiescent registration closure, shared instanced ownership, and a bounded
  singleton per-ID LRU mirror the pinned 4.4.4 manager/cache boundary without
  exposing Mojang binary types.
- The renderer-independent `EtfPlayerSkinProcessor` recognizes ETF 7.0.13's
  64×64 signature and bounded control-pixel choices, derives one- or two-stage
  blink textures plus matching emissive masks without mutating the downloaded
  buffer, and allocates nothing for ordinary skins. It also implements ETF's
  eight coat choices and 1–8 length choices: styles 5–8 omit the top source,
  styles 2/4/6/8 remove moved pixels from the derived base, and styles 3/4/7/8
  request the upstream 0.5-voxel extra inflation. Matching coat emissive masks
  and the exact forced-solid lower-skin rectangles are derived in the same
  pass. Legacy six-pixel villager markers and controller choices 1–9 select
  villager, skin-textured villager, or one of five profile-plane nose sources.
  Removal choices clear the upstream face/blink source rectangles, and
  textured noses retain the upstream transpose/mirror/vertical-align
  conversion plus matching emissive pixels. Their logical 8×8 result is
  nearest-expanded into the shared 64×64 dynamic-array layer while the mesh
  retains an 8×8 UV domain. `PlayerRenderer` publishes those results through source-identity-keyed
  dynamic textures on the render queue. `PlayerModel` isolates the coat to the
  jacket mesh, applies the fat-coat inflation in shader space, suppresses it
  for hidden jackets and leggings, and renders its emissive mask at full
  brightness. Dedicated high mesh tags isolate villager and textured nose
  geometry from ordinary skin-part masks; both follow the native head transform
  and support full-bright emissives. Marker choice 2 derives synchronized
  normal/blink/blink2, coat, and textured-nose masks. `PlayerModel` samples the
  native entity-glint texture twice against world position and restores a
  `SRC_COLOR + ONE`, depth-equal, depth-write-disabled pass after each isolated
  mesh target; transparent base-mask pixels remain discardable even though
  ordinary lower player skin is forced opaque by the base shader. ETF-signature
  skins now follow the pinned adapter's default ETF-only base-transparency
  policy through a native profile option; the forced-solid marker overrides
  it, ordinary skins remain opaque, and all blink frames retain the same
  policy. Base emissive masks also preserve transparent pixels. World players
  and the first-person arm select the same deterministic base/blink frame;
  pending uploads fall back to the base skin. Artifact inspection established
  that ETF 7.0.13's internal `cape2` through `cape5` names are textured-nose
  source rectangles, not a cape renderer; cape geometry is therefore not an
  ETF gate. Real-GL captures and visual references remain.
- GeckoLib ingestion retains geometry documents, parented bones, cubes,
  box/per-face UV, pivots, transforms, animation loop modes, numeric/expression
  channels, interpolation names, easing names, and arguments.
- Matching GeckoLib geometry and animation documents are joined by namespace
  and basename before publication. The native runtime evaluates loop/hold/once
  clips, expressions, linear/step/Catmull-Rom channels, and every built-in
  easing registered by the pinned 4.4.4 artifact, including its first easing
  argument and exact compatibility quirks. Owner-scoped custom easing
  registration rejects replacement of built-ins and non-finite output, and
  reaches both controller evaluation and retained single-clip playback. Named
  playback and transition blending can update retained bones. A separate
  source-native controller facade adds predicate decisions, restart/stop,
  concurrent replace/add layers, expression data, state-driven animation
  speed/easing overrides, triggerable animation preemption/base reload, typed
  sound/particle/custom keyframe handlers, bounded event delivery, and
  generation-owned animatable caches. Its pinned `RawAnimation`-shaped builder
  and queue cover default/play-once/hold/loop stages, 20 Hz waits, repeat
  expansion, delta carry across finite stages, stage event delivery, and
  triggered base-animation reload. Finished raw identity is stable until an
  explicit reset, matching the audited controller contract rather than
  restarting a completed predicate result every frame. Custom loop names
  survive animation JSON parsing and resolve through an owner-scoped
  repeat/advance/hold registry; trigger preemption rebuilds the prior base raw
  animation from its first stage, as the pinned controller does. Particle
  handlers receive locator and `pre_effect_script` unchanged,
  matching the pinned API's data contract rather than inventing script
  execution. A retained model with one attached clip starts it automatically.
  Entity events wait for finalized bone transforms, then play native positional
  sounds, spawn registered particle factories at entity or resolved locator
  positions, and cross an owner-scoped, exception-isolated listener boundary
  for custom adapted-mod instructions. This intentionally does not claim
  binary linkage for classes compiled against GeckoLib and Mojang types.
- Adapted mods can register entity, block-entity, item, and armor targets
  against a stable Gecko content identity and register a controller factory for
  that same identity. Target kinds are isolated even when identifiers match.
  New retained instances then evaluate the full named controller set instead
  of the one-clip fallback. Registration closure immediately disables
  callbacks; content lookup falls back without retaining a stale generation.
  Routed block entities render through section-cache-owned skeletal instances;
  world/item-display and first-person held items use the item route; every
  living renderer checks four independent armor slots through the armor route.
  The same identity can own bounded opaque, translucent, or additive full-model
  texture passes with state predicates and optional full-bright tint. Their
  meshes and textures are baked into the content generation, their
  registration token prevents a replacement callback from driving an old
  mesh, and every pass restores renderer state.
- Retained animal and humanoid model replacement snapshots compatible named
  Gecko controller layers. A replacement preserves current clip and raw queue
  identity, elapsed and transition time, stage/wait/hold progress, trigger
  resume state, last pose, and the fired-event boundary. Removed controllers or
  clips reset only the incompatible layer and do not replay old keyframes.
- `SkeletalModelBinder` converts neutral geometry into the retained renderer
  model without OpenGL. Static transforms, direct OptiFine texture locations,
  cube/per-face UV, and UV rotation survive. The loader leases the content
  generation through GPU model lifetime; animal and humanoid renderers prefer a
  matching CEM filename/entity ID. `SkeletalModelComposer` preserves unrelated
  native parts, replaces only ordinary CEM targets, and gives `attach:true`
  roots private child transforms so their geometry/expressions do not move the
  native target.
- Baked skeletal models have an explicit retirement boundary. Retirement
  rejects new instances but keeps uploaded meshes alive for existing retained
  instances; the last instance release unloads GPU buffers, while an unuploaded
  retired generation drops its CPU buffers. Each content model also holds its
  exact content-generation lease, keeping ETF catalog/cache state alive until
  that final release.
- `/reload content` runs the content-fidelity transaction on the render thread:
  it reparses the current asset/data views, binds and CPU-bakes CEM/Gecko
  geometry plus ETF material variants, uploads candidate meshes, attaches
  candidate-generation leases during commit, atomically replaces skeletal
  model/entity lookup maps, and flags live entity features to rebuild their
  model instances. Routed block-entity section caches are invalidated after
  publication; item and armor features migrate compatible controller state,
  while first-person held items detect the newly baked model identity on their
  next draw. Referenced static textures are decoded and assigned
  stable shader coordinates before bake; affected resolution buckets upload to
  candidate handles and publish or roll back with the model maps. New texture
  keys append without allocating new sampler units. Missing assets reject the
  candidate before publication; the previous generation and its active
  instances remain usable.
- Item model overrides implement last-match selection for the audited 1.20.4
  stack and render-context predicate subset. Unknown and wrong-item providers
  fail as negative infinity; first-person and GUI paths supply live local
  active-use, handedness, cooldown, and fishing state. Bundle occupancy,
  clock/compass, trim-registry lookup, remote use progress, and later
  component-based dispatch remain explicit gaps. Element-backed override models
  retain and bake their cuboid geometry instead of falling back to a flat
  sprite. Display entities
  retain protocol transformations and item/block/text metadata; native
  renderers cover transform interpolation, fixed/entity billboards, packed
  light overrides, wrapped/aligned text, opacity, background, shadow, and
  see-through state.
- Resource packs and data packs mount through distinct `assets/` and `data/`
  views and publish with the same session content generation. The datapack
  runtime discovers singular and plural function/tag paths, expands required
  and optional tags, executes load/tick functions, expands storage-backed
  macros, schedules delayed functions, and bounds recursion and per-tick
  command work.
- Local sessions lease that generation and run its load tag after the local
  world entity reset. A failed replacement load retains the previous executing
  function runtime. Candidate load evaluation is transactional: failure
  restores scoreboard/storage state and the prior session entity graph,
  including identity, IDs/UUIDs, command NBT/tags, transforms, mounts, removals,
  and creations. Disconnect removes the runtime tick callback before closing
  its lease.
- The local authority implements dummy scoreboards, command storage, SNBT,
  selector filters, entity score holders, entity
  data/tag/kill/ride/teleport commands, and fail-closed unknown commands. Its
  bounded `execute` subset covers `as`, `at`, `on passengers`, `on target`, `on
  attacker`, `positioned`, `anchored`, coordinate/entity `facing`, `rotated`,
  entity/score/storage/function conditions—including score-to-score
  comparisons and entity-data existence—and result/success stores to score,
  storage, and entity NBT while retaining executor, position, rotation, and
  feet/eyes anchor through nested functions. Data sources include storage and
  selected entities. Signed scoreboard division and remainder follow command
  floor semantics. Terminal entity conditions expose the match count required
  by the exporter.
- The function runtime owns return control flow. `return`, `return fail`, and
  `return run` reached directly or through nested `execute` clauses stop the
  owning function and preserve their result. A called function catches its own
  return before handing the result back to a caller, matching the pinned
  compiler's `execute if function` predicates and tween/variant guards.
- The local `summon` boundary accepts absolute, relative, and local positions
  and creates session-owned item, block, and text display, interaction, and
  marker entities. It retains transformations, billboard/light/text/item/block
  state, command tags, UUIDs, and bounded nested passenger attachment state.
  Locally owned interaction entities capture player attack/interact packets,
  retain the response metadata bit and the command-visible player/timestamp
  record, expose that player to `execute on attacker` or `execute on target`,
  and dispatch the corresponding Animated Java advancement reward handler as
  the player at the current datapack tick.
  The shared `DisplayEntity` metadata class is registered for versioned
  protocol field lookup.
- Function macros can now source an exact selected entity as well as command
  storage. Entity snapshots expose UUIDs as the vanilla four-signed-int array,
  and NBT string macro values substitute as raw command fragments. An
  upstream-shaped test exercises the pinned compiler's interaction callback
  chain and its full signed UUID-word-to-byte-to-string algorithm.
- A reduced exporter-shaped fixture records Animated Java 1.10.2 and upstream
  commit `a5fc548d2a53cc0887fa070db33ccfcef1cd3541`. Against 1.20.4 assets it proves
  data/resource discovery and load → summon/init → tween-guarded tick/frame
  mutation → removal, including the compiler's
  `execute if score … run return 1` form. It is deliberately not represented
  as an unmodified exporter output. Its provenance also fingerprints the exact `global.mcb`,
  `global.mcbt`, and `main.mcb` compiler templates used for the command-surface
  audit.
- A separate fixture is the unmodified output of the official Animated Java
  1.10.2 plugin running in Blockbench 5.1.4 against its upstream
  `armor_stand_minimal_1.20.4.ajblueprint`. The exporter completed without
  plugin or export errors and produced 13 resource files plus 109 data files.
  Exact host, plugin, source, and blueprint hashes and the complete generated
  output manifest are recorded in
  [the Blockbench export evidence](2026-07-24-animated-java-blockbench-export.md).
  The corresponding Minosoft integration test passes exact manifest and model
  discovery, load/summon, a generated walk-frame advance, and removal. Closing
  that gate required native trailing-comma SNBT, append-to-missing-list, missing
  data-source failure, and atomic missing-macro-key behavior; focused unit tests
  preserve each command-runtime correction.

## Automated evidence

Java 17 focused runs passed for:

- CEM JEM/JPM parsing and standalone JPM;
- GeckoLib geometry and animation ingestion;
- bounded expression evaluation, audited numeric/easing methods, lazy control
  flow, raw NBT predicates, ordered render outputs, and failure limits;
- live aliased EMF part-property reads, ordered same-frame absolute writes,
  zero-valued missing reads, missing-target rejection, and distinct
  subtree-visible versus local-box-hidden retained rendering;
- the EMF 3.0.17 expression input-name catalog, compatibility spellings, known
  defaults, unknown-name rejection, Euler constant, wrapped clocks/identifiers,
  dimension mapping, body-relative head angles, movement projection, and exact
  vanilla walking-animation update/interpolation with riding/baby behavior; a
  headless renderer integration test also resolves those units from a live
  Minosoft entity, local player, render clock, and world identity;
- caller-owned render-path flags, live hover and ETF rule-index propagation,
  altitude/frozen-biome-aware exposed-rain wetness, and stopped-arrow
  in-ground classification;
- entity-part alias version/entity precedence and removal;
- native player/zombie/cow/pig/sheep aliases without leakage to an unsupported
  entity type;
- ETF rules, variants, custom and bounded NBT predicates, current/below block
  identifiers and state subsets, opacity-backed vertical probes, vanilla-client
  regional difficulty, prior rule/suffix chaining, 2,048-entry LRU eviction,
  cache closure, and material frames;
- resource discovery and malformed all-or-nothing candidate behavior;
- generation preparation/commit failure, reader retirement, and cleanup;
- candidate leases acquired during atomic commit and deferred cleanup after a
  failed commit;
- neutral-to-renderer hierarchy, transform, texture, and UV binding;
- native-part preservation, targeted CEM replacement, and isolated attachment
  composition;
- owner-scoped EMF shadow/leash output resolution, bounded terrain projection
  over lit full-collision outline surfaces, size/opacity/distance/light/vertical
  falloff, baby scaling, block-revision invalidation, resource-pack shadow
  texture identity, audited UV/clamp/LOD/blend state, dummy-GPU mesh replacement
  and retirement, previous/current vanilla mob body control shared by skeletal
  roots and leash anchors, audited mob/generic/knot/player leash anchors,
  crossed-ribbon sag/color geometry including vertical degeneracy, independently
  interpolated endpoint block/sky light, lightmap shader binding, and leash
  attach/detach plus endpoint-light/EMF-offset replacement rendering state;
- legacy vehicle-versus-leash attach decoding and modern leash-holder state
  without accidental vehicle mounts;
- loaded and pre-upload skeletal retirement with final-instance GPU unload/CPU
  drop and per-model content/cache lease accounting;
- dummy-GPU live content reload with a successful uploaded model swap, retained
  old-instance lifetime, entity routing update, new-texture append, missing
  asset rejection that preserves the active generation, and published texture
  lookup rollback/finalization with zero live candidate resources; the same
  reload fixture verifies a CEM expression reading the model's live translated
  pivot before and after publication;
- Gecko clip attachment, runtime interpolation, expressions, loop modes,
  the pinned built-in easing catalog and arguments, owner-scoped custom easing
  registration and retained-transform application, controller transitions,
  state-driven speed/easing overrides, triggerable base-animation reload,
  bounded raw play/once/hold/loop/wait/repeat queues with cross-stage delta
  carry, stable finished identity, explicit reset, current-stage queries, and
  event delivery, retained JSON custom-loop names, owner-scoped custom loop
  registration and lifecycle, typed keyframe handlers retaining particle
  script/locator data, stable content-identity entity routing, retained
  multi-controller application, owner-scoped render-layer predicates,
  generation-baked additive layer meshes, registration-token replacement
  isolation, compatible raw-queue/controller migration without event replay,
  event parsing, deferred post-transform dispatch, headless
  sound/particle/custom routing, native dummy-audio and registered-particle
  delivery, locator placement, and owner-scoped listener cleanup and failure
  isolation;
- generic object manager typed data, trigger forwarding, first-tick/update
  state, compatible snapshots, registration quiescence, shared instanced
  ownership, bounded singleton LRU eviction, and deterministic closure;
- item predicate selection plus shared display range/AABB visibility,
  transform/shadow/text-style interpolation, negative start deltas, and capped
  teleport pose smoothing;
- arbitrary cuboid item-model retention and integration-test compilation;
- datapack function/tag discovery, macro expansion, deterministic scheduling,
  recursion limits, and command budgets;
- separate data-namespace mounting, generation runtime publication,
  scoreboard/storage/SNBT command state, execute conditions/stores, selectors,
  entity mutation/lifecycle, and fail-closed dispatch;
- failed candidate-load rollback for scoreboard/storage state and for entity
  creation, removal, NBT, tags, transforms, identity, ownership, and mounts;
- a real 1.20.4 registry integration fixture that summons an item-display root
  with a text-display passenger and verifies transformation, material, tag,
  light, and attachment state;
- local interaction response metadata, attack/interact record capture, and
  `execute on target`/`execute on attacker` resolution;
- pinned-exporter-shaped entity macro callback dispatch, entity-data
  predicates/sources, score comparisons, raw NBT-string substitution, and exact
  signed four-word UUID conversion;
- the reduced pinned Animated Java 1.10.2 lifecycle fixture described above;
- the unmodified Animated Java 1.10.2 Blockbench export's manifest/model
  discovery and load/summon/walk/remove lifecycle, including the trailing SNBT
  comma, append-created list, missing data source, and atomic macro-preflight
  behavior it exposed;
- eight successive unmodified-export runtime generations, rejected-load
  rollback, recovery, stable root/passenger identity and UUIDs, continued walk
  advancement, last-known-good runtime retention, and exactly-once retired CPU
  generation cleanup;
- a durable headless fixture that binds one CEM/ETF/Gecko pack for both 1.19.4
  and 1.20.4, including version-specific CEM aliases, expression/clip
  evaluation, a generic Gecko object manager, and ETF variant/emissive
  discovery;
- generation-leased stable texture coordinates, dead-hole reuse, trailing-layer
  compaction, permanent resource-pack slot protection, and a 32-generation
  headless reload accounting fixture; failed OpenGL bucket allocation deletes
  both the incomplete handle and any earlier candidate handles, while
  created/deleted/live diagnostics make a real-driver run auditable;
- exact ETF/EMF/GeckoLib adapter activation and scope cleanup;
- functionality catalog capability honesty.

The real pinned pack was prepared and inspected with:

```text
./play.sh modpack prepare content-fidelity \
  --trajectory content-fidelity
./play.sh modpack inspect content-fidelity \
  --trajectory content-fidelity
```

It reported ETF `7.0.13`, EMF `3.0.17`, GeckoLib `4.4.4`, and Fabric API as
adapted with no artifact blockers or dependency issues. The ETF inventory
reports the new regional/block/selection context honestly while retaining
partial status for broader block-entity and feature surfaces.

No live visual or repeated GPU-reload acceptance is claimed by this evidence.

## Remaining gates

1. Extend the EMF entity/version part-alias catalog beyond Minosoft's five
   shipped skeletal rigs. Connect CEM evaluation to the non-world render paths
   represented by the new context carrier, then finish entity-specific live
   inputs, including special limb animators. Then complete complex attachment parity,
   non-default shadow sampler-metadata overrides and reference-render
   acceptance, exact player-holder body-yaw dynamics, raw expression diagnostic
   parity, fallback diagnostics, and reference captures.
2. Complete broader non-skeletal and block-entity feature textures, then finish
   configuration, repeated real-GL validation, and visual reference captures.
   The retained
   skeletal-entity base/emissive, player coat, and transactional new-texture
   reload paths are implemented but are not the whole ETF surface.
3. Validate the new entity/block-entity/item/armor routes and the
   controller/cache/event/easing/loop/render-layer facade with dependent mods.
   Add GUI item rendering, exact armor-to-parent-bone fitting, and rendered
   reference captures. Binary GeckoLib/Mojang
   compatibility remains a separate explicit project or requires exact native
   adapters per dependent mod.
4. Add glowing/team outlines, rendered-reference comparison, and real-OpenGL
   reload accounting for the passing unmodified Animated Java 1.10.2 export,
   then prove remote-server behavior.
   Headless transactional reload/rollback and CPU generation cleanup now pass.
   Add another upstream blueprint only to expand the command or asset surface.
5. Exercise the generation-leased stable-slot compactor with repeated real-GL
   unload accounting, broader multi-version fixtures, and live visual captures
   before any “fully supported” claim.
