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

import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerModelMeshBuilder
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoader.Companion.sModel
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMeshBuilder

object VanillaArmorModels : SkeletalMeshBuilder {
    val OUTER = minosoft("armor/humanoid_outer").sModel()
    val INNER = minosoft("armor/humanoid_inner").sModel()
    val MATERIAL = minosoft("armor")

    fun register(loader: ModelLoader) {
        val override = mapOf(MATERIAL to loader.context.textures.debugTexture)
        loader.skeletal.register(OUTER, override = override, mesh = this)
        loader.skeletal.register(INNER, override = override, mesh = this)
    }

    override fun buildMesh(context: RenderContext) = PlayerModelMeshBuilder(context)
}
