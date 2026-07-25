/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.effect

import de.bixilon.kutil.math.simple.DoubleMath.floor
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.blocks.types.properties.shape.outline.OutlinedBlock
import de.bixilon.minosoft.data.registries.shapes.aabb.AABB
import de.bixilon.minosoft.data.registries.shapes.aabb.AABBList
import de.bixilon.minosoft.data.registries.shapes.shape.Shape
import de.bixilon.minosoft.data.world.World
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.BlockPosition
import kotlin.math.min

/**
 * CPU-side entity-shadow projection. The scan follows the vanilla 1.20.4
 * contract: candidate positions span the shadow radius horizontally and a
 * bounded opacity-derived band vertically, then project onto the full-cube
 * block immediately below each candidate.
 */
object EntityShadowProjector {

    fun project(
        world: World,
        centerX: Double,
        entityY: Double,
        centerZ: Double,
        radius: Float,
        opacity: Float,
    ): List<Quad> {
        if (radius <= 0.0f || opacity <= 0.0f) return emptyList()

        val horizontalRadius = min(radius, MAX_RADIUS).toDouble()
        val verticalReach = min(opacity / VANILLA_VERTICAL_OPACITY_STEP, radius)
            .coerceAtLeast(0.0f)
            .toDouble()
        val minX = (centerX - horizontalRadius).floor
        val maxX = (centerX + horizontalRadius).floor
        val minY = (entityY - verticalReach).floor
        val maxY = entityY.floor
        val minZ = (centerZ - horizontalRadius).floor
        val maxZ = (centerZ + horizontalRadius).floor
        val projected = ArrayList<Quad>()
        var surfaces = 0

        projection@ for (z in minZ..maxZ) {
            for (x in minX..maxX) {
                for (candidateY in minY..maxY) {
                    val lightPosition = BlockPosition(x, candidateY, z)
                    val blockPosition = BlockPosition(x, candidateY - 1, z)
                    val state = world[blockPosition] ?: continue
                    if (BlockStateFlags.FULL_COLLISION !in state.flags) continue
                    val block = state.block
                    if (block !is OutlinedBlock) continue
                    val shape = when {
                        BlockStateFlags.FULL_OUTLINE in state.flags -> Shape.FULL
                        else -> block.outlineShape
                            ?: block.getOutlineShape(state)
                            ?: block.getOutlineShape(world.session, blockPosition, state)
                            ?: world.getBlockEntity(blockPosition)?.let {
                                block.getOutlineShape(world.session, blockPosition, state, it)
                            }
                    } ?: continue
                    val bounds = shape.bounds() ?: continue
                    val light = world.getLight(lightPosition)
                    if (world.dimension.light || world.dimension.skyLight) {
                        if (maxOf(light.block, light.sky) <= MIN_LIGHT_LEVEL) continue
                    }
                    val brightness = if (world.dimension.light || world.dimension.skyLight) {
                        world.dimension.ambientLight[maxOf(light.block, light.sky)]
                    } else {
                        1.0f
                    }
                    val verticalOpacity = (
                        opacity - (entityY - candidateY) * VANILLA_VERTICAL_OPACITY_STEP
                    ).toFloat() * VANILLA_FINAL_OPACITY_SCALE * brightness
                    if (verticalOpacity <= 0.0f) continue

                    val worldMinX = x + bounds.min.x
                    val worldMaxX = x + bounds.max.x
                    val worldMinZ = z + bounds.min.z
                    val worldMaxZ = z + bounds.max.z
                    if (worldMinX >= worldMaxX || worldMinZ >= worldMaxZ) continue

                    surfaces++
                    if (surfaces > MAX_PROJECTED_SURFACES) break@projection
                    projected += Quad(
                        x0 = worldMinX,
                        x1 = worldMaxX,
                        y = candidateY + bounds.min.y,
                        z0 = worldMinZ,
                        z1 = worldMaxZ,
                        u0 = textureCoordinate(worldMinX, centerX, horizontalRadius),
                        u1 = textureCoordinate(worldMaxX, centerX, horizontalRadius),
                        v0 = textureCoordinate(worldMinZ, centerZ, horizontalRadius),
                        v1 = textureCoordinate(worldMaxZ, centerZ, horizontalRadius),
                        opacity = verticalOpacity.coerceAtMost(1.0f),
                    )
                }
            }
        }
        return projected
    }

    /**
     * Vanilla maps the entity-to-surface offset into the shadow texture with
     * an inverted axis and a two-radius span. The texture's clamp metadata
     * handles blocks whose bounds extend beyond the scanned radius.
     */
    private fun textureCoordinate(surface: Double, center: Double, radius: Double): Float {
        return ((center - surface) / (2.0 * radius) + 0.5).toFloat()
    }

    private fun Shape.bounds(): AABB? = when (this) {
        is AABB -> this
        is AABBList -> {
            if (aabbs.isEmpty()) return null
            var bounds = aabbs[0]
            for (index in 1 until aabbs.size) bounds += aabbs[index]
            bounds
        }
        else -> null
    }

    data class Quad(
        val x0: Double,
        val x1: Double,
        val y: Double,
        val z0: Double,
        val z1: Double,
        val u0: Float,
        val u1: Float,
        val v0: Float,
        val v1: Float,
        val opacity: Float,
    )

    private const val MAX_RADIUS = 32.0f
    private const val MAX_PROJECTED_SURFACES = 16_384
    private const val MIN_LIGHT_LEVEL = 3
    private const val VANILLA_VERTICAL_OPACITY_STEP = 0.5f
    private const val VANILLA_FINAL_OPACITY_SCALE = 0.5f
}
