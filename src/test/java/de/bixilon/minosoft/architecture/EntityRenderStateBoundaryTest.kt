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
 */

package de.bixilon.minosoft.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keeps entity batching keyed by explicit render state instead of runtime class identity. */
class EntityRenderStateBoundaryTest {
    @Test
    fun `drawable entity features require explicit render state keys`() {
        val drawable = source("feature/DrawableEntityRenderFeature.kt")
        assertTrue(
            Regex("override\\s+val\\s+renderStateKey:\\s*EntityRenderStateKey").containsMatchIn(drawable),
            "DrawableEntityRenderFeature must require a typed render-state key",
        )
        assertFalse(
            Regex("renderStateKey:\\s*EntityRenderStateKey\\s*=").containsMatchIn(drawable),
            "DrawableEntityRenderFeature must not supply an implicit grouping key",
        )
    }

    @Test
    fun `entity feature ordering does not fall back to class hashes`() {
        val violations = entitySources().flatMap { file ->
            val text = file.readText()
            FORBIDDEN.flatMap { pattern ->
                pattern.findAll(text).map { match ->
                    "${PROJECT_ROOT.relativize(file)}:${line(text, match.range.first)}"
                }
            }
        }.sorted()
        assertTrue(violations.isEmpty(), "Entity rendering regained class-derived ordering:\n${violations.joinToString("\n")}")
    }

    private fun source(relative: String): String = ENTITY_ROOT.resolve(relative).readText()

    private fun entitySources(): List<Path> = Files.walk(ENTITY_ROOT).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
    }

    private fun line(source: String, offset: Int): Int = source.take(offset).count { it == '\n' } + 1

    private companion object {
        val PROJECT_ROOT: Path = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val ENTITY_ROOT: Path = PROJECT_ROOT.resolve("src/main/java/de/bixilon/minosoft/gui/rendering/entities")
        val FORBIDDEN = listOf(
            Regex("class\\.java\\.hashCode\\s*\\("),
            Regex("javaClass\\.hashCode\\s*\\("),
        )
    }
}
