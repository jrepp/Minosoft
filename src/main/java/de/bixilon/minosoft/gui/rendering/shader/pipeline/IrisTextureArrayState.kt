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
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager

/**
 * Adapter state for Minosoft's multi-array texture storage.
 *
 * Iris normally publishes one `atlasSize` or `gtextureSize` value for the
 * texture bound to a vanilla draw. Minosoft can select a different array
 * bucket per vertex, so transformed shader stages index this immutable table
 * with their actual texture-array value.
 */
data class IrisTextureArrayState(
    val sizes: List<Vec2i>,
) {
    init {
        require(sizes.size == TextureManager.SHADER_TEXTURE_ARRAY_SIZE) {
            "Iris texture-array state requires ${TextureManager.SHADER_TEXTURE_ARRAY_SIZE} slots, found ${sizes.size}"
        }
        require(sizes.all { it.x >= 0 && it.y >= 0 }) {
            "Iris texture-array dimensions must be non-negative: $sizes"
        }
    }

    fun uploadTo(native: NativeShader, uniforms: Set<String>): Int {
        if (UNIFORM !in uniforms) return 0
        var uploads = 0
        for ((index, size) in sizes.withIndex()) {
            val name = "$UNIFORM[$index]"
            if (!native.hasUniform(name)) continue
            native.setVec2i(name, size)
            uploads++
        }
        return uploads
    }

    fun diagnostics(): Map<Int, String> = buildMap {
        sizes.forEachIndexed { index, size ->
            if (size.x > 0 && size.y > 0) put(index, "${size.x}x${size.y}")
        }
    }

    companion object {
        const val UNIFORM = "uTextureSizes"
        val SUPPORTED_UNIFORMS = setOf(UNIFORM)

        fun capture(textures: TextureManager) = IrisTextureArrayState(textures.shaderTextureSizes())
    }
}
