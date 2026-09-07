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

package de.bixilon.minosoft.gui.rendering.textures

import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.readTexture
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertEquals

class TextureFallbackTest {
    private fun encode(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam
                // Adam7 forces the ImageIO fallback, including for palette PNGs.
                parameters.progressiveMode = ImageWriteParam.MODE_DEFAULT
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    @Test
    fun `indexed fallback resolves palette colors and transparent entries`() {
        val palette = IndexColorModel(4, 3,
            byteArrayOf(190.toByte(), 50, 255.toByte()),
            byteArrayOf(120, 170.toByte(), 0),
            byteArrayOf(70, 80, 255.toByte()), byteArrayOf(255.toByte(), 255.toByte(), 0))
        val image = BufferedImage(3, 1, BufferedImage.TYPE_BYTE_BINARY, palette)
        for (x in 0..2) image.raster.setSample(x, 0, 0, x)
        val texture = ByteArrayInputStream(encode(image)).readTexture()
        for (x in 0..2) assertEquals(image.getRGB(x, 0), texture.getRGBA(x, 0).argb, "palette entry $x")
    }

    @Test
    fun `grayscale fallback preserves encoded intensity without color space conversion`() {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_BYTE_GRAY)
        image.raster.setSample(0, 0, 0, 148)
        image.raster.setSample(1, 0, 0, 195)
        val texture = ByteArrayInputStream(encode(image)).readTexture()
        for ((x, expected) in listOf(148, 195).withIndex()) {
            assertEquals(expected, texture.getR(x, 0))
            assertEquals(expected, texture.getG(x, 0))
            assertEquals(expected, texture.getB(x, 0))
            assertEquals(255, texture.getA(x, 0))
        }
    }

    @Test
    fun `sixteen bit grayscale fallback scales samples to eight bit`() {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_USHORT_GRAY)
        image.raster.setSample(0, 0, 0, 32768)
        image.raster.setSample(1, 0, 0, 65535)
        val texture = ByteArrayInputStream(encode(image)).readTexture()
        assertEquals(128, texture.getR(0, 0))
        assertEquals(255, texture.getR(1, 0))
    }
}
