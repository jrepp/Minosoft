<!-- Copyright (C) 2026 Jacob Repp -->

# Graphics evidence map

## Boundary

Graphics owns window/context integration, OpenGL resources, renderer lifecycle,
cameras, chunk/entity meshes, shaders/textures, lightmaps, sky/fog, particles,
in-game GUI/HUD, and audio presentation.

## Evidence map

| Status | Claim | Evidence |
| --- | --- | --- |
| Observed | A `Rendering` instance is composed for a `PlaySession`. | `gui/rendering/Rendering.kt` and the current session establishment path. |
| Observed | OpenGL 3.3+ is the documented baseline behind a rendering-system abstraction. | `gui/rendering/system/` and [rendering overview](../../rendering/ReadMe.md). |
| Observed | Renderer and GPU resources expose explicit unload lifecycles. | Renderer, mesh, buffer, framebuffer, texture, light, and sound implementations. |
| Observed | Shader objects already expose a reload operation. | `gui/rendering/shader/Shader.kt` and `system/opengl/shader/OpenGlNativeShader.kt`. |
| Verified | Non-GPU logic can use dummy rendering-system implementations. | `src/integration-test/kotlin/de/bixilon/minosoft/gui/rendering/system/dummy/`. |
| Observed | Model code still holds some renderer state. | `data/entities/entities/Entity.kt` and related entity/render coupling. |
| Verified | The pinned Sodium compatibility adapter registers a Minosoft-native renderer hook, binds it to chunk scheduling, executes it in the frame preparation path, and unloads it with the renderer. | `SodiumRendererHook`, `RenderLoader.registerRenderer`, and [Sodium activation evidence](../evidence/2026-07-21-sodium-activation.md). |
| Verified | Fabric compatibility adapters can independently intercept the native entity-visibility decision and frame queue-flush boundary through owner-keyed hooks; registrations disappear when the pack scope closes. | `FabricEntityVisibilityHooks`, `FabricFrameHooks`, `EntitiesRenderer`, `RenderLoop`, focused tests, and [Fabric stack evidence](../evidence/2026-07-21-fabric-stack.md). |
| Verified | The exact Iris adapter installs a visible owner-scoped shader at the world framebuffer presentation boundary. Live control disabled/restored the concrete program, host shader reload preserved it, and generation cleanup removes its selection without disturbing another owner. Shader-pack compilation, shadow passes, and upstream binary renderer integration remain explicitly unmapped. | `IrisCompatibilityAdapter`, `WorldFramebuffer.postProcessors`, `WorldPostProcessorsTest`, and [Iris/JEI behavioral acceptance](../evidence/2026-07-23-iris-jei-acceptance.md). |
| Verified | GUI mesh indices are counted per complete four-vertex quad. A paged JEI recipe screen exposed and now guards the former vertex-count/index-count mismatch; 256 vertices produce 64 quads, and incomplete quads are rejected. | `GuiMeshBuilder.quadCount`, `GuiMeshBuilderTest`, and [Iris/JEI activation evidence](../evidence/2026-07-23-iris-jei-activation.md). |
| Verified | Adapted mod JAR assets can be mounted into the normal per-session priority asset stack, and property-bearing Tech Reborn blockstates reach model loading. | `ExternalAssetProviders`, `AssetsLoader`, `FabricSessionContentBridge`, and [Tech Reborn world-generation evidence](../evidence/2026-07-21-tech-reborn-worldgen.md). |
| Verified | Verified external resource-pack ZIPs participate in the same session priority stack; a live debug capture showed Faithful 64x replacements after a supervised client reload. | `resourcepacks/*.pw.toml`, `AssetsLoader`, and [resource-pack evidence](../evidence/2026-07-22-resource-pack-stack.md). |
| Verified | Moon phase affects night-sky appearance but cannot zero the terrain lightmap; a new moon retains a bounded sky-visibility floor. | `NightLighting`, `NormalLightmapUpdater`, `SkyboxColor`, and `NightLightingTest`. |
| Verified | The pause menu exposes a Minosoft-native Lighting screen for persisted gamma, fullbright, and ambient-occlusion changes; gamma changes explicitly invalidate the live lightmap. Brightness uses the shared discrete slider rather than separate step buttons. | `LightingMenu`, `LightingControls`, `Lightmap.init`, focused control tests, and [stepped-slider evidence](../evidence/2026-07-23-stepped-slider.md). |
| Verified | A presentation-only player light prevents fully black nearby surfaces without mutating world light: it peaks at a configurable 15% by default, falls quadratically to zero over six blocks, and is evaluated per fragment for terrain, breaking overlays, and lightmapped entities. The Lighting menu slider adjusts it from Off through 30% in five-percent steps. | `PlayerLightShader`, `PlayerLightFalloff`, `player_light.glsl`, focused tests, [player-light evidence](../evidence/2026-07-23-player-light.md), and [stepped-slider evidence](../evidence/2026-07-23-stepped-slider.md). |
| Verified | Pause → Audio exposes the existing OpenAL engine through profile-backed live enable/volume controls, actual initialization status, normal-path test output, sound-family policies, stop-all, and a restart-qualified startup policy. Master volume uses the shared discrete slider and reaches listener `AL_GAIN`; layered sound indices preserve vanilla events while honoring pack append/replace semantics. Both final pages fit the live 1800×1000 viewport. | `AudioMenu`, `AudioAdvancedMenu`, `AudioControls`, `SoundIndexMerger`, focused tests, [audio-menu evidence](../evidence/2026-07-22-audio-menu.md), and [stepped-slider evidence](../evidence/2026-07-23-stepped-slider.md). |
| Verified | Pause → Mod settings exposes the active pack's shared compatibility catalog and runtime diagnostics, including global FPS context and attributed invocation timing at owned graphics hooks. | `FabricModSettingsMenu`, `FabricModDiagnosticsMenu`, `FabricModDiagnostics`, and [2026-07-22 catalog/diagnostics evidence](../evidence/2026-07-22-fabric-catalog-diagnostics.md). |
| Verified | Manual shader and texture reloads execute on the render queue and publish owned Fabric prepare/apply/complete/failed lifecycle events. This is an observable lifecycle boundary, not yet an atomic GPU-resource swap. | `ReloadCommand`, `FabricResourceReloadEvents`, focused transaction tests, and [resource-reload evidence](../evidence/2026-07-22-fabric-resource-reload-events.md). |
| Verified | Entity-feature mesh disposal is state-aware and idempotent: immediate shutdown does not enqueue a duplicate GPU unload, and queued replacement cleanup rechecks mesh state on the render thread. | `MeshedFeature`, focused entity-rendering tests, live post-reload log sampling, and [entity mesh unload evidence](../evidence/2026-07-22-entity-mesh-unload.md). |
| Verified | OpenGL texture shaders initialize every active sampler-array slot to a valid 2D-array unit; dynamic arrays grow for high-resolution pack skins, and font arrays allocate independent width/height maxima. Faithful 64x no longer produces Apple's unloadable-array warning or `GL_INVALID_VALUE`. | `OpenGlTextureManager`, `OpenGlDynamicTextureArray`, `OpenGlFontTextureArray`, `OpenGlTextureSizing`, focused tests, and [resource-pack OpenGL evidence](../evidence/2026-07-22-resource-pack-opengl-audio.md). |
| Verified | Positional audio refreshes the listener from the current camera before attenuation and reports requested, resolved, rejected, buffer-ready, and source-start stages. A harvested stone started at its block-center world coordinate. | `AudioPlayer`, client debug state, and [positional-audio evidence](../evidence/2026-07-22-resource-pack-opengl-audio.md). |
| Verified | Mining progress plays the block sound group's `hit` event at block center with a frame-rate-independent 200 ms cadence; remote break-animation updates use the same position path, while completion retains the distinct `destroy` sound. | `BlockHitAudio`, `BlockHitCadence`, `SurvivalDigger`, `BlockBreakAnimationS2CP`, focused tests, and [harvesting evidence](../evidence/2026-07-22-resource-pack-opengl-audio.md). |
| Verified | The first-person arm consumes player-owned 200 ms swing progress and applies a bounded hand transform during harvesting; framebuffer comparison shows the hand displaced inward/downward while the target remains in its partial-break state. | `ArmSwingState`, `ArmRenderer`, focused tests, and [harvesting evidence](../evidence/2026-07-22-resource-pack-opengl-audio.md). |
| Verified | Chest and shulker-box item families use their resolved entity textures for GUI/world-item rendering. All 61 vanilla 1.20.4 `builtin/entity` block items are classified: 20 have precise box-family renderers and 41 receive a visible particle-texture fallback with structured audit output. The crafted shield is audited separately and receives the same safe fallback. Entity-backed block transitions invalidate section meshes even without a normal baked model, so a live chest harvest/pickup/place cycle remained visible without restarting the client. | `ChestItemRender`, `ShulkerBoxItemRender`, `ItemLoader`, `ItemRenderUtil`, `ChunkRendererChangeListener`, focused tests, and [chest/generalized block-item evidence](../evidence/2026-07-23-chest-rendering.md). |
| Verified | A block item's dedicated generated/layered item model replaces its world-block fallback, while ordinary inherited block models remain 3D. Automated coverage distinguishes ladder from oak planks; live debug-pipe acceptance verified the Faithful ladder result centered in its crafting slot. | `ItemLoader`, `RenderTestLoader`, and [generated block-item evidence](../evidence/2026-07-23-generated-block-item-rendering.md). |
| Verified | Creative player inventories render a 9×7 registry-backed catalog beside the normal container. Live capture and input acceptance covered page 1→2 scrolling and creative stack pickup while preserving the inventory and adapted-mod controls. | `CreativeItemCatalogElement`, `InventoryScreen`, `CreativeCatalogPagerTest`, and [creative catalog evidence](../evidence/2026-07-23-creative-catalog-gamemode-aliases.md). |
| Verified | Pause → Debug options → Debug rendering exposes the active world framebuffer's fill/line mode and the existing F3 debug HUD state with live labels and a reset. The legacy polygon key binding toggles current framebuffer state, preserving one source of truth across keyboard and menu control. | `DebugRenderingMenu`, `DebugRenderingControls`, `DebugKeyBindings`, focused tests, and [debug-rendering menu evidence](../evidence/2026-07-23-debug-rendering-menu.md). |
| Verified | Zombies use a dedicated six-part skeletal model whose left limbs mirror the populated classic-zombie UV regions. Zombie variants remain visible, and otherwise unmapped living entities fail visibly through a debug-textured humanoid renderer instead of a model-less dummy. The generic fallback is diagnostic geometry, not species-accurate modeling. | `ZombieRenderer`, `HumanoidMobRenderer`, `FallbackLivingEntityRenderer`, focused resource/integration tests, and [mob/title evidence](../evidence/2026-07-23-mob-rendering-title-menu.md). |
| Verified | The credits screen builds its scroll from original Minosoft content plus same-key local pack/mod contributions; it advances at a stable 20 Hz cadence and rebuilds only lines intersecting the viewport. | `CreditsContent`, `CreditsScreen`, focused tests, and [credits parity evidence](../evidence/2026-07-23-credits-parity.md). |
| Observed | Full Tech Reborn rendering is incomplete: Fabric/custom builtin models and at least one machine texture still report diagnostics. | Live diagnostics recorded in [Tech Reborn world-generation evidence](../evidence/2026-07-21-tech-reborn-worldgen.md). |
| Verified | Vanilla JSON and PNG support has an explicit fidelity matrix. Declared `cullface` is distinct from boundary occlusion geometry and rotates with blockstate applies; inherited model AO survives baking per face; animation sheets honor rectangular frame dimensions, row-major grids, and object-entry `frametime` fallback. Last-match custom-model-data and damage item overrides are native; sampler metadata and the complete predicate catalog remain incomplete. | `ModelFace`, `SingleBlockStateApply`, `BakedFace`, `BakedModel`, `AnimationProperties`, `SpriteAnimator`, `ItemPredicate`, focused tests, [content-system documentation](../../Assets.md), and [foundation evidence](../evidence/2026-07-24-content-fidelity-foundation.md). |
| Verified | The exact 1.20.4 ETF, EMF, and GeckoLib artifacts select source-native adapters without executing upstream bytecode. CEM and Gecko geometry parse into a neutral model; version/entity aliases and per-instance CEM expressions reach retained transforms, targeted CEM parts compose with native geometry and isolated attach roots, matching Gecko clips update retained bones, and a separate source-native Gecko facade covers predicate/concurrent-layer control and owned caches. Expanded ETF context and material discovery reach skeletal base/emissive passes with state restoration. `/reload content` now reparses, bakes, uploads, and atomically swaps CEM/Gecko models plus ETF material/cache generations on the render thread; retained instances defer old GPU/cache cleanup, and a new/missing texture rejects the candidate. A headless fixture crosses 1.19.4 and 1.20.4. Animated Java foundations retain arbitrary cuboid item geometry, render item/block/text displays, and execute a bounded selector/entity lifecycle against session-owned display/interaction passenger trees. Failed candidate loads restore command and entity state. Complete EMF catalogs/complex attachment parity, ETF feature/player coverage, Gecko binary APIs/events/render layers/routing, remaining Animated Java exporter semantics, texture-array reload, repeated real-GL accounting, and visual acceptance remain partial. | `assets/model/`, `assets/datapack/`, `local/datapack/`, `SkeletalModelBinder`, `SkeletalModelComposer`, `CemExpressionManager`, `GeckoLibControllerSet`, `DisplayEntityRenderer`, `TextDisplayFeature`, `SkeletalLoader.reloadContentFidelity`, `ContentGenerationStore.reloadLeased`, `ContentFidelityMultiVersionTest`, `modpacks/content-fidelity/`, focused tests, [native adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md), and [content-system documentation](../../Assets.md). |

