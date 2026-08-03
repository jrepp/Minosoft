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
import de.bixilon.minosoft.gui.rendering.graph.resource.TransactionalOverrideStore
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.ShaderPipelineScope
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.terrain.TerrainBackendDescriptor
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass

data class WorldShaderPipelineDiagnostics(
    val selectedProfile: String? = null,
    val compiledScenePrograms: Int = 0,
    val compiledShadowScenePrograms: Int = 0,
    val compiledSceneRoutes: Set<String> = emptySet(),
    val compiledShadowSceneRoutes: Set<String> = emptySet(),
    val shadowTerrainPrograms: Map<String, String> = emptyMap(),
    val logicalShaderBuffers: Int = 0,
    val physicalShaderTextures: Int = 0,
    val customShaderTextures: Map<String, String> = emptyMap(),
    val textureArraySizes: Map<Int, String> = emptyMap(),
    val textureArrayUniformUploads: Long = 0,
    val shaderBuffers: Map<String, String> = emptyMap(),
    val customShaderResources: Map<String, String> = emptyMap(),
    val programStages: Map<String, String> = emptyMap(),
    val programOutputs: Map<String, String> = emptyMap(),
    val programFlips: Map<String, String> = emptyMap(),
    val programSamplers: Map<String, String> = emptyMap(),
    val programShadowComparisonSamplers: Map<String, String> = emptyMap(),
    val programAlphaTests: Map<String, String> = emptyMap(),
    val programBlendOverrides: Map<String, String> = emptyMap(),
    val appliedBlendOverrides: Map<String, Long> = emptyMap(),
    val selectedTerrainBinds: Map<String, Long> = emptyMap(),
    val selectedSceneBinds: Map<String, Long> = emptyMap(),
    // Retains the requested host semantic and ABI beside the shader-pack fallback root.
    val selectedSceneContracts: Map<String, Long> = emptyMap(),
    // Counts actual OpenGL submissions, not intermediate/re-entrant shader binds.
    val submittedSceneDraws: Map<String, Long> = emptyMap(),
    val submittedSceneVertices: Map<String, Long> = emptyMap(),
    // Main-view ABI coverage derived from actual submissions and compiled routes.
    val submittedMainSceneVertexAbis: Set<String> = emptySet(),
    val submittedMainSceneStateAbis: Set<String> = emptySet(),
    val unsubmittedCompiledSceneVertexAbis: Set<String> = emptySet(),
    val unsubmittedCompiledSceneStateAbis: Set<String> = emptySet(),
    val rejectedSceneBinds: Map<String, Long> = emptyMap(),
    val fallbackSceneBinds: Map<String, Long> = emptyMap(),
    val frameState: Long? = null,
    val frameInputValues: Map<String, Float> = emptyMap(),
    val shadowCulling: Map<String, String> = emptyMap(),
    val frameUniformUploads: Long = 0,
    val drawUniformUploads: Long = 0,
    val diagnosticTraceSampleRate: Int = 1,
    // High-cardinality trace maps are sampled; aggregate upload counters above remain exact.
    val drawStateBinds: Map<String, Long> = emptyMap(),
    val entityColorBinds: Map<String, Long> = emptyMap(),
    val renderStageBinds: Map<String, Long> = emptyMap(),
    val depthSnapshots: Map<String, Long> = emptyMap(),
    val fullscreenProgramExecutions: Map<String, Long> = emptyMap(),
    val activePassCutoff: String? = null,
)

enum class ShaderDepthSnapshot {
    DISTANT_BEFORE_TRANSLUCENT,
    BEFORE_TRANSLUCENT,
    BEFORE_HAND,
    SHADOW_BEFORE_TRANSLUCENT,
}

interface WorldShaderPipeline : AutoCloseable {
    val owner: RenderOwnerId
    val plan: ShaderPipelinePlan?

