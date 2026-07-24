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

package de.bixilon.minosoft.gui.rendering.entities.renderer.display

import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayContext
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.data.registries.identified.Identified
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.factory.RegisteredEntityModelFactory
import de.bixilon.minosoft.gui.rendering.entities.feature.item.ItemFeature
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions

class ItemDisplayEntityRenderer(
    renderer: EntitiesRenderer,
    entity: ItemDisplayEntity,
) : DisplayEntityRenderer<ItemDisplayEntity>(renderer, entity) {
    val item = ItemFeature(this, entity.stack, entity.displayContext.position, many = false).register()

    init {
        entity::stack.observe(this, true) { item.stack = it }
        entity::displayContext.observe(this, true) { item.display = it.position }
    }

    companion object : RegisteredEntityModelFactory<ItemDisplayEntity>, Identified {
        override val identifier get() = ItemDisplayEntity.identifier
        override fun create(renderer: EntitiesRenderer, entity: ItemDisplayEntity) = ItemDisplayEntityRenderer(renderer, entity)
        override fun register(loader: ModelLoader) = Unit
    }
}

private val ItemDisplayContext.position: DisplayPositions
    get() = when (this) {
        ItemDisplayContext.NONE, ItemDisplayContext.FIXED -> DisplayPositions.FIXED
        ItemDisplayContext.THIRD_PERSON_LEFT_HAND -> DisplayPositions.THIRD_PERSON_LEFT_HAND
        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND -> DisplayPositions.THIRD_PERSON_RIGHT_HAND
        ItemDisplayContext.FIRST_PERSON_LEFT_HAND -> DisplayPositions.FIRST_PERSON_LEFT_HAND
        ItemDisplayContext.FIRST_PERSON_RIGHT_HAND -> DisplayPositions.FIRST_PERSON_RIGHT_HAND
        ItemDisplayContext.HEAD -> DisplayPositions.HEAD
        ItemDisplayContext.GUI -> DisplayPositions.GUI
        ItemDisplayContext.GROUND -> DisplayPositions.GROUND
    }
