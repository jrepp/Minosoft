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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.monster

import de.bixilon.minosoft.data.entities.entities.monster.Zombie
import de.bixilon.minosoft.data.registries.identified.Identified
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.factory.RegisteredEntityModelFactory
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.HumanoidMobRenderer
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoader.Companion.sModel

class ZombieRenderer(
    renderer: EntitiesRenderer,
    entity: Zombie,
) : HumanoidMobRenderer<Zombie>(renderer, entity, MODEL) {

    companion object : RegisteredEntityModelFactory<Zombie>, Identified {
        override val identifier get() = Zombie.identifier
        val MODEL = minecraft("entities/zombie/zombie").sModel()

        override fun create(renderer: EntitiesRenderer, entity: Zombie) = ZombieRenderer(renderer, entity)

        override fun register(loader: ModelLoader) {
            loader.skeletal.register(MODEL)
        }
    }
}
