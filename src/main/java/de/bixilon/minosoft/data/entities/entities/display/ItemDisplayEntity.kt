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
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.data.EntityDataField
import de.bixilon.minosoft.data.registries.entities.EntityFactory
import de.bixilon.minosoft.data.registries.entities.EntityType
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.protocol.network.session.play.PlaySession

class ItemDisplayEntity(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation) : DisplayEntity(session, entityType, data, position, rotation) {
    val stack: ItemStack? by data(ITEM, null)
    val displayContext: ItemDisplayContext by data(ITEM_DISPLAY, ItemDisplayContext.NONE) {
        if (it is ItemDisplayContext) it else ItemDisplayContext.of((it as Number).toInt())
    }

    companion object : EntityFactory<ItemDisplayEntity> {
        override val identifier = minecraft("item_display")
        val ITEM = EntityDataField("ITEM")
        val ITEM_DISPLAY = EntityDataField("ITEM_DISPLAY")

        override fun build(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation): ItemDisplayEntity {
            return ItemDisplayEntity(session, entityType, data, position, rotation)
        }
    }
}

enum class ItemDisplayContext {
    NONE,
    THIRD_PERSON_LEFT_HAND,
    THIRD_PERSON_RIGHT_HAND,
    FIRST_PERSON_LEFT_HAND,
    FIRST_PERSON_RIGHT_HAND,
    HEAD,
    GUI,
    GROUND,
    FIXED,
    ;

    companion object {
        fun of(value: Int) = entries.getOrElse(value) { NONE }
    }
}
