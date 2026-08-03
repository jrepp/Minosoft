/*
 * Minosoft
 * Copyright (C) 2020-2024 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.entities

import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.entities.entities.monster.Spider
import de.bixilon.minosoft.data.entities.entities.monster.Husk
import de.bixilon.minosoft.data.entities.entities.monster.Zombie
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.createEntity
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.create
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.FallbackLivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.monster.ZombieRenderer
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["entity_renderer", "rendering"])
class EntityRendererManagerTest {


    private fun create(): EntityRendererManager {
        val renderer = EntityRendererTestUtil.create()
        return EntityRendererManager(renderer)
    }

    fun setup() {
        val renderer = create()
        renderer.init()
    }

    fun `spawn single entity`() {
        val renderer = create()
        renderer.init()
        val entity = renderer.renderer.createEntity(Pig)
        assertEquals(renderer.size, 0)
        renderer.renderer.session.world.entities.add(1, null, entity)
        renderer.renderer.queue.work()
        assertEquals(renderer.size, 1)
        renderer.renderer.session.world.entities.remove(1)
        renderer.renderer.queue.work()
        assertEquals(renderer.size, 0)
    }

    fun `spawn multiple entities`() {
        val renderer = create()
        renderer.init()
        val e1 = renderer.renderer.createEntity(Pig)
        val e2 = renderer.renderer.createEntity(Pig)
        val e3 = renderer.renderer.createEntity(Pig)
        assertEquals(renderer.size, 0)
        renderer.renderer.session.world.entities.add(1, null, e1)
        renderer.renderer.session.world.entities.add(2, null, e2)
        renderer.renderer.queue.work()
        assertEquals(renderer.size, 2)
        renderer.renderer.session.world.entities.add(3, null, e3)
        renderer.renderer.queue.work()
        assertEquals(renderer.size, 3)
        renderer.renderer.session.world.entities.remove(1)
        renderer.renderer.queue.work()
        assertEquals(renderer.size, 2)
        renderer.renderer.session.world.entities.remove(2)
        renderer.renderer.session.world.entities.remove(3)
        renderer.renderer.queue.work()
        assertEquals(renderer.size, 0)
    }

    fun `zombie uses textured renderer`() {
        val renderer = EntityRendererTestUtil.create().create(Zombie)
        assertTrue(renderer is ZombieRenderer)
    }

    fun `zombie variants remain visible`() {
        val renderer = EntityRendererTestUtil.create().create(Husk)
        assertTrue(renderer is ZombieRenderer)
    }

    fun `unmapped living mob uses visible fallback`() {
        val renderer = EntityRendererTestUtil.create().create(Spider)
        assertTrue(renderer is FallbackLivingEntityRenderer)
    }

    fun `entity GPU retirement waits until frame drawing completes`() {
        val entities = EntityRendererTestUtil.create()
        val entity = entities.createEntity(Pig)
        var unloaded = false
        val renderer = object : EntityRenderer<Pig>(entities, entity) {
            override fun unload() {
                unloaded = true
            }
        }

        entities.renderers.unload(renderer)
        entities.queue.work()
        assertFalse(unloaded)

        entities.postDraw()
        assertTrue(unloaded)
    }
}
