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

import de.bixilon.minosoft.gui.rendering.graph.resource.TransactionalGenerationStore
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.interop.DistantTerrainProvider
import de.bixilon.minosoft.terrain.model.interop.NearTerrainProvider
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class TerrainRuntimeMode {
    LEGACY,
    UNIFIED_COMPARE,
    UNIFIED,
}

interface TerrainMaterialTableGeneration : AutoCloseable {
    val generation: Long
}

interface TerrainShaderPipelineGeneration : AutoCloseable {
    val generation: Long
}

data class TerrainPhysicalLayout(
    val id: String,
    val generation: Long,
    val domain: TerrainDomain,
) {
    init {
        require(id.isNotBlank()) { "Terrain physical layout ID must not be blank" }
        require(generation >= 0L) { "Terrain physical layout generation must not be negative" }
    }
}

class TerrainPipelineCandidate private constructor(
    val mode: TerrainRuntimeMode,
    val nearProvider: NearTerrainProvider,
    val distantProvider: DistantTerrainProvider?,
    val materialTable: TerrainMaterialTableGeneration,
    val shaderPipeline: TerrainShaderPipelineGeneration,
    val nearLayout: TerrainPhysicalLayout,
    val distantLayout: TerrainPhysicalLayout?,
    declaredViews: Set<String>,
    declaredMaterialPasses: Set<String>,
    val renderGraphGeneration: Long,
) : AutoCloseable {
    val declaredViews: Set<String> = java.util.Set.copyOf(declaredViews)
    val declaredMaterialPasses: Set<String> = java.util.Set.copyOf(declaredMaterialPasses)
    private val closed = AtomicBoolean()

    init {
        require(nearProvider.generation >= 0L) { "Near terrain provider generation must not be negative" }
        require(distantProvider == null || distantProvider.generation >= 0L) {
            "Distant terrain provider generation must not be negative"
        }
        require(materialTable.generation >= 0L) { "Terrain material-table generation must not be negative" }
        require(shaderPipeline.generation >= 0L) { "Terrain shader-pipeline generation must not be negative" }
        require(TerrainDomain.NEAR in nearProvider.descriptor.domains) {
            "Near terrain provider must declare the near domain"
        }
        require(distantProvider == null || TerrainDomain.DISTANT in distantProvider.descriptor.domains) {
            "Distant terrain provider must declare the distant domain"
        }
        require(nearLayout.domain == TerrainDomain.NEAR) { "Near terrain layout must declare the near domain" }
        require(nearLayout.id in nearProvider.descriptor.physicalLayoutIds) {
            "Near terrain provider does not support physical layout ${nearLayout.id}"
        }
        require((distantProvider == null) == (distantLayout == null)) {
            "Distant terrain provider and physical layout must be selected together"
        }
        require(
            distantProvider == null ||
                distantLayout?.domain == TerrainDomain.DISTANT &&
                distantLayout.id in distantProvider.descriptor.physicalLayoutIds,
        ) {
            "Distant terrain provider does not support the selected distant layout"
        }
        require(distantLayout == null || nearLayout.id != distantLayout.id) {
            "Near and distant terrain must negotiate distinct physical layout IDs"
        }
        require(this.declaredViews.isNotEmpty()) { "Terrain pipeline must declare at least one view" }
        require(this.declaredViews.none(String::isBlank)) { "Terrain pipeline view IDs must not be blank" }
        require(nearProvider.descriptor.supportedViews.containsAll(this.declaredViews)) {
            "Near terrain provider does not support every declared pipeline view"
        }
        require(distantProvider == null || distantProvider.descriptor.supportedViews.containsAll(this.declaredViews)) {
            "Distant terrain provider does not support every declared pipeline view"
        }
        require(this.declaredMaterialPasses.isNotEmpty()) {
            "Terrain pipeline must declare at least one material pass"
        }
        require(this.declaredMaterialPasses.none(String::isBlank)) {
            "Terrain pipeline material pass IDs must not be blank"
        }
        require(renderGraphGeneration >= 0L) { "Render graph generation must not be negative" }
        requireDistinctOwners()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeReverse(ownedResources())
    }

    private fun requireDistinctOwners() {
        val identities = IdentityHashMap<AutoCloseable, Unit>()
        for (resource in ownedResources()) {
            require(identities.put(resource, Unit) == null) {
                "Terrain pipeline resources must have distinct ownership"
            }
        }
    }

    private fun ownedResources(): List<AutoCloseable> =
        listOfNotNull(nearProvider, distantProvider, materialTable, shaderPipeline)

    companion object {
        fun create(
            mode: TerrainRuntimeMode,
            nearProvider: NearTerrainProvider,
            distantProvider: DistantTerrainProvider?,
            materialTable: TerrainMaterialTableGeneration,
            shaderPipeline: TerrainShaderPipelineGeneration,
            nearLayout: TerrainPhysicalLayout,
            distantLayout: TerrainPhysicalLayout?,
            declaredViews: Set<String>,
            declaredMaterialPasses: Set<String>,
            renderGraphGeneration: Long,
        ): TerrainPipelineCandidate {
            val resources = distinctByIdentity(
                listOfNotNull(nearProvider, distantProvider, materialTable, shaderPipeline),
            )
            return try {
                TerrainPipelineCandidate(
                    mode,
                    nearProvider,
                    distantProvider,
                    materialTable,
                    shaderPipeline,
                    nearLayout,
                    distantLayout,
                    declaredViews,
                    declaredMaterialPasses,
                    renderGraphGeneration,
                )
            } catch (failure: Throwable) {
                closeReverse(resources, failure)
                throw failure
            }
        }
    }
}

