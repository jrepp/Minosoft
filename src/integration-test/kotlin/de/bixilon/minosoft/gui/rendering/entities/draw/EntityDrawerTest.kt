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

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.reflection.ReflectionUtil.field
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.kutil.reflection.ReflectionUtil.getFieldOrNull
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.entities.entities.monster.Creeper
import de.bixilon.minosoft.data.entities.event.events.damage.GenericDamageEvent
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.createEntity
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.feature.item.ItemFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.flame.EntityFlameFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.LivingEntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisEntityOverlay
import de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisDrawState
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.LayerSettings
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["entities", "rendering"])
class EntityDrawerTest {
    private val LAYERS = EntityDrawer::class.java.getFieldOrNull("layers")!!.field
    private val SHADOW_LAYERS = EntityDrawer::class.java.getFieldOrNull("shadowLayers")!!.field

    private fun create(): EntityDrawer {
        val renderer = EntityRendererTestUtil.create()
        renderer::layers.forceSet(LayerSettings())
        return EntityDrawer(renderer)
    }

    private fun EntityDrawer.entity(): Entity {
        val entity = Pig(renderer.session, EntityRendererTestUtil.PIG, EntityData(renderer.session, Int2ObjectOpenHashMap()), Vec3d(), EntityRotation.EMPTY)
        entity.init()

        return entity
    }

    private fun EntityDrawer.feature(
        layer: EntityLayer,
        priority: Int = 0,
        distance: Double = 0.0,
        additionalLayers: Set<EntityLayer> = emptySet(),
        prepared: (() -> Unit)? = null,
    ) = object : EntityRenderer<Entity>(this.renderer, entity()) {

        val drawable = object : FeatureDrawable {
            override val layer = layer
            override val additionalLayers = additionalLayers
            override val priority = priority
            override val sort get() = 0
            override val distance2 = distance

            override fun prepare() {
                prepared?.invoke()
            }

            override fun draw() = Unit
        }

        init {
            this.distance2 = distance
            collect(this@feature)
        }

        override fun collect(drawer: EntityDrawer) {
            drawer += drawable
        }

        override fun toString() = "feature"
    }

    fun `no features`() {
        val drawer = create()
        drawer.prepare()
        assertEquals(drawer[EntityLayer.Opaque].size, 0)
        assertEquals(drawer[EntityLayer.Translucent].size, 0)
    }

    fun `registers outlines as a canonical world overlay pass`() {
        val drawer = create()
        drawer.registerLayers()

        val pass = drawer.renderer.layers.elements.single {
            it.passId == RenderPassId("minosoft:scene/entity-outlines")
        }
        assertEquals(pass.semantic, PipelineSemantic.WORLD_OVERLAY)
        assertEquals(pass.layer.priority, 4000)
    }

    fun `registers opaque and translucent entity semantics separately`() {
        val drawer = create()
        drawer.registerLayers()

        assertEquals(
            drawer.renderer.layers.elements.single {
                it.passId == RenderPassId("minosoft:scene/entities-0")
            }.semantic,
            PipelineSemantic.ENTITIES,
        )
        assertEquals(
            drawer.renderer.layers.elements.single {
                it.passId == RenderPassId("minosoft:scene/entities-1")
            }.semantic,
            PipelineSemantic.ENTITIES_TRANSLUCENT,
        )
    }

    fun `world item feature publishes entity and nested item identities`() {
        val drawer = create()
        val entity = drawer.entity()
        val entityRenderer = object : EntityRenderer<Entity>(drawer.renderer, entity) {
            override fun collect(drawer: EntityDrawer) = Unit
        }
        val item = requireNotNull(
            drawer.renderer.session.registries.item[ResourceLocation.of("minecraft:stone")],
        )
        val feature = ItemFeature(entityRenderer, ItemStack(item), DisplayPositions.GROUND)

        val state = feature.irisDrawState()

        assertEquals(state.entity, entity.type.identifier)
        assertEquals(state.item, item.identifier)
    }

    fun `living entity feature publishes exact hurt overlay`() {
        val drawer = create()
        val entity = drawer.entity() as Pig
        entity.onDamage(GenericDamageEvent)
        val entityRenderer = object : LivingEntityRenderer<Pig>(drawer.renderer, entity) {
            override fun collect(drawer: EntityDrawer) = Unit
        }
        val feature = object :
            de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderFeature(entityRenderer),
            FeatureDrawable {
            override val layer = EntityLayer.Opaque
            override val sort = 0
            override val distance2 = 0.0
            override fun draw() = Unit
            override fun collect(drawer: EntityDrawer) = Unit
        }

        assertEquals(feature.irisDrawState().entityColor, IrisEntityOverlay.HURT)
    }

    fun `creeper feature publishes its nonzero white fuse overlay`() {
        val drawer = create()
        val entity = drawer.renderer.createEntity(Creeper)
        entity.setWhiteOverlayFuseTicksForDebug(20)
        val entityRenderer = object : LivingEntityRenderer<Creeper>(drawer.renderer, entity) {
            override fun collect(drawer: EntityDrawer) = Unit
        }
        val feature = object :
            de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderFeature(entityRenderer),
            FeatureDrawable {
            override val layer = EntityLayer.Opaque
            override val sort = 0
            override val distance2 = 0.0
            override fun draw() = Unit
            override fun collect(drawer: EntityDrawer) = Unit
        }

        assertEquals(
            feature.irisDrawState().entityColor,
            IrisEntityOverlay.resolve(hurtOrDying = false, whiteOverlayProgress = 20.0f / 28.0f),
        )
    }

