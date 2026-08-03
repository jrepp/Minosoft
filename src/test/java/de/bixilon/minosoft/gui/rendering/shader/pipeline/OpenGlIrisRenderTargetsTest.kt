/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferManager
import de.bixilon.minosoft.gui.rendering.framebuffer.world.MainWorldTarget
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.RenderingCapabilities
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShaderSource
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import de.bixilon.minosoft.gui.rendering.system.opengl.vendor.OpenGlVendor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil.NULL
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenGlIrisRenderTargetsTest {
    @Test
    fun `production Iris targets preserve history copies depth restore state resize and retire`() {
        assumeTrue(System.getenv(ENABLE_ENVIRONMENT) == "true", "Set $ENABLE_ENVIRONMENT=true to run the real OpenGL fixture")
        val callback = GLFWErrorCallback.createPrint(System.err).also(GLFWErrorCallback::set)
        check(glfwInit()) { "Unable to initialize GLFW for the Iris target fixture" }
        var window = NULL
        try {
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
            window = glfwCreateWindow(16, 16, "Minosoft Iris target fixture", NULL, NULL)
            check(window != NULL) { "Unable to create a hidden OpenGL 4.1 context" }
            glfwMakeContextCurrent(window)
            val capabilities = GL.createCapabilities()
            assertTrue(capabilities.OpenGL41, "The fixture did not receive OpenGL 4.1")
            executeFixture()
        } finally {
            GL.setCapabilities(null)
            if (window != NULL) glfwDestroyWindow(window)
            glfwTerminate()
            callback.free()
        }
    }

    private fun executeFixture() {
        val context = allocate<RenderContext>()
        val system = OpenGlRenderSystem(context, log = false)
        context::system.forceSet(system)
        system::vendor.forceSet(OpenGlVendor.of(requireNotNull(glGetString(GL_VENDOR)).lowercase()))
        val main = allocate<MainWorldTarget>().apply {
            size = Vec2i(SIZE, SIZE)
            scale = 1.0f
        }
        val manager = allocate<FramebufferManager>()
        manager::main.forceSet(main)
        context::framebuffer.forceSet(manager)
        system.viewport = Vec2i(SIZE, SIZE)

        val targets = IrisOpenGlRenderTargets(context, bufferPlan())
        val customTextures = IrisOpenGlCustomTextures(context, IrisTexturePlan(custom = emptyList()))
        val customResources = IrisOpenGlCustomResources(context, IrisCustomResourcePlan())
        val programs = mutableListOf<Pair<de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader, Shader>>()
        val vao = glGenVertexArrays()
        var prepared = false
        try {
            targets.prepare()
            prepared = true
            customResources.prepare(Vec2i(SIZE, SIZE))
            glBindVertexArray(vao)

            val write = program("write", ShaderProgramPhase.PREPARE, listOf(COLOR0), emptyMap(), setOf(COLOR0))
            val copy = program("copy", ShaderProgramPhase.COMPOSITE, listOf(COLOR1), mapOf("history" to COLOR0), setOf(COLOR1))
            val writeShader = shader(system, "write", CONSTANT_FRAGMENT).also(programs::add)
            val copyShader = shader(system, "copy", COPY_FRAGMENT).also(programs::add)

            draw(targets, customTextures, customResources, system, write, writeShader)
            targets.finish(write)
            draw(targets, customTextures, customResources, system, copy, copyShader)
            assertPixel(32, 128, 223)
            targets.finish(copy)

            targets.beginFrame(allocate())
            draw(targets, customTextures, customResources, system, copy, copyShader)
            assertPixel(32, 128, 223)
            targets.finish(copy)

            assertIntegerClear(targets)
            assertDepthSnapshots(targets)
            assertRawAndComparisonOrder(targets, customTextures, customResources, system, programs)
            assertMipmapFilterScope(targets, customTextures, customResources, system, programs)
            assertStateRestoration(system)

            val oldTexture = targets.textureName(COLOR0)
            main.size = Vec2i(SIZE + 2, SIZE - 2)
            targets.beginFrame(allocate())
            assertNotEquals(oldTexture, targets.textureName(COLOR0))
        } finally {
            system.shader.shader = null
            programs.asReversed().forEach { (native, _) -> native.unload() }
            glDeleteVertexArrays(vao)
            customResources.close()
            customTextures.close()
            if (prepared) targets.close()
        }

        val resources = system.resources.snapshot()
        assertEquals(0, resources.live, "Iris target fixture leaked tracked OpenGL resources")
        assertEquals(resources[OpenGlResourceType.TEXTURE].created, resources[OpenGlResourceType.TEXTURE].deleted)
        assertEquals(resources[OpenGlResourceType.FRAMEBUFFER].created, resources[OpenGlResourceType.FRAMEBUFFER].deleted)
        assertEquals(resources[OpenGlResourceType.PROGRAM].created, resources[OpenGlResourceType.PROGRAM].deleted)
        assertEquals(resources[OpenGlResourceType.SHADER].created, resources[OpenGlResourceType.SHADER].deleted)
    }

    private fun draw(
        targets: IrisOpenGlRenderTargets,
        textures: IrisOpenGlCustomTextures,
        resources: IrisOpenGlCustomResources,
        system: OpenGlRenderSystem,
        program: ShaderProgramSource,
        compiled: Pair<de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader, Shader>,
    ) {
        targets.bindProgram(RenderViewId.MAIN, program)
        system.shader.shader = compiled.second
        targets.bindSamplers(program, compiled.first, textures, resources)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glFinish()
    }

    private fun assertPixel(red: Int, green: Int, blue: Int) {
        val pixel = BufferUtils.createByteBuffer(4)
        glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel)
        assertChannel(pixel.get(0).toUByte().toInt(), red)
        assertChannel(pixel.get(1).toUByte().toInt(), green)
        assertChannel(pixel.get(2).toUByte().toInt(), blue)
        assertEquals(255, pixel.get(3).toUByte().toInt())
    }

    private fun assertIntegerClear(targets: IrisOpenGlRenderTargets) {
        glBindTexture(GL_TEXTURE_2D, targets.textureName(INTEGER_COLOR))
        val value = BufferUtils.createIntBuffer(SIZE * SIZE)
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RED_INTEGER, GL_INT, value)
        assertTrue((0 until value.capacity()).all { value.get(it) == 7 })
    }

    private fun assertDepthSnapshots(targets: IrisOpenGlRenderTargets) {
        listOf(
            ShaderDepthSnapshot.DISTANT_BEFORE_TRANSLUCENT,
            ShaderDepthSnapshot.BEFORE_TRANSLUCENT,
            ShaderDepthSnapshot.BEFORE_HAND,
            ShaderDepthSnapshot.SHADOW_BEFORE_TRANSLUCENT,
        ).forEach { snapshot -> assertTrue(targets.snapshotDepth(snapshot), "Depth snapshot failed: $snapshot") }
    }

    private fun assertRawAndComparisonOrder(
        targets: IrisOpenGlRenderTargets,
        textures: IrisOpenGlCustomTextures,
        resources: IrisOpenGlCustomResources,
        system: OpenGlRenderSystem,
        programs: MutableList<Pair<de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader, Shader>>,
    ) {
        val raw = program("raw", ShaderProgramPhase.FINAL, emptyList(), mapOf("shadowtex0" to SHADOW_DEPTH))
        val comparison = raw.copy(
            name = "comparison",
            samplers = setOf("shadowtex0"),
            shadowSamplers = setOf("shadowtex0"),
            resourceUsage = raw.resourceUsage.copy(shadowComparisonSamplers = setOf("shadowtex0")),
        )
        val rawShader = shader(system, "raw", RAW_DEPTH_FRAGMENT).also(programs::add)
        val comparisonShader = shader(system, "comparison", COMPARE_DEPTH_FRAGMENT).also(programs::add)
        fun bind(program: ShaderProgramSource, compiled: Pair<de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader, Shader>, expected: Int) {
            system.shader.shader = compiled.second
            targets.bindSamplers(program, compiled.first, textures, resources)
            system.bindTexture(0, GL_TEXTURE_2D, targets.textureName(SHADOW_DEPTH))
            assertEquals(expected, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE))
        }
        bind(raw, rawShader, GL_NONE)
        bind(comparison, comparisonShader, GL_COMPARE_REF_TO_TEXTURE)
        bind(raw, rawShader, GL_NONE)
    }

    private fun assertStateRestoration(system: OpenGlRenderSystem) {
        system[RenderingCapabilities.BLENDING] = true
        system[RenderingCapabilities.DEPTH_TEST] = false
        system[RenderingCapabilities.FACE_CULLING] = true
        system.depthMask = true
        system.polygonMode = PolygonModes.LINE
        system.viewport = Vec2i(SIZE, SIZE)
        system.activeTexture(3)
        val expected = IrisOpenGlStateSnapshot.capture(system)
        try {
            system.reset(depthTest = true, blending = false, faceCulling = false, depthMask = false)
            system.polygonMode = PolygonModes.FILL
            system.viewport = Vec2i(3, 5)
            system.activeTexture(1)
            throw InjectedFailure()
        } catch (_: InjectedFailure) {
            expected.restore(system)
        }
        assertEquals(GL_TEXTURE0 + 3, glGetInteger(GL_ACTIVE_TEXTURE))
        assertEquals(expected.readFramebuffer, glGetInteger(GL_READ_FRAMEBUFFER_BINDING))
        assertEquals(expected.drawFramebuffer, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING))
        assertTrue(glIsEnabled(GL_BLEND))
        assertTrue(!glIsEnabled(GL_DEPTH_TEST))
        assertTrue(glIsEnabled(GL_CULL_FACE))
        assertTrue(glGetBoolean(GL_DEPTH_WRITEMASK))
        assertEquals(PolygonModes.LINE, system.polygonMode)
        assertEquals(Vec2i(SIZE, SIZE), system.viewport)
    }

    private fun assertMipmapFilterScope(
        targets: IrisOpenGlRenderTargets,
        textures: IrisOpenGlCustomTextures,
        resources: IrisOpenGlCustomResources,
        system: OpenGlRenderSystem,
        programs: MutableList<Pair<de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader, Shader>>,
    ) {
        val ordinary = program("ordinary-filter", ShaderProgramPhase.FINAL, emptyList(), mapOf("history" to COLOR0))
        val mipmapped = ordinary.copy(
            name = "mipmapped-filter",
            resourceUsage = ordinary.resourceUsage.copy(mipmapsBefore = setOf(COLOR0)),
        )
        val ordinaryShader = shader(system, "ordinary-filter", COPY_FRAGMENT).also(programs::add)
        val mipmappedShader = shader(system, "mipmapped-filter", COPY_FRAGMENT).also(programs::add)
        fun bind(program: ShaderProgramSource, compiled: Pair<de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader, Shader>, expected: Int) {
            system.shader.shader = compiled.second
            targets.bindSamplers(program, compiled.first, textures, resources)
            system.bindTexture(0, GL_TEXTURE_2D, targets.textureName(COLOR0))
            assertEquals(expected, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER))
        }

        bind(ordinary, ordinaryShader, GL_NEAREST)
        bind(mipmapped, mipmappedShader, GL_NEAREST_MIPMAP_NEAREST)
        bind(ordinary, ordinaryShader, GL_NEAREST)
    }

    private fun shader(system: OpenGlRenderSystem, name: String, fragment: String): Pair<de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader, Shader> {
        val native = system.shader.createGraphics(source("$name.vsh", VERTEX), null, null, null, source("$name.fsh", fragment))
        native.load()
        return native to object : Shader(native) {}
    }

    private fun program(
        name: String,
        phase: ShaderProgramPhase,
        writes: List<ShaderBufferId>,
        samples: Map<String, ShaderBufferId>,
        flips: Set<ShaderBufferId> = emptySet(),
    ) = ShaderProgramSource(
        name = name,
        phase = phase,
        vertex = VERTEX,
        fragment = if (samples.isEmpty()) CONSTANT_FRAGMENT else COPY_FRAGMENT,
        uniforms = emptySet(),
        samplers = samples.keys,
        resourceUsage = ShaderProgramResourceUsage(colorWrites = writes, sampledBuffers = samples, flipsAfter = flips),
    )

    private fun bufferPlan() = ShaderBufferPlan(
        listOf(
            color(COLOR0, RenderColorFormat.RGBA8, RenderClearPolicy.LOAD, 0.0f, mipmapped = true),
            color(COLOR1, RenderColorFormat.RGBA8, RenderClearPolicy.CLEAR, 0.0f),
            color(INTEGER_COLOR, RenderColorFormat.R32I, RenderClearPolicy.CLEAR, 7.0f),
            depth(ShaderBufferKind.DEPTHTEX, 0, RenderTargetSize.Relative(1.0f)),
            depth(ShaderBufferKind.DEPTHTEX, 1, RenderTargetSize.Relative(1.0f)),
            depth(ShaderBufferKind.DEPTHTEX, 2, RenderTargetSize.Relative(1.0f)),
            depth(ShaderBufferKind.DHDEPTHTEX, 0, RenderTargetSize.Relative(1.0f)),
            depth(ShaderBufferKind.DHDEPTHTEX, 1, RenderTargetSize.Relative(1.0f)),
            color(SHADOW_COLOR, RenderColorFormat.RGBA8, RenderClearPolicy.CLEAR, 0.0f, RenderTargetSize.Fixed(4, 4)),
            depth(ShaderBufferKind.SHADOWTEX, 0, RenderTargetSize.Fixed(4, 4), ShaderBufferFilter.SHADOW_COMPARE),
            depth(ShaderBufferKind.SHADOWTEX, 1, RenderTargetSize.Fixed(4, 4), ShaderBufferFilter.SHADOW_COMPARE),
        ),
    )

    private fun color(
        id: ShaderBufferId,
        format: RenderColorFormat,
        clear: RenderClearPolicy,
        value: Float,
        size: RenderTargetSize = RenderTargetSize.Relative(1.0f),
        mipmapped: Boolean = false,
    ) = ShaderBufferDescriptor(
        id,
        ShaderBufferFormat.Color(format),
        size,
        clear,
        ShaderBufferClearColor.Fixed(listOf(value, 0.0f, 0.0f, 1.0f)),
        ShaderBufferFilter.NEAREST,
        mipmapped,
        true,
    )

    private fun depth(kind: ShaderBufferKind, index: Int, size: RenderTargetSize, filter: ShaderBufferFilter = ShaderBufferFilter.NEAREST) =
        ShaderBufferDescriptor(ShaderBufferId(kind, index), ShaderBufferFormat.Depth(RenderDepthFormat.DEPTH24), size, RenderClearPolicy.CLEAR, ShaderBufferClearColor.Fixed(listOf(1.0f, 0.0f, 0.0f, 0.0f)), filter, false, false)

    private fun source(name: String, code: String) = NativeShaderSource(ResourceLocation("minosoft", "tests/iris-targets/$name"), code)
    private fun assertChannel(actual: Int, expected: Int) = assertTrue(actual in expected - 2..expected + 2, "expected $expected ±2, got $actual")

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> allocate(): T = unsafe.allocateInstance(T::class.java) as T

    private class InjectedFailure : RuntimeException()

    private companion object {
        const val ENABLE_ENVIRONMENT = "MINOSOFT_OPENGL_IRIS_TARGETS_TEST"
        const val SIZE = 8
        val COLOR0 = ShaderBufferId(ShaderBufferKind.COLORTEX, 0)
        val COLOR1 = ShaderBufferId(ShaderBufferKind.COLORTEX, 1)
        val INTEGER_COLOR = ShaderBufferId(ShaderBufferKind.COLORTEX, 2)
        val SHADOW_COLOR = ShaderBufferId(ShaderBufferKind.SHADOWCOLOR, 0)
        val SHADOW_DEPTH = ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0)
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null) as Unsafe
        val VERTEX = """
            #version 410 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()
        val CONSTANT_FRAGMENT = """
            #version 410 core
            layout(location = 0) out vec4 color;
            void main() { color = vec4(0.125, 0.5, 0.875, 1.0); }
        """.trimIndent()
        val COPY_FRAGMENT = """
            #version 410 core
            uniform sampler2D history;
            layout(location = 0) out vec4 color;
            void main() { color = texelFetch(history, ivec2(gl_FragCoord.xy), 0); }
        """.trimIndent()
        val RAW_DEPTH_FRAGMENT = """
            #version 410 core
            uniform sampler2D shadowtex0;
            layout(location = 0) out vec4 color;
            void main() { color = vec4(texture(shadowtex0, vec2(0.5)).rrr, 1.0); }
        """.trimIndent()
        val COMPARE_DEPTH_FRAGMENT = """
            #version 410 core
            uniform sampler2DShadow shadowtex0;
            layout(location = 0) out vec4 color;
            void main() { color = vec4(texture(shadowtex0, vec3(0.5)), 0.0, 0.0, 1.0); }
        """.trimIndent()
    }
}
