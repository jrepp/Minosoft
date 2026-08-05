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

package de.bixilon.minosoft.gui.rendering.entities.draw

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
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlanner
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDrawState
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.system.base.layer.RenderLayer
import de.bixilon.minosoft.gui.rendering.system.base.settings.RenderSettings
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private val IRIS_ENTITY_SHADOW = minecraft("entity_shadow")
private val IRIS_NAME_TAG = minecraft("name_tag")
private val IRIS_ENTITY_FLAME = minecraft("entity_flame")

/** Releases peak-sized frame-list backing arrays only after sustained low use. */
internal class SustainedLowUseListTrimmer(
    private val minimumPeak: Int = 512,
    private val lowUseFrames: Int = 120,
    private val minimumRetainedCapacity: Int = 0,
) {
    internal var estimatedCapacity = minimumRetainedCapacity
        private set
    private var consecutiveLowUseFrames = 0

    init {
        require(minimumPeak > 0 && lowUseFrames > 0 && minimumRetainedCapacity >= 0)
    }

    fun clear(list: ArrayList<*>) {
        val used = list.size
        if (used > estimatedCapacity) estimatedCapacity = used
        consecutiveLowUseFrames = if (
            estimatedCapacity >= minimumPeak && used <= estimatedCapacity / 4
        ) {
            consecutiveLowUseFrames + 1
        } else {
            0
        }
        list.clear()
        if (consecutiveLowUseFrames < lowUseFrames) return
        list.trimToSize()
        if (minimumRetainedCapacity > 0) list.ensureCapacity(minimumRetainedCapacity)
        estimatedCapacity = minimumRetainedCapacity
        consecutiveLowUseFrames = 0
    }
}

