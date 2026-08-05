/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

enum class FabricFunctionalityStatus(val wireName: String) {
    MAPPED("mapped"),
    PARTIAL("partial"),
    UNMAPPED("unmapped"),
}

enum class FabricFunctionalityArea(val wireName: String) {
    API("api"),
    CONTENT("content"),
    DIAGNOSTICS("diagnostics"),
    GAMEPLAY("gameplay"),
    NETWORKING("networking"),
    OPTIONS("options"),
    PERFORMANCE("performance"),
    RENDERING("rendering"),
    RELOAD("reload"),
    SERVER("server"),
    WORLD("world"),
}

enum class FabricHostMenu(val wireName: String) {
    LIGHTING("lighting"),
}

data class FabricFunctionality(
    val id: String,
    val title: String,
    val area: FabricFunctionalityArea,
    val status: FabricFunctionalityStatus,
    val detail: String,
    val hostMapping: String? = null,
    val menu: FabricHostMenu? = null,
    val restartRequired: Boolean = false,
)

data class FabricFunctionalitySummary(
    val mapped: Int,
    val partial: Int,
    val unmapped: Int,
) {
    val total: Int get() = mapped + partial + unmapped

    companion object {
        fun of(functionality: Collection<FabricFunctionality>) = FabricFunctionalitySummary(
            mapped = functionality.count { it.status == FabricFunctionalityStatus.MAPPED },
            partial = functionality.count { it.status == FabricFunctionalityStatus.PARTIAL },
            unmapped = functionality.count { it.status == FabricFunctionalityStatus.UNMAPPED },
        )
    }
}

object FabricFunctionalityCatalog {
    private fun mapped(id: String, title: String, area: FabricFunctionalityArea, detail: String, host: String, menu: FabricHostMenu? = null, restart: Boolean = false) =
        FabricFunctionality(id, title, area, FabricFunctionalityStatus.MAPPED, detail, host, menu, restart)

    private fun partial(id: String, title: String, area: FabricFunctionalityArea, detail: String, host: String? = null, menu: FabricHostMenu? = null, restart: Boolean = false) =
        FabricFunctionality(id, title, area, FabricFunctionalityStatus.PARTIAL, detail, host, menu, restart)

    private fun unmapped(id: String, title: String, area: FabricFunctionalityArea, detail: String) =
        FabricFunctionality(id, title, area, FabricFunctionalityStatus.UNMAPPED, detail)

    val SODIUM = listOf(
        partial("chunk-render-scheduling", "Chunk render scheduling", FabricFunctionalityArea.PERFORMANCE, "The exact Sodium adapter selects the sole neutral near-terrain provider, while the renderer-owned provider delegates scheduling behavior to Minosoft's chunk core.", "TerrainBackendRegistry / TerrainProviderSelection / ChunkRendererNearTerrainBackend"),
        partial("terrain-backend", "Terrain backend ownership", FabricFunctionalityArea.RENDERING, "The selected neutral provider is the sole graph-visible terrain owner while Sodium is active and controls preparation, transfer, visibility, and submission calls. Mesh generation and the packed vertex ABI still delegate to Minosoft core code rather than upstream Sodium algorithms.", "TerrainBackendRegistry / TerrainProviderSelection / ChunkRendererNearTerrainBackend"),
        mapped("brightness", "Brightness", FabricFunctionalityArea.OPTIONS, "Sodium brightness maps to the live Minosoft gamma profile.", "rendering.light.gamma", FabricHostMenu.LIGHTING),
        mapped("smooth-lighting", "Smooth lighting", FabricFunctionalityArea.OPTIONS, "Sodium smooth lighting maps to four-corner block/sky light, continuous AO, inset interpolation, brightness-oriented diagonals, and the shared water path.", "rendering.light.ambientOcclusion / SmoothTerrainLighting", FabricHostMenu.LIGHTING),
        mapped("video-settings-screen", "Video settings screen", FabricFunctionalityArea.OPTIONS, "An owned categorized, searchable source-native form exposes staged Sodium-equivalent video and quality controls.", "SodiumCompatibilityAdapter / FabricSettings"),
        mapped("view-distance", "View distance", FabricFunctionalityArea.OPTIONS, "A stepped native control writes the live block view-distance profile.", "block.viewDistance"),
        mapped("gui-scale", "GUI scale", FabricFunctionalityArea.OPTIONS, "A stepped native control writes the live GUI framebuffer scale.", "rendering.quality.resolution.guiScale"),
        mapped("fullscreen", "Fullscreen", FabricFunctionalityArea.OPTIONS, "The native settings form stages and applies the active window fullscreen state.", "window.fullscreen"),
        mapped("v-sync", "VSync", FabricFunctionalityArea.OPTIONS, "The native VSync switch maps to swap interval 1/0.", "rendering.advanced.swapInterval"),
        mapped("cloud-quality", "Cloud quality", FabricFunctionalityArea.OPTIONS, "Off, fast, and fancy cycle values map to native cloud enablement and flat/layered rendering.", "rendering.sky.clouds"),
        mapped("biome-blend", "Biome blend", FabricFunctionalityArea.OPTIONS, "Radius and dependency-driven algorithm controls feed cached per-block samples and per-vertex bilinear grass, foliage, and water tinting; every setting change invalidates terrain.", "rendering.biome.blending / TerrainTintCache"),
        mapped("mipmap-levels", "Mipmap levels", FabricFunctionalityArea.OPTIONS, "The stepped profile control carries an explicit client-generation restart marker.", "rendering.textures.mipmaps", restart = true),
        partial("defer-chunk-updates", "Deferred chunk updates", FabricFunctionalityArea.PERFORMANCE, "Minosoft bounds chunk-transfer time, but does not match Sodium's always-defer behavior.", "rendering.performance.limitChunkTransferTime"),
        unmapped("simulation-distance", "Simulation distance", FabricFunctionalityArea.OPTIONS, "No Sodium-compatible local simulation-distance control."),
        unmapped("fps-limit", "FPS limit", FabricFunctionalityArea.OPTIONS, "No persisted frame-rate limiter profile."),
        unmapped("graphics-quality", "Graphics quality preset", FabricFunctionalityArea.OPTIONS, "No umbrella fast/fancy/default compatibility setting."),
        unmapped("weather-quality", "Weather quality", FabricFunctionalityArea.OPTIONS, "Weather visibility toggles exist, but Sodium's render-distance quality control does not."),
        unmapped("leaves-quality", "Leaves quality", FabricFunctionalityArea.RENDERING, "No fast/fancy leaf render-layer mapping."),
        unmapped("particle-quality", "Particle quality", FabricFunctionalityArea.RENDERING, "No Sodium-compatible particle-count quality mapping."),
        unmapped("entity-distance", "Entity distance multiplier", FabricFunctionalityArea.RENDERING, "Minosoft uses an absolute entity distance, not Sodium's multiplier semantics."),
        unmapped("entity-shadows", "Entity shadows", FabricFunctionalityArea.RENDERING, "Entity shadow rendering is not mapped."),
        unmapped("vignette", "Vignette", FabricFunctionalityArea.RENDERING, "Sodium's vignette option is not mapped."),
        unmapped("block-face-culling", "Block face culling", FabricFunctionalityArea.PERFORMANCE, "No runtime option matching Sodium's face-culling toggle."),
        unmapped("fog-occlusion", "Fog occlusion", FabricFunctionalityArea.PERFORMANCE, "No chunk rejection path driven by fog coverage."),
        unmapped("animate-visible-textures", "Visible texture animation", FabricFunctionalityArea.PERFORMANCE, "Animated textures are not filtered by frame visibility."),
        unmapped("cpu-render-ahead", "CPU render-ahead", FabricFunctionalityArea.PERFORMANCE, "No configurable in-flight frame limit."),
        unmapped("persistent-mapping", "Persistent buffer mapping", FabricFunctionalityArea.PERFORMANCE, "No Sodium-compatible persistent staging-buffer path."),
        unmapped("chunk-update-threads", "Chunk update threads", FabricFunctionalityArea.PERFORMANCE, "No isolated Sodium chunk-builder thread-count control."),
        unmapped("no-error-context", "No-error OpenGL context", FabricFunctionalityArea.PERFORMANCE, "OpenGL context error checking cannot be changed through the adapter."),
        unmapped("driver-diagnostics", "Driver diagnostics", FabricFunctionalityArea.DIAGNOSTICS, "Sodium driver checks, warnings, and config-recovery screens are not mapped."),
    )

