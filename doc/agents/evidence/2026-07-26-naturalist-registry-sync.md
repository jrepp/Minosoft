<!-- Copyright (C) 2026 Jacob Repp -->

# Naturalist remote registry-sync evidence

Date: 2026-07-26

## Decision

Minosoft does not need to execute Naturalist or GeckoLib client bytecode to
receive Naturalist entity spawns from the managed Fabric server. Fabric API
4.0.21 for Minecraft 1.20.4 defines the portable configuration-phase contract:
the server advertises `fabric:registry/sync/complete`, the client registers
`fabric:registry/sync/direct`, the server sends a bounded logical registry
buffer in fragments followed by an empty terminator, and the client
acknowledges `fabric:registry/sync/complete`.

The pinned Fabric Registry Sync implementation was audited for its grouped
namespace and delta-coded raw-ID layout. Minosoft implements that wire contract
as a source-native adapter. It does not link Fabric, Mojang, GeckoLib, or
Naturalist binary APIs in the client.

## Implemented boundary

`FabricRemoteRegistrySync` provides:

- bounded configuration payload and total-buffer limits;
- exact grouped-namespace/delta-ID decoding with duplicate, truncation,
  overflow, collision, and trailing-data rejection;
- owner-scoped provider and entity-definition registrations;
- weak, session-scoped fragment and diagnostic state;
- transactional replacement of the receiving session's entity numeric-ID
  overlay while preserving identifier lookup and the parent registry;
- native reuse for known entity types and explicit materialization of registered
  dependent-mod living/non-living types;
- rejection of unknown remote entity types before a spawn packet can resolve a
  wrong factory;
- completion acknowledgement only after a successful transaction;
- cleanup of incomplete fragment and diagnostic state when the final provider
  unloads.

Minosoft's general outgoing custom-payload packet retains its historical
byte-array envelope. Fabric's legacy configuration channel list instead uses
the packet remainder, so `ChannelC2SP` now has an explicit raw-data mode for
that boundary. The response registers `fabric:registry/sync/direct` plus a
valid Minosoft-owned compatibility marker. The managed server accepts both
identifiers without an invalid-channel warning; ordinary payload callers keep
their existing framing.

## Pinned Naturalist surface

Artifact inspection of Naturalist `5.0.0-pre.4+fabric-1.20.4` established 33
registered entity types and their exact dimensions. The Naturalist adapter owns
all 33 definitions, including the non-living duck egg, and now routes 32 entity
identifiers across 25 Gecko geometry identities.

The additional route is `naturalist:lizard_tail`. Its geometry and texture
variants are present in the artifact, but the model references no current
`.rp_anim.json` document. Minosoft therefore treats the detached tail as a
static routed model and aliases its deterministic green-tail texture. This is
an explicit artifact limitation, not invented animation behavior.

The pinned Naturalist source also establishes the absolute 1.20.4 tracked-data
allocation used by its `GeoModel` texture selectors. Vanilla `Entity` occupies
indices 0–7, `LivingEntity` 8–14, `Mob` 15, `AgeableMob` 16, and
`TamableAnimal` 17–18 before subclass fields. Minosoft exposes one bounded,
lock-safe raw tracked-data read for this exact dependent-mod boundary and
registers 25 owner-scoped texture definitions. They cover:

- entity-type bird and snake skins;
- tracked butterfly, dragonfly, lizard, detached-tail, tortoise, and snail
  variants;
- bear and lion sleeping/anger/mane state;
- deer baby state and duck/snail name variants;
- exact static bases for the remaining routed geometries.

Every resolver declares all possible texture resources before bake. Runtime
selection may return only that declared set and otherwise falls back to its
declared default. Registrations have generation tokens and quiescent cleanup:
a retained old model cannot invoke a replacement adapter's selector. The
selected Naturalist base then composes through ETF only when that exact base
has an ETF catalog entry.

## Automated verification

