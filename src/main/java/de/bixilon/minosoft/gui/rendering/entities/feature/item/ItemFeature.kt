/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.item

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.MVec3f
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelTarget
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKey
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKeys
import de.bixilon.minosoft.gui.rendering.entities.feature.block.BlockMeshBuilder
import de.bixilon.minosoft.gui.rendering.entities.feature.block.BlockShader
import de.bixilon.minosoft.gui.rendering.entities.feature.item.ItemFeature.ItemRenderDistance.Companion.getCount
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.skeletal.GeckoLibEntityEventConsumer
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.ContentModelReloadable
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateContext
import de.bixilon.minosoft.gui.rendering.models.item.ItemRender
import de.bixilon.minosoft.gui.rendering.models.item.ItemRenderUtil.getModel
import de.bixilon.minosoft.gui.rendering.models.item.resolve
import de.bixilon.minosoft.gui.rendering.models.raw.display.DisplayPositions
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalModelStates
import de.bixilon.minosoft.gui.rendering.skeletal.instance.GeckoLibAnimationManagerSnapshot
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.util.Backports.nextFloatPort
import de.bixilon.minosoft.data.text.formatting.color.Colors
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import java.util.*
import kotlin.time.Duration

open class ItemFeature(
    renderer: EntityRenderer<*>,
    stack: ItemStack?,
    display: DisplayPositions,
    val many: Boolean = true,
) : MeshedFeature<Mesh>(renderer, EntityRenderStateKeys.ITEM_BLOCK), ContentModelReloadable, EntityOutlineFeature {
    override val renderStateKey: EntityRenderStateKey
        get() = if (skeletal == null) EntityRenderStateKeys.ITEM_BLOCK else EntityRenderStateKeys.ITEM_SKELETAL
    override val castsShadow get() = true
    var display: DisplayPositions = display
        set(value) {
            if (field == value) return
            field = value
            unload = true
        }
    private var matrix = MMat4f()
    private var displayMatrix = Mat4f.EMPTY
    private var distance: ItemRenderDistance? = null
    private var resolvedModel: ItemRender? = null
    private var skeletal: SkeletalInstance? = null
    private var pendingGeckoAnimation: GeckoLibAnimationManagerSnapshot? = null
    var stack: ItemStack? = stack
        set(value) {
            if (field == value) return
            field = value
            unload = true
        }

    override val layer get() = EntityLayer.Translucent // TODO

    override fun update(delta: Duration) {
        val stack = this.stack
        updateResolvedModel(stack)
        updateDistance()
        super.update(delta)

        if (this.mesh == null && this.skeletal == null) {
            stack ?: return unload()
            val model = resolvedModel
            if (!createSkeletal(stack, model) && model != null) createMesh(stack, model)
        }
        updateMatrix()
        updateSkeletal(delta)
    }

    private fun updateResolvedModel(stack: ItemStack?) {
        val context = renderer.renderer.context
        val model = stack?.item?.getModel(context.session)?.resolve(
            stack,
            ItemPredicateContext.of(renderer.entity, stack, context.itemPredicates),
        )
        if (model === resolvedModel) return
        resolvedModel = model
        unload = true
    }

    private fun updateDistance() {
        val distance = ItemRenderDistance.of(renderer.distance2)
        if (distance == this.distance) return
        unload = true
        this.distance = distance
    }

    private fun createMesh(stack: ItemStack, model: ItemRender) {
        val distance = this.distance ?: return
        val display = model.getDisplay(display, stack)
        this.displayMatrix = display?.matrix ?: Mat4f.EMPTY
        val mesh = BlockMeshBuilder(renderer.renderer.context)
        val offset = MVec3f()

        val tint = renderer.renderer.context.tints.getItemTint(stack)

        val count = if (many) distance.getCount(stack.count) else 1
        val spread = minOf(0.1f, count / 30.0f)

        model.render(offset.unsafe, mesh, stack, tint) // 0 without offset

        if (count > 1) {
            val random = Random(1234567890123456789L)
            for (i in 0 until count - 1) {
                offset.x = random.nextFloatPort(-spread, spread)
                offset.y = random.nextFloatPort(-spread, spread)
                offset.z = random.nextFloatPort(-spread, spread)

                model.render(offset.unsafe, mesh, stack, tint)
            }
        }
        // TODO: enchantment glint, ...

        this.mesh = mesh.bake()
    }

    private fun createSkeletal(stack: ItemStack, vanilla: ItemRender?): Boolean {
        val models = renderer.renderer.context.models.skeletal
        val name = models.contentModel(GeckoLibModelTarget.ITEM, stack.item.identifier) ?: return false
        val model = models[name] ?: return false
        val instance = model.createInstance(renderer.renderer.context)
        val events = GeckoLibEntityEventConsumer(renderer, instance)
        instance.neutralAnimation.eventConsumer = events::dispatch
        instance.geckoAnimation.eventConsumer = events::dispatch
        pendingGeckoAnimation?.let(instance.geckoAnimation::restore)
        pendingGeckoAnimation = null
        skeletal = instance
        unload = false

        displayMatrix = vanilla?.getDisplay(display, stack)?.matrix ?: Mat4f.EMPTY
        return true
    }

    private fun updateMatrix() {
        val matrix = this.matrix

        matrix.set(renderer.matrix.unsafe)
        matrix *= displayMatrix

        matrix.apply {
            translateXAssign(-0.5f)
            translateZAssign(-0.5f)
        }
    }

    private fun updateSkeletal(delta: Duration) {
        val skeletal = this.skeletal ?: return
        skeletal.transform.reset()
        skeletal.animation.draw(delta)
        val entity = renderer.entity
        val velocity = entity.physics.velocity
        val state = GeckoLibAnimationState(
            ageSeconds = entity.age.coerceAtLeast(0) / 20.0f,
            moving = velocity.x * velocity.x + velocity.z * velocity.z > MOVEMENT_EPSILON_SQUARED,
            data = mapOf(
                "query.is_on_ground" to if (entity.physics.onGround) 1.0 else 0.0,
                "query.is_in_water" to if (entity.physics.inWater) 1.0 else 0.0,
            ),
        )
        skeletal.geckoAnimation.updateState(state)
        if (skeletal.geckoAnimation.active) {
            skeletal.geckoAnimation.draw(delta, state)
        } else {
            skeletal.neutralAnimation.draw(delta)
        }
        skeletal.matrix.set(matrix.unsafe)
        skeletal.transform.transform(skeletal.matrix.unsafe)
        if (skeletal.geckoAnimation.active) {
            skeletal.geckoAnimation.dispatchEvents()
        } else {
            skeletal.neutralAnimation.dispatchEvents()
        }
    }

    override fun prepare() {
        super<MeshedFeature>.prepare()
        val skeletal = this.skeletal ?: return
        if (skeletal.state == SkeletalModelStates.PREPARING) skeletal.load()
    }

    override fun draw() {
        val skeletal = this.skeletal
        if (skeletal == null) {
            super.draw()
            return
        }
        val tint = renderer.light.value
        skeletal.draw(tint)
        drawGeckoRenderLayers(skeletal)
    }

    override fun draw(mesh: Mesh) {
        renderer.renderer.context.system.set(layer.settings)
        val shader = renderer.renderer.features.block.shader
        draw(mesh, shader)
    }


    protected open fun draw(mesh: Mesh, shader: BlockShader) {
        // Ordinary item-model quads share the retained block-feature vertex
        // layout, but Iris classifies dropped and display items as entity
        // geometry. Preserve the physical ABI while selecting the entity
        // program family instead of masquerading as a moving block.
        shader.withProgramFamily(SceneProgramFamily.ENTITY) {
            shader.matrix = matrix.unsafe
            shader.tint = renderer.light.value
            mesh.draw()
        }
    }

    override fun drawOutline(color: RGBAColor) {
        val context = renderer.renderer.context
        val system = context.system
        try {
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
            val skeletal = this.skeletal
            if (skeletal != null) {
                val shader = context.skeletal.shader
                shader.outlineColor = color
                skeletal.draw(shader)
                for ((name, layer) in skeletal.model.geckoRenderLayers) {
                    skeletal.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
                    skeletal.drawMesh(shader, layer.mesh)
                }
            } else {
                val mesh = this.mesh ?: return
                val shader = renderer.renderer.features.block.shader
                shader.outlineColor = color
                draw(mesh, shader)
            }
        } finally {
            context.skeletal.shader.outlineColor = Colors.TRANSPARENT
            renderer.renderer.features.block.shader.outlineColor = Colors.TRANSPARENT
            system.reset(depthTest = false, blending = false, faceCulling = false, depthMask = false)
        }
    }

    override fun unload() {
        this.displayMatrix = Mat4f.EMPTY
        releaseSkeletal(enqueue = false)
        super.unload()
    }

    override fun enqueueUnload() {
        val release = unload
        super.enqueueUnload()
        if (release) releaseSkeletal(enqueue = true)
    }

    override fun reloadContentModel() {
        pendingGeckoAnimation = skeletal?.geckoAnimation?.snapshot()
        unload = true
    }

    private fun releaseSkeletal(enqueue: Boolean) {
        val skeletal = this.skeletal ?: return
        this.skeletal = null
        skeletal.neutralAnimation.clearEvents()
        skeletal.geckoAnimation.clearEvents()
        val release = {
            when (skeletal.state) {
                SkeletalModelStates.PREPARING -> skeletal.drop()
                SkeletalModelStates.LOADED -> skeletal.unload()
                SkeletalModelStates.UNLOADED -> Unit
            }
        }
        if (enqueue && skeletal.state == SkeletalModelStates.LOADED) {
            renderer.renderer.queue += release
        } else {
            release()
        }
    }

    private fun drawGeckoRenderLayers(skeletal: SkeletalInstance) {
        if (skeletal.model.geckoRenderLayers.isEmpty()) return
        val system = renderer.renderer.context.system
        val shader = renderer.renderer.context.skeletal.shader
        val tint = renderer.light.value
        try {
            for ((name, layer) in skeletal.model.geckoRenderLayers) {
                skeletal.geckoAnimation.renderLayer(name, layer.registrationId) ?: continue
                when (layer.blend) {
                    GeckoLibRenderLayerBlend.OPAQUE -> system.reset(
                        faceCulling = false,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.TRANSLUCENT -> system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                        depth = DepthFunctions.EQUAL,
                    )
                    GeckoLibRenderLayerBlend.ADDITIVE -> system.reset(
                        blending = true,
                        faceCulling = false,
                        depthMask = false,
                        sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                        destinationRGB = BlendingFunctions.ONE,
                        sourceAlpha = BlendingFunctions.ONE,
                        destinationAlpha = BlendingFunctions.ONE,
                        depth = DepthFunctions.EQUAL,
                    )
                }
                shader.use()
                shader.tint = if (layer.fullBright) ChatColors.WHITE.rgb() else tint
                skeletal.drawMesh(shader, layer.mesh)
            }
        } finally {
            system.reset()
        }
    }

    private companion object {
        const val MOVEMENT_EPSILON_SQUARED = 1.0E-7
    }

    private enum class ItemRenderDistance(distance: Double) {
        CLOSE(10.0),
        MID(20.0),
        FAR(30.0),
        EXTREME(48.0),
        ;

        val distance = distance * distance

        companion object {
            fun of(distance: Double) = when {
                distance < CLOSE.distance -> CLOSE
                distance < MID.distance -> MID
                distance < FAR.distance -> FAR
                distance < EXTREME.distance -> EXTREME
                else -> null
            }

            fun ItemRenderDistance.getCount(count: Int) = when (this) {
                CLOSE -> when {
                    count <= 12 -> count
                    else -> 16
                }

                MID -> when {
                    count <= 4 -> count
                    count < 16 -> 5
                    count < 32 -> 6
                    count < 48 -> 7
                    else -> 8
                }

                FAR -> when {
                    count <= 2 -> count
                    count < 32 -> 3
                    else -> 4
                }

                EXTREME -> when {
                    count <= 1 -> count
                    count <= 32 -> 1
                    else -> 2
                }
            }
        }
    }
}