    val DISTANT_HORIZONS = listOf(
        mapped("chunk-events", "Chunk lifecycle ingestion", FabricFunctionalityArea.WORLD, "Native chunk create and update events capture detached normalized vertical-run pages with block/fluid semantics, light and biome input while retaining a top-only rollback projection.", "DistantHorizonsLodController / DistantWorldVerticalSampler / FabricChunkEvents"),
        mapped("block-mutation-events", "Block mutation ingestion", FabricFunctionalityArea.WORLD, "Single and batched native block mutations resample only affected immutable vertical columns after the world mutation boundary.", "DistantHorizonsLodController / FabricBlockMutationEvents"),
        mapped("world-events", "World lifecycle cleanup", FabricFunctionalityArea.WORLD, "Leaving a world clears the session-owned detached tile store so LOD state cannot leak across sessions.", "DistantHorizonsLodController / FabricWorldEvents"),
        mapped("client-payload-channels", "Managed-server LOD payloads", FabricFunctionalityArea.NETWORKING, "One bounded source-native channel negotiates world-tagged schema-v2 page requests/responses with exact correlation and cancellation while retaining a readable v1 rollback codec; upstream DH payload types do not link.", "DistantTerrainProtocolV2 / DistantTerrainRequestTracker / FabricClientPayloadChannels"),
        mapped("screens", "Distant terrain settings screen", FabricFunctionalityArea.OPTIONS, "The adapter registers its persisted schema through the shared native Mod settings screen.", "DistantHorizonsOptions / FabricSettings"),
        partial("world-generation", "Distant terrain generation capability", FabricFunctionalityArea.WORLD, "Local authority and the managed Fabric bridge produce detached unexplored tiles under bounded budgets; upstream DH generator overrides do not execute.", "DistantUnexploredGenerator / DistantHorizonsLodServer"),
        mapped("distant-terrain-lod", "Explored distant terrain", FabricFunctionalityArea.RENDERING, "The exact 2.4.4-b adapter supplies normalized vertical pages to the default provider-neutral hierarchy, which publishes graph-visible solid, water, and shadow routes outside the native near-terrain seam.", "DistantTerrainRenderer / DistantHierarchicalTerrainRuntime / DistantPageMesher"),
        mapped("lod-storage", "LOD storage", FabricFunctionalityArea.WORLD, "A bounded access-ordered memory store retains detached tiles after native unload. Schema-v2 page records are checksum protected, atomically replaced per dirty key, crash recoverable, world tagged, distance/recency evicted with dirty-page pinning, and inspectable; the checked v1 GZIP decoder is read-only migration input and no longer has a production writer.", "DistantDirectoryTerrainStore / DistantTerrainStoreWriter / DistantLodPersistence"),
        mapped("lod-rendering", "Distant terrain rendering", FabricFunctionalityArea.RENDERING, "The production renderer consumes native, generated, restored, and schema-v2 network vertical pages, derives bounded quadtree parents, builds semantic vertical faces per selected page through the shared CPU service, and publishes region/material/view batches with independent main/shadow screen-space selection. Top-only input remains only as readable v1 migration data. Bounded CPU/byte admission, retry/quarantine, last-known-good shader reload, atomic page replacement, overflow storage shards, built-in/Bliss checked pixels, mixed-detail selection, movement scaling, the post-retirement streaming soak, and Apple OpenGL resource cleanup are qualified; cross-driver qualification remains release-matrix work.", "DistantTerrainInterop / DistantTerrainRenderer / DistantHierarchicalTerrainRuntime / DistantPageHierarchyIndex / DistantPageMesher"),
        mapped("lod-shader-programs", "Iris Distant Horizons programs", FabricFunctionalityArea.RENDERING, "The exact adapter activates the DISTANT_HORIZONS pack macro and executes dh_terrain, dh_water, and dh_shadow through a compact provider-neutral position/color/light/normal-material ABI, typed DH matrices, and independent DH depth targets.", "DistantTerrainShader / IrisLegacyShaderTransformer / IrisDistantFrameState / IrisOpenGlRenderTargets"),
        mapped("lod-world-generation", "Distant world generation", FabricFunctionalityArea.WORLD, "Local authority produces detached partial vertical pages without publishing native chunks. The managed Fabric server admits bounded base-page work, deduplicates shared FULL-chunk futures, validates player/world epochs at admission and completion, cancels queued/in-flight publication on dimension change or disconnect, and passed the named live TPS gate.", "DistantUnexploredGenerator / DistantHorizonsLodServer"),
        mapped("lod-networking", "Distant terrain networking", FabricFunctionalityArea.NETWORKING, "The advertised schema-v2 channel carries connection/world epoch, normalized level, request ID, bounded page keys/detail, source revision, and bounded page payloads. The client atomically rejects wrong-world, unknown, unrequested, duplicate, and unsupported-detail responses while consuming stale pages as superseded without publishing them; pre-join v2 negotiation survives playable-world replacement; v1 remains readable but is not advertised by the managed server.", "DistantTerrainProtocolV2 / DistantTerrainRequestTracker / DistantLodNetworkClient / DistantHorizonsLodServer"),
        mapped("lod-options", "Distant Horizons settings", FabricFunctionalityArea.OPTIONS, "Native persisted settings expose rendering, local generation, storage, and managed-server transfer controls through the shared settings schema. Pixel-identical upstream Mod Menu screens are not claimed.", "DistantHorizonsOptions / FabricSettings"),
        unmapped("dh-binary-api", "Distant Horizons binary API", FabricFunctionalityArea.API, "The Mojang/Fabric-targeted implementation and API classes are inspected but do not link into Minosoft."),
    )

    val ENTITY_CULLING = listOf(
        mapped("entity-visibility", "Entity visibility", FabricFunctionalityArea.PERFORMANCE, "Owned hook crosses every native entity visibility decision.", "FabricEntityVisibilityHooks"),
        mapped("config-screen", "Configuration screen", FabricFunctionalityArea.OPTIONS, "A writable persisted form controls occlusion policy, native entity distance, and a validated identifier whitelist.", "EntityCullingOptions / FabricSettings"),
        unmapped("block-entity-culling", "Block entity culling", FabricFunctionalityArea.PERFORMANCE, "No block-entity visibility adapter exists."),
        unmapped("tick-culling", "Tick culling", FabricFunctionalityArea.PERFORMANCE, "Client entity ticking is not suppressed by visibility."),
        unmapped("nametag-culling", "Nametag culling", FabricFunctionalityArea.RENDERING, "Nametag-through-wall policy is not mapped."),
        mapped("entity-whitelist", "Entity whitelist", FabricFunctionalityArea.OPTIONS, "Validated resource identifiers bypass only the occluded visibility level while preserving distance and frustum rejection.", "EntityCullingOptions"),
        unmapped("block-entity-whitelist", "Block entity whitelist", FabricFunctionalityArea.OPTIONS, "Per-type block-entity exclusions are not mapped."),
        unmapped("debug-culling", "Culling debug controls", FabricFunctionalityArea.DIAGNOSTICS, "Toggle keys, boxes, tracing distance, and F3 diagnostics are not mapped."),
        unmapped("solid-leaves", "Solid leaves", FabricFunctionalityArea.PERFORMANCE, "Leaves are not promoted to occlusion solids by this adapter."),
    )

