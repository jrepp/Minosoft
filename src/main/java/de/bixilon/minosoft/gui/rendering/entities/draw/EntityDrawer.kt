/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.draw

import de.bixilon.kutil.concurrent.lock.Lock
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.effect.EntityShadowFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.item.ItemFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.flame.EntityFlameFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.text.name.EntityNameFeature
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineFeature
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDrawState
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.system.base.layer.RenderLayer
import de.bixilon.minosoft.gui.rendering.system.base.settings.RenderSettings
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import java.util.Collections
import java.util.IdentityHashMap

class EntityDrawer(
    val renderer: EntitiesRenderer,
) {
    private val lock = Lock.lock()
    private val layers = HashMap<EntityLayer, ArrayList<FeatureDrawable>>()
    private val shadowLayers = HashMap<EntityLayer, ArrayList<FeatureDrawable>>()
    val outline = EntityOutlineRenderer(renderer.context)

    var size = 0
        private set

    fun registerLayers() {
        for ((index, layer) in EntityLayer.LAYERS.withIndex()) {
            val semantic = when (layer) {
                EntityLayer.Opaque -> PipelineSemantic.ENTITIES
                EntityLayer.Translucent -> PipelineSemantic.ENTITIES_TRANSLUCENT
                else -> error("Unknown entity layer $layer")
            }
            renderer.layers.registerSemantic(
                layer,
                null,
                { if (!renderer.referenceSuppressed) layers[layer]?.draw(layer) },
                semantic,
                passId = RenderPassId("minosoft:scene/entities-$index"),
                auxiliaryRenderers = mapOf(
                    IrisShaderPackPlanner.SHADOW_VIEW to {
                        if (!renderer.referenceSuppressed) shadowLayers[layer]?.drawShadowCasters(layer)
                    },
                ),
            )
        }
        renderer.layers.registerSemantic(
            EntityOutlineLayer,
            null,
            { if (!renderer.referenceSuppressed) outline.draw() },
            PipelineSemantic.WORLD_OVERLAY,
            passId = RenderPassId("minosoft:scene/entity-outlines"),
        ) { renderer.referenceSuppressed || outline.isEmpty }
    }

    private fun ArrayList<FeatureDrawable>.sort(order: EntityLayer.EntitySortOrders) =
        sortWith { a, b -> compareFeatureDrawables(a, b, order) }

    private fun ArrayList<FeatureDrawable>.draw(layer: EntityLayer) = forEach { feature ->
        renderer.context.shaderPipeline.withDrawState(feature.irisDrawState()) {
            feature.drawLayer(layer)
        }
    }
    private fun ArrayList<FeatureDrawable>.drawShadowCasters(layer: EntityLayer) {
        val shadow = renderer.context.shaderPipeline.plan()?.shadowDirectives
        val culling = renderer.context.shaderPipeline.shadowCulling()
        val camera = renderer.context.session.camera.entity.renderInfo.eyePosition
        forEach { feature ->
            val entity = (feature as? EntityRenderFeature)?.renderer?.entity
            val position = entity?.renderInfo?.position
            val dimensions = entity?.dimensions
            if (
                feature.layer == layer &&
                feature.castsShadow &&
                (
                    shadow == null ||
                        shadow.allowsEntity(entity is PlayerEntity) &&
                        (
                            position == null ||
                                dimensions == null ||
                                (culling?.allowsEntityBounds(
                                    position.x - dimensions.x * 0.5,
                                    position.y,
                                    position.z - dimensions.x * 0.5,
                                    position.x + dimensions.x * 0.5,
                                    position.y + dimensions.y,
                                    position.z + dimensions.x * 0.5,
                                ) ?: shadow.allowsEntityBounds(
                                    camera.x,
                                    camera.y,
                                    camera.z,
                                    position.x - dimensions.x * 0.5,
                                    position.y,
                                    position.z - dimensions.x * 0.5,
                                    position.x + dimensions.x * 0.5,
                                    position.y + dimensions.y,
                                    position.z + dimensions.x * 0.5,
                                ))
                            )
                    )
            ) {
                renderer.context.shaderPipeline.withDrawState(feature.irisDrawState(), feature::draw)
            }
        }
    }

    fun prepare() {
        val prepared = Collections.newSetFromMap(IdentityHashMap<FeatureDrawable, Boolean>())
        var size = 0
        for ((layer, features) in this.layers) {
            for (feature in features) {
                if (!prepared.add(feature)) continue
                feature.prepare()
                size++
            }
            features.sort(layer.sort)
        }
        for ((layer, features) in shadowLayers) {
            for (feature in features) {
                if (!prepared.add(feature)) continue
                feature.prepare()
                size++
            }
            features.sort(layer.sort)
        }
        outline.prepare()
        this.size = size
    }


    fun clear() {
        // don't remove array lists (reduce useless allocations)
        for ((_, features) in this.layers) {
            features.clear()
        }
        for ((_, features) in shadowLayers) {
            features.clear()
        }
        outline.clear()
    }

    operator fun plusAssign(drawable: FeatureDrawable) = lock.locked {
        val featureLayers = buildSet {
            add(drawable.layer)
            addAll(drawable.additionalLayers)
        }
        for (layer in featureLayers) {
            this.layers.getOrPut(layer) { ArrayList(100) } += drawable
        }
    }

    fun addShadow(drawable: FeatureDrawable) = lock.locked {
        val featureLayers = buildSet {
            add(drawable.layer)
            addAll(drawable.additionalLayers)
        }
        for (layer in featureLayers) {
            shadowLayers.getOrPut(layer) { ArrayList(100) } += drawable
        }
    }

    fun addOutline(feature: EntityOutlineFeature, color: RGBAColor) = lock.locked {
        outline += EntityOutlineRenderer.Command(feature, color)
    }

    private object EntityOutlineLayer : RenderLayer {
        override val settings = RenderSettings(
            depthTest = false,
            blending = true,
            faceCulling = false,
            depthMask = false,
        )
        override val priority = 4000
    }
}

fun FeatureDrawable.irisDrawState(): IrisDrawState {
    val feature = this as? EntityRenderFeature ?: return IrisDrawState.EMPTY
    val special = this is EntityShadowFeature || this is EntityNameFeature || this is EntityFlameFeature
    val identifier = when (this) {
        is EntityShadowFeature -> minecraft("entity_shadow")
        is EntityNameFeature -> minecraft("name_tag")
        is EntityFlameFeature -> minecraft("entity_flame")
        else -> feature.renderer.entity.type.identifier
    }
    val overlay = if (!special && feature.renderer is LivingEntityRenderer<*>) {
        IrisEntityOverlay.resolve(feature.renderer.entity)
    } else {
        IrisDrawState.EMPTY.entityColor
    }
    val item = (this as? ItemFeature)?.stack?.item?.identifier
    return IrisDrawState(entity = identifier, item = item, entityColor = overlay)
}

internal fun compareFeatureDrawables(
    a: FeatureDrawable,
    b: FeatureDrawable,
    order: EntityLayer.EntitySortOrders,
): Int {
    var sort = a.compareTo(b)
    if (sort != 0) return sort

    sort = a.distance2.compareTo(b.distance2) * order.sign
    if (sort != 0) return sort

    return a.stableOrder.compareTo(b.stableOrder)
}
