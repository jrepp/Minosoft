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

package de.bixilon.minosoft.gui.rendering.chunk.queue.meshing

import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.kutil.concurrent.lock.locks.reentrant.ReentrantLock
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.entities.BlockEntityRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.cache.ChunkMeshCache
import de.bixilon.minosoft.gui.rendering.chunk.mesher.ChunkMesher
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.tasks.MeshPrepareTask
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.tasks.MesherTaskManager
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildSnapshot
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainProductionPhase
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainSnapshotCaptureException
import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.runtime.TerrainProcessBuildService
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildCompletion
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildOutcome
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainBuildUrgency
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainCancellationToken
import de.bixilon.minosoft.terrain.runtime.scheduling.TerrainSchedulerTenantId
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.ConcurrentHashMap

class ChunkMeshingQueue(
    private val renderer: ChunkRenderer,
) {
    private val schedulerOwnerId = "near:${renderer.session.sessionId}:${System.identityHashCode(renderer)}"
    private val schedulerTenant = TerrainSchedulerTenantId(schedulerOwnerId)

    private data class PreparedMesh(
        val section: ChunkSection,
        val snapshot: TerrainBuildSnapshot?,
        val cache: ChunkMeshCache,
        val mesh: de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes?,
        val failure: Throwable? = null,
    )

    private val comparator = MeshQueueComparator()
    val tasks = MesherTaskManager(renderer)
    private val runtimeDelegate = lazy {
        TerrainProcessBuildService.shared.register<ChunkMesher.WorkerContext, PreparedMesh>(
            ownerId = schedulerOwnerId,
            tenant = schedulerTenant,
            contextFactory = renderer.mesher::createWorkerContext,
            disposer = ::discard,
        )
    }
    private val runtime by runtimeDelegate
    private val telemetry get() = renderer.terrainPerformance

    private val queue = ArrayDeque<MeshQueueItem>(1000)
    private val positions: MutableSet<SectionPosition> = HashSet(1000)
    private val latestRequests: MutableMap<SectionPosition, TerrainBuildIdentity> = HashMap(1000)
    private val latestCauses: MutableMap<SectionPosition, ChunkMeshingCause> = HashMap(1000)
    private val submittedTasks = ConcurrentHashMap<TerrainBuildIdentity, MeshPrepareTask>()
    private var nextRequestRevision = 1L

    val lock = ReentrantLock()

    init {
        telemetry.configureWorkers(tasks.max)
    }

    val size: Int get() = lock.locked { queue.size }


    fun sort() = lock.locked {
        comparator.update(renderer.visibility.eyePosition)
        queue.sortWith(comparator)
    }

    fun clear() = lock.locked {
        queue.clear()
        this.positions.clear()
        latestRequests.clear()
        latestCauses.clear()
        telemetry.queueDepth(0)
        if (runtimeDelegate.isInitialized()) runtime.cancelAll()
    }

    fun unsafeAdd(section: ChunkSection, cause: ChunkMeshingCause) {
        val position = SectionPosition.of(section)
        telemetry.requested(cause)
        val previous = latestRequests[position]
        if (previous != null && hasSameBuildInputs(previous, section)) {
            telemetry.suppressed(cause)
            return
        }
        latestRequests[position] = createIdentity(section)
        latestCauses[position] = cause
        if (!positions.add(position)) return

        this.queue += queuedItem(section, cause)
        telemetry.queueDepth(queue.size)
    }

    operator fun plusAssign(section: ChunkSection) {
        add(section, ChunkMeshingCause.UNKNOWN)
    }

    fun add(section: ChunkSection, cause: ChunkMeshingCause) {
        val position = SectionPosition.of(section)
        val accepted = lock.locked {
            telemetry.requested(cause)
            val previous = latestRequests[position]
            if (previous != null && hasSameBuildInputs(previous, section)) {
                telemetry.suppressed(cause)
                return@locked false
            }
            latestRequests[position] = createIdentity(section)
            latestCauses[position] = cause
            if (!positions.add(position)) return@locked true

            this.queue += queuedItem(section, cause)
            queue.sortWith(comparator)
            telemetry.queueDepth(queue.size)
            true
        }
        if (accepted) renderer.loaded.coverageRequested(position.chunkPosition)
    }

    operator fun minusAssign(position: ChunkPosition) = removeIf(false) { it.chunkPosition == position }

    operator fun minusAssign(position: SectionPosition) = lock.locked {
        this.positions.remove(position)
        this.queue.removeIf { it.position == position } // TODO: only first
        latestRequests.remove(position)
        latestCauses.remove(position)
        telemetry.queueDepth(queue.size)
        Unit
    }

    fun removeIf(requeue: Boolean, predicate: (position: SectionPosition) -> Boolean): Unit = lock.locked {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (!predicate.invoke(item.position)) continue

            if (requeue) {
                if (item.section in renderer.visibility) continue // it will just land in here again
                iterator.remove()

                renderer.unload(item.section) // TODO: don't remove from culled queue (and from meshing queue)
                renderer.culledQueue += item.section
            } else {
                iterator.remove()
            }


            this.positions -= item.position
            latestRequests.remove(item.position)
            latestCauses.remove(item.position)
        }
        telemetry.queueDepth(queue.size)
    }


    private fun enqueue(item: MeshQueueItem): Boolean {
        val section = item.section
        val position = SectionPosition.of(section)
        val identity = latestRequests[position] ?: return false
        val cause = latestCauses[position] ?: item.cause

        val cancellation = TerrainCancellationToken()
        val task = MeshPrepareTask(section, identity, cancellation)
        tasks += task
        submittedTasks[identity] = task
        telemetry.outstandingBuilds(tasks.size)
        val accepted = runtime.submit(
            identity = identity,
            urgency = TerrainBuildUrgency.IMMEDIATE,
            cancellation = cancellation,
        ) { context, token ->
            renderer.loaded.coverageBuilding(position.chunkPosition)
            val workerStarted = telemetry.workerStarted()
            telemetry.started(cause)
            telemetry.finish(TerrainProductionPhase.QUEUE_WAIT, item.queuedAtNanos)
            try {
                val modelLoader: ModelLoader? = renderer.context.models
                val cache = ChunkMeshCache(renderer.context, modelLoader?.skeletal)
                try {
                    val snapshotStarted = telemetry.begin(TerrainProductionPhase.SNAPSHOT_CAPTURE)
                    val snapshot = try {
                        TerrainBuildSnapshot.capture(section, renderer.mesher.snapshotHalo)
                    } finally {
                        telemetry.finish(TerrainProductionPhase.SNAPSHOT_CAPTURE, snapshotStarted)
                    }
                    if (snapshot.centerRevision != identity.capturedModelRevision) token.cancel()
                    val meshStarted = telemetry.begin(TerrainProductionPhase.MESH_BUILD)
                    val mesh = try {
                        context.mesh(cache, section, snapshot, identity, token)
                    } finally {
                        telemetry.finish(TerrainProductionPhase.MESH_BUILD, meshStarted)
                    }
                    mesh?.let { telemetry.output(it.outputBytes) }
                    mesh?.candidateCache = cache
                    PreparedMesh(section, snapshot, cache, mesh)
                } catch (failure: Throwable) {
                    PreparedMesh(section, null, cache, null, failure)
                }
            } finally {
                submittedTasks.remove(identity, task)
                tasks -= task
                telemetry.outstandingBuilds(tasks.size)
                telemetry.workerFinished(workerStarted)
                work()
            }
        }
        if (accepted == null) {
            submittedTasks.remove(identity, task)
            tasks -= task
            telemetry.outstandingBuilds(tasks.size)
            telemetry.rejected()
            queue.addFirst(queuedItem(section, cause))
            positions += position
            telemetry.queueDepth(queue.size)
            return false
        }
        return true
    }

    private fun createIdentity(section: ChunkSection): TerrainBuildIdentity {
        val requestRevision = nextRequestRevision
        nextRequestRevision = Math.incrementExact(nextRequestRevision)
        val terrainGeneration = renderer.terrain.selection().generation
        return TerrainBuildIdentity(
            page = SectionPosition.of(section).toTerrainPageKey(renderer.world.terrainEpoch),
            requestRevision = requestRevision,
            capturedModelRevision = section.terrainRevision.get(),
            providerGeneration = terrainGeneration,
            layoutGeneration = terrainGeneration,
            materialGeneration = renderer.context.shaderPipeline.selection().generation,
            coverageGeneration = 0L,
            prioritySequence = requestRevision,
        )
    }

    private fun hasSameBuildInputs(identity: TerrainBuildIdentity, section: ChunkSection): Boolean {
        val position = SectionPosition.of(section)
        val terrainGeneration = renderer.terrain.selection().generation
        return identity.page == position.toTerrainPageKey(renderer.world.terrainEpoch) &&
            identity.capturedModelRevision == section.terrainRevision.get() &&
            identity.providerGeneration == terrainGeneration &&
            identity.layoutGeneration == terrainGeneration &&
            identity.materialGeneration == renderer.context.shaderPipeline.selection().generation
    }

    fun work() {
        lock.locked {
            if (queue.isEmpty() || tasks.size >= tasks.max || renderer.loadingQueue.size >= renderer.loadingQueue.max) {
                return
            }
            while (queue.isNotEmpty()) {
                if (tasks.size >= tasks.max) break

                val item = queue.removeFirst()
                positions -= item.position
                telemetry.queueDepth(queue.size)
                if (latestRequests[item.position] == null) continue
                if (!enqueue(item)) break
            }
        }
    }

    fun publishCompleted(maxCompletions: Int = tasks.max * 2) {
        if (!runtimeDelegate.isInitialized()) return
        runtime.drain(maxCompletions) { publish(it) }
    }

    private fun publish(completion: TerrainBuildCompletion<PreparedMesh>) {
        submittedTasks.remove(completion.identity)?.let {
            tasks -= it
            telemetry.outstandingBuilds(tasks.size)
        }
        val position = completion.identity.page.toNearSectionPosition()
        val requestCurrent = lock.locked {
            latestRequests[position] == completion.identity
        }
        val terrainGeneration = renderer.terrain.selection().generation
        val currentIdentity = completion.identity.copy(
            page = completion.identity.page.copy(worldEpoch = renderer.world.terrainEpoch),
            capturedModelRevision = completion.outcome.sectionRevisionOrNull()
                ?: completion.identity.capturedModelRevision,
            providerGeneration = terrainGeneration,
            layoutGeneration = terrainGeneration,
            materialGeneration = renderer.context.shaderPipeline.selection().generation,
        )
        val current = requestCurrent && completion.identity.mismatch(currentIdentity) == null

        when (val outcome = completion.outcome) {
            is TerrainBuildOutcome.Success -> {
                if (current) {
                    publish(completion.identity, outcome.value)
                } else {
                    telemetry.stale()
                    discard(outcome.value)
                    if (requestCurrent) {
                        renderer.invalidate(
                            outcome.value.section,
                            ChunkMeshingCause.STALE_RETRY,
                            advanceRevision = false,
                        )
                    }
                }
            }

            is TerrainBuildOutcome.Cancelled -> {
                telemetry.cancelled()
                outcome.completedValue?.let(::discard)
                if (requestCurrent) renderer.loaded.coverageRequested(position.chunkPosition)
            }
            is TerrainBuildOutcome.Failure -> {
                telemetry.failed()
                if (requestCurrent) renderer.loaded.coverageRequested(position.chunkPosition)
                Log.log(LogMessageType.GENERAL, LogLevels.WARN, outcome.error)
            }
        }
        if (requestCurrent) {
            lock.locked {
                if (latestRequests.remove(position, completion.identity)) {
                    latestCauses.remove(position)
                }
            }
        }
    }

    private fun publish(identity: TerrainBuildIdentity, prepared: PreparedMesh) {
        if (prepared.failure != null) {
            telemetry.failed()
            renderer.loaded.coverageRequested(prepared.section.chunk.position)
            prepared.cache.drop()
            Log.log(LogMessageType.GENERAL, LogLevels.WARN, prepared.failure)
            if (prepared.failure is TerrainSnapshotCaptureException) {
                renderer.invalidate(
                    prepared.section,
                    ChunkMeshingCause.TASK_RETRY,
                    advanceRevision = false,
                )
            }
            return
        }

        val mesh = prepared.mesh
        if (mesh == null) { // TODO: Store lod and check if it changed (not that it got completely optimized out and never updated)
            val position = identity.page.toNearSectionPosition()
            renderer.loaded -= position
            prepared.cache.drop()
            renderer.cache.publish(position, null)
        } else {
            try {
                attachBlockEntities(prepared)
            } catch (failure: Throwable) {
                telemetry.failed()
                renderer.loaded.coverageRequested(prepared.section.chunk.position)
                discard(prepared)
                Log.log(LogMessageType.GENERAL, LogLevels.WARN, failure)
                return
            }
            renderer.loaded.coverageUploadPending(prepared.section.chunk.position)
            renderer.loadingQueue += mesh
        }
        telemetry.succeeded()
    }

    private fun attachBlockEntities(prepared: PreparedMesh) {
        val mesh = prepared.mesh ?: return
        if (mesh.entityPositions.isEmpty()) return
        val snapshot = checkNotNull(prepared.snapshot) { "Successful terrain mesh is missing its detached snapshot" }
        prepared.cache.unmark()
        val renderers = ArrayList<BlockEntityRenderer>(mesh.entityPositions.size)
        for (position in mesh.entityPositions) {
            val expected = snapshot.blockEntityMetadata(position) ?: continue
            val entity = prepared.section.entities[position] ?: continue
            if (entity.state.block.identifier.toString() != expected.blockIdentifier) continue
            val renderer = prepared.cache.createEntity(position, entity) ?: continue
            renderer.update(LightLevel(snapshot.light(position.x, position.y, position.z).toByte()))
            renderers += renderer
        }
        prepared.cache.cleanup()
        mesh.attachEntities(renderers.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun discard(prepared: PreparedMesh) {
        var failure: Throwable? = null
        try {
            prepared.mesh?.drop()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            prepared.cache.drop()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        if (failure != null) throw failure
    }

    private fun TerrainBuildOutcome<PreparedMesh>.sectionRevisionOrNull(): Long? = when (this) {
        is TerrainBuildOutcome.Success -> value.section.terrainRevision.get()
        is TerrainBuildOutcome.Cancelled -> completedValue?.section?.terrainRevision?.get()
        is TerrainBuildOutcome.Failure -> null
    }

    private fun SectionPosition.toTerrainPageKey(worldEpoch: Long) = TerrainPageKey(
        domain = TerrainDomain.NEAR,
        detailLevel = 0,
        x = x.toLong(),
        y = y.toLong(),
        z = z.toLong(),
        worldEpoch = worldEpoch,
    )

    private fun TerrainPageKey.toNearSectionPosition(): SectionPosition {
        check(domain == TerrainDomain.NEAR) { "Near meshing received a non-near terrain page" }
        check(detailLevel == 0) { "Near meshing received an unsupported detail level" }
        return SectionPosition(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z))
    }

    fun close() {
        clear()
        if (!runtimeDelegate.isInitialized()) return

        var failure: Throwable? = null
        try {
            runtime.close()
        } catch (error: Throwable) {
            failure = error
        }
        if (failure != null) throw failure
    }

    private fun queuedItem(section: ChunkSection, cause: ChunkMeshingCause) = MeshQueueItem(
        section,
        cause,
        telemetry.begin(TerrainProductionPhase.QUEUE_WAIT),
    )
}
