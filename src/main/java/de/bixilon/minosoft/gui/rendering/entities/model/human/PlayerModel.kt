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

package de.bixilon.minosoft.gui.rendering.entities.model.human

import de.bixilon.kutil.cast.CastUtil.unsafeCast
import de.bixilon.kutil.observer.set.SetObserver.Companion.observeSet
import de.bixilon.minosoft.assets.model.texture.entity.EtfPlayerNoseType
import de.bixilon.minosoft.data.entities.entities.player.SkinParts
import de.bixilon.minosoft.data.entities.entities.player.SkinParts.Companion.pack
import de.bixilon.minosoft.data.entities.entities.player.properties.textures.metadata.SkinModel
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerModelMeshBuilder
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions

open class PlayerModel(
    renderer: PlayerRenderer<*>,
    model: BakedSkeletalModel,
    val type: SkinModel,
) : HumanModel<PlayerRenderer<*>>(renderer, model) {
    private val shader = renderer.renderer.features.player.shader
    var skinParts = 0xFF
        private set

    init {
        renderer.entity::skinParts.observeSet(this, instant = true) { skinParts = renderer.entity.skinParts.pack() }
    }

    override fun updateInstance() {
        super.updateInstance()
        instance.matrix.scaleAssign(0.9375f)
    }

    override fun draw() {
        val renderer = this.renderer.unsafeCast<PlayerRenderer<*>>()
        val system = manager.context.system
        val frame = renderer.skinFrame()
        try {
            if (frame?.allowBaseTransparency == true) {
                system.reset(
                    blending = true,
                    faceCulling = false,
                    sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                    destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                    sourceAlpha = BlendingFunctions.ONE,
                    destinationAlpha = BlendingFunctions.ZERO,
                )
            } else {
                system.reset(faceCulling = false) // TODO:  !renderSelf
            }

            shader.use()
            shader.texture = frame?.base?.shaderId ?: renderer.renderer.context.textures.debugTexture.shaderId
            shader.tint = renderer.light.value
            shader.entityColor = IrisEntityOverlay.resolve(renderer.entity)
            shader.skinParts = this.skinParts
            shader.inflate = 0.0f
            shader.hideBase = false
            shader.featurePart = 0x00
            shader.allowBaseTransparency = frame?.allowBaseTransparency == true
            shader.glint = false

            manager.upload(instance)
            instance.model.mesh.draw()

            val coat = frame?.coat?.takeIf { renderer.canRenderEtfCoat() }
            if (coat != null) {
                system.reset(
                    blending = true,
                    faceCulling = false,
                    sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                    destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                    sourceAlpha = BlendingFunctions.ONE,
                    destinationAlpha = BlendingFunctions.ZERO,
                )
                shader.use()
                shader.texture = coat.shaderId
                shader.tint = renderer.light.value
                shader.skinParts = SkinParts.JACKET.bitmask
                shader.inflate = frame.coatInflation
                shader.hideBase = true
                shader.featurePart = 0x00
                shader.allowBaseTransparency = false
                shader.glint = false
                instance.model.mesh.draw()
            }

            val nosePart = frame?.noseType?.featurePart()
            val nose = frame?.nose?.takeIf { nosePart != null }
            if (nose != null) drawFeature(nose.shaderId, nosePart!!)

            frame?.emissive?.let {
                drawEmissive(it.shaderId, this.skinParts, 0.0f, hideBase = false, featurePart = 0x00)
            }
            if (coat != null) {
                frame.coatEmissive?.let {
                    drawEmissive(it.shaderId, SkinParts.JACKET.bitmask, frame.coatInflation, hideBase = true, featurePart = 0x00)
                }
            }
            if (nose != null) {
                frame.noseEmissive?.let {
                    drawEmissive(it.shaderId, 0x00, 0.0f, hideBase = true, featurePart = nosePart!!)
                }
            }
            frame?.enchant?.let {
                drawGlint(it.shaderId, this.skinParts, 0.0f, hideBase = false, featurePart = 0x00)
            }
            if (coat != null) {
                frame.coatEnchant?.let {
                    drawGlint(it.shaderId, SkinParts.JACKET.bitmask, frame.coatInflation, hideBase = true, featurePart = 0x00)
                }
            }
            if (nose != null) {
                frame.noseEnchant?.let {
                    drawGlint(it.shaderId, 0x00, 0.0f, hideBase = true, featurePart = nosePart!!)
                }
            }
        } finally {
            shader.inflate = 0.0f
            shader.hideBase = false
            shader.featurePart = 0x00
            shader.allowBaseTransparency = false
            shader.glint = false
            shader.skinParts = this.skinParts
            system.reset()
        }
    }

    private fun drawFeature(texture: Int, featurePart: Int) {
        manager.context.system.reset(
            blending = true,
            faceCulling = false,
            sourceRGB = BlendingFunctions.SOURCE_ALPHA,
            destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
            sourceAlpha = BlendingFunctions.ONE,
            destinationAlpha = BlendingFunctions.ZERO,
        )
        shader.use()
        shader.texture = texture
        val renderer = this.renderer.unsafeCast<PlayerRenderer<*>>()
        shader.tint = renderer.light.value
        shader.skinParts = 0x00
        shader.inflate = 0.0f
        shader.hideBase = true
        shader.featurePart = featurePart
        shader.allowBaseTransparency = false
        shader.glint = false
        instance.model.mesh.draw()
    }

    private fun drawEmissive(
        texture: Int,
        skinParts: Int,
        inflation: Float,
        hideBase: Boolean,
        featurePart: Int,
    ) {
        val system = manager.context.system
        system.reset(
            blending = true,
            faceCulling = false,
            depthMask = false,
            sourceRGB = BlendingFunctions.SOURCE_ALPHA,
            destinationRGB = BlendingFunctions.ONE,
            sourceAlpha = BlendingFunctions.ONE,
            destinationAlpha = BlendingFunctions.ONE,
            depth = DepthFunctions.EQUAL,
        )
        shader.withProgramFamily(SceneProgramFamily.ENTITY_EYES) {
            shader.texture = texture
            shader.tint = ChatColors.WHITE.rgb()
            shader.skinParts = skinParts
            shader.inflate = inflation
            shader.hideBase = hideBase
            shader.featurePart = featurePart
            shader.allowBaseTransparency = !hideBase
            shader.glint = false
            instance.model.mesh.draw()
        }
    }

    private fun drawGlint(
        texture: Int,
        skinParts: Int,
        inflation: Float,
        hideBase: Boolean,
        featurePart: Int,
    ) {
        val system = manager.context.system
        system.reset(
            blending = true,
            faceCulling = false,
            depthMask = false,
            sourceRGB = BlendingFunctions.SOURCE_COLOR,
            destinationRGB = BlendingFunctions.ONE,
            sourceAlpha = BlendingFunctions.ONE,
            destinationAlpha = BlendingFunctions.ZERO,
            depth = DepthFunctions.EQUAL,
        )
        shader.withProgramFamily(SceneProgramFamily.ARMOR_GLINT) {
            shader.texture = texture
            shader.tint = ChatColors.WHITE.rgb()
            shader.skinParts = skinParts
            shader.inflate = inflation
            shader.hideBase = hideBase
            shader.featurePart = featurePart
            shader.allowBaseTransparency = false
            shader.glintTexture = PlayerRenderer.GLINT.shaderId
            shader.glintTime = this.renderer.unsafeCast<PlayerRenderer<*>>().entity.age.toFloat()
            shader.glint = true
            instance.model.mesh.draw()
        }
    }

    private fun EtfPlayerNoseType.featurePart(): Int? = when (this) {
        EtfPlayerNoseType.VILLAGER, EtfPlayerNoseType.VILLAGER_TEXTURED ->
            PlayerModelMeshBuilder.ETF_VILLAGER_NOSE_PART
        EtfPlayerNoseType.TEXTURED -> PlayerModelMeshBuilder.ETF_TEXTURED_NOSE_PART
        EtfPlayerNoseType.NONE -> null
    }
}
