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

package de.bixilon.minosoft.gui.rendering.terrain.storage

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.runtime.storage.TerrainBatchCache
import de.bixilon.minosoft.terrain.runtime.storage.TerrainBatchCacheMetrics
import de.bixilon.minosoft.terrain.runtime.storage.TerrainDrawBatch
import de.bixilon.minosoft.terrain.runtime.storage.TerrainRegionStorage
import de.bixilon.minosoft.terrain.runtime.storage.TerrainViewKey

internal data class TerrainRegionFrameMetrics(
    val drawBatches: Int,
    val drawCommands: Int,
    val drawVertices: Long,
)

/** Shared ownership boundary for one frame of near or distant region draws. */
internal class TerrainRegionFrameSubmission(
    private val context: RenderContext,
    private val completion: () -> OpenGlTerrainSubmissionCompletion,
) {
    private val batchCache = TerrainBatchCache()
    private val submittedBatches = ArrayList<TerrainDrawBatch>()
    private var frameDrawBatches = 0
    private var frameDrawCommands = 0
    private var frameDrawVertices = 0L
    private var lastMetrics = TerrainRegionFrameMetrics(0, 0, 0L)

    val hasSubmittedBatches: Boolean get() = submittedBatches.isNotEmpty()

    fun beginFrame() {
        check(submittedBatches.isEmpty()) { "Previous terrain region frame was not finished" }
        frameDrawBatches = 0
        frameDrawCommands = 0
        frameDrawVertices = 0L
    }

    fun draw(
        device: OpenGlTerrainRegionDevice,
        storage: TerrainRegionStorage,
        material: TerrainSemanticMaterialId,
        view: TerrainViewKey,
        orderedPages: List<TerrainPageKey>,
        layoutGeneration: Long,
        materialGeneration: Long,
        beforeDraw: () -> Unit = {},
    ): Boolean {
        val batch = batchCache.batch(
            storage = storage,
            material = material,
            view = view,
            orderedPages = orderedPages,
            layoutGeneration = layoutGeneration,
            materialGeneration = materialGeneration,
        ) ?: return false
        try {
            beforeDraw()
            val deviceBatches = device.draw(batch)
            submittedBatches += batch
            frameDrawBatches = Math.addExact(frameDrawBatches, deviceBatches)
            frameDrawCommands = Math.addExact(frameDrawCommands, batch.commands.size)
            frameDrawVertices = Math.addExact(
                frameDrawVertices,
                batch.commands.sumOf { it.indexCount.toLong() },
            )
            return true
        } catch (failure: Throwable) {
            batch.close()
            throw failure
        }
    }

    fun finishFrame() {
        lastMetrics = TerrainRegionFrameMetrics(frameDrawBatches, frameDrawCommands, frameDrawVertices)
        if (submittedBatches.isEmpty()) return
        var failure: Throwable? = null
        try {
            val fenced = completion().fence(context.terrainSubmissions.next().serial)
            for (batch in submittedBatches) {
                try {
                    batch.submit(fenced)
                } catch (error: Throwable) {
                    failure = combine(failure, error)
                }
            }
        } catch (error: Throwable) {
            failure = combine(failure, error)
        }
        try {
            closeSubmittedBatches()
        } catch (error: Throwable) {
            failure = combine(failure, error)
        }
        if (failure != null) throw failure
    }

    fun metrics(): TerrainRegionFrameMetrics = lastMetrics

    fun batchMetrics(): TerrainBatchCacheMetrics = batchCache.metrics()

    fun clearCache() = batchCache.clear()

    fun abandon() {
        submittedBatches.clear()
        batchCache.clear()
        resetMetrics()
    }

    fun closeSubmittedBatches() {
        var failure: Throwable? = null
        try {
            for (batch in submittedBatches.asReversed()) {
                try {
                    batch.close()
                } catch (error: Throwable) {
                    failure = combine(failure, error)
                }
            }
        } finally {
            submittedBatches.clear()
        }
        if (failure != null) throw failure
    }

    fun resetMetrics() {
        frameDrawBatches = 0
        frameDrawCommands = 0
        frameDrawVertices = 0L
        lastMetrics = TerrainRegionFrameMetrics(0, 0, 0L)
    }

    private fun combine(primary: Throwable?, next: Throwable): Throwable {
        if (primary == null) return next
        primary.addSuppressed(next)
        return primary
    }
}
