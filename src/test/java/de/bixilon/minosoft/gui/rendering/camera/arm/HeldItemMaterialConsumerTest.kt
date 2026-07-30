/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.camera.arm

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureTransparencies
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HeldItemMaterialConsumerTest {
    @Test
    fun `opaque and cutout item faces retain hand while translucent faces retain hand water`() {
        val opaque = RecordingConsumer()
        val translucent = RecordingConsumer()
        val consumer = HeldItemMaterialConsumer(opaque, translucent)

        TextureTransparencies.entries.forEach { transparency ->
            consumer.addQuad(
                Vec3f.EMPTY,
                FaceVertexData(12),
                PackedUVArray(),
                texture(transparency),
                0xFF,
                ChatColors.WHITE.rgb(),
                IntArray(4),
            )
        }

        assertEquals(
            listOf(TextureTransparencies.OPAQUE, TextureTransparencies.TRANSPARENT),
            opaque.transparencies,
        )
        assertEquals(listOf(TextureTransparencies.TRANSLUCENT), translucent.transparencies)
    }

    private class RecordingConsumer : BlockVertexConsumer {
        val transparencies = mutableListOf<TextureTransparencies>()

        override fun addQuad(
            offset: Vec3f,
            positions: FaceVertexData,
            uv: PackedUVArray,
            texture: ShaderTexture,
            light: Int,
            tint: RGBColor,
            ao: IntArray,
        ) {
            transparencies += texture.transparency
        }
    }

    private fun texture(transparency: TextureTransparencies) = object : ShaderTexture {
        override val shaderId = 1
        override val transparency = transparency

        override fun transformUV(uv: Vec2f) = uv
        override fun transformUV(u: Float, v: Float) = PackedUV(u, v)
        override fun transformU(u: Float) = u
        override fun transformV(v: Float) = v
        override fun transformUV(uv: PackedUV) = uv
    }
}
