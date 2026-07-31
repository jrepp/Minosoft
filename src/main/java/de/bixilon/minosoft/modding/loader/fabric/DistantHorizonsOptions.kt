/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.SettingsCategory
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.config.settings.SteppedConfigControl
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderConfig

/**
 * Source-native settings for the exact DH compatibility surface.
 *
 * Direct controller tests use the in-memory defaults. Adapter activation uses
 * [persisted], which stores values beneath the active trajectory profile.
 */
internal class DistantHorizonsOptions private constructor(
    private val store: FabricAdapterOptionStore?,
) : DistantTerrainRenderConfig {
    @Volatile var enabled = boolean(ENABLED, true)
        private set
    @Volatile override var renderDistanceChunks = integer(RENDER_DISTANCE, 128, RENDER_DISTANCES)
        private set
    @Volatile var persistenceEnabled = boolean(PERSISTENCE, true)
        private set
    @Volatile override var maximumTiles = integer(MAXIMUM_TILES, 16_384, TILE_LIMITS)
        private set
    @Volatile var unexploredGenerationEnabled = boolean(UNEXPLORED_GENERATION, true)
        private set
    @Volatile var generationRadiusChunks = integer(GENERATION_RADIUS, 96, GENERATION_RADII)
        private set
    @Volatile var generationBudgetPerTick = integer(GENERATION_BUDGET, 1, GENERATION_BUDGETS)
        private set
    @Volatile var networkTransferEnabled = boolean(NETWORK_TRANSFER, true)
        private set
    @Volatile var networkRadiusChunks = integer(NETWORK_RADIUS, 128, NETWORK_RADII)
        private set
    @Volatile var networkRequestTiles = integer(NETWORK_REQUEST_TILES, 16, NETWORK_REQUEST_SIZES)
        private set

    fun schema(): SettingsSchema {
        val rendering = SettingsCategory("rendering", "Rendering")
        val generation = SettingsCategory("generation", "World generation")
        val storage = SettingsCategory("storage", "Storage")
        val network = SettingsCategory("network", "Multiplayer")
        return SettingsSchema(
            title = "Distant Horizons settings",
            categories = listOf(rendering, generation, storage, network),
            entries = listOf(
                ConfigEntry(
                    id = ENABLED,
                    label = "Enable distant terrain",
                    description = "Publishes explored and generated LOD tiles through the DH terrain, water, and shadow routes.",
                    defaultValue = true,
                    control = BooleanConfigControl,
                    read = { enabled },
                    write = { enabled = it; store?.set(ENABLED, it) },
                    category = rendering.id,
                ),
                ConfigEntry(
                    id = RENDER_DISTANCE,
                    label = "LOD render distance",
                    description = "Maximum distant-terrain radius in chunks.",
                    defaultValue = 128,
                    control = SteppedConfigControl(RENDER_DISTANCES) { "$it chunks" },
                    read = { renderDistanceChunks },
                    write = { renderDistanceChunks = it; store?.set(RENDER_DISTANCE, it) },
                    category = rendering.id,
                ),
                ConfigEntry(
                    id = UNEXPLORED_GENERATION,
                    label = "Generate unexplored local terrain",
                    description = "Uses the authoritative local-world generator to build detached LOD tiles outside native view distance.",
                    defaultValue = true,
                    control = BooleanConfigControl,
                    read = { unexploredGenerationEnabled },
                    write = { unexploredGenerationEnabled = it; store?.set(UNEXPLORED_GENERATION, it) },
                    category = generation.id,
                ),
                ConfigEntry(
                    id = GENERATION_RADIUS,
                    label = "Generation radius",
                    description = "Maximum local unexplored-generation radius in chunks.",
                    defaultValue = 96,
                    control = SteppedConfigControl(GENERATION_RADII) { "$it chunks" },
                    read = { generationRadiusChunks },
                    write = { generationRadiusChunks = it; store?.set(GENERATION_RADIUS, it) },
                    category = generation.id,
                ),
                ConfigEntry(
                    id = GENERATION_BUDGET,
                    label = "Generation budget",
                    description = "Detached LOD chunks generated per client tick.",
                    defaultValue = 1,
                    control = SteppedConfigControl(GENERATION_BUDGETS) { "$it per tick" },
                    read = { generationBudgetPerTick },
                    write = { generationBudgetPerTick = it; store?.set(GENERATION_BUDGET, it) },
                    category = generation.id,
                ),
                ConfigEntry(
                    id = PERSISTENCE,
                    label = "Persistent LOD database",
                    description = "Atomically saves detached LOD tiles per server and dimension for later sessions.",
                    defaultValue = true,
                    control = BooleanConfigControl,
                    read = { persistenceEnabled },
                    write = { persistenceEnabled = it; store?.set(PERSISTENCE, it) },
                    category = storage.id,
                ),
                ConfigEntry(
                    id = MAXIMUM_TILES,
                    label = "LOD database capacity",
                    description = "Maximum retained 16×16 tiles per active world. Changing this takes effect on the next world entry.",
                    defaultValue = 16_384,
                    control = SteppedConfigControl(TILE_LIMITS) { "$it tiles" },
                    read = { maximumTiles },
                    write = { maximumTiles = it; store?.set(MAXIMUM_TILES, it) },
                    restartRequired = true,
                    category = storage.id,
                ),
                ConfigEntry(
                    id = NETWORK_TRANSFER,
                    label = "Server LOD transfer",
                    description = "Accepts bounded source-native LOD tiles from servers that advertise the Minosoft DH channel.",
                    defaultValue = true,
                    control = BooleanConfigControl,
                    read = { networkTransferEnabled },
                    write = { networkTransferEnabled = it; store?.set(NETWORK_TRANSFER, it) },
                    category = network.id,
                ),
                ConfigEntry(
                    id = NETWORK_RADIUS,
                    label = "Server request radius",
                    description = "Maximum remote LOD request radius in chunks.",
                    defaultValue = 128,
                    control = SteppedConfigControl(NETWORK_RADII) { "$it chunks" },
                    read = { networkRadiusChunks },
                    write = { networkRadiusChunks = it; store?.set(NETWORK_RADIUS, it) },
                    category = network.id,
                ),
                ConfigEntry(
                    id = NETWORK_REQUEST_TILES,
                    label = "Tiles per request",
                    description = "Maximum missing tiles requested in one bounded network message.",
                    defaultValue = 16,
                    control = SteppedConfigControl(NETWORK_REQUEST_SIZES) { "$it tiles" },
                    read = { networkRequestTiles },
                    write = { networkRequestTiles = it; store?.set(NETWORK_REQUEST_TILES, it) },
                    category = network.id,
                ),
            ),
            persist = { store?.persist() },
        )
    }

    private fun boolean(key: String, default: Boolean): Boolean =
        store?.boolean(key) ?: default

    private fun integer(key: String, default: Int, allowed: List<Int>): Int =
        store?.integer(key)?.takeIf { it in allowed } ?: default

    companion object {
        private const val ENABLED = "enabled"
        private const val RENDER_DISTANCE = "render_distance_chunks"
        private const val PERSISTENCE = "persistence"
        private const val MAXIMUM_TILES = "maximum_tiles"
        private const val UNEXPLORED_GENERATION = "unexplored_generation"
        private const val GENERATION_RADIUS = "generation_radius_chunks"
        private const val GENERATION_BUDGET = "generation_budget_per_tick"
        private const val NETWORK_TRANSFER = "network_transfer"
        private const val NETWORK_RADIUS = "network_radius_chunks"
        private const val NETWORK_REQUEST_TILES = "network_request_tiles"

        private val RENDER_DISTANCES = listOf(32, 64, 96, 128, 192, 256)
        private val TILE_LIMITS = listOf(4_096, 8_192, 16_384, 32_768, 65_536)
        private val GENERATION_RADII = listOf(32, 64, 96, 128, 192, 256)
        private val GENERATION_BUDGETS = listOf(1, 2, 4, 8)
        private val NETWORK_RADII = listOf(32, 64, 96, 128, 192, 256)
        private val NETWORK_REQUEST_SIZES = listOf(4, 8, 16, 32)
        private val DEFAULTS = mapOf(
            ENABLED to "true",
            RENDER_DISTANCE to "128",
            PERSISTENCE to "true",
            MAXIMUM_TILES to "16384",
            UNEXPLORED_GENERATION to "true",
            GENERATION_RADIUS to "96",
            GENERATION_BUDGET to "1",
            NETWORK_TRANSFER to "true",
            NETWORK_RADIUS to "128",
            NETWORK_REQUEST_TILES to "16",
        )

        fun inMemory(): DistantHorizonsOptions = DistantHorizonsOptions(null)

        fun persisted(): DistantHorizonsOptions =
            DistantHorizonsOptions(FabricAdapterOptionStore("distant_horizons", DEFAULTS))
    }
}
