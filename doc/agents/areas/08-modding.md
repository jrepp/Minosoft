<!-- Copyright (C) 2026 Jacob Repp -->

# Mod-workflow evidence map

## Boundary

This layer owns mod discovery, metadata and dependency resolution, isolated code
loading, entrypoints, lifecycle/ownership, resource registration, developer
rebuild/watch/reload, and compatibility adapters. Fabric is the first ecosystem
bridge, not a promise that arbitrary Minecraft Fabric mods can run unchanged.

## Current evidence

| Status | Claim | Evidence |
| --- | --- | --- |
| Observed | Minosoft already has phased discovery, manifests, dependency/provides checks, per-mod classloaders, assets, and `init`/`postInit`. | `modding/loader/` and the dummy-mod integration fixture. |
| Observed | A mod can be removed from `ModList` and its class bytes/assets are asked to unload. | `ModList.remove`, `MinosoftMod.unload`, and `LoaderUtil.unloadAll`. |
| Verified | Native mods have an explicit unload callback that runs before their classloader and asset manager are released; `ModList.clear()` now unloads every distinct generation rather than dropping its maps. | `ModMain.unload`, `MinosoftMod.unload`, `ModList.clear`, and `MinosoftModTest`. |
| Observed | Event listeners can be unregistered, but registrations are not automatically associated with a mod. | `AbstractEventMaster`/`EventMaster` and callback listener helpers. |
| Unknown | No test proves an unloaded mod classloader becomes garbage-collectable or that old callbacks stop. | Existing mod integration test covers initial loading only. |
| Verified | The Java launcher resolves checked-in Packwiz manifests into hash-verified, read-only artifacts outside the source tree and isolates mutable state by trajectory. An optional portable cache uses the same hash-addressed identity and supports cache-only local builds without committing binaries. | `modpacks/`, `util/play/Play.java`, `./play.sh modpack cache add FILE`, and `./play.sh modpack prepare sodium --trajectory <name>`. |
| Verified | Fabric metadata is parsed with Fabric Loader's public version and predicate APIs, and unsupported activation capabilities are reported before native loading. | `modding/loader/fabric/`, its focused test, and `./play.sh modpack inspect sodium`. |
| Observed | Sodium 0.5.8 for Minecraft 1.20.4 targets Mojang/Fabric binary APIs and declares entrypoints, mixins, an access widener, and five nested API JARs; those binaries cannot link directly against Minosoft. | `modpacks/sodium/` plus the resolved artifact metadata. |
| Verified | The pinned Sodium surface resolves to an explicit Minosoft compatibility adapter. It selects one frame-pinned, graph-exclusive terrain-provider generation with duplicate-submission checks and bounded timing/resource telemetry. The backend still delegates Minosoft's terrain algorithms, so `ADAPTED` is not full Sodium behavioral ownership. | `FabricPackLoader`, `SodiumCompatibilityAdapter`, `SodiumRendererHook`, `TerrainBackendRegistry`, focused tests, [live activation evidence](../evidence/2026-07-21-sodium-activation.md), and [R0–R7 checkpoint](../evidence/2026-07-24-render-substrate-r0-r7.md). |
| Verified | Adapter discovery is an owner-keyed registry. Duplicate adapter IDs and multiple matches for one artifact fail deterministically; a native pre-phase mod can register an additional adapter before pack preflight. | `FabricCompatibilityAdapterRegistry`, `FabricCompatibilityAdapters.register`, boot ordering in `Minosoft.preBoot`, and `FabricPackPreflightTest`. |
| Verified | The `fabric-stack` pack pins nine client-visible adapted artifacts plus server-only Terralith and Tectonic. All nine client surfaces activate exact source-native adapters with no preflight blockers. Inventory Management owns reusable container-screen extensions, native sort/transfer/stack controls, operation diagnostics, and the pinned upstream server payload contract. Iris now selects a transactional shader-pipeline generation executing the bounded project reference pack's terrain, shadow target/view, and final composite; an independent real-world pack remains gated. JEI owns a container control and paged viewer backed by the synchronized recipe registry. Naturalist `5.0.0-pre.4` targets 1.20.4, enters through the portable cache, mounts a bounded client asset surface with 31 entity routes across 24 Gecko geometry identities, and runs upstream gameplay on the managed server beside GeckoLib. The launcher installs only manifest-declared `both`/`server` support mods into its managed Fabric server set; the worldgen pair stays out of Minosoft's client classpath, and Tectonic selects its embedded Terratonic pack when Loader reports Terralith. | `modpacks/fabric-stack/`, the nine compatibility adapters, `ShaderPipelineRegistry`, `FabricContainerScreenExtensions`, `NaturalistCompatibilityAdapter`, `Play.prepareFabricServer`, focused tests, [R0–R7 checkpoint](../evidence/2026-07-24-render-substrate-r0-r7.md), [Inventory Management evidence](../evidence/2026-07-22-inventory-management.md), [Terralith evidence](../evidence/2026-07-23-terralith-worldgen.md), [Terratonic evidence](../evidence/2026-07-23-terratonic-worldgen.md), and [Naturalist evidence](../evidence/2026-07-24-naturalist-integration.md). |
| Verified | Every built-in Fabric adapter exposes a version-specific catalog of mapped, partial, and unmapped upstream functionality. Preflight and the in-game Mod settings screen consume the same records. | `FabricFunctionalityCatalog`, `FabricPackPreflightCli`, `FabricModSettingsMenu`, and `FabricFunctionalityCatalogTest`. |
| Verified | Process-local diagnostics track per-mod activation state/time and installed host hooks with invocation count plus average/maximum hook cost. The in-game screen adds pack, trajectory, generation, uptime, and global FPS context without attributing whole-frame cost to a mod. | `FabricModDiagnostics`, hook registries/call sites, `FabricModDiagnosticsMenu`, focused tests, and [2026-07-22 evidence](../evidence/2026-07-22-fabric-catalog-diagnostics.md). |
| Verified | Fabric API now publishes a source-level client event bridge with owned client-start/stop, world-render, and HUD phases. Callbacks execute synchronously in registration order on the render thread, isolate failures, expose attributed timing, and disappear when their registration scope closes. | `FabricClientEvents`, render/world/HUD call sites, `FabricClientEventsTest`, and [client-event evidence](../evidence/2026-07-22-fabric-client-events.md). |
| Verified | Fabric API publishes source-level start/end client-tick events around a single ordered, non-overlapping 20 Hz session cycle. Default and extension tasks are snapshot-ordered, a failed task cannot suppress later work or tick end, and the capability is generation-owned and timed. | `FabricClientTickEvents`, `SessionTicker`, `SessionTickRunnerTest`, and [tick evidence](../evidence/2026-07-22-fabric-client-tick-events.md). |
| Verified | Fabric API publishes a generation-owned source lifecycle for session-asset, shader, and texture reloads with prepare/apply/complete/failed phases. Initial assets use candidate-before-apply and clean up failed candidates; graphics applies on the render queue, but shader rollback remains a separate gate. | `FabricResourceReloadEvents`, `PlaySession.load`, `ReloadCommand`, focused tests, and [resource-reload evidence](../evidence/2026-07-22-fabric-resource-reload-events.md). |
| Verified | Fabric API publishes normalized render-thread input observations and a source-native configurable key-binding registry. Definitions can precede render sessions, fan into current/future input managers, reject duplicate IDs, isolate callback failures, and detach per registration and per render context. | `FabricInputEvents`, `FabricKeyBindings`, `BindingsManager`, registry tests, and [input/connection evidence](../evidence/2026-07-22-fabric-input-connection-events.md). |
| Verified | Fabric API publishes generation-owned client connection phases over every play session without assuming a singleton client. | `FabricClientConnectionEvents`, the `PlaySession.state` observer, and [input/connection evidence](../evidence/2026-07-22-fabric-input-connection-events.md). |
| Verified | Fabric API publishes owned world/dimension transitions, chunk lifecycle, post-commit block mutation batches, bounded client payload channels, screens, HUD layers, client commands, entity lifecycle, player interaction policy, particles, and sound at shared local/remote source boundaries. | `FabricWorldEvents`, `FabricClientPayloadChannels`, `FabricUiHooks`, `FabricClientCommands`, `FabricEntityEvents`, `FabricPlayerInteractionHooks`, `FabricMediaEvents`, focused tests, and [world/UI/gameplay evidence](../evidence/2026-07-22-fabric-world-ui-gameplay-hooks.md). |
| Verified | Compatibility adapters can register a rendering-independent typed settings schema through the existing owned-screen boundary, and active registrations appear in the native Mod settings menu. Staged validation, reset/apply/cancel, dependency and restart state, failed-apply rollback, cycle controls, and clipped scrolling remain Minosoft source APIs; Mojang, Cloth Config, MidnightLib, and Mod Menu screen classes still do not link. | `ConfigEntry`, `SettingsSession`, `FabricSettings`, `FabricModSettingsMenu`, focused tests, and [settings-form evidence](../evidence/2026-07-24-settings-forms.md). |
| Verified | Renderer-bound settings factories and atomically persisted adapter values now map Sodium, Entity Culling, ImmediatelyFast, Iris, Naturalist, JEI, and Inventory Management controls. The JEI transfer UI delegates only to supporting container handlers; Xaero terrain/waypoint data and Tech Reborn machine/network authority remain separate non-UI dependencies. | `FabricSettings`, `FabricAdapterOptionStore`, exact compatibility adapters, `FabricRecipeTransfers`, `FabricMachineScreens`, focused tests, and [Fabric UI evidence](../evidence/2026-07-24-fabric-ui-framework.md). |
| Verified | The Tech Reborn 5.10.4 pack recursively resolves 54 nested provider surfaces and activates three exact source-native adapters in dependency order. Those adapters expose Fabric API module inventory, bounded Team Reborn energy storage, and Tech Reborn's resource catalog in one owned scope; they do not execute upstream gameplay bytecode. | `FabricPackPreflight`, `TechRebornCompatibilityAdapters`, capability tests, `modpacks/tech-reborn/`, and [Tech Reborn activation evidence](../evidence/2026-07-21-tech-reborn-discovery.md). |
| Verified | The Tech Reborn adapter now decodes and fingerprints blocks, items, block properties/states, and ore features; mounts artifact assets; synchronizes the snapshot into a local play session; and drives deterministic authoritative local ore generation. | `FabricWorldContentReader`, `FabricSessionContentBridge`, `ExternalAssetProviders`, `TechRebornGenerator`, and [world-generation evidence](../evidence/2026-07-21-tech-reborn-worldgen.md). |
| Verified | The partial content-fidelity trajectory resolves exact ETF 7.0.13, EMF 3.0.17, GeckoLib 4.4.4, and Fabric API artifacts to source-native adapters without executing their upstream Minecraft bytecode. Headless CEM/ETF/Gecko parsing, transactional generations, live version/entity aliases, per-instance CEM expressions, expanded ETF context, source-native Gecko predicate/layer controllers, owner-scoped custom easings, bounded raw animation queues, owner-scoped custom loop types, generation-owned caches, retained rendering, transactional static texture handles, and the Animated Java runtime foundation exist. Content generations lease stable texture coordinates; dead holes are reused and trailing layers compact without renumbering retained meshes. A durable fixture crosses 1.19.4 and 1.20.4; binary APIs, complete exporter/render behavior, repeated real-GL accounting, and visuals remain gated. | `modpacks/content-fidelity/`, `assets/model/`, `assets/datapack/`, `local/datapack/`, `ContentFidelityCompatibilityAdapters`, `GeckoLibRawAnimation`, `GeckoLibEasingRegistry`, `GeckoLibLoopTypeRegistry`, `StaticTextureSlotOwnership`, `SkeletalLoader.reloadContentFidelity`, `ContentGenerationStore.reloadLeased`, `ContentFidelityMultiVersionTest`, focused tests, launcher prepare/inspect validation, and the [native adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | The exact EMF adapter accepts raw `nbt(key,query)` expressions without executing upstream code. Escaped arguments resolve against the same bounded nested-entity context and existence/range/wildcard-path/pattern matcher used by ETF rules, keeping one predicate boundary and one per-session entity truth. | `SkeletalExpression`, `CemExpressionEvaluator`, `SkeletalFeature`, `EntityTextureContextFactory`, `EntityTextureConditions`, focused tests, and the [native adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | OptiFine's runtime is not a viable ordinary-mod path because it transforms a Mojang/Forge class surface Minosoft does not provide. The selected compatibility trajectory is Minosoft-native ingestion and rendering for selected OptiFine formats, surfaced behind exact EMF/ETF adapter identities; OptiFine itself remains a non-redistributed format reference. | `ContentFidelityCompatibilityAdapters`, `SkeletalContentParsers`, `EntityTextureRuleParsers`, `modpacks/content-fidelity/ladder.tsv`, [content-fidelity foundation evidence](../evidence/2026-07-24-content-fidelity-foundation.md), and [native adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | The exact EMF adapter's model-driven shadow and leash outputs reach native Minosoft render features without executing EMF or OptiFine renderer bytecode. Publication is identity-owned so retiring a replaced CEM instance cannot clear the replacement's outputs. The bounded shadow path projects across lit full-collision outline surfaces with size/opacity/offset/distance/light/vertical falloff, baby scaling, terrain-mutation invalidation, and the resource-pack shadow texture using audited UV/clamp/LOD/blend behavior. The leash path applies EMF additions in mob-local space, distinguishes generic, fence-knot, and player-pose holder anchors, and emits the vanilla two crossed 24-segment ribbons with quadratic sag and alternating colors. Mob roots and anchors share previous/current vanilla body-control interpolation; ribbon vertices independently interpolate endpoint block/sky light through the renderer lightmap. Dummy-GPU tests cover mesh/light replacement and retirement. Exact player-holder body-yaw dynamics, non-default shadow sampler-metadata overrides, configuration, and visual parity remain partial. | `EntityBodyRotation`, `EntityRenderInfo`, `EntityRenderEffects`, `EntityShadowProjector`, `EntityShadowFeature`, `EntityLeashProjector`, `EntityLeashFeature`, `LightColorMeshBuilder`, `LightColorShader`, `EntityRenderFeatures.SHADOW_TEXTURE`, `ShaderManager.entityShadowTextureShader`, `ShaderManager.entityLeashShader`, `SkeletalFeature`, `World.blockRevision`, `FabricBlockMutationEvents`, focused tests, and the [native adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | An active adapted Fabric pack publishes one immutable, pack-scoped mod-ID snapshot for ETF `modLoaded` predicates and removes exactly that snapshot on scope closure. Blocked/inactive candidates are excluded, so render predicates cannot mistake discovered metadata for active compatibility. | `EntityTextureRuntimeEnvironment`, `FabricPackLoader`, `FabricRegistrationScope`, and `FabricPackPreflightTest`. |
| Verified | Gecko dependent-mod adaptation has an owner-scoped, target-isolated route boundary for entities, block entities, items, and armor. Routes refer to stable content identities and retain no generation themselves; each renderer instance acquires the baked model's lease. Closure removes only the exact target route. This is a Minosoft source API, not GeckoLib/Mojang binary linkage. | `GeckoLibModelRouteRegistry`, its four target facades, `SkeletalLoader.contentModel`, focused route and loader tests, [native adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md), and [content-system documentation](../../Assets.md). |
| Verified | Gecko generic object adaptation mirrors the pinned `GeoAnimatable` manager boundary without Mojang types: exact snapshot content creates a quiescent controller manager with typed data tickets, trigger forwarding, first-tick/update time, compatible snapshots, shared instanced ownership, and a bounded singleton per-ID LRU. Closing a registration suppresses callbacks; eviction and cache closure clear managers deterministically. This remains a Minosoft source API, not GeckoLib binary linkage. | `ContentFidelitySnapshot.geckoAnimatableManager`, `GeckoLibAnimatableManager`, `GeckoLibInstancedAnimatableInstanceCache`, `GeckoLibSingletonAnimatableInstanceCache`, focused API/loader tests, and `ContentFidelityMultiVersionTest`. |
| Observed | RealisticCraft is an upstream Fabric modpack rather than a mod and has no 1.20.4 release; official Mekanism 1.20.4 targets NeoForge rather than Fabric. | `modpacks/catalog.tsv` and upstream project/version metadata checked on 2026-07-21. |
| Verified | A source-native canary compiles separately, is published as an immutable content-addressed JAR, loads through Minosoft's existing `pre` phase, and reports its new marker after a supervised recompile. | `dev/canary-mod/`, `canaryModJar`, and [2026-07-21 canary reload evidence](../evidence/2026-07-21-canary-reload.md). |
| Verified | Adapted Fabric generations register typed namespaced `summary` operations through cleanup handles; reload removes the old endpoint/provider surface and re-registers it for the replacement generation. Bounded metric/AOI provider types remain target work. | `ModDebugProvider`, `FabricDiagnosticDebugProvider`, `FabricRegistrationScope`, and [debug control-plane evidence](../evidence/2026-07-22-debug-control-plane.md). |

## Loader invariants

- Every side effect created by a mod generation has one owner and a deterministic
  cleanup handle: events, observers, commands, channels, ticks, tasks, threads,
  assets, files, UI, renderers, and GPU resources.
- A reload creates a new classloader generation. Never redefine mod classes in
  place or reuse a classloader after structural changes.
- Host API packages load parent-first; mod implementation/dependency packages are
  isolated according to an explicit policy. Split-package and host-class shadowing
  failures are rejected during preflight.
- Activation is transactional. Build/resolve/construct the candidate first; if it
  fails, keep the previous generation active. Once swap begins, quiesce old
  callbacks, activate the candidate, dispose old resources on their owning
  threads, and release the old classloader.
- No old-generation callback may run after quiescence. No host/global collection
  may retain an old-generation object after disposal.
- Production reload is opt-in. Development reload may trade continuity for speed,
  but never hide a leak or half-activated generation.

## Target loader shape

Keep these concepts behind interfaces; names are descriptive, not mandated:

| Concept | Responsibility |
| --- | --- |
| `ModDescriptor` | Normalized identity, version, environment, dependencies, entrypoints, resources, compatibility flags |
| `ModCandidate` / resolver | Discovery plus deterministic dependency/conflict resolution before code loading |
| `ModGeneration` | One immutable artifact set, classloader, entrypoint instances, state, and ownership scope |
| `ModContext` | Stable host services exposed to the generation; no raw service locator |
| `RegistrationScope` | Collects idempotent cleanup handles and closes them in reverse order/on owning threads |
| `ModEntrypoint` | `prepare`, `activate`, `deactivate`, and `dispose`; activation may return state explicitly supported for migration |
| `ReloadCoordinator` | Watches staging artifacts, debounces/hashes, finds a safe point, swaps atomically, and diagnoses failure |

Lifecycle:

```text
discover -> resolve -> prepare -> activate
                              old active
watch -> stage -> resolve -> prepare candidate -> quiesce -> swap -> dispose old
                                      failure --------^ keep old active
```

Load only complete, immutable staged artifacts. A build writes elsewhere and
publishes by atomic rename (or equivalent), so the watcher never opens a partial
JAR/directory. Resource/GPU cleanup is queued to the render thread; simulation
callbacks swap at a tick safe point.

## Fabric adaptation strategy

Fabric Loader is Apache-2.0 and largely version-independent, making its metadata
and resolution design valuable. Fabric normally has to launch/transform a
compiled Minecraft client. Minosoft owns its source and can add explicit hooks,
stable service interfaces, and disposal points instead of depending on runtime
patches for its native mod API.

Adapt Fabric Loader by capability, behind Minosoft-owned interfaces:

| Adapt/reuse candidate | Minosoft treatment |
| --- | --- |
| Metadata parsing and schema | Normalize into `ModDescriptor`; preserve unknown/unsupported fields for diagnostics |
| Semantic versions and dependency resolution | Reuse as a library or port with upstream conformance fixtures |
| Candidate discovery and nested JAR handling | Adapt to immutable staged artifacts and generation ownership |
| Environment and entrypoint keys | Map into Minosoft host capabilities and lifecycle entrypoints |
| Language adapters | Permit only adapters whose instances/resources belong to a generation scope |
| Knot launcher, target-classpath mutation, runtime remapping | Do not use for native Minosoft loading |
| Mixins/access transformation | Replace native use cases with source-level hooks; compatibility translation is separate and restart-bound |

Fabric Loader's normal runtime freezes after loading and adds mod paths to one
target classpath. That global lifecycle must not become Minosoft's reload
coordinator. If upstream code is forked, keep it in a visibly isolated adapter
module/package with its Apache notices and conformance tests so the divergence is
understandable and can be rebased.

### Client source event bridge

`FabricClientEvents` is the first general-purpose Fabric-shaped source API. It
does not load Fabric callback binaries or expose Mojang classes. Compatibility
adapters register ordered callbacks per owner and phase through their
`FabricRegistrationScope`; the host invokes those callbacks at explicit source
boundaries:

| Phase | Host boundary |
| --- | --- |
| `CLIENT_STARTED` / `CLIENT_STOPPING` | Immediately around the active render loop |
| `BEFORE_WORLD_RENDER` / `AFTER_WORLD_RENDER` | Around the world renderer pipeline |
| `BEFORE_HUD_RENDER` / `AFTER_HUD_RENDER` | Around the HUD draw, before screens and popovers |
| `START` / `END` tick | Around one ordered play-session tick cycle on its worker thread |
| `PREPARE` / `APPLY` / `COMPLETE` / `FAILED` reload | Session assets on the loading worker; shader/texture apply on the render queue |
| key / char / mouse move / scroll | After Minosoft's normalized input routing on the render thread |
| session created / state changed / joined / disconnected | On the thread that mutates the play-session state |

All phases run synchronously on their host boundary's initiating thread in
registration order. Render phases use the render thread; tick phases use the
session tick worker; session assets use the loading worker; shader/texture
reload and normalized input use the render queue/thread. Connection callbacks
use the thread changing session state. An owner may register multiple callbacks; its
diagnostic hook remains installed until its last registration closes. A
throwing callback is logged and timed but cannot abort the host boundary or
prevent later callbacks. Closing an owner registration prevents later
invocation. Preserve these ordering, thread-affinity, isolation, and ownership
semantics when adding payload or world-change phases.
Render phases and tick phases intentionally have different thread affinity.
Adapters must not perform OpenGL work from a tick callback; use a render-phase
callback or enqueue onto the render context instead.

World, chunk, block, entity, payload, command, interaction, and media callbacks
run on the thread that owns the corresponding native operation. Chunk/world
locks are released before lifecycle callbacks. Screens and HUD add/remove work
always cross the render queue. Payload callbacks receive an immutable view and
must request a copy before decoding mutable bytes. The client payload source API
is capped at one MiB; its bound does not retroactively change legacy vanilla
channel handlers.

Hook families that lack a symmetric owner/removal or candidate-swap boundary
remain in the [Fabric hook backlog](../backlog/fabric-hooks.md). Do not advertise
those families as mapped until their listed acceptance gate passes.

## Fabric bridge: compatibility levels

| Level | Meaning | Initial position |
| --- | --- | --- |
| Metadata | Read `fabric.mod.json`: ID/version, environment, entrypoint keys, dependency/conflict/provides predicates, and recursive nested-JAR providers | **Verified parser and pinned-pack resolution subset** |
| Source API | A native Minosoft mod can register a pinned-version compatibility adapter and owned host hooks before pack preflight | **Verified adapter subset; stable external API remains** |
| Binary API | Classes compiled against selected Fabric API interfaces link unchanged | Later, per explicitly implemented API module |
| Behavior | Semantics match Fabric/Minecraft closely enough for the mod to behave correctly | Must be proven feature by feature |

First bridge slice:

1. Normalize `fabric.mod.json` into `ModDescriptor`; retain Minosoft's manifest
   through a separate adapter. Start with upstream metadata fixtures.
2. Support `main` and `client`-shaped entrypoint keys through Minosoft-owned
   interfaces/context. A Fabric class name in metadata alone does not create
   binary compatibility with `ModInitializer`.
3. Reuse or adapt Fabric version/dependency semantics behind the resolver API.
   Prefer direct dependency on stable public pieces; otherwise isolate a small
   maintained fork and retain required Apache notices.
4. Map `assets/<namespace>/...` into the mod generation's assets manager.
5. Reject unsupported features with structured diagnostics.

Initially unsupported/restart-required:

- Mixins, access wideners/class tweakers, or arbitrary host-class transformation.
  They alter already loaded host classes and are not a clean unload boundary.
- General Fabric API or Minecraft binary compatibility. Minosoft reimplements the
  client and does not expose Mojang/Fabric runtime classes.
- `server` entrypoints until Minosoft defines an actual server host; the current
  local connection is not a Fabric dedicated server.
- Native libraries, unmanaged threads, and unscoped global mutation.

## Trajectory

Rendering compatibility follows the
[render-substrate target architecture](../backlog/render-substrate.md).
`ADAPTED` continues to mean that an exact artifact reached an owned native
boundary; it does not mean the mod owns the behavior expected by its render
contract.

Advance the render-mod ladder in this order:

1. Keep the completed canonical graph, typed program/target generations, and
   built-in providers green across base, headless, and multi-version profiles.
2. Treat SM0–SM6 as reported support work absorbed into the production substrate
   and dated checkpoint. Do not retain synthetic duplicate graph or matrix APIs.
3. Move the exact pinned Sodium algorithms behind the exclusive terrain backend:
   scheduling, meshing, upload, visibility/batching, and graph-view submission.
4. Expand the Iris shader-pipeline provider against an independent pinned pack:
   program selection, views, typed targets, uniforms/samplers, and composites.
5. Complete texture/layout transactions and driver-side resource accounting,
   then prove the producer-rich visual matrix, combined ownership, repeated
   reload/unload, and percentile performance gates.

Keep Mojang/Fabric type translation at exact compatibility modules. Do not grow
a general Mojang renderer facade in the substrate, run the built-in and Sodium
terrain paths concurrently, or generalize shader-pack support from the bounded
project reference pack. The completion function in the target architecture is
the gate for closing this trajectory.

## Compatibility ladder workspace

`modpacks/<pack>/` is the tracked testing space. It contains Packwiz manifests,
an aggregate `fabric.mod.json`, and `ladder.tsv`; it never contains downloaded
mod JARs. `util/play/Play.java` resolves those immutable inputs into an out-of-source
content store:

```text
artifact store
├── artifacts/<hash-format>/<hash>/<file>  # shared, verified bytes
├── packs/<pack>/<manifest-fingerprint>/   # immutable mods + resource-pack view
├── shared/assets/                         # reusable client assets
└── trajectories/<name>/<pack>/            # mutable home/profile state
```

`resourcepacks/*.pw.toml` entries follow the same URL/hash/index contract as
mods but are never inspected as Fabric JARs or placed on a classpath. The parent
atomically writes their immutable staged paths into the selected trajectory's
resources profile immediately before client launch. Later filenames have higher
managed asset priority; existing manual profile packs remain higher still.

Use the ladder as evidence, not a wish list: advance a rung only when its stated
acceptance evidence exists. The pinned Sodium version now activates in
`ADAPTED` mode: its Mojang-targeted bytecode is deliberately not linked.
`SodiumCompatibilityAdapter` validates the known metadata surface and installs
an owned, graph-exclusive terrain provider. Its current implementation delegates
the Minosoft terrain core, so this is source-API adaptation, not a claim of
Fabric/Minecraft binary or full Sodium behavioral compatibility.

The separate `fabric-stack` pack keeps that one-mod baseline intact and adds
Fabric API plus seven different compatibility surfaces. Entity Culling maps to
Minosoft's existing frustum/CPU-occlusion decision; ImmediatelyFast maps to the
retained renderer's bounded queue-flush boundary. Inventory Management maps its
first workflow to an owned container-screen extension and delegates
sort/transfer/stack mutations to the exact pinned upstream mod on the
launcher-owned Fabric server. Iris selects an owned transactional shader
generation and executes the project's bounded terrain/shadow/final reference
pack. Independent shader-pack compatibility and broader program semantics are
still gated. JEI maps an
owned container control to a paged view of the current session's synchronized
recipe registry. Its ingredient overlay, transfer, search, bookmarks, and plugin
APIs remain explicitly unmapped. GeckoLib supplies the exact 1.20.4 content
contract used by Naturalist's adapter. The ported Naturalist `5.0.0-pre.4`
artifact targets 1.20.4 and is hash-pinned through the portable cache. The
managed Fabric server executes its registry, AI, and spawning behavior beside
GeckoLib; the Minosoft client does not execute that Mojang-targeted bytecode.
Instead, its exact adapter filters unsupported geometry, routes 31 animal entity
identifiers across 24 content identities, and selects deterministic
idle/movement clips. Remote registry negotiation and state-specific skins
remain unmapped. Mojang client bytecode, mixins, access wideners, and
Fabric/Mod Menu entrypoints are not executed.
General remote-server negotiation and revisioned native transaction plans remain
backlog work. The adapted artifacts' upstream bytecode, mixins, access wideners,
nested libraries, and Fabric API modules are inspected but not executed.
The Packwiz hash pins artifact bytes, while the adapter validates the expected
metadata surface. An upstream artifact change therefore needs a manifest update
and adapter review.

```sh
./play.sh modpack list
./play.sh modpack prepare sodium --trajectory sodium-main
./play.sh modpack inspect sodium --trajectory sodium-main
./play.sh dev --modpack sodium --trajectory sodium-main
./play.sh modpack inspect fabric-stack --trajectory compatibility-main
./play.sh dev --modpack fabric-stack --trajectory compatibility-main
./play.sh modpack inspect tech-reborn --trajectory industrial-main
./play.sh dev --modpack tech-reborn --trajectory industrial-main
./play.sh modpack prepare content-fidelity --trajectory content-fidelity-main
./play.sh modpack inspect content-fidelity --trajectory content-fidelity-main
```

The final command keeps the Java utility as Minosoft's parent and cleanly restarts
the client after successful candidate builds. For this pinned artifact it
activates the compatibility adapter and emits machine-searchable pack, hook
installation, hook invocation, and hook unload evidence. Add future mods as
immutable Packwiz entries and separate ladder rungs so failures stay attributable
while the pack grows. Unknown versions or surfaces remain blocked by default.

Generalization boundary: the registry, ownership scope, diagnostics, and hook
lifecycle are reusable. Semantic behavior is not inferred from a mod ID or mixin
name; every advertised capability still needs an explicit host contract and a
behavioral acceptance test. `ADAPTED` must never be reported as Fabric binary or
full upstream behavior compatibility.

`modpacks/catalog.tsv` records requested candidates that cannot enter the active
Minecraft/loader baseline. Do not create fake Packwiz mod entries for a whole
upstream modpack, a different Minecraft version, or a non-Fabric artifact. Give
those candidates a separate trajectory and keep the incompatibility explicit.

The `tech-reborn` ladder remains separate from `fabric-stack`. In addition to
artifact/dependency/capability activation, the exact artifact now supplies a
fingerprinted 310-block, 629-item, 3,814-state registry surface and 15 ore
features to the source-native local authority. This proves registration,
property/palette mapping, deterministic local generation, and standard asset
ingestion. It does not mean full Tech Reborn gameplay is loaded: external-server
agreement, custom model loaders, recipes, machines, persistence, drops, energy
networks, menus, and payload semantics remain separate gates.

The `content-fidelity` ladder is also separate. It pins exact Fabric API,
Entity Texture Features, Entity Model Features, and GeckoLib 1.20.4 artifacts.
All four artifacts now select exact source-native adapters while their upstream
Minecraft bytecode remains inactive. ETF rules/material state, EMF CEM
geometry/expressions, and GeckoLib geometry/animation data cross headless
parsers and generation-owned registries. A bounded Gecko evaluator/controller
can apply matched clips to retained bones; the source-native facade also
provides predicate decisions, concurrent replace/add layers, transitions,
owner-scoped custom easing/loop registration, bounded raw animation stage
queues with stable finished identity and explicit reset, and generation-owned
animatable caches. Stable content identities now route adapted entity types to
owner-scoped controller factories. Generation-baked opaque, translucent, and
additive texture passes evaluate entity-state predicates without retaining a
closed registration or claiming GeckoLib/Mojang binary linkage.
Version/entity aliases now affect CEM geometry, transforms, and
expressions. Per-instance CEM evaluation reads live aliased pivot, rotation,
scale, visibility, and box-hidden state, applies ordered absolute writes,
rejects missing targets, and keeps subtree visibility distinct from local-box
hiding. Its centralized live EMF context now owns partial-tick clocks, frame
counters, degree/radian and body-relative rotations, persistent vanilla limb
interpolation, movement projection, dimensions, positions, health and
hurt/death animation timers,
equipment/use, attachment, locomotion, tame/aggressive/anger, bounded
fluid/ground probes, exposed-rain wetness using the pinned altitude/frozen-biome
temperature samplers, hover targeting, and the selected
ETF rule index. A caller-owned context carries first-person, held, item-frame,
GUI, head, and shoulder flags, and stopped colliding arrows expose in-ground
state. Every catalogued input now has an explicit source except the intentional
IEEE `nan` constant; invoking CEM from those non-world render paths,
and entity-specific special cases remain explicit gates. ETF
contexts include bounded entity/client-player/vehicle NBT, biome tags,
pack-scoped active-mod IDs, equipment and item inputs, general mob variants,
genes, inventory/jump/movement attributes, and predicate-gated vertical block
identifiers in addition to team, profession, color, tame, and movement inputs.
Animated Java prerequisites now include last-match
item predicates, arbitrary cuboid item geometry, display rendering, separate
resource/data-pack mounting, a generation-leased local function runtime,
scoreboard/storage/SNBT state, storage/entity macros, bounded
execute/selectors, display/interaction passenger trees, entity
mutation/lifecycle commands, packet-driven reward callbacks, and atomic
failed-load rollback. Item/block/text rendering now shares mapped-1.20.4
view-range and visibility-box semantics plus retained transformation, shadow,
text-style, and teleport-pose interpolation. A reduced fixture pinned to
exporter 1.10.2 proves load,
summon, tween-guarded tick mutation, and removal. Returns reached through
`execute` terminate the owning function without escaping a called function's
boundary; fingerprinted upstream compiler templates drive that regression,
callback, and exact signed UUID conversion tests.
A feet/eyes command anchor and coordinate/entity `execute facing` semantics now
survive nested function context and are covered against real 1.20.4 entities.
A durable headless fixture also verifies one CEM/ETF/Gecko content set across
1.19.4 and 1.20.4, including version-specific aliases, animation evaluation,
and variant/emissive material discovery.
The render-thread `/reload content` path reparses that registered content,
CPU-bakes and uploads the candidate models and material meshes, acquires the
candidate generation leases during commit, atomically replaces skeletal
model/entity routing, and flags existing source-native entity renderers for
instance recreation. Old retained instances keep their GPU buffers and ETF
catalog/cache alive until release. A candidate that needs a texture absent from
the already-uploaded static array fails before publication and leaves the old
generation active.
This advances artifact adaptation and selected runtime behavior, not full
compatibility: complete broader ETF non-skeletal/block-entity feature textures,
complete EMF catalogs/attachments and the remaining live input semantics, diagnostic parity,
Gecko binary APIs, GUI item
views, exact armor fitting, real dependent-mod fixtures, additional Animated
Java blueprints that expose new command or asset surfaces, repeated real-GL
accounting, remote-server acceptance, and visual fixtures remain explicit
gates.
OptiFine is not staged because it is a non-Fabric transformation runtime and
conflicts with EMF/ETF; it remains a CEM format reference. Animated Java is a
Blockbench export workflow rather than a runtime JAR. Its remaining gates are
glowing/team outlines, real-GPU reload accounting and rendered-reference
comparison for the passing pinned upstream export, expansion
with additional blueprints where they expose new command or asset surfaces,
remote-server behavior, and visual acceptance. Headless repeated runtime
reload/rollback and CPU-generation cleanup now pass. The exact authoring-side
reproduction and artifact hashes are in
the [Blockbench export evidence](../evidence/2026-07-24-animated-java-blockbench-export.md). The
[display semantics evidence](../evidence/2026-07-24-animated-java-display-semantics.md)
records the implemented renderer-state boundary. The
[content-system contract](../../Assets.md) and tracked ladder define the
acceptance sequence.

## Source-native extension policy

When a mod needs a hook that Fabric would normally obtain with a mixin, first
decide whether it is a generally useful, lifecycle-safe Minosoft hook. If so, add
it to source with:

1. a stable event/service contract owned by the kernel or client API;
2. a registration handle tied to `RegistrationScope`;
3. an explicit execution thread and ordering policy;
4. an exception/isolation policy;
5. a test proving registration, invocation, removal, and generation collection.

Do not mirror every Minecraft internal injection point. Stable domain operations
and events are more understandable and survive Minosoft refactors. A Fabric
compatibility adapter may translate a supported Fabric API event onto one of
these hooks, with behavioral tests documenting the match.

## Developer loop

1. A separate mod project runs its fast compile/test task continuously.
2. Successful output is staged atomically into the watched development directory.
3. The coordinator hashes and preflights metadata/dependencies without disturbing
   the active generation.
4. It prepares the candidate classloader/context, requests a simulation/render
   safe point, and swaps.
5. Diagnostics report generation ID, timings, retained old-generation references,
   and whether restart is required.

Core host code can use the debugger/JBR fast path, but the clean client workflow
uses whole client-generation replacement. Mod-only edits replace affected child
generations as the target architecture. The current canary lane narrows the
compile to the mod but still replaces the whole client process; it is the
acceptance fixture for introducing the disposable mod-generation boundary. This
permits structural edits while keeping loader/API types stable and makes every
retained old-generation reference a diagnosable bug.

## Delivery gates

1. Lifecycle scope: load/unload the dummy mod repeatedly; listener/observer/task
   counts return to baseline and the old classloader is collectible.
2. Atomic reload: valid artifact swaps; invalid artifact preserves the old mod;
   dependency changes resolve deterministically.
3. Resource reload: assets and one render-thread resource swap without leaks.
4. Fabric metadata: fixture coverage for environment, entrypoints, version ranges,
   depends/recommends/suggests/breaks/conflicts/provides, and unsupported features.
5. Developer sample: a minimal external mod rebuilds and reloads during a running
   local session with measured feedback time.
6. Compatibility matrix: every advertised Fabric API surface has binary and
   behavioral tests; everything else fails explicitly.
7. Client generation: reload the client and all child mods together; reconnect
   cleanly and prove every old classloader is collectible.

## References

- [Existing Minosoft modding notes](../../Modding2.md)
- [Content and asset system](../../Assets.md)
- [Fabric Loader overview](https://docs.fabricmc.net/develop/loader/)
- [`fabric.mod.json` specification](https://docs.fabricmc.net/develop/loader/fabric-mod-json)
- [Fabric Loader source and Apache-2.0 license](https://github.com/FabricMC/fabric-loader)
- [Fabric API source](https://github.com/FabricMC/fabric-api)
