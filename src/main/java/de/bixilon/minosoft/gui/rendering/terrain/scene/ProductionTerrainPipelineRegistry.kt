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
import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelineRegistry
import de.bixilon.minosoft.gui.rendering.shader.pipeline.WorldShaderPipeline
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackend
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.terrain.distant.DistantTerrainInterop
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.interop.DistantTerrainProvider
import de.bixilon.minosoft.terrain.model.interop.NearTerrainProvider
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.interop.TerrainLightingSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainMaterialClass
import de.bixilon.minosoft.terrain.model.interop.TerrainProviderCapability
import de.bixilon.minosoft.terrain.model.interop.TerrainTintSemantics
import java.util.concurrent.atomic.AtomicBoolean

data class ProductionTerrainPipelineSelection(
    val generation: Long,
    val identity: TerrainPipelineSelectionIdentity,
    val nearDescriptor: TerrainInteropDescriptor,
    val distantDescriptor: TerrainInteropDescriptor?,
)

/** Owns the exact near, distant, material, shader, layout, and graph selection used by one frame. */
internal class ProductionTerrainPipelineRegistry(
    private val context: RenderContext,
    private val renderers: RendererManager,
    private val graphGeneration: () -> Long,
) : AutoCloseable {
    private var registry: TerrainPipelineRegistry? = null
    private var selectedIdentity: TerrainPipelineSelectionIdentity? = null
    private var closed = false

    fun <T> withFrame(prepare: () -> Unit, action: () -> T): T {
        val registry = ensureCurrent()
        return registry.acquire().use { lease ->
            val near = lease.value.nearProvider as LeasedNearProvider
            val shader = lease.value.shaderPipeline as LeasedShaderPipeline
            near.backendRegistry.withPinnedBackend(near.backend) {
                context.shaderPipeline.withFramePipeline(context, shader.pipeline, prepare) {
                    action()
                }
            }
        }
    }

    fun selection(): ProductionTerrainPipelineSelection {
        val registry = ensureCurrent()
        return registry.acquire().use { lease ->
            val value = lease.value
            ProductionTerrainPipelineSelection(
                generation = value.generation,
                identity = value.selectionIdentity(),
                nearDescriptor = value.nearProvider.descriptor,
                distantDescriptor = value.distantProvider?.descriptor,
            )
        }
    }

    override fun close() {
        val owned = synchronized(this) {
            if (closed) return
            closed = true
            selectedIdentity = null
            registry.also { registry = null }
        }
        owned?.close()
    }

    @Synchronized
    private fun ensureCurrent(): TerrainPipelineRegistry {
        check(!closed) { "Production terrain pipeline registry is closed" }
        val chunks = requireNotNull(renderers.filterIsInstance<ChunkRenderer>().singleOrNull()) {
            "Production terrain pipeline requires the near terrain renderer"
        }
        val identity = currentIdentity(chunks)
        val current = registry
        if (current != null && identity == selectedIdentity) return current

        if (current == null) {
            val candidate = candidate(chunks)
            val initialIdentity = candidate.selectionIdentity()
            return TerrainPipelineRegistry(candidate).also {
                registry = it
                selectedIdentity = initialIdentity
            }
        }

        val generation = current.publish { candidate(chunks) }
        check(generation > 0L) { "Replacement terrain pipeline generation did not advance" }
        selectedIdentity = current.acquire().use { it.value.selectionIdentity() }
        return current
    }

    private fun currentIdentity(chunks: ChunkRenderer): TerrainPipelineSelectionIdentity {
        val near = chunks.terrain.selection()
        val shader = context.shaderPipeline.selection()
        val distant = renderers.filterIsInstance<DistantTerrainProvider>().singleOrNull()
        return TerrainPipelineSelectionIdentity(
            mode = TerrainRuntimeMode.UNIFIED,
            nearProviderId = near.owner.value,
            nearProviderGeneration = near.generation,
            distantProviderId = distant?.descriptor?.providerId,
            distantProviderGeneration = distant?.generation,
            nearLayoutGeneration = near.generation,
            distantLayoutGeneration = distant?.let { DistantTerrainInterop.PHYSICAL_LAYOUT_GENERATION },
            materialGeneration = shader.generation,
            shaderPipelineGeneration = shader.generation,
            renderGraphGeneration = graphGeneration(),
        )
    }

    private fun candidate(chunks: ChunkRenderer): TerrainPipelineCandidate {
        val near = LeasedNearProvider(chunks, chunks.terrain.acquire())
        val shader = LeasedShaderPipeline(context.shaderPipeline.acquire())
        val distant = renderers.filterIsInstance<DistantTerrainProvider>().singleOrNull()?.let(::StableDistantProvider)
        val material = StableMaterialTable(shader.generation)
        var creationStarted = false
        try {
            val providers = listOfNotNull(near.descriptor, distant?.descriptor)
            val views = providers.map(TerrainInteropDescriptor::supportedViews).reduce(Set<String>::intersect)
            val passes = providers.asSequence()
                .flatMap { it.materials.asSequence() }
                .map { "minosoft:terrain/${it.name.lowercase().replace('_', '-')}" }
                .toSortedSet()

            creationStarted = true
            return TerrainPipelineCandidate.create(
                mode = TerrainRuntimeMode.UNIFIED,
                nearProvider = near,
                distantProvider = distant,
                materialTable = material,
                shaderPipeline = shader,
                nearLayout = TerrainPhysicalLayout(
                    id = near.backendDescriptor.vertexLayout.id.value,
                    generation = near.generation,
                    domain = TerrainDomain.NEAR,
                ),
                distantLayout = distant?.let {
                    TerrainPhysicalLayout(
                        id = DistantTerrainInterop.PHYSICAL_LAYOUT_ID,
                        generation = DistantTerrainInterop.PHYSICAL_LAYOUT_GENERATION,
                        domain = TerrainDomain.DISTANT,
                    )
                },
                declaredViews = views,
                declaredMaterialPasses = passes,
                renderGraphGeneration = graphGeneration(),
            )
        } catch (failure: Throwable) {
            if (!creationStarted) {
                for (resource in listOfNotNull<AutoCloseable>(material, distant, shader, near)) {
                    try {
                        resource.close()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
            }
            throw failure
        }
    }

    private class LeasedNearProvider(
        val chunks: ChunkRenderer,
        private val lease: de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendRegistry.Lease,
    ) : NearTerrainProvider {
        private val closed = AtomicBoolean()
        val backendRegistry get() = chunks.terrain
        val backend: TerrainBackend get() = lease.backend
        val backendDescriptor: TerrainBackendDescriptor get() = lease.descriptor
        override val generation: Long get() = lease.generation
        override val descriptor = lease.descriptor.toInteropDescriptor()

        override fun close() {
            if (closed.compareAndSet(false, true)) lease.close()
        }
    }

    private class StableDistantProvider(
        private val delegate: DistantTerrainProvider,
    ) : DistantTerrainProvider {
        override val generation: Long get() = delegate.generation
        override val descriptor: TerrainInteropDescriptor get() = delegate.descriptor
        override fun close() = Unit
    }

    private class StableMaterialTable(
        override val generation: Long,
    ) : TerrainMaterialTableGeneration {
        override fun close() = Unit
    }

    private class LeasedShaderPipeline(
        private val lease: ShaderPipelineRegistry.Lease,
    ) : TerrainShaderPipelineGeneration {
        private val closed = AtomicBoolean()
        override val generation: Long get() = lease.generation
        val pipeline: WorldShaderPipeline get() = lease.pipeline

        override fun close() {
            if (closed.compareAndSet(false, true)) lease.close()
        }
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
        capabilities = if (supportsAuxiliaryViews) {
            setOf(TerrainProviderCapability.AUXILIARY_VIEWS)
        } else {
            emptySet()
        },
        materials = materials.mapTo(linkedSetOf()) { TerrainMaterialClass.valueOf(it.name) },
        semanticVertexLayoutId = vertexLayout.id.value,
        physicalLayoutIds = setOf(vertexLayout.id.value),
        supportedViews = views,
        uploadCapabilities = emptySet(),
        shaderInputs = vertexLayout.attributes.mapTo(sortedSetOf()) { it.semantic.name.lowercase() },
        lightingSemantics = TerrainLightingSemantics.BLOCK_AND_SKY,
        tintSemantics = TerrainTintSemantics.RESOLVED_COLOR,
    )
}

private fun TerrainPipelineGeneration.selectionIdentity() = TerrainPipelineSelectionIdentity(
    mode = mode,
    nearProviderId = nearProvider.descriptor.providerId,
    nearProviderGeneration = nearProvider.generation,
    distantProviderId = distantProvider?.descriptor?.providerId,
    distantProviderGeneration = distantProvider?.generation,
    nearLayoutGeneration = nearLayout.generation,
    distantLayoutGeneration = distantLayout?.generation,
    materialGeneration = materialTable.generation,
    shaderPipelineGeneration = shaderPipeline.generation,
    renderGraphGeneration = renderGraphGeneration,
)

private fun TerrainPipelineCandidate.selectionIdentity() = TerrainPipelineSelectionIdentity(
    mode = mode,
    nearProviderId = nearProvider.descriptor.providerId,
    nearProviderGeneration = nearProvider.generation,
    distantProviderId = distantProvider?.descriptor?.providerId,
    distantProviderGeneration = distantProvider?.generation,
    nearLayoutGeneration = nearLayout.generation,
    distantLayoutGeneration = distantLayout?.generation,
    materialGeneration = materialTable.generation,
    shaderPipelineGeneration = shaderPipeline.generation,
    renderGraphGeneration = renderGraphGeneration,
)
