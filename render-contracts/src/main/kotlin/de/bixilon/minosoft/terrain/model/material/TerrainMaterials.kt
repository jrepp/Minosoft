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

package de.bixilon.minosoft.terrain.model.material

@JvmInline
value class TerrainSemanticMaterialId(
    val value: String,
) : Comparable<TerrainSemanticMaterialId> {
    init {
        require(value.matches(NORMALIZED_ID)) {
            "Terrain semantic material ID must be normalized"
        }
    }

    override fun compareTo(other: TerrainSemanticMaterialId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    private companion object {
        val NORMALIZED_ID = Regex("[a-z0-9_.-]+(?::[a-z0-9/._-]+)?")
    }
}

data class TerrainGeometryReuseInputs(
    val materialIndirection: Boolean,
    val modelShapeGeneration: Long,
    val faceCullingGeneration: Long,
    val bakedTintGeneration: Long?,
    val vertexAttributeGeneration: Long,
) {
    init {
        requireGeneration("model-shape", modelShapeGeneration)
        requireGeneration("face-culling", faceCullingGeneration)
        require(bakedTintGeneration == null || bakedTintGeneration >= 0L) {
            "Terrain baked-tint generation must not be negative"
        }
        requireGeneration("vertex-attribute", vertexAttributeGeneration)
    }

    fun reuseFrom(previous: TerrainGeometryReuseInputs): TerrainGeometryReuseDecision = when {
        !previous.materialIndirection || !materialIndirection ->
            TerrainGeometryReuseDecision.REBUILD_WITHOUT_MATERIAL_INDIRECTION

        previous.modelShapeGeneration != modelShapeGeneration ->
            TerrainGeometryReuseDecision.REBUILD_MODEL_SHAPE_CHANGED

        previous.faceCullingGeneration != faceCullingGeneration ->
            TerrainGeometryReuseDecision.REBUILD_FACE_CULLING_CHANGED

        previous.bakedTintGeneration != bakedTintGeneration ->
            TerrainGeometryReuseDecision.REBUILD_BAKED_TINT_CHANGED

        previous.vertexAttributeGeneration != vertexAttributeGeneration ->
            TerrainGeometryReuseDecision.REBUILD_VERTEX_ATTRIBUTES_CHANGED

        else -> TerrainGeometryReuseDecision.REUSE_TABLE_ONLY
    }
}

enum class TerrainGeometryReuseDecision {
    REUSE_TABLE_ONLY,
    REBUILD_WITHOUT_MATERIAL_INDIRECTION,
    REBUILD_MODEL_SHAPE_CHANGED,
    REBUILD_FACE_CULLING_CHANGED,
    REBUILD_BAKED_TINT_CHANGED,
    REBUILD_VERTEX_ATTRIBUTES_CHANGED,
}

class TerrainMaterialTableMetadata(
    val generation: Long,
    semanticMaterialIds: Collection<TerrainSemanticMaterialId>,
    val modelGeneration: Long,
    val atlasGeneration: Long,
    val tintGeneration: Long,
    val animationGeneration: Long,
    val geometryReuseInputs: TerrainGeometryReuseInputs,
) {
    val semanticMaterialIds: List<TerrainSemanticMaterialId>

    init {
        requireGeneration("material-table", generation)
        requireGeneration("model", modelGeneration)
        requireGeneration("atlas", atlasGeneration)
        requireGeneration("tint", tintGeneration)
        requireGeneration("animation", animationGeneration)
        require(semanticMaterialIds.isNotEmpty()) {
            "Terrain material table must contain at least one semantic material ID"
        }
        require(semanticMaterialIds.toSet().size == semanticMaterialIds.size) {
            "Terrain material table contains duplicate semantic material IDs"
        }
        this.semanticMaterialIds = java.util.List.copyOf(semanticMaterialIds.sorted())
    }

    fun geometryReuseFrom(previous: TerrainMaterialTableMetadata): TerrainGeometryReuseDecision =
        geometryReuseInputs.reuseFrom(previous.geometryReuseInputs)
}

private fun requireGeneration(name: String, generation: Long) {
    require(generation >= 0L) { "Terrain $name generation must not be negative" }
}
