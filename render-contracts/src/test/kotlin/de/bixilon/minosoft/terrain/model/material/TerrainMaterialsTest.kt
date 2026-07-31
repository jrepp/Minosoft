/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.model.material

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class TerrainMaterialsTest {
    @Test
    fun `semantic IDs are normalized and table collections are immutable snapshots`() {
        assertThrows<IllegalArgumentException> { TerrainSemanticMaterialId("Minecraft:Stone") }
        assertThrows<IllegalArgumentException> { TerrainSemanticMaterialId(" minecraft:stone") }

        val source = mutableListOf(TerrainSemanticMaterialId("minecraft:stone"))
        val metadata = metadata(semanticIds = source)
        source += TerrainSemanticMaterialId("minecraft:dirt")

        assertEquals(listOf(TerrainSemanticMaterialId("minecraft:stone")), metadata.semanticMaterialIds)
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (metadata.semanticMaterialIds as MutableList<TerrainSemanticMaterialId>)
                .add(TerrainSemanticMaterialId("minecraft:grass_block"))
        }
    }

    @Test
    fun `material and geometry generations must not be negative`() {
        assertThrows<IllegalArgumentException> { metadata(atlasGeneration = -1L) }
        assertThrows<IllegalArgumentException> {
            reuseInputs(vertexAttributeGeneration = -1L)
        }
        assertThrows<IllegalArgumentException> {
            reuseInputs(bakedTintGeneration = -1L)
        }
    }

    @Test
    fun `only compatible indirect table updates reuse geometry`() {
        val first = metadata(
            generation = 4L,
            atlasGeneration = 10L,
            animationGeneration = 6L,
        )
        val tableOnlyUpdate = metadata(
            generation = 5L,
            atlasGeneration = 11L,
            animationGeneration = 7L,
        )
        assertEquals(
            TerrainGeometryReuseDecision.REUSE_TABLE_ONLY,
            tableOnlyUpdate.geometryReuseFrom(first),
        )

        val shapeUpdate = metadata(
            generation = 6L,
            reuseInputs = reuseInputs(modelShapeGeneration = 4L),
        )
        assertEquals(
            TerrainGeometryReuseDecision.REBUILD_MODEL_SHAPE_CHANGED,
            shapeUpdate.geometryReuseFrom(tableOnlyUpdate),
        )

        val directMaterials = metadata(
            generation = 7L,
            reuseInputs = reuseInputs(materialIndirection = false),
        )
        assertEquals(
            TerrainGeometryReuseDecision.REBUILD_WITHOUT_MATERIAL_INDIRECTION,
            directMaterials.geometryReuseFrom(shapeUpdate),
        )
    }

    private fun metadata(
        generation: Long = 5L,
        semanticIds: Collection<TerrainSemanticMaterialId> =
            listOf(TerrainSemanticMaterialId("minecraft:stone")),
        atlasGeneration: Long = 11L,
        animationGeneration: Long = 7L,
        reuseInputs: TerrainGeometryReuseInputs = reuseInputs(),
    ) = TerrainMaterialTableMetadata(
        generation = generation,
        semanticMaterialIds = semanticIds,
        modelGeneration = 3L,
        atlasGeneration = atlasGeneration,
        tintGeneration = 2L,
        animationGeneration = animationGeneration,
        geometryReuseInputs = reuseInputs,
    )

    private fun reuseInputs(
        materialIndirection: Boolean = true,
        modelShapeGeneration: Long = 3L,
        bakedTintGeneration: Long? = null,
        vertexAttributeGeneration: Long = 9L,
    ) = TerrainGeometryReuseInputs(
        materialIndirection = materialIndirection,
        modelShapeGeneration = modelShapeGeneration,
        faceCullingGeneration = 3L,
        bakedTintGeneration = bakedTintGeneration,
        vertexAttributeGeneration = vertexAttributeGeneration,
    )
}