`FabricRemoteRegistrySyncTest` covers the exact grouped/delta codec,
truncation/trailing rejection, definition collisions, and idempotent owned
cleanup. `FabricRemoteRegistrySyncIntegrationTest` crosses the configuration
registration response, fragmented logical message termination, transactional
server raw-ID installation, generic living-entity construction, completion
acknowledgement, modern custom-payload encoding, and final-provider
diagnostic cleanup in a real 1.20.4 `PlaySession`.

`NaturalistCompatibilityAdapterTest` verifies the 33 definitions, 32 routes, 25
controller and texture identities, tracked tortoise and entity-type bird
selection, lizard-tail geometry/texture routing, artifact asset filtering, and
complete adapter-scope cleanup. `GeckoLibControllerBindingRegistryTest` proves
that texture selection is bounded to declared resources and the exact
registration generation. `SkeletalLoaderTest` crosses geometry parsing,
candidate texture baking, runtime tracked-state selection, and generation
cleanup. `FabricTechCapabilitiesTest` verifies Fabric API capability ownership
and unload.

The focused Java 17 gates passed:

```text
./gradlew test -x :debug-core:test \
  --tests de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistryTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.NaturalistCompatibilityAdapterTest
./gradlew integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoaderTest
./gradlew compileKotlin compileTestKotlin compileIntegrationTestKotlin
BUILD SUCCESSFUL
```

After the live run and documentation updates, the broad Java 17 gate also
passed:

```text
./gradlew test integrationTest
BUILD SUCCESSFUL
```

## Managed-server verification

Trajectory `naturalist-registry-sync-2026-07-26` started the pinned Fabric
0.15.11 server with Fabric API `0.97.3+1.20.4`, registry sync `4.0.21`,
GeckoLib `4.4.4`, and Naturalist `5.0.0-pre.4`. The client and server reached
`both.ready`. The live client recorded:

```text
FABRIC_REMOTE_REGISTRY_ADVERTISED channels=4 direct=fabric:registry/sync/direct
FABRIC_REMOTE_REGISTRY_FRAGMENT bytes=79462 total=79462
FABRIC_REMOTE_REGISTRY_SYNCED registries=4 entityTypes=159 materialized=33
```

`client.entities` then observed one `naturalist:rattlesnake` and two
`naturalist:tortoise` instances using the living-renderer path. This proves
server-assigned remote identities survive real spawn packets. It does not prove
that their routed Gecko pixels or tracked-data-selected textures are visually
correct.

Trajectory `naturalist-textures-2026-07-26` then repeated the pinned server and
client launch from a freshly built supervised generation. Two server-spawned
rattlesnakes resolved this exact read-only chain:

```text
type                  naturalist:rattlesnake
contentRoute          minosoft:content/naturalist/geo/entity/snake.geo.json/geometry.snake.smodel
routedContentFormat   geckolib
routedContentSource   naturalist:geo/entity/snake.geo.json
routedContentGeometry geometry.snake
routedContentTexture  naturalist:textures/entity/snake/rattlesnake.png
```

Both entities were outside the camera frustum, so their retained draw meshes
were correctly absent; the route/bake/selection fields deliberately inspect
the baked route without forcing visibility or retaining a mesh. This proves
the live registry → route → bake → source texture-selection boundary, but not
checked framebuffer pixels.

The same run exercised both real content-reload rejection checkpoints. Each
preserved active generation 1 and balanced all 297 candidate GL objects:

```text
after-upload:      187 buffers, 106 vertex arrays, 4 textures; live delta 0
after-publication: 187 buffers, 106 vertex arrays, 4 textures; live delta 0
```

Accepted recovery published generation 3. Both live rattlesnakes still resolved
the same route, geometry, and `rattlesnake.png` afterward. The supervised
base-game replacement also kept the server PID stable while the client moved
from generation 1 to generation 2.

## Retained draw-pass and seam diagnosis

Trajectory `naturalist-draw-passes-2026-07-26` moved the player close enough
for two server-spawned rattlesnakes to retain their real renderer features.
This exposed and fixed two consumer/runtime gaps before the draw diagnosis:

