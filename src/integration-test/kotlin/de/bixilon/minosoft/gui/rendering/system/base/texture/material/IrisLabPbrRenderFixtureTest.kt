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

import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import javax.imageio.ImageIO

class IrisLabPbrRenderFixtureTest {
    @Test
    fun `managed fixture retains authored animated normal and specular sources`() {
        val fixture = Paths.get(
            requireNotNull(
                javaClass.classLoader.getResource("content_fidelity/iris-labpbr-render/fixture.json"),
            ).toURI(),
        ).parent
        assertEquals(
            fixture.manifestHash(),
            "b45f544728e23287230c7f31895af0b7cd352f0caa3dcdb4f7a8442407a659fb",
        )

        val block = fixture.resolve("resources/assets/minecraft/textures/block")
        val diffuse = ImageIO.read(block.resolve("sand.png").toFile())
        val normal = ImageIO.read(block.resolve("sand_n.png").toFile())
        val specular = ImageIO.read(block.resolve("sand_s.png").toFile())
        assertEquals(diffuse.width, 16)
        assertEquals(diffuse.height, 64)
        assertPixel(diffuse.getRGB(8, 8), 216, 196, 138, 255)
        assertPixel(diffuse.getRGB(8, 24), 216, 196, 138, 255)
        assertPixel(diffuse.getRGB(8, 40), 216, 196, 138, 255)
        assertPixel(diffuse.getRGB(8, 56), 216, 196, 138, 255)
        assertEquals(normal.width, 16)
        assertEquals(normal.height, 64)
        assertEquals(specular.width, 16)
        assertEquals(specular.height, 64)
        assertPixel(normal.getRGB(8, 8), 128, 128, 255, 255)
        assertPixel(normal.getRGB(8, 24), 255, 255, 128, 255)
        assertPixel(normal.getRGB(8, 40), 128, 128, 255, 255)
        assertPixel(normal.getRGB(8, 56), 255, 255, 128, 255)
        assertPixel(specular.getRGB(8, 8), 0, 0, 0, 255)
        assertPixel(specular.getRGB(8, 24), 0, 0, 0, 255)
        assertPixel(specular.getRGB(8, 40), 255, 0, 0, 128)
        assertPixel(specular.getRGB(8, 56), 255, 0, 0, 128)

        for (metadata in listOf("sand.png.mcmeta", "sand_n.png.mcmeta", "sand_s.png.mcmeta")) {
            val source = Files.readString(block.resolve(metadata))
            assertEquals(Regex(""""frametime"\s*:\s*20""").containsMatchIn(source), true)
            assertEquals(
                Regex(""""frames"\s*:\s*\[0,\s*1,\s*2,\s*3]""").containsMatchIn(source),
                true,
            )
        }

        assertEquals(
            Files.readString(
                fixture.resolve(
                    "datapacks/data/minosoft_acceptance/functions/iris_labpbr/prepare.mcfunction",
                ),
            ).trim(),
            "fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 minecraft:sand",
        )
    }

    private fun assertPixel(
        argb: Int,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ) {
        assertEquals((argb ushr 16) and 0xFF, red)
        assertEquals((argb ushr 8) and 0xFF, green)
        assertEquals(argb and 0xFF, blue)
        assertEquals((argb ushr 24) and 0xFF, alpha)
    }

    private fun Path.manifestHash(): String {
        val manifest = MessageDigest.getInstance("SHA-256")
        val files = Files.walk(this).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter {
                    val relative = relativize(it).toString().replace('\\', '/')
                    relative.startsWith("resources/") || relative.startsWith("datapacks/")
                }
                .sorted(compareBy { relativize(it).toString().replace('\\', '/') })
                .toList()
        }
        for (file in files) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file))
                .toHex()
            val relative = relativize(file).toString().replace('\\', '/')
            manifest.update("$digest  $relative\n".toByteArray())
        }
        return manifest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
