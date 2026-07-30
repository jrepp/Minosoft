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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.animal

import de.bixilon.minosoft.data.entities.entities.animal.Sheep
import de.bixilon.minosoft.data.registries.identified.Identified
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.factory.RegisteredEntityModelFactory
import de.bixilon.minosoft.gui.rendering.entities.model.animal.AnimalModelFeature
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoader.Companion.sModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

class SheepRenderer(renderer: EntitiesRenderer, entity: Sheep) : AnimalRenderer<Sheep>(renderer, entity) {
    override fun getModel() = SHEEP
    private var wool: SheepWoolFeature? = null

    override fun update(time: ValueTimeMark, delta: Duration, auxiliaryVisible: Boolean) {
        super.update(time, delta, auxiliaryVisible)
        val wool = this.wool ?: renderer.context.models.skeletal[SHEEP_WOOL]
            ?.let { SheepWoolFeature(this, it).register() }
            ?.also { this.wool = it }
            ?: return
        wool.enabled = !entity.isSheared
    }

    override fun unload() {
        super.unload()
        wool = null
    }

    private class SheepWoolFeature(
        private val sheepRenderer: SheepRenderer,
        model: BakedSkeletalModel,
    ) : AnimalModelFeature<SheepRenderer>(sheepRenderer, model) {
        override fun modelTint(tint: RGBColor): RGBColor = tint * sheepRenderer.entity.color
    }

    companion object : RegisteredEntityModelFactory<Sheep>, Identified {
        override val identifier get() = Sheep.identifier
        internal val SHEEP = minecraft("entities/sheep/sheep").sModel()
        internal val SHEEP_WOOL = minecraft("entities/sheep/sheep_wool").sModel()
        internal val SHEEP_WOOLLY_PREVIEW = minecraft("entities/sheep/sheep_woolly_preview").sModel()

        override fun create(renderer: EntitiesRenderer, entity: Sheep) = SheepRenderer(renderer, entity)

        override fun register(loader: ModelLoader) {
            loader.skeletal.register(SHEEP)
            loader.skeletal.register(SHEEP_WOOL)
            loader.skeletal.register(SHEEP_WOOLLY_PREVIEW)
        }
    }
}
