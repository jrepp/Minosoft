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

package de.bixilon.minosoft.assets.model.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentGenerationStoreTest {

    @Test
    fun `failed preparation preserves active generation`() {
        val store = ContentGenerationStore<String>()
        store.reload { PreparedContent("stable") }

        assertFailsWith<IllegalArgumentException> {
            store.reload { throw IllegalArgumentException("broken candidate") }
        }

        store.lease()!!.use {
            assertEquals(1, it.generationId)
            assertEquals("stable", it.value)
        }
        store.close()
    }

    @Test
    fun `replacement defers cleanup until the final reader exits`() {
        val store = ContentGenerationStore<String>()
        var firstCleaned = false
        store.reload { PreparedContent("first", AutoCloseable { firstCleaned = true }) }
        val first = store.lease()!!

        store.reload { PreparedContent("second") }
        assertFalse(firstCleaned)
        store.lease()!!.use {
            assertEquals(2, it.generationId)
            assertEquals("second", it.value)
        }

        first.close()
        assertTrue(firstCleaned)
        store.close()
    }

    @Test
    fun `closing retires active content and blocks new generations`() {
        val store = ContentGenerationStore<String>()
        var cleaned = 0
        store.reload { PreparedContent("active", AutoCloseable { cleaned++ }) }
        val lease = store.lease()!!

        store.close()
        assertNull(store.lease())
        assertEquals(0, cleaned)
        lease.close()
        assertEquals(1, cleaned)
        assertFailsWith<IllegalStateException> { store.reload { PreparedContent("late") } }
    }

    @Test
    fun `failed commit closes candidate and preserves active generation`() {
        val store = ContentGenerationStore<String>()
        var candidateCleaned = false
        store.reload { PreparedContent("stable") }

        assertFailsWith<IllegalStateException> {
            store.reload(
                prepare = { PreparedContent("candidate", AutoCloseable { candidateCleaned = true }) },
                commit = { throw IllegalStateException("cannot publish") },
            )
        }

        assertTrue(candidateCleaned)
        store.lease()!!.use { assertEquals("stable", it.value) }
        store.close()
    }
}
