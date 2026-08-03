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
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldPassRegistry
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.gui.rendering.system.base.layer.OpaqueLayer
import de.bixilon.minosoft.gui.rendering.system.base.layer.TranslucentLayer
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
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
    override val passes = WorldPassRegistry()
    private val profile = session.profiles.particle
    private val shader = context.system.shader.create(minosoft("particle")) { ParticleShader(it) }

    private val meshData = FloatListUtil.direct(1024 * ParticleMeshBuilder.ParticleMeshStruct.floats, false)
    private val translucentData = FloatListUtil.direct(512 * ParticleMeshBuilder.ParticleMeshStruct.floats, false)
    var mesh: Mesh? = null
        private set
    var translucentMesh: Mesh? = null
        private set
    private var retainedMesh: Mesh? = null
    private var retainedTranslucentMesh: Mesh? = null
    private var meshCapacity = 0
    private var translucentMeshCapacity = 0
    private var pendingMesh: ParticleMeshBuilder? = null
    private var pendingTranslucentMesh: ParticleMeshBuilder? = null
    private var pendingMeshVertices = 0
    private var pendingTranslucentVertices = 0
    private var unloaded = false

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

    override fun registerPasses() {
        passes.add(
            OpaqueLayer,
            shader,
            renderer = { if (!referenceSuppressed) mesh?.draw() },
            semantic = PipelineSemantic.PARTICLES_OPAQUE,
            passId = RenderPassId("minosoft:scene/particles-opaque"),
            skip = { referenceSuppressed || mesh == null },
        )
        passes.add(
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
    }

    override fun prepareDrawAsync() {
        check(!unloaded) { "Particle renderer is unloaded" }
        this.meshData.clear()
        this.translucentData.clear()

        val mesh = ParticleMeshBuilder(context, this.meshData)
        val translucent = ParticleMeshBuilder(context, this.translucentData)

        ticker.tick(mesh, translucent)

        pendingMeshVertices = meshData.size / ParticleMeshBuilder.ParticleMeshStruct.floats
        pendingTranslucentVertices = translucentData.size / ParticleMeshBuilder.ParticleMeshStruct.floats
        pendingMesh = prepareCapacity(mesh, pendingMeshVertices, meshCapacity)
        pendingTranslucentMesh = prepareCapacity(translucent, pendingTranslucentVertices, translucentMeshCapacity)
    }

    override fun postPrepareDraw() {
        retainedMesh = realize(
            retainedMesh,
            pendingMesh,
            pendingMeshVertices,
            meshCapacity,
        ).also {
            meshCapacity = it.capacity
            mesh = it.visible
        }.retained
        retainedTranslucentMesh = realize(
            retainedTranslucentMesh,
            pendingTranslucentMesh,
            pendingTranslucentVertices,
            translucentMeshCapacity,
        ).also {
            translucentMeshCapacity = it.capacity
            translucentMesh = it.visible
        }.retained
        pendingMesh = null
        pendingTranslucentMesh = null
    }

    private fun prepareCapacity(builder: ParticleMeshBuilder, vertices: Int, currentCapacity: Int): ParticleMeshBuilder? {
        if (vertices == 0) return null
        val capacity = if (vertices <= currentCapacity) currentCapacity else nextCapacity(vertices)
        repeat(capacity - vertices) { builder.addPaddingVertex() }
        return builder
    }

    private fun realize(
        retained: Mesh?,
        builder: ParticleMeshBuilder?,
        vertices: Int,
        currentCapacity: Int,
    ): RetainedResult {
        if (builder == null) return RetainedResult(retained, null, currentCapacity)
        val capacity = builder.data.size / ParticleMeshBuilder.ParticleMeshStruct.floats
        if (retained != null && retained.state == MeshStates.LOADED && capacity == currentCapacity) {
            builder.updateVertices(retained, vertices)
            return RetainedResult(retained, retained, currentCapacity)
        }
        val replacement = builder.bake()
        replacement.buffer.setVertices(vertices)
        try {
            replacement.load()
            retained?.let {
                when (it.state) {
                    MeshStates.PREPARING -> it.drop()
                    MeshStates.LOADED -> it.unload()
                    MeshStates.UNLOADED -> Unit
                }
            }
        } catch (error: Throwable) {
            when (replacement.state) {
                MeshStates.PREPARING -> replacement.drop()
                MeshStates.LOADED -> replacement.unload()
                MeshStates.UNLOADED -> Unit
            }
            throw error
        }
        return RetainedResult(replacement, replacement, capacity)
    }

    override fun removeAll() {
        particles.clear()
        queue.clear()
    }

    override fun unload() {
        if (unloaded) return
        unloaded = true
        var failure: Throwable? = null
        for (owned in listOfNotNull(retainedMesh, retainedTranslucentMesh).distinct()) {
            try {
                when (owned.state) {
                    MeshStates.PREPARING -> owned.drop()
                    MeshStates.LOADED -> owned.unload()
                    MeshStates.UNLOADED -> Unit
                }
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        retainedMesh = null
        retainedTranslucentMesh = null
        mesh = null
        translucentMesh = null
        try {
            meshData.free()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            translucentData.free()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }

    private data class RetainedResult(val retained: Mesh?, val visible: Mesh?, val capacity: Int)

    companion object : RendererBuilder<ParticleRenderer> {
        const val MAXIMUM_AMOUNT = 50000

        fun nextCapacity(required: Int): Int {
            require(required > 0) { "Particle buffer capacity must be positive" }
            val highest = Integer.highestOneBit(required)
            return if (highest == required) required else Math.multiplyExact(highest, 2)
        }

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
