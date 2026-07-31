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

package de.bixilon.minosoft.terrain.distant

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage

internal data class DistantLodColumn(
    val surfaceY: Int,
    val material: ResourceLocation?,
    val solidY: Int = surfaceY,
    val solidMaterial: ResourceLocation? = material,
) {
    init {
        require(surfaceY == Int.MIN_VALUE || surfaceY in -MAXIMUM_WORLD_COORDINATE..MAXIMUM_WORLD_COORDINATE) {
            "Distant LOD surface height is out of bounds: $surfaceY"
        }
        require(solidY == Int.MIN_VALUE || solidY in -MAXIMUM_WORLD_COORDINATE..MAXIMUM_WORLD_COORDINATE) {
            "Distant LOD solid height is out of bounds: $solidY"
        }
    }

    private companion object {
        const val MAXIMUM_WORLD_COORDINATE = 30_000_000
    }
}

/**
 * Immutable 16x16 explored-surface snapshot. Column index is `(z << 4) | x`.
 */
internal class DistantLodTile private constructor(
    val position: ChunkPosition,
    private val heights: IntArray,
    private val materials: Array<ResourceLocation?>,
    private val solidHeights: IntArray,
    private val solidMaterials: Array<ResourceLocation?>,
) {
    init {
        require(position.x in -MAXIMUM_CHUNK_COORDINATE..MAXIMUM_CHUNK_COORDINATE) {
            "Distant LOD tile x is out of bounds: ${position.x}"
        }
        require(position.z in -MAXIMUM_CHUNK_COORDINATE..MAXIMUM_CHUNK_COORDINATE) {
            "Distant LOD tile z is out of bounds: ${position.z}"
        }
        require(heights.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT heights, found ${heights.size}"
        }
        require(materials.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT materials, found ${materials.size}"
        }
        require(solidHeights.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT solid heights, found ${solidHeights.size}"
        }
        require(solidMaterials.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT solid materials, found ${solidMaterials.size}"
        }
    }

    operator fun get(x: Int, z: Int): DistantLodColumn {
        require(x in 0 until ChunkSize.SECTION_WIDTH_X) { "Distant LOD x is out of bounds: $x" }
        require(z in 0 until ChunkSize.SECTION_WIDTH_Z) { "Distant LOD z is out of bounds: $z" }
        val index = index(x, z)
        return DistantLodColumn(
            heights[index],
            materials[index],
            solidHeights[index],
            solidMaterials[index],
        )
    }

    fun update(
        columns: Set<Int>,
        sampler: (x: Int, z: Int) -> DistantLodColumn,
    ): DistantLodTile {
        if (columns.isEmpty()) return this
        columns.forEach { index ->
            require(index in 0 until COLUMN_COUNT) { "Distant LOD column is out of bounds: $index" }
        }
        val nextHeights = heights.copyOf()
        val nextMaterials = materials.copyOf()
        val nextSolidHeights = solidHeights.copyOf()
        val nextSolidMaterials = solidMaterials.copyOf()
        for (index in columns) {
            val column = sampler(index and COLUMN_MASK, index ushr COLUMN_BITS)
            nextHeights[index] = column.surfaceY
            nextMaterials[index] = column.material
            nextSolidHeights[index] = column.solidY
            nextSolidMaterials[index] = column.solidMaterial
        }
        return DistantLodTile(
            position,
            nextHeights,
            nextMaterials,
            nextSolidHeights,
            nextSolidMaterials,
        )
    }

    companion object {
        const val COLUMN_COUNT = ChunkSize.SECTION_WIDTH_X * ChunkSize.SECTION_WIDTH_Z
        private const val COLUMN_BITS = 4
        private const val COLUMN_MASK = ChunkSize.SECTION_WIDTH_X - 1
        private const val MAXIMUM_CHUNK_COORDINATE = 30_000_000 / ChunkSize.SECTION_WIDTH_X

        fun capture(
            position: ChunkPosition,
            sampler: (x: Int, z: Int) -> DistantLodColumn,
        ): DistantLodTile {
            val heights = IntArray(COLUMN_COUNT)
            val materials = arrayOfNulls<ResourceLocation>(COLUMN_COUNT)
            val solidHeights = IntArray(COLUMN_COUNT)
            val solidMaterials = arrayOfNulls<ResourceLocation>(COLUMN_COUNT)
            for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
                for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
                    val index = index(x, z)
                    val column = sampler(x, z)
                    heights[index] = column.surfaceY
                    materials[index] = column.material
                    solidHeights[index] = column.solidY
                    solidMaterials[index] = column.solidMaterial
                }
            }
            return DistantLodTile(
                position,
                heights,
                materials,
                solidHeights,
                solidMaterials,
            )
        }

        fun index(x: Int, z: Int): Int = (z shl COLUMN_BITS) or x
    }
}

/**
 * Bounded access-ordered store for detached distant tiles.
 */
