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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen

import de.bixilon.minosoft.util.json.Jackson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreditsContentTest {

    @Test
    fun `credits use the project namespace rather than a copyrighted content key`() {
        assertEquals("minosoft", CreditsContent.RESOURCE.namespace)
        assertEquals("texts/credits.json", CreditsContent.RESOURCE.path)
    }

    @Test
    fun `credit sections preserve title and name order`() {
        val json = Jackson.MAPPER.readTree(
            """[{"section":"Studio","titles":[{"title":"Engineering","names":["Alex","Sam"]}]}]"""
        )

        val lines = CreditsContent.parse(listOf(json))

        assertEquals(
            listOf("Studio", "", "Engineering", "Alex", "Sam", "", ""),
            lines.map(CreditsLine::text),
        )
        assertEquals(CreditsLineStyle.SECTION, lines.first().style)
        assertEquals(CreditsLineStyle.TITLE, lines[2].style)
        assertEquals(CreditsLineStyle.NAME, lines[3].style)
    }

    @Test
    fun `local and mod supplied documents append in provider order`() {
        val local = Jackson.MAPPER.readTree("""[{"section":"Minosoft","titles":[]}]""")
        val mod = Jackson.MAPPER.readTree("""[{"section":"Local Mod","titles":[]}]""")

        val lines = CreditsContent.parse(listOf(local, mod))

        assertTrue(lines.indexOfFirst { it.text == "Minosoft" } < lines.indexOfFirst { it.text == "Local Mod" })
    }

    @Test
    fun `missing local credits fail visibly with original project copy`() {
        assertEquals(
            listOf(
                CreditsLine("Minosoft", CreditsLineStyle.SECTION),
                CreditsLine("No local or mod-supplied credits were found.", CreditsLineStyle.TEXT),
            ),
            CreditsContent.parse(emptyList()),
        )
    }
}

class CreditsScrollStateTest {

    @Test
    fun `normal scroll advances at thirty pixels per second`() {
        val state = CreditsScrollState()

        repeat(20) { state.advance(spaceDown = false, controlDown = false) }

        assertEquals(30.0f, state.pixels)
    }

    @Test
    fun `space and control apply the fast-forward tiers`() {
        val state = CreditsScrollState()

        state.advance(spaceDown = true, controlDown = false)
        assertEquals(7.5f, state.pixels)
        state.advance(spaceDown = true, controlDown = true)
        assertEquals(37.5f, state.pixels)
    }

    @Test
    fun `completion includes the trailing half-screen pause`() {
        val state = CreditsScrollState()
        repeat(106) { state.advance(spaceDown = false, controlDown = false) }
        assertTrue(!state.complete(viewportHeight = 100.0f, contentHeight = 10.0f))
        state.advance(spaceDown = false, controlDown = false)
        assertTrue(state.complete(viewportHeight = 100.0f, contentHeight = 10.0f))
    }
}
