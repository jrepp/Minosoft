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
        mapped("chunk-render-scheduling", "Chunk render scheduling", FabricFunctionalityArea.PERFORMANCE, "Owned Sodium hook participates in Minosoft chunk preparation.", "SodiumRendererHook"),
        mapped("brightness", "Brightness", FabricFunctionalityArea.OPTIONS, "Sodium brightness maps to the live Minosoft gamma profile.", "rendering.light.gamma", FabricHostMenu.LIGHTING),
        mapped("smooth-lighting", "Smooth lighting", FabricFunctionalityArea.OPTIONS, "Sodium smooth lighting maps to Minosoft ambient occlusion.", "rendering.light.ambientOcclusion", FabricHostMenu.LIGHTING),
        partial("video-settings-screen", "Video settings screen", FabricFunctionalityArea.OPTIONS, "Minosoft exposes native settings screens, but has not reproduced Sodium's page/control framework.", "Pause > Lighting / Mod settings", FabricHostMenu.LIGHTING),
        partial("view-distance", "View distance", FabricFunctionalityArea.OPTIONS, "A Minosoft profile field exists; the Sodium menu control is not mapped yet.", "block.viewDistance"),
        partial("gui-scale", "GUI scale", FabricFunctionalityArea.OPTIONS, "A Minosoft profile field exists; live Sodium control semantics are not mapped yet.", "rendering.quality.resolution.guiScale"),
        partial("fullscreen", "Fullscreen", FabricFunctionalityArea.OPTIONS, "Minosoft can toggle fullscreen, but the setting is not exposed through the mapped mod menu.", "window.fullscreen"),
        partial("v-sync", "VSync", FabricFunctionalityArea.OPTIONS, "Minosoft owns swap interval configuration; the Sodium control is not mapped yet.", "rendering.advanced.swapInterval"),
        partial("cloud-quality", "Cloud quality", FabricFunctionalityArea.OPTIONS, "Minosoft has flat/layered cloud controls with different semantics.", "rendering.sky.clouds"),
        partial("biome-blend", "Biome blend", FabricFunctionalityArea.OPTIONS, "Minosoft exposes radius and algorithm rather than Sodium's single distance control.", "rendering.biome.blending"),
        partial("mipmap-levels", "Mipmap levels", FabricFunctionalityArea.OPTIONS, "The profile is mapped, but texture arrays require a client generation restart.", "rendering.textures.mipmaps", restart = true),
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

    val ENTITY_CULLING = listOf(
        mapped("entity-visibility", "Entity visibility", FabricFunctionalityArea.PERFORMANCE, "Owned hook crosses every native entity visibility decision.", "FabricEntityVisibilityHooks"),
        partial("config-screen", "Configuration screen", FabricFunctionalityArea.OPTIONS, "The host catalog is visible in game, but Entity Culling controls are read-only.", "Pause > Mod settings"),
        unmapped("block-entity-culling", "Block entity culling", FabricFunctionalityArea.PERFORMANCE, "No block-entity visibility adapter exists."),
        unmapped("tick-culling", "Tick culling", FabricFunctionalityArea.PERFORMANCE, "Client entity ticking is not suppressed by visibility."),
        unmapped("nametag-culling", "Nametag culling", FabricFunctionalityArea.RENDERING, "Nametag-through-wall policy is not mapped."),
        unmapped("entity-whitelist", "Entity whitelist", FabricFunctionalityArea.OPTIONS, "Per-type entity exclusions are not mapped."),
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
        unmapped("runtime-config", "Runtime configuration", FabricFunctionalityArea.OPTIONS, "ImmediatelyFast runtime flags and config access API are not mapped."),
        unmapped("conflict-handling", "Mod and hardware conflict handling", FabricFunctionalityArea.DIAGNOSTICS, "ImmediatelyFast compatibility guards are not mapped."),
    )

    val FABRIC_API = listOf(
        partial("fabric-api-modules", "Fabric API module inventory", FabricFunctionalityArea.API, "Fifty nested module identities are resolved and exposed, but their binary APIs do not link.", "FabricApiModuleRegistry"),
        mapped("client-events", "Client event bridge", FabricFunctionalityArea.API, "An owned render-thread bridge dispatches deterministic lifecycle, world-render, and HUD phases with isolated callbacks.", "FabricClientEvents"),
        mapped("client-connection-events", "Client connection event bridge", FabricFunctionalityArea.NETWORKING, "Owned created, state-change, joined, and disconnected phases follow each Minosoft play session.", "FabricClientConnectionEvents"),
        mapped("client-commands", "Client commands", FabricFunctionalityArea.GAMEPLAY, "Owned local commands intercept exact names before server dispatch and receive an explicit session plus raw arguments.", "FabricClientCommands"),
        mapped("client-payload-channels", "Client payload channels", FabricFunctionalityArea.NETWORKING, "Bounded owner-scoped receivers observe immutable payload views and send through an explicit play session.", "FabricClientPayloadChannels"),
        mapped("client-tick-events", "Client tick event bridge", FabricFunctionalityArea.API, "Owned start/end phases bracket one ordered 20 Hz session tick cycle with per-task failure isolation.", "FabricClientTickEvents"),
        mapped("world-events", "World and dimension lifecycle", FabricFunctionalityArea.WORLD, "Owned before/after identity changes and playable join/leave phases cover remote and local sessions.", "FabricWorldEvents"),
        mapped("chunk-events", "Chunk lifecycle", FabricFunctionalityArea.WORLD, "Central create, update, unload, and bulk-clear events cover network and local chunk managers.", "FabricChunkEvents"),
        mapped("block-mutation-events", "Block mutation batches", FabricFunctionalityArea.WORLD, "Single and batched block changes publish previous/current states after the native mutation boundary.", "FabricBlockMutationEvents"),
        mapped("entity-events", "Entity lifecycle", FabricFunctionalityArea.WORLD, "Central add, remove, and bulk-clear events cover local and remote world entity stores.", "FabricEntityEvents"),
        mapped("particle-events", "Accepted particle events", FabricFunctionalityArea.RENDERING, "Particles crossing native enablement and view-distance policy are observed before entering the render queue.", "FabricParticleEvents"),
        mapped("sound-events", "Sound request events", FabricFunctionalityArea.API, "2D, world, packet, and local sound requests are observed at the native audio-player boundary before queued resolution.", "FabricSoundEvents"),
        mapped("player-interactions", "Player interaction decisions", FabricFunctionalityArea.GAMEPLAY, "Ordered owner hooks can pass or deny attack-block, attack-entity, use-block, use-entity, and use-item actions before native handling.", "FabricPlayerInteractionHooks"),
        mapped("screens", "Owned screen factories", FabricFunctionalityArea.OPTIONS, "Namespaced screen factories open on the render queue and disappear with their owner scope.", "FabricScreens"),
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
        unmapped("shader-pack-loader", "Shader pack loader", FabricFunctionalityArea.RENDERING, "OptiFine-format shader pack discovery and compilation are not implemented."),
        partial("shader-pipeline", "Shader rendering pipeline", FabricFunctionalityArea.RENDERING, "The owned Iris adapter installs a visible full-screen world presentation shader at Minosoft's framebuffer boundary. It does not compile upstream Iris terrain, entity, sky, weather, or OptiFine shader-pack programs.", "WorldFramebuffer.postProcessors / FabricClientEvents.BEFORE_WORLD_RENDER"),
        unmapped("shadow-maps", "Shader shadow maps", FabricFunctionalityArea.RENDERING, "No Iris-compatible shadow framebuffer or render pass exists."),
        unmapped("shader-settings", "Shader settings screen", FabricFunctionalityArea.OPTIONS, "Iris's option model and Mod Menu entrypoint are not mapped."),
        unmapped("sodium-shader-interop", "Sodium shader integration", FabricFunctionalityArea.RENDERING, "The source-native Sodium scheduling hook does not implement Iris/Sodium binary renderer integration."),
        partial("resource-reload-events", "Live shader reload", FabricFunctionalityArea.RELOAD, "The owned Iris presentation shader participates in host shader reloads and reports completion; shader-pack generations and failure-preserving replacement remain incomplete.", "FabricResourceReloadEvents / ShaderManagement.reload"),
    )

    val ENTITY_MODEL_FEATURES = listOf(
        mapped("skeletal-content", "CEM JEM/JPM geometry ingestion", FabricFunctionalityArea.CONTENT, "The exact adapter owns a headless parser for JEM models, external JPM parts, boxes, per-face UVs, transforms, inflation, attachments, and expression bindings.", "CemParser / SkeletalContentParsers"),
        partial("entity-part-aliases", "Version-aware entity part aliases", FabricFunctionalityArea.CONTENT, "The owned alias registry supports version and entity-specific mappings with deterministic removal; the complete vanilla entity mapping catalog is not populated yet.", "SkeletalPartAliases"),
        partial("cem-expressions", "CEM expression animation", FabricFunctionalityArea.CONTENT, "Expression targets compile into a bounded, reflection-free VM with deterministic caller-owned random input; the complete entity/render variable catalog is not populated yet.", "SkeletalExpression"),
        partial("cem-render-binding", "Entity renderer binding", FabricFunctionalityArea.RENDERING, "Neutral geometry binds to retained skeletal transforms, direct textures, UVs, and matching animal/humanoid entity IDs; complete aliases, attachments, feature layers, and visual parity remain.", "SkeletalModelBinder / SkeletalLoader"),
        partial("cem-reload", "CEM generation reload", FabricFunctionalityArea.RELOAD, "CPU asset/content candidates are all-or-nothing and old generations remain leased through model lifetime; live render-thread re-bake/swap and repeated GPU disposal proof remain.", "ContentGenerationStore"),
        unmapped("emf-config", "EMF configuration", FabricFunctionalityArea.OPTIONS, "EMF configuration and Mod Menu screens are not mapped."),
    )

    val ENTITY_TEXTURE_FEATURES = listOf(
        mapped("entity-texture-content", "Random entity texture rules", FabricFunctionalityArea.CONTENT, "The exact adapter owns a headless OptiFine-style properties parser, deterministic weighted selection, extensible context predicates, and generation-owned selection cache.", "EntityTextureRuleParsers / EntityTextureSelectionCache"),
        partial("entity-texture-context", "Entity texture context", FabricFunctionalityArea.CONTENT, "Rules support string, wildcard, regular-expression, boolean, numeric, range, and custom predicates; the full ETF entity/NBT context catalog is not bound yet.", "EntityTextureContext"),
        partial("entity-texture-variants", "Entity texture variants", FabricFunctionalityArea.RENDERING, "Deterministic stable variant selection is implemented independently of rendering; resource discovery and renderer material binding remain incomplete.", "EntityTextureRuleSet"),
        partial("entity-texture-materials", "Emissive and blinking materials", FabricFunctionalityArea.RENDERING, "Deterministic base, emissive, blink, and blink-emissive frame selection is represented; the explicit emissive GPU pass and render-state restoration are not implemented.", "EntityTextureMaterial"),
        partial("entity-texture-reload", "Entity texture generation reload", FabricFunctionalityArea.RELOAD, "CPU rule candidates are all-or-nothing and adapter caches are generation-owned; live renderer material swap and repeated GPU disposal proof remain.", "ContentGenerationStore / FabricRegistrationScope"),
        unmapped("etf-config", "ETF configuration", FabricFunctionalityArea.OPTIONS, "ETF configuration and Mod Menu screens are not mapped."),
        unmapped("etf-player-skin-api", "ETF player skin API", FabricFunctionalityArea.API, "ETF's binary player skin and texture APIs do not link to native Minosoft services."),
    )

    val GECKOLIB = listOf(
        mapped("skeletal-content", "Geo JSON ingestion", FabricFunctionalityArea.CONTENT, "The exact adapter owns a headless parser for geometry documents, bone hierarchies, cubes, pivots, rotations, inflation, mirroring, box UVs, and per-face UVs.", "GeckoLibParser / SkeletalContentParsers"),
        partial("animation-json", "Animation JSON ingestion", FabricFunctionalityArea.CONTENT, "Numeric and expression vectors, loop/hold/once timing, keyed channels, linear/step/Catmull-Rom interpolation, and a bounded easing subset are parsed and evaluated deterministically; the full easing catalog and event keyframes remain.", "GeckoLibParser / SkeletalAnimationEvaluator"),
        partial("animation-controllers", "Animation controllers", FabricFunctionalityArea.API, "A native named-clip controller supports playback, restart, transitions, and pose blending, but GeckoLib animatable/cache binary APIs and mod-authored controller predicates do not link.", "SkeletalAnimationController / NeutralAnimationManager"),
        partial("gecko-rendering", "Geo model rendering", FabricFunctionalityArea.RENDERING, "Matching geometry and animation documents cross the retained-skeletal bridge and evaluated poses can update bone transforms; model selection, materials/render layers, automatic controller routing, and entity/block/item/armor/object coverage remain.", "ContentFidelityLoader / SkeletalModelBinder / NeutralAnimationManager"),
        unmapped("gecko-events", "Sound, particle, and custom keyframes", FabricFunctionalityArea.GAMEPLAY, "Animation event keyframes and listener dispatch are not implemented."),
        partial("gecko-reload", "GeckoLib generation reload", FabricFunctionalityArea.RELOAD, "CPU geometry/animation candidates are all-or-nothing, matching clips attach before publication, and old generations remain leased through model lifetime; controller migration, live render swap, and repeated GPU disposal proof remain.", "ContentFidelityLoader / ContentGenerationStore"),
        unmapped("gecko-binary-api", "Dependent-mod binary API", FabricFunctionalityArea.API, "Classes compiled against GeckoLib and Mojang types do not link to the native data adapter."),
    )

    val JEI = listOf(
        unmapped("ingredient-overlay", "Item and ingredient overlay", FabricFunctionalityArea.RENDERING, "JEI's paged item and ingredient list overlay and cheat-mode rendering are not implemented."),
        mapped("recipe-viewer", "Recipe viewer", FabricFunctionalityArea.GAMEPLAY, "An owned container control opens a paged view of the play session's synchronized recipe registry.", "JeiRecipeMenu"),
        unmapped("recipe-transfer", "Recipe transfer", FabricFunctionalityArea.GAMEPLAY, "Crafting and inventory recipe transfer slot routing and packets are not mapped."),
        unmapped("bookmarks", "Bookmark ingredients", FabricFunctionalityArea.GAMEPLAY, "Sticky ingredient bookmarks and their overlay actions are not implemented."),
        unmapped("search", "Search and filtering", FabricFunctionalityArea.OPTIONS, "JEI's search syntax, filters, and result routing are not mapped."),
        unmapped("ingredient-sync", "Ingredient sync", FabricFunctionalityArea.NETWORKING, "Server-to-client ingredient and recipe sync channels do not link."),
        unmapped("recipe-plugin-api", "Recipe plugin API", FabricFunctionalityArea.API, "JEI's runtime plugin registration and recipe category API do not link."),
        mapped("container-screen-extensions", "Inventory screen integration", FabricFunctionalityArea.RENDERING, "An owned native extension attaches the Recipes control to current container screens without applying Mojang mixins.", "FabricContainerScreenExtensions"),
        unmapped("jei-config", "Configuration", FabricFunctionalityArea.OPTIONS, "JEI configuration screens and Mod Menu entrypoint are not mapped."),
        unmapped("jei-dev-tools", "Ingredient debug tools", FabricFunctionalityArea.DIAGNOSTICS, "JEI's ingredient tree and runtime debugging views are not mapped."),
    )

    val MOD_MENU = listOf(
        partial("installed-mod-list", "Installed mod list", FabricFunctionalityArea.OPTIONS, "Minosoft's Mod settings screen lists every staged top-level Fabric artifact and its mapping counts.", "FabricModSettingsMenu"),
        partial("config-navigation", "Configuration navigation", FabricFunctionalityArea.OPTIONS, "Source-native host menus can be opened from mapped catalog entries, but upstream ModMenuApi screen factories cannot link.", "FabricModFunctionalityMenu"),
        unmapped("modmenu-entrypoints", "Mod Menu API entrypoints", FabricFunctionalityArea.API, "ModMenuApi binary entrypoints and provided screen factories are not implemented."),
        unmapped("search-and-filters", "Search and filters", FabricFunctionalityArea.OPTIONS, "The host mod catalog has no Mod Menu-compatible search, filters, or library grouping."),
        unmapped("metadata-badges", "Metadata badges and hierarchy", FabricFunctionalityArea.OPTIONS, "Mod Menu custom badges, parent/child grouping, icons, and descriptions are not decoded."),
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
        unmapped("containers-and-menus", "Containers and menus", FabricFunctionalityArea.OPTIONS, "Tech Reborn machine GUIs and synchronized containers are not mapped."),
        unmapped("custom-payloads", "Custom payloads", FabricFunctionalityArea.NETWORKING, "Tech Reborn packet channels and payload semantics are not mapped."),
        unmapped("persistent-state", "Persistent machine state", FabricFunctionalityArea.GAMEPLAY, "Machine and network state persistence is not implemented."),
        unmapped("modded-server", "Authoritative modded server", FabricFunctionalityArea.SERVER, "The vanilla test server cannot host Tech Reborn gameplay."),
        unmapped("content-reload", "Content generation reload", FabricFunctionalityArea.RELOAD, "Registry epochs and persistent-state migration are not implemented."),
    )
}