    val IMMEDIATELY_FAST = listOf(
        mapped("frame-batching", "Frame batching", FabricFunctionalityArea.PERFORMANCE, "Owned frame hook crosses Minosoft's retained queue-flush boundary.", "FabricFrameHooks"),
        partial("hud-batching", "HUD batching", FabricFunctionalityArea.PERFORMANCE, "Minosoft retains GUI meshes, but ImmediatelyFast's batching implementation is not executed.", "GUI retained meshes"),
        partial("screen-batching", "Screen batching", FabricFunctionalityArea.PERFORMANCE, "Minosoft retains screens independently; behavioral parity is unverified.", "GUI retained meshes"),
        unmapped("font-atlas-resizing", "Font atlas resizing", FabricFunctionalityArea.PERFORMANCE, "ImmediatelyFast font atlas growth is not mapped."),
        unmapped("map-atlas-generation", "Map atlas generation", FabricFunctionalityArea.PERFORMANCE, "Map texture atlas generation is not mapped."),
        unmapped("fast-text-lookup", "Fast text lookup", FabricFunctionalityArea.PERFORMANCE, "ImmediatelyFast text lookup caches are not mapped."),
        unmapped("fast-buffer-upload", "Fast buffer upload", FabricFunctionalityArea.PERFORMANCE, "ImmediatelyFast buffer upload path is not mapped."),
        unmapped("sign-text-buffering", "Sign text buffering", FabricFunctionalityArea.PERFORMANCE, "Experimental sign text buffering is not mapped."),
        mapped("runtime-config", "Runtime configuration", FabricFunctionalityArea.OPTIONS, "Persisted source-native switches control the frame hook and record HUD/screen retained-batching policy.", "ImmediatelyFastOptions / FabricSettings"),
        unmapped("conflict-handling", "Mod and hardware conflict handling", FabricFunctionalityArea.DIAGNOSTICS, "ImmediatelyFast compatibility guards are not mapped."),
    )

    val FABRIC_API = listOf(
        partial("fabric-api-modules", "Fabric API module inventory", FabricFunctionalityArea.API, "Fifty nested module identities are resolved and exposed, but their binary APIs do not link.", "FabricApiModuleRegistry"),
        mapped("client-events", "Client event bridge", FabricFunctionalityArea.API, "An owned render-thread bridge dispatches deterministic lifecycle, world-render, and HUD phases with isolated callbacks.", "FabricClientEvents"),
        mapped("client-connection-events", "Client connection event bridge", FabricFunctionalityArea.NETWORKING, "Owned created, state-change, joined, and disconnected phases follow each Minosoft play session.", "FabricClientConnectionEvents"),
        mapped("client-commands", "Client commands", FabricFunctionalityArea.GAMEPLAY, "Owned local commands intercept exact names before server dispatch and receive an explicit session plus raw arguments.", "FabricClientCommands"),
        mapped("client-payload-channels", "Client payload channels", FabricFunctionalityArea.NETWORKING, "Bounded owner-scoped receivers observe immutable payload views and send through an explicit play session.", "FabricClientPayloadChannels"),
        partial("remote-registry-sync", "Fabric remote registry sync", FabricFunctionalityArea.NETWORKING, "The 1.20.4 configuration handshake advertises and decodes Fabric Registry Sync v0 direct payloads, transactionally remaps entity wire IDs, and materializes owner-scoped dependent-mod entity definitions. Other synchronized registry kinds and general binary mod discovery remain.", "FabricRemoteRegistrySync / Registry.replaceIds"),
        mapped("client-tick-events", "Client tick event bridge", FabricFunctionalityArea.API, "Owned start/end phases bracket one ordered 20 Hz session tick cycle with per-task failure isolation.", "FabricClientTickEvents"),
        mapped("world-events", "World and dimension lifecycle", FabricFunctionalityArea.WORLD, "Owned before/after identity changes and playable join/leave phases cover remote and local sessions.", "FabricWorldEvents"),
        mapped("chunk-events", "Chunk lifecycle", FabricFunctionalityArea.WORLD, "Central create, update, unload, and bulk-clear events cover network and local chunk managers.", "FabricChunkEvents"),
        mapped("block-mutation-events", "Block mutation batches", FabricFunctionalityArea.WORLD, "Single and batched block changes publish previous/current states after the native mutation boundary.", "FabricBlockMutationEvents"),
        mapped("entity-events", "Entity lifecycle", FabricFunctionalityArea.WORLD, "Central add, remove, and bulk-clear events cover local and remote world entity stores.", "FabricEntityEvents"),
        mapped("particle-events", "Accepted particle events", FabricFunctionalityArea.RENDERING, "Particles crossing native enablement and view-distance policy are observed before entering the render queue.", "FabricParticleEvents"),
        mapped("sound-events", "Sound request events", FabricFunctionalityArea.API, "2D, world, packet, and local sound requests are observed at the native audio-player boundary before queued resolution.", "FabricSoundEvents"),
        mapped("player-interactions", "Player interaction decisions", FabricFunctionalityArea.GAMEPLAY, "Ordered owner hooks can pass or deny attack-block, attack-entity, use-block, use-entity, and use-item actions before native handling.", "FabricPlayerInteractionHooks"),
        mapped("screens", "Owned screen factories", FabricFunctionalityArea.OPTIONS, "Namespaced screen factories open on the render queue and disappear with their owner scope.", "FabricScreens"),
        mapped("settings-forms", "Typed settings forms", FabricFunctionalityArea.OPTIONS, "Source-native schemas provide typed staged values, dependency-driven disabled states, validation, reset/apply/cancel, persistence callbacks, rollback on failed apply, and restart markers through owned screens.", "ConfigEntry / SettingsSession / FabricSettings"),
        mapped("cycle-selectors", "Cycle selectors", FabricFunctionalityArea.OPTIONS, "Reusable cycle selectors expose bounded typed options through mouse, wheel, and keyboard input.", "CycleSelectorElement"),
        mapped("scroll-panels", "Clipped scroll panels", FabricFunctionalityArea.OPTIONS, "Scrollable panels render and tick only intersecting rows and CPU-clip GUI quads, text, and item quads to the viewport.", "ClippedScrollPanelElement / ClippedGuiVertexConsumer"),
        mapped("tabs-and-search", "Categories, tabs, descriptions, and search", FabricFunctionalityArea.OPTIONS, "Bounded tab bars, category schemas, hover descriptions, and focused search filtering retain valid selection across result changes.", "TabBarElement / SettingsFilter / TextPopper"),
        mapped("virtual-grids", "Clipped virtual grids", FabricFunctionalityArea.RENDERING, "Virtual grids instantiate, tick, hit-test, and render only intersecting cells through the shared clip consumer.", "ClippedVirtualGridElement / VirtualGridState"),
        mapped("dialogs-and-banners", "Dialogs and status banners", FabricFunctionalityArea.OPTIONS, "Source-native modal actions and severity-colored status banners cover confirmation, recovery, warning, and error presentation.", "ConfirmationDialog / StatusBannerElement"),
        mapped("map-canvases", "Interactive map canvases", FabricFunctionalityArea.RENDERING, "A clipped canvas provides anchored pan/zoom transforms, visible bounds, marker tooltips, selection, and waypoint-edit callbacks.", "MapCanvasElement / MapViewportState"),
        mapped("machine-screen-primitives", "Synchronized machine-screen primitives", FabricFunctionalityArea.OPTIONS, "Revisioned snapshots, tanks, energy/progress gauges, tabs, tooltips, custom native slots, and bounded payload controls are source-native.", "SynchronizedMachineScreenState / GaugeElement / FabricMachineControl"),
        mapped("hud-layers", "Owned HUD layers", FabricFunctionalityArea.RENDERING, "HUD builders attach to current and future render sessions and unload on the render queue with their owner.", "FabricHudLayers"),
        mapped("input-events", "Normalized input event bridge", FabricFunctionalityArea.API, "Owned key, character, mouse-move, and scroll observations use the normal GLFW/debug-injection event path on the render thread.", "FabricInputEvents"),
        mapped("key-bindings", "Owned key binding bridge", FabricFunctionalityArea.OPTIONS, "Namespaced configurable bindings attach to every render session and remove only their owner callback when closed.", "FabricKeyBindings"),
        mapped("resource-reload-events", "Resource reload event bridge", FabricFunctionalityArea.RELOAD, "Owned prepare/apply/complete/failure phases cover session assets and render-queue shader/texture reload operations.", "FabricResourceReloadEvents"),
        partial("fabric-lifecycle-events", "Lifecycle events", FabricFunctionalityArea.API, "Owned client start and stopping phases are emitted on the render thread; Fabric binary callback interfaces do not link yet.", "FabricClientEvents"),
        partial("fabric-key-binding-api", "Key binding API", FabricFunctionalityArea.API, "Source-native configurable bindings and normalized input events exist; Fabric KeyBindingHelper and Mojang key types do not link.", "FabricKeyBindings"),
        partial("fabric-client-connection-events", "Client connection events", FabricFunctionalityArea.NETWORKING, "Source-native session lifecycle and bounded payload channels exist; Fabric binary callback and payload types do not link.", "FabricClientConnectionEvents / FabricClientPayloadChannels"),
        partial("fabric-rendering-api", "Rendering APIs", FabricFunctionalityArea.API, "Owned render phases and lifecycle-safe HUD layers use Minosoft render types; Fabric binary rendering types do not link yet.", "FabricClientEvents / FabricHudLayers"),
        partial("fabric-networking-api", "Networking APIs", FabricFunctionalityArea.NETWORKING, "Source-native client play channels are bounded and owner-scoped; login, server, codec, and Fabric binary payload types remain unmapped.", "FabricClientPayloadChannels"),
        partial("fabric-resource-api", "Resource APIs", FabricFunctionalityArea.API, "Source-level reload lifecycle events exist for session assets, shaders, and textures; Fabric binary listeners and general resource-manager APIs do not link.", "FabricResourceReloadEvents"),
        unmapped("fabric-server-api", "Server APIs", FabricFunctionalityArea.SERVER, "Fabric server entrypoints and APIs have no host runtime."),
    )