    fun beginFrame(state: IrisFrameState) = Unit
    fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader)
    fun bindScene(
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
        fallback: Shader,
    ): Shader = fallback
    fun sceneUniforms(fallback: Shader, selected: Shader): Set<String>? = null
    fun syncSceneState(fallback: Shader, selected: Shader) {
        fallback.syncUniformsTo(selected.native, sceneUniforms(fallback, selected))
    }
    fun bindFrameState(
        shader: Shader,
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
    ) = Unit
    fun bindDrawState(shader: Shader, state: IrisDrawState) = Unit

    fun recordUniformUpload(fallback: Shader, target: NativeShader, revision: Long) = Unit
    fun recordSceneDraw(
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
        fallback: Shader,
        selected: Shader,
        vertices: Int,
    ) = Unit
    fun restoreHostState() = Unit
    fun bindViewTarget(view: RenderViewId, fallback: () -> Unit) = fallback()
    fun snapshotDepth(snapshot: ShaderDepthSnapshot) = Unit
    fun executePrograms(phase: ShaderProgramPhase, drawFullscreen: () -> Unit) = Unit
    fun beginView(view: RenderViewId) = Unit
    fun endView(view: RenderViewId) = Unit
    fun renderView(view: RenderViewId, draw: () -> Unit) {
        beginView(view)
        try {
            draw()
        } finally {
            endView(view)
        }
    }
    fun composite(fallback: FramebufferShader): FramebufferShader
    fun diagnostics(): WorldShaderPipelineDiagnostics = WorldShaderPipelineDiagnostics()
    override fun close()
}

object BuiltInWorldShaderPipeline : WorldShaderPipeline {
    override val owner = RenderOwnerId("minosoft:built-in-shader")
    override val plan: ShaderPipelinePlan? = null

    override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) = fallback.activate()
    override fun composite(fallback: FramebufferShader): FramebufferShader = fallback
    override fun close() = Unit
}

class ShaderPipelineRegistry : AutoCloseable {
    private data class ActiveFrame(
        val pipeline: WorldShaderPipeline,
        val plan: ShaderPipelinePlan?,
        val state: IrisFrameState,
    )

    private data class SceneBinding(
        val fallback: Shader,
        val selected: Shader,
        val uniforms: Set<String>?,
        val contract: SceneShaderContract?,
    )

    internal data class Generation(
        val pipeline: WorldShaderPipeline,
        val closeOnRetire: Boolean,
    )

    data class Selection(
        val generation: Long,
        val owner: RenderOwnerId,
        val packName: String?,
        val fingerprint: String?,
        val programDirectory: String?,
        val sunPathRotation: Float?,
        val smoothingDirectives: IrisSmoothingDirectives?,
        val particlesOrdering: IrisParticleOrdering?,
        val separateEntityDraws: Boolean?,
        val skipAllRendering: Boolean?,
        val shadowDirectives: IrisShadowDirectives?,
    )

    class Lease internal constructor(
        private val delegate: TransactionalGenerationStore.Lease<Generation>,
    ) : AutoCloseable {
        val generation: Long get() = delegate.generation
        val pipeline: WorldShaderPipeline get() = delegate.value.pipeline

        override fun close() = delegate.close()
    }

    private val lock = Any()
    private val store = TransactionalOverrideStore(Generation(BuiltInWorldShaderPipeline, true)) { generation ->
        if (generation.closeOnRetire) generation.pipeline.close()
    }
    private val framePipeline = ThreadLocal<WorldShaderPipeline?>()
    private val sceneSemantic = ThreadLocal<PipelineSemantic?>()
    private val sceneBinding = ThreadLocal<SceneBinding?>()
    private val sceneSyncing = ThreadLocal<Boolean?>()
    private val internalTarget = ThreadLocal<Boolean?>()
    private val drawState = ThreadLocal<IrisDrawState?>()
    private val frameStateClock = IrisFrameStateClock()
    @Volatile
    private var activeFrame: ActiveFrame? = null
    private var closed = false

    fun acquire(): Lease = Lease(store.acquire())

