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

package de.bixilon.minosoft.gui.rendering.particle

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.array.ArrayUtil.cast
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.world.chunk.ChunkUtil.isInViewDistance
import de.bixilon.minosoft.data.world.particle.AbstractParticleRenderer
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.events.CameraMatrixChangeEvent
import de.bixilon.minosoft.gui.rendering.graph.RenderPassId
import de.bixilon.minosoft.gui.rendering.particle.mesh.ParticleMeshBuilder
import de.bixilon.minosoft.gui.rendering.particle.types.Particle
import de.bixilon.minosoft.gui.rendering.renderer.renderer.AsyncRenderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.LayerSettings
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.system.base.layer.OpaqueLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.TranslucentLayer
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.modding.event.listener.CallbackEventListener.Companion.listen
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.modding.loader.fabric.FabricParticleEventContext
import de.bixilon.minosoft.modding.loader.fabric.FabricParticleEvents
import de.bixilon.minosoft.util.collections.floats.FloatListUtil
import java.util.*


class ParticleRenderer(
    private val session: PlaySession,
    override val context: RenderContext,
) : WorldRenderer, AsyncRenderer, AbstractParticleRenderer {
    override val random = Random()
    override val layers = LayerSettings()
    private val profile = session.profiles.particle
    private val shader = context.system.shader.create(minosoft("particle")) { ParticleShader(it) }

    private val meshData = FloatListUtil.direct(1024 * ParticleMeshBuilder.ParticleMeshStruct.floats, false)
    private val translucentData = FloatListUtil.direct(512 * ParticleMeshBuilder.ParticleMeshStruct.floats, false)
    var mesh: Mesh? = null
    var translucentMesh: Mesh? = null

    val particles = ParticleList(profile.maxAmount)
    val queue = ParticleQueue(this)
    val ticker = ParticleTicker(this)
    private var matrixUpdate = true

    /**
     * Visual-reference suppression that does not clear or otherwise mutate
     * the live particle simulation.
     */
    var referenceSuppressed = false

    override val skip get() = !enabled || referenceSuppressed


    var enabled = true
        set(value) {
            if (!value) {
                particles.clear()
                queue.clear()
            }
            field = value
        }
    var maxAmount = MAXIMUM_AMOUNT
        set(value) {
            if (value < 0) throw IllegalStateException("Can not set negative amount of particles!")
            if (value < field) {
                removeAll()
            }
            field = value
        }

    val size: Int
        get() = particles.size

    fun hasParticle(particle: Particle): Boolean = particles.lock.locked {
        particle in particles.particles || queue.contains(particle)
    }

    override fun registerLayers() {
        layers.registerSemantic(
            OpaqueLayer,
            shader,
            renderer = { if (!referenceSuppressed) mesh?.draw() },
            semantic = PipelineSemantic.PARTICLES_OPAQUE,
            passId = RenderPassId("minosoft:scene/particles-opaque"),
            skip = { referenceSuppressed || mesh == null },
        )
        layers.registerSemantic(
            TranslucentLayer,
            shader,
            renderer = { if (!referenceSuppressed) translucentMesh?.draw() },
            semantic = PipelineSemantic.PARTICLES_TRANSLUCENT,
            passId = RenderPassId("minosoft:scene/particles-translucent"),
            skip = { referenceSuppressed || translucentMesh == null },
        )
    }

    private fun loadTextures() {
        for (particle in session.registries.particleType) {
            val loaded: Array<Texture> = arrayOfNulls<Texture?>(particle.textures.size).cast()
            for ((index, texture) in particle.textures.withIndex()) {
                loaded[index] = context.textures.static.create(texture)
            }
            particle.loadedTextures = loaded
        }
    }

    override fun init(latch: AbstractLatch) {
        profile::maxAmount.observe(this, true) { maxAmount = minOf(it, MAXIMUM_AMOUNT) }
        profile::enabled.observe(this, true) { enabled = it }

        // TODO: unload particles when renderer is paused

        session.events.listen<CameraMatrixChangeEvent> { matrixUpdate = true }


        loadTextures()
        DefaultParticleBehavior.register(session, this)
    }

    override fun postInit(latch: AbstractLatch) {
        shader.load()
        ticker.init()

        session.world.particle = this
    }

    override fun add(particle: Particle) {
        if (!context.state.running || !enabled) {
            return
        }
        if (!particle.chunkPosition.isInViewDistance(session.world.view.particleViewDistance, session.player.physics.positionInfo.chunkPosition)) {
            particle.dead = true
            return
        }

        FabricParticleEvents.dispatch(FabricParticleEventContext(session, particle))
        queue += particle
    }

    private fun updateShader() {
        val (right, up) = particleBillboardAxes(context.camera.matrix.viewMatrix)
        shader.cameraRight = right
        shader.cameraUp = up
    }

    override fun prePrepareDraw() {
        if (matrixUpdate) {
            updateShader()
            matrixUpdate = false
        }
        mesh?.unload()
        translucentMesh?.unload()

        this.mesh = null
        this.translucentMesh = null
    }

    override fun prepareDrawAsync() {
        this.meshData.clear()
        this.translucentData.clear()

        val mesh = ParticleMeshBuilder(context, this.meshData)
        val translucent = ParticleMeshBuilder(context, this.translucentData)

        ticker.tick(mesh, translucent)

        mesh._data?.takeIf { !it.isEmpty }?.let { this.mesh = mesh.bake() }
        translucent._data?.takeIf { !it.isEmpty }?.let { this.translucentMesh = translucent.bake() }
    }

    override fun postPrepareDraw() {
        mesh?.load()
        translucentMesh?.load()
    }

    override fun removeAll() {
        particles.clear()
        queue.clear()
    }

    override fun unload() {
        meshData.free()
        translucentData.free()
    }

    companion object : RendererBuilder<ParticleRenderer> {
        const val MAXIMUM_AMOUNT = 50000

        override fun build(session: PlaySession, context: RenderContext): ParticleRenderer? {
            if (session.profiles.particle.skipLoading) {
                return null
            }
            return ParticleRenderer(session, context)
        }
    }
}

/**
 * Extracts the world-space billboard basis from the view transform. The
 * projection matrix must not participate here: its FOV and aspect scaling
 * would turn a particle's world-space radius into a screen-dependent quad.
 */
internal fun particleBillboardAxes(viewMatrix: Mat4f): Pair<Vec3f, Vec3f> = Pair(
    Vec3f(viewMatrix[0, 0], viewMatrix[0, 1], viewMatrix[0, 2]),
    Vec3f(viewMatrix[1, 0], viewMatrix[1, 1], viewMatrix[1, 2]),
)