    val IRIS = listOf(
        partial("shader-pack-loader", "Shader pack loader", FabricFunctionalityArea.RENDERING, "The settings bridge discovers directory/zip packs; the native planner classifies dimension-scoped Iris 1.7.2 geometry, shadow, and ordered fullscreen program families, typed color/depth targets, conditional programs, particle ordering, and separate translucent entity draws. Unsupported stages, incomplete known programs, and unknown families fail closed; broad OptiFine compatibility remains.", "IrisPresentationController / IrisShaderPackPlanner"),
        partial("shader-pipeline", "Shader rendering pipeline", FabricFunctionalityArea.RENDERING, "With MINOSOFT_SHADER_PACK set, the exact Iris adapter plans and transactionally compiles typed targets, material terrain and shadow programs, negotiated scene programs, and ordered begin/shadow-composite/prepare/deferred/composite/final chains. It pins one selected generation across the complete frame, routes opaque and translucent particle/entity/block-entity producers into explicit phases, preserves vertex/state ABIs while specializing line, leash, emissive-eye, armor-glint, beacon-beam, lightning, and explicit non-opaque first-person Gecko item layers through pinned Iris fallbacks, supports path-tracing main-geometry suppression without disabling shadows or post-processing, preserves the prior generation on failure, and retires GPU generations through render-thread unload. Producer-rich live canaries, ordinary held-item material splitting, the remaining OptiFine property, uniform, custom-image, and compute catalogs, and independent pack acceptance remain.", "ShaderPipelineRegistry.withFramePipeline / ShaderPipelineRegistry.withScene / RendererPipeline / IrisShaderPackPlanner / IrisWorldShaderPipeline"),
        partial("shadow-maps", "Shader shadow maps", FabricFunctionalityArea.RENDERING, "The shader-pack shadow view owns typed color/depth targets, immutable Iris-compatible projection/light matrices, opaque/translucent terrain depth separation, whole-producer terrain/entity/block-entity caster routing, player-only and light-emitter subsets, independent loaded-producer collection, authored terrain/entity distance multipliers, and pinned default/advanced/distance/reversed/voxel culling volumes. Split Gecko block-entity translucent/additive layers can complete inside the same pre-snapshot block-entity submission, while beacon beams retain Iris's explicit exclusion. Cascades, loading beyond the host render distance, perspective-specific checked pixels, independent cross-driver references, and broader target catalogs remain.", "iris:shaderpack/shadow / BlockEntityRenderer.drawShadow / IrisShadowCullingVolume / IrisWorldShaderPipeline"),
        partial("shader-settings", "Shader settings screen", FabricFunctionalityArea.OPTIONS, "A persisted source-native form selects packs, applies inherited profiles and profile-driven program suppression, exposes validated boolean/enumerated defines, uses stepped controls for authored sliders, and maps authored option order into bounded top-level groups. It consumes locale-specific shaders/lang labels, comments, profiles, screens, and values with en_us fallback; supports cross-group search; and replaces unreadable large tab strips with a category selector. Exact Iris column-grid/spacer navigation and the remaining property/custom-input grammar remain.", "IrisPresentationController / ShaderPackSettings / ShaderPackLanguage / SettingsFormMenu"),
        partial("sodium-shader-interop", "Sodium shader integration", FabricFunctionalityArea.RENDERING, "Sodium and Iris negotiate the selected semantic vertex layout before publication and share one terrain submission path, including the shadow view. The current Sodium provider still emits the host packed ABI rather than upstream Sodium's compact encoder.", "TerrainBackendDescriptor / ShaderPipelineRegistry"),
        mapped("resource-reload-events", "Live shader reload", FabricFunctionalityArea.RELOAD, "Shader-pack parsing, supported-program validation, program compilation, target creation, and publication form one last-known-good generation; failed candidates leave the active pipeline selected, and installing or removing a presentation invalidates terrain material generations before the next complete frame.", "IrisShaderPipelineController.reload / ShaderPipelineRegistry"),
    )

