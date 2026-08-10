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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.SkipException
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO

@Test(groups = ["assets", "external-assets"])
class ContentForgeAssetsIT {

    fun `content forge and Blockbench output matches provenance and parses`() {
        val root = OfflineTestAssets.contentForgeRoot()
            ?: throw SkipException("Set ${OfflineTestAssets.CONTENT_FORGE_ROOT_ENV} to validate external authored assets.")
        val provenanceFile = root.resolve("provenance.json")
        assertTrue(Files.isRegularFile(provenanceFile), "Missing content-forge provenance: $provenanceFile")

        val provenance = JSON.readTree(provenanceFile.toFile())
        assertEquals(provenance.path("schema").asInt(), 1)
        assertEquals(provenance.path("producer").asText(), "content-forge")
        val targets = provenance.path("targets")
        assertTrue(targets.isArray && !targets.isEmpty, "Content-forge provenance has no targets.")
        assertTrue(targets.size() <= MAX_TARGETS, "Content-forge provenance exceeds $MAX_TARGETS targets.")

        val seen = hashSetOf<String>()
        val producers = hashSetOf<String>()
        val missingTargets = mutableListOf<String>()
        val hashMismatches = mutableListOf<String>()
        for (entry in targets) {
            val target = requiredText(entry, "target")
            assertTrue(target.length <= MAX_TARGET_LENGTH, "Content target is too long: $target")
            assertTrue(seen.add(target), "Duplicate content target: $target")
            producers += requiredText(entry, "producer")

            val relative = Path.of(target).normalize()
            assertTrue(!relative.isAbsolute && !relative.startsWith(".."), "Unsafe content target: $target")
            assertEquals(relative.toString().replace('\\', '/'), target, "Non-canonical content target: $target")
            assertTrue(target.startsWith("assets/"), "Content target is outside assets/: $target")

            val file = root.resolve(relative).normalize()
            assertTrue(file.startsWith(root), "Content target escaped its root: $target")
            if (!Files.isRegularFile(file)) {
                missingTargets += target
                continue
            }
            if (sha256(file) != requiredText(entry, "sha256")) hashMismatches += target
        }

        val counts = validateAssetTree(root)
        assertTrue("blockbench" in producers, "Content provenance contains no Blockbench output.")
        assertTrue(counts.files > 0, "Content output contains no files.")
        assertTrue(counts.blockstates > 0, "Content output contains no blockstates.")
        assertTrue(counts.models > 0, "Content output contains no models.")
        assertTrue(counts.textures > 0, "Content output contains no textures.")
        assertTrue(missingTargets.isEmpty(), failureSummary("missing provenance targets", missingTargets))
        assertTrue(hashMismatches.isEmpty(), failureSummary("provenance hash mismatches", hashMismatches))
    }

    private fun validateAssetTree(root: Path): AssetCounts {
        val assetsRoot = root.resolve("assets")
        var files = 0
        var blockstates = 0
        var models = 0
        var textures = 0
        Files.walk(assetsRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                files++
                assertTrue(files <= MAX_TARGETS, "Content output exceeds $MAX_TARGETS files.")
                assertTrue(!Files.isSymbolicLink(file), "Content output contains a symbolic link: $file")
                val target = root.relativize(file).toString().replace('\\', '/')
                when {
                    "/blockstates/" in target && target.endsWith(".json") -> {
                        blockstates++
                        assertJsonObject(file, target)
                    }
                    "/models/" in target && target.endsWith(".json") -> {
                        models++
                        assertJsonObject(file, target)
                    }
                    "/textures/" in target && target.endsWith(".png") -> {
                        textures++
                        assertPngHeader(file, target)
                    }
                    target.endsWith(".json") || target.endsWith(".mcmeta") -> assertJsonObject(file, target)
                }
            }
        }
        return AssetCounts(files, blockstates, models, textures)
    }

    private fun assertJsonObject(file: Path, target: String) {
        Files.newInputStream(file).use { input ->
            assertTrue(JSON.readTree(input)?.isObject == true, "Content JSON is not an object: $target")
        }
    }

    private fun assertPngHeader(file: Path, target: String) {
        val stream = requireNotNull(ImageIO.createImageInputStream(file.toFile())) {
            "Content PNG is unreadable: $target"
        }
        stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            assertTrue(readers.hasNext(), "Content PNG has no decoder: $target")
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                assertTrue(width in 1..MAX_IMAGE_DIMENSION, "Content PNG width is invalid: $target ($width)")
                assertTrue(height in 1..MAX_IMAGE_DIMENSION, "Content PNG height is invalid: $target ($height)")
                assertTrue(width.toLong() * height <= MAX_IMAGE_PIXELS, "Content PNG is too large: $target (${width}x$height)")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun requiredText(node: JsonNode, field: String): String {
        val value = node.path(field)
        assertTrue(value.isTextual && value.asText().isNotBlank(), "Content provenance target is missing $field.")
        return value.asText()
    }

    private fun failureSummary(problem: String, targets: List<String>): String {
        return "${targets.size} $problem: ${targets.take(MAX_FAILURE_SAMPLE).joinToString()}"
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        val JSON = ObjectMapper()
        const val MAX_TARGETS = 100_000
        const val MAX_TARGET_LENGTH = 512
        const val MAX_IMAGE_DIMENSION = 8_192
        const val MAX_IMAGE_PIXELS = 64L * 1024 * 1024
        const val MAX_FAILURE_SAMPLE = 10
    }

    private data class AssetCounts(val files: Int, val blockstates: Int, val models: Int, val textures: Int)
}
