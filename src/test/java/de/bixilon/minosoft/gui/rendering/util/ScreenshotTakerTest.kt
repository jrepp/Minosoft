/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.util

import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenshotTakerTest {

    @Test
    fun `user screenshots use the standard game directory`() {
        val home = createTempDirectory("minosoft-screenshot-home")

        assertEquals(home.resolve("screenshots"), ScreenshotTaker.standardDirectory(home))
    }

    @Test
    fun `same-second screenshots receive a collision suffix`() {
        val directory = createTempDirectory("minosoft-screenshots")
        val time = Instant.parse("2026-07-28T12:34:56Z")
        val first = ScreenshotTaker.destinationFile(directory, time).toPath()
        Files.createFile(first)

        val second = ScreenshotTaker.destinationFile(directory, time).toPath()

        assertEquals("${first.fileName.toString().removeSuffix(".png")}_1.png", second.fileName.toString())
    }
}
