/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.framebuffer.world.overlay.overlays.simple

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureTransparencies
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.PackedUV
import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleOverlayTextureCoordinateTest {
    @Test
    fun `simple overlays stay within the diffuse page of a material texture array`() {
        val texture = PageTexture(uScale = 0.5f, vScale = 0.25f)

        val start = physicalOverlayUv(texture, Vec2f(0.0f, 0.0f))
        val end = physicalOverlayUv(texture, Vec2f(1.0f, 1.0f))

        assertEquals(Vec2f(0.0f, 0.0f), start)
        assertEquals(Vec2f(0.5f, 0.25f), end)
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