    fun replace(
        terrain: TerrainBackendDescriptor,
        prepare: () -> WorldShaderPipeline,
    ): AutoCloseable {
        val candidate = prepare()
        try {
            validate(candidate, terrain)
            return synchronized(lock) {
                check(!closed) { "Shader pipeline registry is closed" }
                store.replace { Generation(candidate, true) }
            }
        } catch (failure: Throwable) {
            try {
                candidate.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * Pins one pipeline generation for a complete frame. Nested [withPipeline]
     * calls reuse this binding, so a reload published by a frame callback can
     * not mix a new shader generation into the graph already being executed.
     */
    fun <T> withFramePipeline(action: (WorldShaderPipeline) -> T): T {
        check(framePipeline.get() == null) { "A shader pipeline frame is already active on this thread" }
        return store.acquire().use { lease ->
            val pipeline = lease.value.pipeline
            framePipeline.set(pipeline)
            try {
                action(pipeline)
            } finally {
                framePipeline.remove()
            }
        }
    }

    internal fun <T> withFramePipeline(
        pipeline: WorldShaderPipeline,
        action: (WorldShaderPipeline) -> T,
    ): T {
        check(framePipeline.get() == null) { "A shader pipeline frame is already active on this thread" }
        framePipeline.set(pipeline)
        try {
            return action(pipeline)
        } finally {
            framePipeline.remove()
        }
    }

    fun <T> withFramePipeline(
        context: de.bixilon.minosoft.gui.rendering.RenderContext,
        action: (WorldShaderPipeline) -> T,
    ): T = withFramePipeline(context, {}, action)

    fun <T> withFramePipeline(
        context: de.bixilon.minosoft.gui.rendering.RenderContext,
        prepare: () -> Unit,
        action: (WorldShaderPipeline) -> T,
    ): T {
        return acquire().use { lease ->
            withFramePipeline(context, lease.pipeline, prepare, action)
        }
    }

    internal fun <T> withFramePipeline(
        context: de.bixilon.minosoft.gui.rendering.RenderContext,
        pipeline: WorldShaderPipeline,
        prepare: () -> Unit,
        action: (WorldShaderPipeline) -> T,
    ): T {
        check(framePipeline.get() == null) { "A shader pipeline frame is already active on this thread" }
        val state = frameStateClock.capture(context, pipeline.plan)
        pipeline.beginFrame(state)
        check(activeFrame == null) { "A shader pipeline frame is already active" }
        activeFrame = ActiveFrame(pipeline, pipeline.plan, state)
        try {
            prepare()
            framePipeline.set(pipeline)
            try {
                return action(pipeline)
            } finally {
                framePipeline.remove()
            }
        } finally {
            activeFrame = null
        }
    }

    fun <T> withPipeline(action: (WorldShaderPipeline) -> T): T {
        val pinned = framePipeline.get()
        if (pinned != null) return action(pinned)
        val preparing = activeFrame?.pipeline
        if (preparing != null) return action(preparing)
        return store.acquire().use { lease -> action(lease.value.pipeline) }
    }

    /**
     * Establishes the semantic for every shader bind performed by one graph
     * pass, including binds made inside child/entity renderers.
     */
    fun <T> withScene(semantic: PipelineSemantic, action: () -> T): T {
        check(sceneSemantic.get() == null) { "A scene shader semantic is already active on this thread" }
        sceneSemantic.set(semantic)
        try {
            return action()
        } finally {
            try {
                withPipeline(WorldShaderPipeline::restoreHostState)
            } finally {
                sceneBinding.remove()
                sceneSemantic.remove()
            }
        }
    }

    /**
     * Retains exact host shader activation while drawing geometry into a
     * renderer-owned auxiliary target. Selected scene programs also own their
     * framebuffer routing, so allowing them to bind here would redirect the
     * draw back into the Iris world target.
     */
    fun <T> withInternalTarget(action: () -> T): T {
        check(internalTarget.get() != true) { "An internal render target is already active on this thread" }
        val previousBinding = sceneBinding.get()
        withPipeline(WorldShaderPipeline::restoreHostState)
        internalTarget.set(true)
        sceneBinding.remove()
        try {
            return action()
        } finally {
            internalTarget.remove()
            if (previousBinding == null) sceneBinding.remove() else sceneBinding.set(previousBinding)
        }
    }

    /**
     * Adds identity state for a single draw. Scopes may nest (for example an
     * armor item inside an entity draw) without losing the outer entity ID.
     */
    fun <T> withDrawState(state: IrisDrawState, action: () -> T): T {
        val previous = drawState.get()
        drawState.set(if (previous == null) state else state.over(previous))
        try {
            return action()
        } finally {
            if (previous == null) drawState.remove() else drawState.set(previous)
        }
    }

    internal fun bindShader(fallback: Shader) {
        val semantic = sceneSemantic.get()
        requireFrameSceneSemantic(fallback.pipelineScope)
        if (
            internalTarget.get() == true ||
            semantic == null ||
            fallback.pipelineScope != ShaderPipelineScope.SCENE_GEOMETRY
        ) {
            withPipeline(WorldShaderPipeline::restoreHostState)
            fallback.activate()
            sceneBinding.set(SceneBinding(fallback, fallback, null, null))
            return
        }
        sceneBinding.remove()
        val contract = fallback.effectiveSceneContract()
        if (contract == null) {
            val selectedPlan = withPipeline { it.plan }
            if (selectedPlan != null) {
                error(
                    "Scene shader ${fallback::class.java.name} has no render-pipeline contract " +
                        "for ${semantic.name.lowercase()} while shader pack ${selectedPlan.packName} is active",
                )
            }
            fallback.activate()
            sceneBinding.set(SceneBinding(fallback, fallback, null, null))
            return
        }
        val selected = withPipeline { pipeline -> pipeline.bindScene(semantic, contract, fallback) }
        selected.activate()
        withPipeline { pipeline -> pipeline.bindFrameState(selected, semantic, contract) }
        val uniforms = withPipeline { pipeline -> pipeline.sceneUniforms(fallback, selected) }
        sceneBinding.set(SceneBinding(fallback, selected, uniforms, contract))
        if (selected !== fallback && sceneSyncing.get() != true) {
            sceneSyncing.set(true)
            try {
                withPipeline { pipeline -> pipeline.syncSceneState(fallback, selected) }
            } finally {
                sceneSyncing.remove()
            }
        }
        withPipeline { pipeline -> pipeline.bindDrawState(selected, drawState.get() ?: IrisDrawState.EMPTY) }
    }

    /**
     * Records the selected scene program at the physical vertex submission
     * boundary. Bind counters remain useful for state diagnostics, but only
     * this callback proves which specialization survived re-entrant uniform
     * and mesh-helper binds until geometry actually reached the driver.
     */
    internal fun recordDraw(vertices: Int) {
        if (vertices <= 0 || internalTarget.get() == true) return
        val semantic = sceneSemantic.get() ?: return
        val binding = sceneBinding.get() ?: return
        val contract = binding.contract ?: return
        withPipeline { pipeline ->
            pipeline.recordSceneDraw(
                semantic,
                contract,
                binding.fallback,
                binding.selected,
                vertices,
            )
        }
    }

    internal fun recordUniformUpload(fallback: Shader, target: NativeShader, revision: Long) {
        val binding = sceneBinding.get() ?: return
        if (binding.fallback !== fallback || binding.selected.native !== target) return
        withPipeline { it.recordUniformUpload(fallback, target, revision) }
    }

    /**
     * A selected shader pack owns every scene-geometry draw made during the
     * pinned render frame. Host shaders may still be activated outside a frame
     * while resources are loading, and internal composites have an explicit
     * scope, but a frame callback may not silently draw world geometry without
     * declaring which Iris scene semantic it belongs to.
     */
    internal fun requireFrameSceneSemantic(scope: ShaderPipelineScope) {
        if (
            scope == ShaderPipelineScope.SCENE_GEOMETRY &&
            internalTarget.get() != true &&
            sceneSemantic.get() == null &&
            framePipeline.get()?.plan != null
        ) {
            error(
                "Scene geometry shader bound outside a semantic render pass while shader pack " +
                    "${framePipeline.get()?.plan?.packName} owns the frame",
            )
        }
    }

    internal fun uniformTarget(fallback: Shader) =
        if (internalTarget.get() == true) {
            fallback.native
        } else {
            sceneBinding.get()?.takeIf { it.fallback === fallback }?.selected?.native ?: fallback.native
        }

    internal fun acceptsUniform(fallback: Shader, name: String): Boolean {
        if (internalTarget.get() == true) return true
        val binding = sceneBinding.get()?.takeIf { it.fallback === fallback } ?: return true
        if (binding.selected === fallback) return true
        val uniforms = binding.uniforms ?: return true
        return name.substringBefore('[') in uniforms
    }

    fun selection(): Selection = store.acquire().use { lease ->
        val pipeline = lease.value.pipeline
        Selection(
            generation = lease.generation,
            owner = pipeline.owner,
            packName = pipeline.plan?.packName,
            fingerprint = pipeline.plan?.fingerprint,
            programDirectory = pipeline.plan?.programDirectory,
            sunPathRotation = pipeline.plan?.sunPathRotation,
            smoothingDirectives = pipeline.plan?.smoothingDirectives,
            particlesOrdering = pipeline.plan?.particlesOrdering,
            separateEntityDraws = pipeline.plan?.separateEntityDraws,
            skipAllRendering = pipeline.plan?.skipAllRendering,
            shadowDirectives = pipeline.plan?.shadowDirectives,
        )
    }

    fun plan(): ShaderPipelinePlan? =
        framePipeline.get()?.plan ?: activeFrame?.plan ?: store.acquire().use { it.value.pipeline.plan }

    /**
     * The render thread publishes this immutable snapshot before asynchronous
     * producer preparation starts and clears it only after the graph finishes.
     * Workers therefore consume the same generation and matrices as the
     * shadow pass even when another session publishes a replacement.
     */
    fun shadowCulling(): IrisShadowCullingSet? = activeFrame?.state?.shadowCulling

    fun stats(): TransactionalGenerationStore.Stats = store.stats()

    fun diagnostics(): WorldShaderPipelineDiagnostics =
        store.acquire().use { it.value.pipeline.diagnostics() }

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

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        store.close()
    }
}