class TerrainPipelineGeneration internal constructor(
    val generation: Long,
    val candidate: TerrainPipelineCandidate,
) {
    val mode: TerrainRuntimeMode get() = candidate.mode
    val nearProvider: NearTerrainProvider get() = candidate.nearProvider
    val distantProvider: DistantTerrainProvider? get() = candidate.distantProvider
    val materialTable: TerrainMaterialTableGeneration get() = candidate.materialTable
    val shaderPipeline: TerrainShaderPipelineGeneration get() = candidate.shaderPipeline
    val nearLayout: TerrainPhysicalLayout get() = candidate.nearLayout
    val distantLayout: TerrainPhysicalLayout? get() = candidate.distantLayout
    val declaredViews: Set<String> get() = candidate.declaredViews
    val declaredMaterialPasses: Set<String> get() = candidate.declaredMaterialPasses
    val renderGraphGeneration: Long get() = candidate.renderGraphGeneration
}

class TerrainPipelineRegistry(
    initial: TerrainPipelineCandidate,
) : AutoCloseable {
    class Lease internal constructor(
        private val delegate: TransactionalGenerationStore.Lease<TerrainPipelineGeneration>,
        private val drainRetired: () -> Unit,
    ) : AutoCloseable {
        val value: TerrainPipelineGeneration get() = delegate.value
        override fun close() {
            delegate.close()
            drainRetired()
        }
    }

    private val lock = Any()
    private val retired = ConcurrentLinkedQueue<TerrainPipelineCandidate>()
    private val store = TransactionalGenerationStore(
        TerrainPipelineGeneration(0L, initial),
    ) { retired += it.candidate }
    private var nextGeneration = 1L
    private var closed = false

    fun acquire(): Lease = Lease(store.acquire(), ::drainRetired)

    fun publish(construct: () -> TerrainPipelineCandidate): Long {
        val candidate = construct()
        var published = false
        val generation = try {
            val generation = synchronized(lock) {
                if (closed) return@synchronized null
                val generation = nextGeneration
                nextGeneration = Math.incrementExact(nextGeneration)
                store.replace { TerrainPipelineGeneration(generation, candidate) }
                published = true
                generation
            }
            generation ?: throw IllegalStateException("Terrain pipeline registry is closed")
        } catch (failure: Throwable) {
            if (!published) closeReverse(listOf(candidate), failure)
            try {
                drainRetired()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        drainRetired()
        return generation
    }

    fun stats(): TransactionalGenerationStore.Stats = store.stats()

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            store.close()
        }
        drainRetired()
    }

    private fun drainRetired() {
        var failure: Throwable? = null
        while (true) {
            val candidate = retired.poll() ?: break
            try {
                candidate.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        if (failure != null) throw failure
    }
}

private fun closeReverse(resources: List<AutoCloseable>, primary: Throwable? = null) {
    var failure = primary
    for (resource in resources.asReversed()) {
        try {
            resource.close()
        } catch (cleanup: Throwable) {
            if (failure == null) {
                failure = cleanup
            } else {
                failure.addSuppressed(cleanup)
            }
        }
    }
    if (primary == null && failure != null) throw failure
}

private fun distinctByIdentity(resources: List<AutoCloseable>): List<AutoCloseable> {
    val identities = IdentityHashMap<AutoCloseable, Unit>()
    return resources.filter { identities.put(it, Unit) == null }
}
