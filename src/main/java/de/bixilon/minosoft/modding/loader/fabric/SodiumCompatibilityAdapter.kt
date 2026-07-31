/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.CycleConfigControl
import de.bixilon.minosoft.config.settings.SettingsCategory
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.config.settings.SteppedConfigControl
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.world.World
import de.bixilon.minosoft.debug.ClientDebugChannel
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.terrain.BuiltInTerrainVertexLayout
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.near.provider.TerrainProviderSelection
import de.bixilon.minosoft.gui.rendering.tint.sampler.SamplingAlgorithms

object SodiumCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:sodium-0.5.8-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.CHUNK_RENDER_SCHEDULING)
    override val functionality = FabricFunctionalityCatalog.SODIUM

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "sodium" &&
            metadata.version == "0.5.8+mc1.20.4" &&
            metadata.environment == "client" &&
            metadata.entrypoints.containsAll(setOf("client", "preLaunch")) &&
            metadata.mixins > 0 &&
            metadata.accessWidener != null &&
            metadata.nestedJars == 5
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Sodium artifact: ${probe.metadata.version}" }
        val terrain = TerrainProviderSelection(TERRAIN_DESCRIPTOR)
        scope.own(terrain)
        scope.own(FabricRendererRegistry.register(id, SodiumRendererHookBuilder(terrain)))
        scope.own(ClientDebugChannel.register(SodiumDebugProvider(terrain)))
        scope.own(
            FabricSettings.register(id, minosoft("sodium_options"), "Sodium video settings") { renderer ->
                settings(renderer)
            },
        )
    }

    private fun settings(renderer: de.bixilon.minosoft.gui.rendering.gui.GUIRenderer): SettingsSchema {
        val profiles = renderer.session.profiles
        val rendering = profiles.rendering
        val clouds = rendering.sky.clouds
        val blending = rendering.biome.blending
        val categories = listOf(
            SettingsCategory.GENERAL,
            SettingsCategory("quality", "Quality"),
            SettingsCategory("advanced", "Advanced"),
        )
        val biomeBlend = ConfigEntry(
            id = "biome_blend",
            label = "Biome blend radius",
            defaultValue = 5,
            control = SteppedConfigControl((0..15).toList()),
            read = { blending.radius },
            write = {
                blending.radius = it
                blending.enabled = it > 0
            },
            category = "quality",
        )
        val biomeAlgorithm = ConfigEntry(
            id = "biome_algorithm",
            label = "Biome blend algorithm",
            defaultValue = SamplingAlgorithms.GAUSSIAN,
            control = CycleConfigControl(SamplingAlgorithms.entries) { it.name.lowercase().replace('_', ' ') },
            read = { blending.algorithm },
            write = { blending.algorithm = it },
            enabledWhen = { values -> values[biomeBlend] > 0 },
            category = "quality",
        )
        return SettingsSchema(
            title = "Sodium video settings",
            entries = listOf(
                ConfigEntry(
                    id = "view_distance",
                    label = "View distance",
                    description = "Maximum client terrain distance in chunks.",
                    defaultValue = 10,
                    control = SteppedConfigControl((2..World.MAX_VIEW_DISTANCE).toList()) { "$it chunks" },
                    read = { profiles.block.viewDistance.coerceIn(2, World.MAX_VIEW_DISTANCE) },
                    write = { profiles.block.viewDistance = it },
                ),
                ConfigEntry(
                    id = "gui_scale",
                    label = "GUI scale",
                    description = "Scales source-native GUI rendering independently of the window size.",
                    defaultValue = 1.0f,
                    control = SteppedConfigControl((2..16).map { it / 4.0f }) { "${it}x" },
                    read = { rendering.quality.resolution.guiScale },
                    write = { rendering.quality.resolution.guiScale = it },
                ),
                ConfigEntry(
                    id = "fullscreen",
                    label = "Fullscreen",
                    defaultValue = false,
                    control = BooleanConfigControl,
                    read = { renderer.context.window.fullscreen },
                    write = { renderer.context.window.fullscreen = it },
                ),
                ConfigEntry(
                    id = "v_sync",
                    label = "Vertical sync",
                    description = "Uses swap interval 1 when enabled and 0 when disabled.",
                    defaultValue = true,
                    control = BooleanConfigControl,
                    read = { rendering.advanced.swapInterval > 0 },
                    write = { rendering.advanced.swapInterval = if (it) 1 else 0 },
                ),
                ConfigEntry(
                    id = "smooth_lighting",
                    label = "Smooth lighting",
                    defaultValue = true,
                    control = BooleanConfigControl,
                    read = { rendering.light.ambientOcclusion },
                    write = { rendering.light.ambientOcclusion = it },
                    category = "quality",
                ),
                ConfigEntry(
                    id = "cloud_quality",
                    label = "Cloud quality",
                    defaultValue = CloudQuality.FANCY,
                    control = CycleConfigControl(CloudQuality.entries) { it.label },
                    read = {
                        when {
                            !clouds.enabled -> CloudQuality.OFF
                            clouds.flat -> CloudQuality.FAST
                            else -> CloudQuality.FANCY
                        }
                    },
                    write = {
                        clouds.enabled = it != CloudQuality.OFF
                        clouds.flat = it == CloudQuality.FAST
                    },
                    category = "quality",
                ),
                biomeBlend,
                biomeAlgorithm,
                ConfigEntry(
                    id = "mipmap_levels",
                    label = "Mipmap levels",
                    description = "Texture arrays are rebuilt on the next client generation.",
                    defaultValue = 4,
                    control = SteppedConfigControl((0..4).toList()),
                    read = { rendering.textures.mipmaps },
                    write = { rendering.textures.mipmaps = it },
                    restartRequired = true,
                    category = "advanced",
                ),
                ConfigEntry(
                    id = "defer_chunk_updates",
                    label = "Limit chunk transfer time",
                    defaultValue = true,
                    control = BooleanConfigControl,
                    read = { rendering.performance.limitChunkTransferTime },
                    write = { rendering.performance.limitChunkTransferTime = it },
                    category = "advanced",
                ),
            ),
            categories = categories,
        )
    }

    private enum class CloudQuality(val label: String) {
        OFF("Off"),
        FAST("Fast"),
        FANCY("Fancy"),
    }

    internal val TERRAIN_DESCRIPTOR = TerrainBackendDescriptor(
        owner = RenderOwnerId("minosoft:sodium-compatible-terrain"),
        implementation = "sodium-0.5.8-adapter-minosoft-core",
        materials = TerrainMaterialClass.entries.toSet(),
        vertexLayout = BuiltInTerrainVertexLayout.VALUE,
        supportsAuxiliaryViews = true,
    )
}
