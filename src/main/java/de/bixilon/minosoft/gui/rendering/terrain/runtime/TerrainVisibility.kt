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

package de.bixilon.minosoft.gui.rendering.terrain.runtime

import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.SectionPosition
import java.util.ArrayDeque

/**
 * Immutable connectivity between the six faces of a section.
 *
 * A missing value is deliberately represented separately by callers. An
 * absent connectivity record means "include conservatively", while [NONE]
 * means that the section was measured and no pair of faces connects.
 */
@JvmInline
value class TerrainDirectionalVisibility private constructor(private val bits: Long) {
    val encodedBits: Long get() = bits

    fun connects(from: Directions, to: Directions): Boolean {
        if (from == to) return true
        return bits and bit(from, to) != 0L
    }

    companion object {
        val NONE = TerrainDirectionalVisibility(0L)
        val ALL = of(Directions.VALUES.flatMap { from ->
            Directions.VALUES.map { to -> from to to }
        })

        fun of(connections: Iterable<Pair<Directions, Directions>>): TerrainDirectionalVisibility {
            var bits = 0L
            for ((from, to) in connections) {
                if (from == to) continue
                bits = bits or bit(from, to) or bit(to, from)
            }
            return TerrainDirectionalVisibility(bits)
        }

        private fun bit(from: Directions, to: Directions): Long =
            1L shl (from.ordinal * Directions.SIZE + to.ordinal)
    }
}

/**
 * Computes conservative face connectivity by flood filling non-opaque cells.
 */
object TerrainConnectivityBuilder {
    private const val SIZE = ChunkSize.SECTION_WIDTH_X
    private const val AREA = SIZE * SIZE
    private const val VOLUME = SIZE * SIZE * SIZE

    fun build(opaque: BooleanArray): TerrainDirectionalVisibility {
        require(opaque.size == VOLUME) { "Expected $VOLUME opacity cells, got ${opaque.size}" }

        val visited = BooleanArray(VOLUME)
        val queue = IntArray(VOLUME)
        val connections = ArrayList<Pair<Directions, Directions>>(Directions.SIZE * Directions.SIZE)

        for (start in 0 until VOLUME) {
            if (opaque[start] || visited[start]) continue

            var read = 0
            var write = 0
            var boundaryMask = 0
            queue[write++] = start
            visited[start] = true

            while (read < write) {
                val index = queue[read++]
                val y = index / AREA
                val remainder = index - y * AREA
                val z = remainder / SIZE
                val x = remainder - z * SIZE

                if (y == 0) boundaryMask = boundaryMask or mask(Directions.DOWN)
                if (y == SIZE - 1) boundaryMask = boundaryMask or mask(Directions.UP)
                if (z == 0) boundaryMask = boundaryMask or mask(Directions.NORTH)
                if (z == SIZE - 1) boundaryMask = boundaryMask or mask(Directions.SOUTH)
                if (x == 0) boundaryMask = boundaryMask or mask(Directions.WEST)
                if (x == SIZE - 1) boundaryMask = boundaryMask or mask(Directions.EAST)

                if (x > 0) enqueue(index - 1, opaque, visited, queue, write).also { write = it }
                if (x < SIZE - 1) enqueue(index + 1, opaque, visited, queue, write).also { write = it }
                if (z > 0) enqueue(index - SIZE, opaque, visited, queue, write).also { write = it }
                if (z < SIZE - 1) enqueue(index + SIZE, opaque, visited, queue, write).also { write = it }
                if (y > 0) enqueue(index - AREA, opaque, visited, queue, write).also { write = it }
                if (y < SIZE - 1) enqueue(index + AREA, opaque, visited, queue, write).also { write = it }
            }

            for (from in Directions.VALUES) {
                if (boundaryMask and mask(from) == 0) continue
                for (to in Directions.VALUES) {
                    if (from != to && boundaryMask and mask(to) != 0) connections += from to to
                }
            }
        }
        return TerrainDirectionalVisibility.of(connections)
    }

    private fun enqueue(
        index: Int,
        opaque: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        write: Int,
    ): Int {
        if (opaque[index] || visited[index]) return write
        visited[index] = true
        queue[write] = index
        return write + 1
    }

    private fun mask(direction: Directions) = 1 shl direction.ordinal
}

data class TerrainVisibilityNode(
    val position: SectionPosition,
    val connectivity: TerrainDirectionalVisibility?,
)

/**
 * Synchronous, deterministic graph traversal. The caller supplies the
 * view-distance/frustum-filtered nodes, so traversal only decides occlusion.
 */