## Stable contracts

- Rendering consumes session/model state; it does not own authoritative world
  state or launcher flows.
- Respect graphics context/thread ownership for create, upload, draw, reload, and
  unload operations.
- Render phases, framebuffer, depth, blend, and culling state are shared
  contracts. Establish or restore state explicitly.
- Every active OpenGL sampler uniform must reference a texture unit with the
  matching target, even when application data never selects that array slot.
- Resource-pack dimensions are input data. Validate them against the driver
  limit and size array storage before issuing an upload.
- Simulation light values and GPU lightmaps are separate layers with an explicit
  invalidation/upload boundary.
- A missing baked model does not imply a visually inert block transition.
  Entity-backed placement, removal, and state changes must invalidate the
  section mesh that owns their alternate renderer.
- GUI index buffers must contain one six-index group per complete four-vertex
  quad. Never size or populate the index buffer from raw vertex count.
- Visual changes need more than a single screenshot: check relevant dimensions,
  time/weather, fog boundaries, and graphics profiles.

## Trajectory

**Target:** complete assets and shaders as the first safe live-reload vertical
slice. The lifecycle and render-thread apply boundary now exist. Next, watch
changed resources, validate/compile a replacement off the active path, then
swap/unload on the render thread. A failure must keep the last-known-good
resource active and report the source path and compiler log. The current
`OpenGlNativeShader.reload()` unloads before loading, so it does not yet satisfy
that failure-preservation contract.

