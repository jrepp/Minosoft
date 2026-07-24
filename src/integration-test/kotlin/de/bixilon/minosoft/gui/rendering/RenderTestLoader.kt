/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering

import de.bixilon.kutil.latch.SimpleLatch
import de.bixilon.kutil.observer.DataObserver
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.kutil.time.TimeUtil.sleep
import de.bixilon.minosoft.assets.AssetsLoader
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.models.item.FlatItemRender
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions
import de.bixilon.minosoft.gui.rendering.system.dummy.DummyRenderSystem
import de.bixilon.minosoft.gui.rendering.system.window.dummy.DummyWindow
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds

@Test(priority = 100, groups = ["rendering"])
class RenderTestLoader {

    fun init() {
        val session = createSession(5)
        val latch = SimpleLatch(1)
        session::assets.forceSet(AssetsLoader.create(session.profiles.resources, session.version))
        session.assets.load(latch)
        session::error.forceSet(DataObserver(null))
        RenderTestUtil.rendering = Rendering(session)
        RenderTestUtil.rendering.start(latch, audio = false)
        latch.dec()
        while (latch.count > 0) {
            sleep(10.milliseconds)
            session.error?.let { throw it }
        }
        val context = RenderTestUtil.rendering.context
        assertTrue(context.window is DummyWindow)
        assertTrue(context.system is DummyRenderSystem)
        RenderTestUtil.context = context
    }

    @Test(dependsOnMethods = ["init"])
    fun `dedicated generated block item model overrides world block model`() {
        val items = RenderTestUtil.context.session.registries.item

        assertTrue(items[minecraft("ladder")]?.model is FlatItemRender)
        assertFalse(items[minecraft("oak_planks")]?.model is FlatItemRender)
    }

    @Test(dependsOnMethods = ["init"])
    fun `generated handheld item preserves first person display`() {
        val sword = RenderTestUtil.context.session.registries.item[minecraft("wooden_sword")]?.model
        assertTrue(sword is FlatItemRender)
        assertTrue(sword?.getDisplay(DisplayPositions.FIRST_PERSON_RIGHT_HAND) != null)
        assertTrue(sword?.getDisplay(DisplayPositions.FIRST_PERSON_LEFT_HAND) != null)
    }

}
