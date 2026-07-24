/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */
package de.bixilon.minosoft.data.entities.entities.display

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.data.EntityDataField
import de.bixilon.minosoft.data.registries.entities.EntityFactory
import de.bixilon.minosoft.data.registries.entities.EntityType
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.protocol.network.session.play.PlaySession

class TextDisplayEntity(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation) : DisplayEntity(session, entityType, data, position, rotation) {
    val text: ChatComponent by data(TEXT, ChatComponent.EMPTY) { ChatComponent.of(it) }
    val lineWidth: Int by data(LINE_WIDTH, 200)
    val background: Int by data(BACKGROUND, 0x40000000)
    val textOpacity: Byte by data(TEXT_OPACITY, -1)
    val flags: Byte by data(TEXT_DISPLAY_FLAGS, 0)

    val shadow get() = flags.toInt() and 0x01 != 0
    val seeThrough get() = flags.toInt() and 0x02 != 0
    val defaultBackground get() = flags.toInt() and 0x04 != 0
    val alignment get() = when {
        flags.toInt() and 0x08 != 0 -> TextDisplayAlignment.LEFT
        flags.toInt() and 0x10 != 0 -> TextDisplayAlignment.RIGHT
        else -> TextDisplayAlignment.CENTER
    }

    companion object : EntityFactory<TextDisplayEntity> {
        override val identifier = minecraft("text_display")
        val TEXT = EntityDataField("TEXT")
        val LINE_WIDTH = EntityDataField("LINE_WIDTH")
        val BACKGROUND = EntityDataField("BACKGROUND")
        val TEXT_OPACITY = EntityDataField("TEXT_OPACITY")
        val TEXT_DISPLAY_FLAGS = EntityDataField("TEXT_DISPLAY_FLAGS")

        override fun build(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation): TextDisplayEntity {
            return TextDisplayEntity(session, entityType, data, position, rotation)
        }
    }
}

enum class TextDisplayAlignment {
    CENTER,
    LEFT,
    RIGHT,
}
