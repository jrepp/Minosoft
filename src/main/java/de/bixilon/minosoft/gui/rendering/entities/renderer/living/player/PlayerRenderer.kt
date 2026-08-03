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

package de.bixilon.minosoft.gui.rendering.entities.renderer.living.player

import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureBlinkState
import de.bixilon.minosoft.assets.model.texture.entity.EtfPlayerSkinBuffers
import de.bixilon.minosoft.assets.model.texture.entity.EtfPlayerNoseType
import de.bixilon.minosoft.assets.model.texture.entity.EtfPlayerSkinProcessor
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.entities.Poses
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.entities.entities.player.SkinParts
import de.bixilon.minosoft.data.entities.entities.player.properties.textures.metadata.SkinModel
import de.bixilon.minosoft.data.registries.identified.Identified
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.registries.item.items.armor.slots.LeggingsItem
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.factory.RegisteredEntityModelFactory
import de.bixilon.minosoft.gui.rendering.entities.feature.text.score.EntityScoreFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.armor.VanillaArmorPoseSource
import de.bixilon.minosoft.gui.rendering.entities.model.human.PlayerModel
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoader.Companion.sModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMeshBuilder
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTexture
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureListener
import de.bixilon.minosoft.gui.rendering.system.base.texture.dynamic.DynamicTextureState
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.skin.SkinManager.Companion.isReallyWide
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.readTexture
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

open class PlayerRenderer<E : PlayerEntity>(renderer: EntitiesRenderer, entity: E) : LivingEntityRenderer<E>(renderer, entity), DynamicTextureListener, VanillaArmorPoseSource {
    var model: PlayerModel? = null
    override val vanillaArmorPose get() = model?.instance
    var skin: DynamicTexture? = null
        private set
    var etfSkin: EtfPlayerSkinTextures? = null
        private set
    private var pendingSkin: DynamicTexture? = null

    protected var unloadModel = false

    val score = EntityScoreFeature(this).register()

    init {
        entity.additional::properties.observe(this) { unloadModel = true }
    }

    private fun unloadModel() {
        val model = this.model ?: return
        this.model = null
        features -= model
        renderer.retirementQueue += { model.unload() }
        this.unloadModel = false
    }

    override fun update(time: ValueTimeMark, delta: Duration, auxiliaryVisible: Boolean) {
        super.update(time, delta, auxiliaryVisible)
        if (unloadModel) unloadModel()
        if (this.model == null) {
            this.model = createModel()
            model?.register()
        }
    }

    private fun setSkin(): SkinModel? {
        pendingSkin?.removeListener(this)
        pendingSkin = null
        this.skin = null
        this.etfSkin = null
        val skin = renderer.context.textures.skins.getSkin(entity, fetch = false, async = true) ?: return null
        if (skin.texture.state == DynamicTextureState.LOADED) {
            setLoadedSkin(skin.texture)
            if (skin.model == SkinModel.WIDE && renderer.profile.features.player.detectSlim) return if (skin.isReallyWide()) SkinModel.WIDE else SkinModel.SLIM
            return skin.model
        } else {
            skin.default?.let(::setLoadedSkin)
            pendingSkin = skin.texture
            skin.texture.addListener(this)
        }

        return skin.model
    }

    private fun createModel(): PlayerModel? {
        val skin = setSkin() ?: return null
        return createModel(skin)
    }

    protected open fun createModel(skin: SkinModel): PlayerModel? {
        val model = getModel(skin) ?: return null

        return PlayerModel(this, model, skin)
    }


    private fun getModel(skin: SkinModel): BakedSkeletalModel? {
        val name = when (skin) {
            SkinModel.WIDE -> WIDE
            SkinModel.SLIM -> SLIM
        }
        return renderer.context.models.skeletal[name]
    }

    override fun onDynamicTextureChange(texture: DynamicTexture): Boolean {
        if (texture !== pendingSkin) return true
        if (texture.state == DynamicTextureState.ERROR) {
            pendingSkin = null
            return true
        }
        if (texture.state != DynamicTextureState.LOADED) return false
        pendingSkin = null
        setLoadedSkin(texture)
        return true
    }

    fun skinFrame(): EtfPlayerSkinFrame? {
        val skin = this.skin ?: return null
        return etfSkin?.at(entity.age.toLong(), entity.uuid?.hashCode()?.toLong() ?: entity.id?.toLong() ?: 0L)
            ?: EtfPlayerSkinFrame(
                base = skin,
                emissive = null,
                enchant = null,
                blinkState = EntityTextureBlinkState.OPEN,
            )
    }

    fun canRenderEtfCoat(): Boolean =
        SkinParts.JACKET in entity.skinParts &&
            entity.equipment[EquipmentSlots.LEGS]?.item !is LeggingsItem

