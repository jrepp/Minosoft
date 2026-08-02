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

package de.bixilon.minosoft.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class TerrainArchitectureBoundaryTest {
    @Test
    fun `terrain idle generation capture starts on the render thread`() {
        val source = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/debug/ClientDebugChannel.kt")
            .readText()
            .withoutCommentsAndLiterals()

        assertTrue(
            "onRenderAsync { flushTerrainIdle(request, body, it) }" in source,
            "Terrain flush-idle must capture and retire pipeline generations only from the render thread.",
        )
    }

    @Test
    fun `standalone terrain packages do not import Fabric adapters`() {
        val violations = mainSourceFiles()
            .map { it to it.readText() }
            .filter { (_, source) -> sourcePackage(source)?.isCoreTerrainPackage() == true }
            .flatMap { (file, source) ->
                FABRIC_IMPORT.findAll(source.withoutCommentsAndLiterals()).map { match ->
                    "${relative(file)}:${source.lineNumber(match.range.first)} ${match.value.trim()}"
                }
            }
            .sorted()

        assertTrue(
            violations.isEmpty(),
            "Standalone terrain packages must not import Fabric adapters:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `Fabric does not gain renderer or terrain provider ownership`() {
        val offenders = mainSourceFiles()
            .map { it to it.readText() }
            .filter { (_, source) -> sourcePackage(source)?.isFabricPackage() == true }
            .filter { (_, source) -> source.implementsRestrictedType() }
            .map { (file, _) -> relative(file) }
            .sorted()
        val newOffenders = offenders - LEGACY_FABRIC_RENDERER_FILES

        assertTrue(
            newOffenders.isEmpty(),
            "Fabric adapters must not gain renderer or terrain-provider ownership. " +
                "Move these implementations into standalone terrain/rendering packages:\n" +
            newOffenders.joinToString("\n"),
        )
    }

    @Test
    fun `Fabric does not regain neutral distant ownership`() {
        val offenders = mainSourceFiles()
            .map { it to it.readText() }
            .filter { (_, source) -> sourcePackage(source)?.isFabricPackage() == true }
            .flatMap { (file, source) ->
                source.declaredTypeNames()
                    .filter { it in NEUTRAL_DISTANT_TYPES }
                    .map { "${relative(file)}: $it" }
            }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "Fabric adapters must import distant data and renderer types from neutral terrain packages:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `core terrain owners do not claim foreign compatibility artifacts`() {
        val offenders = mainSourceFiles()
            .map { it to it.readText() }
            .filter { (_, source) -> sourcePackage(source)?.isCoreTerrainPackage() == true }
            .filter { (_, source) -> "DistantHorizons" in source || "distant-horizons" in source }
            .map { (file, _) -> relative(file) }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "Core terrain classes, owners, and passes must use neutral identities:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `headless terrain scheduler stays below application rendering`() {
        val offenders = mainSourceFiles()
            .map { it to it.readText() }
            .flatMap { (file, source) ->
                val packageName = sourcePackage(source)
                source.declaredTypeNames()
                    .filter { it in HEADLESS_SCHEDULER_TYPES }
                    .filterNot { packageName?.startsWith("$TERRAIN_PACKAGE.runtime.scheduling") == true }
                    .map { "${relative(file)}: $it" }
            }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "Terrain scheduler and mailbox types must remain in the dependency-clean terrain runtime:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `headless terrain storage stays below application rendering`() {
        val offenders = mainSourceFiles()
            .map { it to it.readText() }
            .flatMap { (file, source) ->
                val packageName = sourcePackage(source)
                source.declaredTypeNames()
                    .filter { it in HEADLESS_STORAGE_TYPES }
                    .filterNot { packageName?.startsWith("$TERRAIN_PACKAGE.runtime.storage") == true }
                    .map { "${relative(file)}: $it" }
            }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "Terrain allocation, storage, batching, and publication types must remain in the " +
                "dependency-clean terrain runtime:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `production near geometry dispatch is snapshot only`() {
        val source = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/chunk/mesher/ChunkMesher.kt")
            .readText()
            .withoutCommentsAndLiterals()

        assertTrue(
            "solid.mesh(snapshot" in source && "fluid.mesh(snapshot" in source,
            "Production near solid and fluid geometry must be dispatched from the detached snapshot.",
        )
        assertTrue(
            "solid.mesh(section" !in source && "fluid.mesh(section" !in source,
            "Production near geometry must not dispatch the legacy live-section mesher.",
        )
    }

    @Test
    fun `near and distant production builds use the process terrain service`() {
        val near = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/chunk/queue/meshing/ChunkMeshingQueue.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val distant = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/distant/DistantTerrainRenderer.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val distantHierarchy = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/distant/DistantHierarchicalTerrainRuntime.kt")
            .readText()
            .withoutCommentsAndLiterals()

        assertTrue("TerrainProcessBuildService.shared" in near)
        assertTrue("TerrainProcessBuildService.shared" !in distant)
        assertTrue("DistantHierarchicalTerrainRuntime(" in distant)
        assertTrue("TerrainPageFailureRegistry" in near && "recordTransient" in near && "releaseEligible" in near)
        assertTrue(
            "TerrainPageFailureRegistry" in distantHierarchy &&
                "recordTransient" in distantHierarchy &&
                "releaseEligible" in distantHierarchy,
        )
        assertTrue("newSingleThreadExecutor" !in distant && "CompletableFuture" !in distant)
    }

    @Test
    fun `production frames lease one complete terrain pipeline generation`() {
        val pipeline = PROJECT_ROOT
            .resolve(
                "src/main/java/de/bixilon/minosoft/gui/rendering/renderer/renderer/pipeline/RendererPipeline.kt",
            )
            .readText()
            .withoutCommentsAndLiterals()
        val registry = PROJECT_ROOT
            .resolve(
                "src/main/java/de/bixilon/minosoft/gui/rendering/terrain/scene/ProductionTerrainPipelineRegistry.kt",
            )
            .readText()
            .withoutCommentsAndLiterals()
        val diagnostics = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/debug/terrain/TerrainProductionDiagnosticCapture.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val manager = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/renderer/renderer/RendererManager.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val renderLoop = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/RenderLoop.kt")
            .readText()
            .withoutCommentsAndLiterals()

        assertTrue("terrainPipelines.withFrame(prepare)" in pipeline)
        assertTrue("graph.execute(execution)" in pipeline && "complete()" in pipeline)
        assertTrue("complete =" in manager && "finishFrame()" in manager)
        assertTrue("context.renderer.forEach { it.postDraw() }" !in renderLoop)
        assertTrue("TerrainPipelineCandidate" !in registry && "TerrainMaterialTableGeneration" !in registry)
        assertTrue("chunks.terrain.acquire().use" in registry)
        assertTrue("context.shaderPipeline.acquire().use" in registry)
        assertTrue("withPinnedBackend(near.backend)" in registry)
        assertTrue("withFramePipeline(context, shader.pipeline" in registry)
        assertTrue("pipelineSelection.generation" in diagnostics)
        assertTrue("TerrainPipelineGenerationClock" !in diagnostics)
    }

    @Test
    fun `shader presentation removal invalidates near material generations`() {
        val adapter = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/modding/loader/fabric/IrisCompatibilityAdapter.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val removal = adapter
            .substringAfter("fun setEnabled(context: RenderContext, enabled: Boolean): Boolean")
            .substringBefore("fun isInstalled(context: RenderContext): Boolean")

        assertTrue(
            ".invalidate(context.session.world, TerrainBuildCause.RESOURCE_GENERATION_CHANGE)" in removal,
            "Restoring the built-in shader pipeline must rebuild near terrain for its material generation",
        )
    }

    @Test
    fun `selected near region path keeps transactional storage and both draw capabilities`() {
        val region = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/near/OpenGlNearTerrainRegionRuntime.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val device = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/storage/OpenGlTerrainRegionResources.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val meshes = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/chunk/mesh/ChunkMeshes.kt")
            .readText()
            .withoutCommentsAndLiterals()

        assertTrue("TerrainRegionStorage" in region)
        assertTrue("SharedOpenGlTerrainRegionDevice" in region)
        assertTrue("glMultiDrawElementsBaseVertex" in device)
        assertTrue("glDrawElementsBaseVertex" in device)
        assertTrue("TerrainDrawCommand::topology" in device)
        assertTrue("glFenceSync" in device && "glClientWaitSync" in device)
        assertTrue("requireCurrentContext()" in device && "Rendering.currentContext" in device)
        assertTrue("Rendering.currentContext !== context" in region)
        assertTrue("fun loadRegionBacked" in meshes && "mesh.drop()" in meshes)

        val asynchronousPreparation = region.substringAfter("fun prepareFrame()").substringBefore("fun publish(")
        assertTrue(
            "completion" !in asynchronousPreparation &&
                "collectRetired" !in asynchronousPreparation &&
                ".close()" !in asynchronousPreparation,
            "Asynchronous near preparation must not call context-bound OpenGL resource operations.",
        )
        assertTrue("fun finishFrame()" in region && "collectStorage()" in region)
    }

    @Test
    fun `production seam retains lifecycle coverage and frame pinned distant masking`() {
        val near = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/near/NearSurfaceCoverage.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val distant = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/distant/DistantHierarchicalTerrainRuntime.kt")
            .readText()
            .withoutCommentsAndLiterals()

        assertTrue("TerrainCoverageTracker" in near)
        assertTrue("TerrainCoveragePageMask" in distant)
        assertTrue("mainDrawSelection" in distant && "shadowDrawSelection" in distant)
        assertTrue("nativeOwnership.lifecycle" in distant)
        assertTrue("TerrainCoverageTransitionPolicy(true, 8)" in distant)
    }

    @Test
    fun `hierarchical distant candidate is page local semantic and process scheduled`() {
        val runtime = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/distant/DistantHierarchicalTerrainRuntime.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val renderer = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/distant/DistantTerrainRenderer.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val frameSubmission = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/storage/TerrainRegionFrameSubmission.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val shader = PROJECT_ROOT
            .resolve("src/main/resources/assets/minosoft/rendering/shader/distant/terrain/terrain.vsh")
            .readText()
            .withoutCommentsAndLiterals()

        assertTrue("TerrainProcessBuildService.shared" in runtime)
        assertTrue("DistantPageMesher.mesh" in runtime)
        assertTrue("index.publishSource" in runtime && "index.publishRender" in runtime)
        assertTrue("index.removeSource" in runtime)
        assertTrue("HashMap<TerrainPageKey, Pending>" in runtime)
        assertTrue("TerrainPageFailureRegistry" in runtime)
        assertTrue("HashMap<TerrainPageKey, Int>" !in runtime)
        assertTrue("TerrainRegionStorage" in runtime)
        assertTrue("TerrainBatchCache" in frameSubmission && "TerrainDrawBatch" in frameSubmission)
        assertTrue("TerrainSelectionPublication" in runtime)
        assertTrue("DistantTerrainMeshBuilder" !in runtime && "gui.rendering.util.mesh.Mesh" !in runtime)
        assertTrue("DistantLodMeshPlanner" !in runtime)
        assertTrue("DistantHierarchicalTerrainRuntime(" in renderer)
        assertTrue("DistantLodMeshPlanner" !in renderer && "DistantTerrainMeshBuilder" !in renderer)
        assertTrue("uPageOffset" in shader && "vinPosition + uPageOffset" in shader)
    }

    @Test
    fun `near and distant views share transactional selection publication`() {
        val near = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/near/OpenGlNearTerrainRegionRuntime.kt")
            .readText()
            .withoutCommentsAndLiterals()
        val distant = PROJECT_ROOT
            .resolve("src/main/java/de/bixilon/minosoft/gui/rendering/terrain/distant/DistantHierarchicalTerrainRuntime.kt")
            .readText()
            .withoutCommentsAndLiterals()

        for (runtime in listOf(near, distant)) {
            assertTrue("TerrainSelectionPublication" in runtime)
            assertTrue("selectionPublication.desire" in runtime)
            assertTrue("selectionPublication.promote" in runtime)
            assertTrue("selectionPublication.active" in runtime)
        }
    }

    @Test
    fun `production terrain has no rollout switch or retired whole domain renderer`() {
        val legacyProperties = setOf(
            "\"minosoft.terrain.runtime\"",
            "\"minosoft.terrain.semantic-artifacts\"",
            "\"minosoft.terrain.region-storage\"",
            "\"minosoft.terrain.distant-hierarchy\"",
        )
        val retiredTypes = setOf(
            "DistantMeshBuildRequest",
            "DistantMeshBuildResult",
            "DistantLodMeshPlanner",
            "DistantTerrainMeshBuilder",
            "ChunkMeshingCause",
        )

        for (source in mainSourceFiles()) {
            val contents = source.readText()
            legacyProperties.forEach { property ->
                assertTrue(property !in contents, "Legacy terrain rollout property remains in ${relative(source)}")
            }
            val declarations = contents.declaredTypeNames()
            retiredTypes.forEach { type ->
                assertTrue(type !in declarations, "Retired terrain type remains in ${relative(source)}: $type")
            }
        }
    }

    private fun mainSourceFiles(): List<Path> = Files.walk(PROJECT_ROOT).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .filter { it.name.endsWith(".kt") || it.name.endsWith(".java") }
            .filter { MAIN_SOURCE_PATH.containsMatchIn(relative(it)) }
            .sorted(compareBy(::relative))
            .toList()
    }

    private fun sourcePackage(source: String): String? =
        PACKAGE.find(source.withoutCommentsAndLiterals())?.groupValues?.get(1)

    private fun String.isCoreTerrainPackage(): Boolean =
        this == TERRAIN_PACKAGE ||
            startsWith("$TERRAIN_PACKAGE.") ||
            this == RENDERING_TERRAIN_PACKAGE ||
            startsWith("$RENDERING_TERRAIN_PACKAGE.")

    private fun String.isFabricPackage(): Boolean =
        this == FABRIC_PACKAGE || startsWith("$FABRIC_PACKAGE.")

    private fun String.implementsRestrictedType(): Boolean {
        val tokens = withoutCommentsAndLiterals().tokenize()
        for (declarationIndex in tokens.indices) {
            if (tokens[declarationIndex] !in TYPE_DECLARATIONS) continue

            var parentheses = 0
            var brackets = 0
            var angles = 0
            var inheritanceStarted = false
            for (index in declarationIndex + 1 until tokens.size) {
                when (val token = tokens[index]) {
                    "(" -> parentheses++
                    ")" -> parentheses--
                    "[" -> brackets++
                    "]" -> brackets--
                    "<" -> angles++
                    ">" -> if (angles > 0) angles--
                    "{", ";" -> if (parentheses == 0 && brackets == 0 && angles == 0) break
                    ":", "extends", "implements" -> if (
                        parentheses == 0 &&
                        brackets == 0 &&
                        angles == 0
                    ) {
                        inheritanceStarted = true
                    }
                    in RESTRICTED_TYPES -> if (inheritanceStarted) return true
                }
            }
        }
        return false
    }

    private fun String.withoutCommentsAndLiterals(): String {
        val result = StringBuilder(length)
        var index = 0
        var state = LexicalState.CODE
        while (index < length) {
            val current = this[index]
            val next = getOrNull(index + 1)
            when (state) {
                LexicalState.CODE -> when {
                    current == '/' && next == '/' -> {
                        result.append("  ")
                        index++
                        state = LexicalState.LINE_COMMENT
                    }
                    current == '/' && next == '*' -> {
                        result.append("  ")
                        index++
                        state = LexicalState.BLOCK_COMMENT
                    }
                    current == '"' && next == '"' && getOrNull(index + 2) == '"' -> {
                        result.append("   ")
                        index += 2
                        state = LexicalState.RAW_STRING
                    }
                    current == '"' -> {
                        result.append(' ')
                        state = LexicalState.STRING
                    }
                    current == '\'' -> {
                        result.append(' ')
                        state = LexicalState.CHARACTER
                    }
                    else -> result.append(current)
                }
                LexicalState.LINE_COMMENT -> {
                    result.append(if (current == '\n') '\n' else ' ')
                    if (current == '\n') state = LexicalState.CODE
                }
                LexicalState.BLOCK_COMMENT -> {
                    result.append(if (current == '\n') '\n' else ' ')
                    if (current == '*' && next == '/') {
                        result.append(' ')
                        index++
                        state = LexicalState.CODE
                    }
                }
                LexicalState.RAW_STRING -> {
                    result.append(if (current == '\n') '\n' else ' ')
                    if (current == '"' && next == '"' && getOrNull(index + 2) == '"') {
                        result.append("  ")
                        index += 2
                        state = LexicalState.CODE
                    }
                }
                LexicalState.STRING, LexicalState.CHARACTER -> {
                    result.append(if (current == '\n') '\n' else ' ')
                    if (current == '\\') {
                        result.append(' ')
                        index++
                    } else if (
                        state == LexicalState.STRING && current == '"' ||
                        state == LexicalState.CHARACTER && current == '\''
                    ) {
                        state = LexicalState.CODE
                    }
                }
            }
            index++
        }
        return result.toString()
    }

    private fun String.tokenize(): List<String> =
        TOKEN.findAll(this).map { it.value }.toList()

    private fun String.declaredTypeNames(): List<String> {
        val tokens = withoutCommentsAndLiterals().tokenize()
        return tokens.indices.mapNotNull { index ->
            if (tokens[index] !in TYPE_DECLARATIONS) return@mapNotNull null
            tokens.getOrNull(index + 1)?.takeIf { IDENTIFIER.matches(it) }
        }
    }

    private fun String.lineNumber(offset: Int): Int =
        1 + take(offset).count { it == '\n' }

    private fun relative(path: Path): String =
        PROJECT_ROOT.relativize(path).toString().replace('\\', '/')

    private enum class LexicalState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        RAW_STRING,
        STRING,
        CHARACTER,
    }

    private companion object {
        val PROJECT_ROOT: Path = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

        const val TERRAIN_PACKAGE = "de.bixilon.minosoft.terrain"
        const val RENDERING_TERRAIN_PACKAGE = "de.bixilon.minosoft.gui.rendering.terrain"
        const val FABRIC_PACKAGE = "de.bixilon.minosoft.modding.loader.fabric"

        val LEGACY_FABRIC_RENDERER_FILES = emptySet<String>()

        val RESTRICTED_TYPES = setOf(
            "Renderer",
            "WorldRenderer",
            "TerrainBackend",
            "TerrainProvider",
            "NearTerrainProvider",
            "DistantTerrainProvider",
        )
        val NEUTRAL_DISTANT_TYPES = setOf(
            "DistantLodColumn",
            "DistantLodTile",
            "DistantLodTileStore",
            "DistantLodSnapshot",
            "DistantLodTileSource",
            "DistantLodRenderCellDiagnostic",
            "DistantLodRenderDiagnostics",
            "DistantTerrainRenderConfig",
            "DistantTerrainRenderSource",
            "DistantTerrainRenderer",
            "DistantTerrainRendererBuilder",
            "DistantTerrainShader",
            "DistantMeshBuildRequest",
            "DistantMeshBuildResult",
            "DistantLodQuad",
            "DistantLodSurface",
            "DistantLodEdge",
            "DistantLodSkirt",
            "DistantLodMaterial",
            "DistantLodMeshPlanner",
            "DistantReliefEntry",
            "DistantCoverage",
            "DistantCellKey",
            "DistantTerrainMeshBuilder",
            "DistantTerrainMeshStruct",
        )
        val HEADLESS_SCHEDULER_TYPES = setOf(
            "TerrainBuildRuntime",
            "TerrainBuildRuntimeSnapshot",
            "TerrainBuildEstimate",
            "TerrainBuildCompletion",
            "TerrainBuildOutcome",
            "TerrainCancellationToken",
            "TerrainBuildUrgency",
            "TerrainSchedulerTenantId",
            "TerrainSharedBuildService",
            "TerrainSharedBuildServiceSnapshot",
            "TerrainSharedBuildTenantSnapshot",
        )
        val HEADLESS_STORAGE_TYPES = setOf(
            "TerrainRangeAllocator",
            "TerrainRegionStorage",
            "TerrainBatchCache",
            "TerrainUploadBudgetScheduler",
            "ByteArrayTerrainRegionDevice",
            "TerrainSelectionPublication",
        )
        val TYPE_DECLARATIONS = setOf("class", "interface", "object", "record")

        val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        val MAIN_SOURCE_PATH = Regex("""(^|/)src/main/(java|kotlin)/""")
        val FABRIC_IMPORT = Regex(
            """(?m)^\s*import\s+de\.bixilon\.minosoft\.modding\.loader\.fabric(?:\.|\s*;?\s*$)""",
        )
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
        val TOKEN = Regex("""[A-Za-z_]\w*|[(){}\[\]<>:;,]""")
    }
}
