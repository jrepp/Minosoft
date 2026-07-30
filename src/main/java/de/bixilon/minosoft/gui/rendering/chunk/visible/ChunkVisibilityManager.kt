/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.chunk.visible

import de.bixilon.kutil.enums.inline.enums.IntInlineEnumSet
import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.minosoft.data.world.World
import de.bixilon.minosoft.data.world.chunk.ChunkSection
import de.bixilon.minosoft.data.world.chunk.ChunkUtil.isInViewDistance
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.camera.frustum.FrustumResults
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.chunk.mesh.ChunkMeshes
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainDirectionalVisibility
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainProductionPhase
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainVisibilityNode
import de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainVisibilityTraversal
import kotlin.math.abs

class ChunkVisibilityManager(
    val renderer: ChunkRenderer,
) {
    private val visibility = renderer.context.camera.visibility

    var eyePosition = BlockPosition()
        private set
    var sectionPosition = SectionPosition()
        private set

    private var reasons: IntInlineEnumSet<VisibilityGraphInvalidReason> = IntInlineEnumSet()
    private var viewDistance = renderer.context.session.world.view.viewDistance

    init {
        val state = renderer.context.camera.fog.state
        var end = 0.0f
        state::revision.observe(this) {
            if (state.end < end) {
                reasons += VisibilityGraphInvalidReason.FOG
            }

            end = state.end
        }
    }


    var meshes = VisibleMeshes(this)
        private set


    fun isInViewDistance(position: ChunkPosition): Boolean {
        return position.isInViewDistance(viewDistance, sectionPosition.chunkPosition)
    }

    fun isInViewDistance(position: SectionPosition): Boolean {
        if (abs(position.y - this.sectionPosition.y) > World.MAX_VERTICAL_VIEW_DISTANCE) return false
        return isInViewDistance(position.chunkPosition)
    }

    operator fun contains(position: ChunkPosition) = visibility.isChunkVisible(position)
    operator fun contains(position: SectionPosition) = visibility.isSectionVisible(position, full = false) >= FrustumResults.PARTLY_INSIDE
    operator fun contains(section: ChunkSection) = visibility.isSectionVisible(section)

    fun contains(position: SectionPosition, min: InSectionPosition, max: InSectionPosition) = visibility.isSectionVisible(position, min, max, true)

    private fun collectVisibleMeshes(force: Boolean) {
        val telemetry = renderer.terrainPerformance
        val started = telemetry.begin(TerrainProductionPhase.VISIBILITY)
        try {
            while (true) {
                val snapshot = renderer.loaded.visibilitySnapshot()
                val meshes = VisibleMeshes(this, eyePosition, this.meshes)

                val nodes = snapshot.candidates.associate { (loaded, _) ->
                    loaded.position to TerrainVisibilityNode(loaded.position, loaded.connectivity)
                }.toMutableMap()
                if (nodes.isNotEmpty()) {
                    val xRange = minOf(sectionPosition.x, nodes.keys.minOf { it.x })..
                        maxOf(sectionPosition.x, nodes.keys.maxOf { it.x })
                    val yRange = minOf(sectionPosition.y, nodes.keys.minOf { it.y })..
                        maxOf(sectionPosition.y, nodes.keys.maxOf { it.y })
                    val zRange = minOf(sectionPosition.z, nodes.keys.minOf { it.z })..
                        maxOf(sectionPosition.z, nodes.keys.maxOf { it.z })

                    // Empty/unbuilt sections are passable, not missing graph vertices.
                    // Treating them as absent would incorrectly hide everything beyond
                    // the first air gap. Unknown geometry is ALL as the conservative
                    // fallback until its measured connectivity publishes.
                    for (y in yRange) {
                        for (z in zRange) {
                            for (x in xRange) {
                                val position = SectionPosition(x, y, z)
                                nodes.putIfAbsent(
                                    position,
                                    TerrainVisibilityNode(position, TerrainDirectionalVisibility.ALL),
                                )
                            }
                        }
                    }
                }
                val visible = TerrainVisibilityTraversal.traverse(sectionPosition, nodes).toHashSet()

                var visibleSections = 0
                for ((loaded, result) in snapshot.candidates) {
                    if (loaded.position !in visible) continue
                    if (force) {
                        loaded.resetOcclusion()
                    }
                    meshes.unsafeAdd(loaded, result)
                    visibleSections++
                }

                if (!renderer.loaded.publishVisibility(snapshot, meshes)) {
                    continue
                }
                meshes.sort()
                telemetry.visible(visibleSections)
                return
            }
        } finally {
            telemetry.finish(TerrainProductionPhase.VISIBILITY, started)
        }
    }

    internal fun publish(meshes: VisibleMeshes) {
        this.meshes = meshes
    }

    private fun onVisibilityChange() {
        val eyePosition = renderer.context.session.camera.entity.physics.positionInfo.eyePosition

        if (this.eyePosition != eyePosition) {
            this.eyePosition = eyePosition

            val sectionPosition = eyePosition.sectionPosition
            if (this.sectionPosition != sectionPosition) {
                this.sectionPosition = sectionPosition
                renderer.meshingQueue.tasks.interruptIf(true) { !isInViewDistance(it) }
                renderer.meshingQueue.removeIf(true) { !isInViewDistance(it) }
                renderer.loadingQueue.removeIf(true) { !isInViewDistance(it) }
                renderer.loaded.update()

                renderer.culledQueue.enqueueViewDistance()
            }

            // TODO: remove from meshing queue
            renderer.meshingQueue.sort()
            renderer.loadingQueue.sort()
        }

        renderer.culledQueue.enqueue()
    }


    fun invalidate(reason: VisibilityGraphInvalidReason) {
        this.reasons += reason
    }

    fun update() {
        updateViewDistance() // TODO: delay that for 100ms to not cause rapid loading/unloading

        val reasons = reasons
        if (reasons.size == 0) return

        if (VisibilityGraphInvalidReason.VISIBILITY_GRAPH in reasons) {
            onVisibilityChange()
        }


        // TODO: only recalculate if partly visible, fog/world change, do not if frustum only moved

        var force = false

        if (VisibilityGraphInvalidReason.FOG in reasons || VisibilityGraphInvalidReason.MESH_UPDATE in reasons) { // TODO: only set mesh update if shape changed
            force = true
        }


        collectVisibleMeshes(force)

        this.reasons = IntInlineEnumSet()
    }

    private fun updateViewDistance() {
        val view = renderer.context.session.world.view

        val current = this.viewDistance
        val next = view.viewDistance
        this.viewDistance = next

        when {
            next > current -> renderer.culledQueue.enqueueViewDistance()
            next < current -> {
                renderer.meshingQueue.removeIf(true) { !isInViewDistance(it) }
                renderer.meshingQueue.tasks.interruptIf(true) { !isInViewDistance(it) }
                renderer.loadingQueue.removeIf(true) { !isInViewDistance(it) }
                renderer.loaded.update()
            }
        }
    }
}
