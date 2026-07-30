/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.loader

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.readTexture

/**
 * Vanilla atlas `paletted_permutations` semantics used by armor trims. Source
 * alpha is retained and multiplied by palette alpha; an unmapped opaque source
 * color becomes transparent instead of receiving a plausible fallback color.
 */
object PalettedTexturePermutation {
    fun apply(source: TextureBuffer, key: TextureBuffer, palette: TextureBuffer): RGBA8Buffer {
        require(key.size == palette.size) {
            "Palette key size ${key.size} does not match palette size ${palette.size}."
        }
        val colors = HashMap<Int, de.bixilon.minosoft.data.text.formatting.color.RGBAColor>(
            key.size.x * key.size.y,
        )
        for (y in 0 until key.size.y) {
            for (x in 0 until key.size.x) {
                val sourceColor = key.getRGBA(x, y)
                if (sourceColor.alpha == 0) continue
                colors[sourceColor.rgb] = palette.getRGBA(x, y)
            }
        }

        val result = RGBA8Buffer(source.size)
        for (y in 0 until source.size.y) {
            for (x in 0 until source.size.x) {
                val pixel = source.getRGBA(x, y)
                if (pixel.alpha == 0) {
                    result.setRGBA(x, y, 0, 0, 0, 0)
                    continue
                }
                val replacement = colors[pixel.rgb]
                if (replacement == null) {
                    result.setRGBA(x, y, 0, 0, 0, 0)
                    continue
                }
                result.setRGBA(
                    x,
                    y,
                    replacement.red,
                    replacement.green,
                    replacement.blue,
                    pixel.alpha * replacement.alpha / 0xFF,
                )
            }
        }
        return result
    }
}

class PalettedTexturePermutationLoader(
    private val source: ResourceLocation,
    private val key: ResourceLocation,
    private val palette: ResourceLocation,
) : TextureLoader {
    override fun load(context: RenderContext): TextureLoaderResult {
        val assets = context.session.assets
        val sourceBuffer = assets[source].readTexture()
        val keyBuffer = assets[key].readTexture()
        val paletteBuffer = assets[palette].readTexture()
        return TextureLoaderResult(
            PalettedTexturePermutation.apply(sourceBuffer, keyBuffer, paletteBuffer),
            null,
        )
    }

    override fun toString() = "$source[$key->$palette]"
}