    val ENTITY_MODEL_FEATURES = listOf(
        mapped("skeletal-content", "CEM JEM/JPM geometry ingestion", FabricFunctionalityArea.CONTENT, "The exact adapter owns a headless parser for JEM models, external JPM parts, boxes, per-face UVs, transforms, inflation, attachments, and expression bindings.", "CemParser / SkeletalContentParsers"),
        partial("entity-part-aliases", "Version-aware entity part aliases", FabricFunctionalityArea.CONTENT, "Version/entity aliases are applied to bound geometry, transforms, and expression targets with deterministic collision failure and removal; the complete vanilla entity mapping catalog is not populated yet.", "SkeletalPartAliases / SkeletalModelBinder"),
        partial("cem-expressions", "CEM expression animation", FabricFunctionalityArea.CONTENT, "A bounded, reflection-free per-instance runtime reads live aliased part pivots, rotations, scales, visibility, and box-hidden state, applies ordered absolute property writes, preserves distinct visible/visible_boxes hierarchy semantics, rejects missing write targets, and retains persistent var/varb state. Its centralized EMF 3.0.17 context supplies every catalogued input except the intentional nan constant: partial-tick clocks, frame counters, degree/radian rotations, body-relative head yaw, vanilla limb interpolation, movement projection, dimensions, positions, health plus hurt/death timers, equipment/use, attachment, locomotion, tame/aggressive/anger, fluid/ground probes, stopped-arrow collision state, exposed rain wetness with pinned altitude/frozen-biome temperature sampling, hovered state, selected ETF rule index, explicit render-path flags, and raw nbt(key,query) predicates through the shared ETF matcher. Non-world CEM render routes, special-case limb/entity parity, diagnostic parity, and complex attachment parity remain.", "SkeletalExpression / CemExpressionEvaluator / CemExpressionManager / CemEntityExpressionContextFactory / CemRenderPathContext / CemLimbState / LivingEntity / Biome / VanillaBiomeTemperature / EntityTextureMaterialFrame / TransformInstance / EntityTextureConditions"),
        partial("cem-render-binding", "Entity renderer binding", FabricFunctionalityArea.RENDERING, "Neutral geometry binds to retained skeletal transforms, direct textures, UVs, and matching animal/humanoid IDs. Native parts survive targeted replacement, attach roots use isolated child transforms, and owner-scoped render outputs drive bounded terrain-conforming resource-textured shadows plus leashes with audited local mob/EMF anchors, generic/knot/player holder anchors, quadratic sag, alternating vanilla colors, and two crossed 24-segment ribbons. Mob skeletal roots and leash anchors share a previous/current vanilla body-control pose, while leash vertices interpolate independent endpoint block/sky light through the renderer lightmap. The shadow path uses audited UV, fixed-level clamp, and source-alpha blend behavior. Broader entity/feature coverage, player-holder body-yaw dynamics, non-default shadow sampler-metadata overrides, and rendered visual acceptance remain.", "SkeletalModelBinder / SkeletalModelComposer / SkeletalLoader / EntityBodyRotation / EntityRenderEffects / EntityShadowProjector / EntityShadowFeature / EntityRenderFeatures.SHADOW_TEXTURE / ShaderManager.entityShadowTextureShader / ShaderManager.entityLeashShader / EntityLeashProjector / EntityLeashFeature / LightColorMeshBuilder"),
        partial("cem-reload", "CEM generation reload", FabricFunctionalityArea.RELOAD, "The render-thread content reload reparses, re-bakes, uploads, and atomically swaps CEM model/entity lookups plus refreshed/new static texture buckets. A rejected candidate preserves prior texture names/handles and the active generation; successful publication deletes replaced handles; retired models defer mesh disposal and stable texture-slot reclamation until their final retained instance closes. Dead holes are reused and trailing layers compact without renumbering live meshes; repeated real-GL accounting remains.", "ReloadCommand / SkeletalLoader.reloadContentFidelity / StaticTextureArrayUpdate / StaticTextureSlotOwnership / ContentGenerationStore"),
        unmapped("emf-config", "EMF configuration", FabricFunctionalityArea.OPTIONS, "EMF configuration and Mod Menu screens are not mapped."),
    )

    val ENTITY_TEXTURE_FEATURES = listOf(
        mapped("entity-texture-content", "Random entity texture rules", FabricFunctionalityArea.CONTENT, "The exact adapter owns a headless OptiFine-style properties parser, deterministic weighted selection, extensible context predicates, and generation-owned selection cache.", "EntityTextureRuleParsers / EntityTextureSelectionCache"),
        partial("entity-texture-context", "Entity texture context", FabricFunctionalityArea.CONTENT, "Rules consume biome tags, weather/name/height/time/health, pack-scoped active-mod IDs, team, profession/type, general variants, color/tame/movement state, equipment/items, genes, inventory/jump/movement attributes, vanilla-client regional difficulty, predicate-gated vertical block IDs, current-plus-below blocks/blockSpawned with colon-separated state subsets, solid_render-backed isOpaqueFullCube probes, prior textureRule/textureSuffix selection, and bounded entity/client-player/vehicle NBT. Shared NBT predicates cover existence, inversion, integer ranges, wildcard list paths, raw/string, wildcard, and bounded regex forms. Broader block-entity context remains.", "EntityTextureContextFactory / EntityTextureRegionalDifficulty / EntityTextureConditions / EntityTextureSelectionCache / EntityTextureRuntimeEnvironment"),
        partial("entity-texture-variants", "Entity texture variants", FabricFunctionalityArea.RENDERING, "Deterministic stable variant selection, finite material discovery, generation-owned caching, and independently selected skeletal body/feature materials exist. Broader non-skeletal feature bindings and visual parity remain.", "EntityTextureRuleSet / SkeletalLoader"),
        partial("entity-texture-materials", "Emissive, blinking, and player-feature materials", FabricFunctionalityArea.RENDERING, "Configured emissive suffixes plus deterministic base, emissive, blink, and blink2 frames reach retained skeletal entities. A retained native-skeletal integration reloads independent base/feature materials and proves exact half/closed/open selection with paired emissives and two geometry plus two emissive passes while the retired generation remains leased. ETF predicates cover expanded entity/environment and bounded NBT context, exclusions, semantic version ranges, calendar/world/client values, and percent health. ETF-marked player skins derive matching blink/emissive textures, all eight coat styles/lengths, moved-base edits, coat emissives, fat-coat inflation, leggings suppression, forced lower-skin opacity, legacy/controller villager noses, five-source textured noses with removal/emissive passes, marker-selected animated glint masks, and profile-controlled ETF-only base transparency; world and first-person views share base/blink state. Full-bright and glint passes restore render state. Broader non-skeletal features, configuration, repeated real-GL validation, and visual parity remain.", "EntityTextureMaterial / EntityTextureContextFactory / EtfPlayerSkinProcessor / EtfPlayerSkinTextures / SkeletalFeature / PlayerRenderer / PlayerModel / PlayerModelMeshBuilder / SkeletalLoaderTest"),
        partial("entity-texture-reload", "Entity texture generation reload", FabricFunctionalityArea.RELOAD, "The render-thread content transaction swaps ETF catalogs, pre-baked content and native skeletal material meshes, and refreshed/new static texture buckets together. Each old model leases its exact catalog/cache and texture coordinates through final retirement, while a missing asset rejects the candidate. Dead content-owned holes are reused, trailing layers compact, and permanent resource-pack slots are preserved. Player-derived textures are source-generation keyed; repeated real-GL disposal proof remains.", "SkeletalLoader.reloadContentFidelity / StaticTextureArrayUpdate / StaticTextureSlotOwnership / BakedSkeletalModel.contentLease"),
        unmapped("etf-config", "ETF configuration", FabricFunctionalityArea.OPTIONS, "ETF configuration and Mod Menu screens are not mapped."),
        unmapped("etf-player-skin-api", "ETF player skin API", FabricFunctionalityArea.API, "ETF's binary player skin and texture APIs do not link to native Minosoft services."),
    )

