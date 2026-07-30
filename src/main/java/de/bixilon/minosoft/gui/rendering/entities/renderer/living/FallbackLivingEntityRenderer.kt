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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living

import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerSkinUvTexture
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoader.Companion.sModel

class FallbackLivingEntityRenderer(
    renderer: EntitiesRenderer,
    entity: LivingEntity,
) : HumanoidMobRenderer<LivingEntity>(renderer, entity, MODEL) {

    companion object {
        val MODEL = minosoft("entities/living_fallback").sModel()

        fun register(loader: ModelLoader) {
            loader.skeletal.register(
                MODEL,
                template = PlayerRenderer.WIDE,
                override = mapOf(PlayerRenderer.SKIN to PlayerSkinUvTexture),
            )
        }
    }
}