    private fun setLoadedSkin(texture: DynamicTexture) {
        this.skin = texture
        this.etfSkin = null
        val buffer = texture.data?.buffer ?: return
        val derived = EtfPlayerSkinProcessor.process(buffer) ?: return
        renderer.context.queue += {
            if (this.skin === texture) installEtfSkin(texture, derived)
        }
    }

    private fun installEtfSkin(source: DynamicTexture, buffers: EtfPlayerSkinBuffers) {
        val dynamic = renderer.context.textures.dynamic
        fun push(kind: EtfPlayerDerivedTextureKind, buffer: TextureBuffer?) =
            buffer?.let {
                dynamic.push(EtfPlayerDerivedTextureIdentifier(source, kind), async = false) { it }
            }
        val nose = when (buffers.noseType) {
            EtfPlayerNoseType.VILLAGER -> dynamic.push(
                EtfPlayerSharedTextureIdentifier.VILLAGER_NOSE,
                async = false,
            ) { renderer.context.session.assets[VILLAGER_NOSE].readTexture() }
            EtfPlayerNoseType.TEXTURED -> push(EtfPlayerDerivedTextureKind.NOSE, buffers.nose)
            EtfPlayerNoseType.VILLAGER_TEXTURED, EtfPlayerNoseType.NONE -> null
        }
        etfSkin = EtfPlayerSkinTextures(
            base = push(EtfPlayerDerivedTextureKind.BASE, buffers.base) ?: source,
            blink = push(EtfPlayerDerivedTextureKind.BLINK, buffers.blink),
            blink2 = push(EtfPlayerDerivedTextureKind.BLINK_2, buffers.blink2),
            emissive = push(EtfPlayerDerivedTextureKind.EMISSIVE, buffers.emissive),
            blinkEmissive = push(EtfPlayerDerivedTextureKind.BLINK_EMISSIVE, buffers.blinkEmissive),
            blink2Emissive = push(EtfPlayerDerivedTextureKind.BLINK_2_EMISSIVE, buffers.blink2Emissive),
            enchant = push(EtfPlayerDerivedTextureKind.ENCHANT, buffers.enchant),
            blinkEnchant = push(EtfPlayerDerivedTextureKind.BLINK_ENCHANT, buffers.blinkEnchant),
            blink2Enchant = push(EtfPlayerDerivedTextureKind.BLINK_2_ENCHANT, buffers.blink2Enchant),
            coat = push(EtfPlayerDerivedTextureKind.COAT, buffers.coat),
            coatEmissive = push(EtfPlayerDerivedTextureKind.COAT_EMISSIVE, buffers.coatEmissive),
            coatEnchant = push(EtfPlayerDerivedTextureKind.COAT_ENCHANT, buffers.coatEnchant),
            fatCoat = buffers.fatCoat,
            allowBaseTransparency =
                renderer.profile.features.player.etfSkinTransparency && !buffers.forcedSolidLowerSkin,
            nose = nose,
            noseEmissive = push(EtfPlayerDerivedTextureKind.NOSE_EMISSIVE, buffers.noseEmissive),
            noseEnchant = push(EtfPlayerDerivedTextureKind.NOSE_ENCHANT, buffers.noseEnchant),
            noseType = buffers.noseType,
        )
    }

    override fun unload() {
        pendingSkin?.removeListener(this)
        pendingSkin = null
        etfSkin = null
        skin = null
        super.unload()
    }

    override fun updateMatrix(delta: Duration) {
        super.updateMatrix(delta)
        when (entity.pose) {
            Poses.SNEAKING -> matrix.apply { translateYAssign(SNEAKING_OFFSET) } // TODO: interpolate
            else -> Unit
        }
    }


    companion object : RegisteredEntityModelFactory<PlayerEntity>, Identified, SkeletalMeshBuilder {
        override val identifier get() = PlayerEntity.identifier
        val WIDE = minecraft("entities/player/wide").sModel()
        val SLIM = minecraft("entities/player/slim").sModel()
        val SKIN = minecraft("skin")
        val NOSE = minecraft("etf_nose")
        val VILLAGER_NOSE = minecraft("entity/villager/villager").texture()
        private val GLINT_TEXTURE = minecraft("misc/enchanted_glint_entity").texture()
        lateinit var GLINT: Texture
            private set

        private const val SNEAKING_OFFSET = -0.125f

        override fun create(renderer: EntitiesRenderer, entity: PlayerEntity) = PlayerRenderer(renderer, entity)
        override fun buildMesh(context: RenderContext) = PlayerModelMeshBuilder(context)

        override fun register(loader: ModelLoader) {
            GLINT = loader.context.textures.static.create(GLINT_TEXTURE)
            val override = mapOf(
                SKIN to PlayerSkinUvTexture,
                NOSE to loader.context.textures.debugTexture,
            ) // disable textures, they all dynamic
            loader.skeletal.register(WIDE, override = override, mesh = this)
            loader.skeletal.register(SLIM, override = override, mesh = this)
        }
    }
}
