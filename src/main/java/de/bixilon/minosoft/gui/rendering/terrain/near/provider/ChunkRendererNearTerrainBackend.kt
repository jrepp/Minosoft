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

package de.bixilon.minosoft.gui.rendering.terrain.near.provider

import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.toBuildCause
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackend
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainInvalidationReason
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.TerrainSectionSnapshot

/**
 * A near-terrain provider profile that delegates the current production
 * scheduling, meshing, upload, visibility, and submission behavior to the
 * canonical chunk renderer.
 */
class ChunkRendererNearTerrainBackend internal constructor(
    private val core: NearTerrainRenderCore,
    override val descriptor: TerrainBackendDescriptor,
) : TerrainBackend {
    constructor(chunks: ChunkRenderer, descriptor: TerrainBackendDescriptor) : this(
        ChunkRendererNearTerrainCore(chunks),
        descriptor,
    )

    private var closed = false

    override fun prepare() {
        check(!closed) { "Near terrain backend is closed" }
        core.prepare()
    }

    override fun finishPreparation() {
        check(!closed) { "Near terrain backend is closed" }
        core.finishPreparation()
    }

    override fun submit(view: RenderViewId, material: TerrainMaterialClass) {
        check(!closed) { "Near terrain backend is closed" }
        core.submit(view, material)
    }

    override fun finishFrame() {
        check(!closed) { "Near terrain backend is closed" }
        core.finishFrame()
    }

    override fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason) {
        check(!closed) { "Near terrain backend is closed" }
        core.invalidate(snapshot, reason)
    }

    override fun close() {
        closed = true
    }
}

internal interface NearTerrainRenderCore {
    fun prepare()
    fun finishPreparation()
    fun submit(view: RenderViewId, material: TerrainMaterialClass)
    fun finishFrame()
    fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason)
}

private class ChunkRendererNearTerrainCore(
    private val chunks: ChunkRenderer,
) : NearTerrainRenderCore {
    override fun prepare() = chunks.prepareTerrainCore()

    override fun finishPreparation() = chunks.finishTerrainPreparationCore()

    override fun submit(view: RenderViewId, material: TerrainMaterialClass) {
        chunks.submitTerrainCore(view, material)
    }

    override fun finishFrame() = chunks.finishTerrainFrameCore()

    override fun invalidate(snapshot: TerrainSectionSnapshot, reason: TerrainInvalidationReason) {
        chunks.invalidate(snapshot.position, reason.toBuildCause())
    }
}
