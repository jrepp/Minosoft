/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.local.generator

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.dimension.DimensionProperties
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.biome.source.DummyBiomeSource
import de.bixilon.minosoft.modding.loader.fabric.FabricOreFeature
import de.bixilon.minosoft.modding.loader.fabric.FabricSessionContentBridge
import de.bixilon.minosoft.modding.loader.fabric.FabricWorldContents
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

class TechRebornGenerator(
    private val session: PlaySession,
    private val seed: Long = System.getProperty("minosoft.worldSeed")?.toLongOrNull() ?: DEFAULT_SEED,
) : ChunkGenerator {
    override val dimension = DimensionProperties(minY = -64, height = 384)
    private val content = requireNotNull(FabricWorldContents.byNamespace(NAMESPACE)) {
        "Tech Reborn world content is not active. Launch with --fabric-pack containing techreborn."
    }
    private val sessionContent = requireNotNull(FabricSessionContentBridge.session(session)) {
        "Tech Reborn registry snapshot was not synchronized into this session."
    }.also { it.snapshot.validate(content) }
    private val plains = session.registries.biome[ResourceLocation("minecraft", "plains")]
    private val bedrock = vanilla("bedrock")
    private val deepslate = vanilla("deepslate")
    private val stone = vanilla("stone")
    private val dirt = vanilla("dirt")
    private val grass = vanilla("grass_block")
    private val logged = AtomicBoolean()

    override fun generate(builder: ChunkBuilder) {
        builder.biomes = DummyBiomeSource(plains)
        generateTerrain(builder)
        var generated = 0
        for (feature in content.ores) generated += generateFeature(builder, feature)
        if (logged.compareAndSet(false, true)) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "TECH_REBORN_WORLD_GENERATED seed=$seed chunk=${builder.position.x},${builder.position.z} ores=$generated features=${content.ores.size} fingerprint=${content.fingerprint}"
            }
        }
    }

    private fun generateTerrain(builder: ChunkBuilder) {
        for (x in 0..15) for (z in 0..15) {
            builder[x, dimension.minY, z] = bedrock
            for (y in dimension.minY + 1 until 0) builder[x, y, z] = deepslate
            for (y in 0 until SURFACE_Y - 3) builder[x, y, z] = stone
            for (y in SURFACE_Y - 3 until SURFACE_Y) builder[x, y, z] = dirt
            builder[x, SURFACE_Y, z] = grass
        }
    }

    private fun generateFeature(builder: ChunkBuilder, feature: FabricOreFeature): Int {
        if (feature.targets.none { target ->
                target.tag?.contains("stone_ore_replaceables") == true ||
                    target.tag?.contains("deepslate_ore_replaceables") == true
            }) return 0
        val random = Random(mixSeed(builder.position.x, builder.position.z, feature.id.hashCode()))
        val min = maxOf(dimension.minY + 1, feature.minHeight.resolve(dimension.minY, dimension.maxY))
        val max = minOf(SURFACE_Y - 1, feature.maxHeight.resolve(dimension.minY, dimension.maxY))
        if (max < min) return 0
        var generated = 0
        repeat(feature.count) {
            var x = random.nextInt(16)
            var y = min + random.nextInt(max - min + 1)
            var z = random.nextInt(16)
            repeat(feature.size) vein@{
                val target = feature.targets.firstOrNull { target ->
                    if (y < 0) target.tag?.contains("deepslate_ore_replaceables") == true
                    else target.tag?.contains("stone_ore_replaceables") == true
                } ?: return@vein
                val replaceable = if (y < 0) deepslate else stone
                if (builder[x, y, z] != replaceable) return@vein
                val state = sessionContent.blocks[target.state]?.states?.default ?: return@vein
                builder[x, y, z] = state
                generated++
                x = (x + random.nextInt(3) - 1).coerceIn(0, 15)
                y = (y + random.nextInt(3) - 1).coerceIn(min, max)
                z = (z + random.nextInt(3) - 1).coerceIn(0, 15)
            }
        }
        return generated
    }

    private fun vanilla(path: String): BlockState = requireNotNull(session.registries.block[ResourceLocation("minecraft", path)]?.states?.default) {
        "Minecraft block is unavailable for Tech Reborn terrain: $path"
    }

    private fun mixSeed(chunkX: Int, chunkZ: Int, salt: Int): Long {
        var value = seed xor (chunkX.toLong() * 341873128712L) xor (chunkZ.toLong() * 132897987541L) xor salt.toLong()
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    companion object {
        const val NAMESPACE = "techreborn"
        const val DEFAULT_SEED = 0x544543485245424FL
        const val SURFACE_Y = 64
    }
}
