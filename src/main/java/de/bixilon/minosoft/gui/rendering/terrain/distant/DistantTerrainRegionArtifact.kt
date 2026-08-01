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

package de.bixilon.minosoft.gui.rendering.terrain.distant

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFaceDirection
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantMeshQuad
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageMeshArtifact
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactBounds
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactCoordinate
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactStream
import de.bixilon.minosoft.terrain.model.mesh.TerrainMeshArtifact
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionExtent
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionKey
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compact material mapping retained by the consolidated distant-page encoder. */
internal enum class DistantLodMaterial(
    val dhId: Int,
    val color: RGBAColor,
    val water: Boolean = false,
) {
    UNKNOWN(0, RGBAColor(112, 112, 112)),
    LEAVES(1, RGBAColor(72, 128, 58)),
    STONE(2, RGBAColor(120, 120, 120)),
    WOOD(3, RGBAColor(126, 94, 58)),
    METAL(4, RGBAColor(164, 166, 170)),
    DIRT(5, RGBAColor(120, 86, 54)),
    LAVA(6, RGBAColor(255, 102, 12)),
    DEEPSLATE(7, RGBAColor(70, 70, 76)),
    SNOW(8, RGBAColor(238, 244, 247)),
    SAND(9, RGBAColor(218, 204, 143)),
    TERRACOTTA(10, RGBAColor(156, 88, 63)),
    NETHER_STONE(11, RGBAColor(104, 40, 42)),
    WATER(12, RGBAColor(48, 96, 196, 184), water = true),
    GRASS(13, RGBAColor(92, 146, 62)),
    AIR(14, RGBAColor(0, 0, 0, 0)),
    ILLUMINATED(15, RGBAColor(255, 194, 92));

    companion object {
        fun of(resource: ResourceLocation?): DistantLodMaterial {
            val path = resource?.path ?: return UNKNOWN
            return when {
                path == "air" || path.endsWith("_air") -> AIR
                "water" in path -> WATER
                "lava" in path -> LAVA
                "leaves" in path -> LEAVES
                "grass" in path || "moss" in path -> GRASS
                "snow" in path || "ice" in path -> SNOW
                "sand" in path || "gravel" in path -> SAND
                "terracotta" in path -> TERRACOTTA
                "deepslate" in path -> DEEPSLATE
                "netherrack" in path || "nether_" in path -> NETHER_STONE
                "log" in path || "wood" in path || "planks" in path -> WOOD
                "iron" in path || "gold" in path || "copper" in path || "metal" in path -> METAL
                "dirt" in path || "mud" in path || "clay" in path -> DIRT
                "torch" in path || "lamp" in path || "lantern" in path || "glow" in path ||
                    "light" in path -> ILLUMINATED
                "stone" in path || "ore" in path -> STONE
                else -> UNKNOWN
            }
        }
    }
}

internal data class DistantTerrainMeshStruct(
    val position: Vec3f,
    val color: Int,
    val light: Int,
    val normalMaterial: Int,
) {
    companion object : MeshStruct(DistantTerrainMeshStruct::class)
}

/** Encodes compact distant quads relative to one shared region draw origin. */
internal object DistantTerrainRegionArtifactEncoder {
    val solidMaterial = TerrainSemanticMaterialId("minosoft:terrain/distant-opaque")
    val waterMaterial = TerrainSemanticMaterialId("minosoft:terrain/distant-water")

    fun encode(
        identity: TerrainBuildIdentity,
        page: DistantVerticalPage,
        artifact: DistantPageMeshArtifact,
        extent: TerrainRegionExtent,
    ): TerrainMeshArtifact {
        require(identity.page == page.key && artifact.page == page.key) {
            "Distant region artifact inputs belong to different pages"
        }
        val region = TerrainRegionKey.containing(page.key, extent)
        val regionPageX = Math.multiplyExact(region.x, extent.x.toLong())
        val regionPageZ = Math.multiplyExact(region.z, extent.z.toLong())
        val offsetX = Math.toIntExact(
            Math.multiplyExact(Math.subtractExact(page.key.x, regionPageX), page.pageSizeBlocks.toLong()),
        )
        val offsetZ = Math.toIntExact(
            Math.multiplyExact(Math.subtractExact(page.key.z, regionPageZ), page.pageSizeBlocks.toLong()),
        )
        val solid = artifact.quads.filter { it.fluid == null }
        val water = artifact.quads.filter { it.fluid != null }
        val streams = buildList(2) {
            encodeStream("distant:opaque", solidMaterial, solid, offsetX, offsetZ)?.let(::add)
            encodeStream("distant:water", waterMaterial, water, offsetX, offsetZ)?.let(::add)
        }
        val bounds = if (artifact.quads.isEmpty()) null else artifact.quads.bounds(offsetX, offsetZ)
        return TerrainMeshArtifact(
            identity = identity,
            streams = streams,
            bounds = bounds,
            connectivityBits = 0L,
            coverageContribution = if (streams.isEmpty()) emptyList() else listOf(identity.page),
        )
    }

