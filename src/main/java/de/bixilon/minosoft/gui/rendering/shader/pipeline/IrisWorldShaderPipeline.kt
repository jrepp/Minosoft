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

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.exceptions.ShaderLinkingException
import de.bixilon.minosoft.gui.rendering.exceptions.ShaderLoadingException
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourceId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetDescriptor
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.world.PipelineSemantic
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.types.LightShader
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.skeletal.SkeletalManager
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes
import de.bixilon.minosoft.gui.rendering.system.base.RenderingCapabilities
import de.bixilon.minosoft.tags.MinecraftTagTypes.BLOCK
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShaderSource
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.irisPerBufferBlending
import de.bixilon.minosoft.gui.rendering.light.LightmapBuffer
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.gui.rendering.terrain.NEAR_TERRAIN_MATERIALS
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL30.glGetIntegeri
import org.lwjgl.opengl.GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT
import org.lwjgl.opengl.GL43.glDispatchCompute
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

private data class IrisSceneProgramKey(
    val name: String,
    val vertexAbi: SceneVertexAbi,
    val stateAbi: SceneStateAbi,
)

private data class IrisSceneProgram(
    val diagnosticId: Int,
    val source: ShaderProgramSource,
    val bridge: SceneProgramBridge,
    val shader: Shader,
)

private class IrisShadowTerrainShader(native: NativeShader) : Shader(native), TextureShader {
    override var textures: TextureManager by textureManager()
}

private class IrisTerrainShader(native: NativeShader) : Shader(native), TextureShader, LightShader {
    override var textures: TextureManager by textureManager()
    override val lightmap: LightmapBuffer by lightmap()
}

private data class IrisFullscreenProgram(
    val source: ShaderProgramSource,
    val shader: Shader,
)

private data class IrisComputeProgram(
    val source: IrisComputeProgramSource,
    val bindingSource: ShaderProgramSource,
    val shader: Shader,
)

/** Generation-scoped live diagnostic with an identity presentation at each retained boundary. */
internal sealed class IrisPassCutoff(val wireName: String) {
    data object Shadow : IrisPassCutoff("shadow")
    data object Geometry : IrisPassCutoff("geometry")
    data object Deferred : IrisPassCutoff("deferred")
    data class Composite(val program: String) : IrisPassCutoff(program)
    data object Final : IrisPassCutoff("final")

    internal fun allows(phase: ShaderProgramPhase): Boolean = when (phase) {
        ShaderProgramPhase.BEGIN,
        ShaderProgramPhase.SHADOW_COMPOSITE,
        -> true
        ShaderProgramPhase.PREPARE -> this !is Shadow
        ShaderProgramPhase.DEFERRED -> this is Deferred || this is Composite || this is Final
        ShaderProgramPhase.COMPOSITE -> this is Composite || this is Final
        else -> true
    }

    internal fun programs(phase: ShaderProgramPhase, ordered: List<String>): List<String> {
        if (!allows(phase)) return emptyList()
        if (phase != ShaderProgramPhase.COMPOSITE || this !is Composite) return ordered
        val cutoff = ordered.indexOf(program)
        check(cutoff >= 0) { "Iris composite cutoff is not present in the active plan: $program" }
        return ordered.take(cutoff + 1)
    }

    internal fun allowsFinalCompute(): Boolean = this is Final

    internal fun presentationBuffer(
        finalBuffer: ShaderBufferId,
        compositePrograms: List<ShaderProgramSource>,
    ): ShaderBufferId {
        val base = ShaderBufferId(ShaderBufferKind.COLORTEX, 0)
        if (this !is Composite || finalBuffer == base) return base
        val completed = programs(ShaderProgramPhase.COMPOSITE, compositePrograms.map(ShaderProgramSource::name)).toSet()
        return if (compositePrograms.any { it.name in completed && finalBuffer in it.resourceUsage.colorWrites }) {
            finalBuffer
        } else {
            base
        }
    }

    companion object {
        internal fun options(plan: ShaderPipelinePlan): List<String> = buildList {
            add(Shadow.wireName)
            add(Geometry.wireName)
            add(Deferred.wireName)
            addAll(
                plan.programs.asSequence()
                    .filter { it.phase == ShaderProgramPhase.COMPOSITE }
                    .map(ShaderProgramSource::name),
            )
            add(Final.wireName)
        }.distinct()

        internal fun parse(value: String, plan: ShaderPipelinePlan): IrisPassCutoff {
            require(value.length <= MAXIMUM_CUTOFF_NAME_LENGTH) {
                "Iris pass cutoff exceeds $MAXIMUM_CUTOFF_NAME_LENGTH characters"
            }
            return when (value) {
                Shadow.wireName -> Shadow
                Geometry.wireName -> Geometry
                Deferred.wireName -> Deferred
                Final.wireName -> Final
                else -> {
                    require(value in options(plan)) {
                        "Unknown Iris pass cutoff '$value'; expected one of ${options(plan).joinToString()}"
                    }
                    Composite(value)
                }
            }
        }

        private const val MAXIMUM_CUTOFF_NAME_LENGTH = 256
    }
}

