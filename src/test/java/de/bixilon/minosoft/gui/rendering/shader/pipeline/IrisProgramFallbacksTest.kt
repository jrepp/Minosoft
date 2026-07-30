/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrisProgramFallbacksTest {
    @Test
    fun `terrain materials select their specific program before generic terrain`() {
        assertEquals(
            listOf(
                "gbuffers_terrain_solid",
                "gbuffers_terrain",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.terrain(TerrainMaterialClass.OPAQUE),
        )
        assertEquals(
            listOf(
                "gbuffers_terrain_cutout",
                "gbuffers_terrain",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.terrain(TerrainMaterialClass.CUTOUT),
        )
        assertEquals(
            listOf(
                "gbuffers_water",
                "gbuffers_terrain",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.terrain(TerrainMaterialClass.TRANSLUCENT),
        )
    }

    @Test
    fun `shadow materials select solid and cutout programs before generic shadow`() {
        assertEquals(
            listOf("shadow_solid", "shadow"),
            IrisProgramFallbacks.shadow(TerrainMaterialClass.OPAQUE),
        )
        assertEquals(
            listOf("shadow_cutout", "shadow"),
            IrisProgramFallbacks.shadow(TerrainMaterialClass.CUTOUT),
        )
        assertEquals(
            listOf("shadow"),
            IrisProgramFallbacks.shadow(TerrainMaterialClass.TRANSLUCENT),
        )
    }

    @Test
    fun `distant terrain selects its dedicated shadow root`() {
        val distant = SceneShaderContract(
            SceneProgramFamily.DISTANT_TERRAIN,
            SceneVertexAbi.DISTANT_TERRAIN,
            SceneStateAbi.DISTANT_TERRAIN,
        )

        assertEquals(listOf("dh_shadow"), IrisProgramFallbacks.shadowScene(distant))
    }

    @Test
    fun `graph semantic overrides a generic host shader family`() {
        val generic = SceneShaderContract(
            SceneProgramFamily.TEXTURED_LIT,
            SceneVertexAbi.POSITION_TEXTURE,
            SceneStateAbi.GENERIC_TEXTURE,
        )

        assertEquals(
            "gbuffers_entities",
            IrisProgramFallbacks.scene(PipelineSemantic.ENTITIES, generic).first(),
        )
        assertEquals(
            "gbuffers_block",
            IrisProgramFallbacks.scene(PipelineSemantic.BLOCK_ENTITIES, generic).first(),
        )
        assertEquals(
            "gbuffers_hand",
            IrisProgramFallbacks.scene(PipelineSemantic.HAND, generic).first(),
        )
    }

    @Test
    fun `physical block features keep block programs inside entity passes`() {
        val blockFeature = SceneShaderContract(
            SceneProgramFamily.BLOCK,
            SceneVertexAbi.BLOCK_FEATURE,
            SceneStateAbi.BLOCK,
        )

        assertEquals(
            "gbuffers_block_translucent",
            IrisProgramFallbacks.scene(
                PipelineSemantic.ENTITIES_TRANSLUCENT,
                blockFeature,
                separateEntityDraws = true,
            ).first(),
        )
    }

    @Test
    fun `item block meshes can request entity programs without changing their physical ABI`() {
        val itemFeature = SceneShaderContract(
            SceneProgramFamily.ENTITY,
            SceneVertexAbi.BLOCK_FEATURE,
            SceneStateAbi.BLOCK,
        )

        assertEquals(
            "gbuffers_entities_translucent",
            IrisProgramFallbacks.scene(
                PipelineSemantic.ENTITIES_TRANSLUCENT,
                itemFeature,
                separateEntityDraws = true,
            ).first(),
        )
    }

    @Test
    fun `translucent particles use the translucent program before opaque fallback`() {
        val particle = SceneShaderContract(
            SceneProgramFamily.PARTICLE,
            SceneVertexAbi.PARTICLE_POINT,
            SceneStateAbi.PARTICLE,
        )

        assertEquals(
            listOf(
                "gbuffers_particles_translucent",
                "gbuffers_particles",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.scene(PipelineSemantic.PARTICLES_TRANSLUCENT, particle),
        )
    }

    @Test
    fun `separate translucent entity programs retain their standard fallbacks`() {
        val generic = SceneShaderContract(
            SceneProgramFamily.TEXTURED_LIT,
            SceneVertexAbi.POSITION_TEXTURE,
            SceneStateAbi.GENERIC_TEXTURE,
        )

        assertEquals(
            listOf(
                "gbuffers_entities_translucent",
                "gbuffers_entities",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.scene(
                PipelineSemantic.ENTITIES_TRANSLUCENT,
                generic,
                separateEntityDraws = true,
            ),
        )
        assertEquals(
            "gbuffers_entities",
            IrisProgramFallbacks.scene(
                PipelineSemantic.ENTITIES_TRANSLUCENT,
                generic,
                separateEntityDraws = false,
            ).first(),
        )
        assertEquals(
            listOf(
                "gbuffers_block_translucent",
                "gbuffers_block",
                "gbuffers_terrain",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.scene(
                PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT,
                generic,
                separateEntityDraws = true,
            ),
        )
    }

    @Test
    fun `specialized scene families retain pinned Iris 1_7_2 fallbacks`() {
        fun contract(family: SceneProgramFamily) = SceneShaderContract(
            family,
            SceneVertexAbi.SKELETAL,
            SceneStateAbi.SKELETAL_TINTED,
        )

        assertEquals(
            listOf("gbuffers_line", "gbuffers_basic"),
            IrisProgramFallbacks.scene(PipelineSemantic.WORLD_OVERLAY, contract(SceneProgramFamily.LINE)),
        )
        assertEquals(
            listOf("gbuffers_basic"),
            IrisProgramFallbacks.scene(PipelineSemantic.ENTITIES, contract(SceneProgramFamily.LEASH)),
        )
        assertEquals(
            listOf("gbuffers_beaconbeam", "gbuffers_textured", "gbuffers_basic"),
            IrisProgramFallbacks.scene(PipelineSemantic.BLOCK_ENTITIES_TRANSLUCENT, contract(SceneProgramFamily.BEACON_BEAM)),
        )
        assertEquals(
            listOf(
                "gbuffers_lightning",
                "gbuffers_entities",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.scene(PipelineSemantic.ENTITIES_TRANSLUCENT, contract(SceneProgramFamily.LIGHTNING)),
        )
        assertEquals(
            listOf("gbuffers_spidereyes", "gbuffers_textured", "gbuffers_basic"),
            IrisProgramFallbacks.scene(PipelineSemantic.ENTITIES_TRANSLUCENT, contract(SceneProgramFamily.ENTITY_EYES)),
        )
        assertEquals(
            listOf("gbuffers_armor_glint", "gbuffers_textured", "gbuffers_basic"),
            IrisProgramFallbacks.scene(PipelineSemantic.ENTITIES, contract(SceneProgramFamily.ARMOR_GLINT)),
        )
        assertEquals(
            listOf(
                "gbuffers_hand_water",
                "gbuffers_hand",
                "gbuffers_textured_lit",
                "gbuffers_textured",
                "gbuffers_basic",
            ),
            IrisProgramFallbacks.scene(PipelineSemantic.HAND, contract(SceneProgramFamily.HAND_WATER)),
        )
    }

    @Test
    fun `selection follows fallback order independent of pack declaration order`() {
        val generic = source("gbuffers_terrain")
        val specific = source("gbuffers_terrain_cutout")

        assertEquals(
            specific,
            IrisProgramFallbacks.select(
                listOf(generic, specific),
                IrisProgramFallbacks.terrain(TerrainMaterialClass.CUTOUT),
            ),
        )
    }

    @Test
    fun `only roots reachable from retained scene producers are executable`() {
        assertTrue(IrisProgramFallbacks.supportsSceneProgram("gbuffers_entities"))
        assertTrue(IrisProgramFallbacks.supportsSceneProgram("gbuffers_hand_water"))
        assertFalse(IrisProgramFallbacks.supportsSceneProgram("gbuffers_item"))
        assertFalse(IrisProgramFallbacks.supportsSceneProgram("gbuffers_entities_glowing"))
        assertTrue(IrisProgramFallbacks.supportsSceneProgram("dh_terrain"))
    }

    private fun source(name: String) = ShaderProgramSource(
        name = name,
        phase = ShaderProgramPhase.TERRAIN,
        vertex = "void main() {}",
        fragment = "void main() {}",
        uniforms = emptySet(),
        samplers = emptySet(),
    )
}