- the generic living fallback required a humanoid rig and retained its
  diagnostic model instead of the routed Gecko model; Gecko identities now
  use the generic `SkeletalFeature`, while CEM/native humanoids retain the
  humanoid wrapper;
- Naturalist's Blockbench animations use Molang `Math.sin`/`Math.cos` in
  degrees. The expression VM now distinguishes degree-based `math.*` trig from
  the existing radian-based bare EMF functions. Controller transitions retain
  the last evaluated source pose instead of reevaluating the outgoing
  expression clip with an empty context.

The repaired client remained render-ready while the snake animated. The actual
retained feature and independently resolved route reported the same bounded
draw topology:

```text
contentFormat                     geckolib
contentSource                     naturalist:geo/entity/snake.geo.json
contentGeometry                   geometry.snake
contentTexture                    naturalist:textures/entity/snake/rattlesnake.png
contentBaseVertices               0
contentSelectedTexturePasses      1
contentSelectedTextureVertices    252
contentGeometryPasses             1
contentEmissivePasses             0
contentGeckoLayerCandidates       0
contentKnownDrawPasses            1
```

There is therefore no duplicate full-body mesh, fallback body, emissive body,
or registered Gecko overlay competing with the rattlesnake base. The source
geometry contains seven cubes, and 252 vertices is exactly seven complete
six-face cubes. The authored cubes do contain local coplanar candidates:
`neck` and `tail` meet at `z=-2`, `neck` and `head` meet at `y=7`, and the
zero-height `tongue` has coincident opposite faces. `tail2` and `tail4` instead
use source-authored `inflate: 0.01`.

The pinned GeckoLib 4.4.4 source audit rules out generic face suppression as a
compatible fix. `BakedModelFactory.buildQuads` emits all six directions,
including both faces of a zero-thickness plane. Those faces are required when
an articulated joint rotates apart. `RenderUtils.fixInvertedFlatCube` adjusts
only normals on degenerate side planes; it does not delete geometry.

The audit did expose a separate Minosoft mismatch at the inflated tail
segments. GeckoLib expands vertex bounds by `inflate`, but its box-UV layout
uses the floored, uninflated `cube.size`. Minosoft previously passed its
already-inflated `from`/`to` bounds to `CuboidUtil.cubeUV`; a source
`4×3×6` cube with `inflate: 0.01` therefore used a
`4.02×3.02×6.02` atlas layout. That can sample across authored atlas edges and
look like an unstable surface seam even though only one body pass exists.

`SkeletalElement.boxUvSize` now retains source box-UV dimensions independently
from geometry bounds. The Gecko binder applies 4.4.4's floor rule before
inflation, while retained vertex positions still carry the authored inflate.
Focused binder and UV-layout tests prove the separation. This correction does
not claim that every live seam is resolved: the authored neck/tail,
neck/head, and zero-height tongue faces still require a deterministic close
render to classify.

The first framebuffer capture contained white entity boxes because the
persistent hitbox debug profile was enabled. `visual.prepare-reference`
disabled hitboxes without changing the profile, and the boxes disappeared in
the next capture. The live entities were partly terrain-occluded at night, so
these captures are diagnostic only and are not a checked pixel reference. A
future seam gate must use a deterministic close camera and fixed animation
timestamp, then compare a small joint crop across repeated frames.

Focused Java 17 verification passed:

```text
./gradlew :test \
  --tests de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionTest \
  --tests de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerApiTest \
  :integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoaderTest
BUILD SUCCESSFUL
```

The source-size UV correction additionally passed:

```text
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.skeletal.binding.SkeletalModelBinderTest \
  :integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.models.util.CuboidUtilTest
BUILD SUCCESSFUL

./gradlew test integrationTest
BUILD SUCCESSFUL
```

## Tracked controller input

The generic Naturalist controller previously selected an idle clip whenever an
entity stopped. That contradicts the pinned snake source: its primary
controller stops at the default pose, selects `move` only from limb movement,
and gives sleeping and climbing priority with a ten-tick transition.

