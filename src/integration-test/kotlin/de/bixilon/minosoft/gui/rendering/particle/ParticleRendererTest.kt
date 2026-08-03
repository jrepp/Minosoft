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

package de.bixilon.minosoft.gui.rendering.particle

import de.bixilon.kmath.vec.vec3.d.MVec3d
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.observer.DataObserver
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.particle.ParticleType
import de.bixilon.minosoft.data.registries.particle.data.ParticleData
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.camera.Camera
import de.bixilon.minosoft.gui.rendering.light.RenderLight
import de.bixilon.minosoft.gui.rendering.particle.mesh.ParticleMeshBuilder
import de.bixilon.minosoft.gui.rendering.particle.types.Particle
import de.bixilon.minosoft.gui.rendering.system.dummy.DummyRenderSystem
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureManager
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotSame
import org.testng.Assert.assertNull
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

@Test(groups = ["particle"])
class ParticleRendererTest {

    fun `retained capacities grow geometrically`() {
        assertEquals(1, ParticleRenderer.nextCapacity(1))
        assertEquals(4, ParticleRenderer.nextCapacity(3))
        assertEquals(1024, ParticleRenderer.nextCapacity(513))
    }

    private fun create(): ParticleRenderer {
        val context = RenderContext::class.java.allocate()
        context::session.forceSet(createSession(1))
        context::state.forceSet(DataObserver(RenderingStates.RUNNING))
        context::system.forceSet(DummyRenderSystem(context))
        context::textures.forceSet(DummyTextureManager(context))
        context::light.forceSet(RenderLight(context))
        context::camera.forceSet(Camera(context))
        val renderer = ParticleRenderer(context.session, context)
        context::thread.forceSet(Thread.currentThread())


        return renderer
    }

    private fun ParticleRenderer.draw() {
        prePrepareDraw()
        prepareDrawAsync()
        postPrepareDraw()
    }


    fun setup() {
        create().unload()
    }

    fun `draw once`() {
        val renderer = create()
        assertEquals(renderer.size, 0)
        val particle = TestParticle(renderer.context.session)
        renderer += particle
        renderer.draw()
        assertEquals(particle.vertices, 1)
        assertEquals(particle.tryTicks, 1)
        assertFalse(particle.dead)
        assertEquals(renderer.size, 1)
        renderer.unload()
    }

    fun `draw twice`() {
        val renderer = create()
        val particle = TestParticle(renderer.context.session)
        renderer += particle
        renderer.draw(); renderer.draw()
        assertEquals(particle.vertices, 2)
        assertEquals(particle.tryTicks, 2)
        assertEquals(renderer.size, 1)
        renderer.unload()
    }

    fun kill() {
        val renderer = create()
        val particle = TestParticle(renderer.context.session)
        renderer += particle
        assertTrue(renderer.hasParticle(particle))
        renderer.draw(); renderer.draw()
        assertTrue(renderer.hasParticle(particle))
        particle.dead = true
        renderer.draw()
        assertEquals(particle.vertices, 2)
        assertEquals(particle.tryTicks, 2)
        assertEquals(renderer.size, 0)
        assertFalse(renderer.hasParticle(particle))
        renderer.unload()
    }

    fun `add 2 particles`() {
        val renderer = create()
        assertEquals(renderer.size, 0)
        val a = TestParticle(renderer.context.session)
        val b = TestParticle(renderer.context.session)
        renderer += a; renderer += b
        assertEquals(renderer.size, 0) // queue not updated yet
        renderer.draw()
        assertEquals(renderer.size, 2)
        assertEquals(a.vertices, 1); assertEquals(a.tryTicks, 1)
        assertEquals(b.vertices, 1); assertEquals(b.tryTicks, 1)
        renderer.unload()
    }

    fun `discard with maxAmount`() {
        val renderer = create()
        assertEquals(renderer.size, 0)
        renderer.maxAmount = 1
        val a = TestParticle(renderer.context.session)
        val b = TestParticle(renderer.context.session)
        renderer += a; renderer += b
        assertEquals(renderer.size, 0) // queue not updated yet
        renderer.draw()
        assertEquals(renderer.size, 1)
        assertEquals(a.vertices, 1); assertEquals(a.tryTicks, 1)
        assertEquals(b.vertices, 0); assertEquals(b.tryTicks, 0)
        renderer.unload()
    }

    fun `opaque particle allocation is retained resized and hidden across empty frames`() {
        val renderer = create()
        val firstParticle = TestParticle(renderer.context.session)
        renderer += firstParticle
        renderer.draw()
        val first = renderer.mesh ?: error("Expected an opaque particle mesh")
        assertEquals(first.buffer.vertices, 1)

        renderer.draw()
        assertSame(renderer.mesh, first)

        val secondParticle = TestParticle(renderer.context.session)
        renderer += secondParticle
        renderer.draw()
        val second = renderer.mesh ?: error("Expected a resized opaque particle mesh")
        assertNotSame(second, first)
        assertEquals(first.state, MeshStates.UNLOADED)
        assertEquals(second.buffer.vertices, 2)

        val thirdParticle = TestParticle(renderer.context.session)
        renderer += thirdParticle
        renderer.draw()
        val third = renderer.mesh ?: error("Expected a geometrically resized opaque particle mesh")
        assertNotSame(third, second)
        assertEquals(second.state, MeshStates.UNLOADED)
        assertEquals(third.buffer.vertices, 3)

        renderer.draw()
        assertSame(renderer.mesh, third)
        assertEquals(third.buffer.vertices, 3)

        firstParticle.dead = true
        secondParticle.dead = true
        thirdParticle.dead = true
        renderer.draw()
        assertNull(renderer.mesh)
        assertEquals(third.state, MeshStates.LOADED)

        renderer += TestParticle(renderer.context.session)
        renderer.draw()
        assertSame(renderer.mesh, third)
        assertEquals(third.buffer.vertices, 1)

        renderer.unload()
        renderer.unload()
        assertEquals(third.state, MeshStates.UNLOADED)
    }


    // TODO: auto ticking (task registering)

    private class TestParticle(session: PlaySession) : Particle(session, Vec3d.EMPTY, MVec3d.EMPTY, DATA) {
        var vertices = 0
        var tryTicks = 0

        init {
            maxAge = 10
        }

        override fun tryTick(time: ValueTimeMark) {
            tryTicks++
        }

        override fun addVertex(mesh: ParticleMeshBuilder, translucentMesh: ParticleMeshBuilder, time: ValueTimeMark) {
            mesh.addPaddingVertex()
            vertices++
        }


        companion object {
            val DATA = ParticleData(ParticleType(minosoft("test"), emptyList(), false, null))
        }
    }
}
