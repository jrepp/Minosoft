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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayUtilityTest {
    private val project = Path.of("").toAbsolutePath().normalize()
    private val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")

    private fun runPlay(vararg arguments: String, environment: Map<String, String> = emptyMap(), removedEnvironment: Set<String> = emptySet()): String {
        val command = mutableListOf(
            java.toString(),
            "-Dminosoft.project=$project",
            "-cp",
            project.resolve("util/play/build/install/play-util/lib").toString() + File.separator + "*",
            "Play",
        )
        command += arguments
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().putAll(environment)
        removedEnvironment.forEach { builder.environment().remove(it) }
        val outputFile = Files.createTempFile("play-test-output", ".log")
        try {
            val process = builder.redirectOutput(outputFile.toFile()).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Play utility did not finish.")
                val output = Files.readString(outputFile)
                assertEquals(0, process.exitValue(), output)
                return output
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    @Test
    fun `launcher works without a HOME environment variable`() {
        val output = runPlay("help", removedEnvironment = setOf("HOME", "MINOSOFT_MODPACK_STORE"))
        assertTrue(output.contains("--canary"), output)
    }

    @Test
    fun `explicit modpack store works without a HOME environment variable`() {
        val store = createTempDirectory("play-store")
        try {
            val output = runPlay("help", environment = mapOf("MINOSOFT_MODPACK_STORE" to store.toString()), removedEnvironment = setOf("HOME"))
            assertTrue(output.contains("--canary"), output)
        } finally {
            Files.deleteIfExists(store)
        }
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
        assertTrue(output.contains("--world-generator"), output)
        assertTrue(output.contains("--world-seed"), output)
        assertTrue(output.contains("MINOSOFT_LOCAL_WORLD=true"), output)
        assertTrue(output.contains("--content-provider"), output)
        assertTrue(output.contains("--content-stack"), output)
        assertTrue(output.contains("--voxelibre-root"), output)
        assertTrue(output.contains("--faithful-pack"), output)
        assertTrue(output.contains("--content-source"), output)
        assertTrue(output.contains("--content-mods"), output)
        assertTrue(output.contains("--standalone-content"), output)
        assertTrue(output.contains("content compose"), output)
        assertTrue(output.contains("content audit"), output)
        assertTrue(output.contains("MINOSOFT_CONTENT_PROVIDER=voxelibre"), output)
        assertTrue(output.contains("MINOSOFT_CONTENT_STACK"), output)
        assertTrue(output.contains("MINOSOFT_FAITHFUL_PACKS"), output)
        assertTrue(output.contains("MINOSOFT_WORLD_GENERATOR"), output)
        assertTrue(output.contains("scenario run"), output)
        assertTrue(output.contains("worldgen inspect"), output)
        assertTrue(output.contains("client.render-ready"), output)
        assertTrue(output.contains("on-failure"), output)
        assertTrue(output.contains("MINOSOFT_MODPACK_CACHE"), output)
        assertTrue(output.contains("--debug-gpu-memory-leaks"), output)
        assertTrue(output.contains("MINOSOFT_DEBUG_GPU_MEMORY_LEAKS=true"), output)
    }

    @Test
    fun `portable cache supplies a cache-only pack artifact`() {
        val root = createTempDirectory("minosoft-portable-cache-")
        val packs = root.resolve("packs").createDirectories()
        val pack = packs.resolve("fixture").createDirectories()
        val mods = pack.resolve("mods").createDirectories()
        val source = root.resolve("fixture-mod.jar")
        JarOutputStream(Files.newOutputStream(source)).use { jar ->
            jar.putNextEntry(JarEntry("fabric.mod.json"))
            jar.write("""{"schemaVersion":1,"id":"fixture_mod","version":"1.0.0","environment":"client"}""".encodeToByteArray())
            jar.closeEntry()
        }
        val artifactHash = hash("SHA-512", source)
        Files.writeString(
            mods.resolve("fixture.pw.toml"),
            """
                name = "Fixture"
                filename = "fixture-mod.jar"
                side = "client"

                [download]
                hash-format = "sha512"
                hash = "$artifactHash"
                url = "minosoft-cache:fixture-mod.jar"
            """.trimIndent(),
        )
        Files.writeString(
            pack.resolve("fabric.mod.json"),
            """{"schemaVersion":1,"id":"fixture_pack","version":"1.0.0","depends":{"minecraft":"1.20.4","fixture_mod":"=1.0.0"}}""",
        )
        Files.writeString(pack.resolve("ladder.tsv"), "00\tfixture\tPortable cache fixture\tready\tHash-pinned local artifact\n")
        val contentFixture = project.resolve("build/test-content-fixtures/play-utility/fixture")
        val resources = contentFixture.resolve("resources")
        val dataPacks = contentFixture.resolve("datapacks")
        resources.resolve("assets/demo").createDirectories()
        dataPacks.resolve("data/demo/functions").createDirectories()
        Files.writeString(resources.resolve("pack.mcmeta"), """{"pack":{"pack_format":22,"description":"fixture"}}""")
        Files.writeString(resources.resolve("assets/demo/value.txt"), "fixture-resource")
        Files.writeString(dataPacks.resolve("pack.mcmeta"), """{"pack":{"pack_format":26,"description":"fixture"}}""")
        Files.writeString(dataPacks.resolve("data/demo/functions/load.mcfunction"), "say fixture")
        val fixtureManifest = contentManifest(contentFixture)
        Files.writeString(
            contentFixture.resolve("fixture.json"),
            """
                {
                  "minecraft": "1.20.4",
                  "output": {
                    "resource_files": 2,
                    "data_files": 2,
                    "manifest_sha256": "$fixtureManifest"
                  }
                }
            """.trimIndent(),
        )
        Files.writeString(
            pack.resolve("fixtures.tsv"),
            """
                id	source	manifest_sha256	resource_files	data_files
                demo	${project.relativize(contentFixture).toString().replace('\\', '/')}	$fixtureManifest	2	2
            """.trimIndent() + "\n",
        )
        val indexed = listOf("fabric.mod.json", "fixtures.tsv", "ladder.tsv", "mods/fixture.pw.toml")
        val index = buildString {
            appendLine("hash-format = \"sha256\"")
            for (relative in indexed) {
                appendLine()
                appendLine("[[files]]")
                appendLine("file = \"$relative\"")
                appendLine("hash = \"${hash("SHA-256", pack.resolve(relative))}\"")
                if (relative.endsWith(".pw.toml")) appendLine("metafile = true")
            }
        }
        Files.writeString(pack.resolve("index.toml"), index)
        Files.writeString(
            pack.resolve("pack.toml"),
            """
                name = "Portable Cache Fixture"
                pack-format = "packwiz:1.1.0"
                version = "1.0.0"

                [index]
                file = "index.toml"
                hash-format = "sha256"
                hash = "${hash("SHA-256", pack.resolve("index.toml"))}"

                [versions]
                fabric = "0.15.11"
                minecraft = "1.20.4"
            """.trimIndent(),
        )
        val cache = root.resolve("cache")
        val store = root.resolve("store")
        val environment = mapOf(
            "MINOSOFT_MODPACKS_DIR" to packs.toString(),
            "MINOSOFT_MODPACK_STORE" to store.toString(),
            "MINOSOFT_MODPACK_CACHE" to cache.toString(),
        )

        val cached = runPlay("modpack", "cache", "add", source.toString(), environment = environment)
        assertTrue(cached.contains(artifactHash), cached)
        val prepared = runPlay("modpack", "prepare", "fixture", environment = environment)

        assertTrue(prepared.contains("Imported Fixture fixture-mod.jar from portable cache."), prepared)
        assertEquals(
            artifactHash,
            hash("SHA-512", store.resolve("artifacts/sha512/$artifactHash/fixture-mod.jar")),
        )
        assertTrue(prepared.contains("content fixtures: 1"), prepared)
        val staged = store.resolve("trajectories/default/fixture/content-fixtures/demo/$fixtureManifest")
        assertEquals(fixtureManifest, contentManifest(staged))
        assertTrue(Files.isRegularFile(staged.resolve("resources/assets/demo/value.txt")))
        assertTrue(Files.isRegularFile(staged.resolve("datapacks/data/demo/functions/load.mcfunction")))
    }

    private fun hash(algorithm: String, path: Path): String =
        MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun contentManifest(root: Path): String {
        val files = Files.walk(root).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter {
                    val relative = root.relativize(it).toString().replace('\\', '/')
                    relative.startsWith("resources/") || relative.startsWith("datapacks/")
                }
                .sorted(compareBy { root.relativize(it).toString().replace('\\', '/') })
                .toList()
        }
        val manifest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            val relative = root.relativize(file).toString().replace('\\', '/')
            manifest.update("${hash("SHA-256", file)}  $relative\n".encodeToByteArray())
        }
        return manifest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `screenshot regression accepts an identical png`() {
        val image = project.resolve("src/main/resources/assets/minosoft/textures/white.png")
        val output = Jackson.MAPPER.readTree(runPlay("screenshot", "compare", image.toString(), image.toString(), "--json"))

        assertTrue(output.path("passed").asBoolean(), output.toString())
        assertEquals(0, output.path("changedPixels").asInt())
        assertEquals(0.0, output.path("meanAbsoluteError").asDouble())
    }

    @Test
    fun `screenshot crop writes a bounded reference region`() {
        val image = project.resolve("src/main/resources/assets/minosoft/textures/debug.png")
        val output = createTempDirectory("play-screenshot-crop").resolve("crop.png")
        val result = Jackson.MAPPER.readTree(
            runPlay(
                "screenshot",
                "crop",
                image.toString(),
                output.toString(),
                "--region",
                "4,5,6,7",
                "--json",
            ),
        )

        assertEquals(16, result.path("sourceWidth").asInt())
        assertEquals(16, result.path("sourceHeight").asInt())
        assertEquals(6, result.path("width").asInt())
        assertEquals(7, result.path("height").asInt())
        ImageIO.read(output.toFile()).also {
            assertEquals(6, it.width)
            assertEquals(7, it.height)
        }
    }
}
