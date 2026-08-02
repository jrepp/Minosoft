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

package de.bixilon.minosoft.gui.rendering.terrain.scene

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererManager
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.terrain.distant.DistantTerrainInterop
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.interop.DistantTerrainProvider
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.interop.TerrainLightingSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import de.bixilon.minosoft.terrain.model.interop.TerrainTintSemantics

data class ProductionTerrainPipelineSelection(
    val generation: Long,
    val identity: TerrainPipelineSelectionIdentity,
    val nearDescriptor: TerrainInteropDescriptor,
    val distantDescriptor: TerrainInteropDescriptor?,
)

/** Pins the existing backend and shader leases directly for one frame. */
internal class ProductionTerrainPipelineRegistry(
    private val context: RenderContext,
    private val renderers: RendererManager,
    private val graphGeneration: () -> Long,
) : AutoCloseable {
    private var selectedIdentity: TerrainPipelineSelectionIdentity? = null
    private var generation = -1L
    private var closed = false

    fun <T> withFrame(prepare: () -> Unit, action: () -> T): T {
        val chunks = chunks()
        return chunks.terrain.acquire().use { near ->
            context.shaderPipeline.acquire().use { shader ->
                record(identity(near.generation, near.descriptor, shader.generation))
                chunks.terrain.withPinnedBackend(near.backend) {
                    context.shaderPipeline.withFramePipeline(context, shader.pipeline, prepare) { action() }
                }
            }
        }
    }

    fun selection(): ProductionTerrainPipelineSelection {
        val chunks = chunks()
        return chunks.terrain.acquire().use { near ->
            context.shaderPipeline.acquire().use { shader ->
                val distant = distant()
                val identity = identity(near.generation, near.descriptor, shader.generation, distant)
                ProductionTerrainPipelineSelection(
                    generation = record(identity),
                    identity = identity,
                    nearDescriptor = near.descriptor.toInteropDescriptor(),
                    distantDescriptor = distant?.descriptor,
                )
            }
        }
    }

    @Synchronized
    private fun record(identity: TerrainPipelineSelectionIdentity): Long {
        check(!closed) { "Production terrain pipeline selection is closed" }
        if (identity != selectedIdentity) {
            generation = Math.incrementExact(generation)
            selectedIdentity = identity
        }
        return generation
    }

    private fun identity(
        nearGeneration: Long,
        near: TerrainBackendDescriptor,
        shaderGeneration: Long,
        distant: DistantTerrainProvider? = distant(),
    ) = TerrainPipelineSelectionIdentity(
        mode = TerrainRuntimeMode.UNIFIED,
        nearProviderId = near.owner.value,
        nearProviderGeneration = nearGeneration,
        distantProviderId = distant?.descriptor?.providerId,
        distantProviderGeneration = distant?.generation,
        nearLayoutGeneration = nearGeneration,
        distantLayoutGeneration = distant?.let { DistantTerrainInterop.PHYSICAL_LAYOUT_GENERATION },
        materialGeneration = shaderGeneration,
        shaderPipelineGeneration = shaderGeneration,
        renderGraphGeneration = graphGeneration(),
    )

    private fun chunks(): ChunkRenderer {
        synchronized(this) { check(!closed) { "Production terrain pipeline selection is closed" } }
        var selected: ChunkRenderer? = null
        for (renderer in renderers) {
            if (renderer !is ChunkRenderer) continue
            check(selected == null) { "Production terrain pipeline has multiple near terrain renderers" }
            selected = renderer
        }
        return requireNotNull(selected) {
            "Production terrain pipeline requires the near terrain renderer"
        }
    }

    private fun distant(): DistantTerrainProvider? {
        var selected: DistantTerrainProvider? = null
        for (renderer in renderers) {
            if (renderer !is DistantTerrainProvider) continue
            check(selected == null) { "Production terrain pipeline has multiple distant terrain renderers" }
            selected = renderer
        }
        return selected
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        selectedIdentity = null
    }
}

private fun TerrainBackendDescriptor.toInteropDescriptor(): TerrainInteropDescriptor {
    val views = if (supportsAuxiliaryViews) {
        setOf("minosoft:main", "minosoft:shadow")
    } else {
        setOf("minosoft:main")
    }
    return TerrainInteropDescriptor(
        providerId = owner.value,
        domains = setOf(TerrainDomain.NEAR),
        capabilities = if (supportsAuxiliaryViews) setOf(TerrainProviderCapability.AUXILIARY_VIEWS) else emptySet(),
        materials = materials,
        semanticVertexLayoutId = vertexLayout.id.value,
        physicalLayoutIds = setOf(vertexLayout.id.value),
        supportedViews = views,
        uploadCapabilities = emptySet(),
        shaderInputs = vertexLayout.attributes.mapTo(sortedSetOf()) { it.semantic.name.lowercase() },
        lightingSemantics = TerrainLightingSemantics.BLOCK_AND_SKY,
        tintSemantics = TerrainTintSemantics.RESOLVED_COLOR,
    )
}
