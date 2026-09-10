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

package de.bixilon.minosoft.data.registries.blocks.state

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.annotations.Test

@Test(groups = ["block", "registry"])
class ConnectedBlockStateIT {

    fun `modern connected and corner states remain selectable`() {
        assertNotNull(IT.VERSION)
        val session = createSession()

        assertProperties(
            state(session, "minecraft:oak_fence", mapOf(
                "north" to "true", "east" to "true", "south" to "true", "west" to "true",
            )),
            mapOf("north" to "side", "east" to "side", "south" to "side", "west" to "side"),
        )
        assertProperties(
            state(session, "minecraft:cobblestone_wall", mapOf(
                "north" to "low", "east" to "low", "south" to "low", "west" to "low", "up" to "true",
            )),
            mapOf("north" to "low", "east" to "low", "south" to "low", "west" to "low", "up" to "side"),
        )
        assertProperties(
            state(session, "minecraft:oak_stairs", mapOf(
                "facing" to "north", "half" to "bottom", "shape" to "inner_left",
            )),
            mapOf("facing" to "north", "half" to "lower", "shape" to "inner_left"),
        )
    }

    private fun state(session: PlaySession, name: String, properties: Map<String, String>): BlockState {
        val block = requireNotNull(session.registries.block[ResourceLocation.of(name)])
        val parsed = properties.map { (propertyName, value) ->
            val property = requireNotNull(block.properties[propertyName])
            property to requireNotNull(property.parse(value))
        }.toMap()
        return block.states.withProperties(parsed)
    }

    private fun assertProperties(state: BlockState?, expected: Map<String, String>) {
        val actual = requireNotNull(state).properties.entries.associate { (property, value) ->
            property.name to value.toString().lowercase()
        }
        for ((name, value) in expected) {
            assertEquals(actual[name], value, "Unexpected value for block property $name")
        }
    }
}
