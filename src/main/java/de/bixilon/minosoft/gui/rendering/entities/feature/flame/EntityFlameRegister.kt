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

package de.bixilon.minosoft.gui.rendering.entities.feature.flame

import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.feature.register.FeatureRegister
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture

class EntityFlameRegister(renderer: EntitiesRenderer) : FeatureRegister {
    val shader = renderer.context.system.shader.create(
        minosoft("entities/features/flame"),
        ::EntityFlameShader,
    )
    val textures = if (renderer.session.version.flattened) {
        arrayOf(
            renderer.context.textures.static.create(minecraft("block/fire_0").texture()),
            renderer.context.textures.static.create(minecraft("block/fire_1").texture()),
        )
    } else {
        arrayOf(
            renderer.context.textures.static.create(minecraft("blocks/fire_layer_0").texture()),
            renderer.context.textures.static.create(minecraft("blocks/fire_layer_1").texture()),
        )
    }

    override fun postInit() {
        shader.load()
    }
}
