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

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.reflection.ReflectionUtil.field
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.kutil.reflection.ReflectionUtil.getFieldOrNull
import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.minosoft.data.entities.entities.player.RemotePlayerEntity
import de.bixilon.minosoft.data.registries.entities.EntityFactory
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.create
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.setInvisible
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.input.key.manager.InputManager
import de.bixilon.minosoft.gui.rendering.system.base.buffer.GpuBufferStates
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.VertexBuffer
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.GenericColorMeshBuilder.GenericColorMeshStruct
import de.bixilon.minosoft.util.KUtil.startInit
import org.testng.Assert.*
import org.testng.annotations.Test
import java.nio.FloatBuffer
import kotlin.time.Duration.Companion.seconds

@Test(groups = ["entities", "rendering"])
class HitboxFeatureTest {
    private val MESH = MeshedFeature::class.java.getFieldOrNull("mesh")!!.field

    val HitboxFeature.mesh: Mesh?
        get() {
            enqueueUnload()
            renderer.renderer.queue.work()
            return MESH[this]
        }

    private fun create(entity: EntityFactory<*>): HitboxFeature {
        val renderer = create().create(entity)
        renderer::hitbox.forceSet(null) // remove
        renderer.entity.draw(now())
        renderer.renderer.context::input.forceSet(InputManager(renderer.renderer.context))
        renderer.renderer.profile.features.hitbox.enabled = true
        renderer.renderer.features.hitbox.enabled = true
        renderer.renderer.features.hitbox.init() // register listeners

        return HitboxFeature(renderer)
    }

    fun `create simple hitbox`() {
        val hitbox = create(RemotePlayerEntity)
        hitbox.update(0.0.seconds)
        assertNotNull(hitbox.mesh)
    }

    @Test(enabled = false) // the hitbox is not unloaded anymore if the entity goes dark
    fun `unload if entity is invisible`() {
        val hitbox = create(RemotePlayerEntity)
        hitbox.update(0.0.seconds)
        hitbox.renderer.entity.setInvisible(true)
        hitbox.update(0.0.seconds)
        assertNull(hitbox.mesh)
    }

    fun `entity is invisible but invisibles are shown`() {
        val hitbox = create(RemotePlayerEntity)
        hitbox.update(0.0.seconds)
        hitbox.renderer.entity.setInvisible(true)
        hitbox.renderer.renderer.profile.features.hitbox.showInvisible = true
        hitbox.update(0.0.seconds)
        assertNotNull(hitbox.mesh)
    }

    fun `profile disabled`() {
        val hitbox = create(RemotePlayerEntity)
        hitbox.renderer.renderer.profile.features.hitbox.enabled = false
        hitbox.update(0.0.seconds)
        assertNull(hitbox.mesh)
    }

    fun `don't update hitbox if unchanged`() {
        val hitbox = create(RemotePlayerEntity)
        hitbox.update(1.0.seconds)
        val mesh = hitbox.mesh
        hitbox.update(1.0.seconds)
        assertSame(mesh, hitbox.mesh)
    }

    fun `reuse hitbox GPU mesh if entity moved`() {
        val hitbox = create(RemotePlayerEntity)
        val start = now()
        hitbox.update(0.0.seconds)
        hitbox.prepare()
        val mesh = hitbox.mesh
        hitbox.renderer.entity.physics.forceMove(Vec3d(0.5))
        hitbox.renderer.entity.draw(start + 1.seconds)
        hitbox.update(1.0.seconds)
        hitbox.prepare()
        assertSame(mesh, hitbox.mesh)
    }

    fun `assigning the same mesh does not retire it`() {
        val renderer = create().create(RemotePlayerEntity)
        val feature = TestMeshedFeature(renderer)
        val buffer = CountingVertexBuffer()
        val mesh = Mesh(buffer).also(Mesh::load)

        feature.replace(mesh)
        feature.replace(mesh)
        renderer.renderer.queue.work()

        assertEquals(buffer.unloads, 0)
        feature.unload()
        assertEquals(buffer.unloads, 1)
    }

    fun `feature teardown drains queued retired meshes once`() {
        val renderer = create().create(RemotePlayerEntity)
        val feature = TestMeshedFeature(renderer)
        val first = CountingVertexBuffer()
        val second = CountingVertexBuffer()

        feature.replace(Mesh(first).also(Mesh::load))
        feature.replace(Mesh(second).also(Mesh::load))
        feature.unload()
        renderer.renderer.queue.work()

        assertEquals(first.unloads, 1)
        assertEquals(second.unloads, 1)
    }

    private class TestMeshedFeature(renderer: EntityRenderer<*>) : MeshedFeature<Mesh>(renderer) {
        fun replace(mesh: Mesh) {
            this.mesh = mesh
        }
    }

    private class CountingVertexBuffer : VertexBuffer {
        override var state = GpuBufferStates.PREPARING
            private set
        override val vertices = 0
        override val primitive = PrimitiveTypes.QUAD
        override val struct = GenericColorMeshStruct
        var unloads = 0
            private set

        override fun init() {
            state = GpuBufferStates.INITIALIZED
        }

        override fun unload() {
            unloads++
            state = GpuBufferStates.UNLOADED
        }

        override fun draw() = Unit
        override fun updateVertices(data: FloatBuffer) = Unit
        override fun drop() {
            state = GpuBufferStates.UNLOADED
        }
    }

    // TODO: velocity, correct size, direction, (eye height), lazy
    // TODO: visibility, interpolation
}