    val GECKOLIB = listOf(
        mapped("skeletal-content", "Geo JSON ingestion", FabricFunctionalityArea.CONTENT, "The exact adapter owns a headless parser for geometry documents, bone hierarchies, cubes, pivots, rotations, inflation, mirroring, box UVs, and per-face UVs. Retained binding expands vertex bounds for inflation while deriving box UVs from GeckoLib's floored uninflated source size.", "GeckoLibParser / SkeletalContentParsers / SkeletalModelBinder / SkeletalElement.boxUvSize"),
        partial("animation-json", "Animation JSON ingestion", FabricFunctionalityArea.CONTENT, "Numeric and expression vectors, loop/hold/once timing, keyed channels, linear/step/Catmull-Rom interpolation, sound/particle/custom event timelines, every built-in easing plus its first argument from the pinned 4.4.4 artifact, and owner-scoped custom easings are parsed/evaluated deterministically and reach retained playback; visual parity remains.", "GeckoLibParser / SkeletalAnimationEvaluator / GeckoLibEasingRegistry"),
        partial("animation-controllers", "Animation controllers", FabricFunctionalityArea.API, "The source-native API supports stable content-identity routes for entities, block entities, items, and armor plus controller factories, named predicate controllers, playback/restart/stop, transitions, concurrent replace/add layers, expression data, bounded declared protocol tracked-data inputs, immutable bounded random/entity-type/nearby-player host-state inputs, bounded host-event-to-trigger mappings, state-driven animation speed/easing overrides, triggerable animation preemption/base reload, typed keyframe handlers, generation-owned animatable caches, owner-scoped easing/loop/event/render-layer listeners, bounded RawAnimation default/play-once/hold/loop/wait/repeat queues with delta carry, stable finished identity, current-stage queries, explicit reset, and compatible state migration without event replay. Generic objects have typed data tickets, first-tick/update state, controller triggers, compatible snapshots, and instanced or bounded singleton cache ownership. Entity animation packets enter a bounded per-entity sequence journal; retained living consumers replay only fresh events through the controller owner's quiescent callback boundary. Retained renderers publish immutable controller/queue summaries bounded to 64 records while preserving total count and fail closed after owner retirement. Handler-defined runtime effect aliases can be mapped, suppressed, or passed through by one generation-bound resolver per content identity. The pinned Naturalist adapter supplies dependent-mod route/controller/effect-handler coverage; a local real-renderer gate observes its four-controller rattlesnake timeline across reload. Additional dependent mods and GeckoLib/Mojang binary APIs remain.", "EntityAnimationJournal / GeckoLibControllerBindingRegistry / GeckoLibRuntimeEffectRegistry / GeckoLibModelRouteRegistry / GeckoLibControllerSet / GeckoLibControllerSetInspection / GeckoLibTrackedDataInput / GeckoLibHostStateInput / GeckoLibEntityHostStateResolver / GeckoLibControllerSetSnapshot / GeckoLibRawAnimation / GeckoLibAnimatableManager / GeckoLibInstancedAnimatableInstanceCache / GeckoLibSingletonAnimatableInstanceCache / GeckoLibEasingRegistry / GeckoLibLoopTypeRegistry / GeckoLibRuntimeEvents"),
        partial("gecko-rendering", "Geo model rendering", FabricFunctionalityArea.RENDERING, "Matching geometry and animation documents cross the retained-skeletal bridge. Identity-routed multi-controller sets render entities, routed block entities, world/item-display and first-person held items, plus routed armor slots; generation-baked opaque/translucent/additive texture passes evaluate bounded state predicates and restore render state. Owner-scoped dependent-mod texture resolvers declare every bakeable base candidate and select one from bounded live entity state without retaining callbacks across adapter generations. Exact owner-declared entity definitions can also materialize through the local data-pack authority without a synthetic wire ID. Generic non-rendered objects use the headless manager facade, and a pinned Naturalist real-GL scene retains one 252-vertex body pass and its four-controller rattle state across reload. GUI item views, exact armor-to-parent-bone fitting, checked remote Naturalist pixels, additional dependent mods, and visual parity remain.", "ContentFidelityLoader / SkeletalModelBinder / GeckoLibAnimationManager / GeckoLibModelRouteRegistry / GeckoLibEntityTextureRegistry / FabricRemoteRegistrySync / LocalDisplayEntityFactory / GeckoLibBlockEntityRenderer / ItemFeature / HeldItemRenderer / GeckoLibArmorFeature"),
        partial("gecko-events", "Sound, particle, and custom keyframes", FabricFunctionalityArea.GAMEPLAY, "Typed per-controller handlers retain exact sound, particle locator/pre-effect, and custom instruction data. Bounded entity, block-entity, item, and armor keyframes defer until finalized transforms, then a content-identity-bound owner resolver can map, suppress, or pass through handler-defined effect aliases before native positional sound or registered particle playback. Retained bindings fail closed after owner replacement; custom instructions still reach owner-scoped listeners. Additional dependent-mod mappings and visual parity remain.", "GeckoLibControllerKeyframeEvent / GeckoLibRuntimeEffectRegistry / GeckoLibEventPlayback / GeckoLibEntityEventConsumer / GeckoLibBlockEntityRenderer / GeckoLibRuntimeEvents"),
        partial("gecko-reload", "GeckoLib generation reload", FabricFunctionalityArea.RELOAD, "Geometry, matching clips, dependent-mod base-texture candidates, and render-layer meshes plus refreshed/new static texture buckets are rebuilt, uploaded, and atomically swapped on the render thread; rejected candidates preserve active texture/model lookups and generation-bound selectors, retired GPU meshes and texture coordinates survive retained instances until final release, dead slots compact without renumbering live meshes, compatible entity/item/armor controller state migrates without event replay, and routed block-entity section caches rebuild against the new generation. A live Naturalist run balanced 297 candidate GL objects at both rejection checkpoints and preserved its exact rattlesnake route/texture through accepted recovery; checked pixels and repeated visible multi-entity loops remain.", "SkeletalLoader.reloadContentFidelity / GeckoLibEntityTextureRegistry / ChunkRenderer.invalidate / ItemFeature.reloadContentModel / GeckoLibArmorFeature / StaticTextureArrayUpdate / StaticTextureSlotOwnership / GeckoLibControllerSetSnapshot / BakedSkeletalModel.retire"),
        unmapped("gecko-binary-api", "Dependent-mod binary API", FabricFunctionalityArea.API, "Classes compiled against GeckoLib and Mojang types do not link to the native data adapter."),
    )

