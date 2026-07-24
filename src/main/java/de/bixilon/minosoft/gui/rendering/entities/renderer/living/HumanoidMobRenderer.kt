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
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.model.human.HumanoidMobModel
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

open class HumanoidMobRenderer<E : LivingEntity>(
    renderer: EntitiesRenderer,
    entity: E,
    private val modelResource: ResourceLocation,
) : LivingEntityRenderer<E>(renderer, entity), ContentModelReloadable {
    var model: HumanoidMobModel? = null
        private set
    private var unloadModel = false

    override fun enqueueUnload() {
        super.enqueueUnload()
        if (!unloadModel) return
        val model = model
        this.model = null
        if (model != null) {
            features -= model
            renderer.queue += { model.unload() }
        }
        unloadModel = false
    }

    override fun reloadContentModel() {
        unloadModel = true
    }

    override fun update(time: ValueTimeMark, delta: Duration) {
        super.update(time, delta)
        if (model != null) return

        val resource = renderer.context.models.skeletal.contentModel(entity.type.identifier) ?: modelResource
        val baked = renderer.context.models.skeletal[resource] ?: return
        model = HumanoidMobModel(this, baked).also { it.register() }
    }

    override fun unload() {
        super.unload()
        model = null
    }
}
