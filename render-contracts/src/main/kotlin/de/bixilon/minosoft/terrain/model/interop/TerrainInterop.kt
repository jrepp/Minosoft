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

package de.bixilon.minosoft.terrain.model.interop

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain

enum class TerrainProviderCapability {
    COMPACT_VERTEX_ENCODING,
    REGION_BATCHING,
    FACE_SEGMENTATION,
    CPU_CONNECTIVITY,
    AUXILIARY_VIEWS,
    DISTANT_WATER,
}

enum class TerrainMaterialClass {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT,
    DISTANT_WATER,
    EMISSIVE_ADDITIVE,
}

enum class TerrainUploadCapability {
    BUFFER_UPDATE,
    PERSISTENT_MAPPED_STAGING,
    MULTI_DRAW,
}

enum class TerrainLightingSemantics {
    BLOCK_AND_SKY,
    RESOLVED_COLOR,
}

enum class TerrainTintSemantics {
    NONE,
    BIOME_INPUT,
    RESOLVED_COLOR,
}

class TerrainInteropDescriptor(
    val providerId: String,
    domains: Set<TerrainDomain>,
    capabilities: Set<TerrainProviderCapability>,
    materials: Set<TerrainMaterialClass>,
    val semanticVertexLayoutId: String,
    physicalLayoutIds: Set<String>,
    supportedViews: Set<String>,
    uploadCapabilities: Set<TerrainUploadCapability>,
    shaderInputs: Set<String>,
    val lightingSemantics: TerrainLightingSemantics,
    val tintSemantics: TerrainTintSemantics,
    foreignProfileIds: Set<String> = emptySet(),
) {
    val domains: Set<TerrainDomain> = java.util.Set.copyOf(domains)
    val capabilities: Set<TerrainProviderCapability> = java.util.Set.copyOf(capabilities)
    val materials: Set<TerrainMaterialClass> = java.util.Set.copyOf(materials)
    val physicalLayoutIds: Set<String> = java.util.Set.copyOf(physicalLayoutIds)
    val supportedViews: Set<String> = java.util.Set.copyOf(supportedViews)
    val uploadCapabilities: Set<TerrainUploadCapability> = java.util.Set.copyOf(uploadCapabilities)
    val shaderInputs: Set<String> = java.util.Set.copyOf(shaderInputs)
    val foreignProfileIds: Set<String> = java.util.Set.copyOf(foreignProfileIds)

    init {
        require(providerId.isNotBlank()) { "Terrain provider ID must not be blank" }
        require(this.domains.isNotEmpty()) { "Terrain provider must declare at least one domain" }
        require(this.materials.isNotEmpty()) { "Terrain provider must declare at least one material" }
        require(semanticVertexLayoutId.isNotBlank()) { "Terrain semantic vertex layout ID must not be blank" }
        require(this.physicalLayoutIds.isNotEmpty()) {
            "Terrain provider must declare at least one physical layout"
        }
        require(this.physicalLayoutIds.none(String::isBlank)) { "Terrain physical layout IDs must not be blank" }
        require(this.supportedViews.isNotEmpty()) { "Terrain provider must declare at least one view" }
        require(this.supportedViews.none(String::isBlank)) { "Terrain view IDs must not be blank" }
        require(this.shaderInputs.none(String::isBlank)) { "Terrain shader inputs must not be blank" }
        require(this.foreignProfileIds.none(String::isBlank)) { "Terrain foreign profile IDs must not be blank" }
    }
}

interface TerrainProvider : AutoCloseable {
    val generation: Long
    val descriptor: TerrainInteropDescriptor
}

interface NearTerrainProvider : TerrainProvider

interface DistantTerrainProvider : TerrainProvider
