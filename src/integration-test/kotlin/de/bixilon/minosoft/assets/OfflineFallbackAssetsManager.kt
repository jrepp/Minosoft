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

package de.bixilon.minosoft.assets

import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.data.registries.identified.Namespaces
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import javax.imageio.ImageIO

/** Supplies inert bytes for renderer-bootstrap keys that are irrelevant to the focused assertions. */
class OfflineFallbackAssetsManager : AssetsManager {
    override val loaded: Boolean = true

    override fun get(path: ResourceLocation): InputStream {
        return getOrNull(path) ?: throw FileNotFoundException("Can not find offline stand-in asset $path")
    }

    override fun getOrNull(path: ResourceLocation): InputStream? {
        return bytes(path)?.let(::ByteArrayInputStream)
    }

    private fun bytes(path: ResourceLocation): ByteArray? {
        if (path.namespace != Namespaces.DEFAULT) return null
        return when {
            path.path.startsWith(ITEM_MODEL_PREFIX) && path.path.endsWith(JSON_SUFFIX) -> ITEM_MODEL
            path.path.endsWith(PNG_SUFFIX) &&
                (path.path.startsWith(COLOR_MAP_PREFIX) || path.path == CLOUDS_TEXTURE) -> WHITE_MATRIX_TEXTURE
            path.path.startsWith(TEXTURE_PREFIX) && path.path.endsWith(PNG_SUFFIX) -> WHITE_TEXTURE
            else -> null
        }
    }

    override fun load(latch: AbstractLatch?) = Unit

    override fun unload() = Unit

    override fun getAssetsManager(path: ResourceLocation): AssetsManager? {
        return if (bytes(path) == null) null else this
    }

    private companion object {
        const val ITEM_MODEL_PREFIX = "models/item/"
        const val TEXTURE_PREFIX = "textures/"
        const val COLOR_MAP_PREFIX = "textures/colormap/"
        const val CLOUDS_TEXTURE = "textures/environment/clouds.png"
        const val JSON_SUFFIX = ".json"
        const val PNG_SUFFIX = ".png"

        val ITEM_MODEL = """{"parent":"minecraft:item/generated","textures":{"layer0":"minosoft:white"}}""".toByteArray()
        val WHITE_TEXTURE = requireNotNull(OfflineFallbackAssetsManager::class.java.classLoader.getResourceAsStream("assets/minosoft/textures/white.png")) {
            "Bundled Minosoft white texture is missing."
        }.use { it.readAllBytes() }
        val WHITE_MATRIX_TEXTURE = ByteArrayOutputStream().use { output ->
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
            val pixels = IntArray(256 * 256) { -1 }
            image.setRGB(0, 0, 256, 256, pixels, 0, 256)
            check(ImageIO.write(image, "png", output)) { "PNG encoder is unavailable." }
            output.toByteArray()
        }
    }
}
