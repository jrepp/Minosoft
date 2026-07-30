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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.player

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.assets.util.InputStreamUtil.readJson
import de.bixilon.minosoft.data.entities.entities.player.SkinParts
import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayerModelMeshBuilderTest {
    @Test
    fun `dynamic player skin bake keeps logical uv coordinates`() {
        val uv = Vec2f(0.625f, 0.375f)
        assertEquals(uv, PlayerSkinUvTexture.transformUV(uv))
        assertEquals(uv.x, PlayerSkinUvTexture.transformU(uv.x))
        assertEquals(uv.y, PlayerSkinUvTexture.transformV(uv.y))

        val packed = PlayerSkinUvTexture.transformUV(uv.x, uv.y)
        assertEquals(PackedUV(uv), packed)
        assertEquals(packed, PlayerSkinUvTexture.transformUV(packed))
    }

    @Test
    fun `ETF nose paths use isolated feature tags`() {
        assertEquals(
            PlayerModelMeshBuilder.ETF_VILLAGER_NOSE_PART,
            PlayerModelMeshBuilder.encodedPart("head.etf_villager_nose"),
        )
        assertEquals(
            PlayerModelMeshBuilder.ETF_TEXTURED_NOSE_PART,
            PlayerModelMeshBuilder.encodedPart("head.etf_textured_nose"),
        )
        assertEquals(SkinParts.HAT.ordinal + 1, PlayerModelMeshBuilder.encodedPart("head.hat"))
        assertEquals(0, PlayerModelMeshBuilder.encodedPart("head"))
    }

    @Test
    fun `both player models carry ETF nose geometry`() {
        for (name in listOf("wide", "slim")) {
            val stream = assertNotNull(
                javaClass.classLoader.getResourceAsStream("assets/minecraft/models/entities/player/$name.smodel"),
            )
            val model: SkeletalModel = stream.readJson()
            val head = assertNotNull(model.elements["head"])
            assertNotNull(head.children["etf_villager_nose"])
            assertNotNull(head.children["etf_textured_nose"])
            assertEquals(8, model.textures.getValue(PlayerRenderer.NOSE).resolution.x)
        }
    }

    @Test
    fun `player shader keeps glint masks and opted in ETF base alpha transparent`() {
        val source = assertNotNull(
            javaClass.classLoader.getResourceAsStream("assets/minosoft/rendering/shader/entities/player/player.fsh"),
        ).bufferedReader().use { it.readText() }
        assertTrue("uniform uint uGlintTexture;" in source)
        assertTrue("uniform bool uAllowBaseTransparency;" in source)
        assertTrue("else if (uAllowBaseTransparency)" in source)
        assertTrue("texel.a <= 0.0f" in source)
        assertTrue("sampleGlint" in source)
    }
}