`GeckoLibTrackedDataInput` now lets an owner-scoped controller declare only the
numeric or boolean protocol indices it consumes. The retained living renderer
reads those exact values into `GeckoLibAnimationState.data`; missing or
ill-typed values use a finite declared default. Duplicate input names with
different mappings reject controller construction. This remains headless in
`GeckoLibControllerSet.resolveTrackedData` and does not scan all 255 possible
metadata indices per entity per frame.

For the pinned 1.20.4 snake hierarchy, `ClimbingAnimal.CLIMB_FLAG` is index 17
and `Snake.SLEEPING` is index 19. The Naturalist binding now selects:

- sleep when index 19 is true;
- climb when bit 0 of index 17 is set;
- move when the live renderer reports locomotion;
- no clip for the stationary default pose.

`GeckoLibControllerApiTest` covers bounded resolution, defaults, and conflicting
declarations. `NaturalistCompatibilityAdapterTest` creates the headless bound
manager and verifies all four selections.

## Protocol attack trigger

Naturalist's pinned snake source owns attack as a separate zero-transition
controller. It starts the exact non-looping
`animation.sf_nba.snake.attack` clip when the entity swings, then resumes the
independent primary controller. The source clip is 0.25 seconds long.

Minosoft now preserves that separation without importing GeckoLib or Naturalist
binary classes:

- every base `Entity` records protocol `EntityAnimations` in a 64-entry,
  monotonically sequenced journal with the entity age at receipt;
- `PlayerEntity` also records the event while preserving its existing native
  hand-swing update;
- consumers hold independent cursors; journal overflow drops the oldest events
  and is reported to the reader rather than growing entity state without a
  bound;
- `GeckoLibControllerDefinition.eventTriggers` maps a namespaced host event to
  a trigger declared by that same controller and rejects cross-controller or
  undeclared trigger names;
- `SkeletalFeature` advances its cursor before controller evaluation and
  forwards only events from the current or previous two entity ticks, avoiding
  stale attacks when a renderer is created or replaced after a long absence;
- trigger dispatch crosses the controller registration's quiescent callback
  boundary, so closing the owner prevents a retained manager from invoking it;
- the Naturalist snake maps both main-arm and off-arm swing events to its exact
  attack clip.

Headless tests prove journal bounds and ordering, event-map validation,
owner-close suppression, and Naturalist attack selection. They do not yet prove
the live server packet timestamp or attack pixels.

## Declarative host state and auxiliary snake controllers

The pinned `Snake` bytecode establishes two additional independent controller
predicates:

- `tongueController` starts its 0.75-second play-once clip only while stopped
  and awake when `entity.random.nextInt(1000) < entity.tickCount`;
- `rattleController` loops only while awake on the exact
  `naturalist:rattlesnake` type when a non-spectator, living player passes the
  upstream four-block targeting range and the snake AABB expanded by
  `4×2×4`.

`GeckoLibHostStateInput` now declares those non-protocol inputs as immutable,
bounded query DTOs. The current query catalog covers a bounded random integer,
exact entity type, and nearby-player range/AABB intersection. A controller set
merges identical declarations, rejects conflicting or tracked-data-colliding
names, defaults missing/non-finite results, and exposes the same headless
resolution boundary as tracked data. The retained entity renderer resolves only
the declared queries. Query DTOs contain no owner callback, so state collection
cannot invoke a replacement adapter generation.

The Naturalist snake binding now has four independent controllers: primary,
attack, tongue, and rattle. Tongue uses a `RawAnimation.thenPlay` queue so its
controller returns to stopped after the source clip; rattle uses
`RawAnimation.thenLoop` and stops immediately when its declared host state no
longer matches. Sleeping index 19 remains the shared exact protocol input.

`GeckoLibControllerApiTest` covers bounded declarations, fallback, and
conflict rejection. `NaturalistCompatibilityAdapterTest` proves the exact
random/age, sleep, entity-type, nearby-player, play-once, loop, and stop
decisions headlessly. `GeckoLibEntityHostStateResolverTest` crosses real entity
types, deterministic random resolution, the world-entity read lock, AABB/range
filtering, spectator exclusion, and out-of-range removal.