    val NATURALIST = listOf(
        mapped("skeletal-content", "Naturalist animal models", FabricFunctionalityArea.CONTENT, "The exact adapter mounts the verified artifact and owns 32 entity routes across 25 Gecko geometry identities, including the static detached lizard tail.", "ExternalAssetProviders / GeckoLibEntityModelRegistry"),
        partial("animal-animations", "Animal animations", FabricFunctionalityArea.RENDERING, "Naturalist RP animation documents are ingested. The pinned snake's primary controller reads exact climbing/sleeping wire indices and matches stopped-default, move, climb, and sleep selection with its ten-tick transition. A separate attack controller maps fresh main/off-arm protocol swing events to the exact non-looping 0.25-second attack clip. Independent tongue and rattle controllers reproduce the exact awake/stopped random-versus-age play-once predicate and awake rattlesnake/nearby-player loop predicate through immutable bounded host-state queries. Its generation-bound effect handler maps only the upstream tongue `hiss` alias and suppresses the current `idle` plus handler-less `rattle` aliases instead of auto-playing fake Minecraft sound IDs. A local real-renderer scenario observes all four controllers and the exact rattle queue advancing across production reload. The other species still use a deterministic idle/movement fallback; remote packet/sound timing, fixed-pixel seams, broader layered predicates, and exact visual parity remain.", "EntityAnimationJournal / GeckoLibControllerBindingRegistry / GeckoLibControllerSetInspection / GeckoLibRuntimeEffectRegistry / GeckoLibTrackedDataInput / GeckoLibHostStateInput / GeckoLibEntityHostStateResolver / GeckoLibHostEvents"),
        partial("animal-textures", "Animal textures", FabricFunctionalityArea.RENDERING, "Twenty-five source-native GeoModel texture resolvers declare and bake exact artifact candidates. Entity type/name, baby/aggressive state, and pinned Naturalist tracked-data indices select bird, snake, butterfly, dragonfly, lizard/tail, tortoise, snail, bear, lion, deer, and duck bases before optional ETF composition. A live remote rattlesnake resolved geometry.snake and rattlesnake.png before and after transactional recovery. Source renderer overlays, held-food substitutions, checked pixels, and exact visual parity remain.", "GeckoLibEntityTextureRegistry / SkeletalLoader / EntityData.raw / ExternalAssetProviders"),
        partial("blocks-items-recipes", "Blocks, items, recipes, and loot", FabricFunctionalityArea.CONTENT, "Standard artifact assets mount on the client, but Minosoft does not source-register Naturalist's gameplay objects or execute its recipes and loot.", "ExternalAssetProviders"),
        unmapped("animal-ai-spawning", "Animal AI and spawning", FabricFunctionalityArea.GAMEPLAY, "Naturalist's Mojang/Fabric entity registrations, goals, spawn rules, breeding, combat, and interactions are not executed in Minosoft."),
        partial("naturalist-networking", "Naturalist entity synchronization", FabricFunctionalityArea.NETWORKING, "The adapter owns all 33 pinned remote entity definitions and the standard Fabric Registry Sync v0 handshake materializes their server-assigned wire IDs per session. Bounded raw tracked-data access drives pinned source texture indices and declared controller inputs. Protocol entity-animation packets append to a bounded per-entity sequence journal and fresh arm-swing events drive the pinned snake attack controller; live remote rattlesnakes resolve their exact Gecko route and texture. Broader Naturalist-specific tracked semantics and checked live attack pixels remain.", "FabricRemoteRegistrySync / Registry.replaceIds / EntityData.raw / EntityAnimationJournal / GeckoLibTrackedDataInput / GeckoLibEntityTextureRegistry"),
        partial("naturalist-config", "Naturalist configuration", FabricFunctionalityArea.OPTIONS, "A searchable source-native category exposes and persists every client model-removal route from the pinned MidnightLib config; server spawn values remain gameplay-owned.", "NaturalistModelOptions / FabricSettings"),
    )

    val JEI = listOf(
        unmapped("ingredient-overlay", "Item and ingredient overlay", FabricFunctionalityArea.RENDERING, "JEI's paged item and ingredient list overlay and cheat-mode rendering are not implemented."),
        mapped("recipe-viewer", "Recipe viewer", FabricFunctionalityArea.GAMEPLAY, "An owned container control opens a clipped searchable recipe-card view with categories, ghost ingredient/result slots, selection, and item tooltips.", "JeiRecipeMenu / JeiRecipeCardElement"),
        unmapped("recipe-transfer", "Recipe transfer", FabricFunctionalityArea.GAMEPLAY, "Crafting and inventory recipe transfer slot routing and packets are not mapped."),
        partial("bookmarks", "Bookmark ingredients", FabricFunctionalityArea.GAMEPLAY, "Persisted recipe bookmarks and bookmark-only filtering are available; a separate sticky ingredient overlay remains.", "JeiOptions"),
        partial("search", "Search and filtering", FabricFunctionalityArea.OPTIONS, "Focused term search covers identifiers, categories, ingredients, and results; JEI's full prefix syntax remains.", "JeiRecipeMenu"),
        unmapped("ingredient-sync", "Ingredient sync", FabricFunctionalityArea.NETWORKING, "Server-to-client ingredient and recipe sync channels do not link."),
        unmapped("recipe-plugin-api", "Recipe plugin API", FabricFunctionalityArea.API, "JEI's runtime plugin registration and recipe category API do not link."),
        mapped("container-screen-extensions", "Inventory screen integration", FabricFunctionalityArea.RENDERING, "An owned native extension supplies the Recipes tab in Creative inventories and a standalone control in Survival containers without applying Mojang mixins.", "FabricContainerScreenExtensions / CreativeInventoryCatalogElement"),
        mapped("jei-config", "Configuration", FabricFunctionalityArea.OPTIONS, "An owned source-native form persists bookmark and tooltip policy.", "JeiOptions / FabricSettings"),
        unmapped("jei-dev-tools", "Ingredient debug tools", FabricFunctionalityArea.DIAGNOSTICS, "JEI's ingredient tree and runtime debugging views are not mapped."),
    )

    val MOD_MENU = listOf(
        mapped("installed-mod-list", "Installed mod list", FabricFunctionalityArea.OPTIONS, "The clipped host catalog lists every staged top-level artifact with version, badges, hierarchy, icon presence, activation state, and mapping counts.", "FabricModSettingsMenu"),
        mapped("config-navigation", "Configuration navigation", FabricFunctionalityArea.OPTIONS, "Configurable mod rows resolve owner-scoped source-native screen registrations directly; non-configurable rows retain mapping inspection.", "FabricSettings / FabricScreens"),
        unmapped("modmenu-entrypoints", "Mod Menu API entrypoints", FabricFunctionalityArea.API, "ModMenuApi binary entrypoints and provided screen factories are not implemented."),
        mapped("search-and-filters", "Search and filters", FabricFunctionalityArea.OPTIONS, "Focused search plus all/configurable/library/blocked filters drive a clipped result list.", "FabricModSettingsMenu"),
        partial("metadata-badges", "Metadata badges and hierarchy", FabricFunctionalityArea.OPTIONS, "Descriptions, icon paths, Mod Menu badges, parents, and dependency grouping are decoded and presented; archive icon pixels are represented by presence rather than decoded into a texture.", "FabricMetadataReader / FabricModSettingsMenu"),
        unmapped("update-checker", "Mod update checker", FabricFunctionalityArea.NETWORKING, "Runtime Modrinth/update-source checks are intentionally not executed by the adapter."),
    )

    val XAEROS_MINIMAP = listOf(
        unmapped("minimap-overlay", "Minimap overlay", FabricFunctionalityArea.RENDERING, "No source-native terrain minimap renderer or HUD overlay exists."),
        unmapped("terrain-sampling", "Minimap terrain sampling", FabricFunctionalityArea.WORLD, "Client AOI debug sampling is bounded diagnostics, not Xaero terrain caching or coloring."),
        unmapped("waypoints", "Waypoints", FabricFunctionalityArea.GAMEPLAY, "Waypoint storage, world markers, sharing, and deathpoints are not implemented."),
        unmapped("entity-radar", "Entity radar", FabricFunctionalityArea.RENDERING, "Player, mob, item, and icon radar rendering is not implemented."),
        unmapped("cave-map", "Cave map", FabricFunctionalityArea.WORLD, "Roof detection and underground map layers are not implemented."),
        partial("minimap-settings", "Minimap settings", FabricFunctionalityArea.OPTIONS, "The host now provides owned configurable key bindings, but Xaero's binary registrations, profiles, and Mod Menu screen cannot link.", "FabricKeyBindings"),
        unmapped("server-integration", "Server integration", FabricFunctionalityArea.SERVER, "Xaero server entrypoints, restrictions, world IDs, and permission integrations are not mapped."),
    )

