/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.os.OSTypes
import de.bixilon.kutil.os.PlatformInfo
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShaderSource
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlCapabilityDiagnostics
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import de.bixilon.minosoft.gui.rendering.system.opengl.vendor.OpenGlVendor
import de.bixilon.minosoft.gui.rendering.system.window.glfw.OpenGlContextRequest
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
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glGetString
import org.lwjgl.opengl.GL11.glGetTexImage
import org.lwjgl.opengl.GL11C.GL_VENDOR
import org.lwjgl.opengl.GL15.glGetBufferSubData
import org.lwjgl.opengl.GL30.glGetIntegeri
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_BINDING
import org.lwjgl.opengl.GL43.glBindBuffer
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.system.MemoryUtil.NULL
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sun.misc.Unsafe

class OpenGlIrisCustomResourceComputeTest {
    @Test
    fun `production Iris image and SSBO cross compute and retire cleanly`() {
        assumeTrue(
            System.getenv(ENABLE_ENVIRONMENT) == "true",
            "Set $ENABLE_ENVIRONMENT=true to run the real OpenGL fixture",
        )

        val errorCallback = GLFWErrorCallback.createPrint(System.err)
        errorCallback.set()
        check(glfwInit()) { "Unable to initialize GLFW for the Iris compute fixture" }

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
                window = glfwCreateWindow(16, 16, "Minosoft Iris compute fixture", NULL, NULL)
                if (window != NULL) break
            }
            check(window != NULL) { "Unable to create an OpenGL context for the Iris compute fixture" }

            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            val capabilities = OpenGlCapabilityDiagnostics.capture()
            val supportsRequiredFeatures = capabilities.irisCompute &&
                capabilities.irisCustomImages &&
                capabilities.irisShaderStorageBuffers
            val capabilityMessage =
                "The selected driver does not expose compute + clearable images + SSBOs: $capabilities"
            if (System.getenv(REQUIRE_CAPABILITIES_ENVIRONMENT) == "true") {
                assertTrue(supportsRequiredFeatures, capabilityMessage)
            } else {
                assumeTrue(supportsRequiredFeatures, capabilityMessage)
            }

            executeAndVerify()
        } finally {
            GL.setCapabilities(null)
            if (window != NULL) glfwDestroyWindow(window)
            glfwTerminate()
            errorCallback.free()
        }
    }

    private fun executeAndVerify() {
        val context = allocateContext()
        val system = OpenGlRenderSystem(context, log = false)
        context::system.forceSet(system)
        system::vendor.forceSet(OpenGlVendor.of(requireNotNull(glGetString(GL_VENDOR)).lowercase()))

        val plan = IrisCustomResourcePlan(
            images = listOf(
                IrisCustomImageDescriptor(
                    name = "probeImage",
                    sampler = "probeSampler",
                    target = IrisCustomImageTarget.TEXTURE_2D,
                    format = IrisCustomImageFormat.RGBA,
                    internalFormat = IrisCustomImageInternalFormat.RGBA8,
                    type = IrisCustomImageType.UNSIGNED_BYTE,
                    clear = true,
                    size = IrisCustomImageSize.Absolute(4, 4),
                ),
            ),
            shaderStorageBuffers = listOf(
                IrisShaderStorageBufferDescriptor(index = 0, bytesPerElement = Int.SIZE_BYTES.toLong()),
            ),
        )
        val resources = IrisOpenGlCustomResources(context, plan)
        val native = system.shader.createCompute(
            NativeShaderSource(
                ResourceLocation("minosoft", "tests/iris/custom_resource_probe.csh"),
                COMPUTE,
            ),
        )
        val shader = object : Shader(native) {}

        var resourcesPrepared = false
        var shaderLoaded = false
        try {
            resources.prepare(Vec2i(4, 4))
            resourcesPrepared = true
            resources.beginFrame(Vec2i(4, 4))
            assertEquals(resources.physicalImageCount, 1)
            assertEquals(resources.physicalBufferCount, 1)

            native.load()
            shaderLoaded = true
            system.shader.shader = shader
            assertEquals(resources.bindImages(native, setOf("probeImage")), 1)

            glDispatchCompute(1, 1, 1)
            resources.memoryBarrier(force = true)
            glFinish()

            resources.bindSampler("probeSampler", 0)
            val pixels = BufferUtils.createByteBuffer(4 * 4 * 4)
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            val offset = (1 * 4 + 2) * 4
            assertChannel(pixels.get(offset).toUByte().toInt(), 32, "image red")
            assertChannel(pixels.get(offset + 1).toUByte().toInt(), 128, "image green")
            assertChannel(pixels.get(offset + 2).toUByte().toInt(), 223, "image blue")
            assertEquals(pixels.get(offset + 3).toUByte().toInt(), 255, "image alpha")

            val storage = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0)
            assertTrue(storage > 0, "Custom resource did not bind bufferObject.0")
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, storage)
            val stored = BufferUtils.createIntBuffer(1)
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, stored)
            assertEquals(stored.get(0), STORAGE_SENTINEL)
        } finally {
            system.shader.shader = null
            if (shaderLoaded) native.unload()
            if (resourcesPrepared) resources.close()
        }

        val snapshot = system.resources.snapshot()
        assertEquals(snapshot.live, 0, "Iris compute fixture leaked tracked OpenGL resources")
        assertTrue(snapshot[OpenGlResourceType.TEXTURE].created >= 1)
        assertTrue(snapshot[OpenGlResourceType.BUFFER].created >= 1)
        assertTrue(snapshot[OpenGlResourceType.PROGRAM].created >= 1)
        assertTrue(snapshot[OpenGlResourceType.SHADER].created >= 1)
    }

    private fun assertChannel(actual: Int, expected: Int, message: String) {
        assertTrue(actual in (expected - 2)..(expected + 2), "$message: expected $expected ±2, got $actual")
    }

    private fun allocateContext(): RenderContext {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(RenderContext::class.java) as RenderContext
    }

    private companion object {
        const val ENABLE_ENVIRONMENT = "MINOSOFT_OPENGL_IRIS_COMPUTE_TEST"
        const val REQUIRE_CAPABILITIES_ENVIRONMENT =
            "MINOSOFT_OPENGL_IRIS_COMPUTE_REQUIRE_CAPABILITIES"
        const val STORAGE_SENTINEL = 0x12345678

        val COMPUTE = """
            #version 430 core
            layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
            layout(rgba8) uniform image2D probeImage;
            layout(std430, binding = 0) buffer ProbeStorage {
                uint storedValue;
            };
            void main() {
                imageStore(probeImage, ivec2(2, 1), vec4(0.125, 0.5, 0.875, 1.0));
                storedValue = 0x12345678u;
            }
        """.trimIndent()
    }
}
