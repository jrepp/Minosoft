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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living

import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.data.entities.Poses
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.effect.EntityLeashFeature
import de.bixilon.minosoft.gui.rendering.entities.effect.EntityShadowFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.armor.VanillaArmorFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.skeletal.GeckoLibArmorFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import kotlin.time.Duration

abstract class LivingEntityRenderer<E : LivingEntity>(renderer: EntitiesRenderer, entity: E) : EntityRenderer<E>(renderer, entity) {
    val shadow = EntityShadowFeature(this).register()
    val leash = EntityLeashFeature(this).register()
    val geckoArmor = GeckoLibArmorFeature(this).register()
    val vanillaArmor = VanillaArmorFeature(this).register()
    val vanillaArmorDecorations = vanillaArmor.decorations.register()


    override fun updateMatrix(delta: Duration) {
        super.updateMatrix(delta)
        when (entity.pose) {
            Poses.SLEEPING -> matrix.apply { rotateXAssign(90.0f.rad) } // TODO
            else -> Unit
        }
    }

}
