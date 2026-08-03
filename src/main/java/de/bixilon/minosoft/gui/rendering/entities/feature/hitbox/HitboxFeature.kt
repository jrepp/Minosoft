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

package de.bixilon.minosoft.gui.rendering.entities.feature.hitbox

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.collections.primitive.floats.FloatList
import de.bixilon.kutil.collections.primitive.ints.IntList
import de.bixilon.kutil.math.interpolation.Interpolator
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.ColorInterpolation
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKeys
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.system.base.DepthFunctions
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.LineMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.GenericColorMeshBuilder.GenericColorMeshStruct
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3fUtil
import de.bixilon.minosoft.protocol.network.session.play.tick.TickUtil
import de.bixilon.minosoft.util.collections.floats.FloatListUtil
import de.bixilon.minosoft.util.collections.ints.IntListUtil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HitboxFeature(renderer: EntityRenderer<*>) : MeshedFeature<Mesh>(renderer, EntityRenderStateKeys.HITBOX), FeatureDrawable {
    private val manager = renderer.renderer.features.hitbox

    private var aabb = renderer.entity.renderInfo.cameraAABB
    private var eyePosition = Vec3f.EMPTY
    private var rotation = EntityRotation.EMPTY

    private var color = Interpolator(renderer.entity.hitboxColor ?: ChatColors.WHITE, ColorInterpolation::interpolateRGBA)
    private var velocity = Interpolator(Vec3f.EMPTY, Vec3fUtil::interpolateLinear)
    private var meshData: FloatList? = null
    private var meshIndex: IntList? = null
    private var builder: ReusableHitboxMeshBuilder? = null
    private var pendingVertexUpdate = false

    // TODO: manager.profile.showInvisible

    override fun update(delta: Duration) {
        super.update(delta)
        if (!manager.enabled) {
            unload = true
            return
        }

        val offset = renderer.renderer.context.camera.offset.offset

        val update = updateRenderInfo(offset) or interpolate(delta)

        if (unload || this.mesh == null || update) {
            createMesh()
        }
    }


    private fun updateRenderInfo(offset: BlockPosition): Boolean {
        var changes = 0

        val renderInfo = renderer.entity.renderInfo
        val aabb = renderInfo.cameraAABB // TODO: offset?
        val eyePosition = Vec3f(renderInfo.eyePosition - offset)
        val rotation = renderInfo.rotation

        if (aabb != this.aabb) {
            this.aabb = aabb; changes++
        }
        if (eyePosition != this.eyePosition) {
            this.eyePosition = eyePosition; changes++
        }
        if (rotation != this.rotation) {
            this.rotation = rotation; changes++
        }

        return changes > 0
    }

    private fun interpolate(delta: Duration): Boolean {
        if (color.delta >= 1.0f) {
            this.color.push(renderer.entity.hitboxColor ?: ChatColors.WHITE)
        }
        this.color.add((delta / 1.seconds).toFloat(), 0.3f)

        if (velocity.delta >= 1.0f) {
            this.velocity.push(Vec3f(renderer.entity.physics.velocity))
        }
        this.velocity.add((delta / 1.seconds).toFloat(), (TickUtil.TIME_PER_TICK / 1.seconds).toFloat())


        return !this.color.identical || !this.velocity.identical
    }

    private fun createMesh() {
        val aabb = aabb ?: return
        val mesh = resetBuilder()

        val color = color.value
        if (manager.profile.lazy) {
            mesh.drawLazyAABB(aabb, color)
        } else {
            mesh.drawShape(aabb, color = color)
        }

        val center = Vec3f(aabb.center)
        val velocity = velocity.value
        if (velocity.length2() > 0.003f) {
            mesh.drawLine(center, center + velocity * 5.0f, color = ChatColors.YELLOW)
        }

        mesh.drawLine(eyePosition, eyePosition + rotation.front * 5.0f, color = ChatColors.BLUE)
        mesh.padToCapacity()

        val current = this.mesh
        if (current == null || current.state != MeshStates.LOADED) {
            this.mesh = mesh.bake()
        } else {
            pendingVertexUpdate = true
        }
    }

    private fun resetBuilder(): ReusableHitboxMeshBuilder {
        val data = meshData ?: FloatListUtil.direct(MAX_FLOATS, false).also { meshData = it }
        val index = meshIndex ?: IntListUtil.direct(MAX_INDICES, false).also { meshIndex = it }
        data.clear()
        index.clear()
        val builder = builder ?: ReusableHitboxMeshBuilder(renderer.renderer.context, data, index).also { builder = it }
        builder.reset(data, index)
        return builder
    }

    override fun prepare() {
        if (pendingVertexUpdate) {
            pendingVertexUpdate = false
            val mesh = this.mesh
            if (mesh != null && mesh.state == MeshStates.LOADED) {
                builder?.updateVertices(mesh)
            } else {
                this.mesh = builder?.bake()
            }
        }
        super<MeshedFeature>.prepare()
    }

    override fun updateVisibility(level: EntityVisibilityLevels) = when {
        level >= EntityVisibilityLevels.VISIBLE -> super.updateVisibility(level)
        level == EntityVisibilityLevels.OCCLUDED && manager.profile.showThroughWalls -> super.updateVisibility(EntityVisibilityLevels.VISIBLE)
        else -> {
            unload = true
            super.updateVisibility(level)
        }
    }


    override fun draw(mesh: Mesh) {
        // TODO: update position with shader uniform
        val system = renderer.renderer.context.system
        if (manager.profile.showThroughWalls) {
            system.reset(depth = DepthFunctions.ALWAYS)
        } else {
            system.reset()
        }
        manager.shader.withProgramFamily(SceneProgramFamily.LINE, mesh::draw)
    }

    override fun unload() {
        var failure: Throwable? = null
        try {
            super.unload()
        } catch (error: Throwable) {
            failure = error
        }
        pendingVertexUpdate = false
        try {
            builder?.drop(free = false)
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        builder = null
        try {
            meshData?.free()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        meshData = null
        try {
            meshIndex?.free()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        meshIndex = null
        failure?.let { throw it }
    }

    private class ReusableHitboxMeshBuilder(
        context: de.bixilon.minosoft.gui.rendering.RenderContext,
        data: FloatList,
        index: IntList,
    ) : LineMeshBuilder(context, MAX_QUADS, data, index) {
        override val reused: Boolean = true

        fun reset(data: FloatList, index: IntList) {
            _data = data
            _index = index
        }

        fun padToCapacity() {
            while (data.size < MAX_FLOATS) {
                repeat(4) { addVertex(0.0f, 0.0f, 0.0f, ChatColors.WHITE) }
                addIndexQuad()
            }
            check(data.size == MAX_FLOATS) { "Hitbox geometry exceeded its fixed vertex capacity" }
            val expectedIndices = MAX_QUADS * if (remap) TRIANGLE_INDICES_PER_QUAD else QUAD_INDICES_PER_QUAD
            check(index.size == expectedIndices) {
                "Hitbox index count ${index.size} does not match the fixed $expectedIndices-index layout"
            }
        }
    }

    companion object {
        private const val MAX_QUADS = 56
        private val MAX_FLOATS = MAX_QUADS * 4 * GenericColorMeshStruct.floats
        private const val QUAD_INDICES_PER_QUAD = 4
        private const val TRIANGLE_INDICES_PER_QUAD = 6
        private const val MAX_INDICES = MAX_QUADS * TRIANGLE_INDICES_PER_QUAD
    }
}
