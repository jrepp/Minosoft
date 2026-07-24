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
import de.bixilon.minosoft.data.entities.entities.display.BlockDisplayEntity
import de.bixilon.minosoft.data.registries.identified.Identified
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.factory.RegisteredEntityModelFactory
import de.bixilon.minosoft.gui.rendering.entities.feature.block.BlockFeature
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader

class BlockDisplayEntityRenderer(
    renderer: EntitiesRenderer,
    entity: BlockDisplayEntity,
) : DisplayEntityRenderer<BlockDisplayEntity>(renderer, entity) {
    val block = BlockFeature(this, entity.blockState).register()

    init {
        entity::blockState.observe(this, true) { block.state = it }
    }

    companion object : RegisteredEntityModelFactory<BlockDisplayEntity>, Identified {
        override val identifier get() = BlockDisplayEntity.identifier
        override fun create(renderer: EntitiesRenderer, entity: BlockDisplayEntity) = BlockDisplayEntityRenderer(renderer, entity)
        override fun register(loader: ModelLoader) = Unit
    }
}
