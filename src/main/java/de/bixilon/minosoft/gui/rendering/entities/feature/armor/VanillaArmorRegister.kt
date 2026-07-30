/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.armor

import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.feature.register.FeatureRegister
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture

class VanillaArmorRegister(renderer: EntitiesRenderer) : FeatureRegister {
    val textures = VanillaArmorTextures(renderer)
    val glint = renderer.context.textures.static.create(
        minecraft("misc/enchanted_glint_entity").texture(),
    )
}
