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

package de.bixilon.minosoft.gui.rendering.graph.resource

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionalGenerationStoreTest {
    @Test
    fun `failed preparation preserves active generation`() {
        val retired = mutableListOf<String>()
        val store = TransactionalGenerationStore("active", retired::add)
        val active = store.acquire()

        assertThrows<IllegalArgumentException> {
            store.replace { throw IllegalArgumentException("candidate failed") }
        }

        assertEquals(active.generation, 0L)
        assertEquals(active.value, "active")
        assertEquals(store.stats().activeGeneration, 0L)
        assertTrue(retired.isEmpty())
        active.close()
        store.close()
        assertEquals(retired, listOf("active"))
    }

    @Test
    fun `successful publication retires previous generation after its last lease`() {
        val retired = mutableListOf<String>()
        val store = TransactionalGenerationStore("old", retired::add)
        val oldLease = store.acquire()

        assertEquals(store.replace { "new" }, 1L)
        assertTrue(retired.isEmpty())
        assertEquals(store.stats().retiredAwaitingLeases, 1)

        val newLease = store.acquire()
        assertEquals(newLease.value, "new")
        oldLease.close()
        assertEquals(retired, listOf("old"))
        assertEquals(store.stats().retiredAwaitingLeases, 0)

        newLease.close()
        store.close()
        assertEquals(retired, listOf("old", "new"))
    }

    @Test
    fun `replacement and lease close are idempotent at ownership boundaries`() {
        val retired = mutableListOf<Int>()
        val store = TransactionalGenerationStore(0, retired::add)
        val old = store.acquire()
        store.replace { 1 }

        old.close()
        old.close()
        assertEquals(retired, listOf(0))

        store.close()
        store.close()
        assertEquals(retired, listOf(0, 1))
        assertTrue(store.stats().closed)
        assertNull(store.stats().activeGeneration)
    }

    @Test
    fun `candidate prepared after closure is retired and rejected`() {
        val retired = mutableListOf<String>()
        val store = TransactionalGenerationStore("active", retired::add)
        store.close()

        assertThrows<IllegalStateException> { store.replace { "candidate" } }

        assertEquals(retired, listOf("active", "candidate"))
    }
}
