/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.light.terrain

import de.bixilon.minosoft.data.Axes
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildSnapshot
import kotlin.math.roundToInt

data class TerrainQuadLight(
    val light: IntArray,
    val brightness: FloatArray,
    val flipDiagonal: Boolean,
)

/**
 * Sodium-style face lighting expressed in Minosoft's packed light contract.
 *
 * The four canonical face corners average the outward sample, both edge
 * samples, and the diagonal sample. Partial and inset faces bilinearly
 * interpolate those corners instead of snapping to a full-cube result.
 */
class SmoothTerrainLighting private constructor(
    private val lightAt: (BlockPosition) -> Int?,
    private val opaqueAt: (BlockPosition) -> Boolean,
) {
    constructor(chunk: Chunk) : this(
        lightAt = { position ->
            chunk.neighbours.traceChunk(position.chunkPosition - chunk.position)
                ?.light?.get(position.inChunkPosition)?.index
        },
        opaqueAt = { position ->
            chunk.neighbours.traceBlock(position)?.flags
                ?.let { BlockStateFlags.FULL_OPAQUE in it } == true
        },
    )

    constructor(snapshot: TerrainBuildSnapshot) : this(
        lightAt = snapshot::lightOrNull,
        opaqueAt = { position ->
            snapshot.state(position)?.flags?.let { BlockStateFlags.FULL_OPAQUE in it } == true
        },
    )

    private fun sampleLight(position: BlockPosition, fallback: LightLevel): LightLevel {
        return lightAt(position)?.let { LightLevel(it.toByte()) } ?: fallback
    }

    private fun isOpaque(position: BlockPosition): Boolean = opaqueAt(position)

    private fun offset(position: BlockPosition, axis: Axes, amount: Int): BlockPosition = when (axis) {
        Axes.X -> position.with(x = position.x + amount)
        Axes.Y -> position.with(y = position.y + amount)
        Axes.Z -> position.with(z = position.z + amount)
    }

    private fun component(position: FaceVertexData, vertex: Int, axis: Axes): Float {
        return position[vertex * 3 + axis.ordinal].coerceIn(0.0f, 1.0f)
    }

    private fun averageLight(samples: Array<LightLevel>): LightLevel {
        var block = 0
        var sky = 0
        for (sample in samples) {
            block += sample.block
            sky += sample.sky
        }
        return LightLevel(
            (block.toFloat() / samples.size).roundToInt().coerceIn(0, LightLevel.MAX_LEVEL),
            (sky.toFloat() / samples.size).roundToInt().coerceIn(0, LightLevel.MAX_LEVEL),
        )
    }

    private fun interpolateLight(a: LightLevel, b: LightLevel, delta: Float): LightLevel {
        return LightLevel(
            (a.block + (b.block - a.block) * delta).roundToInt().coerceIn(0, LightLevel.MAX_LEVEL),
            (a.sky + (b.sky - a.sky) * delta).roundToInt().coerceIn(0, LightLevel.MAX_LEVEL),
        )
    }

    private fun bilinear(values: FloatArray, u: Float, v: Float): Float {
        val low = values[0] + (values[1] - values[0]) * u
        val high = values[3] + (values[2] - values[3]) * u
        return low + (high - low) * v
    }

    private fun bilinear(values: Array<LightLevel>, u: Float, v: Float): LightLevel {
        val low = interpolateLight(values[0], values[1], u)
        val high = interpolateLight(values[3], values[2], u)
        return interpolateLight(low, high, v)
    }

    fun calculate(
        position: BlockPosition,
        direction: Directions,
        vertices: FaceVertexData,
        fallbackRaw: Int,
        ambientOcclusion: Boolean,
    ): TerrainQuadLight {
        val fallback = LightLevel(fallbackRaw.toByte())
        val normalAxis = direction.axis
        val uAxis = when (normalAxis) {
            Axes.X -> Axes.Z
            Axes.Y, Axes.Z -> Axes.X
        }
        val vAxis = when (normalAxis) {
            Axes.X, Axes.Z -> Axes.Y
            Axes.Y -> Axes.Z
        }
        val outward = position + direction
        val cornerLight = Array(4) { fallback }
        val cornerBrightness = FloatArray(4) { 1.0f }

        for (corner in 0 until 4) {
            val u = if (corner == 1 || corner == 2) 1 else -1
            val v = if (corner >= 2) 1 else -1
            val edgeU = offset(outward, uAxis, u)
            val edgeV = offset(outward, vAxis, v)
            val diagonal = offset(edgeU, vAxis, v)

            cornerLight[corner] = averageLight(
                arrayOf(
                    sampleLight(outward, fallback),
                    sampleLight(edgeU, fallback),
                    sampleLight(edgeV, fallback),
                    sampleLight(diagonal, fallback),
                ),
            )

            if (ambientOcclusion) {
                val opaqueU = isOpaque(edgeU)
                val opaqueV = isOpaque(edgeV)
                val occlusion = (
                    (if (opaqueU) 1.0f else 0.0f) +
                        (if (opaqueV) 1.0f else 0.0f) +
                        (if (opaqueU && opaqueV || isOpaque(diagonal)) 1.0f else 0.0f)
                    ) / 3.0f
                cornerBrightness[corner] = 1.0f - occlusion * AO_DARKENING
            }
        }

        val lights = IntArray(4)
        val brightness = FloatArray(4)
        for (vertex in 0 until 4) {
            val u = component(vertices, vertex, uAxis)
            val v = component(vertices, vertex, vAxis)
            val normal = component(vertices, vertex, normalAxis)
            val inset = if (direction.negative) normal else 1.0f - normal
            val smooth = bilinear(cornerLight, u, v)
            lights[vertex] = interpolateLight(smooth, fallback, inset).index
            brightness[vertex] = bilinear(cornerBrightness, u, v)
        }

        val diagonal02 = brightness[0] + brightness[2] + lightBrightness(lights[0]) + lightBrightness(lights[2])
        val diagonal13 = brightness[1] + brightness[3] + lightBrightness(lights[1]) + lightBrightness(lights[3])
        return TerrainQuadLight(lights, brightness, flipDiagonal = diagonal02 <= diagonal13)
    }

    companion object {
        private const val AO_DARKENING = 0.4f

        fun lightBrightness(raw: Int): Float {
            val light = LightLevel(raw.toByte())
            return (light.block + light.sky) / (LightLevel.MAX_LEVEL * 2.0f)
        }
    }
}
