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

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.TransactionalGenerationStore
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import java.util.concurrent.atomic.AtomicBoolean

interface WorldShaderPipeline : AutoCloseable {
    val owner: RenderOwnerId
    val plan: ShaderPipelinePlan?

    fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader)
    fun renderView(view: RenderViewId, draw: () -> Unit) = draw()
    fun composite(fallback: FramebufferShader): FramebufferShader
    override fun close()
}

object BuiltInWorldShaderPipeline : WorldShaderPipeline {
    override val owner = RenderOwnerId("minosoft:built-in-shader")
    override val plan: ShaderPipelinePlan? = null

    override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = fallback.use()
    override fun composite(fallback: FramebufferShader): FramebufferShader = fallback
    override fun close() = Unit
}

class ShaderPipelineRegistry : AutoCloseable {
    private data class Generation(
        val pipeline: WorldShaderPipeline,
        val closeOnRetire: Boolean,
    )

    data class Selection(
        val generation: Long,
        val owner: RenderOwnerId,
        val packName: String?,
        val fingerprint: String?,
    )

    private val lock = Any()
    private val store = TransactionalGenerationStore(Generation(BuiltInWorldShaderPipeline, false)) { generation ->
        if (generation.closeOnRetire) generation.pipeline.close()
    }
    private var nextToken = 1L
    private var overrideToken: Long? = null
    private var closed = false

    fun replace(
        terrain: TerrainBackendDescriptor,
        prepare: () -> WorldShaderPipeline,
    ): AutoCloseable {
        val candidate = prepare()
        try {
            validate(candidate, terrain)
            val token: Long
            synchronized(lock) {
                check(!closed) { "Shader pipeline registry is closed" }
                token = nextToken++
                store.replace { Generation(candidate, true) }
                overrideToken = token
            }
            return Registration(token)
        } catch (failure: Throwable) {
            try {
                candidate.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun <T> withPipeline(action: (WorldShaderPipeline) -> T): T =
        store.acquire().use { lease -> action(lease.value.pipeline) }

    fun selection(): Selection = store.acquire().use { lease ->
        val pipeline = lease.value.pipeline
        Selection(
            generation = lease.generation,
            owner = pipeline.owner,
            packName = pipeline.plan?.packName,
            fingerprint = pipeline.plan?.fingerprint,
        )
    }

    fun plan(): ShaderPipelinePlan? = store.acquire().use { it.value.pipeline.plan }

    fun stats(): TransactionalGenerationStore.Stats = store.stats()

    private fun validate(pipeline: WorldShaderPipeline, terrain: TerrainBackendDescriptor) {
        require(pipeline !== BuiltInWorldShaderPipeline) { "Built-in shader pipeline can not be installed as an override" }
        val plan = requireNotNull(pipeline.plan) { "Shader override ${pipeline.owner} must expose its immutable plan" }
        require(plan.owner == pipeline.owner) { "Shader plan owner ${plan.owner} does not match ${pipeline.owner}" }
        val available = terrain.vertexLayout.attributes.mapTo(mutableSetOf()) { it.semantic }
        require(available.containsAll(plan.requiredTerrainSemantics)) {
            "Shader pack ${plan.packName} requires unsupported terrain semantics: ${plan.requiredTerrainSemantics - available}"
        }
        require(IrisShaderPackPlanner.SHADOW_VIEW !in plan.views || terrain.supportsAuxiliaryViews) {
            "Shader pack ${plan.packName} requires a shadow view unsupported by terrain backend ${terrain.owner}"
        }
    }

    private fun remove(token: Long) {
        synchronized(lock) {
            if (closed || overrideToken != token) return
            store.replace { Generation(BuiltInWorldShaderPipeline, false) }
            overrideToken = null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            overrideToken = null
            store.close()
        }
    }

    private inner class Registration(
        private val token: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) remove(token)
        }
    }
}
