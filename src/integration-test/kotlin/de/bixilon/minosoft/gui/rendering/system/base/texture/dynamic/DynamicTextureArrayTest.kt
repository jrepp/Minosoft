/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.concurrent.queue.Queue
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyDynamicTextureArray
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertThrows
import org.testng.annotations.Test

@Test(groups = ["rendering", "textures"])
class DynamicTextureArrayTest {

    fun `capacity growth schedules one replacement generation`() {
        val context = RenderContext::class.java.allocate()
        context::queue.forceSet(Queue())
        val array = DummyDynamicTextureArray(context)

        array.push("first", async = false) { RGBA8Buffer(Vec2i(1, 1)) }
        array.push("second", async = false) { RGBA8Buffer(Vec2i(1, 1)) }

        assertEquals(array.capacity, 2)
        assertEquals(context.queue.size, 1)
        assertEquals(array.reloads, 0)

        context.queue.work()

        assertEquals(array.reloads, 1)
        assertEquals(array.generation, 1)
    }

    fun `failed candidate reload preserves the active generation`() {
        val context = RenderContext::class.java.allocate()
        val array = DummyDynamicTextureArray(context)
        array.reload()
        array.failNextReload = true

        assertThrows(IllegalStateException::class.java) { array.reload() }

        assertEquals(array.reloads, 2)
        assertEquals(array.generation, 1)
    }

    fun `retained dynamic texture can be refreshed after storage replacement`() {
        val context = RenderContext::class.java.allocate()
        context::queue.forceSet(Queue())
        val array = DummyDynamicTextureArray(context)
        val texture = array.push("skin", async = false) { RGBA8Buffer(Vec2i(64, 64)) }

        assertEquals(array.uploads, 1)
        array.reload()
        array.refresh(texture)

        assertEquals(array.storageGeneration, 1L)
        assertEquals(array.uploads, 2)
        assertEquals(texture.state, DynamicTextureState.LOADED)
    }

    @Test(timeOut = 1_000)
    fun `synchronous upload failure releases the array lock`() {
        val context = RenderContext::class.java.allocate()
        context::queue.forceSet(Queue())
        val array = DummyDynamicTextureArray(context)
        array.failNextUpload = true

        assertThrows(IllegalStateException::class.java) {
            array.push("failed", async = false) { RGBA8Buffer(Vec2i(1, 1)) }
        }
        val recovered = array.push("recovered", async = false) { RGBA8Buffer(Vec2i(1, 1)) }

        assertEquals(recovered.state, DynamicTextureState.LOADED)
    }
}
