<!-- Copyright (C) 2026 Jacob Repp -->

# Debug control-plane grounding

## Boundary and status

Minosoft now has a version-one local debug control plane shared by the client,
the pinned Fabric 1.20.4 development server, repository CLI, and adapted mod
providers. It owns endpoint discovery, authenticated local IPC, framed messages,
state/visual/input/block operations, and generation-scoped mod diagnostics.

“Debug pipe” is the product-level name. The transport is a Unix-domain socket on
macOS/Linux and an owner-restricted Windows named pipe. The endpoint is opt-in
through `MINOSOFT_DEBUG`; `Play` enables it for managed development launches.

The control plane is not remote administration. It has no TCP listener, arbitrary
evaluation, reflection browser, unrestricted filesystem access, or server
mutation API. One-shot version-one operations are implemented; subscriptions,
advanced AOI layers, and connection-owned input state remain explicit later
stages. The CLI now composes one-shot operations into checked scenario files.

## Current evidence

| Status | Claim | Evidence |
| --- | --- | --- |
| Verified | `debug-core` is a GUI-independent Java library containing discovery, credentials, framing, transports, operation ownership, deadlines, server, and client. | `debug-core/` and `:debug-core:test`. |
| Verified | The compiled Java play utility is the only CLI protocol consumer; `play.sh` is a thin Java 25 shim that rejects other runtime features before Gradle or the CLI starts. | `util/play/Play.java`, `play.sh`, and `PlayUtilityTest`. |
| Verified | The client publishes an opt-in endpoint and queues visual/input work onto the render path while state/block reads use explicit session/world seams. | `ClientDebugChannel`, live visual/input/AOI acceptance, and the dated evidence below. |
| Verified | User and agent captures share `ScreenshotTaker`'s final-framebuffer snapshot. `visual.capture` returns PNG dimensions, frame/time, top-left RGBA8 semantics, a suggested vanilla-style filename, the user screenshot directory, and a SHA-256 verified by the CLI after writing. An omitted CLI output chooses a collision-safe `.run/agent-screenshots/<endpoint trajectory>/` artifact; an explicit path remains supported. | `ScreenshotTaker`, `ClientDebugChannel.capture`, `Play.debugVisual`, live F2/debug captures, and [screenshot/terrain evidence](../evidence/2026-07-28-screenshot-and-terrain-stability.md). |
| Verified | `debug visual motion-noise` measures camera-induced temporal residuals at an exact returned pose rather than comparing different views. It pairs each yaw-away/return capture with a stationary control at the same elapsed render-frame delta, reports whole-region luma/RGB errors plus a low-gradient speckle ratio, records requested and actual checkpoints, and writes bounded representative crops plus `report.json`. A compare-and-set `visual.background-throttle` override keeps terminal-owned probes at normal cadence without persisting a rendering-profile change, then restores only the state the command acquired. | `MotionNoiseAnalyzer`, `Play.debugMotionNoise`, `RenderContext.backgroundThrottleOverride`, `ClientDebugChannel.configureBackgroundThrottle`, focused utility tests, and [camera-motion noise evidence](../evidence/2026-07-28-camera-motion-noise-measurement.md). |
| Verified | The pinned Fabric 1.20.4 server runs an owned bridge using the same library and exposes tick-thread state, blocks/AOI, and Fabric loader diagnostics. | `debug-server-fabric/`, `Play.prepareFabricServer`, and live server acceptance. |
| Verified | Adapted Fabric mods add namespaced provider operations owned by the active pack generation. Iris exposes a bounded render-queue shader-pipeline reload, while the client exposes one bounded `render.substrate` snapshot for graph/provider/resource/timing acceptance. Shader diagnostics include linked frame/draw upload counts and bounded per-state keys, allowing live acceptance to distinguish compilation from actual dynamic entity/block/item identity delivery. | `FabricDiagnosticDebugProvider`, `IrisDebugProvider`, `ClientDebugChannel`, `FabricRegistrationScope`, [R0–R7 checkpoint](../evidence/2026-07-24-render-substrate-r0-r7.md), and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | Root `metrics.snapshot` exposes compact production terrain gauges, outcomes, bytes, worker utilization, and phase percentiles. `render.substrate.terrain.productionRuntime` adds the fixed histogram bounds/buckets, while `render.terrain-telemetry` toggles recording on the render thread for identical-workload A/B acceptance only at an idle build/upload boundary. Disabling recording performs no phase clock reads or counter/gauge mutation. | `ClientDebugChannel`, `TerrainPerformanceTelemetry`, focused telemetry tests, and [terrain runtime architecture](../../design/terrain-runtime-architecture.md). |
| Verified | Agent performance diagnosis now requires matched trajectory/runtime inputs, warm-up separated from measurement, before/after cumulative-counter deltas, at least 100 comparable observations for a p95 claim, interleaved A/B intervals, explicit histogram bounds, and retained JSON/JUnit artifacts. Instrumentation-overhead A/B remains separate from baseline/candidate comparison, and detailed `render.substrate` snapshots are boundary observations rather than a polling surface. | Root `AGENTS.md` live-runtime contract and [scenario performance-measurement guidance](../acceptance/scenarios.md#performance-measurement-guidance). |
| Verified | Successful Iris scene and terrain bind/draw diagnostics use preassigned integer route IDs with dense primitive arrays; fallback routes use a fixed-capacity primitive counter table. High-cardinality rejected-bind, render-stage, draw-state, and entity-color traces are sampled once per 256 eligible events. Human-readable keys and maps are built only by the requested `render.substrate` snapshot. | `IrisWorldShaderPipeline`, `NumericDiagnosticCounters`, and focused counter tests. |
| Verified | `render.substrate.shaderPrograms.depthSnapshots` reports bounded cumulative counts for main pre-translucent, pre-hand, and shadow pre-translucent depth-copy boundaries. Together with typed shadow routing, selected terrain binds, and the independent terrain submission ledger, this distinguishes merely planned `shadowTranslucent` support from an executing `shadowtex0`→`shadowtex1` separation followed by a real translucent shadow draw. | `WorldShaderPipelineDiagnostics.depthSnapshots`, `IrisWorldShaderPipeline.snapshotDepth`, `ClientDebugChannel.renderSubstrate`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.shaderPrograms` now reports the non-zero physical dimensions of Minosoft's 16 texture-array sampler slots plus cumulative linked `uTextureSizes` uploads. This proves the Iris transformed-pack atlas semantic uses the actual dynamic/font/static bucket table rather than a last-bound draw-wide guess, without changing the wire operation or exposing texture contents. | `ClientDebugChannel.renderSubstrate`, `IrisTextureArrayState`, `TextureManager.shaderTextureSizes`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.materialAnimations` reports bounded authored companion resource identities plus static-texture/channel counts, monotonic advance/upload totals, and the last frame index actually published by each channel on the render thread. `visual.sample` returns that same bounded state beside its framebuffer sample, making pixel and companion phase one atomic observation. Neither operation exposes texture bytes or mutable animation objects. The managed Iris LabPBR run reported the exact sand `_n`/`_s` resources and two advances/uploads per rendered frame; its four constant-diffuse phases then correlated neutral, normal-only, specular/emissive-only, and combined uploads with four non-overlapping framebuffer luminance ranges. | `ClientDebugChannel.renderSubstrate`, `ClientDebugChannel.visualSample`, `OpenGlTextureArray.materialAnimationDiagnostics`, `OpenGlMaterialAnimationDiagnostics`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `visual.prepare-reference` can independently suppress entity and particle presentation without clearing simulation state, visibility, animation, retained meshes, or particle queues. A Complementary A/B used those controls to prove a rectangle-like apparent duplicate rendering came only from the particle pass; entity-only rendering stayed clean. | `ClientDebugChannel.prepareVisualReference`, `EntitiesRenderer.referenceSuppressed`, `ParticleRenderer.referenceSuppressed`, live checked captures, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.shaderPrograms.customShaderTextures` reports the active generation's bounded sampler-to-source descriptors, while `programSamplers` reports the resolved custom texture identity beside ordinary shader buffers. It exposes dimensions/filter/wrap and generated-versus-pack provenance without texture bytes or OpenGL names, so live reload acceptance can prove a custom sampler is planned, uploaded, retained by a linked program, and retired back to the same global resource baseline. | `ClientDebugChannel.renderSubstrate`, `WorldShaderPipelineDiagnostics.customShaderTextures`, `IrisWorldShaderPipeline.diagnostics`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.shaderPrograms.selectedProfile` reports the profile whose effective option constraints selected the active Iris generation, or null when no profile matches. It is captured from the immutable published plan, so live acceptance can prove profile-driven program suppression rather than inferring it from settings state. | `ClientDebugChannel.renderSubstrate`, `WorldShaderPipelineDiagnostics.selectedProfile`, `IrisShaderPackPlanner`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.shaderPrograms.programStages` reports each planned graphics root's bounded ordered stage chain (`vertex`, optional `tess-control`/`tess-evaluation`, optional `geometry`, `fragment`). This lets live acceptance distinguish a genuinely retained staged program from a vertex/fragment fallback without exposing source text or OpenGL names. | `WorldShaderPipelineDiagnostics.programStages`, `IrisWorldShaderPipeline.diagnostics`, `ClientDebugChannel.renderSubstrate`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.shaderPrograms.blendOverrides` reports bounded typed program/per-output Iris blend rules, while `appliedBlendOverrides` counts only explicit rules whose logical output was actually attached. This distinguishes a valid dormant option branch from indexed driver execution. The Complementary WSR run reported water outputs `0,3,6,4,8` and equal non-zero `colortex4`/`8` application counters before and after shader-generation replacement. | `WorldShaderPipelineDiagnostics`, `IrisOpenGlRenderTargets.appliedBlendOverrides`, `ClientDebugChannel.renderSubstrate`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.gpuResources.capabilities` reports bounded current-context driver identity, OpenGL 4.0/4.2/4.3/4.4, `ARB_clear_texture`, and `ARB_draw_buffers_blend` bits, plus derived Iris tessellation, per-buffer blending, render-target image, deterministic custom-image, compute, and shader-storage reachability. The snapshot runs on the owning render queue, so acceptance can distinguish an implemented path from one disabled by the negotiated driver. The restored Apple M4 Max process reports OpenGL 4.1, tessellation and per-buffer blending true, and every 4.2/4.3 image/compute/storage gate false. | `OpenGlCapabilityDiagnostics`, `ClientDebugChannel.renderSubstrate`, focused capability tests, the opt-in real-GL tessellation fixture, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.shaderPrograms` reports the exact compiled main/shadow scene route sets and distinguishes selected routes, candidate rejections caused by missing retained uniforms, and final host fallbacks. `selectedSceneContracts` retains the requested semantic and family plus the selected pack root and host vertex/state ABI, so acceptance does not confuse a fallback filename with the contract the producer requested. Fallback keys carry the same host-side identity. This bounded evidence identified skeletal block entities whose `BLOCK_ENTITIES` semantic had no `gbuffers_block` skeletal candidate; after adding that bridge, the live route selected and both rejection/fallback maps returned empty. It also proved ordinary translucent held-item faces requested `HAND_WATER` even though Complementary selected its `gbuffers_hand` fallback root. | `WorldShaderPipelineDiagnostics`, `IrisWorldShaderPipeline.bindScene`, `ClientDebugChannel.renderSubstrate`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.substrate.frameInputValues` reports bounded current scalar frame inputs selected for live shader acceptance. It exposes Iris mood values, the complete pinned dimension world-information family, individual current/interpolated player-vector components, cloud time, output color-space ordinal, and selected-block material ID plus camera-relative position. This lets the live lane distinguish real server dimension/camera/outline producers from implicit defaults without adding a new operation. A managed Complementary run reported `-64/192/384` world bounds, finite normalized direction vectors, progressing cloud time, sRGB ordinal zero, selected sand ID 10232 at its finite relative center, and the exact `0`/`vec3(-256)` absent-target sentinel after camera restoration. | `ClientDebugChannel.renderSubstrate`, `WorldShaderPipelineDiagnostics`, `BlockOutlineRenderer.irisSelectedTarget`, `IrisWorldShaderPipeline.diagnostics`, and [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | On OpenGL clients, `render.substrate` reports cumulative created/deleted and current live GPU names globally and by resource type. The snapshot executes on the render queue and is suitable for repeated-reload baseline assertions without changing the debug wire protocol. | `ClientDebugChannel.renderSubstrate`, `OpenGlResourceTracker`, focused tests, and [OpenGL resource-accounting evidence](../evidence/2026-07-24-opengl-resource-accounting.md). |
| Verified | `render.reload-content` executes the production content-fidelity reload transaction on the render queue and returns the published content generation. Optional bounded `rejectAt` values `after-upload` and `after-publication` exercise candidate cleanup and lookup/texture rollback without mutating mounted content. Expected rejection returns before/after generation plus typed GPU deltas; Animated Java and EMF/ETF real-GL lanes prove zero live delta, retained pixels/functions, and accepted recovery. | `ClientDebugChannel.reloadContent`, `ContentReloadRejectionPoint`, `FabricResourceReloadEvents`, `animated-java-rejected-reload.json`, `emf-etf-zombie-render-reference.json`, [Animated Java Blockbench export evidence](../evidence/2026-07-24-animated-java-blockbench-export.md), and [EMF/ETF render evidence](../evidence/2026-07-26-emf-etf-render-reference.md). |
| Verified | `content.execute-local` invokes one bounded mounted data-pack function through the production local session runtime, with explicit origin/player-camera poses. `client.entities` exposes bounded IDs, UUIDs, tags, passenger counts, glowing/fire state, and item-display custom-model-data for lifecycle/render assertions. The operation survives content reload because mounted asset/data-pack managers remain session-owned. | `ClientDebugChannel.executeLocalContent`, `LocalConnection.executeDataPackFunction`, `SessionDataPackRuntime`, `PlaySession.SessionAssetsCandidate`, and the [Blockbench acceptance protocol](../acceptance/blockbench.md). |
| Verified | `render.prepare-entity-flame` toggles only one retained visible non-player entity's synchronized client fire flag, accepts an optional exact entity ID, returns the previous value, and supports explicit restoration. It is a bounded render-producer canary for remote worlds whose command tree is unavailable; it does not mutate authoritative server state. A live set/sample/reset sequence proved the Iris main/shadow flame paths and restored the target to its prior state. | `ClientDebugChannel.prepareEntityFlame`, `Entity.isOnFire`, `core.capabilities`, `client.entities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-entity-outline` toggles only one retained visible non-player entity's synchronized client glowing flag, accepts an optional exact entity ID, returns the previous value, and supports explicit restoration without server authority. The canary exercises the complete renderer-owned outline mask and composite. A live Complementary capture showed one outlined villager after the internal-target fix, while rejected/fallback scene maps remained empty and the prior flag was restored. | `ClientDebugChannel.prepareEntityOutline`, `Entity.hasGlowingEffect`, `EntityOutlineRenderer`, `core.capabilities`, `client.entities`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-entity-eyes` selects one retained non-player skeletal feature, temporarily routes its already resolved base material through the production emissive layer, and restores both the exact material override and prior visibility override. It does not add geometry, replace an asset, or change server/entity state. A live Complementary cycle selected the exact `ENTITY_EYES`/`gbuffers_spidereyes` contract and stopped its counter after restore with empty rejection/fallback maps. | `ClientDebugChannel.prepareEntityEyes`, `SkeletalFeature.referenceEmissiveBaseOverride`, `core.capabilities`, `client.entities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-leash` attaches one retained non-player living entity to the local player through the normal synchronized client attachment and conditionally restores the exact prior holder and visibility override. The production `EntityLeashFeature`, EMF-adjusted mob anchor, player holder anchor, endpoint light interpolation, retained ribbon mesh, and `LEASH` scene contract remain authoritative. The canary exposed a real modern `gbuffers_basic` host fallback; after adding the missing specialization, Complementary selected the exact light-color route with empty rejection/fallback maps. | `ClientDebugChannel.prepareLeash`, `EntityAttachment.leashHolder`, `EntityLeashFeature`, `IrisLegacyShaderTransformer`, `core.capabilities`, `client.entities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-vanilla-armor` equips one retained humanoid with a client-only enchanted, gold-trimmed iron set, reports exact prior/current stacks plus resolved base/trim/glint state, accepts an optional exact entity ID, and restores the saved equipment. The operation is bounded to armor slots and does not mutate server authority. Driver-bound diagnostics proved Complementary receives the `PLAYER_SKELETAL/PLAYER` armor-glint geometry; removal now gives retained armor one final synchronization update so base/trim/glint entries retire, and consecutive samples prove the draw/vertex counters stop. | `ClientDebugChannel.prepareVanillaArmor`, `VanillaArmorFeature`, `core.capabilities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-creeper-overlay` holds one retained creeper at a pinned client-only fuse counter and temporarily overrides only that renderer's acceptance visibility, then restores both exact prior values. `render.substrate.shaderPrograms.entityColorBinds` reports bounded quantized RGBA keys only for real uploads to linked programs retaining `entityColor`. A live set/sample/reset sequence proved the exact nonzero white overlay reached both opaque and translucent Iris entity programs without server mutation. | `ClientDebugChannel.prepareCreeperOverlay`, `EntityRenderer.referenceVisibilityOverride`, `WorldShaderPipelineDiagnostics.entityColorBinds`, `core.capabilities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-item-entity` adds one bounded client-only vanilla dropped-item entity through the production entity manager, with optional item and camera-relative distance, then removes that exact retained entity. It proves ordinary item-model geometry selects the Iris entity family while preserving the block-feature physical ABI without mutating server state or inventory. | `ClientDebugChannel.prepareItemEntity`, `ItemFeature`, `core.capabilities`, `client.entities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-translucent-held-item` equips the local player with one bounded client-only vanilla item stack, optionally by exact resource identifier, and restores the exact prior main-hand stack. Combined with `selectedSceneContracts`, live ice, honey-block, slime-block, glass, and tinted-glass canaries proved ordinary item faces select both `HAND` and `HAND_WATER` without server inventory mutation or a whole-model replay. | `ClientDebugChannel.prepareTranslucentHeldItem`, `HeldItemMaterialConsumer`, `WorldShaderPipelineDiagnostics.selectedSceneContracts`, `core.capabilities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-world-border` installs one bounded static client-only border centered on the local player, accepts a finite radius from 2 through 64 blocks, and restores the exact saved center, `BorderArea` instance, and renderer suppression flag. Its response reports prior/current center and radius plus the corrected current distance, so route acceptance does not require server commands or permanently alter the synchronized border. | `ClientDebugChannel.prepareWorldBorder`, `WorldBorder.getDistanceTo`, `WorldBorderRenderer.referenceSuppressed`, `core.capabilities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-lightning` adds one bounded client-only lightning entity at a finite one-to-16-block distance through the production world entity manager and removes that exact instance on restore. It reserves one negative client-only entity ID, never mutates server authority, and uses the ordinary renderer factory, retained lightning feature, translucent entity layer, and scene contract rather than issuing a debug draw. A live Complementary set/sample/reset sequence selected the exact lightning route 320 times; after removal the counter remained fixed across 70 more frames with empty rejection/fallback maps. | `ClientDebugChannel.prepareLightning`, `WorldEntities`, `LightningBoltRenderer`, `LightningBoltFeature`, `core.capabilities`, `render.substrate`, focused renderer coverage, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-primed-tnt` adds one bounded client-only primed-TNT entity through `WorldEntities` and removes that exact instance on restore. The normal renderer factory, TNT block model, `FlashingBlockFeature`, entity graph layer, and physical shadow traversal remain authoritative. A live Complementary cycle selected both main and shadow `FLASHING_BLOCK` contracts with empty rejection/fallback maps; it also exposed and led to correction of the primed-TNT gravity sign. | `ClientDebugChannel.preparePrimedTnt`, `PrimedTNT`, `PrimedTNTEntityRenderer`, `FlashingBlockFeature`, `PrimedTNTPhysics`, `core.capabilities`, `render.substrate`, focused renderer/physics coverage, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-beacon` locates one empty loaded client position with a bounded clear column, places the registry's real beacon block through `World.set`, activates the resulting `BeaconBlockEntity`, and restores the exact prior air state. Normal mutation events, section remeshing, block-entity creation/removal, `ChunkRenderer` collection, and the retained beam renderer remain authoritative; the operation never replaces an existing block or block entity. A live Complementary run selected the exact beacon-beam route, restored air, and reached a stable post-remesh counter with empty rejection/fallback maps. | `ClientDebugChannel.prepareBeacon`, `World.set`, `BlockEntityDataProvider`, `ChunkRendererChangeListener`, `BeaconBeamRenderer`, `core.capabilities`, `render.substrate`, focused block-entity/remesh/renderer tests, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-sign-text` places one real oak sign only in empty loaded client space, applies four bounded glowing lines through the production block-entity-data boundary, reports the resolved block/model/entity/line state, and restores the prior air state. `render.substrate.terrain.visibleMeshes` reports bounded mesh, vertex, load-state, and occlusion totals per terrain material so acceptance can distinguish missing glyph meshing from a skipped Iris submission. A live Complementary cycle selected the emissive-additive terrain route, removed the retained text mesh on restore, and retained empty rejection/fallback maps. | `ClientDebugChannel.prepareSignText`, `ClientDebugChannel.renderSubstrate`, `Chunk.applyBlockEntityData`, `ChunkMeshingQueue`, `core.capabilities`, focused chunk/remesh tests, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-block-break` selects one existing nearby model-bearing client block, publishes or removes one reserved-ID remote-break event through the normal event bus, and reports the exact retained-instance state. It does not change the block or server authority. A live Complementary cycle selected the exact damaged-block contract and removal stopped the counter with zero retained instances and empty rejection/fallback maps. | `ClientDebugChannel.prepareBlockBreak`, `BlockBreakRenderer.hasInstance`, `BlockBreakAnimationEvent`, `core.capabilities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-weather` applies or conditionally restores one full-strength client-only rain state. It reports source/effective precipitation, whether the biome gate is overridden, dimension support, applied/current gradients, and restoration. A non-precipitating biome uses a reversible `RAIN` render-gate override while the existing overlay and shader path remain authoritative. A live Complementary cycle selected the exact weather contract, restored rain and the override, and stopped the counter with empty rejection/fallback maps. | `ClientDebugChannel.prepareWeather`, `WeatherOverlay`, `OverlayManager.get`, `core.capabilities`, `state.sample`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | The sky-texture, sun-scatter, and first-person fire-overlay canaries override only production renderer gates and restore their exact prior values. They do not change dimension, world time, weather, player fire state, server authority, or replace geometry. Live Complementary cycles submitted the fixed-sky, sun-scatter, and generic 2D textured overlay ABIs through their exact main-view routes; each route stopped after restoration with empty rejection/fallback maps. Together with `render.substrate`'s submitted and unsubmitted compiled ABI sets, these operations close otherwise phase-dependent main-view coverage without accepting a mere program bind. | `ClientDebugChannel.prepareSkyTexture`, `ClientDebugChannel.prepareSunScatter`, `ClientDebugChannel.prepareFireOverlay`, `SkyboxRenderer`, `SunScatterRenderer`, `FireOverlay`, `core.capabilities`, `render.substrate`, focused tests, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-translucent-particle` queues one stationary, long-lived client-only sneeze particle whose authored alpha selects the production translucent particle mesh. Disable marks only that exact instance dead; the ordinary ticker removes it from the queue/list. The response reports queue/list retention, dead state, mesh presence, and position without exposing mutable collections. A live Complementary cycle selected the exact translucent-particle contract and its counter stopped after cleanup with empty rejection/fallback maps. | `ClientDebugChannel.prepareTranslucentParticle`, `ParticleRenderer.hasParticle`, `ParticleQueue.contains`, `SneezeParticle`, `core.capabilities`, `render.substrate`, focused lifecycle tests, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-chunk-border` enables the normal F3+G producer through a render-only gate, reports configured/effective state plus retained current/next mesh state, and restores the exact prior override without changing the user's profile. The ordinary async mesh, layer, and generic color shader remain authoritative. A live Complementary cycle selected the exact `BASIC/POSITION_COLOR/COLOR` world-overlay route; cleanup stopped the counter with empty rejection/fallback maps. | `ClientDebugChannel.prepareChunkBorder`, `ChunkBorderRenderer.referenceEnabledOverride`, `core.capabilities`, `render.substrate`, focused gate coverage, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `render.prepare-storage-block-entity` places one selected chest, trapped chest, ender chest, or shulker box only into loaded client air through the production world mutation path, applies the normal open/close block action, and restores exact air. A live Complementary sequence proved all four production classes, the 2→3→2 visible block-entity lifecycle, material IDs 5008/5012/5016, exact skeletal-lightmap routing, and empty rejection/fallback maps. | `StorageBlockEntityCanaryType`, `ClientDebugChannel.prepareStorageBlockEntity`, `World.set`, `core.capabilities`, `render.substrate`, focused catalog coverage, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `mods.iris.configure-options` accepts 1–64 bounded string updates, validates them against the selected pack's authored settings, and reloads through the shared shader resource event. Failed realization restores the exact option string and leaves the previous generation published. Because a real pack compile can outlive the caller's short request deadline, acceptance must re-resolve the live endpoint and confirm generation/fingerprint/resource state after `deadline_exceeded`; the completed operation is still transactional. | `IrisPresentationController.configureOptions`, `IrisDebugProvider`, `IrisShaderPackPlanner.settings`, `core.capabilities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `mods.iris.select-pack` transactionally selects only an exact normalized pack path already known to the controller or discovered beneath the configured profile `shaderpacks` directory. It does not accept arbitrary filesystem inputs or persist the debug choice. Previously selected paths remain in the bounded catalog so acceptance can return to its source pack; the response includes previous path/options for exact restoration. A failed live candidate preserved Complementary, while a successful focused block-entity-shadow cycle returned in-process to the official archive and restored its fingerprint/options/resource ledger. | `IrisPresentationController.selectDiscoveredPack`, `IrisDebugProvider`, `FabricResourceReloadEvents`, `core.capabilities`, `render.substrate`, and the [positive block-entity shadow checkpoint](../evidence/2026-07-26-iris-render-pipeline-support.md#positive-independent-block-entity-shadow-checkpoint). |
| Verified | `render.prepare-billboard-text` selects one retained non-player renderer, saves its exact custom name, name-visible tracked value, and render-only visibility override, then publishes a bounded client-only name through ordinary entity data. Disable restores all saved state. A live Complementary cycle selected the exact translucent billboard-text contract even while the camera culled all natural entities; cleanup stopped the counter with empty rejection/fallback maps. | `ClientDebugChannel.prepareBillboardText`, `EntityNameFeature`, `BillboardTextMeshBuilder`, `core.capabilities`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | `client.entities` reports routed and actually retained content independently. Each side includes format/source/geometry/selected texture plus bounded base, selected-texture, emissive, registered Gecko-layer, geometry, and known-pass vertex/pass counts; the player record includes yaw/pitch for reproducible camera diagnosis. A retained Gecko consumer additionally exposes total controller count, truncation, and at most 64 immutable controller records with clip/time, transition, trigger, raw-queue stage, completion, held, and finished state. The read is observational, never retains an out-of-frustum mesh, and fails closed when its controller owner retires. | `ClientDebugChannel.clientState`, `ContentModelInspectable`, `GeckoLibControllerSet.inspect`, `GeckoLibAnimationManager.inspection`, `BakedSkeletalModel.inspectDrawPasses`, focused tests, `naturalist-controller-state.json`, and [Naturalist registry-sync evidence](../evidence/2026-07-26-naturalist-registry-sync.md). |
| Verified | A bounded client/server block sample normalizes property ordering/case and compares through the CLI without loading chunks. | `Play.debugCompare`, both samplers, and live 125-cell equality. |
| In progress | `world.teleport-player` provides a narrow server-authoritative dimension-transition canary: it selects one exact connected player (or the only player), requires an already loaded target dimension, bounds all finite coordinates to Minecraft's world limit, preserves optional yaw/pitch defaults, executes on the server thread, and returns exact previous/current poses for restoration. It deliberately does not expose a general privileged command surface. Input validation tests pass; live Nether/End Iris generation and restoration evidence awaits the next debug-server bridge reload. | `MinosoftDebugBridgeMod.teleportPlayer`, `MinosoftDebugBridgeModTest`, `core.capabilities`, `state.sample`, `render.substrate`, and the [Iris pipeline boundary](../evidence/2026-07-26-iris-render-pipeline-support.md). |
| Verified | The immutable client player sample includes model-owned sprint state, allowing normal-path input acceptance to distinguish sprint activation from ordinary displacement. | `ClientDebugChannel`, `input.inject`, and [double-tap sprint evidence](../evidence/2026-07-23-double-tap-sprint.md). |
| Verified | Both roles expose `metrics.snapshot` from shared core instrumentation: at most 256 named operation series, fixed latency buckets, outcome counters, total/max latency, and role-owned runtime gauges. | `DebugMetrics`, both status/metrics suppliers, core tests, live capabilities, and [automation evidence](../evidence/2026-07-23-automation-observability.md). |
| Verified | The CLI consumes semantic lifecycle predicates and checked JSON scenarios through `DebugClient`, including assertions, visual baselines, matrix/repeat/soak execution, JSON/JUnit artifacts, and optional JFR around a run. | `Play`, [scenario protocol](../acceptance/scenarios.md), and live smoke/matrix/failure runs. |
| Verified | macOS discovery directories are `0700`; descriptors, credentials, and sockets are `0600`; wrong credentials and stale descriptors are rejected in tests. | Live `stat` evidence plus `DebugDiscoveryTest` and `DebugChannelServerTest`. |
| Observed | Windows named-pipe code compiles and applies an owner/SYSTEM DACL, but this pass did not execute it on a Windows host. | `DebugWindowsPipes` and cross-platform build configuration. |

Durable runtime results are in
[2026-07-22 debug-control-plane evidence](../evidence/2026-07-22-debug-control-plane.md).

## Architecture

```text
play.sh -> compiled Play -> DebugClient
                             |
                 discovery + launch credential
                             |
          +------------------+------------------+
          |                                     |
 ClientDebugChannel                    Fabric server bridge
 render/input/session/world            server tick/world/loader
          |
 Fabric generation-owned ModDebugProvider operations
```

The launcher and tests use `DebugClient`; they do not implement a second wire
protocol. Transport workers authenticate, validate, and dispatch. Operations
that touch render or server state schedule work onto the appropriate owner
thread. Responses contain DTO/JSON values or one bounded binary attachment,
never live game objects.

## Discovery, identity, and permissions

Version-one descriptor roots are:

| Platform | Default root |
| --- | --- |
| macOS | `~/Library/Application Support/Minosoft/debug/v1/` |
| Linux | `${XDG_STATE_HOME:-~/.local/state}/minosoft/debug/v1/` |
| Windows | `%LOCALAPPDATA%\Minosoft\debug\v1\` |

`MINOSOFT_DEBUG_HOME` and `MINOSOFT_DEBUG_RUNTIME` isolate tests and parallel
automation. A root contains `endpoints/<id>.json` plus an unprinted
`credentials/<id>.token`. Unix sockets use a short SHA-256-derived name beneath
the private runtime directory; Windows descriptors carry a named-pipe address.
Callers discover addresses and never construct them.

Descriptors include role, PID, process start, trajectory, generation, transport,
and protocol range. Discovery validates PID plus process-start identity, deletes
stale descriptors without signaling a process, and publishes/removes descriptor
and credential state atomically around endpoint lifetime. The random 256-bit
launch token is sent only across the local owner-restricted transport and never
appears in normal status or endpoint output.

Windows pipes use a protected DACL granting the owner and SYSTEM. Windows
descriptor-file ACL behavior still needs runtime CI evidence; compilation alone
is not permission evidence.

## Protocol and shared API

`DebugFrameCodec` uses a fixed 12-byte header with magic, protocol version, kind,
flags, and payload length. Payloads are capped at 16 MiB. JSON frames carry
HELLO, REQUEST, RESPONSE, and ERROR values; a RESPONSE may declare exactly one
following BINARY frame, used by PNG capture.

Handshake:

```text
connect
-> HELLO {endpointId, token, protocolMin, protocolMax}
<- HELLO {selected protocol, endpoint identity, role, generation}
-> REQUEST {id, operation, deadlineMs, body}
<- RESPONSE {id, result, optional attachment} | ERROR {id, error}
```

Stable error codes include `unsupported_operation`, `invalid_request`,
`unauthenticated`, `not_ready`, `limit_exceeded`, `deadline_exceeded`, and
`internal_error`. Server execution deadlines include owner-thread queue time.
The client adds a transport watchdog and closes a wedged connection after the
request deadline plus a bounded response grace period.

Primary shared types:

- `DebugPaths`, `DebugDiscovery`, `DebugEndpointDescriptor`
- `DebugFrame`, `DebugFrameCodec`, `DebugProtocolException`
- `DebugChannelServer`, `DebugOperationRegistry`, `DebugOperationHandler`
- `DebugClient`, `DebugResponse`, `DebugClientException`
- `ModDebugProvider`, `ModDebugRegistrar`

The emitted core API targets Java 25 and depends on no Minosoft GUI, OpenGL,
session, world, Netty, or Fabric implementation type. JNA is transport-only for
Windows named pipes.

## Version-one operations

| Operation | Role | Owner/thread | Limit/result |
| --- | --- | --- | --- |
| `core.ping` | both | transport-safe | endpoint identity and role |
| `core.capabilities` | both | transport-safe | operation names and owners, payload/deadline limits |
| `core.status` | both | immutable snapshot | process, trajectory, generation, readiness summary |
| `state.sample` | both | client session or server tick snapshot | named client/server view; `client.entities` is bounded to 128 nearby records |
| `visual.capture` | client | render queue | final framebuffer PNG plus dimensions/frame/time, SHA-256, suggested filename, user screenshot directory, and top-left RGBA8 semantics |
| `visual.sample` | client | render queue | ≤4096 points and ≤65536-pixel region hash/luminance |
| `visual.prepare-reference` | client | render queue | clear transient GUI overlays and explicitly enable/disable the HUD plus non-persistent hitbox, cloud, world-border, entity, and particle presentation before checked capture |
| `visual.background-throttle` | client | render queue | compare-and-set `default`/`enabled`/`disabled` non-persistent override for unfocused-window throttling; returns prior/current state and never changes the rendering profile |
| `render.substrate` | client | render queue | selected graph, terrain/shader owners, bounded frame/terrain timings, visible mesh/vertex/state totals, generation/lease/resource counts, authored material-animation publication counters, per-contract scene bind plus actual OpenGL draw/vertex ledgers, and submitted/missing compiled main-view vertex/state ABI sets |
| `render.reload-content` | client | render queue | one production content-fidelity reload; optional bounded `rejectAt` rollback checkpoint with generation and typed GPU delta evidence |
| `render.prepare-entity-flame` | client | render queue | toggle one visible retained non-player fire flag, optionally by exact entity ID, and return its previous value for restoration |
| `render.prepare-billboard-text` | client | render queue/entity tracked data | publish and exactly restore one bounded client-only entity name through the ordinary billboard-text producer |
| `render.prepare-entity-outline` | client | render queue | toggle one visible retained non-player glowing flag, optionally by exact entity ID, and return its previous value for restoration |
| `render.prepare-vanilla-armor` | client | render queue | equip or restore one retained humanoid's client-only enchanted trimmed iron set and report resolved layer state |
| `render.prepare-creeper-overlay` | client | render queue | hold or restore one retained creeper's client-only fuse-overlay and acceptance visibility state, reporting the exact resolved overlay |
| `render.prepare-translucent-held-item` | client | render queue | equip or restore one client-only vanilla main-hand stack for ordinary item-material shader routing |
| `render.prepare-lightning` | client | render queue/entity manager | add or remove one bounded client-only lightning entity through the production retained renderer path |
| `render.prepare-item-entity` | client | render queue/entity manager | add or remove one bounded client-only vanilla dropped item through the ordinary retained item renderer |
| `render.prepare-beacon` | client | render queue/world mutation | place or restore one nearby empty client block through the production beacon block-entity and chunk-remesh path |
| `render.prepare-storage-block-entity` | client | render queue/world mutation | place, open/close, and restore one bounded storage block entity through its production renderer path |
| `render.prepare-sign-text` | client | render queue/world mutation | place, populate, or restore one nearby empty client sign through production block-entity-data and terrain-remesh paths |
| `render.prepare-block-break` | client | render queue/event bus | publish or remove one bounded client-only remote-break overlay on an existing nearby block |
| `render.prepare-weather` | client | render queue/world presentation | apply or conditionally restore full client rain with a reversible precipitation-gate override |
| `render.prepare-sky-texture` | client | render queue/world presentation | force or restore the already-loaded fixed End sky texture through the production skybox renderer without changing dimension or server state |
| `render.prepare-sun-scatter` | client | render queue/world presentation | force or restore the production sun-scatter draw while retaining its real matrix, mesh, position, and intensity path |
| `render.prepare-fire-overlay` | client | render queue/world presentation | force or restore the first-person fire overlay without mutating player fire state |
| `render.prepare-reference-hand` | client | render queue/hand presentation | bind or restore a generated opaque cyan/magenta skin-sized texture only for the first-person arm draw; report selected/reference texture IDs, draw/frame counters, exact binding, and unchanged player-skin state |
| `content.execute-local` | client local world | render queue/local session runtime | one bounded mounted function plus explicit origin/camera pose and execution metadata |
| `input.inject` | client | render/input path | ≤256 key, text, mouse-move, or scroll events |
| `world.blocks.sample` | both | loaded world state | inclusive box, ≤32768 cells, palette/RLE |
| `world.aoi` | both | loaded world state | version-one alias for bounded box sampling |
| `world.teleport-player` | server | server thread/player manager | teleport one exact connected player to an already loaded dimension at finite bounded coordinates and return previous/current poses |
| `mods.debug` | both | process-local snapshot | adapted-client or Fabric-server mod lifecycle |
| `mods.<id>.summary` | client | mod generation | provider-specific adapter/hook summary |
| `mods.iris.reload-shaders` | client | render queue | one host shader reload plus bounded JSON completion |
| `mods.iris.configure-options` | client | render queue/resource reload | validate and transactionally apply 1–64 authored shader-option values |
| `mods.distanthorizons.presentation` | client | mod generation | report, enable, disable, or restore the non-persistent detached-terrain presentation override |
| `mods.distanthorizons.render-diagnostics` | client | mod generation | bounded detached tile/cell/source/native-ownership, adaptive-size, surface, skirt, and maximum-drop snapshot |
| `metrics.snapshot` | both | transport-safe plus immutable role gauges | capped operation series, fixed latency buckets, counters/totals/max, runtime gauges |

### Visual and input invariants

Framebuffer reads use Minosoft's renderer readback and return the composited
game framebuffer. The game process does not accept arbitrary output paths.
The CLI writes an explicit path when supplied; otherwise it uses a
collision-safe `.run/agent-screenshots/<endpoint trajectory>/` path and verifies
the returned SHA-256 after writing. Sampling uses top-left CLI coordinates and
returns RGBA, region SHA-256, and average luminance.

`visual.prepare-reference` makes reference capture independent of a prior pause
or debug menu. It clears poppable overlays, selects HUD visibility, and can
disable the active renderer's hitbox manager and cloud pass without persisting
a profile change; `hideHud`, `hideHitboxes`, and `hideClouds` default to true.
An explicit `hideWorldBorder` can additionally suppress that transient pass for
a checked reference and defaults to false. Explicit `hideEntities` and
`hideParticles` controls also default to false. They suppress only the
corresponding render-graph submissions: entities keep visibility, animation,
and retained meshes, while particles keep their queue and simulation state.
The operation does not mutate world or entity state. Scenario screenshot steps may
require the full framebuffer dimensions and crop one bounded top-left region
before comparison.

### Visual diagnosis protocol

A framebuffer capture alone is not evidence that the renderer is wrong. Use
this order so location, UI, fixture, and ownership failures are eliminated
before code changes:

1. Resolve the exact live client endpoint/generation and record
   `client.player`, `client.world`, and `mods.iris.presentation`.
2. Capture the unmodified frame once. Then call `visual.prepare-reference` and
   capture again; a changed pause/settings overlay is presentation state, not a
   world-render regression.
3. Validate dimension, position, yaw, and pitch against the intended reference.
   Sample a small loaded-only block volume around/below the camera to detect
   solid embedding, caves, water, unsafe spawn height, or an unintended move.
4. Inspect `render.substrate` for terrain ownership, visible mesh/material
   classes, current main/shadow submissions, selected/fallback/rejected routes,
   frame faults, shader fingerprint, and typed GPU counts.
5. Check the mounted fixture list and `visual.sample.materialAnimationStates`
   before attributing periodic changes to scheduling or shader history.
6. Only then perform reversible A/B isolation: Iris enabled/disabled or
   pack/options reload, entities/particles/clouds/world-border suppression, and
   fixed-region multi-frame sampling.
   For movement noise, use `debug visual motion-noise`: it turns away and
   returns to the exact initial pose, compares recovery frames to an
   equal-frame stationary control, and records both requested and actual
   recovery checkpoints. Treat a report with
   `measurementValidity.representativeFrameRate=false` as diagnostic only.
7. Restore every override with its returned prior state, re-resolve the endpoint
   after generation replacement, and take the checked after-capture at the exact
   pose.

If the pose or surrounding blocks differ, the capture is a location diagnosis
and must not update a renderer baseline. If GPU object counts grow, compare
steady-state counts after warmup and generation cleanup before calling the
frame's visual artifact a shader defect.

Input enters the normal Minosoft event path rather than host OS automation. A
mouse button is currently expressed as a key code such as
`MOUSE_BUTTON_LEFT`; actions are `PRESS`/`RELEASE`. The caller is responsible for
balanced actions. Connection-owned held-state cleanup, coordinate-space
conversion, and semantic GUI-element actions remain future API work. Scenario
files can sequence current one-shot operations and wait on lifecycle/state/frame
evidence, but they do not change those input ownership limits.

### State and AOI invariants

Implemented client views are `client.summary`, `client.player`, and
`client.world`. `client.entities` adds at most 128 nearby records sorted from
the player, including entity identity/type/position, renderer/visibility,
glowing/invisible state, feature/passenger counts, up to 32 command tags, and
item-display custom-model-data. For content-routed entities it also reports the
resolved route, baked format/source/geometry, and state-selected texture without
forcing visibility or retaining a mesh. If the renderer currently retains that
model, separate `content*` fields describe the actual draw instance. Both
families include bounded topology counters for base, selected-texture,
emissive, and registered Gecko-layer candidates; geometry and known-pass totals
make duplicate-pass diagnosis explicit without issuing a draw. A retained
Gecko instance also publishes its total controller count and at most 64 copied
controller summaries. Those records include current clip/time, remaining and
transition time, triggered/raw-queue stage state, completion count, held state,
and raw-finished state; no controller or owner callback escapes the renderer.
The player record includes yaw and pitch. This distinction lets
frustum/distance lifecycle remain observable instead of silently creating
acceptance-only render state.
The Fabric server supplies
`server.summary` including bounded player and world records. Client-observed
and server-authoritative samples remain visibly separate.

Version-one AOI is an inclusive axis-aligned block box in `y,z,x` order. It is
read-only and loaded-only: it never generates or requests a missing chunk.
`minecraft:air` and `minosoft:not_loaded` are distinct palette entries. Block
states are canonicalized, palette-compressed, and run-length encoded. The CLI
expands two bounded samples, normalizes state property ordering/case, aligns
coordinates, and reports at most 64 detailed differences plus the total.

Spheres, cylinders, chunk selectors, light/biome/entity layers, binary
partitions, hashes, and delta subscriptions remain target work after the stable
one-shot box contract.

### Local content-operation invariants

`content.execute-local` is advertised by the client debug channel, but succeeds
only when the selected session has an active source-native `LocalConnection`;
it rejects network sessions. The request contains one resource location of at
most 256 characters, at most 16 bounded string macro arguments totaling at
most 8 KiB, and finite origin/camera coordinates inside the ±30,000,000 world
bound. Pitch is limited to `-90..90`.
Execution goes through the mounted `SessionDataPackRuntime`; it is not an
evaluation endpoint or a second command interpreter. The response reports the
executed command count, content generation, data-pack tick, frame, and applied
poses.

The operation may mutate only that local session according to the named
checked-in/mounted function. It does not add a server mutation surface or
arbitrary command string. The explicit player camera is part of the same
render-queue operation so visual fixtures can reproduce their view without OS
automation.

## Mod provider ownership

`ClientDebugChannel.register(ModDebugProvider)` validates the mod/provider ID,
names operations `mods.<id>.<local-name>`, records the provider version in the
operation owner, and returns one reverse-order cleanup handle. Partial
registration failure closes every operation already added. Closing a Fabric
pack registration scope removes its provider operations before the old
generation exits.

The current provider surface is operation-only. First-party adapted Fabric mods
register `summary`; Iris also registers `reload-shaders`, which selects exactly
one active render session and transactionally replaces the planned
shader-program/target generation through the same resource-reload lifecycle as
the CLI command. Sodium's summary identifies its selected terrain adapter; the
core `render.substrate` snapshot reports the graph and both selected providers
without granting either mod an unowned diagnostic endpoint. `mods.debug`
supplies baseline lifecycle and attributed hook counters/timing even if a mod
has no provider. Core operation metrics use fixed bounded series/buckets. Future
mod-owned metrics and AOI layers should build on the same cleanup discipline
rather than introducing a second registry.

## Implemented CLI

Selectors may appear with each command:

```text
--role client|server
--trajectory NAME
--endpoint ID
--json
```

Commands:

```sh
./play.sh debug endpoints --json
./play.sh debug status --role client --trajectory NAME --json
./play.sh debug capabilities --role server --trajectory NAME --json
./play.sh debug state client.player --role client --json
./play.sh debug mods --role client --json
./play.sh debug request render.substrate '{}' --role client --json
./play.sh debug request render.reload-content '{}' --role client --json
./play.sh debug request content.execute-local \
  '{"function":"example:fixture/summon","arguments":{},"origin":{"x":0.5,"y":20.0,"z":0.5,"yaw":0.0,"pitch":0.0},"camera":{"x":0.5,"y":19.5,"z":3.5,"yaw":180.0,"pitch":0.0}}' \
  --role client --json
./play.sh debug request mods.sodium.summary '{}' --role client --json
./play.sh debug request mods.iris.reload-shaders '{}' --role client --json

./play.sh debug visual capture --role client --trajectory NAME --json
./play.sh debug visual capture /tmp/frame.png --role client --json
./play.sh debug visual sample --point 640,360 \
  --region 0,0,320,180 --role client --json
./play.sh debug visual motion-noise \
  --yaw-delta 5 --samples 2 --recovery-frames 0,4,16,32 \
  --settle-frames 32 --away-frames 4 --region 0,0,1920,700 \
  --trajectory NAME --json

./play.sh debug input key KEY_ESCAPE PRESS --role client --json
./play.sh debug input key KEY_ESCAPE RELEASE --role client --json
./play.sh debug input mouse 640 360 --role client --json
./play.sh debug input text hello --role client --json
./play.sh debug input scroll 0 -1 --role client --json

./play.sh debug blocks 0 60 0 15 79 15 --role server --json
./play.sh debug aoi 0 60 0 15 79 15 --role client --json
./play.sh debug compare blocks 0 60 0 15 79 15 --trajectory NAME --json
```

Selection chooses the highest generation for a role/trajectory and rejects
ambiguous peers at the same generation. Tokens are never printed. Binary bytes
are written by `visual capture` and the bounded `motion-noise` artifact writer.
The latter requires both client and server endpoints, teleports only yaw at the
sampled finite pose, restores that pose conditionally, and places its report
under `.run/motion-noise/<trajectory>/` unless `--output` is supplied. Expected
remote failures have concise human output or a stable JSON error object rather
than a Java stack trace.

## Fabric server decision

The development server lane is pinned to Minecraft 1.20.4, Fabric Loader
0.15.11, Fabric API 0.97.3, and the repository-owned
`minosoft_debug_bridge`. `Play` builds/stages the bridge and Fabric API into the
server's mutable runtime directory, then launches the official Fabric server
launcher. The bridge starts after `SERVER_STARTED`, samples server-owned state
through `MinecraftServer.execute`, and closes before server shutdown.

Paper/Bukkit is a different plugin ecosystem and may be added as a separate
server lane. NeoForge is a future separate lane for mods such as Mekanism.
Hybrid Fabric/Bukkit servers are not part of this architecture because they
weaken loader/API ownership and compatibility evidence.

## Lifecycle and hot reload

- Endpoint publication follows operation/authentication setup; close removes the
  socket/pipe, descriptor, and credential.
- A client replacement increments generation and publishes a new endpoint ID;
  the old endpoint disappears rather than redirecting an in-flight request.
- The development parent watches `src/main`, `debug-core/src/main`, build files,
  the selected manifest, canary source when enabled, and configured external
  roots.
- Candidate compile/preflight happens while the current client remains active.
  A successful base/shared-core candidate replaces only the client; the parent
  and server stay stable.
- Fabric pack scope cleanup removes old provider operations and replacement
  activation re-registers them with the new generation.

## Acceptance status

| Gate | Status |
| --- | --- |
| Discovery/authentication/stale cleanup/private Unix permissions | Verified in unit and live macOS checks |
| Frame validation, errors, deadlines, binary attachment, operation cleanup | Verified in `debug-core` tests |
| Client final-frame capture and point/region sampling without AppleScript | Verified live |
| Same-pose camera-motion residual/control measurement and throttle/pose restoration | Verified live at 52.7+ FPS median-frame-valid cadence |
| Normal-path mouse/key input causing death-screen Respawn | Verified live |
| Client/server state on one protocol | Verified live |
| Fabric server headless endpoint startup and clean shutdown discovery removal | Verified live |
| Loaded-only client/server bounded block equality | Verified live after canonicalization fix |
| Generation-owned Sodium/stack provider replacement | Verified across generations 1→3 |
| Canonical graph/provider/resource/timing snapshot | Verified live for base, Sodium, Iris-reference, and combined profiles |
| Exact Animated Java local function, entity inspection, capture, reload, removal, and GPU loop | Verified live for default pose across four content generations |
| Base and shared-debug source hot reload with stable parent/server | Verified live |
| Scenario lifecycle/metrics JSON+JUnit, matrix, screenshot failure, and JFR disposition | Verified live |
| Full repository unit and integration suites | Historical Java 17 gate: 1,787 main unit tests, 2,077 integration tests, and 9 `debug-core` tests, zero failures/errors. The current Java 25 gate is recorded in the [Java 25 baseline](../evidence/2026-07-30-java-25-baseline.md). |
| Linux and Windows runtime transport/ACL behavior | Not run in this macOS pass; Windows compiles |
| Streaming/backpressure and advanced AOI/provider layers | Deferred; not advertised in version one |

## Next trajectory

1. Add Linux and Windows runtime transport/ACL CI before calling portability
   fully verified.
2. Add connection-owned pressed input state and resolved coordinate spaces.
   Semantic sequence files and lifecycle/frame wait predicates now exist at the
   CLI scenario layer; continue with GUI-element predicates where state/frame
   evidence cannot express the target.
3. Version state DTO schemas. Frame and terrain preparation/submission now expose
   bounded median/p95 windows; add tick histograms and driver-side GPU object
   counts without making the snapshot unbounded.
4. Add partitioned AOI layers and content hashes, then subscriptions with bounded
   queue depth and explicit `resync_required` behavior.
5. Extend `ModDebugRegistrar` with owned metrics and AOI-layer registrations and
   prove canary cleanup under reload.
6. Implement the [agent trajectory tooling backlog](../backlog/agent-trajectory-tooling.md),
   beginning with mutation leases, atomic visual diagnosis bundles, and a safe
   saved-world snapshot boundary.

## Stable contracts

- One protocol implementation and shared client serve the CLI and tests.
- Discovery is per-user, versioned, atomic, trajectory-aware, and token-safe.
- Operations are explicit, namespaced, deadline-bounded, and owner-thread aware.
- No operation exposes arbitrary evaluation/reflection or silently falls back to
  AppleScript/OS input.
- Sampling is read-only and loaded-only by default.
- Client-observed and server-authoritative state remain distinct.
- Mod operations cannot outlive their registration/generation.
- Unsupported future capability is absent from `core.capabilities`.

## References

- [Base/kernel evidence map](01-base.md)
- [Client evidence map](03-client.md)
- [Server boundary](04-server.md)
- [Graphics evidence map](05-graphics.md)
- [Development workflow](07-development-workflow.md)
- [Mod workflow](08-modding.md)
- [Hot-reload acceptance protocol](../acceptance/hot-reload.md)
- [Scenario and observability protocol](../acceptance/scenarios.md)
- [Agent trajectory tooling backlog](../backlog/agent-trajectory-tooling.md)
- [Debug control-plane acceptance evidence](../evidence/2026-07-22-debug-control-plane.md)
- [Automation and observability evidence](../evidence/2026-07-23-automation-observability.md)
