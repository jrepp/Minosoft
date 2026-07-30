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

package de.bixilon.minosoft.gui.rendering.camera.arm

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.cast.CastUtil.nullCast
import de.bixilon.kutil.exception.Broken
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.entities.entities.player.properties.textures.metadata.SkinModel
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.camera.CameraDefinition.FALLBACK_FAR_PLANE
import de.bixilon.minosoft.gui.rendering.camera.CameraDefinition.NEAR_PLANE
import de.bixilon.minosoft.gui.rendering.camera.CameraUtil
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer.Companion.SKIN
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer.Companion.SLIM
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer.Companion.WIDE
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerSkinUvTexture
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.LayerSettings
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.system.base.layer.RenderLayer
import de.bixilon.minosoft.gui.rendering.system.base.settings.RenderSettings
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTexture
import de.bixilon.minosoft.protocol.network.session.play.PlaySession

class ArmRenderer(override val context: RenderContext) : WorldRenderer {
    override val layers = LayerSettings()
    private var perspective = Mat4f()
    val shader = context.system.shader.create(minosoft("entities/player/arm")) { ArmShader(it) }
    private val mainHandItem = HeldItemRenderer(context)
    private val offHandItem = HeldItemRenderer(context)
    private var referenceSkinTexture: DynamicTexture? = null
    private var refreshedOrdinarySkin: DynamicTexture? = null
    private var refreshedOrdinarySkinGeneration = Long.MIN_VALUE
    var armDraws: Long = 0L
        private set
    var lastArmDrawFrame: Long = -1L
        private set
    var lastArmTextureShaderId: Int? = null
        private set

    val referenceSkinEnabled: Boolean
        get() = referenceSkinTexture != null
    val referenceSkinShaderId: Int?
        get() = referenceSkinTexture?.shaderId

    fun ordinarySkinDiagnostics(): ArmSkinDiagnostics? {
        val entity = context.session.camera.entity.nullCast<PlayerEntity>() ?: return null
        val renderer = entity.renderer?.nullCast<PlayerRenderer<*>>() ?: return null
        val frame = renderer.skinFrame()
        val fallback = context.textures.skins.default[entity]?.texture
        return ArmSkinDiagnostics(
            selected = (frame?.base ?: fallback)?.diagnostics(entity.mainArm),
            frame = frame?.base?.diagnostics(entity.mainArm),
            fallback = fallback?.diagnostics(entity.mainArm),
        )
    }

    private fun DynamicTexture.diagnostics(arm: Arms): ArmSkinTextureDiagnostics {
        val buffer = data?.buffer
        return ArmSkinTextureDiagnostics(
            shaderId = shaderId,
            state = state.name.lowercase(),
            width = buffer?.size?.x,
            height = buffer?.size?.y,
            visiblePixels = buffer?.countPixels(),
            nonBlackVisiblePixels = buffer?.countPixels(nonBlack = true),
            armNonBlackVisiblePixels = buffer?.countArmPixels(arm),
        )
    }

    private fun TextureBuffer.countPixels(nonBlack: Boolean = false): Int {
        var count = 0
        for (y in 0 until size.y) {
            for (x in 0 until size.x) {
                val color = getRGBA(x, y)
                if (color.alpha == 0) continue
                if (nonBlack && color.red == 0 && color.green == 0 && color.blue == 0) continue
                count++
            }
        }
        return count
    }

    private fun TextureBuffer.countArmPixels(arm: Arms): Int {
        if (size.x < 64 || size.y < 64) return 0
        val xRange = when (arm) {
            Arms.RIGHT -> 40 until 56
            Arms.LEFT -> 32 until 48
        }
        val yRange = when (arm) {
            Arms.RIGHT -> 16 until 32
            Arms.LEFT -> 48 until 64
        }
        var count = 0
        for (y in yRange) {
            for (x in xRange) {
                val color = getRGBA(x, y)
                if (color.alpha == 0) continue
                if (color.red == 0 && color.green == 0 && color.blue == 0) continue
                count++
            }
        }
        return count
    }

