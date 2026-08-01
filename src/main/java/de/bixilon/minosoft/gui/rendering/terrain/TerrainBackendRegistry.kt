/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.terrain

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.TransactionalGenerationStore
import de.bixilon.minosoft.gui.rendering.graph.resource.TransactionalOverrideStore
import de.bixilon.minosoft.gui.rendering.stats.RenderTimingWindow

class TerrainBackendRegistry(
    private val builtIn: TerrainBackend,
) : AutoCloseable {
    internal data class Generation(
        val backend: TerrainBackend,
        val descriptor: TerrainBackendDescriptor,
        val closeOnRetire: Boolean,
    )

    data class Selection(
        val generation: Long,
        val owner: RenderOwnerId,
        val implementation: String,
    )

    data class RuntimeStats(
        val resources: TransactionalGenerationStore.Stats,
        val preparedFrames: Long,
        val submittedBatches: Long,
        val preparationTimingSamples: Int,
        val medianPreparationNanos: Long,
        val p95PreparationNanos: Long,
        val submissionTimingSamples: Int,
        val medianSubmissionNanos: Long,
        val p95SubmissionNanos: Long,
        val currentFrameSubmissions: Set<Pair<RenderViewId, TerrainMaterialClass>>,
    )

    class Lease internal constructor(
        private val delegate: TransactionalGenerationStore.Lease<Generation>,
    ) : AutoCloseable {
        val generation: Long get() = delegate.generation
        val backend: TerrainBackend get() = delegate.value.backend
        val descriptor: TerrainBackendDescriptor get() = delegate.value.descriptor

        override fun close() = delegate.close()
    }

    private val lock = Any()
    private val builtInDescriptor = snapshot(builtIn.descriptor)
    private val store = TransactionalOverrideStore(Generation(builtIn, builtInDescriptor, true)) { generation ->
        if (generation.closeOnRetire) generation.backend.close()
    }
    private var closed = false
    private var frameOpen = false
    private var frameLease: Lease? = null
    private var frameBackend: TerrainBackend? = null
    private var pinnedBackend: TerrainBackend? = null
    private var preparedFrames = 0L
    private var submittedBatches = 0L
    private var preparationStartedNanos = 0L
    private val preparationTimings = RenderTimingWindow()
    private val submissionTimings = RenderTimingWindow()
    private val frameSubmissions = linkedSetOf<Pair<RenderViewId, TerrainMaterialClass>>()

    fun acquire(): Lease = Lease(store.acquire())

    fun selection(): Selection = acquire().use { lease ->
        Selection(
            generation = lease.generation,
            owner = lease.descriptor.owner,
            implementation = lease.descriptor.implementation,
        )
    }

    fun descriptor(): TerrainBackendDescriptor = acquire().use { it.descriptor }

    fun replace(candidate: () -> TerrainBackend): AutoCloseable {
        val backend = candidate()

        try {
            val descriptor = snapshot(backend.descriptor)
            require(backend !== builtIn) { "A provider can not replace terrain with the built-in backend instance" }
            return synchronized(lock) {
                check(!closed) { "Terrain backend registry is closed" }
                store.replace { Generation(backend, descriptor, true) }
            }
        } catch (failure: Throwable) {
            try {
                backend.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    inline fun <T> withBackend(action: (TerrainBackend) -> T): T =
        acquire().use { lease -> action(lease.backend) }

    internal fun <T> withPinnedBackend(backend: TerrainBackend, action: () -> T): T {
        synchronized(lock) {
            check(!closed) { "Terrain backend registry is closed" }
            check(pinnedBackend == null) { "A terrain pipeline frame is already pinned" }
            check(!frameOpen) { "Terrain pipeline pin started while a frame is open" }
            pinnedBackend = backend
        }
        var failure: Throwable? = null
        try {
            return action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val unfinished = synchronized(lock) {
                pinnedBackend = null
                if (frameOpen) frameBackend else null
            }
            if (unfinished != null) {
                try {
                    unfinished.finishFrame()
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    failure.addSuppressed(cleanup)
                } finally {
                    synchronized(lock) {
                        frameOpen = false
                        frameBackend = null
                        frameLease = null
                        preparationStartedNanos = 0L
                    }
                }
            }
        }
    }

    fun prepare() {
        var lease: Lease? = null
        val backend: TerrainBackend
        synchronized(lock) {
            check(!closed) { "Terrain backend registry is closed" }
            check(!frameOpen) { "Terrain frame preparation started before the previous frame finished" }
            backend = pinnedBackend ?: acquire().also { lease = it }.backend
            frameLease = lease
            frameBackend = backend
            frameOpen = true
            frameSubmissions.clear()
            preparedFrames++
            preparationStartedNanos = System.nanoTime()
        }
        try {
            backend.prepare()
        } catch (failure: Throwable) {
            synchronized(lock) {
                frameOpen = false
                frameLease = null
                frameBackend = null
                preparationStartedNanos = 0L
            }
            lease?.close()
            throw failure
        }
    }

    fun finishPreparation() {
        try {
            currentFrameBackend().finishPreparation()
        } finally {
            val started = synchronized(lock) {
                check(preparationStartedNanos != 0L) { "Terrain preparation has no start timestamp" }
                preparationStartedNanos.also { preparationStartedNanos = 0L }
            }
            preparationTimings.add(System.nanoTime() - started)
        }
    }

    fun submit(view: RenderViewId, material: TerrainMaterialClass) {
        synchronized(lock) {
            check(frameOpen) { "Terrain submission requires an open prepared frame" }
            require(frameSubmissions.add(view to material)) {
                "Duplicate terrain submission for view=$view material=$material"
            }
            submittedBatches++
        }
        val started = System.nanoTime()
        try {
            currentFrameBackend().submit(view, material)
        } finally {
            submissionTimings.add(System.nanoTime() - started)
        }
    }

    fun finishFrame() {
        val (backend, lease) = synchronized(lock) {
            check(frameOpen) { "Terrain frame completion requires an open prepared frame" }
            checkNotNull(frameBackend) { "Terrain frame has no selected backend generation" } to frameLease
        }
        try {
            backend.finishFrame()
        } finally {
            synchronized(lock) {
                frameOpen = false
                frameLease = null
                frameBackend = null
            }
            lease?.close()
        }
    }

    fun stats(): RuntimeStats {
        val preparation = preparationTimings.snapshot()
        val submission = submissionTimings.snapshot()
        return synchronized(lock) {
            RuntimeStats(
                resources = store.stats(),
                preparedFrames = preparedFrames,
                submittedBatches = submittedBatches,
                preparationTimingSamples = preparation.samples,
                medianPreparationNanos = preparation.medianNanos,
                p95PreparationNanos = preparation.p95Nanos,
                submissionTimingSamples = submission.samples,
                medianSubmissionNanos = submission.medianNanos,
                p95SubmissionNanos = submission.p95Nanos,
                currentFrameSubmissions = frameSubmissions.toSet(),
            )
        }
    }

    private fun snapshot(descriptor: TerrainBackendDescriptor) =
        TerrainBackendDescriptor(
            owner = descriptor.owner,
            implementation = descriptor.implementation,
            materials = descriptor.materials.toSet(),
            vertexLayout = descriptor.vertexLayout,
            supportsAuxiliaryViews = descriptor.supportsAuxiliaryViews,
        )

    private fun currentFrameBackend(): TerrainBackend = synchronized(lock) {
        check(frameOpen) { "Terrain frame operation requires an open prepared frame" }
        checkNotNull(frameBackend) { "Terrain frame has no selected backend generation" }
    }

    override fun close() {
        val activeFrame: Lease?
        synchronized(lock) {
            if (closed) return
            closed = true
            frameOpen = false
            frameBackend = null
            pinnedBackend = null
            preparationStartedNanos = 0L
            activeFrame = frameLease
            frameLease = null
        }
        activeFrame?.close()
        store.close()
    }
}
