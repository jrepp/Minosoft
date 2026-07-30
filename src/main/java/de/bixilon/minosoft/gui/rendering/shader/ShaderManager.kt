/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.shader

import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.beacon.BeaconBeamShader
import de.bixilon.minosoft.gui.rendering.entities.renderer.lightning.LightningShader
import de.bixilon.minosoft.gui.rendering.shader.generic.ColorShader
import de.bixilon.minosoft.gui.rendering.shader.generic.Generic2dTextureShader
import de.bixilon.minosoft.gui.rendering.shader.generic.GenericTextureShader
import de.bixilon.minosoft.gui.rendering.shader.generic.LightColorShader
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture

class ShaderManager(
    val context: RenderContext,
) {
    val genericColorShader = context.system.shader.create(minosoft("generic/color")) { ColorShader(it) }
    val genericLineShader = context.system.shader.create(minosoft("generic/color")) {
        ColorShader(it, SceneProgramFamily.LINE)
    }
    val entityLeashShader = context.system.shader.create(minosoft("generic/color_light")) { LightColorShader(it) }
    val beaconBeamTexture = context.textures.static.create(minecraft("entity/beacon_beam").texture(), mipmaps = false)
    val beaconBeamShader = context.system.shader.create(minosoft("chunk/entities/beacon/beam")) { BeaconBeamShader(it) }
    val lightningShader = context.system.shader.create(minosoft("entities/lightning/lightning")) { LightningShader(it) }
    val genericTextureShader = context.system.shader.create(minosoft("generic/texture")) { GenericTextureShader(it) }
    val entityShadowTextureShader = context.system.shader.create(minosoft("generic/texture")) {
        it.defines["DISABLE_MIPMAPS"] = ""
        it.defines["CLAMP_TEXTURE_UV"] = ""
        GenericTextureShader(it)
    }
    val genericTexture2dShader = context.system.shader.create(minosoft("generic/texture_2d")) { Generic2dTextureShader(it) }


    fun postInit() {
        genericColorShader.load()
        genericLineShader.load()
        entityLeashShader.load()
        beaconBeamShader.load()
        lightningShader.load()
        genericTextureShader.load()
        entityShadowTextureShader.load()
        genericTexture2dShader.load()
    }

    fun unload() {
        var failure: Throwable? = null
        for (shader in context.system.shader.toList().asReversed()) {
            if (!shader.native.loaded) continue
            try {
                shader.unload()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }
}