    fun `entity flame publishes its special Iris identity without living overlay`() {
        val drawer = create()
        val entity = drawer.entity() as Pig
        entity.onDamage(GenericDamageEvent)
        val entityRenderer = object : LivingEntityRenderer<Pig>(drawer.renderer, entity) {
            override fun collect(drawer: EntityDrawer) = Unit
        }
        val state = EntityFlameFeature(entityRenderer).irisDrawState()

        assertEquals(state.entity, ResourceLocation.of("minecraft:entity_flame"))
        assertEquals(state.entityColor, IrisDrawState.EMPTY.entityColor)
    }

    fun `single opaque features`() {
        val drawer = create()
        val feature = drawer.feature(EntityLayer.Opaque)
        drawer.prepare()
        assertEquals(drawer[EntityLayer.Opaque], listOf(feature.drawable))
        assertEquals(drawer[EntityLayer.Translucent].size, 0)
    }

    fun `single translucent features`() {
        val drawer = create()
        val feature = drawer.feature(EntityLayer.Translucent)
        drawer.prepare()
        assertEquals(drawer[EntityLayer.Opaque].size, 0)
        assertEquals(drawer[EntityLayer.Translucent], listOf(feature.drawable))
    }

    fun `composite feature is prepared once while participating in both layers`() {
        val drawer = create()
        var prepares = 0
        val feature = drawer.feature(
            EntityLayer.Opaque,
            additionalLayers = setOf(EntityLayer.Translucent),
            prepared = { prepares++ },
        )

        drawer.prepare()

        assertEquals(drawer[EntityLayer.Opaque], listOf(feature.drawable))
        assertEquals(drawer[EntityLayer.Translucent], listOf(feature.drawable))
        assertEquals(drawer.size, 1)
        assertEquals(prepares, 1)
    }

    fun `shadow collection is independent and shared features prepare once`() {
        val drawer = create()
        var prepares = 0
        val feature = drawer.feature(EntityLayer.Opaque, prepared = { prepares++ })
        drawer.addShadow(feature.drawable)

        drawer.prepare()

        assertEquals(drawer[EntityLayer.Opaque], listOf(feature.drawable))
        assertEquals(drawer.shadow(EntityLayer.Opaque), listOf(feature.drawable))
        assertEquals(prepares, 1)

        drawer.clear()
        assertEquals(drawer[EntityLayer.Opaque].size, 0)
        assertEquals(drawer.shadow(EntityLayer.Opaque).size, 0)
    }

    fun `two opaque features, sorted by distance`() {
        val drawer = create()
        val a = drawer.feature(EntityLayer.Opaque, priority = 0, 0.0)
        val b = drawer.feature(EntityLayer.Opaque, priority = 0, 1.0)
        drawer.prepare()
        assertEquals(drawer[EntityLayer.Opaque], listOf(a.drawable, b.drawable))
    }

    fun `two translucent features, sorted by distance`() {
        val drawer = create()
        val a = drawer.feature(EntityLayer.Translucent, priority = 0, 0.0)
        val b = drawer.feature(EntityLayer.Translucent, priority = 0, 1.0)
        drawer.prepare()
        assertEquals(drawer[EntityLayer.Translucent], listOf(b.drawable, a.drawable))
    }

    fun `two translucent features, sorted by type`() {
        val drawer = create()
        val a = drawer.feature(EntityLayer.Translucent, priority = 1, 0.0)
        val b = drawer.feature(EntityLayer.Translucent, priority = 0, 0.0)
        drawer.prepare()
        assertEquals(drawer[EntityLayer.Translucent], listOf(b.drawable, a.drawable))
    }

    fun `sorted by type and distance`() {
        val drawer = create()
        val a = drawer.feature(EntityLayer.Opaque, priority = 1, 0.0)
        val b = drawer.feature(EntityLayer.Opaque, priority = 0, 0.0)
        val c = drawer.feature(EntityLayer.Opaque, priority = 0, 5.0)
        val d = drawer.feature(EntityLayer.Opaque, priority = 0, 2.0)
        val e = drawer.feature(EntityLayer.Opaque, priority = 1, 2.0)
        drawer.prepare()
        assertEquals(drawer[EntityLayer.Opaque], listOf(b.drawable, d.drawable, c.drawable, a.drawable, e.drawable))
    }

    // TODO: test if type is respected

    private operator fun EntityDrawer.get(layer: EntityLayer) = LAYERS.get<Map<EntityLayer, ArrayList<FeatureDrawable>>>(this)[layer] ?: emptyList()
    private fun EntityDrawer.shadow(layer: EntityLayer) =
        SHADOW_LAYERS.get<Map<EntityLayer, ArrayList<FeatureDrawable>>>(this)[layer] ?: emptyList()
}
