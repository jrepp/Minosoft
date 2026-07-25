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

package de.bixilon.minosoft.modding.loader.fabric

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class FabricMetadataUiTest {
    @Test
    fun `decodes descriptions icons badges and parent hierarchy`() {
        val metadata = FabricMetadataReader.read(
            ByteArrayInputStream(
                """
                    {
                      "schemaVersion": 1,
                      "id": "child_mod",
                      "version": "1.0.0",
                      "name": "Child",
                      "description": "A configurable child mod.",
                      "icon": {"16": "assets/icon16.png", "128": "assets/icon128.png"},
                      "custom": {"modmenu": {"badges": ["library", "client"], "parent": "parent_mod"}}
                    }
                """.trimIndent().toByteArray(),
            ),
            "memory",
        )

        assertEquals("A configurable child mod.", metadata.description)
        assertEquals("assets/icon128.png", metadata.icon)
        assertEquals(setOf("library", "client"), metadata.badges)
        assertEquals("parent_mod", metadata.parent)
    }
}
