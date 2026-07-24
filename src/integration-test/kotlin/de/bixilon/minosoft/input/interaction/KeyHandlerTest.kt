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

package de.bixilon.minosoft.input.interaction

import org.testng.AssertJUnit.assertEquals
import org.testng.AssertJUnit.assertTrue
import org.testng.annotations.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Test(groups = ["interaction"])
class KeyHandlerTest {

    fun `single press`() {
        val handler = TestKeyHandler()
        handler.press()
        assertEquals(handler.actions(), listOf(
            TestKeyHandler.Actions.PRESS,
        ))
        handler.release()
    }

    fun `tick once`() {
        val handler = TestKeyHandler(expectedTicks = 1)
        handler.press()
        assertTrue(handler.awaitExpectedTicks())
        handler.release()
        assertEquals(handler.actions(), listOf(
            TestKeyHandler.Actions.PRESS,
            TestKeyHandler.Actions.TICK,
            TestKeyHandler.Actions.RELEASE,
        ))
    }

    fun `tick twice`() {
        val handler = TestKeyHandler(expectedTicks = 2)
        handler.press()
        assertTrue(handler.awaitExpectedTicks())
        handler.release()
        assertEquals(handler.actions(), listOf(
            TestKeyHandler.Actions.PRESS,
            TestKeyHandler.Actions.TICK,
            TestKeyHandler.Actions.TICK,
            TestKeyHandler.Actions.RELEASE,
        ))
    }

    fun `press and release`() {
        val handler = TestKeyHandler()
        handler.press()
        handler.release()
        assertEquals(handler.actions(), listOf(
            TestKeyHandler.Actions.PRESS,
            TestKeyHandler.Actions.RELEASE,
        ))
    }


    class TestKeyHandler(expectedTicks: Int = 0) : KeyHandler() {
        private val actions: MutableList<Actions> = Collections.synchronizedList(mutableListOf())
        private val expectedTicks = CountDownLatch(expectedTicks)

        enum class Actions {
            PRESS, TICK, RELEASE,
        }

        fun actions(): List<Actions> = synchronized(actions) { actions.toList() }

        fun awaitExpectedTicks(): Boolean = expectedTicks.await(2L, TimeUnit.SECONDS)

        override fun onPress() {
            this.actions += Actions.PRESS
        }

        override fun onRelease() {
            this.actions += Actions.RELEASE
        }

        override fun onTick() {
            this.actions += Actions.TICK
            expectedTicks.countDown()
        }
    }
}
