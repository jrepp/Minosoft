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
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.cache.ChunkMeshCache
import de.bixilon.minosoft.gui.rendering.chunk.mesher.ChunkMesher
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.tasks.MeshPrepareTask
import de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.tasks.MesherTaskManager
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCompletion
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildIdentity
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildOutcome
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildRuntime
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildSnapshot
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainCancellationToken
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainProductionPhase
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.ConcurrentHashMap

class ChunkMeshingQueue(
    private val renderer: ChunkRenderer,
) {
    private data class PreparedMesh(
        val section: ChunkSection,
        val cache: ChunkMeshCache,
        val mesh: de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes?,
        val failure: Throwable? = null,
    )

    private val comparator = MeshQueueComparator()
    val tasks = MesherTaskManager(renderer)
    private val runtimeDelegate: Lazy<TerrainBuildRuntime<ChunkMesher.WorkerContext, PreparedMesh>> = lazy {
        TerrainBuildRuntime<ChunkMesher.WorkerContext, PreparedMesh>(
            workerCount = tasks.max,
            queueCapacity = tasks.max * 2,
            threadNamePrefix = "Terrain build",
            contextFactory = renderer.mesher::createWorkerContext,
        )
    }
    private val runtime by runtimeDelegate
    private val telemetry get() = renderer.terrainPerformance

    private val queue = ArrayDeque<MeshQueueItem>(1000)
    private val positions: MutableSet<SectionPosition> = HashSet(1000)
    private val latestRequests: MutableMap<SectionPosition, TerrainBuildIdentity> = HashMap(1000)
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
        telemetry.queueDepth(0)
        if (runtimeDelegate.isInitialized()) runtime.cancelAll()
    }

    fun unsafeAdd(section: ChunkSection, cause: ChunkMeshingCause) {
        val position = SectionPosition.of(section)
        latestRequests[position] = createIdentity(section)
        telemetry.requested()
        if (!positions.add(position)) return

        this.queue += queuedItem(section, cause)
        telemetry.queueDepth(queue.size)
    }

    operator fun plusAssign(section: ChunkSection): Unit = lock.locked {
        val position = SectionPosition.of(section)
        latestRequests[position] = createIdentity(section)
        telemetry.requested()
        if (!positions.add(position)) return

        this.queue += queuedItem(section, ChunkMeshingCause.UNKNOWN)
        queue.sortWith(comparator)
        telemetry.queueDepth(queue.size)
    }

    operator fun minusAssign(position: ChunkPosition) = removeIf(false) { it.chunkPosition == position }

    operator fun minusAssign(position: SectionPosition) = lock.locked {
        this.positions.remove(position)
        this.queue.removeIf { it.position == position } // TODO: only first
        latestRequests.remove(position)
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
        }
        telemetry.queueDepth(queue.size)
    }


    private fun enqueue(item: MeshQueueItem): Boolean {
        val section = item.section
        val position = SectionPosition.of(section)
        val identity = latestRequests[position] ?: return false

        val cancellation = TerrainCancellationToken()
        val task = MeshPrepareTask(section, identity, cancellation)
        tasks += task
        submittedTasks[identity] = task
        telemetry.outstandingBuilds(tasks.size)
        val accepted = runtime.submit(identity, cancellation) { context, token ->
            val workerStarted = telemetry.workerStarted()
            telemetry.started()
            telemetry.finish(TerrainProductionPhase.QUEUE_WAIT, item.queuedAtNanos)
            try {
                val modelLoader: ModelLoader? = renderer.context.models
                val cache = ChunkMeshCache(renderer.context, modelLoader?.skeletal)
                try {
                    val snapshotStarted = telemetry.begin(TerrainProductionPhase.SNAPSHOT_CAPTURE)
                    val snapshot = try {
                        TerrainBuildSnapshot.capture(section)
                    } finally {
                        telemetry.finish(TerrainProductionPhase.SNAPSHOT_CAPTURE, snapshotStarted)
                    }
                    if (snapshot.centerRevision != identity.modelRevision) token.cancel()
                    val meshStarted = telemetry.begin(TerrainProductionPhase.MESH_BUILD)
                    val mesh = try {
                        context.mesh(cache, section, snapshot, token)
                    } finally {
                        telemetry.finish(TerrainProductionPhase.MESH_BUILD, meshStarted)
                    }
                    mesh?.let { telemetry.output(it.outputBytes) }
                    mesh?.candidateCache = cache
                    PreparedMesh(section, cache, mesh)
                } catch (failure: Throwable) {
                    PreparedMesh(section, cache, null, failure)
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
            queue.addFirst(queuedItem(section, ChunkMeshingCause.UNKNOWN))
            positions += position
            telemetry.queueDepth(queue.size)
            return false
        }
        return true
    }

    private fun createIdentity(section: ChunkSection) = TerrainBuildIdentity(
        position = SectionPosition.of(section),
        requestRevision = nextRequestRevision++,
        modelRevision = section.terrainRevision.get(),
        backendGeneration = renderer.terrain.selection().generation,
        materialGeneration = renderer.context.shaderPipeline.selection().fingerprint,
    )

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

    fun publishCompleted() {
        if (!runtimeDelegate.isInitialized()) return
        runtime.drain { publish(it) }
    }

    private fun publish(completion: TerrainBuildCompletion<PreparedMesh>) {
        submittedTasks.remove(completion.identity)?.let {
            tasks -= it
            telemetry.outstandingBuilds(tasks.size)
        }
        val requestCurrent = lock.locked {
            latestRequests[completion.identity.position] == completion.identity
        }
        val current = requestCurrent &&
            completion.identity.modelRevision == completion.outcome.sectionRevisionOrNull() &&
            renderer.terrain.selection().generation == completion.identity.backendGeneration &&
            renderer.context.shaderPipeline.selection().fingerprint == completion.identity.materialGeneration

        when (val outcome = completion.outcome) {
            is TerrainBuildOutcome.Success -> {
                if (current) {
                    publish(completion.identity, outcome.value)
                } else {
                    telemetry.stale()
                    discard(outcome.value)
                    if (requestCurrent) renderer.invalidate(outcome.value.section)
                }
            }

            is TerrainBuildOutcome.Cancelled -> {
                telemetry.cancelled()
                outcome.completedValue?.let(::discard)
            }
            is TerrainBuildOutcome.Failure -> {
                telemetry.failed()
                Log.log(LogMessageType.GENERAL, LogLevels.WARN, outcome.error)
            }
        }
        if (requestCurrent) {
            lock.locked { latestRequests.remove(completion.identity.position, completion.identity) }
        }
    }

    private fun publish(identity: TerrainBuildIdentity, prepared: PreparedMesh) {
        if (prepared.failure != null) {
            telemetry.failed()
            prepared.cache.drop()
            Log.log(LogMessageType.GENERAL, LogLevels.WARN, prepared.failure)
            return
        }

        telemetry.succeeded()
        val mesh = prepared.mesh
        if (mesh == null) { // TODO: Store lod and check if it changed (not that it got completely optimized out and never updated)
            renderer.loaded -= identity.position
            prepared.cache.drop()
            renderer.cache.publish(identity.position, null)
        } else {
            renderer.loadingQueue += mesh
        }
    }

    private fun discard(prepared: PreparedMesh) {
        prepared.mesh?.drop()
        prepared.cache.drop()
    }

    private fun TerrainBuildOutcome<PreparedMesh>.sectionRevisionOrNull(): Long? = when (this) {
        is TerrainBuildOutcome.Success -> value.section.terrainRevision.get()
        is TerrainBuildOutcome.Cancelled -> completedValue?.section?.terrainRevision?.get()
        is TerrainBuildOutcome.Failure -> null
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
        try {
            publishCompleted()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        if (failure != null) throw failure
    }

    private fun queuedItem(section: ChunkSection, cause: ChunkMeshingCause) = MeshQueueItem(
        section,
        cause,
        telemetry.begin(TerrainProductionPhase.QUEUE_WAIT),
    )
}