    /**
     * Selects a generated high-contrast skin-sized texture for a reversible
     * visual acceptance check. This does not replace the player skin or world
     * model.
     */
    fun setReferenceSkinEnabled(enabled: Boolean) {
        referenceSkinTexture = if (enabled) {
            context.textures.dynamic.push(REFERENCE_SKIN, async = false) {
                RGBA8Buffer(Vec2i(64)).apply {
                    for (y in 0 until 64) {
                        for (x in 0 until 64) {
                            val even = ((x / 4) + (y / 4)) % 2 == 0
                            if (even) {
                                setRGBA(x, y, 0xFF, 0x20, 0xD0, 0xFF)
                            } else {
                                setRGBA(x, y, 0x20, 0xFF, 0xE0, 0xFF)
                            }
                        }
                    }
                }
            }
        } else {
            null
        }
    }

    override fun registerLayers() {
        layers.registerSemantic(
            layer = HandLayer,
            shader = null,
            renderer = this::drawHand,
            semantic = PipelineSemantic.HAND,
            passId = RenderPassId("minosoft:scene/hand"),
        )
    }

    override fun init(latch: AbstractLatch) {
        registerModels()
    }

    override fun postInit(latch: AbstractLatch) {
        shader.load()
        mainHandItem.postInit()
        offHandItem.postInit()
        context.window::size.observe(this, true) {
            perspective = handProjection(
                CameraUtil.perspective(60.0f.rad, it.x.toFloat() / it.y, NEAR_PLANE, FALLBACK_FAR_PLANE),
            )
        }
    }

    private fun registerModels() {
        val skeletal = context.models.skeletal
        val override = mapOf(SKIN to PlayerSkinUvTexture)

        skeletal.register(LEFT_ARM_WIDE, WIDE, override) { ArmMeshBuilder(context, Arms.LEFT) }
        skeletal.register(RIGHT_ARM_WIDE, WIDE, override) { ArmMeshBuilder(context, Arms.RIGHT) }

        skeletal.register(LEFT_ARM_SLIM, SLIM, override) { ArmMeshBuilder(context, Arms.LEFT) }
        skeletal.register(RIGHT_ARM_SLIM, SLIM, override) { ArmMeshBuilder(context, Arms.RIGHT) }
    }


    private fun getModel(arm: Arms, model: SkinModel): BakedSkeletalModel? {
        val name = when {
            arm == Arms.LEFT && model == SkinModel.WIDE -> LEFT_ARM_WIDE
            arm == Arms.RIGHT && model == SkinModel.WIDE -> RIGHT_ARM_WIDE
            arm == Arms.LEFT && model == SkinModel.SLIM -> LEFT_ARM_SLIM
            arm == Arms.RIGHT && model == SkinModel.SLIM -> RIGHT_ARM_SLIM
            else -> Broken()
        }

        return context.models.skeletal[name]
    }

