/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.models.baked

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.models.ModelTestUtil.bake
import de.bixilon.minosoft.gui.rendering.models.baked.BakedModelTestUtil.createTextureManager
import de.bixilon.minosoft.gui.rendering.models.block.BlockModel
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement
import de.bixilon.minosoft.gui.rendering.models.block.element.face.ModelFace
import de.bixilon.minosoft.gui.rendering.models.block.state.apply.SingleBlockStateApply
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.annotations.Test

@Test(groups = ["models"])
class ContentFidelityBakeTest {
    private fun bake(face: ModelFace, ambientOcclusion: Boolean = true, y: Int = 0) =
        SingleBlockStateApply(
            BlockModel(
                elements = listOf(ModelElement(Vec3f(0.0f), Vec3f(1.0f), faces = mapOf(Directions.NORTH to face))),
                textures = mapOf("test" to minecraft("block/test").texture()),
                ambientOcclusion = ambientOcclusion,
            ),
            y = y,
        ).bake(createTextureManager("block/test"))!!

    fun `boundary geometry without cullface remains uncullable`() {
        val face = bake(ModelFace("#test", null, 0)).faces[Directions.NORTH.ordinal].single()

        assertNotNull(face.properties)
        assertNull(face.cullFace)
    }

    fun `cullface rotates with blockstate`() {
        val face = bake(ModelFace("#test", null, 0, cullFace = Directions.NORTH), y = 1)
            .faces[Directions.EAST.ordinal].single()

        assertEquals(face.cullFace, Directions.EAST)
    }

    fun `model ambient occlusion is retained per baked face`() {
        val face = bake(ModelFace("#test", null, 0), ambientOcclusion = false)
            .faces[Directions.NORTH.ordinal].single()

        assertFalse(face.ambientOcclusion)
    }
}