class IrisWorldShaderPipeline private constructor(
    private val context: RenderContext,
    override val plan: ShaderPipelinePlan,
    private val terrain: Map<TerrainMaterialClass, IrisTerrainShader>,
    private val terrainSources: Map<TerrainMaterialClass, ShaderProgramSource>,
    private val scenes: Map<IrisSceneProgramKey, IrisSceneProgram>,
    private val composite: FramebufferShader,
    private val compositeSource: ShaderProgramSource,
    private val presentationBuffer: ShaderBufferId,
    private val diagnosticComposite: FramebufferShader,
    private val shadowTerrain: Map<TerrainMaterialClass, IrisShadowTerrainShader>,
    private val shadowTerrainSources: Map<TerrainMaterialClass, ShaderProgramSource>,
    private val shadowTerrainProgramNames: Map<String, String>,
    private val shadowScenes: Map<IrisSceneProgramKey, IrisSceneProgram>,
    private val fullscreenPrograms: Map<ShaderProgramPhase, List<IrisFullscreenProgram>>,
    private val computePrograms: Map<ShaderProgramPhase, List<IrisComputeProgram>>,
    private val targets: IrisOpenGlRenderTargets,
    private val customTextures: IrisOpenGlCustomTextures,
    private val customResources: IrisOpenGlCustomResources,
    private val frameUniforms: Map<Shader, Set<String>>,
    private val programSources: Map<Shader, ShaderProgramSource>,
    private val textureArrayLayouts: Map<Shader, IrisTextureArrayLayout>,
) : WorldShaderPipeline {
    override val owner get() = plan.owner
    private var closed = false
    private val sceneProgramCount = scenes.size + shadowScenes.size
    private val sceneProgramsById = arrayOfNulls<IrisSceneProgram>(sceneProgramCount)
    private val sceneProgramsByShader = IdentityHashMap<Shader, IrisSceneProgram>()
    private val sceneShadersByNative = IdentityHashMap<NativeShader, Shader>()
    private val selectedTerrainBinds = LongArray(2 * TerrainMaterialClass.entries.size)
    private val selectedSceneBinds = LongArray(sceneProgramCount)
    private val selectedSceneContracts =
        LongArray(PipelineSemantic.entries.size * SceneProgramFamily.entries.size * sceneProgramCount)
    private val submittedSceneDraws =
        LongArray(PipelineSemantic.entries.size * SceneProgramFamily.entries.size * sceneProgramCount)
    private val submittedSceneVertices = LongArray(submittedSceneDraws.size)
    private val submittedFallbackDraws = NumericDiagnosticCounters()
    private val submittedFallbackVertices = NumericDiagnosticCounters()
    private val submittedMainSceneVertexAbis = BooleanArray(SceneVertexAbi.entries.size)
    private val submittedMainSceneStateAbis = BooleanArray(SceneStateAbi.entries.size)
    private val rejectedSceneBinds = linkedMapOf<String, Long>()
    private val fallbackSceneBinds = NumericDiagnosticCounters()
    private val diagnosedFallbackSceneContracts = BooleanArray(
        PipelineSemantic.entries.size *
            SceneProgramFamily.entries.size *
            SceneVertexAbi.entries.size *
            SceneStateAbi.entries.size,
    )
    private val depthSnapshots = linkedMapOf<String, Long>()
    private var frameState: IrisFrameState? = null
    private var textureArrayState = IrisTextureArrayState.capture(context.textures)
    private val frameSmoothing = IrisFrameSmoothing(plan.smoothingDirectives)
    private val customUniformEvaluator = IrisCustomUniformEvaluator(plan.customUniforms, plan.sunPathRotation)
    private var frameUniformUploads = 0L
    private var textureArrayUniformUploads = 0L
    private val textureArrayUploadFrames = IdentityHashMap<Shader, Int>()
    private val frameBindings = IrisIdentityBindingCache<Shader, FrameBinding>()
    private val drawBindings = IrisIdentityBindingCache<Shader, IrisResolvedDrawState>()
    private val hostUniformRevisions = IrisIdentityRevisionCache<Shader, Shader>()
    private var drawUniformUploads = 0L
    private val drawStateBinds = linkedMapOf<String, Long>()
    private val entityColorBinds = linkedMapOf<String, Long>()
    private val renderStageBinds = linkedMapOf<String, Long>()
    private val fullscreenProgramExecutions = linkedMapOf<String, Long>()
    private val maximumComputeGroups = if (computePrograms.values.all { it.isEmpty() }) {
        null
    } else {
        ComputeGroups(
            gl { glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0) },
            gl { glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1) },
            gl { glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2) },
        )
    }
    private var setupSize = Vec2i(-1, -1)
    private var currentView = RenderViewId.MAIN
    private var rejectedSceneBindEvents = 0L
    private var drawStateDiagnosticEvents = 0L
    private var renderStageDiagnosticEvents = 0L
    @Volatile private var passCutoff: IrisPassCutoff? = null

    init {
        (scenes.values + shadowScenes.values).forEach { program ->
            sceneProgramsById[program.diagnosticId] = program
            sceneProgramsByShader[program.shader] = program
            sceneShadersByNative[program.shader.native] = program.shader
        }
    }

    override fun beginFrame(state: IrisFrameState) {
        check(!closed) { "Iris shader pipeline is closed" }
        // Frame state and target roles are pinned before any graph producer executes.
        val selected = state.selectedBlock
        val selectedId = plan.idMaps.block(selected.state, missing = 0) { tag, blockState ->
            context.session.tags.isIn(BLOCK, tag, blockState.block) ||
                context.session.legacyTags.isIn(BLOCK, tag, blockState.block)
        }
        val resolved = state.copy(selectedBlock = selected.copy(id = selectedId))
        val smoothed = frameSmoothing.apply(resolved)
        frameState = smoothed
        textureArrayState = IrisTextureArrayState.capture(context.textures)
        targets.beginFrame(smoothed)
        customResources.beginFrame(targets.size(RenderViewId.MAIN))
        executeSetupIfNeeded()
    }

    override fun bindViewTarget(view: RenderViewId, fallback: () -> Unit) {
        check(!closed) { "Iris shader pipeline is closed" }
        targets.bindView(view)
    }

    override fun restoreHostState() {
        if (closed) return
        targets.restoreHostBlend()
    }

    override fun bindTerrain(view: RenderViewId, material: TerrainMaterialClass, fallback: Shader) {
        check(!closed) { "Iris shader pipeline is closed" }
        if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
            val source = shadowTerrainSources.getValue(material)
            targets.bindProgram(view, source)
            shadowTerrain.getValue(material).also {
                it.activate()
                uploadFrameState(it, IrisRenderStage.terrain(material))
                val layout = textureArrayLayouts.getValue(it)
                targets.bindSamplers(
                    source,
                    it.native,
                    customTextures,
                    customResources,
                    layout.physicalSlots.toSet(),
                )
                uploadDrawState(it, IrisDrawState.EMPTY)
            }
            recordTerrainBind(view, material)
        } else {
            val source = terrainSources.getValue(material)
            targets.bindProgram(view, source)
            terrain.getValue(material).also {
                it.activate()
                uploadFrameState(it, IrisRenderStage.terrain(material))
                val layout = textureArrayLayouts.getValue(it)
                targets.bindSamplers(
                    source,
                    it.native,
                    customTextures,
                    customResources,
                    layout.physicalSlots.toSet(),
                )
                uploadDrawState(it, IrisDrawState.EMPTY)
            }
            recordTerrainBind(view, material)
        }
    }

    private fun recordTerrainBind(
        view: RenderViewId,
        material: TerrainMaterialClass,
    ) {
        val viewIndex = if (view == IrisShaderPackPlanner.SHADOW_VIEW) SHADOW_VIEW_INDEX else MAIN_VIEW_INDEX
        selectedTerrainBinds[viewIndex * TerrainMaterialClass.entries.size + material.ordinal]++
    }

    override fun bindScene(
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
        fallback: Shader,
    ): Shader {
        check(!closed) { "Iris shader pipeline is closed" }
        val shadowView = currentView == IrisShaderPackPlanner.SHADOW_VIEW
        val programs = if (shadowView) shadowScenes else scenes
        val candidates = if (shadowView) {
            IrisProgramFallbacks.shadowScene(contract)
        } else {
            IrisProgramFallbacks.scene(semantic, contract, plan.separateEntityDraws)
        }
        for (name in candidates) {
            val selected = programs[IrisSceneProgramKey(name, contract.vertexAbi, contract.stateAbi)] ?: continue
            val missingUniforms = selected.bridge.uniforms - fallback.declaredUniforms
            if (missingUniforms.isNotEmpty()) {
                if (sampleDiagnostic(rejectedSceneBindEvents++)) {
                    val key =
                        "${semantic.name}/$name/${contract.vertexAbi.name}/${contract.stateAbi.name}/missing=" +
                            missingUniforms.sorted().joinToString(",")
                    rejectedSceneBinds[key] = (rejectedSceneBinds[key] ?: 0L) + 1L
                }
                continue
            }
            targets.bindProgram(currentView, selected.source)
            selectedSceneBinds[selected.diagnosticId]++
            selectedSceneContracts[sceneRouteIndex(semantic, contract.family, selected.diagnosticId)]++
            return selected.shader
        }
        targets.bindView(currentView)
        val route = contractRouteKey(semantic, contract)
        fallbackSceneBinds.add(route)
        if (!diagnosedFallbackSceneContracts[route]) {
            diagnosedFallbackSceneContracts[route] = true
            val availability = candidates.joinToString(",") { name ->
                val selected = programs[
                    IrisSceneProgramKey(name, contract.vertexAbi, contract.stateAbi)
                ]
                if (selected == null) {
                    "$name=absent"
                } else {
                    val missing = selected.bridge.uniforms - fallback.declaredUniforms
                    "$name=missing(${missing.sorted().joinToString("+")})"
                }
            }
            Log.log(LogMessageType.RENDERING, LogLevels.WARN) {
                "IRIS_SCENE_BIND_FALLBACK semantic=${semantic.name} family=${contract.family.name} " +
                    "vertex=${contract.vertexAbi.name} state=${contract.stateAbi.name} " +
                    "candidates=$availability"
            }
        }
        return fallback
    }

    override fun syncSceneState(fallback: Shader, selected: Shader) {
        if (!hostUniformRevisions.requiresSync(
                source = fallback,
                target = selected,
                revision = fallback.uniformRevision,
                uploadInProgress = fallback.uniformUploadInProgress,
            )
        ) return
        val program = sceneProgramsByShader[selected] ?: return fallback.syncUniformsTo(selected.native)
        fallback.syncUniformsTo(selected.native, program.bridge.uniforms)
        drawBindings.invalidate(selected)
        textureArrayLayouts[selected]?.bind(selected.native)
        hostUniformRevisions.record(fallback, selected, fallback.uniformRevision)
    }

    override fun recordUniformUpload(fallback: Shader, target: NativeShader, revision: Long) {
        val selected = sceneShadersByNative[target] ?: return
        drawBindings.invalidate(selected)
        hostUniformRevisions.record(fallback, selected, revision)
    }

    override fun sceneUniforms(fallback: Shader, selected: Shader): Set<String>? {
        if (selected === fallback) return null
        return sceneProgramsByShader[selected]?.bridge?.uniforms
    }

    override fun bindFrameState(
        shader: Shader,
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
    ) {
        if (shader in frameUniforms) uploadFrameState(shader, IrisRenderStage.scene(semantic, contract))
        programSources[shader]?.let { source ->
            targets.bindSamplers(
                source,
                shader.native,
                customTextures,
                customResources,
                textureArrayLayouts[shader]?.physicalSlots?.toSet(),
            )
        }
    }

    override fun bindDrawState(shader: Shader, state: IrisDrawState) {
        uploadDrawState(shader, state)
    }

    override fun recordSceneDraw(
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
        fallback: Shader,
        selected: Shader,
        vertices: Int,
    ) {
        val program = sceneProgramsByShader[selected]
        if (program == null) {
            val key = fallbackRouteKey(
                semantic,
                contract,
                unknown = selected !== fallback,
            )
            submittedFallbackDraws.add(key)
            submittedFallbackVertices.add(key, vertices.toLong())
        } else {
            val index = sceneRouteIndex(semantic, contract.family, program.diagnosticId)
            submittedSceneDraws[index]++
            submittedSceneVertices[index] += vertices.toLong()
        }
        if (currentView == RenderViewId.MAIN) {
            submittedMainSceneVertexAbis[contract.vertexAbi.ordinal] = true
            submittedMainSceneStateAbis[contract.stateAbi.ordinal] = true
        }
    }

    private fun sceneRouteIndex(
        semantic: PipelineSemantic,
        family: SceneProgramFamily,
        programId: Int,
    ): Int = (
        semantic.ordinal * SceneProgramFamily.entries.size + family.ordinal
        ) * sceneProgramCount + programId

    private fun fallbackRouteKey(
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
        unknown: Boolean,
    ): Int = contractRouteKey(semantic, contract) * FALLBACK_KIND_COUNT +
        if (unknown) UNKNOWN_FALLBACK_KIND else HOST_FALLBACK_KIND

    private fun contractRouteKey(
        semantic: PipelineSemantic,
        contract: SceneShaderContract,
    ): Int = (
        (
            semantic.ordinal * SceneProgramFamily.entries.size + contract.family.ordinal
            ) * SceneVertexAbi.entries.size + contract.vertexAbi.ordinal
        ) * SceneStateAbi.entries.size + contract.stateAbi.ordinal

    private fun terrainBindDiagnostics(): Map<String, Long> = buildMap {
        for (viewIndex in MAIN_VIEW_INDEX..SHADOW_VIEW_INDEX) {
            NEAR_TERRAIN_MATERIALS.forEach { material ->
                val count = selectedTerrainBinds[viewIndex * TerrainMaterialClass.entries.size + material.ordinal]
                if (count == 0L) return@forEach
                val shadow = viewIndex == SHADOW_VIEW_INDEX
                val view = if (shadow) IrisShaderPackPlanner.SHADOW_VIEW else RenderViewId.MAIN
                val source = if (shadow) shadowTerrainSources.getValue(material) else terrainSources.getValue(material)
                put("${view.value}/${material.name.lowercase()}/${source.name}", count)
            }
        }
    }

    private fun sceneBindDiagnostics(): Map<String, Long> = buildMap {
        sceneProgramsById.forEachIndexed { id, program ->
            val count = selectedSceneBinds[id]
            if (program == null || count == 0L) return@forEachIndexed
            val key = "${program.source.name}/${program.bridge.vertexAbi.name}/${program.bridge.stateAbi.name}"
            put(key, (get(key) ?: 0L) + count)
        }
    }

    private fun sceneRouteDiagnostics(counts: LongArray): Map<String, Long> = buildMap {
        PipelineSemantic.entries.forEach { semantic ->
            SceneProgramFamily.entries.forEach { family ->
                sceneProgramsById.forEachIndexed { id, program ->
                    if (program == null) return@forEachIndexed
                    val count = counts[sceneRouteIndex(semantic, family, id)]
                    if (count == 0L) return@forEachIndexed
                    val key =
                        "${semantic.name}/${family.name}/${program.source.name}/" +
                            "${program.bridge.vertexAbi.name}/${program.bridge.stateAbi.name}"
                    put(key, (get(key) ?: 0L) + count)
                }
            }
        }
    }

    private fun submittedRouteDiagnostics(counts: LongArray, fallback: NumericDiagnosticCounters): Map<String, Long> =
        buildMap {
            putAll(sceneRouteDiagnostics(counts))
            fallback.forEach { encoded, count ->
                var key = encoded
                val kind = key % FALLBACK_KIND_COUNT
                key /= FALLBACK_KIND_COUNT
                val state = SceneStateAbi.entries[key % SceneStateAbi.entries.size]
                key /= SceneStateAbi.entries.size
                val vertex = SceneVertexAbi.entries[key % SceneVertexAbi.entries.size]
                key /= SceneVertexAbi.entries.size
                val family = SceneProgramFamily.entries[key % SceneProgramFamily.entries.size]
                val semantic = PipelineSemantic.entries[key / SceneProgramFamily.entries.size]
                val program = if (kind == HOST_FALLBACK_KIND) "host" else "unknown"
                put("${semantic.name}/${family.name}/$program/${vertex.name}/${state.name}", count)
            }
            if (fallback.overflow > 0L) put("overflow", fallback.overflow)
        }

    private fun fallbackBindDiagnostics(): Map<String, Long> = buildMap {
        fallbackSceneBinds.forEach { encoded, count ->
            var key = encoded
            val state = SceneStateAbi.entries[key % SceneStateAbi.entries.size]
            key /= SceneStateAbi.entries.size
            val vertex = SceneVertexAbi.entries[key % SceneVertexAbi.entries.size]
            key /= SceneVertexAbi.entries.size
            val family = SceneProgramFamily.entries[key % SceneProgramFamily.entries.size]
            val semantic = PipelineSemantic.entries[key / SceneProgramFamily.entries.size]
            put("${semantic.name}/${family.name}/${vertex.name}/${state.name}", count)
        }
        if (fallbackSceneBinds.overflow > 0L) put("overflow", fallbackSceneBinds.overflow)
    }

    private fun submittedVertexAbis(): Set<SceneVertexAbi> = SceneVertexAbi.entries
        .filterTo(linkedSetOf()) { submittedMainSceneVertexAbis[it.ordinal] }

    private fun submittedStateAbis(): Set<SceneStateAbi> = SceneStateAbi.entries
        .filterTo(linkedSetOf()) { submittedMainSceneStateAbis[it.ordinal] }

    override fun snapshotDepth(snapshot: ShaderDepthSnapshot) {
        check(!closed) { "Iris shader pipeline is closed" }
        if (!targets.snapshotDepth(snapshot)) return
        val key = snapshot.name.lowercase()
        depthSnapshots[key] = (depthSnapshots[key] ?: 0L) + 1L
    }

    override fun diagnostics() = WorldShaderPipelineDiagnostics(
        selectedProfile = plan.selectedProfile,
        compiledScenePrograms = scenes.size,
        compiledShadowScenePrograms = shadowScenes.size,
        compiledSceneRoutes = scenes.keys.mapTo(linkedSetOf()) { key ->
            "${key.name}/${key.vertexAbi.name}/${key.stateAbi.name}"
        },
        compiledShadowSceneRoutes = shadowScenes.keys.mapTo(linkedSetOf()) { key ->
            "${key.name}/${key.vertexAbi.name}/${key.stateAbi.name}"
        },
        shadowTerrainPrograms = shadowTerrainProgramNames,
        logicalShaderBuffers = targets.logicalBufferCount,
        physicalShaderTextures =
            targets.physicalTextureCount + customTextures.physicalTextureCount + customResources.physicalImageCount,
        customShaderTextures = buildMap {
            plan.textures.custom.forEach { binding ->
                val dimensions = when (val texture = binding.texture) {
                    is IrisCustomTextureDescriptor.Png -> "${texture.value.width}x${texture.value.height}"
                    is IrisCustomTextureDescriptor.Raw ->
                        "${texture.value.width}x${texture.value.height}x${texture.value.depth}"
                    is IrisCustomTextureDescriptor.Resource -> "managed-resource"
                }
                val sampling = when (val texture = binding.texture) {
                    is IrisCustomTextureDescriptor.Png ->
                        "${if (texture.value.blur) "linear" else "nearest"}/" +
                            if (texture.value.clamp) "clamp" else "repeat"
                    is IrisCustomTextureDescriptor.Raw ->
                        "${if (texture.value.blur) "linear" else "nearest"}/" +
                            if (texture.value.clamp) "clamp" else "repeat"
                    is IrisCustomTextureDescriptor.Resource -> "source-native/deferred"
                }
                put(
                    "${binding.stage.name.lowercase()}.${binding.sampler}",
                    "${binding.texture.path}@$dimensions/$sampling",
                )
            }
            put(
                "noisetex",
                when (val noise = plan.textures.noise) {
                    is IrisNoiseTextureDescriptor.Custom ->
                        "${noise.texture.path}@${noise.texture.width}x${noise.texture.height}" +
                            "/${if (noise.texture.blur) "linear" else "nearest"}" +
                            "/${if (noise.texture.clamp) "clamp" else "repeat"}"
                    is IrisNoiseTextureDescriptor.Generated -> "generated@${noise.resolution}x${noise.resolution}/linear/repeat"
                },
            )
        },
        textureArraySizes = textureArrayState.diagnostics(),
        textureArrayUniformUploads = textureArrayUniformUploads,
        programAlphaTests = plan.programs.mapNotNull { program ->
            val test = program.alphaTest ?: return@mapNotNull null
            program.name to "${test.function.name} ${test.reference}"
        }.toMap(),
        programBlendOverrides = plan.programs
            .filterNot { it.blendOverride.isEmpty }
            .associate { program ->
                program.name to buildList {
                    program.blendOverride.program?.let { add("program=${it.diagnostic()}") }
                    program.blendOverride.buffers.toSortedMap().forEach { (buffer, mode) ->
                        add("$buffer=${mode.diagnostic()}")
                    }
                }.joinToString(",")
            },
        appliedBlendOverrides = targets.appliedBlendOverrides,
        shaderBuffers = plan.buffers.buffers.associate { descriptor ->
            descriptor.id.sampler to buildString {
                append(descriptor.format)
                append('@')
                append(descriptor.size)
                append('/')
                append(descriptor.clear.name.lowercase())
                append('/')
                append(descriptor.filter.name.lowercase())
                if (descriptor.doubleBuffered) append("/double")
                if (descriptor.mipmapped) append("/mipmap")
            }
        },
        customShaderResources = buildMap {
            plan.customResources.images.forEach { descriptor ->
                put(
                    "image.${descriptor.name}",
                    "${descriptor.target}/${descriptor.internalFormat}/${descriptor.size}/clear=${descriptor.clear}",
                )
            }
            plan.customResources.shaderStorageBuffers.forEach { descriptor ->
                put(
                    "bufferObject.${descriptor.index}",
                    "${descriptor.bytesPerElement}/relative=${descriptor.relative}/" +
                        "${descriptor.widthScale}x${descriptor.heightScale}",
                )
            }
        },
        programStages = plan.programs.associate { program ->
            program.name to buildList {
                add("vertex")
                if (program.tessellationControl != null) {
                    add("tess-control")
                    add("tess-evaluation")
                }
                if (program.geometry != null) add("geometry")
                add("fragment")
            }.joinToString(",")
        },
        programOutputs = plan.programs
            .filter { it.resourceUsage.colorWrites.isNotEmpty() }
            .associate { it.name to it.resourceUsage.colorWrites.joinToString(",") },
        programFlips = plan.programs
            .filter { it.resourceUsage.flipsAfter.isNotEmpty() }
            .associate { it.name to it.resourceUsage.flipsAfter.joinToString(",") },
        programSamplers = plan.programs
            .filter {
                it.resourceUsage.sampledBuffers.isNotEmpty() ||
                    it.resourceUsage.sampledCustomTextures.isNotEmpty() ||
                    it.resourceUsage.sampledCustomImages.isNotEmpty() ||
                    it.resourceUsage.renderTargetImages.isNotEmpty() ||
                    it.resourceUsage.customImages.isNotEmpty()
            }
            .associate { program ->
                program.name to (
                    program.resourceUsage.sampledBuffers.entries.map { "${it.key}=${it.value}" } +
                        program.resourceUsage.sampledCustomTextures.entries.map {
                            "${it.key}=${it.value}"
                        } +
                        program.resourceUsage.sampledCustomImages.entries.map {
                            "${it.key}=image.${it.value}"
                        } +
                        program.resourceUsage.renderTargetImages.entries.map {
                            "${it.key}=${it.value}:read-write"
                        } +
                        program.resourceUsage.customImages.map { "image.$it=read-write" }
                    ).joinToString(",")
            },
        programShadowComparisonSamplers = plan.programs
            .filter { it.resourceUsage.shadowComparisonSamplers.isNotEmpty() }
            .associate { program ->
                program.name to program.resourceUsage.shadowComparisonSamplers.sorted().joinToString(",")
            },
        selectedTerrainBinds = terrainBindDiagnostics(),
        selectedSceneBinds = sceneBindDiagnostics(),
        selectedSceneContracts = sceneRouteDiagnostics(selectedSceneContracts),
        submittedSceneDraws = submittedRouteDiagnostics(submittedSceneDraws, submittedFallbackDraws),
        submittedSceneVertices = submittedRouteDiagnostics(submittedSceneVertices, submittedFallbackVertices),
        submittedMainSceneVertexAbis = submittedVertexAbis().mapTo(linkedSetOf()) { it.name },
        submittedMainSceneStateAbis = submittedStateAbis().mapTo(linkedSetOf()) { it.name },
        unsubmittedCompiledSceneVertexAbis = scenes.keys
            .mapTo(linkedSetOf()) { it.vertexAbi }
            .minus(submittedVertexAbis())
            .mapTo(linkedSetOf()) { it.name },
        unsubmittedCompiledSceneStateAbis = scenes.keys
            .mapTo(linkedSetOf()) { it.stateAbi }
            .minus(submittedStateAbis())
            .mapTo(linkedSetOf()) { it.name },
        rejectedSceneBinds = rejectedSceneBinds.toMap(),
        fallbackSceneBinds = fallbackBindDiagnostics(),
        frameState = frameState?.frameCounter?.toLong(),
        frameInputValues = frameState?.let {
            mapOf(
                "viewWidth" to it.viewWidth,
                "viewHeight" to it.viewHeight,
                "playerMood" to it.playerMood,
                "constantMood" to it.constantMood,
                "playerLookVector.x" to it.playerLookVector.x,
                "playerLookVector.y" to it.playerLookVector.y,
                "playerLookVector.z" to it.playerLookVector.z,
                "playerBodyVector.x" to it.playerBodyVector.x,
                "playerBodyVector.y" to it.playerBodyVector.y,
                "playerBodyVector.z" to it.playerBodyVector.z,
                "bedrockLevel" to it.worldInfo.bedrockLevel.toFloat(),
                "cloudHeight" to it.worldInfo.cloudHeight,
                "heightLimit" to it.worldInfo.heightLimit.toFloat(),
                "logicalHeightLimit" to it.worldInfo.logicalHeightLimit.toFloat(),
                "hasCeiling" to if (it.worldInfo.hasCeiling) 1.0f else 0.0f,
                "hasSkylight" to if (it.worldInfo.hasSkylight) 1.0f else 0.0f,
                "ambientLight" to it.worldInfo.ambientLight,
                "near" to it.near,
                "far" to it.far,
                "dhRenderDistance" to it.distantHorizons.renderDistance.toFloat(),
                "cloudTime" to it.cloudTime,
                "currentColorSpace" to 0.0f,
                "currentSelectedBlockId" to it.selectedBlock.id.toFloat(),
                "currentSelectedBlockPos.x" to it.selectedBlock.position.x,
                "currentSelectedBlockPos.y" to it.selectedBlock.position.y,
                "currentSelectedBlockPos.z" to it.selectedBlock.position.z,
            )
        }.orEmpty(),
        shadowCulling = frameState?.shadowCulling?.let {
            mapOf(
                "terrain" to it.terrain.kind.name.lowercase(),
                "entities" to it.entities.kind.name.lowercase(),
                "distinctEntities" to it.hasDistinctEntityVolume.toString(),
                "blockEntityDistance" to (it.blockEntityDistanceLimit?.toString() ?: "terrain-section"),
            )
        }.orEmpty(),
        frameUniformUploads = frameUniformUploads,
        drawUniformUploads = drawUniformUploads,
        diagnosticTraceSampleRate = DIAGNOSTIC_SAMPLE_MASK.toInt() + 1,
        drawStateBinds = drawStateBinds.toMap(),
        entityColorBinds = entityColorBinds.toMap(),
        renderStageBinds = renderStageBinds.toMap(),
        depthSnapshots = depthSnapshots.toMap(),
        fullscreenProgramExecutions = fullscreenProgramExecutions.toMap(),
        activePassCutoff = passCutoff?.wireName,
    )

    internal fun passCutoffOptions(): List<String> = IrisPassCutoff.options(plan)

    internal fun setPassCutoff(value: String?): String? {
        passCutoff = value?.let { IrisPassCutoff.parse(it, plan) }
        return passCutoff?.wireName
    }

    override fun beginView(view: RenderViewId) {
        check(!closed) { "Iris shader pipeline is closed" }
        if (view != IrisShaderPackPlanner.SHADOW_VIEW) return
        check(currentView == RenderViewId.MAIN) { "A shader auxiliary view is already active" }
        targets.beginShadow()
        try {
            executeComputePrograms(ShaderProgramPhase.SHADOW, targets.size(view))
            targets.clearShadowColors()
            targets.bindView(view)
            currentView = view
        } catch (failure: Throwable) {
            currentView = RenderViewId.MAIN
            try {
                targets.bindView(RenderViewId.MAIN)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun endView(view: RenderViewId) {
        check(!closed) { "Iris shader pipeline is closed" }
        if (view != IrisShaderPackPlanner.SHADOW_VIEW) return
        check(currentView == view) { "Shader auxiliary view $view is not active" }
        currentView = RenderViewId.MAIN
        targets.bindView(RenderViewId.MAIN)
    }

    override fun composite(fallback: FramebufferShader): FramebufferShader {
        check(!closed) { "Iris shader pipeline is closed" }
        val cutoff = passCutoff
        if (cutoff != null && cutoff !is IrisPassCutoff.Final) {
            diagnosticComposite.activate()
            targets.bindDiagnosticSampler(
                cutoff.presentationBuffer(
                    presentationBuffer,
                    fullscreenPrograms[ShaderProgramPhase.COMPOSITE].orEmpty().map(IrisFullscreenProgram::source),
                ),
                diagnosticComposite.native,
                "colortex0",
            )
            return diagnosticComposite
        }
        val finalComputes = computePrograms[ShaderProgramPhase.FINAL].orEmpty()
        if (finalComputes.isNotEmpty() && (cutoff?.allowsFinalCompute() != false)) {
            withFullscreenOpenGlState {
                executeComputePrograms(ShaderProgramPhase.FINAL, targets.size(RenderViewId.MAIN))
            }
        }
        composite.activate()
        uploadFrameState(composite, IrisRenderStage.NONE)
        targets.bindSamplers(
            compositeSource,
            composite.native,
            customTextures,
            customResources,
            textureArrayLayouts[composite]?.physicalSlots?.toSet(),
        )
        uploadDrawState(composite, IrisDrawState.EMPTY)
        return composite
    }

    override fun executePrograms(phase: ShaderProgramPhase, drawFullscreen: () -> Unit) {
        check(!closed) { "Iris shader pipeline is closed" }
        val cutoff = passCutoff
        if (cutoff?.allows(phase) == false) return
        val programs = fullscreenPrograms[phase].orEmpty()
        val computes = computePrograms[phase].orEmpty()
        if (programs.isEmpty() && computes.isEmpty()) return
        require(phase in FULLSCREEN_PHASES) { "$phase is not an executable Iris fullscreen phase" }
        withFullscreenOpenGlState {
            val graphicsByName = programs.associateBy { it.source.name }
            val computeByName = computes.associateBy { it.source.name }
            val orderedNames = cutoff?.programs(
                phase,
                fullscreenProgramNames(graphicsByName.keys, computeByName.keys),
            ) ?: fullscreenProgramNames(graphicsByName.keys, computeByName.keys)
            val view = if (phase == ShaderProgramPhase.SHADOW_COMPOSITE) {
                IrisShaderPackPlanner.SHADOW_VIEW
            } else {
                RenderViewId.MAIN
            }
            val dispatchSize = targets.size(view)
            orderedNames.forEach { name ->
                computeByName[name]?.let { program ->
                    executeCompute(program, dispatchSize)
                }
                graphicsByName[name]?.let { program ->
                    context.system.measureGpuPass("iris:program/${program.source.name}") {
                        customResources.memoryBarrier(program.source.resourceUsage.renderTargetImages.isNotEmpty())
                        targets.bindProgram(view, program.source)
                        program.shader.activate()
                        uploadFrameState(program.shader, IrisRenderStage.NONE)
                        targets.bindSamplers(
                            program.source,
                            program.shader.native,
                            customTextures,
                            customResources,
                            textureArrayLayouts[program.shader]?.physicalSlots?.toSet(),
                        )
                        uploadDrawState(program.shader, IrisDrawState.EMPTY)
                        drawFullscreen()
                        targets.finish(program.source)
                        customResources.memoryBarrier(program.source.resourceUsage.renderTargetImages.isNotEmpty())
                    }
                    fullscreenProgramExecutions[program.source.name] =
                        (fullscreenProgramExecutions[program.source.name] ?: 0L) + 1L
                }
            }
            targets.bindView(RenderViewId.MAIN)
        }
    }

    private fun executeSetupIfNeeded() {
        val requested = targets.size(RenderViewId.MAIN)
        if (requested == setupSize) return
        val programs = computePrograms[ShaderProgramPhase.SETUP].orEmpty()
        if (programs.isNotEmpty()) {
            withFullscreenOpenGlState {
                executeComputePrograms(ShaderProgramPhase.SETUP, requested)
                targets.bindView(RenderViewId.MAIN)
            }
        }
        setupSize = requested
    }

    private inline fun withFullscreenOpenGlState(block: () -> Unit) {
        val system = requireNotNull(context.system as? OpenGlRenderSystem)
        val state = IrisOpenGlStateSnapshot.capture(system)
        try {
            system.reset(
                depthTest = false,
                blending = false,
                faceCulling = false,
                depthMask = false,
            )
            system.polygonMode = PolygonModes.FILL
            block()
        } finally {
            targets.restoreHostBlend()
            state.restore(system)
        }
    }

    private fun executeComputePrograms(phase: ShaderProgramPhase, dispatchSize: Vec2i) {
        computePrograms[phase].orEmpty()
            .sortedBy { fullscreenProgramIndex(it.source.name) }
            .forEach { executeCompute(it, dispatchSize) }
    }

    private fun executeCompute(program: IrisComputeProgram, dispatchSize: Vec2i) {
        context.system.measureGpuPass("iris:compute/${program.source.name}") {
            executeComputeTimed(program, dispatchSize)
        }
    }

    private fun executeComputeTimed(program: IrisComputeProgram, dispatchSize: Vec2i) {
        val renderTargetImages = program.source.resourceUsage.renderTargetImages.isNotEmpty()
        customResources.memoryBarrier(renderTargetImages)
        program.shader.activate()
        uploadFrameState(program.shader, IrisRenderStage.NONE)
        targets.bindSamplers(
            program.bindingSource,
            program.shader.native,
            customTextures,
            customResources,
        )
        uploadDrawState(program.shader, IrisDrawState.EMPTY)
        when (val dispatch = program.source.dispatch) {
            is IrisComputeDispatch.Indirect -> customResources.dispatchIndirect(dispatch)
            else -> {
                val groups = dispatch.groups(dispatchSize.x, dispatchSize.y)
                val maximum = requireNotNull(maximumComputeGroups)
                require(groups.within(maximum)) {
                    "Iris compute program ${program.source.name} dispatches " +
                        "${groups.x}x${groups.y}x${groups.z} work groups, driver limit is " +
                        "${maximum.x}x${maximum.y}x${maximum.z}"
                }
                gl { glDispatchCompute(groups.x, groups.y, groups.z) }
            }
        }
        customResources.memoryBarrier(renderTargetImages)
        fullscreenProgramExecutions["compute:${program.source.name}"] =
            (fullscreenProgramExecutions["compute:${program.source.name}"] ?: 0L) + 1L
    }

    private fun uploadFrameState(shader: Shader, renderStage: IrisRenderStage) {
        val state = frameState ?: return
        val binding = FrameBinding(state.frameCounter, renderStage)
        if (frameBindings.matches(shader, binding)) return
        val uniforms = frameUniforms[shader].orEmpty()
        frameUniformUploads += state.uploadTo(shader.native, uniforms, plan.sunPathRotation)
        frameUniformUploads += customUniformEvaluator.uploadTo(shader.native, uniforms, state)
        frameUniformUploads += uploadHeldItems(shader.native, uniforms, state.heldItems)
        if (textureArrayUploadFrames[shader] != state.frameCounter) {
            val textureUploads = textureArrayState.uploadTo(shader.native, uniforms)
            frameUniformUploads += textureUploads
            textureArrayUniformUploads += textureUploads
            textureArrayUploadFrames[shader] = state.frameCounter
        }
        textureArrayLayouts[shader]?.bind(shader.native)
        val stageUploads = renderStage.uploadTo(shader.native, uniforms)
        frameUniformUploads += stageUploads
        if (stageUploads > 0 && sampleDiagnostic(renderStageDiagnosticEvents++)) {
            val program = programSources[shader]?.name ?: "unknown"
            val key = "$program/${renderStage.name.lowercase()}/${renderStage.shaderValue}"
            renderStageBinds[key] = (renderStageBinds[key] ?: 0L) + 1L
        }
        frameBindings.record(shader, binding)
    }

    private fun uploadHeldItems(
        native: NativeShader,
        uniforms: Set<String>,
        held: IrisHeldItemsFrameState,
    ): Int {
        val mainLight = held.mainLight(plan.oldHandLight)
        var uploads = 0
        for (uniform in uniforms) {
            if (uniform !in IrisHeldItemsFrameState.SUPPORTED_UNIFORMS || !native.hasUniform(uniform)) continue
            when (uniform) {
                "heldItemId" -> native.setInt(uniform, plan.idMaps.items[held.main.identifier])
                "heldItemId2" -> native.setInt(uniform, plan.idMaps.items[held.off.identifier])
                "heldBlockLightValue" -> native.setInt(uniform, mainLight.lightValue)
                "heldBlockLightValue2" -> native.setInt(uniform, held.off.lightValue)
                "heldBlockLightColor" -> native.setVec3f(uniform, mainLight.lightColor)
                "heldBlockLightColor2" -> native.setVec3f(uniform, held.off.lightColor)
            }
            uploads++
        }
        return uploads
    }

    private fun uploadDrawState(shader: Shader, state: IrisDrawState) {
        val uniforms = frameUniforms[shader] ?: return
        val native = shader.native
        val entityId = plan.idMaps.entities[state.entity]
        val itemId = plan.idMaps.items[state.item]
        val blockEntityId = plan.idMaps.block(state.blockEntity, missing = -1) { tag, blockState ->
            context.session.tags.isIn(BLOCK, tag, blockState.block) ||
                context.session.legacyTags.isIn(BLOCK, tag, blockState.block)
        }
        val hostBlendEnabled = context.system[RenderingCapabilities.BLENDING]
        val resolvedBlend = programSources[shader]?.blendOverride?.resolveProgram(
            hostBlendEnabled,
            context.system.blendFunction,
        ) ?: IrisResolvedBlendMode(hostBlendEnabled, context.system.blendFunction)
        val resolved = IrisResolvedDrawState(
            entityId = entityId,
            blockEntityId = blockEntityId,
            currentRenderedItemId = itemId,
            entityColor = state.entityColor,
            blendFunc = IrisResolvedDrawState.blend(
                enabled = resolvedBlend.enabled,
                state = resolvedBlend.function,
            ),
        )
        if (drawBindings.matches(shader, resolved)) return
        val uploads = resolved.uploadTo(native, uniforms)
        drawBindings.record(shader, resolved)
        if (uploads == 0) return
        drawUniformUploads += uploads
        if (!sampleDiagnostic(drawStateDiagnosticEvents++)) return
        val program = programSources[shader]?.name ?: "unknown"
        val key = "$program/entity=$entityId/blockEntity=$blockEntityId/item=$itemId"
        drawStateBinds[key] = (drawStateBinds[key] ?: 0L) + 1L
        if ("entityColor" in uniforms && native.hasUniform("entityColor")) {
            val color = state.entityColor
            val colorKey = "$program/rgba=" + listOf(color.x, color.y, color.z, color.w)
                .joinToString(",") { (it.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt().toString() }
            entityColorBinds[colorKey] = (entityColorBinds[colorKey] ?: 0L) + 1L
        }
    }

    private fun sampleDiagnostic(event: Long): Boolean = event and DIAGNOSTIC_SAMPLE_MASK == 0L

    private data class FrameBinding(
        val frame: Int,
        val stage: IrisRenderStage,
    )

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        (
            listOf(composite, diagnosticComposite) +
                fullscreenPrograms.values.flatten().map(IrisFullscreenProgram::shader) +
                computePrograms.values.flatten().map(IrisComputeProgram::shader) +
                terrain.values +
                scenes.values.map(IrisSceneProgram::shader) +
                shadowTerrain.values +
                shadowScenes.values.map(IrisSceneProgram::shader)
            ).distinct().forEach { shader ->
            try {
                if (shader.native.loaded) shader.unload()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        try {
            customTextures.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            customResources.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            targets.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val MAIN_VIEW_INDEX = 0
        private const val SHADOW_VIEW_INDEX = 1
        private const val HOST_FALLBACK_KIND = 0
        private const val UNKNOWN_FALLBACK_KIND = 1
        private const val FALLBACK_KIND_COUNT = 2
        private const val DIAGNOSTIC_SAMPLE_MASK = 0xFFL

        private fun IrisBlendMode.diagnostic(): String = when (this) {
            IrisBlendMode.Off -> "off"
            is IrisBlendMode.Enabled -> listOf(
                function.sourceRGB,
                function.destinationRGB,
                function.sourceAlpha,
                function.destinationAlpha,
            ).joinToString(" ") { it.name }
        }

        fun prepare(context: RenderContext, plan: ShaderPipelinePlan): IrisWorldShaderPipeline {
            val loaded = mutableListOf<Shader>()
            var targets: IrisOpenGlRenderTargets? = null
            var customTextures: IrisOpenGlCustomTextures? = null
            var customResources: IrisOpenGlCustomResources? = null
            try {
                val selectedPrograms = selectPrograms(plan)
                validateTargets(context, plan)
                val renderTargetImages = plan.programs.any {
                    it.resourceUsage.renderTargetImages.isNotEmpty()
                } || plan.computePrograms.any {
                    it.resourceUsage.renderTargetImages.isNotEmpty()
                }
                require(!renderTargetImages || GL.getCapabilities().OpenGL42) {
                    "Iris render-target images require OpenGL 4.2 image load/store"
                }
                require(plan.computePrograms.isEmpty() || GL.getCapabilities().OpenGL43) {
                    "Iris compute programs require OpenGL 4.3"
                }
                require(
                    plan.programs.none { it.tessellationControl != null } ||
                        GL.getCapabilities().OpenGL40,
                ) {
                    "Iris tessellation programs require OpenGL 4.0"
                }
                require(
                    plan.programs.none { it.blendOverride.requiresPerBufferBlending } ||
                        GL.getCapabilities().irisPerBufferBlending,
                ) {
                    "Iris per-buffer blend directives require OpenGL 4.0 or ARB_draw_buffers_blend"
                }
                targets = IrisOpenGlRenderTargets(context, plan.buffers).also(IrisOpenGlRenderTargets::prepare)
                customTextures = IrisOpenGlCustomTextures(context, plan.textures)
                    .also(IrisOpenGlCustomTextures::prepare)
                customResources = IrisOpenGlCustomResources(context, plan.customResources)
                    .also { it.prepare(targets.size(RenderViewId.MAIN)) }
                val arrayLayouts = IrisTextureArrayLayouts.capture(context.textures)
                val terrainShaders = selectedPrograms.terrain.values.distinct().associateWith { source ->
                    IrisTerrainShader(context.native(source, plan, textureLayout = arrayLayouts.terrain)).also {
                        it.load()
                        loaded += it
                    }
                }
                val terrain = selectedPrograms.terrain.mapValues { (_, source) -> terrainShaders.getValue(source) }
                var nextSceneDiagnosticId = 0
                fun compileScenes(selectedPrograms: List<SelectedSceneProgram>): Map<IrisSceneProgramKey, IrisSceneProgram> =
                    buildMap {
                        selectedPrograms.forEach { selected ->
                            val variant = "${selected.bridge.vertexAbi.name.lowercase()}-" +
                                selected.bridge.stateAbi.name.lowercase()
                            val layout = arrayLayouts.scene(selected.bridge.stateAbi)
                            val native = context.native(
                                selected.source,
                                plan,
                                variant,
                                layout,
                                IrisAlphaTestDefaults.scene(selected.source, selected.bridge),
                            )
                            native.defines["MINOSOFT_VERTEX_ABI_${selected.bridge.vertexAbi.name}"] = ""
                            native.defines["MINOSOFT_STATE_ABI_${selected.bridge.stateAbi.name}"] = ""
                            if (selected.bridge.stateAbi in SKELETAL_STATES) {
                                native.defines["TRANSFORMS"] = SkeletalManager.MAX_TRANSFORMS
                            }
                            val shader = object : Shader(native) {}
                            try {
                                shader.load()
                            } catch (error: Exception) {
                                if (error !is ShaderLoadingException && error !is ShaderLinkingException) {
                                    throw error
                                }
                                val lines = error.message.orEmpty().lineSequence()
                                val reason = lines.firstOrNull {
                                    it.trimStart().startsWith("ERROR:")
                                } ?: lines.drop(1).firstOrNull { it.isNotBlank() }
                                    ?: lines.firstOrNull().orEmpty()
                                Log.log(LogMessageType.RENDERING, LogLevels.WARN) {
                                    "IRIS_SCENE_VARIANT_FALLBACK program=${selected.source.name} " +
                                        "vertex=${selected.bridge.vertexAbi.name} " +
                                        "state=${selected.bridge.stateAbi.name} reason=" +
                                        reason.trim()
                                }
                                return@forEach
                            }
                            loaded += shader
                            put(
                                IrisSceneProgramKey(
                                    selected.source.name,
                                    selected.bridge.vertexAbi,
                                    selected.bridge.stateAbi,
                                ),
                                IrisSceneProgram(nextSceneDiagnosticId++, selected.source, selected.bridge, shader),
                            )
                        }
                    }
                val scenes = compileScenes(selectedPrograms.scenes)
                val fullscreenPrograms = selectedPrograms.fullscreen.mapValues { (_, sources) ->
                    sources.map { source ->
                        val textureLayout = arrayLayouts.all.takeIf { "uTextures" in source.uniforms }
                        IrisFullscreenProgram(
                            source,
                            object : Shader(context.native(source, plan, textureLayout = textureLayout)) {}.also {
                                it.load()
                                loaded += it
                            },
                        )
                    }
                }
                val computePrograms = plan.computePrograms.groupBy(IrisComputeProgramSource::phase)
                    .mapValues { (_, sources) ->
                        sources.map { source ->
                            val bindingSource = source.bindingSource()
                            val native = context.system.shader.createCompute(
                                NativeShaderSource(
                                    ResourceLocation("iris", "shaderpack/${source.name}.csh"),
                                    source.source,
                                ),
                            ).also {
                                it.defines.putAll(plan.preprocessorDefines)
                                it.defines["IS_IRIS"] = ""
                                it.defines["IRIS_FEATURE_CUSTOM_IMAGES"] = ""
                            }
                            IrisComputeProgram(
                                source,
                                bindingSource,
                                object : Shader(native) {}.also {
                                    it.load()
                                    loaded += it
                                },
                            )
                        }
                    }
                val compositeSource = selectedPrograms.composite
                val compositeTextureLayout = arrayLayouts.all.takeIf {
                    "uTextures" in compositeSource.uniforms
                }
                val presentationSampler = requireNotNull(
                    compositeSource.resourceUsage.sampledBuffers.keys.firstOrNull(),
                ) {
                    "Iris presentation program ${compositeSource.name} must sample a declared render buffer"
                }
                val presentationBuffer = requireNotNull(
                    compositeSource.resourceUsage.sampledBuffers[presentationSampler],
                ) { "Iris presentation sampler $presentationSampler has no render-buffer binding" }
                val composite = object : FramebufferShader(
                    context.native(compositeSource, plan, textureLayout = compositeTextureLayout),
                    presentationSampler,
                ) {
                    // Iris final programs replace the scene color. They are
                    // executed with blending disabled; packs may legally
                    // expose an RGB output with no meaningful alpha channel.
                    override val blending: Boolean = false

                    // The Iris target manager has already bound every active
                    // final sampler. Binding Minosoft's host framebuffer here
                    // would overwrite the first Iris sampler on texture unit 0.
                    override val bindFramebufferTexture: Boolean = false
                }.also {
                    it.load()
                    loaded += it
                }
                val diagnosticComposite = object : FramebufferShader(
                    context.native(SYNTHETIC_FINAL, plan),
                    "colortex0",
                ) {
                    override val blending: Boolean = false
                    override val bindFramebufferTexture: Boolean = false
                }.also {
                    it.load()
                    loaded += it
                }
                val shadowShaders = selectedPrograms.shadowTerrain.values.distinct().associateWith { source ->
                    IrisShadowTerrainShader(context.native(source, plan, textureLayout = arrayLayouts.terrain)).also {
                        it.load()
                        loaded += it
                    }
                }
                val shadowTerrain = selectedPrograms.shadowTerrain.mapValues { (_, source) ->
                    shadowShaders.getValue(source)
                }
                val shadowScenes = compileScenes(selectedPrograms.shadowScenes)
                val frameUniforms = buildMap {
                    terrain.forEach { (material, shader) ->
                        put(shader, selectedPrograms.terrain.getValue(material).uniforms)
                    }
                    scenes.values.forEach { selected -> put(selected.shader, selected.source.uniforms) }
                    fullscreenPrograms.values.flatten().forEach { selected ->
                        put(selected.shader, selected.source.uniforms)
                    }
                    computePrograms.values.flatten().forEach { selected ->
                        put(selected.shader, selected.source.uniforms)
                    }
                    put(composite, compositeSource.uniforms)
                    shadowTerrain.forEach { (material, shader) ->
                        put(shader, selectedPrograms.shadowTerrain.getValue(material).uniforms)
                    }
                    shadowScenes.values.forEach { selected -> put(selected.shader, selected.source.uniforms) }
                }
                val programSources = buildMap {
                    terrain.forEach { (material, shader) ->
                        put(shader, selectedPrograms.terrain.getValue(material))
                    }
                    scenes.values.forEach { selected -> put(selected.shader, selected.source) }
                    fullscreenPrograms.values.flatten().forEach { selected ->
                        put(selected.shader, selected.source)
                    }
                    computePrograms.values.flatten().forEach { selected ->
                        put(selected.shader, selected.bindingSource)
                    }
                    put(composite, compositeSource)
                    shadowTerrain.forEach { (material, shader) ->
                        put(shader, selectedPrograms.shadowTerrain.getValue(material))
                    }
                    shadowScenes.values.forEach { selected -> put(selected.shader, selected.source) }
                }
                val textureArrayLayouts = buildMap {
                    terrain.values.forEach { put(it, arrayLayouts.terrain) }
                    shadowTerrain.values.forEach { put(it, arrayLayouts.terrain) }
                    scenes.values.forEach { selected ->
                        put(selected.shader, arrayLayouts.scene(selected.bridge.stateAbi))
                    }
                    shadowScenes.values.forEach { selected ->
                        put(selected.shader, arrayLayouts.scene(selected.bridge.stateAbi))
                    }
                    fullscreenPrograms.values.flatten().forEach { selected ->
                        if ("uTextures" in selected.source.uniforms) {
                            put(selected.shader, arrayLayouts.all)
                        }
                    }
                    if ("uTextures" in compositeSource.uniforms) {
                        put(composite, arrayLayouts.all)
                    }
                }
                return IrisWorldShaderPipeline(
                    context,
                    plan,
                    terrain,
                    selectedPrograms.terrain,
                    scenes,
                    composite,
                    compositeSource,
                    presentationBuffer,
                    diagnosticComposite,
                    shadowTerrain,
                    selectedPrograms.shadowTerrain,
                    selectedPrograms.shadowTerrain.mapKeys { (material, _) -> material.name.lowercase() }
                        .mapValues { (_, source) -> source.name },
                    shadowScenes,
                    fullscreenPrograms,
                    computePrograms,
                    targets,
                    customTextures,
                    customResources,
                    frameUniforms,
                    programSources,
                    textureArrayLayouts,
                )
            } catch (failure: Throwable) {
                try {
                    customTextures?.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                try {
                    customResources?.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                try {
                    targets?.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                loaded.asReversed().forEach { shader ->
                    try {
                        if (shader.native.loaded) shader.unload()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }

        private data class SelectedPrograms(
            val terrain: Map<TerrainMaterialClass, ShaderProgramSource>,
            val scenes: List<SelectedSceneProgram>,
            val composite: ShaderProgramSource,
            val fullscreen: Map<ShaderProgramPhase, List<ShaderProgramSource>>,
            val shadowTerrain: Map<TerrainMaterialClass, ShaderProgramSource>,
            val shadowScenes: List<SelectedSceneProgram>,
        )

        private fun IrisComputeProgramSource.bindingSource() = ShaderProgramSource(
            name = "compute:$name",
            phase = phase,
            vertex = "#version 330 core\nvoid main(){}",
            fragment = "#version 330 core\nvoid main(){}",
            uniforms = uniforms,
            samplers = samplers,
            resourceUsage = resourceUsage,
        )

        private data class ComputeGroups(val x: Int, val y: Int, val z: Int)

        private fun ComputeGroups.within(maximum: ComputeGroups): Boolean =
            x <= maximum.x && y <= maximum.y && z <= maximum.z

        private fun fullscreenProgramIndex(name: String): Int =
            name.takeLastWhile(Char::isDigit).toIntOrNull() ?: 0

        private fun fullscreenProgramNames(graphics: Set<String>, compute: Set<String>): List<String> =
            (graphics + compute).sortedWith(compareBy<String>({ fullscreenProgramIndex(it) }, { it }))

        private fun IrisComputeDispatch.groups(width: Int, height: Int): ComputeGroups = when (this) {
            is IrisComputeDispatch.Absolute -> ComputeGroups(x, y, z)
            is IrisComputeDispatch.Relative -> ComputeGroups(
                ceil(width * widthScale / localSizeX).toInt().coerceAtLeast(1),
                ceil(height * heightScale / localSizeY).toInt().coerceAtLeast(1),
                1,
            )
            is IrisComputeDispatch.Indirect ->
                error("Indirect Iris compute dispatches do not have CPU-visible work groups")
        }

        private data class SelectedSceneProgram(
            val source: ShaderProgramSource,
            val bridge: SceneProgramBridge,
        )

        private fun selectPrograms(plan: ShaderPipelinePlan): SelectedPrograms {
            val terrainCandidates = NEAR_TERRAIN_MATERIALS
                .flatMap(IrisProgramFallbacks::terrain)
                .toSet()
            val terrain = plan.programs.filter {
                it.name in terrainCandidates &&
                    (it.sceneBridges.isEmpty() || "// minosoft:terrain_bridge" in it.vertex)
            }
            val finals = plan.programs.filter { it.phase == ShaderProgramPhase.FINAL }
            val fullscreen = plan.programs.filter { it.phase in FULLSCREEN_PHASES }
            val presentation = finals.singleOrNull() ?: SYNTHETIC_FINAL
            val shadows = plan.programs.filter { it.phase == ShaderProgramPhase.SHADOW }
            val scenes = plan.programs.filter {
                it.sceneBridges.isNotEmpty() &&
                    it.name != "gbuffers_water" &&
                    (
                        it.phase in SCENE_PHASES ||
                            it.phase == ShaderProgramPhase.TERRAIN ||
                            it.phase == ShaderProgramPhase.DISTANT_HORIZONS
                        )
            }
            val unsupported =
                plan.programs - terrain.toSet() - finals.toSet() - fullscreen.toSet() - shadows.toSet() - scenes.toSet()
            val terrainByMaterial = NEAR_TERRAIN_MATERIALS.associateWith { material ->
                IrisProgramFallbacks.select(terrain, IrisProgramFallbacks.terrain(material))
            }
            val failures = buildList {
                terrainByMaterial.forEach { (material, program) ->
                    if (program == null) {
                        add("$material terrain has no executable fallback in ${IrisProgramFallbacks.terrain(material)}")
                    }
                }
                if (finals.size > 1) {
                    add("allows at most one final program; found ${finals.describe()}")
                }
                if (unsupported.isNotEmpty()) {
                    add("does not execute ${unsupported.describe()}")
                }
                scenes.filterNot { program ->
                    IrisProgramFallbacks.supportsSceneProgram(program.name) ||
                        (
                            program.name == "gbuffers_entities_glowing" &&
                                scenes.any { it.name == "gbuffers_entities" }
                            )
                }.forEach { program ->
                    add("${program.name} has no retained scene-producer route")
                }
                (terrain + presentation + fullscreen + shadows).forEach { program ->
                    val bridgedUniforms =
                        program.sceneBridges.flatMapTo(mutableSetOf(), SceneProgramBridge::uniforms)
                    val executionPhase = when (program) {
                        in terrain -> ShaderProgramPhase.TERRAIN
                        in shadows -> ShaderProgramPhase.SHADOW
                        else -> program.phase
                    }
                    val unbound = program.uniforms -
                        SUPPORTED_UNIFORMS.getValue(executionPhase) -
                        IrisFrameState.SUPPORTED_UNIFORMS -
                        IrisHeldItemsFrameState.SUPPORTED_UNIFORMS -
                        IrisResolvedDrawState.SUPPORTED_UNIFORMS -
                        IrisRenderStage.SUPPORTED_UNIFORMS -
                        IrisTextureArrayState.SUPPORTED_UNIFORMS -
                        plan.customUniforms.uniforms -
                        program.resourceUsage.sampledBuffers.keys -
                        program.resourceUsage.sampledCustomTextures.keys -
                        program.resourceUsage.sampledCustomImages.keys -
                        program.resourceUsage.renderTargetImages.keys -
                        program.resourceUsage.customImages -
                        bridgedUniforms
                    if (unbound.isNotEmpty()) {
                        add("${program.name} has unbound uniforms/samplers ${unbound.sorted()}")
                    }
                }
                plan.computePrograms.forEach { program ->
                    val unbound = program.uniforms -
                        SUPPORTED_UNIFORMS.getValue(program.phase) -
                        IrisFrameState.SUPPORTED_UNIFORMS -
                        IrisHeldItemsFrameState.SUPPORTED_UNIFORMS -
                        IrisResolvedDrawState.SUPPORTED_UNIFORMS -
                        IrisRenderStage.SUPPORTED_UNIFORMS -
                        IrisTextureArrayState.SUPPORTED_UNIFORMS -
                        plan.customUniforms.uniforms -
                        program.resourceUsage.sampledBuffers.keys -
                        program.resourceUsage.sampledCustomTextures.keys -
                        program.resourceUsage.sampledCustomImages.keys -
                        program.resourceUsage.renderTargetImages.keys -
                        program.resourceUsage.customImages
                    if (unbound.isNotEmpty()) {
                        add("compute:${program.name} has unbound uniforms/samplers ${unbound.sorted()}")
                    }
                }
                (scenes + shadows.filter { it.sceneBridges.isNotEmpty() }).forEach { program ->
                    program.sceneBridges.forEach { bridge ->
                        val supported = SUPPORTED_SCENE_UNIFORMS[bridge.stateAbi]
                        if (supported == null) {
                            add("${program.name} has no executable bridge for ${bridge.vertexAbi}/${bridge.stateAbi}")
                        } else if (
                            program.phase == ShaderProgramPhase.SHADOW &&
                            !supported.containsAll(bridge.uniforms)
                        ) {
                            add(
                                "${program.name} ${bridge.vertexAbi}/${bridge.stateAbi} shadow bridge uniforms " +
                                    "${bridge.uniforms.sorted()} are not a subset of ${supported.sorted()}",
                            )
                        } else if (
                            program.phase != ShaderProgramPhase.SHADOW &&
                            bridge.uniforms != supported
                        ) {
                            add(
                                "${program.name} ${bridge.vertexAbi}/${bridge.stateAbi} bridge uniforms " +
                                    "${bridge.uniforms.sorted()} do not match ${supported.sorted()}",
                            )
                        }
                    }
                    val bridgedUniforms = program.sceneBridges.flatMapTo(mutableSetOf(), SceneProgramBridge::uniforms)
                    val expandedBridgeUniforms = if ("fog" in bridgedUniforms) {
                        bridgedUniforms + HOST_FOG_UNIFORMS
                    } else {
                        bridgedUniforms
                    }
                    val unbound = program.uniforms -
                        expandedBridgeUniforms -
                        IrisFrameState.SUPPORTED_UNIFORMS -
                        IrisHeldItemsFrameState.SUPPORTED_UNIFORMS -
                        IrisResolvedDrawState.SUPPORTED_UNIFORMS -
                        IrisRenderStage.SUPPORTED_UNIFORMS -
                        IrisTextureArrayState.SUPPORTED_UNIFORMS -
                        plan.customUniforms.uniforms -
                        program.resourceUsage.sampledBuffers.keys -
                        program.resourceUsage.sampledCustomTextures.keys -
                        program.resourceUsage.sampledCustomImages.keys -
                        program.resourceUsage.renderTargetImages.keys -
                        program.resourceUsage.customImages
                    if (unbound.isNotEmpty()) {
                        add("${program.name} has unbound uniforms/samplers ${unbound.sorted()}")
                    }
                }
            }
            require(failures.isEmpty()) {
                "Unsupported Iris shader-pack contract: ${failures.joinToString("; ")}"
            }
            val shadowTerrainByMaterial = if (
                shadows.isEmpty() ||
                !plan.shadowDirectives.enabled ||
                !plan.shadowDirectives.terrain
            ) {
                emptyMap()
            } else {
                buildList {
                    add(TerrainMaterialClass.OPAQUE)
                    add(TerrainMaterialClass.CUTOUT)
                    if (plan.shadowDirectives.translucentTerrain) {
                        add(TerrainMaterialClass.TRANSLUCENT)
                    }
                }.associateWith { material ->
                    IrisProgramFallbacks.select(shadows, IrisProgramFallbacks.shadow(material))
                }
            }
            val shadowScenePrograms = shadows
                .flatMap { source -> source.sceneBridges.map { bridge -> SelectedSceneProgram(source, bridge) } }
                .groupBy { it.bridge.vertexAbi to it.bridge.stateAbi }
                .values
                .map { candidates ->
                    candidates.minBy { selected ->
                        IrisProgramFallbacks.shadowScene().indexOf(selected.source.name)
                            .takeIf { it >= 0 }
                            ?: Int.MAX_VALUE
                    }
                }
            val shadowFailures = buildList {
                shadowTerrainByMaterial.forEach { (material, program) ->
                    if (program == null) {
                        add("$material shadow terrain has no executable fallback in ${IrisProgramFallbacks.shadow(material)}")
                    }
                }
            }
            require(shadowFailures.isEmpty()) {
                "Unsupported Iris shader-pack contract: ${shadowFailures.joinToString("; ")}"
            }
            return SelectedPrograms(
                terrain = terrainByMaterial.mapValues { (_, source) -> requireNotNull(source) },
                scenes = scenes.flatMap { source ->
                    source.sceneBridges.map { bridge -> SelectedSceneProgram(source, bridge) }
                },
                composite = presentation,
                fullscreen = fullscreen.groupBy(ShaderProgramSource::phase).mapValues { (_, programs) ->
                    programs.sortedBy(::programSequence)
                },
                shadowTerrain = shadowTerrainByMaterial.mapValues { (_, source) -> requireNotNull(source) },
                shadowScenes = shadowScenePrograms,
            )
        }

        internal fun validateProgramContract(plan: ShaderPipelinePlan) {
            selectPrograms(plan)
        }

        internal fun fullscreenProgramOrder(
            plan: ShaderPipelinePlan,
            phase: ShaderProgramPhase,
        ): List<String> = selectPrograms(plan).fullscreen[phase].orEmpty().map(ShaderProgramSource::name)

        internal fun fullscreenExecutionOrder(
            plan: ShaderPipelinePlan,
            phase: ShaderProgramPhase,
        ): List<String> {
            val graphics = selectPrograms(plan).fullscreen[phase].orEmpty().mapTo(linkedSetOf()) { it.name }
            val compute = plan.computePrograms.filter { it.phase == phase }.mapTo(linkedSetOf()) { it.name }
            return fullscreenProgramNames(graphics, compute).flatMap { name ->
                buildList {
                    if (name in compute) add("compute:$name")
                    if (name in graphics) add(name)
                }
            }
        }

        private fun List<ShaderProgramSource>.describe(): String =
            if (isEmpty()) "none" else joinToString(prefix = "[", postfix = "]") { "${it.name}=${it.phase}" }

        private val SUPPORTED_UNIFORMS = mapOf(
            ShaderProgramPhase.SETUP to setOf("uTexture"),
            ShaderProgramPhase.TERRAIN to setOf(
                "uViewProjectionMatrix",
                "uCameraPosition",
                "uFogStart",
                "uFogDistance",
                "uFogColor",
                "uFogFlags",
                "uPlayerLightPosition",
                "uPlayerLightIntensity",
                "uPlayerLightRadius",
                "uTextures",
            ),
            ShaderProgramPhase.COMPOSITE to setOf("uTexture", "uTextures"),
            ShaderProgramPhase.BEGIN to setOf("uTexture", "uTextures"),
            ShaderProgramPhase.SHADOW_COMPOSITE to setOf("uTexture", "uTextures"),
            ShaderProgramPhase.PREPARE to setOf("uTexture", "uTextures"),
            ShaderProgramPhase.DEFERRED to setOf("uTexture", "uTextures"),
            ShaderProgramPhase.FINAL to setOf("uTexture", "uTextures"),
            ShaderProgramPhase.SHADOW to setOf(
                "uViewProjectionMatrix",
                "uCameraPosition",
                "uFogStart",
                "uFogDistance",
                "uFogColor",
                "uFogFlags",
                "uPlayerLightPosition",
                "uPlayerLightIntensity",
                "uPlayerLightRadius",
                "uTextures",
            ),
        )
        private val SCENE_PHASES = setOf(
            ShaderProgramPhase.BLOCK,
            ShaderProgramPhase.ITEM,
            ShaderProgramPhase.ENTITY,
            ShaderProgramPhase.PARTICLE,
            ShaderProgramPhase.SKY,
            ShaderProgramPhase.WEATHER,
            ShaderProgramPhase.HAND,
            ShaderProgramPhase.BASIC,
        )
        private val FULLSCREEN_PHASES = setOf(
            ShaderProgramPhase.BEGIN,
            ShaderProgramPhase.SHADOW_COMPOSITE,
            ShaderProgramPhase.PREPARE,
            ShaderProgramPhase.DEFERRED,
            ShaderProgramPhase.COMPOSITE,
        )
        private val SYNTHETIC_FINAL = ShaderProgramSource(
            name = "minosoft_final",
            phase = ShaderProgramPhase.FINAL,
            vertex = """
                #version 330 core
                layout (location = 0) in vec2 vinPosition;
                layout (location = 1) in vec2 vinUV;
                out vec2 finUV;
                void main() {
                    gl_Position = vec4(vinPosition, 0.0, 1.0);
                    finUV = vinUV;
                }
            """.trimIndent(),
            fragment = """
                #version 330 core
                in vec2 finUV;
                out vec4 foutColor;
                uniform sampler2D colortex0;
                void main() {
                    foutColor = texture(colortex0, finUV);
                }
            """.trimIndent(),
            uniforms = setOf("colortex0"),
            samplers = setOf("colortex0"),
            resourceUsage = ShaderProgramResourceUsage(
                sampledBuffers = mapOf(
                    "colortex0" to ShaderBufferId(ShaderBufferKind.COLORTEX, 0),
                ),
            ),
        )
        private fun programSequence(program: ShaderProgramSource): Int =
            program.name.takeLastWhile(Char::isDigit).toIntOrNull() ?: 0
        private val SUPPORTED_SCENE_UNIFORMS = mapOf(
            SceneStateAbi.COLOR to setOf("uViewProjectionMatrix"),
            SceneStateAbi.LIGHT_COLOR to setOf("uLightMapBuffer", "uViewProjectionMatrix"),
            SceneStateAbi.BEACON_BEAM to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uMatrix",
                "uTextureOffset",
            ),
            SceneStateAbi.LIGHTNING to setOf(
                "uViewProjectionMatrix",
                "uMatrix",
            ),
            SceneStateAbi.GENERIC_TEXTURE to setOf("uTextures", "uViewProjectionMatrix"),
            SceneStateAbi.GENERIC_TEXTURE_2D to setOf("uTextures"),
            SceneStateAbi.ENTITY_FLAME to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uMatrix",
            ),
            SceneStateAbi.BLOCK to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uMatrix",
                "uTintColor",
                "uOutlineColor",
            ),
            SceneStateAbi.FLASHING_BLOCK to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uMatrix",
                "uTintColor",
                "uOutlineColor",
                "uFlashColor",
                "uFlashProgress",
            ),
            SceneStateAbi.BILLBOARD_TEXT to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uMatrix",
                "uTintColor",
                "uOutlineColor",
            ),
            SceneStateAbi.SKELETAL_TINTED to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uSkeletalBuffer",
                "uTintColor",
                "uOutlineColor",
            ),
            SceneStateAbi.SKELETAL_LIGHTMAP to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uSkeletalBuffer",
                "uLight",
                "uLightMapBuffer",
                "uPlayerLightPosition",
                "uPlayerLightIntensity",
                "uPlayerLightRadius",
            ),
            SceneStateAbi.PLAYER to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uSkeletalBuffer",
                "uIndexLayer",
                "uTintColor",
                "uSkinParts",
                "uInflate",
                "uHideBase",
                "uFeaturePart",
                "uAllowBaseTransparency",
                "uGlint",
                "uGlintTexture",
                "uGlintTime",
            ),
            SceneStateAbi.ARM to setOf(
                "uTextures",
                "uTexture",
                "uTintColor",
                "uSkinParts",
                "uTransform",
            ),
            SceneStateAbi.HELD_ITEM to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uMatrix",
                "uTintColor",
            ),
            SceneStateAbi.PARTICLE to setOf(
                "uTextures",
                "uLightMapBuffer",
                "uViewProjectionMatrix",
                "fog",
                "uCameraPosition",
                "uPlayerLightPosition",
                "uPlayerLightIntensity",
                "uPlayerLightRadius",
                "uCameraRight",
                "uCameraUp",
            ),
            SceneStateAbi.SKY_COLOR to setOf("uSkyViewProjectionMatrix", "uSkyColor"),
            SceneStateAbi.SKY_TEXTURE to setOf(
                "uTextures",
                "uSkyViewProjectionMatrix",
                "uTexture",
                "uTintColor",
            ),
            SceneStateAbi.CLOUD to setOf(
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uCloudsColor",
                "uOffset",
                "uYOffset",
            ),
            SceneStateAbi.PLANET to setOf("uMatrix", "uTintColor", "uTextures"),
            SceneStateAbi.SUN_SCATTER to setOf("uScatterMatrix", "uSunPosition", "uIntensity"),
            SceneStateAbi.WEATHER to setOf("uTextures", "uIntensity", "uOffset", "uTexture"),
            SceneStateAbi.WORLD_BORDER to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "fog",
                "uTintColor",
                "uTexture",
                "uTextureOffset",
            ),
            SceneStateAbi.DAMAGED_BLOCK to setOf(
                "uTextures",
                "uLightMapBuffer",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "uPlayerLightPosition",
                "uPlayerLightIntensity",
                "uPlayerLightRadius",
                "fog",
                "uTexture",
            ),
            SceneStateAbi.TERRAIN to setOf(
                "uTextures",
                "uViewProjectionMatrix",
                "uCameraPosition",
                "uFogStart",
                "uFogDistance",
                "uFogColor",
                "uFogFlags",
                "uPlayerLightPosition",
                "uPlayerLightIntensity",
                "uPlayerLightRadius",
            ),
            SceneStateAbi.DISTANT_TERRAIN to setOf("uViewProjectionMatrix", "uPageOffset"),
        )
        private val HOST_FOG_UNIFORMS = setOf("uFogStart", "uFogDistance", "uFogColor", "uFogFlags")
        private val SKELETAL_STATES = setOf(
            SceneStateAbi.SKELETAL_TINTED,
            SceneStateAbi.SKELETAL_LIGHTMAP,
            SceneStateAbi.PLAYER,
        )
        private fun validateTargets(context: RenderContext, plan: ShaderPipelinePlan): RenderTargetDescriptor? {
            val main = plan.resources.targets.singleOrNull { it.id == RenderResourceId("minosoft:main-world") }
                ?: throw IllegalArgumentException("Iris plan must declare the host main-world target")
            val host = context.framebuffer.main.descriptor
            require(main.size == host.size && main.samples == host.samples && main.depth == host.depth) {
                "Iris main target does not match the selected host target"
            }
            require(main.colorAttachments.size == 1 && main.colorAttachments.single().format == RenderColorFormat.RGBA8) {
                "Iris main target currently requires one RGBA8 color attachment"
            }

            val shadow = plan.resources.targets.singleOrNull { it.id == RenderResourceId("iris:shadow") }
            if (shadow == null) {
                require(IrisShaderPackPlanner.SHADOW_VIEW !in plan.views) {
                    "Iris shadow view has no declared target"
                }
                return null
            }
            require(IrisShaderPackPlanner.SHADOW_VIEW in plan.views) {
                "Iris shadow target is declared without a shadow view"
            }
            require(shadow.samples == 1 && shadow.depth == RenderDepthFormat.DEPTH24) {
                "Iris shadow target currently requires one sample and DEPTH24"
            }
            require(shadow.colorAttachments.size == 1 && shadow.colorAttachments.single().format == RenderColorFormat.RGBA8) {
                "Iris shadow target currently requires one RGBA8 color attachment"
            }
            require(shadow.size is RenderTargetSize.Fixed) { "Iris shadow target must have fixed dimensions" }
            return shadow
        }

        private fun RenderContext.native(
            program: ShaderProgramSource,
            plan: ShaderPipelinePlan,
            variant: String? = null,
            textureLayout: IrisTextureArrayLayout? = null,
            alphaTest: IrisAlphaTest? = program.alphaTest,
        ): NativeShader {
            val textureSlots = textureLayout?.physicalSlots ?: textures.shaderTextureSizes()
                .mapIndexedNotNull { index, size -> index.takeIf { size.x > 0 && size.y > 0 } }
                .ifEmpty { listOf(0) }
            fun specialize(source: String) =
                IrisLegacyShaderTransformer.relocateUnconditionalExtensions(
                    IrisLegacyShaderTransformer.specializeTextureArrays(
                        source,
                        textureSlots,
                        textureLayout?.companionSlots ?: emptyList(),
                    ),
                )
            fun runtimeSource(raw: String, inspection: String?): String {
                if (
                    "MINOSOFT_STATE_ABI_" in raw ||
                    "MINOSOFT_VERTEX_ABI_" in raw
                ) {
                    return raw
                }
                return inspection ?: raw
            }

            return system.shader.createGraphics(
                vertex = NativeShaderSource(
                    ResourceLocation("iris", "shaderpack/${program.name}${variant?.let { "-$it" }.orEmpty()}.vsh"),
                    specialize(runtimeSource(program.vertex, program.inspectionVertex)),
                ),
                tessellationControl = program.tessellationControl?.let { source ->
                    NativeShaderSource(
                        ResourceLocation(
                            "iris",
                            "shaderpack/${program.name}${variant?.let { "-$it" }.orEmpty()}.tcs",
                        ),
                        specialize(runtimeSource(source, program.inspectionTessellationControl)),
                    )
                },
                tessellationEvaluation = program.tessellationEvaluation?.let { source ->
                    NativeShaderSource(
                        ResourceLocation(
                            "iris",
                            "shaderpack/${program.name}${variant?.let { "-$it" }.orEmpty()}.tes",
                        ),
                        specialize(runtimeSource(source, program.inspectionTessellationEvaluation)),
                    )
                },
                geometry = (program.inspectionGeometry ?: program.geometry)?.let {
                    NativeShaderSource(
                        ResourceLocation(
                            "iris",
                            "shaderpack/${program.name}${variant?.let { "-$it" }.orEmpty()}.gsh",
                        ),
                        specialize(it),
                    )
                },
                fragment = NativeShaderSource(
                    ResourceLocation("iris", "shaderpack/${program.name}${variant?.let { "-$it" }.orEmpty()}.fsh"),
                    specialize(
                        IrisLegacyShaderTransformer.alphaTest(
                            program.inspectionFragment ?: program.fragment,
                            alphaTest,
                        ),
                    ),
                ),
                patchVertices = program.tessellationPatchVertices,
            ).also {
                it.defines.putAll(plan.preprocessorDefines)
                it.defines["IS_IRIS"] = ""
                it.defines["IRIS_FEATURE_ENTITY_TRANSLUCENT"] = ""
                if (plan.customResources.images.isNotEmpty()) {
                    it.defines["IRIS_FEATURE_CUSTOM_IMAGES"] = ""
                }
            }
        }
    }
}

/** Identity-keyed binding state that is committed only after realization succeeds. */
internal class IrisIdentityBindingCache<K : Any, V> {
    private val values = IdentityHashMap<K, V>()

    fun matches(key: K, value: V): Boolean = values[key] == value

    fun record(key: K, value: V) {
        values[key] = value
    }

    fun invalidate(key: K) {
        values.remove(key)
    }
}

/** Tracks complete host-uniform snapshots independently for source/target identities. */
internal class IrisIdentityRevisionCache<S : Any, T : Any> {
    private val revisions = IdentityHashMap<T, IdentityHashMap<S, Long>>()

    fun requiresSync(source: S, target: T, revision: Long, uploadInProgress: Boolean): Boolean {
        val recorded = revisions[target]?.get(source)
        if (recorded == revision) return false
        if (uploadInProgress && recorded == revision - 1L) return false
        return true
    }

    fun record(source: S, target: T, revision: Long) {
        revisions.getOrPut(target) { IdentityHashMap() }[source] = revision
    }
}