    val XAEROS_WORLD_MAP = listOf(
        unmapped("world-map-screen", "World map screen", FabricFunctionalityArea.RENDERING, "No source-native fullscreen explored-world map screen exists."),
        unmapped("map-tile-cache", "Map tile cache", FabricFunctionalityArea.WORLD, "Explored chunk rasterization, region tiles, and persistent cache formats are not implemented."),
        unmapped("map-waypoints", "Map waypoints", FabricFunctionalityArea.GAMEPLAY, "World-map waypoint display and editing are not implemented."),
        partial("map-settings", "World map settings", FabricFunctionalityArea.OPTIONS, "The host now provides owned configurable key bindings, but Xaero's binary registrations and Mod Menu screen cannot link.", "FabricKeyBindings"),
        unmapped("minimap-interop", "Xaero minimap interoperability", FabricFunctionalityArea.API, "Shared map textures and waypoint services between the two Xaero mods are not mapped."),
        unmapped("map-server-integration", "Server world identification", FabricFunctionalityArea.SERVER, "Xaero server entrypoints and world identity packets are not mapped."),
    )

    val INVENTORY_MANAGEMENT = listOf(
        partial("vanilla-slot-actions", "Vanilla slot actions", FabricFunctionalityArea.GAMEPLAY, "Minosoft supports click, split, quick-move, collect, drop, hotbar swap, and offhand swap actions, but the upstream mod does not link to those native actions.", "Container.execute / ItemElement"),
        mapped("container-screen-extensions", "Container-screen controls", FabricFunctionalityArea.OPTIONS, "Owned native controls attach to player and container screens and disappear with the adapter generation.", "FabricContainerScreenExtensions"),
        mapped("sort-buttons", "Sort buttons", FabricFunctionalityArea.OPTIONS, "Native player/container controls send the pinned upstream 1.5 boolean payload contract.", "InventoryManagementControlsElement"),
        mapped("bulk-transfer", "Transfer all", FabricFunctionalityArea.GAMEPLAY, "Put-all and take-all controls send the pinned upstream server payload contract.", "inventorymanagement:transfer_all_packet"),
        mapped("auto-stack", "Auto-stack", FabricFunctionalityArea.GAMEPLAY, "Stack-in and stack-out controls send the pinned upstream server payload contract.", "inventorymanagement:auto_stack_packet"),
        unmapped("slot-locking", "Slot locking", FabricFunctionalityArea.GAMEPLAY, "Locked-slot input policy, persistence, and visual indication are not implemented."),
        partial("hotbar-swapping", "Hotbar swapping", FabricFunctionalityArea.GAMEPLAY, "Hovered-slot swaps to keys 1-9 and F already use native container actions; whole-row hold-key plus scroll/1-3 behavior is not mapped.", "ItemElement.onKey"),
        unmapped("durability-alerts", "Durability alerts", FabricFunctionalityArea.OPTIONS, "Durability thresholds, HUD alerts, and sounds are not mapped."),
        unmapped("auto-replacement", "Automatic tool replacement", FabricFunctionalityArea.GAMEPLAY, "Automatic replacement requires an authoritative inventory transaction and server-side protocol that are not mapped."),
        unmapped("variant-grouping", "Variant grouping", FabricFunctionalityArea.GAMEPLAY, "The mod's item-variant grouping and ordering policy are not mapped."),
        partial("configuration-screen", "Configuration screen", FabricFunctionalityArea.OPTIONS, "The native controls are available, but RoundaLib configuration and Mod Menu screen factories do not link to Minosoft's UI.", "InventoryManagementControlsElement"),
        partial("server-protocol", "Multiplayer inventory protocol", FabricFunctionalityArea.SERVER, "The play utility installs the pinned upstream server handler for its owned Fabric server; general remote-server capability negotiation remains unmapped.", "Play.prepareFabricServer"),
    )

    fun known(metadata: FabricMetadata): List<FabricFunctionality> = when (metadata.id) {
        "entity_model_features" -> ENTITY_MODEL_FEATURES
        "entity_texture_features" -> ENTITY_TEXTURE_FEATURES
        "geckolib" -> GECKOLIB
        "naturalist" -> NATURALIST
        "iris" -> IRIS
        "jei" -> JEI
        "modmenu" -> MOD_MENU
        "xaerominimap" -> XAEROS_MINIMAP
        "xaeroworldmap" -> XAEROS_WORLD_MAP
        "inventorymanagement" -> INVENTORY_MANAGEMENT
        else -> emptyList()
    }

    val REBORN_CORE = listOf(
        mapped("energy-storage", "Team Reborn energy storage", FabricFunctionalityArea.API, "Bounded insert, extract, and simulation semantics are source-native.", "FabricEnergyCapabilities"),
        mapped("reborn-assets", "Reborn Core assets", FabricFunctionalityArea.CONTENT, "Artifact assets mount in the session asset stack.", "ExternalAssetProviders"),
        unmapped("reborn-config", "Reborn Core configuration", FabricFunctionalityArea.OPTIONS, "Upstream configuration screens and values are not mapped."),
        unmapped("reborn-machine-api", "Machine API", FabricFunctionalityArea.GAMEPLAY, "Reborn Core machine components do not execute."),
        unmapped("reborn-networking", "Networking", FabricFunctionalityArea.NETWORKING, "Reborn Core payloads are not mapped."),
    )

    val TECH_REBORN = listOf(
        mapped("content-catalog", "Content catalog", FabricFunctionalityArea.CONTENT, "Namespaced artifact resources are decoded and fingerprinted.", "FabricContentCatalogs"),
        mapped("world-content", "Blocks, items, and states", FabricFunctionalityArea.CONTENT, "Source-native registry snapshot installs decoded blocks, items, and palette states.", "FabricSessionContentBridge"),
        mapped("world-generation", "Ore generation", FabricFunctionalityArea.WORLD, "Decoded ore features drive deterministic local generation.", "TechRebornGenerator"),
        partial("content-rendering", "Content rendering", FabricFunctionalityArea.RENDERING, "Standard models and textures mount; custom/builtin models and dynamic machines remain incomplete.", "ExternalAssetProviders"),
        unmapped("recipes", "Recipes", FabricFunctionalityArea.GAMEPLAY, "Recipe resources are cataloged but not executed."),
        unmapped("machines", "Machines", FabricFunctionalityArea.GAMEPLAY, "Machine ticks, inventories, upgrades, and processing are not implemented."),
        unmapped("energy-network", "Energy network", FabricFunctionalityArea.GAMEPLAY, "Energy storage exists, but cable and machine networks do not."),
        partial("containers-and-menus", "Containers and menus", FabricFunctionalityArea.OPTIONS, "Reusable revisioned machine snapshots, custom native slots, tanks, energy/progress gauges, tabs, tooltips, and payload controls exist; Tech Reborn has no machine/network gameplay state to bind them to.", "SynchronizedMachineScreenState / GaugeElement / FabricMachineControl"),
        unmapped("custom-payloads", "Custom payloads", FabricFunctionalityArea.NETWORKING, "Tech Reborn packet channels and payload semantics are not mapped."),
        unmapped("persistent-state", "Persistent machine state", FabricFunctionalityArea.GAMEPLAY, "Machine and network state persistence is not implemented."),
        unmapped("modded-server", "Authoritative modded server", FabricFunctionalityArea.SERVER, "The vanilla test server cannot host Tech Reborn gameplay."),
        unmapped("content-reload", "Content generation reload", FabricFunctionalityArea.RELOAD, "Registry epochs and persistent-state migration are not implemented."),
    )
}
