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
import kotlin.test.assertTrue

/** Prevents production GL work from bypassing the exact context-owned counters. */
class OpenGlInstrumentationBoundaryTest {
    @Test
    fun `physical draw and binding APIs remain on instrumented boundaries`() {
        assertCallsOnly(
            Regex("\\bgl(?:DrawArrays|DrawElements(?:BaseVertex)?|MultiDrawElementsBaseVertex)\\s*\\("),
            VERTEX_BUFFER,
            TERRAIN_DEVICE,
        )
        assertCallsOnly(Regex("\\bglUseProgram\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglActiveTexture\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglBindTexture\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglBindImageTexture\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglBindFramebuffer\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglBindVertexArray\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglBindBuffer\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglBindBuffer(?:Base|Range)\\s*\\("), RENDER_SYSTEM)
        assertCallsOnly(Regex("\\bglBindRenderbuffer\\s*\\("), RENDERBUFFER)
        assertCallsOnly(Regex("\\bglUniform[0-9A-Za-z_]*\\s*\\("), NATIVE_SHADER)
        assertCallsOnly(Regex("\\bglFramebuffer(?:Texture2D|Renderbuffer)\\s*\\("), *FRAMEBUFFER_STATE_FILES.toTypedArray())
        assertCallsOnly(Regex("\\bglCheckFramebufferStatus\\s*\\("), *FRAMEBUFFER_STATE_FILES.toTypedArray())
        assertCallsOnly(Regex("\\bgl(?:DrawBuffers|DrawBuffer|ReadBuffer)\\s*\\("), RENDER_TARGETS)
        assertCallsOnly(Regex("\\bglTexParameter[0-9A-Za-z_]*\\s*\\("), *TEXTURE_PARAMETER_FILES.toTypedArray())
    }

    @Test
    fun `every physical API call has its matching primitive counter`() {
        assertInstrumented(VERTEX_BUFFER, Regex("\\bglDraw(?:Arrays|Elements)\\s*\\("), Regex("work\\.draw(?:Arrays|Elements)\\s*\\("))
        assertInstrumented(TERRAIN_DEVICE, Regex("\\bgl(?:DrawElementsBaseVertex|MultiDrawElementsBaseVertex)\\s*\\("), Regex("work\\.(?:drawElementsBaseVertex|multiDrawElementsBaseVertex)\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglUseProgram\\s*\\("), Regex("work\\.programRequest\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglActiveTexture\\s*\\("), Regex("work\\.activeTextureRequest\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglBindTexture\\s*\\("), Regex("work\\.textureBindRequest\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglBindImageTexture\\s*\\("), Regex("work\\.imageBindRequest\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglBindFramebuffer\\s*\\("), Regex("work\\.framebufferBindRequest\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglBindVertexArray\\s*\\("), Regex("work\\.vaoBindRequest\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglBindBuffer\\s*\\("), Regex("work\\.bufferBindRequest\\s*\\("))
        assertInstrumented(RENDER_SYSTEM, Regex("\\bglBindBuffer(?:Base|Range)\\s*\\("), Regex("work\\.bufferBindRequest\\s*\\("))
        assertInstrumented(RENDERBUFFER, Regex("\\bglBindRenderbuffer\\s*\\("), Regex("work\\.bufferBindRequest\\s*\\("))
        assertInstrumented(NATIVE_SHADER, Regex("\\bglUniform[0-9A-Za-z_]*\\s*\\("), Regex("work\\.(?:scalar|vector|matrix|sampler)UniformUpload\\s*\\(|work\\.uniformBlockBinding\\s*\\("))

        for (file in FRAMEBUFFER_STATE_FILES) {
            assertInstrumented(file, Regex("\\bglFramebuffer(?:Texture2D|Renderbuffer)\\s*\\("), Regex("work\\.framebufferAttachmentChange\\s*\\("))
            assertInstrumented(file, Regex("\\bglCheckFramebufferStatus\\s*\\("), Regex("work\\.framebufferCompletenessCheck\\s*\\("))
        }
        assertInstrumented(RENDER_TARGETS, Regex("\\bgl(?:DrawBuffers|DrawBuffer)\\s*\\("), Regex("work\\.drawBufferChange\\s*\\("))
        assertInstrumented(RENDER_TARGETS, Regex("\\bglReadBuffer\\s*\\("), Regex("work\\.readBufferChange\\s*\\("))
        for (file in TEXTURE_PARAMETER_FILES) {
            assertInstrumented(file, Regex("\\bglTexParameter[0-9A-Za-z_]*\\s*\\("), Regex("textureParameter\\s*\\{"))
        }
    }

    private fun assertCallsOnly(pattern: Regex, vararg allowed: String) {
        val allowedFiles = allowed.toSet()
        val violations = sourceFiles().flatMap { file ->
            val relative = relative(file)
            val source = file.readText()
            pattern.findAll(source).mapNotNull { match ->
                relative.takeUnless(allowedFiles::contains)?.let { "$it:${line(source, match.range.first)}" }
            }
        }.sorted()
        assertTrue(violations.isEmpty(), "OpenGL calls bypass their instrumented boundary:\n${violations.joinToString("\n")}")
    }

    private fun assertInstrumented(file: String, call: Regex, marker: Regex) {
        val source = PROJECT_ROOT.resolve(file).readText()
        val missing = call.findAll(source).mapNotNull { match ->
            val prefix = source.substring(maxOf(0, match.range.first - LOOKBEHIND), match.range.first)
            match.takeUnless { marker.containsMatchIn(prefix) }?.let { line(source, it.range.first) }
        }.toList()
        assertTrue(missing.isEmpty(), "$file has uninstrumented ${call.pattern} calls on lines $missing")
    }

    private fun sourceFiles(): List<Path> = Files.walk(PROJECT_ROOT.resolve("src/main/java")).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
    }

    private fun relative(file: Path): String = PROJECT_ROOT.relativize(file).toString().replace('\\', '/')

    private fun line(source: String, offset: Int): Int = source.take(offset).count { it == '\n' } + 1

    private companion object {
        const val LOOKBEHIND = 320
        val PROJECT_ROOT: Path = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

        const val RENDER_SYSTEM = "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/OpenGlRenderSystem.kt"
        const val VERTEX_BUFFER = "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/buffer/vertex/OpenGlVertexBuffer.kt"
        const val TERRAIN_DEVICE = "src/main/java/de/bixilon/minosoft/gui/rendering/terrain/storage/OpenGlTerrainRegionResources.kt"
        const val NATIVE_SHADER = "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/shader/OpenGlNativeShader.kt"
        const val RENDERBUFFER = "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/buffer/frame/attachment/OpenGlBufferAttachment.kt"
        const val FRAMEBUFFER = "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/buffer/frame/OpenGlFramebuffer.kt"
        const val RENDER_TARGETS = "src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisOpenGlRenderTargets.kt"
        const val CUSTOM_RESOURCES = "src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisOpenGlCustomResources.kt"

        val FRAMEBUFFER_STATE_FILES = listOf(FRAMEBUFFER, RENDER_TARGETS)
        val TEXTURE_PARAMETER_FILES = listOf(
            "src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisOpenGlCustomTextures.kt",
            CUSTOM_RESOURCES,
            RENDER_TARGETS,
            "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/texture/OpenGlTextureUtil.kt",
            "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/texture/OpenGlFontTextureArray.kt",
            "src/main/java/de/bixilon/minosoft/gui/rendering/system/opengl/buffer/frame/attachment/texture/OpenGlTextureAttachment.kt",
        )
    }
}
