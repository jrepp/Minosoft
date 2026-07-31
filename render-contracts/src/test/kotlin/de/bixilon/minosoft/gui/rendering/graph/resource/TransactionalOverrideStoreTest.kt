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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionalOverrideStoreTest {
    @Test
    fun `stale registration cannot remove current override`() {
        val retired = mutableListOf<String>()
        val store = TransactionalOverrideStore("fallback", retired::add)
        val first = store.replace { "first" }
        val second = store.replace { "second" }

        first.close()
        assertEquals("second", store.acquire().use { it.value })

        second.close()
        assertEquals("fallback", store.acquire().use { it.value })
        assertEquals(listOf("fallback", "first", "second"), retired)
        store.close()
        assertEquals(listOf("fallback", "first", "second", "fallback"), retired)
    }

    @Test
    fun `override retirement waits for its final lease`() {
        val retired = mutableListOf<String>()
        val store = TransactionalOverrideStore("fallback", retired::add)
        val registration = store.replace { "override" }
        val lease = store.acquire()

        registration.close()
        assertFalse("override" in retired)
        assertEquals(1, store.stats().retiredAwaitingLeases)

        lease.close()
        assertTrue("override" in retired)
        assertEquals(0, store.stats().retiredAwaitingLeases)
        store.close()
    }
}
