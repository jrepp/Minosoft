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

package de.bixilon.minosoft.gui.rendering.system.opengl.resource

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenGlResourceTrackerTest {

    @Test
    fun `tracks independent typed handle namespaces`() {
        val tracker = OpenGlResourceTracker()
        tracker.created(OpenGlResourceType.BUFFER, 7)
        tracker.created(OpenGlResourceType.TEXTURE, 7)
        tracker.deleted(OpenGlResourceType.BUFFER, 7)

        val snapshot = tracker.snapshot()
        assertEquals(snapshot.created, 2L)
        assertEquals(snapshot.deleted, 1L)
        assertEquals(snapshot.live, 1)
        assertEquals(snapshot[OpenGlResourceType.BUFFER].live, 0)
        assertEquals(snapshot[OpenGlResourceType.TEXTURE].live, 1)
    }

    @Test
    fun `rejects duplicate creation and unknown deletion`() {
        val tracker = OpenGlResourceTracker()
        tracker.created(OpenGlResourceType.PROGRAM, 3)

        assertFailsWith<IllegalStateException> {
            tracker.created(OpenGlResourceType.PROGRAM, 3)
        }
        assertFailsWith<IllegalStateException> {
            tracker.deleted(OpenGlResourceType.SHADER, 3)
        }
        assertFailsWith<IllegalArgumentException> {
            tracker.deleted(OpenGlResourceType.PROGRAM, 0)
        }
    }

    @Test
    fun `allows handle reuse after deletion`() {
        val tracker = OpenGlResourceTracker()
        tracker.created(OpenGlResourceType.QUERY, 11)
        tracker.deleted(OpenGlResourceType.QUERY, 11)
        tracker.created(OpenGlResourceType.QUERY, 11)

        val query = tracker.snapshot()[OpenGlResourceType.QUERY]
        assertEquals(query.created, 2L)
        assertEquals(query.deleted, 1L)
        assertEquals(query.live, 1)
    }
}