## Retained controller observation and local acceptance

`GeckoLibControllerSet.inspect()` now copies a read-only controller summary
from the retained animation manager. The result preserves total controller
count while bounding records to 64 and reports current clip/time, transition
time, trigger state, raw-animation queue stage/current clip/wait, completion
count, held state, and raw-finished state. The renderer publishes that immutable
summary through a volatile reference after draw/restore. `client.entities`
therefore observes the actual retained consumer without forcing an
out-of-frustum mesh or crossing back into an adapter callback. Closing the
controller owner makes the observation fail closed.

The local data-pack authority can now materialize only an exact active
`FabricRemoteEntityDefinition`. It uses the same entity DTO/factory as remote
registry sync but assigns no synthetic wire ID. This permits a deterministic
dependent-mod consumer scene while keeping arbitrary summon identifiers
rejected and registration ownership intact.

The first live run exposed a real host-state mismatch: `LocalPlayerEntity`
intentionally has `canRaycast=false` so camera raycasts cannot hit the player
itself. That renderer concern must not make a creative/survival player
ineligible for Naturalist's targeting predicate. Nearby-player resolution now
uses positive health plus non-spectator game mode with the existing range/AABB
bounds. The focused resolver tests retain survivor, spectator, range, and AABB
coverage.

`acceptance/scenarios/naturalist-controller-state.json` mounts the pinned
`naturalist-controller-render` fixture, summons one fixed local-authority
rattlesnake, places the real local player inside the upstream four-block
boundary, and inspects the real retained OpenGL consumer. Two unchanged runs
passed:

```text
naturalist-controller-state-2026-07-26T22-39-00-214451Z-46666
naturalist-controller-state-2026-07-26T22-39-13-489997Z-46806
```

The second report observed one selected-texture pass with 252 vertices and
four independent controllers. `rattleController` retained
`animation.sf_nba.snake.rattle` and its single-stage loop queue across a
production content reload; elapsed time advanced from `2.0057158` to
`3.0264378` seconds. The fixture was then removed, the client stopped cleanly,
`./play.sh status --json` reported no parent/client PID, and
`./play.sh debug endpoints --json` returned `[]`.

This is a local-authority, real-renderer controller/topology gate. It does not
claim a fixed-pixel seam reference, remote packet timing, remote Naturalist AI
sound, off-screen resume, or source renderer overlays.

### Dependent-mod sound aliases

The pinned GeckoLib animation document uses symbolic `effect` values. Those
values are arguments to a dependent mod's keyframe handler, not resource
locations to play automatically. The pinned `Snake` class installs a sound
handler only on `tongueController`; that handler accepts only `hiss` and maps it
to `naturalist:entity.snake.hiss`. The current tongue timeline instead emits
`idle`, and `rattleController` has no sound handler because `Snake.aiStep`
produces rattling through normal entity sound packets.

Minosoft previously treated every symbolic sound effect as a resource
location, which would turn those two aliases into incorrect
`minecraft:idle`/`minecraft:rattle` playback if the missing controllers were
enabled. `GeckoLibRuntimeEffectRegistry` now provides an owner-scoped,
content-identity-bound handler equivalent:

- `Play` maps one alias to an explicit resource location;
- `Ignore` consumes an event without platform playback;
- `PassThrough` preserves the generic resource-location path for content that
  intentionally uses it;
- entity/item/armor and block-entity consumers bind the exact resolver
  generation with their retained model instance;
- owner close waits for in-flight callbacks and makes old bindings fail closed,
  so a replacement adapter cannot drive a retained old instance.

The Naturalist adapter maps only a `hiss` event from the tongue clip and
suppresses the pinned `idle` and `rattle` aliases. Focused runtime tests prove
mapping, pass-through, suppression, owner cleanup, and old-binding isolation;
the entity integration test proves an alias reaches native positional audio
through the bound resolver. Live controller/event and packet-sound timing
remain separate gates.

