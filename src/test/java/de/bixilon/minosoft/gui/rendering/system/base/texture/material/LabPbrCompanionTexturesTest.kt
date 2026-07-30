/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.material

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.assets.AssetsManager
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoaderResult
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.file.PNGTextureLoader
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LabPbrCompanionTexturesTest {
    @Test
    fun `file textures resolve aligned LabPBR names without recursion`() {
        val diffuse = PNGTextureLoader(ResourceLocation("fixture", "textures/block/slate.png"))

        assertEquals(
            ResourceLocation("fixture", "textures/block/slate_n.png"),
            LabPbrCompanionTextures.resource(diffuse, LabPbrCompanionTextures.Kind.NORMAL),
        )
        assertEquals(
            ResourceLocation("fixture", "textures/block/slate_s.png"),
            LabPbrCompanionTextures.resource(diffuse, LabPbrCompanionTextures.Kind.SPECULAR),
        )
        assertNull(
            LabPbrCompanionTextures.resource(
                PNGTextureLoader(ResourceLocation("fixture", "textures/block/slate_n.png")),
                LabPbrCompanionTextures.Kind.NORMAL,
            ),
        )
        assertNull(
            LabPbrCompanionTextures.resource(
                object : TextureLoader {
                    override fun load(context: RenderContext): TextureLoaderResult =
                        error("not used")
                },
                LabPbrCompanionTextures.Kind.NORMAL,
            ),
        )
    }

    @Test
    fun `missing companions use LabPBR neutral channel values`() {
        val normal = LabPbrCompanionTextures.neutral(
            LabPbrCompanionTextures.Kind.NORMAL,
            Vec2i(2, 2),
        )
        val specular = LabPbrCompanionTextures.neutral(
            LabPbrCompanionTextures.Kind.SPECULAR,
            Vec2i(2, 2),
        )

        assertEquals(128, normal.getR(1, 1))
        assertEquals(128, normal.getG(1, 1))
        assertEquals(255, normal.getB(1, 1))
        assertEquals(255, normal.getA(1, 1))
        assertEquals(0, specular.getR(1, 1))
        assertEquals(0, specular.getG(1, 1))
        assertEquals(0, specular.getB(1, 1))
        assertEquals(255, specular.getA(1, 1))
    }

    @Test
    fun `authored companions decode from resource pack paths and retain channels`() {
        val assets = MemoryAssets()
        val diffuse = ResourceLocation("fixture", "textures/block/slate.png")
        val normal = ResourceLocation("fixture", "textures/block/slate_n.png")
        val specular = ResourceLocation("fixture", "textures/block/slate_s.png")
        assets.push(normal, png(32, 64, 192, 255))
        assets.push(specular, png(7, 83, 149, 211))

        val loader = PNGTextureLoader(diffuse)
        val normalResult = LabPbrCompanionTextures.load(
            assets,
            loader,
            Vec2i(2, 2),
            mipmaps = 0,
            kind = LabPbrCompanionTextures.Kind.NORMAL,
        )
        val specularResult = LabPbrCompanionTextures.load(
            assets,
            loader,
            Vec2i(2, 2),
            mipmaps = 0,
            kind = LabPbrCompanionTextures.Kind.SPECULAR,
        )

        assertEquals(normal, normalResult.resource)
        assertEquals(specular, specularResult.resource)
        assertPixel(normalResult, 32, 64, 192, 255)
        assertPixel(specularResult, 7, 83, 149, 211)
    }

    @Test
    fun `authored companion scales to the diffuse frame size`() {
        val assets = MemoryAssets()
        val diffuse = ResourceLocation("fixture", "textures/block/slate.png")
        val normal = ResourceLocation("fixture", "textures/block/slate_n.png")
        assets.push(
            normal,
            png(12, 34, 56, 78, width = 1, height = 2),
        )

        val result = LabPbrCompanionTextures.load(
            assets,
            PNGTextureLoader(diffuse),
            Vec2i(2, 2),
            mipmaps = 0,
            kind = LabPbrCompanionTextures.Kind.NORMAL,
        )

        assertEquals(normal, result.resource)
        assertPixel(result, 12, 34, 56, 78)
    }

    @Test
    fun `animated companion follows its authored frames at the synchronized diffuse time`() {
        val assets = MemoryAssets()
        val diffuse = ResourceLocation("fixture", "textures/block/slate.png")
        val normal = ResourceLocation("fixture", "textures/block/slate_n.png")
        assets.push(normal, verticalFrames(10, 20, 30, 255, 110, 120, 130, 255))
        assets.push(
            ResourceLocation("fixture", "textures/block/slate_n.png.mcmeta"),
            """
                {
                  "animation": {
                    "frametime": 1,
                    "frames": [0, 1]
                  }
                }
            """.trimIndent().encodeToByteArray(),
        )

        val result = LabPbrCompanionTextures.load(
            assets,
            PNGTextureLoader(diffuse),
            Vec2i(2, 2),
            mipmaps = 0,
            kind = LabPbrCompanionTextures.Kind.NORMAL,
        )
        val animation = assertNotNull(result.animation)

        assertPixel(result, 10, 20, 30, 255)
        assertEquals(animation.frameIndex, 0)
        assertEquals(0L, animation.revision)
        animation.advance(Duration.ZERO, synchronizedPosition = 10.milliseconds)
        assertEquals(0L, animation.revision)
        animation.advance(Duration.ZERO, synchronizedPosition = 50.milliseconds)
        assertPixel(result, 110, 120, 130, 255)
        assertEquals(animation.frameIndex, 1)
        assertEquals(1L, animation.revision)
        animation.advance(Duration.ZERO, synchronizedPosition = Duration.ZERO)
        assertPixel(result, 10, 20, 30, 255)
        assertEquals(animation.frameIndex, 0)
    }

    @Test
    fun `animated companion frame grid scales before timeline slicing`() {
        val assets = MemoryAssets()
        val diffuse = ResourceLocation("fixture", "textures/block/slate.png")
        val normal = ResourceLocation("fixture", "textures/block/slate_n.png")
        assets.push(
            normal,
            verticalFrames(
                10, 20, 30, 255,
                110, 120, 130, 255,
                width = 1,
                frameHeight = 1,
            ),
        )
        assets.push(
            ResourceLocation("fixture", "textures/block/slate_n.png.mcmeta"),
            """{"animation":{"width":1,"height":1,"frametime":1,"frames":[0,1]}}"""
                .encodeToByteArray(),
        )

        val result = LabPbrCompanionTextures.load(
            assets,
            PNGTextureLoader(diffuse),
            Vec2i(2, 2),
            mipmaps = 0,
            kind = LabPbrCompanionTextures.Kind.NORMAL,
        )
        val animation = assertNotNull(result.animation)

        assertPixel(result, 10, 20, 30, 255)
        animation.advance(Duration.ZERO, synchronizedPosition = 50.milliseconds)
        assertPixel(result, 110, 120, 130, 255)
    }

    private fun assertPixel(
        loaded: LabPbrCompanionTextures.Loaded,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ) {
        val buffer = loaded.data.collect().single()
        assertEquals(red, buffer.getR(1, 1))
        assertEquals(green, buffer.getG(1, 1))
        assertEquals(blue, buffer.getB(1, 1))
        assertEquals(alpha, buffer.getA(1, 1))
    }

    private fun png(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        width: Int = 2,
        height: Int = 2,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val value = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        for (y in 0 until height) {
            for (x in 0 until width) image.setRGB(x, y, value)
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun verticalFrames(
        firstRed: Int,
        firstGreen: Int,
        firstBlue: Int,
        firstAlpha: Int,
        secondRed: Int,
        secondGreen: Int,
        secondBlue: Int,
        secondAlpha: Int,
        width: Int = 2,
        frameHeight: Int = 2,
    ): ByteArray {
        val image = BufferedImage(width, frameHeight * 2, BufferedImage.TYPE_INT_ARGB)
        val first = (firstAlpha shl 24) or (firstRed shl 16) or (firstGreen shl 8) or firstBlue
        val second = (secondAlpha shl 24) or (secondRed shl 16) or (secondGreen shl 8) or secondBlue
        for (y in 0 until frameHeight * 2) {
            for (x in 0 until width) image.setRGB(x, y, if (y < frameHeight) first else second)
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private class MemoryAssets : AssetsManager {
        private val entries = mutableMapOf<ResourceLocation, ByteArray>()
        override val loaded = true

        override fun get(path: ResourceLocation): InputStream =
            getOrNull(path) ?: throw FileNotFoundException(path.toString())

        override fun getOrNull(path: ResourceLocation): InputStream? =
            entries[path]?.let(::ByteArrayInputStream)

        override fun load(latch: AbstractLatch?) = Unit

        override fun unload() = Unit

        override fun getAssetsManager(path: ResourceLocation): AssetsManager? =
            if (path in entries) this else null

        fun push(path: ResourceLocation, data: ByteArray) {
            entries[path] = data
        }
    }
}
