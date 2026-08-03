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
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantColumnRun
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantRunFlag
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalColumn
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import java.security.MessageDigest
import java.util.HexFormat

internal data class DistantCompatibilityMaterial(
    val semanticMaterial: TerrainSemanticMaterialId,
    val opaque: Boolean,
    val fluid: DistantFluidSample? = null,
)

/** Registry-backed semantic lookup; identifiers are never classified by suffix. */
internal fun interface DistantCompatibilityMaterialResolver {
    fun resolve(identifier: ResourceLocation): DistantCompatibilityMaterial?
}

/**
 * Readable projection of one legacy top-only tile. Its partial completeness is
 * intentional: it preserves known surfaces and water beds without pretending
 * that the missing vertical interval was observed.
 */
internal class DistantTopOnlyCompatibilityPage(
    val position: ChunkPosition,
    columns: Collection<DistantVerticalColumn>,
) {
    val columns: List<DistantVerticalColumn> = java.util.List.copyOf(columns)
    val completeness: DistantSourceCompleteness = DistantSourceCompleteness.PARTIAL
    val semanticDigest: DistantSemanticDigest = digest(this.columns)

    init {
        require(this.columns.size == DistantLodTile.COLUMN_COUNT) {
            "Top-only compatibility page requires ${DistantLodTile.COLUMN_COUNT} columns"
        }
    }

    operator fun get(x: Int, z: Int): DistantVerticalColumn {
        require(x in 0 until COLUMN_WIDTH) { "Top-only compatibility x is out of bounds: $x" }
        require(z in 0 until COLUMN_WIDTH) { "Top-only compatibility z is out of bounds: $z" }
        return columns[DistantLodTile.index(x, z)]
    }

    fun pageKey(worldEpoch: Long): TerrainPageKey = TerrainPageKey(
        domain = TerrainDomain.DISTANT,
        detailLevel = 0,
        x = position.x.toLong(),
        y = 0L,
        z = position.z.toLong(),
        worldEpoch = worldEpoch,
    )

    fun verticalPage(worldEpoch: Long, originY: Int, sourceRevision: Long): DistantVerticalPage =
        DistantVerticalPage(
            key = pageKey(worldEpoch),
            width = COLUMN_WIDTH,
            originY = originY,
            sourceRevision = sourceRevision,
            completeness = completeness,
            columns = columns,
        )

    private companion object {
        const val COLUMN_WIDTH = 16
        const val DIGEST_SCHEMA = 1
        const val DIGEST_ALGORITHM = "SHA-256"

        fun digest(columns: List<DistantVerticalColumn>): DistantSemanticDigest {
            val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            digest.update(DIGEST_SCHEMA.toByte())
            digest.putInt(columns.size)
            for (column in columns) digest.putText(column.digest)
            return DistantSemanticDigest(
                schemaVersion = DIGEST_SCHEMA,
                algorithm = DIGEST_ALGORITHM,
                encodedValue = HexFormat.of().formatHex(digest.digest()),
            )
        }
    }
}

internal fun DistantLodTile.toTopOnlyCompatibilityPage(
    resolver: DistantCompatibilityMaterialResolver,
): DistantTopOnlyCompatibilityPage = DistantTopOnlyCompatibilityPage(
    position = position,
    columns = buildList(DistantLodTile.COLUMN_COUNT) {
        for (z in 0 until 16) {
            for (x in 0 until 16) add(get(x, z).toVerticalCompatibilityColumn(resolver))
        }
    },
)

/** One-way v1 reader migration. Callers persist only the returned v2 pages. */
internal fun migrateTopOnlyTiles(
    tiles: Iterable<DistantLodTile>,
    existingPositions: Set<ChunkPosition>,
    resolver: DistantCompatibilityMaterialResolver,
    worldEpoch: Long,
    originY: Int,
    nextSourceRevision: () -> Long,
    persist: (DistantVerticalPage) -> Unit,
): List<DistantVerticalPage> {
    val known = existingPositions.toMutableSet()
    return buildList {
        for (tile in tiles) {
            if (!known.add(tile.position)) continue
            val page = tile.toTopOnlyCompatibilityPage(resolver)
                .verticalPage(worldEpoch, originY, nextSourceRevision())
            persist(page)
            add(page)
        }
    }
}