object TerrainVisibilityTraversal {
    fun traverse(
        camera: SectionPosition,
        nodes: Map<SectionPosition, TerrainVisibilityNode>,
    ): List<SectionPosition> {
        if (nodes.isEmpty()) return emptyList()
        if (camera !in nodes) {
            return nodes.keys.sortedWith(distanceComparator(camera))
        }

        data class Visit(val position: SectionPosition, val entry: Directions?)

        val queue = ArrayDeque<Visit>()
        val visited = HashSet<SectionPosition>(nodes.size)
        val result = ArrayList<SectionPosition>(nodes.size)
        queue += Visit(camera, null)

        while (queue.isNotEmpty()) {
            val visit = queue.removeFirst()
            if (!visited.add(visit.position)) continue
            val node = nodes[visit.position] ?: continue
            result += visit.position

            for (exit in Directions.VALUES) {
                if (
                    visit.entry != null &&
                    node.connectivity?.connects(visit.entry, exit) == false
                ) {
                    continue
                }
                val next = offset(visit.position, exit)
                if (next !in visited && next in nodes) queue += Visit(next, exit.inverted)
            }
        }
        return result
    }

    /**
     * Traverses a bounded section volume without materializing an object for
     * every empty section. Missing cells retain the conservative ALL
     * connectivity used by the original dense graph.
     */
    fun traverseBounded(
        camera: SectionPosition,
        nodes: Map<SectionPosition, TerrainVisibilityNode>,
        minimum: SectionPosition,
        maximumInclusive: SectionPosition,
    ): List<SectionPosition> {
        if (nodes.isEmpty()) return emptyList()
        require(minimum.x <= maximumInclusive.x && minimum.y <= maximumInclusive.y && minimum.z <= maximumInclusive.z)
        if (camera.x !in minimum.x..maximumInclusive.x ||
            camera.y !in minimum.y..maximumInclusive.y ||
            camera.z !in minimum.z..maximumInclusive.z
        ) return nodes.keys.sortedWith(distanceComparator(camera))

        val width = Math.addExact(Math.subtractExact(maximumInclusive.x, minimum.x), 1)
        val height = Math.addExact(Math.subtractExact(maximumInclusive.y, minimum.y), 1)
        val depth = Math.addExact(Math.subtractExact(maximumInclusive.z, minimum.z), 1)
        val plane = Math.multiplyExact(width, depth)
        val volume = Math.multiplyExact(plane, height)
        val explicit = arrayOfNulls<TerrainVisibilityNode>(volume)

        fun index(x: Int, y: Int, z: Int): Int =
            (y - minimum.y) * plane + (z - minimum.z) * width + (x - minimum.x)

        for ((position, node) in nodes) {
            if (position.x in minimum.x..maximumInclusive.x &&
                position.y in minimum.y..maximumInclusive.y &&
                position.z in minimum.z..maximumInclusive.z
            ) explicit[index(position.x, position.y, position.z)] = node
        }

        val visited = BooleanArray(volume)
        val queueIndices = IntArray(volume)
        val queueEntries = ByteArray(volume)
        val result = ArrayList<SectionPosition>(nodes.size)
        var read = 0
        var write = 1
        queueIndices[0] = index(camera.x, camera.y, camera.z)
        visited[queueIndices[0]] = true

        while (read < write) {
            val current = queueIndices[read]
            val entryOrdinal = queueEntries[read++].toInt() - 1
            val node = explicit[current]
            if (node != null) result += node.position

            val localY = current / plane
            val remainder = current - localY * plane
            val localZ = remainder / width
            val localX = remainder - localZ * width
            for (exit in Directions.VALUES) {
                if (entryOrdinal >= 0 && node?.connectivity?.connects(Directions.VALUES[entryOrdinal], exit) == false) continue
                val nextX = localX + exit.x
                val nextY = localY + exit.y
                val nextZ = localZ + exit.z
                if (nextX !in 0 until width || nextY !in 0 until height || nextZ !in 0 until depth) continue
                val next = nextY * plane + nextZ * width + nextX
                if (!visited[next]) {
                    visited[next] = true
                    queueIndices[write] = next
                    queueEntries[write] = (exit.inverted.ordinal + 1).toByte()
                    write++
                }
            }
        }
        return result
    }

    fun ordered(
        camera: SectionPosition,
        visible: Collection<SectionPosition>,
        translucent: Boolean,
    ): List<SectionPosition> {
        val comparator = distanceComparator(camera)
        return if (translucent) visible.sortedWith(comparator.reversed()) else visible.sortedWith(comparator)
    }

    private fun distanceComparator(camera: SectionPosition): Comparator<SectionPosition> =
        compareBy<SectionPosition> {
            val dx = it.x - camera.x
            val dy = it.y - camera.y
            val dz = it.z - camera.z
            dx * dx + dy * dy + dz * dz
        }.thenBy { it.y }.thenBy { it.z }.thenBy { it.x }

    private fun offset(position: SectionPosition, direction: Directions) = SectionPosition(
        position.x + direction.x,
        position.y + direction.y,
        position.z + direction.z,
    )
}
