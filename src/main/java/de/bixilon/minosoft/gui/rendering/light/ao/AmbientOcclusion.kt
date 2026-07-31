/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.light.ao

import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil.applyBottom
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil.applyEast
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil.applyNorth
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil.applySouth
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil.applyTop
import de.bixilon.minosoft.gui.rendering.light.ao.AmbientOcclusionUtil.applyWest
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildSnapshot

class AmbientOcclusion private constructor(
    private val calculate: (Directions, InSectionPosition, IntArray) -> IntArray,
) {
    constructor(section: ChunkSection) : this({ direction, position, input ->
        when (direction) {
            Directions.DOWN -> applyBottom(section, position, input)
            Directions.UP -> applyTop(section, position, input)
            Directions.NORTH -> applyNorth(section, position, input)
            Directions.SOUTH -> applySouth(section, position, input)
            Directions.WEST -> applyWest(section, position, input)
            Directions.EAST -> applyEast(section, position, input)
        }
    })

    constructor(snapshot: TerrainBuildSnapshot) : this({ direction, position, input ->
        SnapshotAmbientOcclusion.calculate(snapshot, direction, position, input)
    })

    private var input = Array(Directions.SIZE) { IntArray(AmbientOcclusionUtil.LEVELS) }
    private var output = Array(Directions.SIZE) { AmbientOcclusionUtil.EMPTY }
    private var dirty = 0x00


    fun apply(direction: Directions, position: InSectionPosition): IntArray {
        val mask = 1 shl direction.ordinal
        if (dirty and mask != 0) return output[direction.ordinal] // already calculated

        val input = input[direction.ordinal]

        dirty = dirty or mask
        val output = calculate(direction, position, input)
        this.output[direction.ordinal] = output

        return output
    }

    fun clear() {
        dirty = 0x00
    }
}

private object SnapshotAmbientOcclusion {
    fun calculate(
        snapshot: TerrainBuildSnapshot,
        direction: Directions,
        position: InSectionPosition,
        output: IntArray,
    ): IntArray = when (direction) {
        Directions.DOWN -> setY(snapshot, position.x, position.y - 1, position.z, true, output)
        Directions.UP -> setY(snapshot, position.x, position.y + 1, position.z, false, output)
        Directions.NORTH -> setZ(snapshot, position.x, position.y, position.z - 1, true, output)
        Directions.SOUTH -> setZ(snapshot, position.x, position.y, position.z + 1, false, output)
        Directions.WEST -> setX(snapshot, position.x - 1, position.y, position.z, false, output)
        Directions.EAST -> setX(snapshot, position.x + 1, position.y, position.z, true, output)
    }

    private fun setY(
        snapshot: TerrainBuildSnapshot,
        x: Int,
        y: Int,
        z: Int,
        flip: Boolean,
        output: IntArray,
    ): IntArray {
        val west = trace(snapshot, x - 1, y, z)
        val north = trace(snapshot, x, y, z - 1)
        val east = trace(snapshot, x + 1, y, z)
        val south = trace(snapshot, x, y, z + 1)
        output[0] = AmbientOcclusionUtil.calculateLevel(west, north, trace(snapshot, x - 1, y, z - 1))
        output[2] = AmbientOcclusionUtil.calculateLevel(east, south, trace(snapshot, x + 1, y, z + 1))
        output[if (flip) 3 else 1] = AmbientOcclusionUtil.calculateLevel(north, east, trace(snapshot, x + 1, y, z - 1))
        output[if (flip) 1 else 3] = AmbientOcclusionUtil.calculateLevel(south, west, trace(snapshot, x - 1, y, z + 1))
        return output
    }

    private fun setZ(
        snapshot: TerrainBuildSnapshot,
        x: Int,
        y: Int,
        z: Int,
        flip: Boolean,
        output: IntArray,
    ): IntArray {
        val down = trace(snapshot, x, y - 1, z)
        val west = trace(snapshot, x - 1, y, z)
        val up = trace(snapshot, x, y + 1, z)
        val east = trace(snapshot, x + 1, y, z)
        output[0] = AmbientOcclusionUtil.calculateLevel(down, west, trace(snapshot, x - 1, y - 1, z))
        output[if (flip) 3 else 1] = AmbientOcclusionUtil.calculateLevel(west, up, trace(snapshot, x - 1, y + 1, z))
        output[2] = AmbientOcclusionUtil.calculateLevel(up, east, trace(snapshot, x + 1, y + 1, z))
        output[if (flip) 1 else 3] = AmbientOcclusionUtil.calculateLevel(east, down, trace(snapshot, x + 1, y - 1, z))
        return output
    }

    private fun setX(
        snapshot: TerrainBuildSnapshot,
        x: Int,
        y: Int,
        z: Int,
        flip: Boolean,
        output: IntArray,
    ): IntArray {
        val down = trace(snapshot, x, y - 1, z)
        val north = trace(snapshot, x, y, z - 1)
        val up = trace(snapshot, x, y + 1, z)
        val south = trace(snapshot, x, y, z + 1)
        output[0] = AmbientOcclusionUtil.calculateLevel(down, north, trace(snapshot, x, y - 1, z - 1))
        output[if (flip) 3 else 1] = AmbientOcclusionUtil.calculateLevel(north, up, trace(snapshot, x, y + 1, z - 1))
        output[2] = AmbientOcclusionUtil.calculateLevel(up, south, trace(snapshot, x, y + 1, z + 1))
        output[if (flip) 1 else 3] = AmbientOcclusionUtil.calculateLevel(south, down, trace(snapshot, x, y - 1, z + 1))
        return output
    }

    private fun trace(snapshot: TerrainBuildSnapshot, x: Int, y: Int, z: Int): Int =
        if (snapshot.isOpaque(x, y, z)) 1 else 0
}
