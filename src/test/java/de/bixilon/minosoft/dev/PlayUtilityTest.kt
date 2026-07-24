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

package de.bixilon.minosoft.dev

import de.bixilon.minosoft.util.json.Jackson
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayUtilityTest {
    private val project = Path.of("").toAbsolutePath().normalize()
    private val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")

    private fun runPlay(vararg arguments: String): String {
        val command = mutableListOf(
            java.toString(),
            "-Dminosoft.project=$project",
            "-cp",
            project.resolve("util/play/build/install/play-util/lib/*").toString(),
            "Play",
        )
        command += arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Play utility did not finish.")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    @Test
    fun `compiled utility discovers tracked packs`() {
        val output = runPlay("modpack", "list")
        assertTrue(output.lineSequence().any { it == "sodium" }, output)
        assertTrue(output.lineSequence().any { it == "fabric-stack" }, output)
        assertTrue(output.lineSequence().any { it == "tech-reborn" }, output)
        assertTrue(output.lineSequence().any { it == "content-fidelity" }, output)
    }

    @Test
    fun `status has a machine readable contract`() {
        val status = Jackson.MAPPER.readTree(runPlay("status", "--json"))

        assertEquals("both", status.path("target").asText())
        assertTrue(status.path("serverReady").isBoolean)
        assertTrue(status.path("serverPortOpen").isBoolean)
        assertTrue(status.path("serverDebugReady").isBoolean)
        assertTrue(status.path("serverGameReady").isBoolean)
        assertTrue(status.path("clientDebugReady").isBoolean)
        assertTrue(status.path("clientJoined").isBoolean)
        assertTrue(status.path("clientRenderReady").isBoolean)
        assertTrue(status.path("externalClientPids").isArray)
        assertTrue(status.has("parentPid"))
        assertTrue(status.has("serverPid"))
        assertTrue(status.has("clientPid"))
    }

    @Test
    fun `canary mod is packaged as a native candidate`() {
        val artifact = project.resolve("build/dev-mods/hot-reload-canary.jar")
        JarFile(artifact.toFile()).use { jar ->
            assertTrue(jar.getJarEntry("manifest.json") != null)
            assertTrue(jar.getJarEntry("de/bixilon/minosoft/dev/canary/HotReloadCanary.class") != null)
            val manifest = jar.getInputStream(jar.getJarEntry("manifest.json")).bufferedReader().readText()
            assertTrue(manifest.contains("\"name\": \"hot_reload_canary\""), manifest)
        }
    }

    @Test
    fun `play help exposes the canary reload lane`() {
        val output = runPlay("help")
        assertTrue(output.contains("--canary"), output)
        assertTrue(output.contains("MINOSOFT_CANARY=true"), output)
        assertTrue(output.contains("--local-world"), output)
        assertTrue(output.contains("--world-seed"), output)
        assertTrue(output.contains("MINOSOFT_LOCAL_WORLD=true"), output)
        assertTrue(output.contains("scenario run"), output)
        assertTrue(output.contains("worldgen inspect"), output)
        assertTrue(output.contains("client.render-ready"), output)
        assertTrue(output.contains("on-failure"), output)
    }

    @Test
    fun `screenshot regression accepts an identical png`() {
        val image = project.resolve("src/main/resources/assets/minosoft/textures/white.png")
        val output = Jackson.MAPPER.readTree(runPlay("screenshot", "compare", image.toString(), image.toString(), "--json"))

        assertTrue(output.path("passed").asBoolean(), output.toString())
        assertEquals(0, output.path("changedPixels").asInt())
        assertEquals(0.0, output.path("meanAbsoluteError").asDouble())
    }
}