class EntityDrawer(
    val renderer: EntitiesRenderer,
) {
    private class CollectionBatch {
        val layers = Array(EntityLayer.LAYERS.size) { ArrayList<FeatureDrawable>() }
        val shadowLayers = Array(EntityLayer.LAYERS.size) { ArrayList<FeatureDrawable>() }
        val outlines = ArrayList<EntityOutlineRenderer.Command>()
        private val layerTrimmers = Array(EntityLayer.LAYERS.size) { SustainedLowUseListTrimmer() }
        private val shadowLayerTrimmers = Array(EntityLayer.LAYERS.size) { SustainedLowUseListTrimmer() }
        private val outlineTrimmer = SustainedLowUseListTrimmer()

        fun clear() {
            layers.forEachIndexed { index, list -> layerTrimmers[index].clear(list) }
            shadowLayers.forEachIndexed { index, list -> shadowLayerTrimmers[index].clear(list) }
            outlineTrimmer.clear(outlines)
        }
    }

    private val layers = Array(EntityLayer.LAYERS.size) { ArrayList<FeatureDrawable>(100) }
    private val shadowLayers = Array(EntityLayer.LAYERS.size) { ArrayList<FeatureDrawable>(100) }
    private val layerTrimmers = Array(EntityLayer.LAYERS.size) {
        SustainedLowUseListTrimmer(minimumRetainedCapacity = 100)
    }
    private val shadowLayerTrimmers = Array(EntityLayer.LAYERS.size) {
        SustainedLowUseListTrimmer(minimumRetainedCapacity = 100)
    }
    private val collectionBatches = ConcurrentLinkedQueue<CollectionBatch>()
    private val localBatch = ThreadLocal.withInitial {
        CollectionBatch().also(collectionBatches::add)
    }
    private val preparedFeatures = Collections.newSetFromMap(IdentityHashMap<FeatureDrawable, Boolean>())
    val outline = EntityOutlineRenderer(renderer.context)

    var size = 0
        private set

    fun registerPasses() {
        for ((index, layer) in EntityLayer.LAYERS.withIndex()) {
            val semantic = when (layer) {
                EntityLayer.Opaque -> PipelineSemantic.ENTITIES
                EntityLayer.Translucent -> PipelineSemantic.ENTITIES_TRANSLUCENT
                else -> error("Unknown entity layer $layer")
            }
            renderer.passes.addViews(
                layer,
                null,
                renderer = { view ->
                    if (renderer.referenceSuppressed) return@addViews
                    if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
                        shadowLayers[index].drawShadowCasters()
                    } else {
                        layers[index].draw(layer)
                    }
                },
                semantic,
                passId = RenderPassId("minosoft:scene/entities-$index"),
                views = setOf(RenderViewId.MAIN, IrisShaderPackPlanner.SHADOW_VIEW),
            )
        }
        renderer.passes.add(
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
    private fun ArrayList<FeatureDrawable>.drawShadowCasters() {
        forEach { feature ->
            renderer.context.shaderPipeline.withDrawState(feature.irisDrawState(), feature::draw)
        }
    }

    fun prepare() {
        for (batch in collectionBatches) {
            for (index in layers.indices) {
                layers[index].addAll(batch.layers[index])
                shadowLayers[index].addAll(batch.shadowLayers[index])
            }
            for (command in batch.outlines) outline += command
            batch.clear()
        }
        preparedFeatures.clear()
        var size = 0
        for (index in layers.indices) {
            val layer = EntityLayer.LAYERS[index]
            val features = layers[index]
            for (feature in features) {
                if (!preparedFeatures.add(feature)) continue
                feature.prepare()
                size++
            }
            features.sort(layer.sort)
        }
        for (index in shadowLayers.indices) {
            val features = shadowLayers[index]
            for (feature in features) {
                if (!preparedFeatures.add(feature)) continue
                feature.prepare()
                size++
            }
            features.sortWith(::compareShadowDrawables)
        }
        outline.prepare()
        this.size = size
    }


    fun clear() {
        layers.forEachIndexed { index, list -> layerTrimmers[index].clear(list) }
        shadowLayers.forEachIndexed { index, list -> shadowLayerTrimmers[index].clear(list) }
        collectionBatches.forEach(CollectionBatch::clear)
        outline.clear()
    }

    operator fun plusAssign(drawable: FeatureDrawable) {
        val batch = localBatch.get()
        batch.layers[layerIndex(drawable.layer)] += drawable
        for (layer in drawable.additionalLayers) {
            if (layer != drawable.layer) batch.layers[layerIndex(layer)] += drawable
        }
    }

    fun addShadow(drawable: FeatureDrawable) {
        localBatch.get().shadowLayers[layerIndex(drawable.layer)] += drawable
    }

    fun addOutline(feature: EntityOutlineFeature, color: RGBAColor) {
        localBatch.get().outlines += EntityOutlineRenderer.Command(feature, color)
    }

    private fun layerIndex(layer: EntityLayer): Int = when (layer) {
        EntityLayer.Opaque -> 0
        EntityLayer.Translucent -> 1
        else -> error("Unknown entity layer $layer")
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
        is EntityShadowFeature -> IRIS_ENTITY_SHADOW
        is EntityNameFeature -> IRIS_NAME_TAG
        is EntityFlameFeature -> IRIS_ENTITY_FLAME
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
    var sort = a.priority.compareTo(b.priority)
    if (sort != 0) return sort

    if (order == EntityLayer.EntitySortOrders.NEAREST_FIRST) {
        sort = a.renderStateKey.compareTo(b.renderStateKey)
        if (sort != 0) return sort
    }

    sort = a.distance2.compareTo(b.distance2) * order.sign
    if (sort != 0) return sort

    if (order == EntityLayer.EntitySortOrders.FURTHEST_FIRST) {
        sort = a.renderStateKey.compareTo(b.renderStateKey)
        if (sort != 0) return sort
    }

    return a.stableOrder.compareTo(b.stableOrder)
}

internal fun compareShadowDrawables(a: FeatureDrawable, b: FeatureDrawable): Int {
    var ordered = a.priority.compareTo(b.priority)
    if (ordered != 0) return ordered
    ordered = a.renderStateKey.compareTo(b.renderStateKey)
    if (ordered != 0) return ordered
    return a.stableOrder.compareTo(b.stableOrder)
}