internal class DistantLodTileStore(
    private val maximumTiles: Int = DEFAULT_MAXIMUM_TILES,
) {
    init {
        require(maximumTiles > 0) { "Distant LOD tile limit must be positive" }
    }

    private val tiles = LinkedHashMap<ChunkPosition, DistantLodTile>(16, 0.75f, true)

    @Synchronized
    fun put(tile: DistantLodTile): List<ChunkPosition> {
        val evicted = ArrayList<ChunkPosition>(1)
        tiles[tile.position] = tile
        while (tiles.size > maximumTiles) {
            val eldest = tiles.entries.firstOrNull() ?: break
            tiles.remove(eldest.key)
            evicted += eldest.key
        }
        return java.util.List.copyOf(evicted)
    }

    @Synchronized
    operator fun get(position: ChunkPosition): DistantLodTile? = tiles[position]

    @Synchronized
    fun size(): Int = tiles.size

    @Synchronized
    fun snapshot(): List<DistantLodTile> = java.util.List.copyOf(tiles.values)

    @Synchronized
    fun clear() = tiles.clear()

    private companion object {
        const val DEFAULT_MAXIMUM_TILES = 4_096
    }
}

internal class DistantLodSnapshot(
    val revision: Long,
    tiles: Collection<DistantLodTile>,
    sources: Map<ChunkPosition, DistantLodTileSource>,
    verticalPages: Map<ChunkPosition, DistantVerticalPage> = emptyMap(),
) {
    val tiles: List<DistantLodTile> = java.util.List.copyOf(tiles)
    val sources: Map<ChunkPosition, DistantLodTileSource> = java.util.Map.copyOf(sources)
    val verticalPages: Map<ChunkPosition, DistantVerticalPage> = java.util.Map.copyOf(verticalPages)
}

internal data class DistantLodUpdate(
    val tile: DistantLodTile,
    val source: DistantLodTileSource,
    val verticalPage: DistantVerticalPage?,
)

/** Bounded coalesced changes since one render-source revision. */
internal class DistantLodChanges(
    val revision: Long,
    val reset: Boolean,
    updates: Collection<DistantLodUpdate>,
    removals: Collection<ChunkPosition>,
) {
    val updates: List<DistantLodUpdate> = java.util.List.copyOf(updates)
    val removals: Set<ChunkPosition> = java.util.Set.copyOf(removals)

    companion object {
        fun reset(snapshot: DistantLodSnapshot) = DistantLodChanges(
            revision = snapshot.revision,
            reset = true,
            updates = snapshot.tiles.map { tile ->
                DistantLodUpdate(
                    tile,
                    snapshot.sources[tile.position] ?: DistantLodTileSource.NATIVE,
                    snapshot.verticalPages[tile.position],
                )
            },
            removals = emptySet(),
        )
    }
}

internal class DistantLodChangeWindow(
    val revision: Long,
    val reset: Boolean,
    positions: Collection<ChunkPosition>,
) {
    val positions: List<ChunkPosition> = java.util.List.copyOf(positions)
}

/** Bounded, position-coalescing journal used by the production render feed. */
internal class DistantLodChangeJournal(private val maximumPositions: Int) {
    private val revisions = LinkedHashMap<ChunkPosition, Long>()
    private var floorRevision = 0L

    init {
        require(maximumPositions > 0) { "Distant change journal capacity must be positive" }
    }

    @Synchronized
    fun record(position: ChunkPosition, revision: Long) {
        require(revision >= 0L) { "Distant change revision must not be negative" }
        revisions.remove(position)
        revisions[position] = revision
        while (revisions.size > maximumPositions) {
            val eldest = revisions.entries.first()
            revisions.remove(eldest.key)
            floorRevision = maxOf(floorRevision, eldest.value)
        }
    }

    @Synchronized
    fun changesSince(previousRevision: Long, currentRevision: Long): DistantLodChangeWindow {
        require(currentRevision >= 0L) { "Current distant change revision must not be negative" }
        val reset = previousRevision == Long.MIN_VALUE || previousRevision < floorRevision ||
            previousRevision > currentRevision
        val positions = if (reset) emptyList() else revisions.entries.asSequence()
            .filter { it.value > previousRevision }
            .map(Map.Entry<ChunkPosition, Long>::key)
            .toList()
        return DistantLodChangeWindow(currentRevision, reset, positions)
    }

    @Synchronized
    fun clear() {
        revisions.clear()
        floorRevision = 0L
    }
}

internal enum class DistantLodTileSource(val wireName: String) {
    NATIVE("native"),
    PERSISTENCE("persistence"),
    LOCAL_GENERATION("local-generation"),
    NETWORK("network"),
}

internal interface DistantTerrainRenderConfig {
    val renderDistanceChunks: Int
    val maximumTiles: Int
}

internal interface DistantTerrainRenderSource {
    val revision: Long
    val presentationEnabled: Boolean

    fun snapshot(): DistantLodSnapshot

    fun changesSince(revision: Long): DistantLodChanges = DistantLodChanges.reset(snapshot())

    fun publishDiagnostics(diagnostics: DistantLodRenderDiagnostics)
}

internal fun maximumContiguousDistantRadius(maximumTiles: Int): Int {
    require(maximumTiles > 0) { "Distant LOD tile capacity must be positive" }
    var diameter = kotlin.math.sqrt(maximumTiles.toDouble()).toInt()
    if (diameter % 2 == 0) diameter--
    return ((diameter - 1) / 2).coerceAtLeast(0)
}
