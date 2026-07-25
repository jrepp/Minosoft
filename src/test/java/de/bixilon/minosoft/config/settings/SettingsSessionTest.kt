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

package de.bixilon.minosoft.config.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SettingsSessionTest {
    @Test
    fun `staged changes apply and report restart markers`() {
        var enabled = false
        var quality = Quality.FAST
        var persisted = 0
        val enabledEntry = booleanEntry("enabled", false, { enabled }, { enabled = it })
        val qualityEntry = ConfigEntry(
            id = "quality",
            label = "Quality",
            defaultValue = Quality.FAST,
            control = CycleConfigControl(Quality.entries),
            read = { quality },
            write = { quality = it },
            restartRequired = true,
        )
        val session = SettingsSession(SettingsSchema("Video", listOf(enabledEntry, qualityEntry)) { persisted++ })

        session.set(enabledEntry, true)
        session.set(qualityEntry, Quality.FANCY)
        val result = assertIs<SettingsApplyResult.Applied>(session.apply())

        assertTrue(enabled)
        assertEquals(Quality.FANCY, quality)
        assertEquals(1, persisted)
        assertEquals(setOf(enabledEntry, qualityEntry), result.changed)
        assertEquals(setOf(qualityEntry), result.restartRequired)
        assertFalse(session.dirty)
    }

    @Test
    fun `disabled dependency does not block apply with its validation`() {
        var enabled = true
        var distance = 8
        val enabledEntry = booleanEntry("enabled", true, { enabled }, { enabled = it })
        val distanceEntry = ConfigEntry(
            id = "distance",
            label = "Distance",
            defaultValue = 8,
            control = SteppedConfigControl((2..16).toList()),
            read = { distance },
            write = { distance = it },
            validate = { value, _ -> if (value >= 4) null else "Too small." },
            enabledWhen = { it[enabledEntry] },
        )
        val session = SettingsSession(SettingsSchema("Culling", listOf(enabledEntry, distanceEntry)))

        session.set(distanceEntry, 2)
        assertEquals("Too small.", session.validationErrors()[distanceEntry])
        session.set(enabledEntry, false)
        assertFalse(session.enabled(distanceEntry))
        assertTrue(session.validationErrors().isEmpty())
        assertTrue(session.canApply)
    }

    @Test
    fun `reset stages defaults and cancel restores the baseline`() {
        var value = 5
        val entry = ConfigEntry(
            id = "value",
            label = "Value",
            defaultValue = 2,
            control = SteppedConfigControl(listOf(2, 5, 8)),
            read = { value },
            write = { value = it },
        )
        val session = SettingsSession(SettingsSchema("Test", listOf(entry)))

        session.reset()
        assertEquals(2, session[entry])
        assertTrue(session.dirty)
        session.cancel()
        assertEquals(5, session[entry])
        assertFalse(session.dirty)
        assertEquals(5, value)
    }

    @Test
    fun `failed writer rolls back every attempted value`() {
        var first = 1
        var second = 2
        val firstEntry = numberEntry("first", 1, { first }, { first = it })
        val secondEntry = numberEntry("second", 2, { second }) {
            second = it
            if (it == 8) error("write failed")
        }
        val session = SettingsSession(SettingsSchema("Test", listOf(firstEntry, secondEntry)))
        session.set(firstEntry, 7)
        session.set(secondEntry, 8)

        val result = assertIs<SettingsApplyResult.Failed>(session.apply())

        assertEquals("write failed", result.error.message)
        assertEquals(1, first)
        assertEquals(2, second)
        assertTrue(session.dirty)
    }

    @Test
    fun `failed persistence rolls live values back`() {
        var value = 1
        val entry = numberEntry("value", 1, { value }, { value = it })
        val session = SettingsSession(SettingsSchema("Test", listOf(entry)) { error("disk full") })
        session.set(entry, 7)

        val result = assertIs<SettingsApplyResult.Failed>(session.apply())

        assertEquals("disk full", result.error.message)
        assertEquals(1, value)
        assertEquals(7, session[entry])
    }

    @Test
    fun `schema rejects duplicate ids and unbounded controls`() {
        val first = booleanEntry("same", false, { false }, {})
        val second = booleanEntry("same", true, { true }, {})

        kotlin.test.assertFailsWith<IllegalArgumentException> { SettingsSchema("Test", listOf(first, second)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            CycleConfigControl((0..CycleConfigControl.MAX_OPTIONS).toList())
        }
    }

    private fun booleanEntry(
        id: String,
        default: Boolean,
        read: () -> Boolean,
        write: (Boolean) -> Unit,
    ) = ConfigEntry(id, id, defaultValue = default, control = BooleanConfigControl, read = read, write = write)

    private fun numberEntry(
        id: String,
        default: Int,
        read: () -> Int,
        write: (Int) -> Unit,
    ) = ConfigEntry(id, id, defaultValue = default, control = SteppedConfigControl((0..10).toList()), read = read, write = write)

    private enum class Quality {
        FAST,
        FANCY,
    }
}