    private fun drawHand() {
        if (!context.camera.view.view.renderArm) return
        val entity = context.session.camera.entity.nullCast<PlayerEntity>() ?: return
        val renderer = entity.renderer?.nullCast<PlayerRenderer<*>>()
        val arm = entity.mainArm

        context.system.reset(faceCulling = true, depthTest = true, blending = true, depthMask = true)

        val mainHand = entity.equipment[EquipmentSlots.MAIN_HAND]
        val skin = renderer?.model?.type
        val model = skin?.let { getModel(arm, it) }
        if (mainHand == null && renderer != null && model != null) {
            val frame = renderer.skinFrame()
            val referenceSkin = referenceSkinTexture
            val fallbackSkin = context.textures.skins.default[entity]?.texture
            val ordinarySkin = frame?.base ?: fallbackSkin
            val dynamicTextures = context.textures.dynamic
            if (
                referenceSkin == null &&
                ordinarySkin != null &&
                (
                    refreshedOrdinarySkin !== ordinarySkin ||
                        refreshedOrdinarySkinGeneration != dynamicTextures.storageGeneration
                    )
            ) {
                dynamicTextures.refresh(ordinarySkin)
                refreshedOrdinarySkin = ordinarySkin
                refreshedOrdinarySkinGeneration = dynamicTextures.storageGeneration
            }

            shader.use()
            shader.skinParts = renderer.model?.skinParts ?: 0xFF
            val textureShaderId = referenceSkin?.shaderId
                ?: frame?.base?.shaderId
                ?: fallbackSkin?.shaderId
                ?: context.textures.debugTexture.shaderId
            shader.texture = textureShaderId
            shader.tint = ChatColors.WHITE.rgb()

            val pivot = Vec3f((if (arm == Arms.RIGHT) 6f else -6f) / 16f, 24 / 16f, 0f)

            val matrix = MMat4f().apply {
                translateAssign(Vec3f((if (arm == Arms.RIGHT) 23f / 16f else -23f / 16f), -17 / 16f, -0.7f))
                rotateXAssign(120.0f.rad)
                rotateYAssign((if (arm == Arms.RIGHT) -20.0f else 20.0f).rad)

                entity.armSwing.progress(arm)?.let { progress ->
                    translateAssign(FirstPersonItemTransform.swingOffset(arm, progress))
                    val rotation = FirstPersonItemTransform.swingRotation(arm, progress)
                    rotateYAssign(rotation.y.rad)
                    rotateZAssign(rotation.z.rad)
                    rotateXAssign(rotation.x.rad)
                }

                translateAssign(-pivot)
            }

            shader.transform = perspective * matrix

            model.mesh.draw()
            armDraws++
            lastArmDrawFrame = context.frameNumber
            lastArmTextureShaderId = textureShaderId
            frame?.emissive?.takeIf { referenceSkin == null }?.let { emissive ->
                try {
                    context.system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE,
                        depth = DepthFunctions.EQUAL,
                    )
                    shader.use()
                    shader.texture = emissive.shaderId
                    shader.tint = ChatColors.WHITE.rgb()
                    model.mesh.draw()
                } finally {
                    context.system.reset()
                }
            }
        }
        mainHandItem.draw(entity, EquipmentSlots.MAIN_HAND, arm, perspective)
        offHandItem.draw(entity, EquipmentSlots.OFF_HAND, FirstPersonItemTransform.opposite(arm), perspective)
    }

    override fun unload() {
        mainHandItem.unload()
        offHandItem.unload()
    }


    companion object : RendererBuilder<ArmRenderer> {
        private val LEFT_ARM_WIDE = minosoft("left_arm_wide")
        private val RIGHT_ARM_WIDE = minosoft("right_arm_wide")
        private val LEFT_ARM_SLIM = minosoft("left_arm_slim")
        private val RIGHT_ARM_SLIM = minosoft("right_arm_slim")
        private val REFERENCE_SKIN = minosoft("debug/reference_hand_skin")
        private const val HAND_DEPTH_SCALE = 0.125f

        override fun build(session: PlaySession, context: RenderContext) = ArmRenderer(context)

        internal fun handProjection(projection: Mat4f): Mat4f =
            Mat4f(1.0f, 1.0f, HAND_DEPTH_SCALE, 1.0f) * projection
    }

    private object HandLayer : RenderLayer {
        override val settings = RenderSettings.DEFAULT
        override val priority = 0
    }
}

data class ArmSkinDiagnostics(
    val selected: ArmSkinTextureDiagnostics?,
    val frame: ArmSkinTextureDiagnostics?,
    val fallback: ArmSkinTextureDiagnostics?,
)

data class ArmSkinTextureDiagnostics(
    val shaderId: Int,
    val state: String,
    val width: Int?,
    val height: Int?,
    val visiblePixels: Int?,
    val nonBlackVisiblePixels: Int?,
    val armNonBlackVisiblePixels: Int?,
)
