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

package de.bixilon.minosoft.commands

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CommandAliasesTest {

    @Test
    fun `creative shorthand expands to the vanilla argument`() {
        assertEquals("/gamemode creative", CommandAliases.expand("/gamemode c"))
    }

    @Test
    fun `survival shorthand expands to the vanilla argument`() {
        assertEquals("/gamemode survival", CommandAliases.expand("/gamemode s"))
    }

    @Test
    fun `unrelated commands and chat remain unchanged`() {
        assertEquals("/gamemode spectator", CommandAliases.expand("/gamemode spectator"))
        assertEquals("gamemode c", CommandAliases.expand("gamemode c"))
        assertEquals("/gamemode c Steve", CommandAliases.expand("/gamemode c Steve"))
        assertEquals("hello", CommandAliases.expand("hello"))
    }
}
