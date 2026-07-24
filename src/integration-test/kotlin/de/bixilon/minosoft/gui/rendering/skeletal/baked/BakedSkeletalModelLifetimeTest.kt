/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.baked

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.generation.ContentFidelitySnapshot
import de.bixilon.minosoft.assets.model.generation.ContentGenerationLease
import de.bixilon.minosoft.assets.model.generation.ContentGenerationStore
import de.bixilon.minosoft.assets.model.generation.PreparedContent
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMesh
import de.bixilon.minosoft.gui.rendering.system.base.buffer.GpuBufferStates
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.VertexBuffer
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertThrows
import org.testng.annotations.Test

class BakedSkeletalModelLifetimeTest {

    @Test
    fun `retired loaded model releases GPU buffer after final instance`() {
        val buffer = CountingVertexBuffer()
        val model = model(buffer)
        model.load()
        val instance = model.createInstance(RenderContext::class.java.allocate())
        instance.load()

        model.retire()
        assertEquals(buffer.unloads, 0)
        assertThrows(IllegalStateException::class.java) {
            model.createInstance(RenderContext::class.java.allocate())
        }

        instance.unload()
        assertEquals(buffer.unloads, 1)
    }

    @Test
    fun `retired unuploaded model drops CPU buffer after final instance`() {
        val buffer = CountingVertexBuffer()
        val model = model(buffer)
        val instance = model.createInstance(RenderContext::class.java.allocate())

        model.retire()
        assertEquals(buffer.drops, 0)
        instance.drop()

        assertEquals(buffer.drops, 1)
        assertEquals(buffer.unloads, 0)
    }

    @Test
    fun `retired model without instances disposes immediately`() {
        val loaded = CountingVertexBuffer()
        model(loaded).also {
            it.load()
            it.retire()
        }
        assertEquals(loaded.unloads, 1)

        val preparing = CountingVertexBuffer()
        model(preparing).retire()
        assertEquals(preparing.drops, 1)
    }

    @Test
    fun `content generation lease follows retained model lifetime`() {
        var cleanups = 0
        val store = ContentGenerationStore<ContentFidelitySnapshot>()
        store.reload {
            PreparedContent(ContentFidelitySnapshot(), AutoCloseable { cleanups++ })
        }
        val model = model(CountingVertexBuffer(), requireNotNull(store.lease()))
        model.load()
        val instance = model.createInstance(RenderContext::class.java.allocate())
        instance.load()

        model.retire()
        store.close()
        assertEquals(cleanups, 0)

        instance.unload()
        assertEquals(cleanups, 1)
    }

    private fun model(
        buffer: CountingVertexBuffer,
        contentLease: ContentGenerationLease<ContentFidelitySnapshot>? = null,
    ) = BakedSkeletalModel(
        mesh = Mesh(buffer),
        transform = BakedSkeletalTransform(0, Vec3f.EMPTY, emptyMap()),
        transformCount = 1,
        animations = emptyMap(),
        contentLease = contentLease,
    )

    private class CountingVertexBuffer : VertexBuffer {
        override var state = GpuBufferStates.PREPARING
            private set
        override val vertices = 0
        override val primitive = PrimitiveTypes.QUAD
        override val struct: MeshStruct = SkeletalMesh.SkeletalMeshStruct
        var unloads = 0
            private set
        var drops = 0
            private set

        override fun init() {
            state = GpuBufferStates.INITIALIZED
        }

        override fun unload() {
            unloads++
            state = GpuBufferStates.UNLOADED
        }

        override fun draw() = Unit

        override fun drop() {
            drops++
            state = GpuBufferStates.UNLOADED
        }
    }
}