Graphics extensions from mods must register through an owned scope so a mod
generation can enqueue render-thread cleanup before its classloader is released.
A hook's execution thread is the host call site's thread: the frame boundary is
the render thread, while entity visibility may execute in asynchronous renderer
preparation. Adapters must not assume those are the same thread.
A whole-client generation reload must either dispose and recreate the graphics
context or keep a kernel-owned window/context shell behind a narrow stable API;
choose only after measuring context recreation cost and native ownership.

## Next evidence

1. Map rendering construction, context thread, render queue, and shutdown order.
2. Map shader source resolution/includes and existing `reload()` call sites.
3. Trace world light mutation to mesh/lightmap invalidation and GPU upload.
4. Catalogue mod-safe renderer/HUD registration seams and cleanup requirements.
5. Spike full context recreation versus a stable native shell; record which
   OpenGL/GLFW objects may cross neither option's generation boundary.
6. Classify Fabric custom/builtin model loaders and add an ore-only visual gate
   before claiming the complete machine/cable render surface.
7. Implement the `content-fidelity` ladder from format parser to neutral
   skeletal model, renderer binding, material passes, and transactional reload;
   do not skip directly from staged JARs to an adapted claim.

## Validation

- Matching unit/integration packages for CPU-side logic
- Model, texture, tint, and packet/chunk fixtures under integration resources
- `./gradlew compileKotlin` plus a visual smoke test for GPU lifecycle/state work
- Reload acceptance: valid change becomes visible; invalid change preserves the
  prior resource; repeated reload does not grow GPU/resource counts

## References

- [Rendering overview](../../rendering/ReadMe.md)
- [Content and asset system](../../Assets.md)
- [Meshes](../../rendering/Meshes.md)
- [Entities](../../rendering/Entities.md)
- [In-game GUI](../../rendering/GUI.md)
- [Shaders](../../Shader.md)