Java 17 focused verification passed:

```text
./gradlew :test \
  --tests de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEventsTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.NaturalistCompatibilityAdapterTest \
  :integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.feature.skeletal.GeckoLibEntityEventConsumerTest
BUILD SUCCESSFUL
```

The declarative host-state and auxiliary-controller increment passed:

```text
./gradlew :test \
  --tests de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerApiTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.NaturalistCompatibilityAdapterTest \
  :integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.feature.skeletal.GeckoLibEntityHostStateResolverTest
BUILD SUCCESSFUL

./gradlew test integrationTest
BUILD SUCCESSFUL
```

The retained-controller/local-fixture increment additionally passed focused
API, manager, host-state, and data-pack lifecycle tests plus:

```text
./play.sh modpack prepare fabric-stack \
  --trajectory naturalist-controller-state

./play.sh scenario run \
  acceptance/scenarios/naturalist-controller-state.json \
  --trajectory naturalist-controller-state --json
```

The earlier attack-controller increment also passed:

```text
./gradlew :test \
  --tests de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerApiTest \
  --tests de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistryTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.NaturalistCompatibilityAdapterTest \
  :integrationTest \
  --tests de.bixilon.minosoft.data.entities.entities.EntityTest
BUILD SUCCESSFUL
```

## Remaining acceptance

This advances source-native Naturalist controller rendering to a repeatable
local-authority integration gate. It does not yet prove:

- checked pixels for a remote Naturalist entity rendered through its Gecko
  route;
- source renderer overlays, held-food substitutions, or all tracked predicates;
- non-entity Fabric-synchronized registry kinds;
- arbitrary Fabric/Gecko/Mojang binary compatibility;
- exact upstream AI or renderer behavior, or checked live attack timing, in the
  Minosoft process.

The next visual gate is to place one observed remote Naturalist entity in the
same unoccluded close-camera geometry and capture settled, fixed-timestamp
references. Only after that evidence should the ladder claim checked remote
rendering.

## Continuation map

| Next gate | Existing proof | Required evidence to advance |
| --- | --- | --- |
| Visible remote base | Registry sync, route, bake, source texture selection, actual retained consumer, and one-pass draw topology are live | Put a server-spawned Naturalist entity in an unoccluded reproducible close camera, freeze or select exact animation timestamps, and compare several settled views against a pinned reference. |
| Snake seam | No duplicate full-body/overlay pass exists. Pinned 4.4.4 six-face emission is preserved, and Gecko's floored source-size box UVs are now independent from inflated vertex bounds | Rebuild/reload the live snake, then capture repeated bounded neck/tail, neck/head, and tongue crops with hitboxes/HUD disabled at fixed animation timestamps. Classify only residual changing pixels; do not delete articulation faces generically. |
| Tracked variants | Exact indices and selector unit/integration tests exist | Capture at least two server-authored variant values for one tracked species and prove the expected base changes without rebuilding geometry. |
| Source render layers | Generic Gecko additive/translucent layers exist | Audit and implement Naturalist's sleeping, sheared, firefly, name-mask, and held-food/substitution renderer behavior as independent declared passes or materials. |
| Controller parity | The snake's primary stopped/move/climb/sleep predicate, exact tracked indices, bounded protocol event journal, separate main/off-arm-triggered attack controller, exact random/age-driven tongue and nearby-player rattlesnake controllers, and generation-bound sound-alias handler semantics pass headlessly. A local real-renderer gate observes all four retained controllers, the exact rattle loop, and timeline migration across reload | Drive the primary/attack paths with remote packet timestamps, compare fixed tongue/rattle frames, verify packet sound timing and off-screen resume, then repeat per species. |
| Full dependent-mod claim | Exact Naturalist 5.0.0-pre.4 adapter is artifact-pinned | Repeat the matrix for every supported species/state, another OpenGL driver, reload/removal loops, and headless operation; keep Gecko/Mojang binary linkage explicitly separate. |
