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

package de.bixilon.minosoft.gui.rendering.system.opengl.shader

import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.kutil.os.OSTypes
import de.bixilon.kutil.os.PlatformInfo
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShaderSource
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlCapabilityDiagnostics
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import de.bixilon.minosoft.gui.rendering.system.opengl.vendor.OpenGlVendor
import de.bixilon.minosoft.gui.rendering.system.window.glfw.OpenGlContextRequest
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.PositionOnlyMeshStruct
import de.bixilon.minosoft.test.ITUtil.allocate
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glGetString
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL11C.GL_VENDOR
import org.lwjgl.system.MemoryUtil.NULL
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.SkipException
import org.testng.annotations.Test

class OpenGlTessellationPixelIT {
    @Test
    fun `production tessellation stages rasterize an evaluation dependent pixel`() {
        if (System.getenv(ENABLE_ENVIRONMENT) != "true") {
            throw SkipException("Set $ENABLE_ENVIRONMENT=true to run the real OpenGL fixture")
        }

        val errorCallback = GLFWErrorCallback.createPrint(System.err)
        errorCallback.set()
        check(glfwInit()) { "Unable to initialize GLFW for the tessellation fixture" }

        var window = NULL
        try {
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            for (request in OpenGlContextRequest.candidates(
                isMac = PlatformInfo.OS == OSTypes.MAC,
                preferQuads = false,
            )) {
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, request.major)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, request.minor)
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
                glfwWindowHint(
                    GLFW_OPENGL_FORWARD_COMPAT,
                    if (PlatformInfo.OS == OSTypes.MAC) GLFW_TRUE else GLFW_FALSE,
                )
                window = glfwCreateWindow(WINDOW_SIZE, WINDOW_SIZE, "Minosoft tessellation fixture", NULL, NULL)
                if (window != NULL) break
            }
            check(window != NULL) { "Unable to create an OpenGL context for the tessellation fixture" }

            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            if (!GL.getCapabilities().OpenGL40) {
                throw SkipException("The selected driver does not expose OpenGL 4.0 tessellation")
            }
            assertTrue(OpenGlCapabilityDiagnostics.capture().irisTessellation)

            renderAndAssertPixel()
        } finally {
            GL.setCapabilities(null)
            if (window != NULL) glfwDestroyWindow(window)
            glfwTerminate()
            errorCallback.free()
        }
    }

    private fun renderAndAssertPixel() {
        val context = RenderContext::class.java.allocate()
        val system = OpenGlRenderSystem(context, log = false)
        context::system.forceSet(system)
        system::vendor.forceSet(OpenGlVendor.of(requireNotNull(glGetString(GL_VENDOR)).lowercase()))

        val native = system.shader.createGraphics(
            vertex = source("probe.vsh", VERTEX),
            tessellationControl = source("probe.tcs", TESSELLATION_CONTROL),
            tessellationEvaluation = source("probe.tes", TESSELLATION_EVALUATION),
            geometry = null,
            fragment = source("probe.fsh", FRAGMENT),
            patchVertices = 3,
        )
        val shader = object : Shader(native) {}
        val vertices = BufferUtils.createFloatBuffer(9)
            .put(
                floatArrayOf(
                    -1.0f, -1.0f, 0.0f,
                    1.0f, -1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                ),
            )
            .flip()
        val vertexBuffer = system.createVertexBuffer(
            PositionOnlyMeshStruct,
            vertices,
            PrimitiveTypes.TRIANGLE,
            index = null,
            reused = true,
        )

        var shaderLoaded = false
        var bufferLoaded = false
        try {
            native.load()
            shaderLoaded = true
            system.shader.shader = shader
            vertexBuffer.init()
            bufferLoaded = true

            glViewport(0, 0, WINDOW_SIZE, WINDOW_SIZE)
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            glClear(GL_COLOR_BUFFER_BIT)
            vertexBuffer.draw()
            glFinish()

            val pixel = BufferUtils.createByteBuffer(4)
            glReadPixels(WINDOW_SIZE / 2, WINDOW_SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel)
            assertChannel(pixel.get(0).toUByte().toInt(), 32, "TES red channel")
            assertChannel(pixel.get(1).toUByte().toInt(), 128, "TES green channel")
            assertChannel(pixel.get(2).toUByte().toInt(), 223, "TES blue channel")
            assertEquals(pixel.get(3).toUByte().toInt(), 255, "TES alpha channel")
        } finally {
            system.shader.shader = null
            if (bufferLoaded) vertexBuffer.unload()
            if (shaderLoaded) native.unload()
        }

        val resources = system.resources.snapshot()
        assertEquals(resources.live, 0, "Fixture leaked tracked OpenGL resources")
        assertTrue(resources[OpenGlResourceType.PROGRAM].created > 0)
        assertEquals(resources[OpenGlResourceType.SHADER].created, 4)
        assertEquals(resources[OpenGlResourceType.SHADER].deleted, 4)
    }

    private fun assertChannel(actual: Int, expected: Int, message: String) {
        assertTrue(actual in (expected - 2)..(expected + 2), "$message: expected $expected ±2, got $actual")
    }

    private fun source(path: String, code: String): NativeShaderSource {
        return NativeShaderSource(ResourceLocation("minosoft", "tests/tessellation/$path"), code)
    }

    private companion object {
        const val ENABLE_ENVIRONMENT = "MINOSOFT_OPENGL_TESSELLATION_TEST"
        const val WINDOW_SIZE = 16

        val VERTEX = """
            #version 400 core
            layout(location = 0) in vec3 position;
            void main() {
                gl_Position = vec4(position, 1.0);
            }
        """.trimIndent()

        val TESSELLATION_CONTROL = """
            #version 400 core
            layout(vertices = 3) out;
            void main() {
                gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                if (gl_InvocationID == 0) {
                    gl_TessLevelOuter[0] = 1.0;
                    gl_TessLevelOuter[1] = 1.0;
                    gl_TessLevelOuter[2] = 1.0;
                    gl_TessLevelInner[0] = 1.0;
                }
            }
        """.trimIndent()

        val TESSELLATION_EVALUATION = """
            #version 400 core
            layout(triangles, equal_spacing, cw) in;
            out vec3 evaluationColor;
            void main() {
                gl_Position =
                    gl_TessCoord.x * gl_in[0].gl_Position +
                    gl_TessCoord.y * gl_in[1].gl_Position +
                    gl_TessCoord.z * gl_in[2].gl_Position;
                evaluationColor = vec3(0.125, 0.5, 0.875);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 400 core
            in vec3 evaluationColor;
            layout(location = 0) out vec4 color;
            void main() {
                color = vec4(evaluationColor, 1.0);
            }
        """.trimIndent()
    }
}