/** Low-quality projection retained only as readable top-only migration data. */
internal fun DistantVerticalPage.toTopOnlyCompatibilityTile(): DistantLodTile =
    DistantLodTile.capture(ChunkPosition(Math.toIntExact(key.x), Math.toIntExact(key.z))) { x, z ->
        val occupied = get(x, z).runs.filter { it.material != null }
        val surface = occupied.firstOrNull() ?: return@capture DistantLodColumn(Int.MIN_VALUE, null)
        val solid = occupied.firstOrNull { it.fluid == null }
        DistantLodColumn(
            surfaceY = surface.maximumYExclusive - 1,
            material = ResourceLocation.of(surface.material!!.value),
            solidY = solid?.maximumYExclusive?.minus(1) ?: Int.MIN_VALUE,
            solidMaterial = solid?.material?.value?.let(ResourceLocation::of),
        )
    }

internal fun DistantLodColumn.toVerticalCompatibilityColumn(
    resolver: DistantCompatibilityMaterialResolver,
): DistantVerticalColumn {
    if (surfaceY == Int.MIN_VALUE) return DistantVerticalColumn(emptyList())
    val surface = material?.let(resolver::resolve) ?: return DistantVerticalColumn(emptyList())
    val bed = solidMaterial?.let(resolver::resolve)
    val runs = ArrayList<DistantColumnRun>(2)

    if (surface.fluid != null) {
        val fluidMinimum = if (solidY != Int.MIN_VALUE && solidY < surfaceY) solidY else surfaceY - 1
        runs += compatibilityRun(
            minimumY = fluidMinimum,
            height = Math.subtractExact(surfaceY, fluidMinimum),
            material = surface,
            fluid = surface.fluid,
        )
        if (bed != null && solidY != Int.MIN_VALUE && solidY <= fluidMinimum) {
            runs += compatibilityRun(
                minimumY = solidY - 1,
                height = 1,
                material = bed,
                fluid = null,
            )
        }
    } else {
        runs += compatibilityRun(
            minimumY = surfaceY - 1,
            height = 1,
            material = surface,
            fluid = null,
        )
        if (bed != null && solidY != Int.MIN_VALUE && solidY <= surfaceY - 1) {
            runs += compatibilityRun(
                minimumY = solidY - 1,
                height = 1,
                material = bed,
                fluid = null,
            )
        }
    }
    return DistantVerticalColumn(runs)
}

private fun compatibilityRun(
    minimumY: Int,
    height: Int,
    material: DistantCompatibilityMaterial,
    fluid: DistantFluidSample?,
) = DistantColumnRun(
    minimumY = minimumY,
    height = height,
    material = material.semanticMaterial,
    fluid = fluid,
    blockLight = 0,
    skyLight = 15,
    tint = null,
    flags = buildSet {
        add(DistantRunFlag.GENERATED)
        // Either side of this height-map sample needs neighbour-height closure, not volumetric
        // subtraction; otherwise a later native replacement exposes its full unsampled depth.
        add(DistantRunFlag.SURFACE_ONLY)
        if (material.opaque && fluid == null) add(DistantRunFlag.OPAQUE)
    },
    confidence = TOP_ONLY_CONFIDENCE,
)

private fun MessageDigest.putText(value: String) {
    val bytes = value.encodeToByteArray()
    putInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.putInt(value: Int) {
    update((value ushr 24).toByte())
    update((value ushr 16).toByte())
    update((value ushr 8).toByte())
    update(value.toByte())
}

private const val TOP_ONLY_CONFIDENCE = 25
