/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.lightning

import de.bixilon.minosoft.data.entities.entities.LightningBolt
import de.bixilon.minosoft.data.registries.identified.Identified
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.factory.RegisteredEntityModelFactory
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer

class LightningBoltRenderer(
    renderer: EntitiesRenderer,
    entity: LightningBolt,
) : EntityRenderer<LightningBolt>(renderer, entity) {
    val bolt = LightningBoltFeature(this).register()

    companion object : RegisteredEntityModelFactory<LightningBolt>, Identified {
        override val identifier get() = LightningBolt.identifier

        override fun create(renderer: EntitiesRenderer, entity: LightningBolt) =
            LightningBoltRenderer(renderer, entity)
    }
}