    private fun encodeStream(
        partition: String,
        material: TerrainSemanticMaterialId,
        quads: List<DistantMeshQuad>,
        offsetX: Int,
        offsetZ: Int,
    ): TerrainArtifactStream? {
        if (quads.isEmpty()) return null
        val vertices = ByteBuffer.allocate(Math.multiplyExact(quads.size, BYTES_PER_QUAD))
            .order(ByteOrder.LITTLE_ENDIAN)
        val indices = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(quads.size, 6), Int.SIZE_BYTES))
            .order(ByteOrder.LITTLE_ENDIAN)
        quads.forEachIndexed { quadIndex, quad ->
            encodeQuad(vertices, quad, offsetX, offsetZ)
            val base = Math.multiplyExact(quadIndex, 4)
            indices.putInt(base + 3)
            indices.putInt(base + 2)
            indices.putInt(base + 1)
            indices.putInt(base + 3)
            indices.putInt(base + 1)
            indices.putInt(base)
        }
        return TerrainArtifactStream(
            partitionId = partition,
            material = material,
            vertexStrideBytes = DistantTerrainMeshStruct.bytes,
            vertexBytes = vertices.array(),
            indexElementBytes = Int.SIZE_BYTES,
            indexBytes = indices.array(),
        )
    }

    private fun encodeQuad(destination: ByteBuffer, quad: DistantMeshQuad, offsetX: Int, offsetZ: Int) {
        val material = DistantLodMaterial.of(ResourceLocation.of(quad.material.value))
        val color = quad.tint?.resolvedRgb?.let { rgb ->
            RGBAColor(
                red = rgb ushr 16 and 0xFF,
                green = rgb ushr 8 and 0xFF,
                blue = rgb and 0xFF,
                alpha = material.color.alpha,
            )
        } ?: material.color
        val light = (quad.skyLight shl 4) or quad.blockLight
        val normal = when (quad.direction) {
            DistantFaceDirection.UP -> UP_NORMAL
            DistantFaceDirection.DOWN -> DOWN_NORMAL
            DistantFaceDirection.NORTH -> NORTH_NORMAL
            DistantFaceDirection.SOUTH -> SOUTH_NORMAL
            DistantFaceDirection.WEST -> WEST_NORMAL
            DistantFaceDirection.EAST -> EAST_NORMAL
        }
        val normalMaterial = (material.dhId shl 3) or normal
        fun vertex(u: Int, v: Int) {
            when (quad.direction) {
                DistantFaceDirection.UP,
                DistantFaceDirection.DOWN,
                -> destination.vertex(offsetX + u, v, offsetZ + quad.plane, color.rgba, light, normalMaterial)

                DistantFaceDirection.NORTH,
                DistantFaceDirection.SOUTH,
                -> destination.vertex(offsetX + u, v, offsetZ + quad.plane, color.rgba, light, normalMaterial)

                DistantFaceDirection.WEST,
                DistantFaceDirection.EAST,
                -> destination.vertex(offsetX + quad.plane, v, offsetZ + u, color.rgba, light, normalMaterial)
            }
        }
        when (quad.direction) {
            DistantFaceDirection.UP -> {
                destination.vertex(offsetX + quad.minimumU, quad.plane, offsetZ + quad.maximumVExclusive, color.rgba, light, normalMaterial)
                destination.vertex(offsetX + quad.maximumUExclusive, quad.plane, offsetZ + quad.maximumVExclusive, color.rgba, light, normalMaterial)
                destination.vertex(offsetX + quad.maximumUExclusive, quad.plane, offsetZ + quad.minimumV, color.rgba, light, normalMaterial)
                destination.vertex(offsetX + quad.minimumU, quad.plane, offsetZ + quad.minimumV, color.rgba, light, normalMaterial)
            }

            DistantFaceDirection.DOWN -> {
                destination.vertex(offsetX + quad.minimumU, quad.plane, offsetZ + quad.minimumV, color.rgba, light, normalMaterial)
                destination.vertex(offsetX + quad.maximumUExclusive, quad.plane, offsetZ + quad.minimumV, color.rgba, light, normalMaterial)
                destination.vertex(offsetX + quad.maximumUExclusive, quad.plane, offsetZ + quad.maximumVExclusive, color.rgba, light, normalMaterial)
                destination.vertex(offsetX + quad.minimumU, quad.plane, offsetZ + quad.maximumVExclusive, color.rgba, light, normalMaterial)
            }

            DistantFaceDirection.NORTH,
            DistantFaceDirection.EAST,
            -> {
                vertex(quad.minimumU, quad.maximumVExclusive)
                vertex(quad.maximumUExclusive, quad.maximumVExclusive)
                vertex(quad.maximumUExclusive, quad.minimumV)
                vertex(quad.minimumU, quad.minimumV)
            }

            DistantFaceDirection.SOUTH,
            DistantFaceDirection.WEST,
            -> {
                vertex(quad.maximumUExclusive, quad.maximumVExclusive)
                vertex(quad.minimumU, quad.maximumVExclusive)
                vertex(quad.minimumU, quad.minimumV)
                vertex(quad.maximumUExclusive, quad.minimumV)
            }
        }
    }

    private fun ByteBuffer.vertex(x: Int, y: Int, z: Int, color: Int, light: Int, normalMaterial: Int) {
        putFloat(x.toFloat())
        putFloat(y.toFloat())
        putFloat(z.toFloat())
        putInt(color)
        putInt(light)
        putInt(normalMaterial)
    }

    private fun List<DistantMeshQuad>.bounds(offsetX: Int, offsetZ: Int): TerrainArtifactBounds {
        var minimumX = Int.MAX_VALUE
        var minimumY = Int.MAX_VALUE
        var minimumZ = Int.MAX_VALUE
        var maximumX = Int.MIN_VALUE
        var maximumY = Int.MIN_VALUE
        var maximumZ = Int.MIN_VALUE
        for (quad in this) {
            when (quad.direction) {
                DistantFaceDirection.UP,
                DistantFaceDirection.DOWN,
                -> {
                    minimumX = minOf(minimumX, offsetX + quad.minimumU)
                    maximumX = maxOf(maximumX, offsetX + quad.maximumUExclusive)
                    minimumY = minOf(minimumY, quad.plane)
                    maximumY = maxOf(maximumY, quad.plane)
                    minimumZ = minOf(minimumZ, offsetZ + quad.minimumV)
                    maximumZ = maxOf(maximumZ, offsetZ + quad.maximumVExclusive)
                }

                DistantFaceDirection.NORTH,
                DistantFaceDirection.SOUTH,
                -> {
                    minimumX = minOf(minimumX, offsetX + quad.minimumU)
                    maximumX = maxOf(maximumX, offsetX + quad.maximumUExclusive)
                    minimumY = minOf(minimumY, quad.minimumV)
                    maximumY = maxOf(maximumY, quad.maximumVExclusive)
                    minimumZ = minOf(minimumZ, offsetZ + quad.plane)
                    maximumZ = maxOf(maximumZ, offsetZ + quad.plane)
                }

                DistantFaceDirection.WEST,
                DistantFaceDirection.EAST,
                -> {
                    minimumX = minOf(minimumX, offsetX + quad.plane)
                    maximumX = maxOf(maximumX, offsetX + quad.plane)
                    minimumY = minOf(minimumY, quad.minimumV)
                    maximumY = maxOf(maximumY, quad.maximumVExclusive)
                    minimumZ = minOf(minimumZ, offsetZ + quad.minimumU)
                    maximumZ = maxOf(maximumZ, offsetZ + quad.maximumUExclusive)
                }
            }
        }
        return TerrainArtifactBounds(
            TerrainArtifactCoordinate(minimumX, minimumY, minimumZ),
            TerrainArtifactCoordinate(maximumX, maximumY, maximumZ),
        )
    }

    private const val BYTES_PER_VERTEX = 24
    private const val BYTES_PER_QUAD = BYTES_PER_VERTEX * 4
    private const val UP_NORMAL = 1
    private const val NORTH_NORMAL = 2
    private const val SOUTH_NORMAL = 3
    private const val WEST_NORMAL = 4
    private const val EAST_NORMAL = 5
    private const val DOWN_NORMAL = 6
}
