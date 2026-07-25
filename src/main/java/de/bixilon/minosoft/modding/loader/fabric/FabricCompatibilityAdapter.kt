/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

interface FabricCompatibilityAdapter {
    val id: String
    val handledBlockers: Set<FabricCompatibilityBlocker>
    val capabilities: Set<FabricHostCapability>
    val functionality: List<FabricFunctionality> get() = capabilities.map {
        FabricFunctionality(
            id = it.wireName,
            title = it.wireName,
            area = FabricFunctionalityArea.API,
            status = FabricFunctionalityStatus.MAPPED,
            detail = "Adapter declares the ${it.wireName} host capability.",
            hostMapping = it.wireName,
        )
    }

    fun supports(metadata: FabricMetadata): Boolean
    fun activate(probe: FabricModProbe, scope: FabricRegistrationScope)
}

// Wire names are stable diagnostic contracts; adapter implementation class names are not.
enum class FabricHostCapability(val wireName: String) {
    CLIENT_EVENTS("client-events"),
    CLIENT_CONNECTION_EVENTS("client-connection-events"),
    CLIENT_COMMANDS("client-commands"),
    CLIENT_PAYLOAD_CHANNELS("client-payload-channels"),
    CLIENT_TICK_EVENTS("client-tick-events"),
    BLOCK_MUTATION_EVENTS("block-mutation-events"),
    CHUNK_EVENTS("chunk-events"),
    CHUNK_RENDER_SCHEDULING("chunk-render-scheduling"),
    CONTAINER_SCREEN_EXTENSIONS("container-screen-extensions"),
    CONTENT_CATALOG("content-catalog"),
    ENERGY_STORAGE("energy-storage"),
    ENTITY_TEXTURE_CONTENT("entity-texture-content"),
    ENTITY_VISIBILITY("entity-visibility"),
    ENTITY_EVENTS("entity-events"),
    FABRIC_API_MODULES("fabric-api-modules"),
    FRAME_BATCHING("frame-batching"),
    HUD_LAYERS("hud-layers"),
    INPUT_EVENTS("input-events"),
    KEY_BINDINGS("key-bindings"),
    PARTICLE_EVENTS("particle-events"),
    PLAYER_INTERACTIONS("player-interactions"),
    RECIPE_VIEWER("recipe-viewer"),
    RESOURCE_RELOAD_EVENTS("resource-reload-events"),
    SCREENS("screens"),
    SHADER_PIPELINE("shader-pipeline"),
    SKELETAL_CONTENT("skeletal-content"),
    SOUND_EVENTS("sound-events"),
    WORLD_CONTENT("world-content"),
    WORLD_EVENTS("world-events"),
    WORLD_GENERATION("world-generation"),
}

class FabricCompatibilityAdapterRegistry(initial: Iterable<FabricCompatibilityAdapter> = emptyList()) {
    private val adapters = linkedMapOf<String, FabricCompatibilityAdapter>()

    init {
        initial.forEach(::registerPermanent)
    }

    @Synchronized
    fun register(adapter: FabricCompatibilityAdapter): AutoCloseable {
        registerPermanent(adapter)
        return AutoCloseable {
            synchronized(this) {
                adapters.remove(adapter.id, adapter)
            }
        }
    }

    @Synchronized
    fun resolve(metadata: FabricMetadata): FabricCompatibilityAdapter? {
        val matches = adapters.values.filter { it.supports(metadata) }
        require(matches.size <= 1) {
            "Fabric artifact ${metadata.id} ${metadata.version} matches multiple adapters: ${matches.joinToString { it.id }}"
        }
        return matches.singleOrNull()
    }

    @Synchronized
    fun snapshot(): List<FabricCompatibilityAdapter> = adapters.values.toList()

    private fun registerPermanent(adapter: FabricCompatibilityAdapter) {
        require(adapter.id.isNotBlank()) { "Fabric adapter id must not be blank." }
        require(adapters.putIfAbsent(adapter.id, adapter) == null) { "Fabric adapter id is already registered: ${adapter.id}" }
    }
}

object FabricCompatibilityAdapters {
    private val registry = FabricCompatibilityAdapterRegistry(
        listOf(
            FabricApiCompatibilityAdapter,
            RebornCoreCompatibilityAdapter,
            TechRebornCompatibilityAdapter,
            SodiumCompatibilityAdapter,
            EntityCullingCompatibilityAdapter,
            ImmediatelyFastCompatibilityAdapter,
            InventoryManagementCompatibilityAdapter,
            IrisCompatibilityAdapter,
            JeiCompatibilityAdapter,
            EntityTextureFeaturesCompatibilityAdapter,
            EntityModelFeaturesCompatibilityAdapter,
            GeckoLibCompatibilityAdapter,
            NaturalistCompatibilityAdapter,
        ),
    )

    fun register(adapter: FabricCompatibilityAdapter): AutoCloseable = registry.register(adapter)

    fun resolve(metadata: FabricMetadata): FabricCompatibilityAdapter? = registry.resolve(metadata)

    fun snapshot(): List<FabricCompatibilityAdapter> = registry.snapshot()
}
