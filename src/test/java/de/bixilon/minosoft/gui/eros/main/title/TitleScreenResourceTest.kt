/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.eros.main.title

import de.bixilon.minosoft.gui.eros.main.ErosMainActivities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import javax.xml.parsers.DocumentBuilderFactory

class TitleScreenResourceTest {

    @Test
    fun `title activity owns the Minecraft style title resource`() {
        assertEquals(TitleController.LAYOUT, ErosMainActivities.TITLE.layout)
        assertNotNull(resource())
    }

    @Test
    fun `title buttons are wired to launcher actions`() {
        val document = resource().use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        assertEquals("minecraft-title-screen", document.documentElement.getAttribute("styleClass"))

        val actions = buildMap {
            val buttons = document.getElementsByTagName("Button")
            for (index in 0 until buttons.length) {
                val attributes = buttons.item(index).attributes
                put(attributes.getNamedItem("fx:id").nodeValue, attributes.getNamedItem("onAction").nodeValue)
            }
        }

        assertEquals("#openMultiplayer", actions["multiplayerFX"])
        assertEquals("#openProfiles", actions["profilesFX"])
        assertEquals("#openMods", actions["modsFX"])
        assertEquals("#quit", actions["quitFX"])
    }

    private fun resource() = requireNotNull(
        TitleScreenResourceTest::class.java.getResourceAsStream("/assets/minosoft/eros/main/title/title.fxml"),
    )
}
