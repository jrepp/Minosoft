/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.mesher.fluid

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureTransparencies
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray
import kotlin.test.Test
import kotlin.test.assertEquals

class FluidTextureCoordinateTest {
    @Test
    fun `fluid faces stay within the diffuse page of a material texture array`() {
        val texture = PageTexture(uScale = 0.5f, vScale = 0.25f)
        val source = PackedUVArray(
            floatArrayOf(
                PackedUV(0.0f, 0.0f).raw,
                PackedUV(1.0f, 0.0f).raw,
                PackedUV(1.0f, 1.0f).raw,
                PackedUV(0.0f, 1.0f).raw,
            ),
        )

        val transformed = FluidSectionMesher.transformFluidUv(texture, source)

        assertEquals(0.0f, transformed[0].u)
        assertEquals(0.0f, transformed[0].v)
        assertEquals(0.5f, transformed[2].u, 1.0e-3f)
        assertEquals(0.25f, transformed[2].v, 1.0e-3f)
    }

    private class PageTexture(
        private val uScale: Float,
        private val vScale: Float,
    ) : ShaderTexture {
        override val shaderId = 0
        override val transparency = TextureTransparencies.TRANSLUCENT

        override fun transformUV(uv: Vec2f) = Vec2f(uv.x * uScale, uv.y * vScale)
        override fun transformUV(u: Float, v: Float) = PackedUV(u * uScale, v * vScale)
        override fun transformU(u: Float) = u * uScale
        override fun transformV(v: Float) = v * vScale
        override fun transformUV(uv: PackedUV) = transformUV(uv.u, uv.v)
    }
}
