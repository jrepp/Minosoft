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

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorAttachment
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourceId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourcePlan
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetDescriptor
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.shader.code.glsl.GLSLCommentStripper
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager
import de.bixilon.minosoft.gui.rendering.terrain.TerrainMaterialClass
import de.bixilon.minosoft.util.json.Jackson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory

object IrisShaderPackPlanner {
    private val INCLUDE = Regex("""(?m)^\s*#include\s+[<"]([^>"]+)[>"]\s*$""")
    private val UNIFORM = Regex(
        """\buniform\s+(?:(?:lowp|mediump|highp)\s+)?[A-Za-z_][A-Za-z0-9_]*\s+""" +
            """([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^\]]+])?\s*;""",
    )
    private val SAMPLER = Regex(
        """\buniform\s+(?:(?:lowp|mediump|highp)\s+)?[iu]?sampler\w*\s+""" +
            """([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^\]]+])?\s*;""",
    )
    private val IMAGE_UNIFORM = Regex(
        """\buniform\s+(?:(?:lowp|mediump|highp)\s+)?[iu]?image\w*\s+""" +
            """([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^\]]+])?\s*;""",
    )
    private val TYPED_SAMPLER = Regex(
        """\buniform\s+(?:(?:lowp|mediump|highp)\s+)?([iu]?sampler(?:2DRect|1D|2D|3D))\s+""" +
            """([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^\]]+])?\s*;""",
    )
    private val SCENE_BRIDGE = Regex(
        """(?m)^\s*//\s*minosoft:scene_bridge\s+([A-Z][A-Z0-9_]*)\s+([A-Z][A-Z0-9_]*)\s+([A-Za-z0-9_,.-]+)\s*$""",
    )
    private val TEXTURE_ARRAY_INDEX = Regex(
        """(?m)^\s*//\s*minosoft:texture_array_index\s+([A-Za-z_][A-Za-z0-9_]*)\s*$""",
    )
    private val IRIS_TEXTURE_SIZE_DECLARATION = Regex(
        """(?m)^\s*uniform\s+(?:(?:lowp|mediump|highp)\s+)?ivec2\s+(atlasSize|gtextureSize)\s*;\s*$""",
    )
    private val VERSION_LINE = Regex("""(?m)^\s*#version[^\r\n]*$""")
    private val SELF_DISABLED_PROGRAM_ERROR = Regex(
        """(?im)^\s*#error\s+(?:"[^"\r\n]*program\s+should\s+be\s+disabled[^"\r\n]*"|""" +
            """'[^'\r\n]*program\s+should\s+be\s+disabled[^'\r\n]*'|""" +
            """[^\r\n]*program\s+should\s+be\s+disabled[^\r\n]*)\s*$""",
    )
    private val UNIFORM_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val BLOCK_COMMENT = Regex("""(?s)/\*.*?\*/""")
    private val LINE_COMMENT = Regex("""(?m)//.*$""")
    private val OPTION = Regex(
        """(?m)^([ \t]*)((?://[ \t]*)+)?#define[ \t]+([A-Za-z_][A-Za-z0-9_]*)(?:[ \t]+([^ \t\r\n/]+))?[ \t]*(?://(?:[^\[\r\n]*\[([^\]]+)][^\r\n]*|[^\r\n]*))?[ \t]*\r?$""",
    )
    private val CONST_OPTION = Regex(
        """(?m)^([ \t]*)const[ \t]+(int|float)[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*=[ \t]*([^; \t\r\n]+)[ \t]*;[ \t]*//[^\r\n]*?\[([^\]]+)][^\r\n]*\r?$""",
    )
    private val RENDER_TARGETS = Regex("""(?s)/\*\s*RENDERTARGETS\s*:\s*([0-9,\s]+)\*/""")
    private val DRAW_BUFFERS = Regex("""(?s)/\*\s*DRAWBUFFERS\s*:\s*([0-9Nn\s]+)\*/""")
    private val BUFFER_FORMAT = Regex(
        """\bconst\s+int\s+((?:colortex|shadowcolor)[0-9]+)Format\s*=\s*([A-Z0-9_]+)\s*;""",
    )
    private val BUFFER_CLEAR = Regex(
        """\bconst\s+bool\s+((?:colortex|shadowcolor)[0-9]+)Clear\s*=\s*(true|false)\s*;""",
    )
    private val BUFFER_CLEAR_COLOR = Regex(
        """\bconst\s+vec4\s+((?:colortex|shadowcolor)[0-9]+)ClearColor\s*=\s*vec4\s*\(([^)]*)\)\s*;""",
    )
    private val BUFFER_NEAREST = Regex(
        """\bconst\s+bool\s+((?:colortex|shadowcolor|shadowtex)[0-9]+)Nearest\s*=\s*(true|false)\s*;""",
    )
    private val BUFFER_MIPMAP = Regex(
        """\bconst\s+bool\s+((?:colortex|shadowcolor|shadowtex)[0-9]+)(?:MipmapEnabled|Mipmap)\s*=\s*(true|false)\s*;""",
    )
    private val SHADOW_MAP_RESOLUTION = Regex("""\bconst\s+int\s+shadowMapResolution\s*=\s*([0-9]+)\s*;""")
    private val LEGACY_SHADOW_MAP_RESOLUTION = Regex("""\bSHADOWRES\s*:\s*([-+]?[0-9]+)\b""")
    private val LEGACY_SHADOW_MAP_FOV = Regex(
        """\bSHADOWFOV\s*:\s*([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?)\b""",
    )
    private val LEGACY_SHADOW_HALF_PLANE = Regex(
        """\bSHADOWHPL\s*:\s*([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?)\b""",
    )
    private val NOISE_TEXTURE_RESOLUTION = Regex("""\bconst\s+int\s+noiseTextureResolution\s*=\s*([0-9]+)\s*;""")
    private val SUN_PATH_ROTATION = Regex(
        """\bconst\s+float\s+sunPathRotation\s*=\s*""" +
            """([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?)[fF]?\s*;""",
    )
    private val BUFFER_COMMENT_DIRECTIVES = listOf(
        BUFFER_FORMAT,
        BUFFER_CLEAR,
        BUFFER_CLEAR_COLOR,
        BUFFER_NEAREST,
        BUFFER_MIPMAP,
        SHADOW_MAP_RESOLUTION,
        LEGACY_SHADOW_MAP_RESOLUTION,
        LEGACY_SHADOW_MAP_FOV,
        LEGACY_SHADOW_HALF_PLANE,
        NOISE_TEXTURE_RESOLUTION,
        SUN_PATH_ROTATION,
    )
    private val FLOAT_DIRECTIVE = Regex(
        """\bconst\s+float\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*""" +
            """([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?)[fF]?\s*;""",
    )
    private val PROPERTY = Regex("""^\s*([^#!\s][^=]*)=(.*)$""")
    private val PROPERTY_DIRECTIVE = Regex("""^\s*#\s*([A-Za-z]+)\b(.*)$""")
    private val CUSTOM_UNIFORM_PROPERTY = Regex(
        """(uniform|variable)\.(bool|int|float|vec2|vec3|vec4)\.([A-Za-z_][A-Za-z0-9_]*)""",
    )
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val RESOURCE_TEXTURE = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    private const val MAX_FILES = 1024
    private const val MAX_PROGRAMS = 128
    private const val MAX_INCLUDE_DEPTH = 32
    private const val MAX_PROFILES = 128
    private const val MAX_PROFILE_TOKENS = 512
    private const val MAX_SCREENS = 128
    private const val MAX_SCREEN_TOKENS = 512
    private const val BLOCK_ATLAS_RESOURCE = "minecraft:textures/atlas/blocks.png"
    val OWNER = RenderOwnerId("minosoft:iris-1.7.2-shader-pipeline")
    val SHADOW_VIEW = RenderViewId("minosoft:shadow")

    fun standardEnvironmentDefines(
        minecraftVersion: Int,
        osName: String = System.getProperty("os.name").orEmpty(),
        perBufferBlending: Boolean = false,
        distantHorizons: Boolean = false,
    ): Map<String, String> = buildMap {
        require(minecraftVersion > 0) { "Minecraft shader-pack version must be positive" }
        put("MC_VERSION", minecraftVersion.toString())
        put("IS_IRIS", "")
        put("IRIS_VERSION", "10702")
        put("IRIS_TAG_SUPPORT", "")
        put("MC_NORMAL_MAP", "")
        put("MC_SPECULAR_MAP", "")
        put("MC_RENDER_QUALITY", "1.0")
        put("MC_SHADOW_QUALITY", "1.0")
        put("MC_HAND_DEPTH", "0.125")
        listOf(
            "UNKNOWN",
            "LEAVES",
            "STONE",
            "WOOD",
            "METAL",
            "DIRT",
            "LAVA",
            "DEEPSLATE",
            "SNOW",
            "SAND",
            "TERRACOTTA",
            "NETHER_STONE",
            "WATER",
            "GRASS",
            "AIR",
            "ILLUMINATED",
        ).forEachIndexed { index, material ->
            put("DH_BLOCK_$material", index.toString())
        }
        if (perBufferBlending) {
            put("IRIS_FEATURE_PER_BUFFER_BLENDING", "")
        }
        if (distantHorizons) {
            put("DISTANT_HORIZONS", "")
            // Bliss 2.1 exposes this informational DH screen entry through a
            // numeric #if without a source declaration. Iris resolves the
            // unset selector to its first (zero) value before driver compile.
            put("DH_KNOWN_ISSUES", "0")
        }
        if (osName.startsWith("mac", ignoreCase = true)) {
            // Pinned Iris publishes this platform macro. Packs use it to avoid
            // image-load/store and other paths unavailable on Apple's OpenGL
            // implementation, so omitting it can select a non-executable path.
            put("MC_OS_MAC", "")
        }
        IrisRenderStage.entries.forEach { stage ->
            put("MC_RENDER_STAGE_${stage.name}", stage.shaderValue.toString())
        }
    }

    fun plan(
        path: Path,
        overrides: Map<String, String> = emptyMap(),
        dimension: ResourceLocation? = null,
        preprocessorDefines: Map<String, String> = emptyMap(),
    ): ShaderPipelinePlan {
        require(preprocessorDefines.size <= 64) { "Too many shader-pack preprocessor defines" }
        require(preprocessorDefines.keys.all(IDENTIFIER::matches)) {
            "Shader-pack preprocessor define has an invalid name"
        }
        require(preprocessorDefines.values.all { it.length <= 64 && '\n' !in it && '\r' !in it }) {
            "Shader-pack preprocessor define has an invalid value"
        }
        val pack = readPack(path)
        val sourceFiles = pack.text
        val programDirectory = selectProgramDirectory(sourceFiles, dimension)
        val programFiles = sourceFiles.keys.filter { isDirectChild(it, programDirectory) }
        val declaredOptions = options(sourceFiles, programDirectory).associateBy(ShaderPackOption::name)
        require(overrides.keys.all(declaredOptions::containsKey)) { "Shader pack override references an unknown option." }
        for ((name, value) in overrides) {
            val option = declaredOptions.getValue(name)
            require(value == option.defaultValue || value in option.values) {
                "Invalid value '$value' for shader option $name."
            }
        }
        val optionValues = declaredOptions.mapValues { (name, option) -> overrides[name] ?: option.defaultValue }
        // Shader options are authored as defines in files and are applied to
        // those declarations below. Seeding them here would also predefine
        // ordinary include guards, because OptiFine's option syntax and a
        // guard's `#define NAME` are lexically identical.
        val shaderDefines = preprocessorDefines + mapOf(
            "IS_IRIS" to "1",
            "IRIS_FEATURE_CUSTOM_IMAGES" to "1",
        )
        val files = sourceFiles.mapValues { (_, source) -> applyOptions(source, overrides, declaredOptions) }
        val propertyDefinitions = propertyDefines(declaredOptions, optionValues, shaderDefines)
        val properties = parseProperties(
            preprocessProperties(
                files["shaders.properties"],
                propertyDefinitions,
            ),
        )
        val selectedProfile = selectProfile(parseProfiles(properties, declaredOptions), optionValues)
        val profileDisabledPrograms = selectedProfile?.disabledPrograms.orEmpty()
        val alphaTestOverrides = alphaTestOverrides(properties)
        val vanillaCloudsDisabled = properties["clouds"]?.equals("off", ignoreCase = true) == true
        val roots = programFiles.filter { source ->
            GRAPHICS_STAGE_EXTENSIONS.any(source::endsWith)
        }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .sorted()
        require(roots.size <= MAX_PROGRAMS) { "Shader pack declares too many program roots: ${roots.size}" }

        val discoveredPrograms = roots.mapNotNull { root ->
            if (root.substringAfterLast('/') in profileDisabledPrograms) return@mapNotNull null
            if (!programEnabled(root, properties, optionValues, declaredOptions, propertyDefinitions)) {
                return@mapNotNull null
            }
            val vertexPath = "$root.vsh"
            val fragmentPath = "$root.fsh"
            val geometryPath = "$root.gsh"
            val tessellationControlPath = "$root.tcs"
            val tessellationEvaluationPath = "$root.tes"
            val vertex = files[vertexPath]
            val fragment = files[fragmentPath]
            val geometry = files[geometryPath]
            val tessellationControl = files[tessellationControlPath]
            val tessellationEvaluation = files[tessellationEvaluationPath]
            val programPhase = phase(root.substringAfterLast('/'))
            if (
                programPhase == ShaderProgramPhase.UNSUPPORTED ||
                (
                    programPhase == ShaderProgramPhase.DISTANT_HORIZONS &&
                        "DISTANT_HORIZONS" !in shaderDefines
                    )
            ) return@mapNotNull null
            if (vertex == null || fragment == null) {
                throw IllegalArgumentException(
                    "Shader program ${root.substringAfterLast('/')} requires paired vertex and fragment stages",
                )
            }
            require((tessellationControl == null) == (tessellationEvaluation == null)) {
                "Shader program ${root.substringAfterLast('/')} requires paired tessellation control and evaluation stages"
            }
            val resolvedVertex = resolve(vertexPath, vertex, files, linkedSetOf(), 0)
            val resolvedFragment = resolve(fragmentPath, fragment, files, linkedSetOf(), 0)
            val resolvedGeometry = geometry?.let {
                resolve(geometryPath, it, files, linkedSetOf(), 0)
            }
            val resolvedTessellationControl = tessellationControl?.let {
                resolve(tessellationControlPath, it, files, linkedSetOf(), 0)
            }
            val resolvedTessellationEvaluation = tessellationEvaluation?.let {
                resolve(tessellationEvaluationPath, it, files, linkedSetOf(), 0)
            }
            // Iris disables programs through shaders.properties or profiles. A
            // few packs also leave a defensive #error in a feature-specific
            // stage but omit the matching program condition. Honor only that
            // explicit, active instruction after option preprocessing; all
            // other #error directives remain compiler failures.
            if (
                selfDisabledProgram(
                    resolvedVertex,
                    resolvedFragment,
                    resolvedGeometry,
                    resolvedTessellationControl,
                    resolvedTessellationEvaluation,
                    shaderDefines,
                )
            ) {
                return@mapNotNull null
            }
            val name = root.substringAfterLast('/')
            val temporallyStable = IrisTemporalStabilityTransformer.transform(
                name,
                programPhase,
                IrisLegacyShaderTransformer.Stages(
                    vertex = resolvedVertex,
                    fragment = resolvedFragment,
                    geometry = resolvedGeometry,
                    tessellationControl = resolvedTessellationControl,
                    tessellationEvaluation = resolvedTessellationEvaluation,
                ),
            )
            val presentationStable = IrisComplementaryWaterTransformer.transform(
                name,
                programPhase,
                // Keep source-native presentation corrections ahead of the
                // retained producer ABI bridge.
                temporallyStable,
            )
            val bridged = if (
                (name == "gbuffers_clouds" && vanillaCloudsDisabled) ||
                noOpProgram(
                    resolvedVertex,
                    resolvedFragment,
                    resolvedGeometry,
                    resolvedTessellationControl,
                    resolvedTessellationEvaluation,
                )
            ) {
                IrisLegacyShaderTransformer.suppressedScene(name) ?: return@mapNotNull null
            } else {
                IrisLegacyShaderTransformer.transform(
                    name,
                    programPhase,
                    presentationStable.vertex,
                    presentationStable.fragment,
                    presentationStable.tessellationControl,
                    presentationStable.tessellationEvaluation,
                )
            }
            val sourceNative = if (
                properties["customTexture.textureAtlas"]?.trim() == BLOCK_ATLAS_RESOURCE
            ) {
                IrisLegacyShaderTransformer.transformSourceNativeBlockAtlas(
                    bridged,
                    "textureAtlas",
                    IrisLegacyShaderTransformer.Stages(
                        vertex = preprocessShaderInspection(bridged.vertex, shaderDefines),
                        fragment = preprocessShaderInspection(bridged.fragment, shaderDefines),
                        geometry = bridged.geometry?.let {
                            preprocessShaderInspection(it, shaderDefines)
                        },
                        tessellationControl = bridged.tessellationControl?.let {
                            preprocessShaderInspection(it, shaderDefines)
                        },
                        tessellationEvaluation = bridged.tessellationEvaluation?.let {
                            preprocessShaderInspection(it, shaderDefines)
                        },
                    ),
                )
            } else {
                bridged
            }
            val transformed = sourceNative.copy(
                vertex = transformTextureArraySizes(vertexPath, sourceNative.vertex, shaderDefines),
                fragment = transformTextureArraySizes(fragmentPath, sourceNative.fragment, shaderDefines),
                geometry = (sourceNative.geometry ?: resolvedGeometry)?.let {
                    transformTextureArraySizes(geometryPath, it, shaderDefines)
                },
                tessellationControl = sourceNative.tessellationControl?.let {
                    transformTextureArraySizes(tessellationControlPath, it, shaderDefines)
                },
                tessellationEvaluation = sourceNative.tessellationEvaluation?.let {
                    transformTextureArraySizes(tessellationEvaluationPath, it, shaderDefines)
                },
            )
            require(
                transformed.geometry == null ||
                    "minosoftTessellationControlMain" !in transformed.tessellationControl.orEmpty(),
            ) {
                "Shader program $name combines tessellation, geometry, and host bridge varyings; " +
                    "that chain requires an explicit geometry-stage bridge"
            }
            programSource(
                name = name,
                phase = programPhase,
                vertex = transformed.vertex,
                fragment = transformed.fragment,
                geometry = transformed.geometry,
                tessellationControl = transformed.tessellationControl,
                tessellationEvaluation = transformed.tessellationEvaluation,
                alphaTest = alphaTestOverrides[name],
                inspectionDefines = shaderDefines,
            )
        }
        val programsWithCloudPolicy = if (
            vanillaCloudsDisabled && discoveredPrograms.none { it.name == "gbuffers_clouds" }
        ) {
            val suppressed = requireNotNull(IrisLegacyShaderTransformer.suppressedScene("gbuffers_clouds"))
            discoveredPrograms + programSource(
                name = "gbuffers_clouds",
                phase = ShaderProgramPhase.SKY,
                vertex = suppressed.vertex,
                fragment = suppressed.fragment,
                geometry = null,
                inspectionDefines = shaderDefines,
            )
        } else {
            discoveredPrograms
        }
        val rawPrograms = if (
            programsWithCloudPolicy.none {
                it.name == "gbuffers_particles" || it.name == "gbuffers_particles_translucent"
            }
        ) {
            val legacyParticleFallback = listOf("gbuffers_textured_lit", "gbuffers_textured")
                .firstNotNullOfOrNull { name ->
                    programsWithCloudPolicy.firstOrNull {
                        it.name == name && "// minosoft:terrain_bridge" in it.vertex
                    }
                }
            if (legacyParticleFallback == null) {
                programsWithCloudPolicy
            } else {
                val particle = IrisLegacyShaderTransformer.particleFallback(legacyParticleFallback.fragment)
                programsWithCloudPolicy + programSource(
                    name = "gbuffers_particles",
                    phase = ShaderProgramPhase.PARTICLE,
                    vertex = particle.vertex,
                    fragment = particle.fragment,
                    geometry = particle.geometry,
                    // Iris resolves the authored fallback ProgramSource first,
                    // so its directive override wins over the particle
                    // ShaderKey default. Preserve that source identity here.
                    alphaTest = legacyParticleFallback.alphaTest ?: IrisAlphaTest.ONE_TENTH,
                    inspectionDefines = shaderDefines,
                )
            }
        } else {
            programsWithCloudPolicy
        }
        val waterShadow = rawPrograms.any { "waterShadow" in it.samplers }
        val customResources = customResourcePlan(properties, propertyDefinitions)
        val texturePlan = texturePlan(properties, pack.bytes, rawPrograms)
        val computePrograms = computePrograms(
            programFiles = programFiles,
            files = files,
            properties = properties,
            optionValues = optionValues,
            declaredOptions = declaredOptions,
            disabledPrograms = profileDisabledPrograms,
            inspectionDefines = shaderDefines,
            textures = texturePlan,
            customResources = customResources,
            waterShadow = waterShadow,
        )
        val programs = rawPrograms.map { program ->
            val usage = resourceUsage(
                program,
                properties,
                waterShadow,
                texturePlan,
                customResources,
            )
            program.copy(
                resourceUsage = usage,
                blendOverride = blendOverride(program, properties),
            )
        }
        require(terrainPrograms(programs).isNotEmpty()) {
            "Shader pack has no paired terrain fallback program"
        }
        require(programs.any { it.phase == ShaderProgramPhase.COMPOSITE || it.phase == ShaderProgramPhase.FINAL }) {
            "Shader pack has no paired composite or final program"
        }

        val shadow = programs.any { it.phase == ShaderProgramPhase.SHADOW }
        val shadowDirectives = shadowDirectives(properties, shadow, programs)
        val views = buildSet {
            add(RenderViewId.MAIN)
            if (shadow && shadowDirectives.enabled) add(SHADOW_VIEW)
        }
        val buffers = bufferPlan(programs, computePrograms, properties, shadow)
        return ShaderPipelinePlan(
            owner = OWNER,
            packName = path.fileName.toString(),
            fingerprint = fingerprint(pack.bytes, files, programDirectory),
            programDirectory = programDirectory,
            preprocessorDefines = preprocessorDefines,
            views = views,
            resources = resources(shadow && shadowDirectives.enabled, buffers),
            programs = programs,
            computePrograms = computePrograms,
            requiredTerrainSemantics = requiredSemantics(programs),
            buffers = buffers,
            textures = texturePlan,
            customResources = customResources,
            customUniforms = customUniforms(properties),
            selectedProfile = selectedProfile?.name,
            sunPathRotation = sunPathRotation(programs),
            smoothingDirectives = smoothingDirectives(programs),
            idMaps = idMaps(files, propertyDefinitions),
            oldHandLight = parseBooleanProperty(properties, "oldHandLight", true),
            underwaterOverlay = parseBooleanProperty(properties, "underwaterOverlay", true),
            particlesOrdering = particleOrdering(properties, programs),
            separateEntityDraws = parseBooleanProperty(properties, "separateEntityDraws", false),
            skipAllRendering = parseBooleanProperty(properties, "skipAllRendering", false),
            shadowDirectives = shadowDirectives,
        )
    }

    fun options(path: Path): List<ShaderPackOption> {
        val files = readPack(path).text
        return options(files, selectProgramDirectory(files, null))
    }

    fun settings(path: Path): ShaderPackSettings {
        val files = readPack(path).text
        val options = options(files, selectProgramDirectory(files, null))
        val byName = options.associateBy(ShaderPackOption::name)
        val optionValues = options.associate { it.name to it.defaultValue }
        val properties = parseProperties(
            preprocessProperties(files["shaders.properties"], propertyDefines(byName, optionValues)),
        )
        val profiles = parseProfiles(properties, byName)
        val sliders = properties["sliders"]
            ?.split(Regex("\\s+"))
            ?.filter(String::isNotBlank)
            .orEmpty()
        require(sliders.size <= MAX_SCREEN_TOKENS) {
            "shaders.properties declares too many slider options"
        }
        val missingSliders = sliders.filterNot(byName::containsKey)
        require(missingSliders.isEmpty()) {
            "shaders.properties sliders references unknown shader options: ${missingSliders.joinToString()}"
        }
        val main = parseScreen(properties, null)
        val subScreens = properties.keys.asSequence()
            .filter { it.startsWith("screen.") && !it.endsWith(".columns") }
            .map { it.removePrefix("screen.") }
            .filter(String::isNotBlank)
            .distinct()
            .mapNotNull { id -> parseScreen(properties, id) }
            .toList()
        require(subScreens.size <= MAX_SCREENS) {
            "shaders.properties declares too many option screens"
        }
        validateScreens(main, subScreens, byName)
        return ShaderPackSettings(options, profiles, sliders, main, subScreens)
    }

    private fun options(
        files: Map<String, String>,
        programDirectory: String?,
    ): List<ShaderPackOption> {
        val options = linkedMapOf<String, ShaderPackOption>()
        val inconsistent = mutableSetOf<String>()
        val inactiveProgramDirectories = declaredProgramDirectories(files) - setOfNotNull(programDirectory)
        for ((path, source) in files) {
            if (inactiveProgramDirectories.any { path.startsWith("$it/") }) continue
            for (match in OPTION.findAll(source)) {
                val commented = match.groupValues[2].isNotEmpty()
                val name = match.groupValues[3]
                val value = match.groupValues[4]
                val declared = match.groupValues[5].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
                val option = when {
                    declared.isNotEmpty() && value.isNotEmpty() -> ShaderPackOption(name, value, declared)
                    value.isEmpty() -> ShaderPackOption(name, (!commented).toString(), listOf("false", "true"))
                    else -> continue
                }
                require(option.values.size <= MAX_OPTION_VALUES) { "Shader option $name declares too many values." }
                if (name in inconsistent) continue
                val previous = options.putIfAbsent(name, option)
                if (previous != null && previous != option) {
                    options.remove(name)
                    inconsistent += name
                }
                require(options.size <= MAX_OPTIONS) { "Shader pack exceeds $MAX_OPTIONS options." }
            }
            for (match in CONST_OPTION.findAll(source)) {
                val name = match.groupValues[3]
                val value = match.groupValues[4]
                val declared = match.groupValues[5].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
                val option = ShaderPackOption(name, value, declared)
                require(option.values.size <= MAX_OPTION_VALUES) { "Shader option $name declares too many values." }
                if (name in inconsistent) continue
                val previous = options.putIfAbsent(name, option)
                if (previous != null && previous != option) {
                    options.remove(name)
                    inconsistent += name
                }
                require(options.size <= MAX_OPTIONS) { "Shader pack exceeds $MAX_OPTIONS options." }
            }
        }
        return options.values.sortedBy(ShaderPackOption::name)
    }

    private fun declaredProgramDirectories(files: Map<String, String>): Set<String> = buildSet {
        addAll(LEGACY_PROGRAM_DIRECTORIES)
        files["dimension.properties"]?.lineSequence()?.forEach { line ->
            val match = PROPERTY.matchEntire(line) ?: return@forEach
            val key = match.groupValues[1].trim()
            if (!key.startsWith(DIMENSION_PREFIX)) return@forEach
            add(normalizeProgramDirectory(key.removePrefix(DIMENSION_PREFIX)))
        }
    }

    private fun applyOptions(
        source: String,
        overrides: Map<String, String>,
        options: Map<String, ShaderPackOption>,
    ): String {
        val defines = OPTION.replace(source) { match ->
            val name = match.groupValues[3]
            val option = options[name] ?: return@replace match.value
            val value = overrides[name] ?: option.defaultValue
            val indent = match.groupValues[1]
            if (option.values == BOOLEAN_VALUES) {
                "$indent${if (value == "true") "" else "//"}#define $name"
            } else {
                val values = option.values.joinToString(" ")
                "$indent#define $name $value // [$values]"
            }
        }
        return CONST_OPTION.replace(defines) { match ->
            val name = match.groupValues[3]
            val option = options[name] ?: return@replace match.value
            val value = overrides[name] ?: option.defaultValue
            val indent = match.groupValues[1]
            val type = match.groupValues[2]
            val values = option.values.joinToString(" ")
            "${indent}const $type $name = $value; // [$values]"
        }
    }

    private data class ShaderPackFiles(
        val bytes: Map<String, ByteArray>,
        val text: Map<String, String>,
    )

    private fun readPack(path: Path): ShaderPackFiles {
        val bytes = linkedMapOf<String, ByteArray>()
        var totalBytes = 0

        fun add(relative: String, source: ByteArray) {
            require(relative.isNotBlank() && relative != "." && relative != ".." && !relative.startsWith("../") && !relative.startsWith('/')) {
                "Shader-pack entry escapes the shaders directory: $relative"
            }
            require(bytes.size < MAX_FILES) { "Shader pack exceeds $MAX_FILES files" }
            require(relative !in bytes) { "Shader pack contains duplicate entry: $relative" }
            totalBytes = Math.addExact(totalBytes, source.size)
            require(totalBytes <= MAX_TOTAL_BYTES) { "Shader pack exceeds $MAX_TOTAL_BYTES bytes" }
            bytes[relative] = source
        }

        if (path.isDirectory()) {
            val shaders = path.resolve("shaders")
            require(shaders.isDirectory()) { "Shader pack has no shaders directory: $path" }
            Files.walk(shaders).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { source ->
                    val relative = normalize(shaders.relativize(source).toString())
                    val content = Files.newInputStream(source).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
                    add(relative, readBounded(content, relative))
                }
            }
        } else {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                val shaderEntries = entries.filter { normalize(it.name).contains("shaders/") }
                require(shaderEntries.isNotEmpty()) { "Shader-pack archive has no shaders directory: $path" }
                val marker = shaderEntries.minOf { normalize(it.name).substringBefore("shaders/").length }
                shaderEntries.sortedBy { it.name }.forEach { entry ->
                    val normalized = normalize(entry.name)
                    val shadersIndex = normalized.indexOf("shaders/", marker)
                    require(shadersIndex >= 0) { "Shader-pack entry has an inconsistent shaders root: ${entry.name}" }
                    val relative = normalized.substring(shadersIndex + "shaders/".length)
                    val content = zip.getInputStream(entry).use { input ->
                        readBounded(input.readNBytes(MAX_SOURCE_BYTES + 1), relative)
                    }
                    add(relative, content)
                }
            }
        }
        val text = bytes
            .filterKeys { path ->
                BINARY_TEXTURE_EXTENSIONS.none { extension -> path.endsWith(extension, ignoreCase = true) }
            }
            .mapValues { (_, value) -> value.toString(StandardCharsets.UTF_8) }
        return ShaderPackFiles(bytes, text)
    }

    private fun readBounded(bytes: ByteArray, source: String): ByteArray {
        require(bytes.size <= MAX_SOURCE_BYTES) { "Shader source exceeds $MAX_SOURCE_BYTES bytes: $source" }
        return bytes
    }

    private fun resolve(
        sourcePath: String,
        source: String,
        files: Map<String, String>,
        stack: MutableSet<String>,
        depth: Int,
    ): String {
        require(depth <= MAX_INCLUDE_DEPTH) { "Shader include depth exceeds $MAX_INCLUDE_DEPTH at $sourcePath" }
        require(stack.add(sourcePath)) { "Shader include cycle: ${stack.joinToString(" -> ")} -> $sourcePath" }
        try {
            return INCLUDE.replace(source) { match ->
                val requested = match.groupValues[1]
                if (':' in requested) return@replace match.value
                val relative = if (requested.startsWith('/')) {
                    requested.removePrefix("/")
                } else {
                    sourcePath.substringBeforeLast('/', "").let { parent ->
                        if (parent.isEmpty()) requested else "$parent/$requested"
                    }
                }
                val normalized = normalize(relative)
                require(!normalized.startsWith("../") && normalized != "..") {
                    "Shader include escapes pack root: $requested"
                }
                val included = requireNotNull(files[normalized]) {
                    "Shader include is missing: $sourcePath -> $normalized"
                }
                resolve(normalized, included, files, stack, depth + 1)
            }
        } finally {
            stack.remove(sourcePath)
        }
    }

    private fun normalize(path: String): String = Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/')

    private fun selectProgramDirectory(
        files: Map<String, String>,
        dimension: ResourceLocation?,
    ): String? {
        files["dimension.properties"]?.let { source ->
            val mappings = linkedMapOf<String, Set<String>>()
            source.lineSequence().forEach { line ->
                val match = PROPERTY.matchEntire(line) ?: return@forEach
                val key = match.groupValues[1].trim()
                if (!key.startsWith(DIMENSION_PREFIX)) return@forEach
                val directory = normalizeProgramDirectory(key.removePrefix(DIMENSION_PREFIX))
                val dimensions = match.groupValues[2].trim()
                    .split(Regex("\\s+"))
                    .filter(String::isNotBlank)
                    .map { identity ->
                        if (identity == "*") identity else ResourceLocation.of(identity).toString()
                    }
                    .toSet()
                // Iris accepts empty placeholder mappings. They do not select a
                // program directory, but packs such as Bliss retain one for
                // optional/modded dimension configuration.
                if (dimensions.isEmpty()) return@forEach
                val previous = mappings.putIfAbsent(directory, dimensions)
                require(previous == null) { "dimension.properties declares $key more than once" }
            }
            val identity = dimension?.toString()
            if (identity != null) {
                mappings.entries.firstOrNull { identity in it.value }?.let { return it.key }
            }
            mappings.entries.firstOrNull { "*" in it.value }?.let { return it.key }
            return null
        }

        val hasLegacyPrograms = files.keys.any { source ->
            LEGACY_PROGRAM_DIRECTORIES.any { directory ->
                isDirectChild(source, directory) && SHADER_STAGE_EXTENSIONS.any(source::endsWith)
            }
        }
        if (!hasLegacyPrograms) return null
        return when (dimension?.toString()) {
            NETHER -> "world-1"
            END -> "world1"
            else -> "world0"
        }
    }

    private fun normalizeProgramDirectory(source: String): String {
        require(source.isNotBlank() && !source.startsWith('/') && !source.startsWith('\\')) {
            "dimension.properties program directory must be relative: $source"
        }
        val normalized = normalize(source)
        require(normalized != "." && normalized != ".." && !normalized.startsWith("../")) {
            "dimension.properties program directory escapes the shaders directory: $source"
        }
        return normalized.trimEnd('/')
    }

    private fun isDirectChild(source: String, directory: String?): Boolean {
        val parent = source.substringBeforeLast('/', "")
        return parent == directory.orEmpty()
    }

    private fun programEnabled(
        root: String,
        properties: Map<String, String>,
        optionValues: Map<String, String>,
        options: Map<String, ShaderPackOption>,
        definitions: Map<String, String>,
    ): Boolean {
        val expression = properties["program.$root.enabled"] ?: return true
        require(expression.length <= MAX_PROGRAM_CONDITION_LENGTH) {
            "Shader program ${root.substringAfterLast('/')} condition is too long"
        }
        return BooleanConditionParser(expression) { name ->
            if (name in definitions && name !in options) return@BooleanConditionParser true
            val option = requireNotNull(options[name]) {
                "Shader program ${root.substringAfterLast('/')} condition references unknown boolean option $name"
            }
            require(option.values == BOOLEAN_VALUES) {
                "Shader program ${root.substringAfterLast('/')} condition references non-boolean option $name"
            }
            optionValues.getValue(name).toBooleanStrict()
        }.parse()
    }

    private fun particleOrdering(
        properties: Map<String, String>,
        programs: List<ShaderProgramSource>,
    ): IrisParticleOrdering {
        properties["particles.ordering"]?.let { source ->
            return when (source) {
                "before" -> IrisParticleOrdering.BEFORE
                "after" -> IrisParticleOrdering.AFTER
                "mixed" -> IrisParticleOrdering.MIXED
                else -> throw IllegalArgumentException(
                    "shaders.properties particles.ordering must be before, after, or mixed",
                )
            }
        }
        properties["particles.before.deferred"]?.let { source ->
            return when (source.toBooleanStrictOrNull()) {
                true -> IrisParticleOrdering.BEFORE
                false -> IrisParticleOrdering.AFTER
                null -> throw IllegalArgumentException(
                    "shaders.properties particles.before.deferred must be true or false",
                )
            }
        }
        return if (programs.any { it.phase == ShaderProgramPhase.DEFERRED }) {
            IrisParticleOrdering.AFTER
        } else {
            IrisParticleOrdering.MIXED
        }
    }

    private fun sunPathRotation(programs: List<ShaderProgramSource>): Float {
        val values = programs.flatMap { program ->
            inspectionStages(program).flatMap { source ->
                SUN_PATH_ROTATION.findAll(source).map { match ->
                    match.groupValues[1].toFloat().also { value ->
                        require(value.isFinite()) {
                            "Shader program ${program.name} declares a non-finite sunPathRotation"
                        }
                    }
                }.toList()
            }
        }.distinct()
        require(values.size <= 1) {
            "Shader pack declares inconsistent sunPathRotation values: ${values.sorted()}"
        }
        return values.singleOrNull() ?: 0.0f
    }

    private fun smoothingDirectives(programs: List<ShaderProgramSource>) = IrisSmoothingDirectives(
        wetnessHalfLife = floatDirective(programs, "wetnessHalflife", 600.0f),
        drynessHalfLife = floatDirective(programs, "drynessHalflife", 200.0f),
        eyeBrightnessHalfLife = floatDirective(programs, "eyeBrightnessHalflife", 10.0f),
    )

    private fun floatDirective(
        programs: List<ShaderProgramSource>,
        name: String,
        default: Float,
    ): Float {
        val values = programs.flatMap { program ->
            inspectionStages(program).flatMap { source ->
                FLOAT_DIRECTIVE.findAll(source)
                    .filter { it.groupValues[1] == name }
                    .map { it.groupValues[2].toFloat() }
                    .toList()
            }
        }.distinct()
        require(values.size <= 1) {
            "Shader pack declares inconsistent $name values: ${values.sorted()}"
        }
        return values.singleOrNull() ?: default
    }

    private fun inspectionStages(program: ShaderProgramSource): List<String> = listOfNotNull(
        program.inspectionVertex ?: program.vertex,
        program.inspectionTessellationControl ?: program.tessellationControl,
        program.inspectionTessellationEvaluation ?: program.tessellationEvaluation,
        program.inspectionGeometry ?: program.geometry,
        program.inspectionFragment ?: program.fragment,
    )

    private fun shadowDirectives(
        properties: Map<String, String>,
        hasShadowProgram: Boolean,
        programs: List<ShaderProgramSource>,
    ): IrisShadowDirectives {
        fun boolean(name: String, default: Boolean): Boolean =
            parseBooleanProperty(properties, name, default)

        val enabled = boolean("shadow.enabled", hasShadowProgram)
        require(!enabled || hasShadowProgram) {
            "shaders.properties shadow.enabled=true requires an executable shadow program"
        }
        val terrain = boolean("shadowTerrain", true)
        val translucentTerrain = boolean("shadowTranslucent", true)
        val entities = boolean("shadowEntities", true)
        val player = boolean("shadowPlayer", false)
        val blockEntities = boolean("shadowBlockEntities", true)
        val lightBlockEntities = boolean("shadowLightBlockEntities", false)
        val legacyDistance = legacyShadowFloatDirective(
            programs,
            LEGACY_SHADOW_HALF_PLANE,
            "SHADOWHPL",
        )
        val distance = floatDirective(programs, "shadowDistance", legacyDistance ?: 160.0f)
        val nearPlane = floatDirective(programs, "shadowNearPlane", 0.05f)
        val farPlane = floatDirective(programs, "shadowFarPlane", 256.0f)
        val mapFov = optionalFloatDirective(programs, "shadowMapFov")
            ?: legacyShadowFloatDirective(programs, LEGACY_SHADOW_MAP_FOV, "SHADOWFOV")
        val intervalSize = floatDirective(programs, "shadowIntervalSize", 2.0f)
        val distanceRenderMultiplier = floatDirective(programs, "shadowDistanceRenderMul", -1.0f)
        val entityShadowDistanceMultiplier = floatDirective(programs, "entityShadowDistanceMul", 1.0f)
        val voxelDistance = floatDirective(programs, "voxelDistance", 0.0f)
        val cullingMode = when (properties["shadow.culling"]) {
            null -> IrisShadowCullingMode.DEFAULT
            "false" -> IrisShadowCullingMode.DISTANCE
            "true" -> IrisShadowCullingMode.ADVANCED
            "reversed" -> IrisShadowCullingMode.REVERSED
            else -> IrisShadowCullingMode.DEFAULT
        }
        // Pinned Iris initializes this bit from the primary shadow geometry
        // stage. Its setUsesImages hook exists at the pin but has no caller.
        val voxelizationDetected = programs.any {
            it.name == "shadow" && (it.inspectionGeometry ?: it.geometry) != null
        }
        return IrisShadowDirectives(
            enabled = enabled,
            terrain = terrain,
            translucentTerrain = translucentTerrain,
            entities = entities,
            player = player,
            blockEntities = blockEntities,
            lightBlockEntities = lightBlockEntities,
            distance = distance,
            nearPlane = nearPlane,
            farPlane = farPlane,
            mapFov = mapFov,
            intervalSize = intervalSize,
            distanceRenderMultiplier = distanceRenderMultiplier,
            entityShadowDistanceMultiplier = entityShadowDistanceMultiplier,
            voxelDistance = voxelDistance,
            cullingMode = cullingMode,
            voxelizationDetected = voxelizationDetected,
        )
    }

    private fun legacyShadowFloatDirective(
        programs: List<ShaderProgramSource>,
        regex: Regex,
        name: String,
    ): Float? {
        val values = programs.flatMap { program ->
            inspectionStages(program).flatMap { source ->
                regex.findAll(source).map { it.groupValues[1].toFloat() }.toList()
            }
        }.distinct()
        require(values.size <= 1) {
            "Shader pack declares inconsistent $name values: ${values.sorted()}"
        }
        return values.singleOrNull()
    }

    private fun optionalFloatDirective(
        programs: List<ShaderProgramSource>,
        name: String,
    ): Float? {
        val sentinel = Float.NaN
        return floatDirective(programs, name, sentinel).takeUnless(Float::isNaN)
    }

    private fun stripComments(source: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(source, "")) { "" }

    /**
     * Rewrites Iris's draw-wide texture-size uniforms only when an adapted
     * stage declares which per-vertex/per-fragment Minosoft array index it
     * carries. Raw shader packs remain fail-closed: choosing one bucket for an
     * entire mixed-texture batch would report incorrect dimensions.
     */
    private fun transformTextureArraySizes(
        path: String,
        source: String,
        definitions: Map<String, String>,
    ): String {
        val declarations = IRIS_TEXTURE_SIZE_DECLARATION.findAll(source).toList()
        if (declarations.isEmpty()) return source

        val names = declarations.mapTo(linkedSetOf()) { it.groupValues[1] }
        var transformed = IRIS_TEXTURE_SIZE_DECLARATION.replace(source, "")
        val activeSource = preprocessShaderInspection(transformed, definitions)
        val referencedNames = names.filterTo(linkedSetOf()) { name ->
            Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(activeSource)
        }
        if (referencedNames.isEmpty()) return transformed

        val indices = TEXTURE_ARRAY_INDEX.findAll(source).map { it.groupValues[1] }.distinct().toList()
        require(indices.size == 1) {
            "$path declares Iris atlasSize/gtextureSize but requires exactly one " +
                "minosoft:texture_array_index marker"
        }
        require(!Regex("""\b${IrisTextureArrayState.UNIFORM}\b""").containsMatchIn(source)) {
            "$path declares ${IrisTextureArrayState.UNIFORM}; it is owned by the Iris texture-array transform"
        }
        val version = requireNotNull(VERSION_LINE.find(source)) {
            "$path declares Iris atlasSize/gtextureSize without a GLSL version directive"
        }
        val arrayIndex = indices.single()
        referencedNames.forEach { name ->
            transformed = Regex("""\b${Regex.escape(name)}\b""").replace(
                transformed,
                "(${IrisTextureArrayState.UNIFORM}[int($arrayIndex)])",
            )
        }
        val insertion = version.range.last + 1
        return transformed.substring(0, insertion) +
            "\nuniform ivec2 ${IrisTextureArrayState.UNIFORM}[${TextureManager.SHADER_TEXTURE_ARRAY_SIZE}];" +
            transformed.substring(insertion)
    }

    private inline fun <reified T : Enum<T>> enumValue(program: String, kind: String, value: String): T {
        return try {
            enumValueOf(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Shader program $program declares unknown Minosoft scene $kind $value")
        }
    }

    private fun programSource(
        name: String,
        phase: ShaderProgramPhase,
        vertex: String,
        fragment: String,
        geometry: String?,
        tessellationControl: String? = null,
        tessellationEvaluation: String? = null,
        alphaTest: IrisAlphaTest? = null,
        inspectionDefines: Map<String, String> = emptyMap(),
    ): ShaderProgramSource {
        val inspectionVertex = try {
            preprocessShaderInspection(vertex, inspectionDefines)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Could not inspect shader program $name vertex: ${failure.message}", failure)
        }
        val inspectionFragment = try {
            preprocessShaderInspection(fragment, inspectionDefines)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Could not inspect shader program $name fragment: ${failure.message}", failure)
        }
        val inspectionGeometry = geometry?.let {
            try {
                preprocessShaderInspection(it, inspectionDefines)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Could not inspect shader program $name geometry: ${failure.message}", failure)
            }
        }
        val inspectionTessellationControl = tessellationControl?.let {
            try {
                preprocessShaderInspection(it, inspectionDefines)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Could not inspect shader program $name tessellation control: ${failure.message}",
                    failure,
                )
            }
        }
        val inspectionTessellationEvaluation = tessellationEvaluation?.let {
            try {
                preprocessShaderInspection(it, inspectionDefines)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Could not inspect shader program $name tessellation evaluation: ${failure.message}",
                    failure,
                )
            }
        }
        val combined = stripComments(
            listOfNotNull(
                inspectionVertex,
                inspectionTessellationControl,
                inspectionTessellationEvaluation,
                inspectionGeometry,
                inspectionFragment,
            ).joinToString("\n"),
        )
        val declaredUniforms = UNIFORM.findAll(combined).map { it.groupValues[1] }.toSet()
        val withoutUniformDeclarations = UNIFORM.replace(combined, "")
        val referencedUniforms = declaredUniforms.filterTo(linkedSetOf()) { uniform ->
            Regex("""\b${Regex.escape(uniform)}\b""").containsMatchIn(withoutUniformDeclarations)
        }
        val bridges = SCENE_BRIDGE.findAll(vertex).map { match ->
            val vertexAbi = enumValue<SceneVertexAbi>(name, "vertex ABI", match.groupValues[1])
            val stateAbi = enumValue<SceneStateAbi>(name, "state ABI", match.groupValues[2])
            val uniforms = match.groupValues[3].takeUnless { it == "-" }
                ?.split(',')
                ?.onEach { uniform ->
                    require(UNIFORM_NAME.matches(uniform)) {
                        "Shader program $name declares invalid bridge uniform $uniform"
                    }
                }
                ?.toSet()
                ?: emptySet()
            SceneProgramBridge(vertexAbi, stateAbi, uniforms)
        }.toList()
        require(bridges.map { it.vertexAbi to it.stateAbi }.toSet().size == bridges.size) {
            "Shader program $name declares a duplicate Minosoft scene bridge"
        }
        return ShaderProgramSource(
            name = name,
            phase = phase,
            vertex = vertex,
            fragment = fragment,
            geometry = geometry,
            uniforms = referencedUniforms,
            samplers = SAMPLER.findAll(combined)
                .map { it.groupValues[1] }
                .filterTo(linkedSetOf()) { it in referencedUniforms },
            sceneBridges = bridges.toSet(),
            alphaTest = alphaTest,
            inspectionVertex = inspectionVertex,
            inspectionFragment = inspectionFragment,
            inspectionGeometry = inspectionGeometry,
            tessellationControl = tessellationControl,
            tessellationEvaluation = tessellationEvaluation,
            inspectionTessellationControl = inspectionTessellationControl,
            inspectionTessellationEvaluation = inspectionTessellationEvaluation,
            tessellationPatchVertices = 3.takeIf { tessellationControl != null },
        )
    }

    private fun alphaTestOverrides(properties: Map<String, String>): Map<String, IrisAlphaTest> {
        return properties.asSequence()
            .filter { (key, _) -> key.startsWith(ALPHA_TEST_PREFIX) }
            .mapNotNull { (key, value) ->
                val program = key.removePrefix(ALPHA_TEST_PREFIX)
                if (program.isEmpty()) return@mapNotNull null
                if (value == "off" || value == "false") {
                    return@mapNotNull program to IrisAlphaTest.ALWAYS
                }
                // Iris 1.7.2 accepts the first two space-delimited tokens and
                // ignores malformed directives after logging them. Keep pack
                // loading tolerant while retaining only executable values.
                val tokens = value.split(' ').filter(String::isNotEmpty)
                if (tokens.size < 2) return@mapNotNull null
                val function = when (tokens[0]) {
                    "GL_ALWAYS" -> IrisAlphaTestFunction.ALWAYS
                    else -> runCatching { IrisAlphaTestFunction.valueOf(tokens[0]) }.getOrNull()
                } ?: return@mapNotNull null
                val reference = tokens[1].toFloatOrNull()?.takeIf(Float::isFinite)
                    ?: return@mapNotNull null
                program to IrisAlphaTest(function, reference)
            }
            .toMap(linkedMapOf())
    }

    private fun blendOverride(
        program: ShaderProgramSource,
        properties: Map<String, String>,
    ): IrisProgramBlendOverride {
        val prefix = "$BLEND_PREFIX${program.name}"
        val global = properties[prefix]?.let { blendMode(prefix, it) }
        val perBuffer = properties.asSequence()
            .filter { (key, _) -> key.startsWith("$prefix.") }
            .map { (key, value) ->
                val bufferName = key.removePrefix("$prefix.")
                val match = BUFFER_SAMPLER.matchEntire(bufferName)
                require(match != null) {
                    "Invalid Iris blend target '$bufferName' in $key"
                }
                val kind = when (match.groupValues[1]) {
                    "colortex" -> ShaderBufferKind.COLORTEX
                    "shadowcolor" -> ShaderBufferKind.SHADOWCOLOR
                    else -> throw IllegalArgumentException(
                        "Iris blend target must be a color buffer in $key: $bufferName",
                    )
                }
                val buffer = ShaderBufferId(kind, match.groupValues[2].toInt())
                // Packs commonly retain overrides for outputs selected only by
                // another option branch. Iris keeps those directives dormant;
                // the runtime applies a buffer override only when that logical
                // output is present in this program's active draw-buffer list.
                buffer to blendMode(key, value)
            }
            .toMap(linkedMapOf())
        return IrisProgramBlendOverride(global, perBuffer)
    }

    private fun blendMode(key: String, source: String): IrisBlendMode {
        val value = source.trim()
        if (value.equals("off", ignoreCase = true)) return IrisBlendMode.Off
        val tokens = value.split(Regex("\\s+")).filter(String::isNotEmpty)
        require(tokens.size == 4) {
            "Invalid Iris blend directive $key: expected off or four blend factors"
        }
        val factors = tokens.map { token ->
            requireNotNull(IRIS_BLEND_FACTORS[token]) {
                "Invalid Iris blend factor '$token' in $key"
            }
        }
        return IrisBlendMode.Enabled(
            BlendFunctionState(
                sourceRGB = factors[0],
                destinationRGB = factors[1],
                sourceAlpha = factors[2],
                destinationAlpha = factors[3],
            ),
        )
    }

    /**
     * Mirrors the program families in Iris 1.7.2+1.20.4 ProgramId and
     * ProgramArrayId. Keep this classification exact: treating an unknown
     * geometry program as a composite can route it to a fullscreen quad.
     */
    private fun phase(name: String): ShaderProgramPhase = when {
        name == "shadow" || name == "shadow_solid" || name == "shadow_cutout" -> ShaderProgramPhase.SHADOW
        PROGRAM_FAMILY.getValue(ShaderProgramPhase.SETUP).matches(name) -> ShaderProgramPhase.SETUP
        PROGRAM_FAMILY.getValue(ShaderProgramPhase.BEGIN).matches(name) -> ShaderProgramPhase.BEGIN
        PROGRAM_FAMILY.getValue(ShaderProgramPhase.SHADOW_COMPOSITE).matches(name) ->
            ShaderProgramPhase.SHADOW_COMPOSITE
        PROGRAM_FAMILY.getValue(ShaderProgramPhase.PREPARE).matches(name) -> ShaderProgramPhase.PREPARE
        PROGRAM_FAMILY.getValue(ShaderProgramPhase.DEFERRED).matches(name) -> ShaderProgramPhase.DEFERRED
        PROGRAM_FAMILY.getValue(ShaderProgramPhase.COMPOSITE).matches(name) -> ShaderProgramPhase.COMPOSITE
        name == "final" -> ShaderProgramPhase.FINAL

        name == "gbuffers_terrain" ||
            name == "gbuffers_terrain_solid" ||
            name == "gbuffers_terrain_cutout" ||
            name == "gbuffers_damagedblock" ||
            name == "gbuffers_water" -> ShaderProgramPhase.TERRAIN

        name == "gbuffers_block" ||
            name == "gbuffers_block_translucent" ||
            name == "gbuffers_beaconbeam" -> ShaderProgramPhase.BLOCK

        name == "gbuffers_item" -> ShaderProgramPhase.ITEM

        name == "gbuffers_entities" ||
            name == "gbuffers_entities_translucent" ||
            name == "gbuffers_entities_glowing" ||
            name == "gbuffers_lightning" ||
            name == "gbuffers_armor_glint" ||
            name == "gbuffers_spidereyes" -> ShaderProgramPhase.ENTITY

        name == "gbuffers_particles" ||
            name == "gbuffers_particles_translucent" -> ShaderProgramPhase.PARTICLE

        name == "gbuffers_skybasic" ||
            name == "gbuffers_skytextured" ||
            name == "gbuffers_clouds" -> ShaderProgramPhase.SKY

        name == "gbuffers_weather" -> ShaderProgramPhase.WEATHER
        name == "gbuffers_hand" || name == "gbuffers_hand_water" -> ShaderProgramPhase.HAND
        name == "gbuffers_basic" ||
            name == "gbuffers_line" ||
            name == "gbuffers_textured" ||
            name == "gbuffers_textured_lit" -> ShaderProgramPhase.BASIC

        name == "dh_terrain" || name == "dh_water" -> ShaderProgramPhase.DISTANT_HORIZONS
        name == "dh_shadow" -> ShaderProgramPhase.SHADOW
        else -> ShaderProgramPhase.UNSUPPORTED
    }

    private fun requiredSemantics(programs: List<ShaderProgramSource>): Set<VertexSemantic> {
        val terrain = terrainPrograms(programs).joinToString("\n") { "${it.vertex}\n${it.fragment}" }
        return buildSet {
            add(VertexSemantic.POSITION)
            add(VertexSemantic.TEXTURE_COORDINATE)
            if ("vinLightTint" in terrain) {
                add(VertexSemantic.PACKED_LIGHT_COLOR)
            } else {
                add(VertexSemantic.COLOR)
                add(VertexSemantic.PACKED_LIGHT)
            }
            if ("vinTexture" in terrain) add(VertexSemantic.TEXTURE_LAYER)
            if ("mc_Entity" in terrain) add(VertexSemantic.BLOCK_ID)
            if ("at_midTexCoord" in terrain) add(VertexSemantic.MID_TEXTURE_COORDINATE)
            if ("at_tangent" in terrain) add(VertexSemantic.TANGENT)
            if ("vaNormal" in terrain || "at_normal" in terrain || "gl_Normal" in terrain) {
                add(VertexSemantic.NORMAL)
            }
            if ("at_midBlock" in terrain) add(VertexSemantic.MID_BLOCK)
        }
    }

    private fun noOpProgram(
        vertex: String,
        fragment: String,
        geometry: String?,
        tessellationControl: String?,
        tessellationEvaluation: String?,
    ): Boolean {
        if (geometry != null || tessellationControl != null || tessellationEvaluation != null) return false
        val vertexBody = VERSION_LINE.replace(stripComments(vertex), "").filterNot(Char::isWhitespace)
        val fragmentBody = VERSION_LINE.replace(stripComments(fragment), "").filterNot(Char::isWhitespace)
        return vertexBody == "voidmain(){gl_Position=vec4(-1.0);}" &&
            fragmentBody == "voidmain(){discard;}"
    }

    private fun selfDisabledProgram(
        vertex: String,
        fragment: String,
        geometry: String?,
        tessellationControl: String?,
        tessellationEvaluation: String?,
        definitions: Map<String, String>,
    ): Boolean = listOfNotNull(
        vertex,
        tessellationControl,
        tessellationEvaluation,
        geometry,
        fragment,
    ).any {
        SELF_DISABLED_PROGRAM_ERROR.containsMatchIn(preprocessShaderInspection(it, definitions))
    }

    private fun terrainPrograms(programs: List<ShaderProgramSource>): List<ShaderProgramSource> {
        val candidates = TerrainMaterialClass.entries
            .flatMap(IrisProgramFallbacks::terrain)
            .toSet()
        return programs.filter {
            it.name in candidates &&
                (it.sceneBridges.isEmpty() || "// minosoft:terrain_bridge" in it.vertex)
        }
    }

    private fun propertyDefines(
        options: Map<String, ShaderPackOption>,
        values: Map<String, String>,
        environment: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        require(environment.size <= MAX_PROPERTY_DEFINES) {
            "Shader properties preprocessor declares too many environment definitions"
        }
        val defines = linkedMapOf<String, String>()
        for ((name, option) in options) {
            val value = values.getValue(name)
            if (option.values == BOOLEAN_VALUES) {
                if (value == "true") defines[name] = "1"
            } else {
                defines[name] = value
            }
        }
        for ((name, value) in environment) {
            require(IDENTIFIER.matches(name) && value.length <= MAX_PROPERTY_DEFINE_VALUE_LENGTH) {
                "Invalid shader properties preprocessor definition $name"
            }
            defines[name] = value
        }
        return defines
    }

    private fun preprocessProperties(source: String?, definitions: Map<String, String>): String? {
        if (source == null) return null
        return PropertiesPreprocessor(definitions).process(source)
    }

    private fun preprocessShaderInspection(source: String, definitions: Map<String, String>): String {
        val preservedDirectives = mutableListOf<String>()
        val lineComments = GLSLCommentStripper(preserveBlockComments = true)
        val withoutLineComments = source.lineSequence().joinToString("\n") { lineComments.strip(it) }
        val protected = BLOCK_COMMENT.replace(withoutLineComments) { comment ->
            val value = comment.value
            if (
                RENDER_TARGETS.containsMatchIn(value) ||
                DRAW_BUFFERS.containsMatchIn(value) ||
                BUFFER_COMMENT_DIRECTIVES.any { it.containsMatchIn(value) }
            ) {
                val index = preservedDirectives.size
                preservedDirectives += value
                "__MINOSOFT_SHADER_DIRECTIVE_${index}__"
            } else {
                value
            }
        }
        val comments = GLSLCommentStripper()
        val uncommented = protected.lineSequence().joinToString("\n") { comments.strip(it) }
        var processed = PropertiesPreprocessor(
            definitions,
            preserveDefinitions = true,
            requireBalanced = false,
        ).process(uncommented)
        preservedDirectives.forEachIndexed { index, directive ->
            processed = processed.replace("__MINOSOFT_SHADER_DIRECTIVE_${index}__", directive)
        }
        return processed
    }

    private fun parseProperties(source: String?): Map<String, String> {
        if (source == null) return emptyMap()
        val properties = linkedMapOf<String, String>()
        val logical = mutableListOf<Pair<Int, String>>()
        val pending = StringBuilder()
        var pendingLine = 0
        source.lineSequence().forEachIndexed { index, physical ->
            if (pending.isEmpty()) pendingLine = index
            val trimmedEnd = physical.trimEnd()
            val continuation = trimmedEnd.takeLastWhile { it == '\\' }.length % 2 == 1
            pending.append(if (continuation) trimmedEnd.dropLast(1) else physical)
            if (continuation) {
                pending.append(' ')
            } else {
                logical += pendingLine to pending.toString()
                pending.setLength(0)
            }
        }
        if (pending.isNotEmpty()) logical += pendingLine to pending.toString()
        logical.forEach { (index, line) ->
            val match = PROPERTY.matchEntire(line) ?: return@forEach
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].substringBefore('#').trim()
            require(key.isNotEmpty() && value.isNotEmpty()) {
                "Invalid shaders.properties entry on line ${index + 1}"
            }
            val previous = properties.putIfAbsent(key, value)
            require(previous == null || previous == value) {
                "shaders.properties declares $key inconsistently"
            }
        }
        return properties
    }

    private fun customUniforms(properties: Map<String, String>): IrisCustomUniformPlan {
        val definitions = properties.mapNotNull { (key, expression) ->
            val match = CUSTOM_UNIFORM_PROPERTY.matchEntire(key) ?: return@mapNotNull null
            require(expression.length <= MAX_PROPERTY_CONDITION_LENGTH) {
                "Custom uniform ${match.groupValues[3]} exceeds $MAX_PROPERTY_CONDITION_LENGTH expression characters"
            }
            IrisCustomUniformDefinition(
                name = match.groupValues[3],
                type = IrisCustomUniformType.valueOf(match.groupValues[2].uppercase()),
                expression = expression,
                uniform = match.groupValues[1] == "uniform",
            )
        }
        require(definitions.size <= MAX_CUSTOM_UNIFORMS) {
            "Shader pack declares more than $MAX_CUSTOM_UNIFORMS custom uniforms and variables"
        }
        return IrisCustomUniformPlan(definitions)
    }

    private fun parseProfiles(
        properties: Map<String, String>,
        options: Map<String, ShaderPackOption>,
    ): List<ShaderPackProfile> {
        val definitions = properties.entries.asSequence()
            .filter { it.key.startsWith("profile.") }
            .associateTo(linkedMapOf()) { it.key.removePrefix("profile.") to it.value }
        require(definitions.size <= MAX_PROFILES) {
            "shaders.properties declares too many profiles"
        }
        require(definitions.keys.none(String::isBlank)) {
            "shaders.properties declares a blank profile name"
        }
        val resolved = linkedMapOf<String, ShaderPackProfile>()

        fun resolve(name: String, stack: MutableList<String>): ShaderPackProfile {
            resolved[name]?.let { return it }
            require(name in definitions) { "Shader profile $name is not declared" }
            require(name !in stack) {
                "Shader profile inheritance cycle: ${(stack + name).joinToString(" -> ")}"
            }
            stack += name
            try {
                val values = linkedMapOf<String, String>()
                val disabled = mutableListOf<String>()
                val tokens = definitions.getValue(name)
                    .split(Regex("\\s+"))
                    .filter(String::isNotBlank)
                require(tokens.size <= MAX_PROFILE_TOKENS) {
                    "Shader profile $name declares too many entries"
                }
                for (token in tokens) {
                    when {
                        token.startsWith("!program.") -> {
                            val program = token.removePrefix("!program.")
                            require(program.isNotBlank()) {
                                "Shader profile $name declares a blank disabled program"
                            }
                            disabled += program
                        }

                        token.startsWith("profile.") -> {
                            val inherited = resolve(token.removePrefix("profile."), stack)
                            values.putAll(inherited.optionValues)
                            disabled += inherited.disabledPrograms
                        }

                        token.startsWith('!') -> {
                            val option = token.removePrefix("!")
                            val declared = options[option] ?: continue
                            require(declared.values == BOOLEAN_VALUES) {
                                "Shader profile $name references non-boolean option $option"
                            }
                            values[option] = "false"
                        }

                        '=' in token || ':' in token -> {
                            val separator = listOf(token.indexOf('='), token.indexOf(':'))
                                .filter { it >= 0 }
                                .min()
                            val option = token.substring(0, separator)
                            val value = token.substring(separator + 1)
                            if (option !in options) continue
                            validateProfileOption(name, option, value, options)
                            values[option] = value
                        }

                        else -> {
                            val declared = options[token] ?: continue
                            require(declared.values == BOOLEAN_VALUES) {
                                "Shader profile $name references non-boolean option $token"
                            }
                            values[token] = "true"
                        }
                    }
                }
                return ShaderPackProfile(name, values.toMap(), disabled.distinct()).also {
                    resolved[name] = it
                }
            } finally {
                stack.removeAt(stack.lastIndex)
            }
        }

        return definitions.keys.map { resolve(it, mutableListOf()) }
    }

    private fun validateProfileOption(
        profile: String,
        name: String,
        value: String,
        options: Map<String, ShaderPackOption>,
    ) {
        val option = requireNotNull(options[name]) {
            "Shader profile $profile references unknown option $name"
        }
        require(value == option.defaultValue || value in option.values) {
            "Shader profile $profile assigns invalid value '$value' to $name"
        }
    }

    private fun selectProfile(
        profiles: List<ShaderPackProfile>,
        optionValues: Map<String, String>,
    ): ShaderPackProfile? = profiles.withIndex()
        .filter { (_, profile) ->
            profile.optionValues.all { (name, value) -> optionValues[name] == value }
        }
        .minWithOrNull(
            compareBy<IndexedValue<ShaderPackProfile>>(
                { -it.value.optionValues.size },
                IndexedValue<ShaderPackProfile>::index,
            ),
        )
        ?.value

    private fun parseScreen(
        properties: Map<String, String>,
        id: String?,
    ): ShaderPackScreen? {
        val key = id?.let { "screen.$it" } ?: "screen"
        val columnsKey = "$key.columns"
        val tokens = properties[key]
            ?.split(Regex("\\s+"))
            ?.filter(String::isNotBlank)
        val columns = properties[columnsKey]?.toIntOrNull()
        if (tokens == null && properties[columnsKey] == null) return null
        require(tokens.orEmpty().size <= MAX_SCREEN_TOKENS) {
            "shaders.properties $key declares too many entries"
        }
        require(properties[columnsKey] == null || columns != null && columns in 1..16) {
            "shaders.properties $columnsKey must be an integer in 1..16"
        }
        return ShaderPackScreen(id, tokens.orEmpty(), columns)
    }

    private fun validateScreens(
        main: ShaderPackScreen?,
        subScreens: List<ShaderPackScreen>,
        options: Map<String, ShaderPackOption>,
    ) {
        val ids = subScreens.mapNotNull(ShaderPackScreen::id).toSet()
        val references = linkedMapOf<String, List<String>>()
        (listOfNotNull(main) + subScreens).forEach { screen ->
            val nested = screen.entries.mapNotNull { entry ->
                when {
                    entry == "*" ||
                        entry == "<empty>" ||
                        entry == "[profile]" ||
                        entry == "<profile>" -> null
                    entry.startsWith('[') && entry.endsWith(']') -> {
                        val id = entry.substring(1, entry.lastIndex)
                        if (id.endsWith("_IS_NOT_SUPPORTED") && id !in ids) {
                            return@mapNotNull null
                        }
                        require(id in ids) {
                            "Shader option screen references unknown subscreen $entry"
                        }
                        id
                    }

                    else -> {
                        require(entry in options) {
                            "Shader option screen references unknown option $entry"
                        }
                        null
                    }
                }
            }
            screen.id?.let { references[it] = nested }
        }

        val resolved = mutableSetOf<String>()
        fun visit(id: String, stack: MutableList<String>) {
            if (id in resolved) return
            require(id !in stack) {
                "Shader option screen cycle: ${(stack + id).joinToString(" -> ")}"
            }
            stack += id
            references[id].orEmpty().forEach { visit(it, stack) }
            stack.removeAt(stack.lastIndex)
            resolved += id
        }
        references.keys.forEach { visit(it, mutableListOf()) }
    }

    private fun idMaps(
        files: Map<String, String>,
        definitions: Map<String, String>,
    ): IrisIdMaps = IrisIdMaps(
        items = parseIdTable(preprocessProperties(files["item.properties"], definitions), "item."),
        entities = parseIdTable(preprocessProperties(files["entity.properties"], definitions), "entity."),
        blocks = parseBlockIdRules(preprocessProperties(files["block.properties"], definitions)),
    )

    private fun parseIdTable(source: String?, prefix: String): IrisIdTable {
        if (source == null) return IrisIdTable()
        val values = linkedMapOf<ResourceLocation, Int>()
        for ((key, value) in parseMaterialProperties(source)) {
            if (!key.startsWith(prefix)) continue
            val id = key.removePrefix(prefix).toIntOrNull() ?: continue
            for (token in value.split(Regex("\\s+"))) {
                if (token.isEmpty() || '=' in token) continue
                val identifier = runCatching { ResourceLocation.of(token) }.getOrNull() ?: continue
                values[identifier] = id
            }
        }
        return IrisIdTable(values.toMap(), missing = -1)
    }

    private fun parseBlockIdRules(source: String?): List<IrisBlockIdRule> {
        if (source == null) return IrisLegacyBlockIds.RULES
        val groups = linkedMapOf<Int, List<IrisBlockIdRule>>()
        for ((key, value) in parseMaterialProperties(source)) {
            if (!key.startsWith("block.")) continue
            val id = key.removePrefix("block.").toIntOrNull() ?: continue
            groups[id] = value.split(Regex("\\s+")).mapNotNull { token ->
                parseBlockIdRule(id, token)
            }
        }
        return groups.values.flatten()
    }

    private fun parseBlockIdRule(id: Int, raw: String): IrisBlockIdRule? {
        if (raw.isEmpty()) return null
        val tag = raw.startsWith('%')
        val token = if (tag) raw.replace("%", "") else raw
        val parts = token.split(':')
        if (parts.isEmpty()) return null
        val propertyStart: Int
        val identifier = when {
            parts.size == 1 -> {
                propertyStart = 1
                ResourceLocation.of(parts[0])
            }
            '=' in parts[1] -> {
                propertyStart = 1
                ResourceLocation.of(parts[0])
            }
            else -> {
                propertyStart = 2
                ResourceLocation(parts[0], parts[1])
            }
        }
        val properties = linkedMapOf<String, String>()
        for (index in propertyStart until parts.size) {
            val predicate = parts[index].split('=', limit = 2)
            if (predicate.size != 2 || predicate[0].isEmpty() || predicate[1].isEmpty()) continue
            properties[predicate[0]] = predicate[1]
        }
        return IrisBlockIdRule(id, identifier, tag, properties.toMap())
    }

    /**
     * Bounded subset of java.util.Properties semantics used by Iris material
     * maps: ordered keys, comments, whitespace separators, and continuations.
     */
    private fun parseMaterialProperties(source: String): Map<String, String> {
        val logical = mutableListOf<String>()
        val pending = StringBuilder()
        source.lineSequence().forEach { physical ->
            val trimmedEnd = physical.trimEnd()
            val continuation = trimmedEnd.takeLastWhile { it == '\\' }.length % 2 == 1
            pending.append(if (continuation) trimmedEnd.dropLast(1) else physical)
            if (continuation) {
                pending.append(' ')
            } else {
                logical += pending.toString()
                pending.setLength(0)
            }
        }
        if (pending.isNotEmpty()) logical += pending.toString()

        val properties = linkedMapOf<String, String>()
        for (line in logical) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('!')) continue
            val separator = trimmed.indexOfFirst { it == '=' || it == ':' || it.isWhitespace() }
            if (separator < 0) continue
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator).trimStart(' ', '\t', '\u000c', '=', ':')
            if (key.isEmpty()) continue
            properties[key] = value
        }
        return properties
    }

    private fun resourceUsage(
        program: ShaderProgramSource,
        properties: Map<String, String>,
        waterShadow: Boolean,
        textures: IrisTexturePlan,
        customResources: IrisCustomResourcePlan,
    ): ShaderProgramResourceUsage {
        val writes = when (program.phase) {
            ShaderProgramPhase.FINAL -> emptyList()
            ShaderProgramPhase.SHADOW,
            ShaderProgramPhase.SHADOW_COMPOSITE,
            -> renderTargets(
                program.name,
                program.phase,
                program.inspectionFragment ?: program.fragment,
                ShaderBufferKind.SHADOWCOLOR,
            )
            else -> renderTargets(
                program.name,
                program.phase,
                program.inspectionFragment ?: program.fragment,
                ShaderBufferKind.COLORTEX,
            )
        }
        val custom = customTextures(program, textures)
        val imageSamplers = customResources.images.mapNotNull { image ->
            image.sampler?.takeIf { it in program.samplers }?.let { it to image.name }
        }.toMap(linkedMapOf())
        val images = customResources.images.mapNotNullTo(linkedSetOf()) { image ->
            image.name.takeIf { it in program.uniforms }
        }
        val renderTargetImages = program.uniforms.sorted().mapNotNull { uniform ->
            renderTargetImage(uniform)?.let { uniform to it }
        }.toMap(linkedMapOf())
        val sampled = linkedMapOf<String, ShaderBufferId>()
        for (sampler in program.samplers.sorted()) {
            if (sampler in custom || sampler in imageSamplers) continue
            val buffer = samplerBuffer(sampler, program.phase, waterShadow) ?: continue
            sampled[sampler] = buffer
        }
        val flips = if (program.phase in FULLSCREEN_BUFFER_PHASES) {
            writes.filterTo(linkedSetOf()) { buffer ->
                parseBooleanProperty(properties, "flip.${program.name}.${buffer.sampler}", true)
            }
        } else {
            emptySet()
        }
        val mipmaps = BUFFER_MIPMAP.findAll(program.inspectionFragment ?: program.fragment).mapNotNull { match ->
            if (match.groupValues[2].toBooleanStrict()) {
                samplerBuffer(match.groupValues[1], program.phase, waterShadow)
            } else {
                null
            }
        }.toSet()
        return ShaderProgramResourceUsage(
            colorWrites = writes,
            sampledBuffers = sampled,
            sampledCustomTextures = custom,
            sampledCustomImages = imageSamplers,
            renderTargetImages = renderTargetImages,
            customImages = images,
            flipsAfter = flips,
            mipmapsBefore = mipmaps,
        )
    }

    private fun texturePlan(
        properties: Map<String, String>,
        files: Map<String, ByteArray>,
        programs: List<ShaderProgramSource>,
    ): IrisTexturePlan {
        val custom = mutableListOf<IrisCustomTextureBinding>()
        for ((key, value) in properties) {
            val stage: IrisTextureStage
            val sampler: String
            if (key.startsWith("customTexture.")) {
                sampler = key.removePrefix("customTexture.")
                stage = IrisTextureStage.GLOBAL
            } else {
                if (!key.startsWith("texture.") || key == "texture.noise") continue
                val suffix = key.removePrefix("texture.")
                val stageName = suffix.substringBefore('.', missingDelimiterValue = "")
                val samplerSuffix = suffix.substringAfter('.', missingDelimiterValue = "")
                require(stageName.isNotEmpty() && samplerSuffix.isNotEmpty()) {
                    "Invalid Iris custom texture directive: $key"
                }
                stage = when (stageName) {
                    "setup" -> IrisTextureStage.SETUP
                    "begin" -> IrisTextureStage.BEGIN
                    "shadowcomp" -> IrisTextureStage.SHADOW_COMPOSITE
                    "prepare" -> IrisTextureStage.PREPARE
                    "gbuffers" -> IrisTextureStage.GBUFFERS_AND_SHADOW
                    "deferred" -> IrisTextureStage.DEFERRED
                    "composite" -> IrisTextureStage.COMPOSITE_AND_FINAL
                    else -> throw IllegalArgumentException("Unknown Iris custom texture stage '$stageName' in $key")
                }
                // Iris 1.7.2 truncates the OptiFine directive suffix at the next dot.
                sampler = samplerSuffix.substringBefore('.')
            }
            require(UNIFORM_NAME.matches(sampler)) { "Invalid Iris custom texture sampler '$sampler' in $key" }
            val id = IrisTextureId("custom:${stage.name.lowercase()}:$sampler")
            custom += IrisCustomTextureBinding(stage, sampler, textureDescriptor(id, value, files))
        }
        require(custom.size <= MAX_CUSTOM_TEXTURES) {
            "Shader pack exceeds $MAX_CUSTOM_TEXTURES custom textures"
        }
        custom.filter { it.texture is IrisCustomTextureDescriptor.Resource }.forEach { binding ->
            val activePrograms = programs.filter { program ->
                binding.sampler in program.samplers &&
                    (binding.stage == IrisTextureStage.GLOBAL || binding.stage == textureStage(program.phase))
            }
            require(activePrograms.isEmpty()) {
                "Iris resource-backed texture '${binding.texture.path}' for sampler ${binding.sampler} " +
                    "requires a source-native bridge in active programs: " +
                    activePrograms.joinToString { it.name }
            }
        }
        val source = stripComments(
            programs.joinToString("\n") { inspectionStages(it).joinToString("\n") },
        )
        val resolutions = NOISE_TEXTURE_RESOLUTION.findAll(source).map { it.groupValues[1].toInt() }.toSet()
        require(resolutions.size <= 1) { "Shader pack declares noiseTextureResolution inconsistently" }
        val resolution = resolutions.singleOrNull() ?: DEFAULT_NOISE_TEXTURE_RESOLUTION
        require(resolution in 1..MAX_CUSTOM_TEXTURE_SIZE) {
            "noiseTextureResolution must be in 1..$MAX_CUSTOM_TEXTURE_SIZE: $resolution"
        }
        val noise = properties["texture.noise"]?.let { path ->
            IrisNoiseTextureDescriptor.Custom(pngTexture(IrisTextureId("noise"), path, files))
        } ?: IrisNoiseTextureDescriptor.Generated(resolution)
        return IrisTexturePlan(custom, noise)
    }

    /**
     * Mirrors the image.* and bufferObject.* grammar in pinned Iris 1.7.2.
     *
     * These descriptors are deliberately immutable and allocation-free. The
     * active render generation validates driver limits and realizes them
     * transactionally after every ordinary shader has compiled.
     */
    private fun customResourcePlan(
        properties: Map<String, String>,
        definitions: Map<String, String>,
    ): IrisCustomResourcePlan {
        val images = properties.asSequence()
            .filter { (key, _) -> key.startsWith("image.") }
            .map { (key, value) -> customImage(key, value, definitions) }
            .toList()
        require(images.size <= MAX_CUSTOM_IMAGES) {
            "Shader pack exceeds $MAX_CUSTOM_IMAGES Iris custom images"
        }

        val buffers = properties.asSequence()
            .filter { (key, _) -> key.startsWith("bufferObject.") }
            .map { (key, value) -> shaderStorageBuffer(key, value, definitions) }
            .toList()
        require(buffers.sumOf { descriptor ->
            if (descriptor.relative) 0L else descriptor.bytesPerElement
        } <= MAX_SHADER_STORAGE_BYTES) {
            "Shader pack exceeds $MAX_SHADER_STORAGE_BYTES bytes of absolute shader storage"
        }
        return IrisCustomResourcePlan(images, buffers)
    }

    private fun computePrograms(
        programFiles: List<String>,
        files: Map<String, String>,
        properties: Map<String, String>,
        optionValues: Map<String, String>,
        declaredOptions: Map<String, ShaderPackOption>,
        disabledPrograms: List<String>,
        inspectionDefines: Map<String, String>,
        textures: IrisTexturePlan,
        customResources: IrisCustomResourcePlan,
        waterShadow: Boolean,
    ): List<IrisComputeProgramSource> {
        return programFiles.asSequence()
            .filter { it.endsWith(".csh") }
            .filter { path ->
                val root = path.substringBeforeLast('.')
                root.substringAfterLast('/') !in disabledPrograms &&
                    programEnabled(root, properties, optionValues, declaredOptions, inspectionDefines)
            }
            .sorted()
            .mapNotNull { path ->
                val name = path.substringAfterLast('/').removeSuffix(".csh")
                val phase = computePhase(name)
                require(
                    phase == ShaderProgramPhase.SETUP ||
                        phase == ShaderProgramPhase.SHADOW ||
                        phase == ShaderProgramPhase.FINAL ||
                        phase in FULLSCREEN_BUFFER_PHASES,
                ) {
                    "Iris compute program $name is not attached to an executable compute phase"
                }
                val resolved = resolve(path, files.getValue(path), files, linkedSetOf(), 0)
                val inspected = try {
                    preprocessShaderInspection(resolved, inspectionDefines)
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Could not inspect Iris compute program $name: ${failure.message}",
                        failure,
                    )
                }
                val source = IrisLegacyShaderTransformer.relocateUnconditionalExtensions(
                    COMPUTE_COMPATIBILITY_VERSION.replace(inspected) { match ->
                        "${match.groupValues[1]}core"
                    },
                )
                val combined = stripComments(source)
                val declaredImages = IMAGE_UNIFORM.findAll(combined).map { it.groupValues[1] }.toSet()
                if (
                    declaredImages.isNotEmpty() &&
                    declaredImages.none { declared ->
                        customResources.images.any { it.name == declared } ||
                            renderTargetImage(declared) != null
                    }
                ) {
                    // Pinned packs retain compute files while platform guards
                    // disable the corresponding image.* descriptors (notably
                    // Complementary on Apple's OpenGL 4.1). Iris does not
                    // publish an executable image program in that selection.
                    return@mapNotNull null
                }
                val declaredUniforms = UNIFORM.findAll(combined).map { it.groupValues[1] }.toSet()
                val withoutDeclarations = UNIFORM.replace(combined, "")
                val uniforms = declaredUniforms.filterTo(linkedSetOf()) { uniform ->
                    Regex("""\b${Regex.escape(uniform)}\b""").containsMatchIn(withoutDeclarations)
                }
                val samplers = SAMPLER.findAll(combined)
                    .map { it.groupValues[1] }
                    .filterTo(linkedSetOf()) { it in uniforms }
                val usage = computeResourceUsage(
                    name,
                    phase,
                    uniforms,
                    samplers,
                    textures,
                    customResources,
                    waterShadow,
                )
                IrisComputeProgramSource(
                    name = name,
                    phase = phase,
                    source = source,
                    uniforms = uniforms,
                    samplers = samplers,
                    dispatch = computeDispatch(name, combined, properties, customResources),
                    resourceUsage = usage,
                )
            }
            .toList()
    }

    private fun computeResourceUsage(
        name: String,
        phase: ShaderProgramPhase,
        uniforms: Set<String>,
        samplers: Set<String>,
        textures: IrisTexturePlan,
        customResources: IrisCustomResourcePlan,
        waterShadow: Boolean,
    ): ShaderProgramResourceUsage {
        val program = ShaderProgramSource(
            name = name,
            phase = phase,
            vertex = "#version 330 core\nvoid main(){}",
            fragment = "#version 330 core\nvoid main(){}",
            uniforms = uniforms,
            samplers = samplers,
        )
        val custom = customTextures(program, textures)
        val imageSamplers = customResources.images.mapNotNull { image ->
            image.sampler?.takeIf { it in samplers }?.let { it to image.name }
        }.toMap(linkedMapOf())
        val images = customResources.images.mapNotNullTo(linkedSetOf()) { image ->
            image.name.takeIf { it in uniforms }
        }
        val renderTargetImages = uniforms.sorted().mapNotNull { uniform ->
            renderTargetImage(uniform)?.let { uniform to it }
        }.toMap(linkedMapOf())
        val sampled = samplers.sorted().mapNotNull { sampler ->
            if (sampler in custom || sampler in imageSamplers) return@mapNotNull null
            samplerBuffer(sampler, phase, waterShadow)?.let { sampler to it }
        }.toMap(linkedMapOf())
        return ShaderProgramResourceUsage(
            sampledBuffers = sampled,
            sampledCustomTextures = custom,
            sampledCustomImages = imageSamplers,
            renderTargetImages = renderTargetImages,
            customImages = images,
        )
    }

    private fun computePhase(name: String): ShaderProgramPhase = when {
        SHADOW_COMPUTE_FAMILY.matches(name) -> ShaderProgramPhase.SHADOW
        FINAL_COMPUTE_FAMILY.matches(name) -> ShaderProgramPhase.FINAL
        else -> phase(name)
    }

    private fun renderTargetImage(name: String): ShaderBufferId? {
        val match = RENDER_TARGET_IMAGE.matchEntire(name) ?: return null
        val kind = when (match.groupValues[1]) {
            "colorimg" -> ShaderBufferKind.COLORTEX
            "shadowcolorimg" -> ShaderBufferKind.SHADOWCOLOR
            else -> return null
        }
        return ShaderBufferId(kind, match.groupValues[2].toInt())
    }

    private fun computeDispatch(
        name: String,
        source: String,
        properties: Map<String, String>,
        customResources: IrisCustomResourcePlan,
    ): IrisComputeDispatch {
        properties["indirect.$name"]?.let { configured ->
            val tokens = configured.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            require(tokens.size == 2) {
                "Invalid Iris indirect.$name directive: expected buffer index and byte offset"
            }
            val bufferIndex = tokens[0].toIntOrNull()
            val offset = tokens[1].toLongOrNull()
            require(bufferIndex != null && bufferIndex in 0..8) {
                "Iris indirect compute buffer index must be in 0..8 for $name: ${tokens[0]}"
            }
            require(offset != null && offset >= 0L && offset % Int.SIZE_BYTES == 0L) {
                "Iris indirect compute offset must be a non-negative multiple of ${Int.SIZE_BYTES} " +
                    "for $name: ${tokens[1]}"
            }
            val buffer = customResources.shaderStorageBuffers.singleOrNull { it.index == bufferIndex }
            require(buffer != null) {
                "Iris indirect compute program $name references undeclared bufferObject.$bufferIndex"
            }
            if (!buffer.relative) {
                require(offset <= buffer.bytesPerElement - INDIRECT_DISPATCH_BYTES) {
                    "Iris indirect compute program $name reads $INDIRECT_DISPATCH_BYTES bytes at " +
                        "offset $offset from ${buffer.bytesPerElement}-byte bufferObject.$bufferIndex"
                }
            }
            return IrisComputeDispatch.Indirect(bufferIndex, offset)
        }
        COMPUTE_WORK_GROUPS.find(source)?.let { match ->
            val values = match.groupValues.drop(1).map(String::toInt)
            require(values.all { it in 1..MAX_COMPUTE_WORK_GROUPS }) {
                "Iris compute program $name work groups must be in 1..$MAX_COMPUTE_WORK_GROUPS"
            }
            return IrisComputeDispatch.Absolute(values[0], values[1], values[2])
        }
        val local = requireNotNull(COMPUTE_LOCAL_SIZE.find(source)) {
            "Iris compute program $name does not declare a local work-group size"
        }
        val localX = local.groupValues[1].toInt()
        val localY = local.groupValues[2].toInt()
        require(localX > 0 && localY > 0) {
            "Iris compute program $name local work-group dimensions must be positive"
        }
        val relative = COMPUTE_RELATIVE_WORK_GROUPS.find(source)
        val widthScale = relative?.groupValues?.get(1)?.toFloat() ?: 1.0f
        val heightScale = relative?.groupValues?.get(2)?.toFloat() ?: 1.0f
        return IrisComputeDispatch.Relative(widthScale, heightScale, localX, localY)
    }

    private fun customImage(
        key: String,
        configured: String,
        definitions: Map<String, String>,
    ): IrisCustomImageDescriptor {
        val name = key.removePrefix("image.")
        require(UNIFORM_NAME.matches(name)) { "Invalid Iris custom-image name '$name' in $key" }
        val tokens = configured.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            .map { definitions[it] ?: it }
        require(tokens.size in 7..9) {
            "Invalid Iris custom-image directive $key: expected 7..9 values, got ${tokens.size}"
        }
        val sampler = tokens[0].takeUnless { it == "none" }?.also {
            require(UNIFORM_NAME.matches(it)) { "Invalid Iris custom-image sampler '$it' in $key" }
        }
        val format = enumToken<IrisCustomImageFormat>(tokens[1], key, "pixel format")
        val internal = enumToken<IrisCustomImageInternalFormat>(tokens[2], key, "internal format")
        val type = enumToken<IrisCustomImageType>(tokens[3], key, "pixel type")
        val clear = booleanToken(tokens[4], key, "clear")
        val relative = booleanToken(tokens[5], key, "relative")
        val target: IrisCustomImageTarget
        val size: IrisCustomImageSize
        if (relative) {
            require(tokens.size == 8) {
                "Relative Iris custom image $key must contain width and height scales"
            }
            target = IrisCustomImageTarget.TEXTURE_2D
            size = IrisCustomImageSize.Relative(
                positiveFloat(tokens[6], key, "width scale"),
                positiveFloat(tokens[7], key, "height scale"),
            )
        } else {
            target = when (tokens.size) {
                7 -> IrisCustomImageTarget.TEXTURE_1D
                8 -> IrisCustomImageTarget.TEXTURE_2D
                9 -> IrisCustomImageTarget.TEXTURE_3D
                else -> error("validated above")
            }
            val dimensions = tokens.drop(6).mapIndexed { index, token ->
                val dimension = token.toIntOrNull()
                require(dimension != null && dimension in 1..MAX_CUSTOM_IMAGE_DIMENSION) {
                    "Iris custom-image ${listOf("width", "height", "depth")[index]} must be in " +
                        "1..$MAX_CUSTOM_IMAGE_DIMENSION in $key: $token"
                }
                dimension
            }
            val width = dimensions[0]
            val height = dimensions.getOrElse(1) { 1 }
            val depth = dimensions.getOrElse(2) { 1 }
            val texels = Math.multiplyExact(
                Math.multiplyExact(width.toLong(), height.toLong()),
                depth.toLong(),
            )
            val bytes = Math.multiplyExact(texels, internal.bytesPerTexel.toLong())
            require(bytes <= MAX_CUSTOM_IMAGE_BYTES) {
                "Iris custom image $key requires $bytes bytes, maximum is $MAX_CUSTOM_IMAGE_BYTES"
            }
            size = IrisCustomImageSize.Absolute(width, height, depth)
        }
        require(imageFormatMatches(format, internal)) {
            "Iris custom image $key has incompatible pixel format $format and internal format $internal"
        }
        return IrisCustomImageDescriptor(name, sampler, target, format, internal, type, clear, size)
    }

    private fun shaderStorageBuffer(
        key: String,
        configured: String,
        definitions: Map<String, String>,
    ): IrisShaderStorageBufferDescriptor {
        val indexToken = key.removePrefix("bufferObject.")
        val index = indexToken.toIntOrNull()
        require(index != null && index in 0..8) {
            "Iris shader-storage buffer index must be in 0..8 in $key"
        }
        val tokens = configured.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            .map { definitions[it] ?: it }
        require(tokens.size == 1 || tokens.size == 4) {
            "Invalid Iris shader-storage directive $key: expected size or size relative widthScale heightScale"
        }
        val bytes = tokens[0].toLongOrNull()
        require(bytes != null && bytes in 1..MAX_SINGLE_SHADER_STORAGE_BYTES) {
            "Iris shader-storage size must be in 1..$MAX_SINGLE_SHADER_STORAGE_BYTES in $key"
        }
        if (tokens.size == 1) return IrisShaderStorageBufferDescriptor(index, bytes)
        val relative = booleanToken(tokens[1], key, "relative")
        require(relative) {
            "Four-value Iris shader-storage directive $key must declare relative=true"
        }
        return IrisShaderStorageBufferDescriptor(
            index = index,
            bytesPerElement = bytes,
            relative = true,
            widthScale = positiveFloat(tokens[2], key, "width scale"),
            heightScale = positiveFloat(tokens[3], key, "height scale"),
        )
    }

    private inline fun <reified T : Enum<T>> enumToken(token: String, key: String, kind: String): T {
        return enumValues<T>().firstOrNull { it.name.equals(token, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown Iris custom-image $kind '$token' in $key")
    }

    private fun booleanToken(token: String, key: String, kind: String): Boolean {
        return token.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Iris $kind must be true or false in $key: $token")
    }

    private fun positiveFloat(token: String, key: String, kind: String): Float {
        val value = token.toFloatOrNull()
        require(value != null && value.isFinite() && value > 0.0f) {
            "Iris custom-resource $kind must be finite and positive in $key: $token"
        }
        return value
    }

    private fun imageFormatMatches(
        format: IrisCustomImageFormat,
        internal: IrisCustomImageInternalFormat,
    ): Boolean {
        val expected = when {
            internal.name.startsWith("RGBA") -> "RGBA"
            internal.name.startsWith("RGB") -> "RGB"
            internal.name.startsWith("RG") -> "RG"
            else -> "RED"
        }
        val integer = internal.name.endsWith("I") || internal.name.endsWith("UI")
        return format.name == if (integer) "${expected}_INTEGER" else expected
    }

    private fun textureDescriptor(
        id: IrisTextureId,
        configured: String,
        files: Map<String, ByteArray>,
    ): IrisCustomTextureDescriptor {
        val tokens = configured.trim().split(Regex("\\s+"))
        if (tokens.size == 1) {
            val requested = configured.trim().removePrefix("/")
            if (':' in requested) {
                return IrisCustomTextureDescriptor.Resource(resourceTexture(id, requested))
            }
            return IrisCustomTextureDescriptor.Png(pngTexture(id, configured, files))
        }
        require(tokens.size in 6..8) { "Invalid Iris raw texture definition: $configured" }
        val target = try {
            IrisRawTextureTarget.valueOf(tokens[1])
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unsupported Iris raw texture target '${tokens[1]}'")
        }
        val expectedTokens = when (target) {
            IrisRawTextureTarget.TEXTURE_1D -> 6
            IrisRawTextureTarget.TEXTURE_2D,
            IrisRawTextureTarget.TEXTURE_RECTANGLE,
            -> 7
            IrisRawTextureTarget.TEXTURE_3D -> 8
        }
        require(tokens.size == expectedTokens) {
            "Iris ${target.name} texture requires $expectedTokens definition tokens: $configured"
        }
        val width = tokens[3].toIntOrNull()
        val height = if (target == IrisRawTextureTarget.TEXTURE_1D) 1 else tokens[4].toIntOrNull()
        val depth = if (target == IrisRawTextureTarget.TEXTURE_3D) tokens[5].toIntOrNull() else 1
        require(width != null && height != null && depth != null) {
            "Iris raw texture dimensions must be integers: $configured"
        }
        require(width in 1..MAX_CUSTOM_TEXTURE_SIZE && height in 1..MAX_CUSTOM_TEXTURE_SIZE &&
            depth in 1..MAX_CUSTOM_TEXTURE_SIZE
        ) {
            "Iris raw texture dimensions exceed $MAX_CUSTOM_TEXTURE_SIZE: ${width}x${height}x$depth"
        }
        val formatIndex = when (target) {
            IrisRawTextureTarget.TEXTURE_1D -> 4
            IrisRawTextureTarget.TEXTURE_2D,
            IrisRawTextureTarget.TEXTURE_RECTANGLE,
            -> 5
            IrisRawTextureTarget.TEXTURE_3D -> 6
        }
        val typeIndex = formatIndex + 1
        val format = try {
            IrisRawTextureFormat.valueOf(tokens[formatIndex])
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unsupported Iris raw texture format '${tokens[formatIndex]}'")
        }
        val type = try {
            IrisRawTextureType.valueOf(tokens[typeIndex])
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unsupported Iris raw texture type '${tokens[typeIndex]}'")
        }
        require(tokens[2] in RAW_INTERNAL_FORMATS) {
            "Unsupported Iris raw texture internal format '${tokens[2]}'"
        }
        val requested = tokens[0].removePrefix("/")
        val path = normalize(requested)
        require(path != "." && path != ".." && !path.startsWith("../") && !path.startsWith('/')) {
            "Iris raw texture escapes the shaders directory: ${tokens[0]}"
        }
        val content = requireNotNull(files[path]) { "Missing Iris raw texture: $path" }
        val filtering = textureFiltering(path, files, defaultBlur = true, defaultClamp = true)
        val expectedBytes = Math.multiplyExact(
            Math.multiplyExact(Math.multiplyExact(width, height), depth),
            Math.multiplyExact(format.components, type.bytes),
        )
        require(expectedBytes <= MAX_CUSTOM_TEXTURE_BYTES && content.size == expectedBytes) {
            "Iris raw texture $path requires $expectedBytes bytes but contains ${content.size}"
        }
        return IrisCustomTextureDescriptor.Raw(
            IrisRawTextureDescriptor(
                id = id,
                path = path,
                content = IrisTextureBytes(content),
                target = target,
                internalFormat = tokens[2],
                width = width,
                height = height,
                depth = depth,
                format = format,
                type = type,
                blur = filtering.first,
                clamp = filtering.second,
            ),
        )
    }

    private fun pngTexture(
        id: IrisTextureId,
        configuredPath: String,
        files: Map<String, ByteArray>,
    ): IrisPngTextureDescriptor {
        val requested = configuredPath.trim().removePrefix("/")
        require(requested.isNotEmpty()) { "Iris PNG texture ${id.value} has an empty path" }
        val path = normalize(requested)
        require(path != "." && path != ".." && !path.startsWith("../") && !path.startsWith('/')) {
            "Iris PNG texture escapes the shaders directory: $configuredPath"
        }
        require(path.endsWith(".png", ignoreCase = true)) {
            "Iris local texture path must reference a PNG: $configuredPath"
        }
        val content = requireNotNull(files[path]) { "Missing Iris PNG texture: $path" }
        val (width, height) = pngSize(content, path)
        val metadata = textureFiltering(path, files, defaultBlur = false, defaultClamp = false)
        return IrisPngTextureDescriptor(
            id = id,
            path = path,
            content = IrisTextureBytes(content),
            width = width,
            height = height,
            blur = metadata.first,
            clamp = metadata.second,
        )
    }

    private fun resourceTexture(
        id: IrisTextureId,
        requested: String,
    ): IrisResourceTextureDescriptor {
        require(RESOURCE_TEXTURE.matches(requested)) {
            "Invalid Iris resource-backed texture location '$requested'"
        }
        val path = requested.substringAfter(':')
        require(
            path.isNotEmpty() &&
                path != "." &&
                path != ".." &&
                !path.startsWith('/') &&
                path.split('/').none { it == "." || it == ".." },
        ) {
            "Iris resource-backed texture escapes its namespace: $requested"
        }
        return IrisResourceTextureDescriptor(id, requested)
    }

    private fun textureFiltering(
        path: String,
        files: Map<String, ByteArray>,
        defaultBlur: Boolean,
        defaultClamp: Boolean,
    ): Pair<Boolean, Boolean> = files["$path.mcmeta"]?.let { bytes ->
            val root = try {
                Jackson.MAPPER.readTree(bytes)
            } catch (failure: Throwable) {
                throw IllegalArgumentException("Invalid Iris texture metadata: $path.mcmeta", failure)
            }
            val texture = root.get("texture")
            if (texture == null || texture.isNull) {
                defaultBlur to defaultClamp
            } else {
                require(texture.isObject) { "Iris texture metadata texture field must be an object: $path.mcmeta" }
                val blur = texture.get("blur")?.let {
                    require(it.isBoolean) { "Iris texture metadata blur must be boolean: $path.mcmeta" }
                    it.booleanValue()
                } ?: defaultBlur
                val clamp = texture.get("clamp")?.let {
                    require(it.isBoolean) { "Iris texture metadata clamp must be boolean: $path.mcmeta" }
                    it.booleanValue()
                } ?: defaultClamp
                blur to clamp
            }
        } ?: (defaultBlur to defaultClamp)

    private fun pngSize(content: ByteArray, path: String): Pair<Int, Int> {
        require(content.size >= 24 && PNG_SIGNATURE.indices.all { content[it] == PNG_SIGNATURE[it] }) {
            "Invalid Iris PNG texture header: $path"
        }
        fun int(offset: Int): Int =
            ((content[offset].toInt() and 0xFF) shl 24) or
                ((content[offset + 1].toInt() and 0xFF) shl 16) or
                ((content[offset + 2].toInt() and 0xFF) shl 8) or
                (content[offset + 3].toInt() and 0xFF)
        val width = int(16)
        val height = int(20)
        require(width in 1..MAX_CUSTOM_TEXTURE_SIZE && height in 1..MAX_CUSTOM_TEXTURE_SIZE) {
            "Iris PNG texture dimensions must be in 1..$MAX_CUSTOM_TEXTURE_SIZE: $path is ${width}x$height"
        }
        require(width.toLong() * height <= MAX_CUSTOM_TEXTURE_PIXELS) {
            "Iris PNG texture exceeds $MAX_CUSTOM_TEXTURE_PIXELS pixels: $path is ${width}x$height"
        }
        return width to height
    }

    private fun customTextures(
        program: ShaderProgramSource,
        plan: IrisTexturePlan,
    ): Map<String, IrisTextureId> {
        val stage = textureStage(program.phase)
        return buildMap {
            if (stage != null) {
                plan.custom
                    .filter {
                        it.stage == stage &&
                            it.sampler in program.samplers &&
                            customTextureMatchesSampler(program, it)
                    }
                    .forEach { put(it.sampler, it.texture.id) }
                // Pinned Iris adds global customTexture samplers after the
                // stage interceptor and ordinary buffer samplers. Preserve
                // that precedence independent of property file order.
                plan.custom
                    .filter {
                        it.stage == IrisTextureStage.GLOBAL &&
                            it.sampler in program.samplers &&
                            customTextureMatchesSampler(program, it)
                    }
                    .forEach { put(it.sampler, it.texture.id) }
            }
            if ("noisetex" in program.samplers) put("noisetex", IrisTextureId("noise"))
        }
    }

    private fun textureStage(phase: ShaderProgramPhase): IrisTextureStage? =
        when (phase) {
            ShaderProgramPhase.SETUP -> IrisTextureStage.SETUP
            ShaderProgramPhase.BEGIN -> IrisTextureStage.BEGIN
            ShaderProgramPhase.SHADOW_COMPOSITE -> IrisTextureStage.SHADOW_COMPOSITE
            ShaderProgramPhase.PREPARE -> IrisTextureStage.PREPARE
            ShaderProgramPhase.DEFERRED -> IrisTextureStage.DEFERRED
            ShaderProgramPhase.COMPOSITE,
            ShaderProgramPhase.FINAL,
            -> IrisTextureStage.COMPOSITE_AND_FINAL
            ShaderProgramPhase.TERRAIN,
            ShaderProgramPhase.SHADOW,
            ShaderProgramPhase.BASIC,
            ShaderProgramPhase.BLOCK,
            ShaderProgramPhase.ITEM,
            ShaderProgramPhase.ENTITY,
            ShaderProgramPhase.PARTICLE,
            ShaderProgramPhase.SKY,
            ShaderProgramPhase.WEATHER,
            ShaderProgramPhase.HAND,
            -> IrisTextureStage.GBUFFERS_AND_SHADOW
            else -> null
        }

    /**
     * Iris rewrites raw custom-texture aliases only when the declared GLSL
     * sampler type matches the raw texture target. This lets a pack reuse a
     * name such as colortex0 for sampler3D noise in one pass and for the
     * sampler2D render buffer in another.
     */
    private fun customTextureMatchesSampler(
        program: ShaderProgramSource,
        binding: IrisCustomTextureBinding,
    ): Boolean {
        val raw = (binding.texture as? IrisCustomTextureDescriptor.Raw)?.value ?: return true
        val expectedSuffix = when (raw.target) {
            IrisRawTextureTarget.TEXTURE_1D -> "sampler1D"
            IrisRawTextureTarget.TEXTURE_2D -> "sampler2D"
            IrisRawTextureTarget.TEXTURE_3D -> "sampler3D"
            IrisRawTextureTarget.TEXTURE_RECTANGLE -> "sampler2DRect"
        }
        val source = stripComments(
            inspectionStages(program).joinToString("\n"),
        )
        return TYPED_SAMPLER.findAll(source).any { match ->
            match.groupValues[2] == binding.sampler &&
                match.groupValues[1].endsWith(expectedSuffix)
        }
    }

    private fun renderTargets(
        program: String,
        phase: ShaderProgramPhase,
        fragment: String,
        kind: ShaderBufferKind,
    ): List<ShaderBufferId> {
        val renderTargets = RENDER_TARGETS.findAll(fragment).map { it.groupValues[1] }.toList()
        val drawBuffers = DRAW_BUFFERS.findAll(fragment).map { it.groupValues[1] }.toList()
        require(renderTargets.isEmpty() || drawBuffers.isEmpty()) {
            "Fragment shader $program declares conflicting RENDERTARGETS/DRAWBUFFERS directives"
        }
        val indices = when {
            // Shader packs may establish a base output list and then replace
            // it in a later active option branch. The last surviving
            // declaration is the effective Iris/OptiFine program contract.
            renderTargets.isNotEmpty() -> renderTargets.last()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::toInt)

            drawBuffers.isNotEmpty() -> drawBuffers.last()
                .filterNot(Char::isWhitespace)
                .mapNotNull { character ->
                    if (character == 'N' || character == 'n') null else character.digitToInt()
                }

            kind == ShaderBufferKind.SHADOWCOLOR -> listOf(0, 1)
            phase != ShaderProgramPhase.TERRAIN -> listOf(0)
            else -> (0..7).toList()
        }
        require(indices.isNotEmpty()) { "Fragment shader must write at least one color target" }
        require(indices.toSet().size == indices.size) { "Fragment shader declares duplicate render targets: $indices" }
        return indices.map { ShaderBufferId(kind, it) }
    }

    private fun samplerBuffer(
        sampler: String,
        phase: ShaderProgramPhase,
        waterShadow: Boolean,
    ): ShaderBufferId? {
        when (sampler) {
            "dhDepthTex", "dhDepthTex0" ->
                return ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 0)
            "dhDepthTex1" ->
                return ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 1)
        }
        BUFFER_SAMPLER.matchEntire(sampler)?.let { match ->
            val kind = when (match.groupValues[1]) {
                "colortex" -> ShaderBufferKind.COLORTEX
                "depthtex" -> ShaderBufferKind.DEPTHTEX
                "shadowtex" -> ShaderBufferKind.SHADOWTEX
                "shadowcolor" -> ShaderBufferKind.SHADOWCOLOR
                else -> return@let
            }
            val index = match.groupValues[2].toInt()
            if (index > kind.maxIndex) return null
            return ShaderBufferId(kind, index)
        }
        LEGACY_COLORTEX[sampler]?.let { return ShaderBufferId(ShaderBufferKind.COLORTEX, it) }
        return when (sampler) {
            "waterShadow" -> ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0)
            "shadow" -> ShaderBufferId(ShaderBufferKind.SHADOWTEX, if (waterShadow) 1 else 0)
            "shadowcolor" -> ShaderBufferId(ShaderBufferKind.SHADOWCOLOR, 0)
            "uTexture" -> when {
                phase == ShaderProgramPhase.SHADOW_COMPOSITE ->
                    ShaderBufferId(ShaderBufferKind.SHADOWCOLOR, 0)
                phase in FULLSCREEN_BUFFER_PHASES || phase == ShaderProgramPhase.FINAL ->
                    ShaderBufferId(ShaderBufferKind.COLORTEX, 0)
                else -> null
            }
            else -> null
        }
    }

    private fun bufferPlan(
        programs: List<ShaderProgramSource>,
        computePrograms: List<IrisComputeProgramSource>,
        properties: Map<String, String>,
        shadow: Boolean,
    ): ShaderBufferPlan {
        val referenced = linkedSetOf(
            ShaderBufferId(ShaderBufferKind.COLORTEX, 0),
            ShaderBufferId(ShaderBufferKind.DEPTHTEX, 0),
        )
        programs.forEach { program ->
            referenced += program.resourceUsage.colorWrites
            referenced += program.resourceUsage.sampledBuffers.values
            referenced += program.resourceUsage.renderTargetImages.values
            referenced += program.resourceUsage.mipmapsBefore
        }
        computePrograms.forEach { program ->
            referenced += program.resourceUsage.sampledBuffers.values
            referenced += program.resourceUsage.renderTargetImages.values
            referenced += program.resourceUsage.mipmapsBefore
        }
        if (shadow) {
            referenced += ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0)
            referenced += ShaderBufferId(ShaderBufferKind.SHADOWTEX, 1)
        }
        require(shadow || referenced.none {
            it.kind == ShaderBufferKind.SHADOWTEX || it.kind == ShaderBufferKind.SHADOWCOLOR
        }) {
            "Shader pack samples shadow buffers but declares no executable shadow program"
        }
        val source = programs.joinToString("\n") {
            inspectionStages(it).joinToString("\n")
        } + "\n" + computePrograms.joinToString("\n", transform = IrisComputeProgramSource::source)
        val directiveSource = bufferDirectiveSource(source)
        val formats = uniqueDirectives(BUFFER_FORMAT, directiveSource, "format")
        val clears = uniqueDirectives(BUFFER_CLEAR, directiveSource, "clear")
        val clearColors = uniqueDirectives(BUFFER_CLEAR_COLOR, directiveSource, "clear color")
        val nearest = uniqueDirectives(BUFFER_NEAREST, directiveSource, "filter")
        val shadowResolution = SHADOW_MAP_RESOLUTION.findAll(directiveSource).map { it.groupValues[1].toInt() }.toSet()
        require(shadowResolution.size <= 1) { "Shader pack declares shadowMapResolution inconsistently" }
        val legacyShadowResolution = LEGACY_SHADOW_MAP_RESOLUTION.findAll(directiveSource)
            .map { it.groupValues[1].toInt() }
            .toSet()
        require(legacyShadowResolution.size <= 1) { "Shader pack declares SHADOWRES inconsistently" }
        // Pinned Iris accepts the legacy comment first and then lets the
        // standard const directive override it.
        val shadowSize = shadowResolution.singleOrNull()
            ?: legacyShadowResolution.singleOrNull()
            ?: DEFAULT_SHADOW_RESOLUTION
        require(shadowSize in 1..MAX_FIXED_BUFFER_SIZE) {
            "shadowMapResolution must be in 1..$MAX_FIXED_BUFFER_SIZE: $shadowSize"
        }
        val usesLegacyGdepth = programs.any { "gdepth" in it.samplers }
        val descriptors = referenced.sorted().map { id ->
            val color = id.kind == ShaderBufferKind.COLORTEX || id.kind == ShaderBufferKind.SHADOWCOLOR
            val format = if (color) {
                val declared = formats[id.sampler]
                val value = if (declared == null && id == ShaderBufferId(ShaderBufferKind.COLORTEX, 1) && usesLegacyGdepth) {
                    RenderColorFormat.RGBA32F
                } else {
                    parseColorFormat(declared ?: "RGBA")
                }
                ShaderBufferFormat.Color(value)
            } else {
                ShaderBufferFormat.Depth(
                    if (id.kind == ShaderBufferKind.DHDEPTHTEX) {
                        RenderDepthFormat.DEPTH32F
                    } else {
                        RenderDepthFormat.DEPTH24
                    },
                )
            }
            val size = when (id.kind) {
                ShaderBufferKind.COLORTEX,
                ShaderBufferKind.DEPTHTEX,
                ShaderBufferKind.DHDEPTHTEX,
                -> parseBufferSize(properties[id.samplerSizeProperty])
                    ?: RenderTargetSize.Relative(1.0f)

                ShaderBufferKind.SHADOWTEX,
                ShaderBufferKind.SHADOWCOLOR,
                -> RenderTargetSize.Fixed(shadowSize, shadowSize)
            }
            val clear = when {
                !color -> RenderClearPolicy.CLEAR
                clears[id.sampler]?.toBooleanStrictOrNull() == false -> RenderClearPolicy.LOAD
                else -> RenderClearPolicy.CLEAR
            }
            val clearColor = clearColors[id.sampler]?.let(::parseClearColor) ?: when (id.kind) {
                ShaderBufferKind.COLORTEX -> when (id.index) {
                    0 -> ShaderBufferClearColor.Fog
                    1 -> ShaderBufferClearColor.Fixed(WHITE_CLEAR)
                    else -> ShaderBufferClearColor.Fixed(BLACK_CLEAR)
                }

                ShaderBufferKind.SHADOWCOLOR,
                ShaderBufferKind.DEPTHTEX,
                ShaderBufferKind.DHDEPTHTEX,
                ShaderBufferKind.SHADOWTEX,
                -> ShaderBufferClearColor.Fixed(WHITE_CLEAR)
            }
            val filter = when {
                id.kind == ShaderBufferKind.SHADOWTEX &&
                    parseBooleanProperty(properties, "shadowHardwareFiltering", false) -> ShaderBufferFilter.SHADOW_COMPARE

                nearest[id.sampler]?.toBooleanStrictOrNull() == true -> ShaderBufferFilter.NEAREST
                else -> ShaderBufferFilter.LINEAR
            }
            ShaderBufferDescriptor(
                id = id,
                format = format,
                size = size,
                clear = clear,
                clearColor = clearColor,
                filter = filter,
                mipmapped = programs.any { id in it.resourceUsage.mipmapsBefore },
                doubleBuffered = color,
            )
        }
        val byId = descriptors.associateBy(ShaderBufferDescriptor::id)
        programs.forEach { program ->
            val sizes = program.resourceUsage.colorWrites.mapTo(linkedSetOf()) { id ->
                requireNotNull(byId[id]).size
            }
            require(sizes.size <= 1) {
                "${program.name} writes buffers with incompatible sizes: $sizes"
            }
            if (program.phase !in FULLSCREEN_BUFFER_PHASES && program.phase != ShaderProgramPhase.SHADOW) {
                require(sizes.all { it == RenderTargetSize.Relative(1.0f) }) {
                    "${program.name} is a geometry program and cannot write resized colortex buffers"
                }
            }
        }
        return ShaderBufferPlan(descriptors)
    }

    private fun bufferDirectiveSource(source: String): String {
        return BLOCK_COMMENT.replace(source) { comment ->
            val value = comment.value
            if (BUFFER_COMMENT_DIRECTIVES.any { it.containsMatchIn(value) }) {
                value.removePrefix("/*").removeSuffix("*/")
            } else {
                value
            }
        }
    }

    private val ShaderBufferId.samplerSizeProperty: String
        get() = "size.buffer.$sampler"

    private fun uniqueDirectives(regex: Regex, source: String, label: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        regex.findAll(source).forEach { match ->
            val name = match.groupValues[1]
            val value = match.groupValues[2].trim()
            val previous = values.putIfAbsent(name, value)
            require(previous == null || previous == value) {
                "Shader pack declares $name $label inconsistently"
            }
        }
        return values
    }

    private fun parseClearColor(source: String): ShaderBufferClearColor.Fixed {
        val values = source.split(',').map { component ->
            component.trim().removeSuffix("f").toFloat()
        }
        return ShaderBufferClearColor.Fixed(values)
    }

    private fun parseBufferSize(source: String?): RenderTargetSize? {
        if (source == null) return null
        val values = source.split(Regex("\\s+")).filter(String::isNotEmpty)
        require(values.size == 2) { "Buffer size must contain width and height: $source" }
        val fixed = values.all { INTEGER.matches(it) }
        require(fixed || values.none { INTEGER.matches(it) }) {
            "Buffer size dimensions must both be fixed or both be relative: $source"
        }
        return if (fixed) {
            val width = values[0].toInt()
            val height = values[1].toInt()
            require(width <= MAX_FIXED_BUFFER_SIZE && height <= MAX_FIXED_BUFFER_SIZE) {
                "Fixed shader buffer size exceeds $MAX_FIXED_BUFFER_SIZE: $source"
            }
            RenderTargetSize.Fixed(width, height)
        } else {
            RenderTargetSize.Relative(values[0].toFloat(), values[1].toFloat())
        }
    }

    private fun parseColorFormat(source: String): RenderColorFormat {
        val normalized = when (source) {
            "RGBA" -> "RGBA8"
            else -> source
        }
        return try {
            RenderColorFormat.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unsupported Iris color-buffer format: $source")
        }
    }

    private fun parseBooleanProperty(
        properties: Map<String, String>,
        name: String,
        default: Boolean,
    ): Boolean {
        val source = properties[name] ?: return default
        return source.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("shaders.properties $name must be true or false")
    }

    private fun resources(
        shadow: Boolean,
        buffers: ShaderBufferPlan,
    ): RenderResourcePlan {
        val targets = mutableListOf(
            RenderTargetDescriptor(
                id = RenderResourceId("minosoft:main-world"),
                size = RenderTargetSize.Relative(1.0f),
                colorAttachments = listOf(
                    RenderColorAttachment(RenderResourceId("minosoft:main-world-color"), RenderColorFormat.RGBA8),
                ),
                depth = RenderDepthFormat.DEPTH24,
            ),
        )
        if (shadow) {
            val shadowSize = buffers[ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0)]?.size
                ?: RenderTargetSize.Fixed(DEFAULT_SHADOW_RESOLUTION, DEFAULT_SHADOW_RESOLUTION)
            targets += RenderTargetDescriptor(
                id = RenderResourceId("iris:shadow"),
                size = shadowSize,
                colorAttachments = listOf(
                    RenderColorAttachment(
                        RenderResourceId("iris:shadowcolor0"),
                        RenderColorFormat.RGBA8,
                        RenderClearPolicy.CLEAR,
                    ),
                ),
                depth = RenderDepthFormat.DEPTH24,
            )
        }
        return RenderResourcePlan(targets, emptyList())
    }

    private fun fingerprint(
        rawFiles: Map<String, ByteArray>,
        effectiveTextFiles: Map<String, String>,
        programDirectory: String?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (programDirectory != null) {
            digest.update("minosoft:iris-program-directory".toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(programDirectory.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        rawFiles.toSortedMap().forEach { (path, raw) ->
            digest.update(path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(effectiveTextFiles[path]?.toByteArray(StandardCharsets.UTF_8) ?: raw)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class BooleanConditionParser(
        private val source: String,
        private val option: (String) -> Boolean,
    ) {
        private var index = 0
        private var depth = 0

        fun parse(): Boolean {
            val value = parseOr()
            whitespace()
            require(index == source.length) {
                "Invalid shader program condition near '${source.substring(index).take(32)}'"
            }
            return value
        }

        private fun parseOr(): Boolean {
            var value = parseAnd()
            while (consume("||")) {
                val right = parseAnd()
                value = value || right
            }
            return value
        }

        private fun parseAnd(): Boolean {
            var value = parseUnary()
            while (consume("&&")) {
                val right = parseUnary()
                value = value && right
            }
            return value
        }

        private fun parseUnary(): Boolean {
            whitespace()
            if (consume("!")) return !parseUnary()
            if (consume("(")) {
                depth++
                require(depth <= MAX_PROGRAM_CONDITION_DEPTH) {
                    "Shader program condition exceeds $MAX_PROGRAM_CONDITION_DEPTH nested groups"
                }
                val value = parseOr()
                require(consume(")")) { "Shader program condition has an unmatched '('" }
                depth--
                return value
            }
            val start = index
            while (index < source.length && (source[index] == '_' || source[index].isLetterOrDigit())) index++
            require(index > start && (source[start] == '_' || source[start].isLetter())) {
                "Shader program condition expects a boolean option near '${source.substring(start).take(32)}'"
            }
            return when (val name = source.substring(start, index)) {
                "true" -> true
                "false" -> false
                else -> option(name)
            }
        }

        private fun consume(token: String): Boolean {
            whitespace()
            if (!source.startsWith(token, index)) return false
            index += token.length
            return true
        }

        private fun whitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }

    private class PropertiesPreprocessor(
        initialDefinitions: Map<String, String>,
        private val preserveDefinitions: Boolean = false,
        private val requireBalanced: Boolean = true,
    ) {
        private val definitions = initialDefinitions.toMutableMap()
        private val frames = mutableListOf<ConditionalFrame>()

        fun process(source: String): String {
            val output = StringBuilder(source.length)
            logicalLines(source).forEachIndexed { index, line ->
                val directive = PROPERTY_DIRECTIVE.matchEntire(line)
                if (directive == null) {
                    if (active()) output.appendLine(line)
                    return@forEachIndexed
                }
                val command = directive.groupValues[1]
                val argument = directive.groupValues[2].substringBefore("//").trim()
                when (command) {
                    "if" -> push(active() && expression(argument, index), index, "#if $argument")
                    "ifdef" -> push(active() && defined(argument, index), index, "#ifdef $argument")
                    "ifndef" -> push(active() && !defined(argument, index), index, "#ifndef $argument")
                    "elif" -> alternate(argument, index)
                    "else" -> otherwise(index)
                    "endif" -> close(index)
                    "define" -> if (active()) {
                        define(argument, index)
                        if (preserveDefinitions) output.appendLine(line)
                    }
                    "undef" -> if (active()) {
                        definitions.remove(identifier(argument, index))
                        if (preserveDefinitions) output.appendLine(line)
                    }
                    else -> if (active()) output.appendLine(line)
                }
            }
            require(!requireBalanced || frames.isEmpty()) {
                "Unclosed shaders.properties conditional blocks: " +
                    frames.joinToString { "line ${it.line + 1} '${it.condition.take(64)}'" }
            }
            return output.toString()
        }

        private fun active(): Boolean = frames.lastOrNull()?.active ?: true

        private fun push(matches: Boolean, line: Int, condition: String) {
            require(frames.size < MAX_PROPERTY_CONDITION_DEPTH) {
                "shaders.properties conditional exceeds $MAX_PROPERTY_CONDITION_DEPTH levels on line ${line + 1}"
            }
            val parentActive = active()
            frames += ConditionalFrame(parentActive, matches, matches, line = line, condition = condition)
        }

        private fun alternate(argument: String, line: Int) {
            val frame = frames.lastOrNull()
            if (frame == null && !requireBalanced) return
            requireNotNull(frame) { "Unexpected #elif in shaders.properties on line ${line + 1}" }
            require(!frame.elseSeen) { "#elif follows #else in shaders.properties on line ${line + 1}" }
            val matches = frame.parentActive && !frame.branchTaken && expression(argument, line)
            frame.active = matches
            frame.branchTaken = frame.branchTaken || matches
        }

        private fun otherwise(line: Int) {
            val frame = frames.lastOrNull()
            if (frame == null && !requireBalanced) return
            requireNotNull(frame) { "Unexpected #else in shaders.properties on line ${line + 1}" }
            require(!frame.elseSeen) { "Duplicate #else in shaders.properties on line ${line + 1}" }
            frame.active = frame.parentActive && !frame.branchTaken
            frame.branchTaken = true
            frame.elseSeen = true
        }

        private fun close(line: Int) {
            if (frames.isEmpty() && !requireBalanced) return
            require(frames.isNotEmpty()) { "Unexpected #endif in shaders.properties on line ${line + 1}" }
            frames.removeAt(frames.lastIndex)
        }

        private fun expression(source: String, line: Int): Boolean {
            require(source.length <= MAX_PROPERTY_CONDITION_LENGTH) {
                "shaders.properties conditional is too long on line ${line + 1}"
            }
            return PropertyConditionParser(source, definitions).parse()
        }

        private fun defined(source: String, line: Int): Boolean = identifier(source, line) in definitions

        private fun identifier(source: String, line: Int): String {
            require(IDENTIFIER.matches(source)) {
                "Invalid shaders.properties preprocessor identifier on line ${line + 1}"
            }
            return source
        }

        private fun define(source: String, line: Int) {
            val name = source.takeWhile { !it.isWhitespace() }
            if ('(' in name) return
            identifier(name, line)
            val value = source.drop(name.length).trim().ifEmpty { "1" }
            require(value.length <= MAX_PROPERTY_DEFINE_VALUE_LENGTH) {
                "shaders.properties definition $name is too long on line ${line + 1}"
            }
            definitions[name] = value
        }

        private fun logicalLines(source: String): List<String> {
            val lines = mutableListOf<String>()
            val pending = StringBuilder()
            source.lineSequence().forEach { physical ->
                val trimmed = physical.trimEnd()
                val continued = trimmed.endsWith('\\')
                if (pending.isEmpty()) {
                    val directive = trimmed.trimStart()
                    val joinDirective = directive.startsWith("#if ") ||
                        directive.startsWith("#elif ") ||
                        directive.startsWith("#define ")
                    if (!continued || !joinDirective) {
                        lines += physical
                        return@forEach
                    }
                }
                pending.append(if (continued) trimmed.dropLast(1) else physical)
                if (continued) {
                    pending.append(' ')
                } else {
                    lines += pending.toString()
                    pending.setLength(0)
                }
            }
            if (pending.isNotEmpty()) lines += pending.toString()
            return lines
        }

        private data class ConditionalFrame(
            val parentActive: Boolean,
            var branchTaken: Boolean,
            var active: Boolean,
            var elseSeen: Boolean = false,
            val line: Int,
            val condition: String,
        )
    }

    private class PropertyConditionParser(
        private val source: String,
        private val definitions: Map<String, String>,
    ) {
        private var index = 0

        fun parse(): Boolean {
            val value = parseOr().truthy()
            whitespace()
            require(index == source.length) {
                "Invalid shaders.properties conditional near '${source.substring(index).take(32)}'"
            }
            return value
        }

        private fun parseOr(): PropertyValue {
            var value = parseAnd()
            while (consume("||")) {
                val right = parseAnd()
                value = PropertyValue.boolean(value.truthy() || right.truthy())
            }
            return value
        }

        private fun parseAnd(): PropertyValue {
            var value = parseEquality()
            while (consume("&&")) {
                val right = parseEquality()
                value = PropertyValue.boolean(value.truthy() && right.truthy())
            }
            return value
        }

        private fun parseEquality(): PropertyValue {
            var value = parseRelational()
            while (true) {
                value = when {
                    consume("==") -> PropertyValue.boolean(value.compare(parseRelational()) == 0)
                    consume("!=") -> PropertyValue.boolean(value.compare(parseRelational()) != 0)
                    else -> return value
                }
            }
        }

        private fun parseRelational(): PropertyValue {
            var value = parseUnary()
            while (true) {
                value = when {
                    consume("<=") -> PropertyValue.boolean(value.compare(parseUnary()) <= 0)
                    consume(">=") -> PropertyValue.boolean(value.compare(parseUnary()) >= 0)
                    consume("<") -> PropertyValue.boolean(value.compare(parseUnary()) < 0)
                    consume(">") -> PropertyValue.boolean(value.compare(parseUnary()) > 0)
                    else -> return value
                }
            }
        }

        private fun parseUnary(): PropertyValue {
            if (consume("!")) return PropertyValue.boolean(!parseUnary().truthy())
            whitespace()
            if (source.startsWith("defined", index) && identifierBoundary(index + "defined".length)) {
                index += "defined".length
                whitespace()
                val parenthesized = consume("(")
                val name = readIdentifier()
                if (parenthesized) require(consume(")")) {
                    "Unclosed defined() in shaders.properties conditional"
                }
                return PropertyValue.boolean(name in definitions)
            }
            if (consume("(")) {
                val value = parseOr()
                require(consume(")")) { "Unclosed group in shaders.properties conditional" }
                return value
            }
            return primary()
        }

        private fun primary(): PropertyValue {
            whitespace()
            val start = index
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            while (index < source.length && (source[index].isLetterOrDigit() || source[index] in "._")) index++
            require(index > start) {
                "Expected value in shaders.properties conditional near '${source.substring(start).take(32)}'"
            }
            val token = source.substring(start, index)
            if (IDENTIFIER.matches(token)) {
                return PropertyValue(resolve(token))
            }
            return PropertyValue(token)
        }

        private fun resolve(name: String): String {
            var value = definitions[name] ?: return "0"
            repeat(MAX_PROPERTY_DEFINE_EXPANSIONS) {
                if (!IDENTIFIER.matches(value)) return value
                value = definitions[value] ?: return value
            }
            throw IllegalArgumentException("Shader properties definition expansion is too deep for $name")
        }

        private fun readIdentifier(): String {
            whitespace()
            val start = index
            if (index < source.length && (source[index] == '_' || source[index].isLetter())) index++
            while (index < source.length && (source[index] == '_' || source[index].isLetterOrDigit())) index++
            require(index > start) { "Expected identifier in shaders.properties conditional" }
            return source.substring(start, index)
        }

        private fun consume(token: String): Boolean {
            whitespace()
            if (!source.startsWith(token, index)) return false
            index += token.length
            return true
        }

        private fun identifierBoundary(at: Int): Boolean =
            at >= source.length || !(source[at] == '_' || source[at].isLetterOrDigit())

        private fun whitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }

    private data class PropertyValue(val text: String) {
        fun truthy(): Boolean = text.toDoubleOrNull()?.let { it != 0.0 } ?: (text.isNotEmpty() && text != "false")

        fun compare(other: PropertyValue): Int {
            val leftNumber = text.toDoubleOrNull()
            val rightNumber = other.text.toDoubleOrNull()
            return if (leftNumber != null && rightNumber != null) {
                leftNumber.compareTo(rightNumber)
            } else {
                text.compareTo(other.text)
            }
        }

        companion object {
            fun boolean(value: Boolean): PropertyValue = PropertyValue(if (value) "1" else "0")
        }
    }

    private val BOOLEAN_VALUES = listOf("false", "true")
    private val BINARY_TEXTURE_EXTENSIONS = setOf(".png", ".raw", ".bin", ".dat")
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
    private val GRAPHICS_STAGE_EXTENSIONS = setOf(".vsh", ".tcs", ".tes", ".gsh", ".fsh")
    private val SHADER_STAGE_EXTENSIONS = setOf(".vsh", ".fsh", ".gsh", ".csh", ".tcs", ".tes")
    private val COMPUTE_COMPATIBILITY_VERSION =
        Regex("""(?m)^(\s*#version\s+[0-9]+\s+)compatibility\s*$""")
    private val COMPUTE_WORK_GROUPS = Regex(
        """\bconst\s+ivec3\s+workGroups\s*=\s*ivec3\s*\(\s*([0-9]+)\s*,\s*([0-9]+)\s*,\s*([0-9]+)\s*\)\s*;""",
    )
    private val COMPUTE_RELATIVE_WORK_GROUPS = Regex(
        """\bconst\s+vec2\s+workGroupsRender\s*=\s*vec2\s*\(\s*""" +
            """([0-9]+(?:\.[0-9]+)?)\s*,\s*([0-9]+(?:\.[0-9]+)?)\s*\)\s*;""",
    )
    private val COMPUTE_LOCAL_SIZE = Regex(
        """layout\s*\([^)]*local_size_x\s*=\s*([0-9]+)[^)]*""" +
            """local_size_y\s*=\s*([0-9]+)[^)]*\)\s*in\s*;""",
    )
    private val LEGACY_PROGRAM_DIRECTORIES = setOf("world0", "world-1", "world1")
    private const val ALPHA_TEST_PREFIX = "alphaTest."
    private const val BLEND_PREFIX = "blend."
    private const val DIMENSION_PREFIX = "dimension."
    private const val NETHER = "minecraft:the_nether"
    private const val END = "minecraft:the_end"
    private val FULLSCREEN_BUFFER_PHASES = setOf(
        ShaderProgramPhase.BEGIN,
        ShaderProgramPhase.SHADOW_COMPOSITE,
        ShaderProgramPhase.PREPARE,
        ShaderProgramPhase.DEFERRED,
        ShaderProgramPhase.COMPOSITE,
    )
    private val PROGRAM_FAMILY = mapOf(
        ShaderProgramPhase.SETUP to Regex("""setup(?:[1-9][0-9]?)?"""),
        ShaderProgramPhase.BEGIN to Regex("""begin(?:[1-9][0-9]?)?"""),
        ShaderProgramPhase.SHADOW_COMPOSITE to Regex("""shadowcomp(?:[1-9][0-9]?)?"""),
        ShaderProgramPhase.PREPARE to Regex("""prepare(?:[1-9][0-9]?)?"""),
        ShaderProgramPhase.DEFERRED to Regex("""deferred(?:[1-9][0-9]?)?"""),
        ShaderProgramPhase.COMPOSITE to Regex("""composite(?:[1-9][0-9]?)?"""),
    )
    private val BUFFER_SAMPLER = Regex("""(colortex|depthtex|shadowtex|shadowcolor)([0-9]+)""")
    private val RENDER_TARGET_IMAGE = Regex("""(colorimg|shadowcolorimg)([0-9]+)""")
    private val SHADOW_COMPUTE_FAMILY = Regex("""shadow(?:[1-9][0-9]?)?""")
    private val FINAL_COMPUTE_FAMILY = Regex("""final(?:[1-9][0-9]?)?""")
    private val LEGACY_COLORTEX = mapOf(
        "gcolor" to 0,
        "gdepth" to 1,
        "gnormal" to 2,
        "composite" to 3,
        "gaux1" to 4,
        "gaux2" to 5,
        "gaux3" to 6,
        "gaux4" to 7,
    )
    private val IRIS_BLEND_FACTORS = mapOf(
        "ZERO" to BlendingFunctions.ZERO,
        "ONE" to BlendingFunctions.ONE,
        "SRC_COLOR" to BlendingFunctions.SOURCE_COLOR,
        "ONE_MINUS_SRC_COLOR" to BlendingFunctions.ONE_MINUS_SOURCE_COLOR,
        "DST_COLOR" to BlendingFunctions.DESTINATION_COLOR,
        "ONE_MINUS_DST_COLOR" to BlendingFunctions.ONE_MINUS_DESTINATION_COLOR,
        "SRC_ALPHA" to BlendingFunctions.SOURCE_ALPHA,
        "ONE_MINUS_SRC_ALPHA" to BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
        "DST_ALPHA" to BlendingFunctions.DESTINATION_ALPHA,
        "ONE_MINUS_DST_ALPHA" to BlendingFunctions.ONE_MINUS_DESTINATION_ALPHA,
        "SRC_ALPHA_SATURATE" to BlendingFunctions.SOURCE_ALPHA_SATURATE,
    )
    private val RAW_INTERNAL_FORMATS = setOf(
        "R8",
        "RG8",
        "RGB8",
        "RGBA8",
        "R16F",
        "RG16F",
        "RGB16F",
        "RGBA16F",
        "R32F",
        "RG32F",
        "RGB32F",
        "RGBA32F",
    )
    private val BLACK_CLEAR = listOf(0.0f, 0.0f, 0.0f, 0.0f)
    private val WHITE_CLEAR = listOf(1.0f, 1.0f, 1.0f, 1.0f)
    private val INTEGER = Regex("""[0-9]+""")
    private const val DEFAULT_SHADOW_RESOLUTION = 1024
    private const val DEFAULT_NOISE_TEXTURE_RESOLUTION = 256
    private const val MAX_CUSTOM_TEXTURE_SIZE = 4096
    private const val MAX_CUSTOM_TEXTURE_PIXELS = 16_777_216L
    private const val MAX_CUSTOM_TEXTURE_BYTES = 64 * 1024 * 1024
    private const val MAX_CUSTOM_TEXTURES = 256
    private const val MAX_CUSTOM_IMAGES = 16
    private const val MAX_COMPUTE_WORK_GROUPS = 65_535
    private const val INDIRECT_DISPATCH_BYTES = 3L * Int.SIZE_BYTES
    private const val MAX_CUSTOM_IMAGE_DIMENSION = 16_384
    private const val MAX_CUSTOM_IMAGE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_SINGLE_SHADER_STORAGE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_SHADER_STORAGE_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_FIXED_BUFFER_SIZE = 16_384
    private const val MAX_OPTIONS = 1_024
    private const val MAX_OPTION_VALUES = 4_096
    private const val MAX_PROGRAM_CONDITION_LENGTH = 4_096
    private const val MAX_PROGRAM_CONDITION_DEPTH = 64
    private const val MAX_PROPERTY_DEFINES = 256
    private const val MAX_PROPERTY_DEFINE_VALUE_LENGTH = 16_384
    private const val MAX_PROPERTY_DEFINE_EXPANSIONS = 16
    private const val MAX_PROPERTY_CONDITION_LENGTH = 4_096
    private const val MAX_PROPERTY_CONDITION_DEPTH = 64
    private const val MAX_CUSTOM_UNIFORMS = 512
}

data class ShaderPackOption(
    val name: String,
    val defaultValue: String,
    val values: List<String>,
)

data class ShaderPackProfile(
    val name: String,
    val optionValues: Map<String, String>,
    val disabledPrograms: List<String>,
)

data class ShaderPackScreen(
    val id: String?,
    val entries: List<String>,
    val columns: Int?,
)

data class ShaderPackSettings(
    val options: List<ShaderPackOption>,
    val profiles: List<ShaderPackProfile>,
    val sliders: List<String>,
    val mainScreen: ShaderPackScreen?,
    val subScreens: List<ShaderPackScreen>,
) {
    fun selectedProfile(overrides: Map<String, String>): ShaderPackProfile? {
        val values = options.associate { it.name to (overrides[it.name] ?: it.defaultValue) }
        return profiles.withIndex()
            .filter { (_, profile) ->
                profile.optionValues.all { (name, value) -> values[name] == value }
            }
            .minWithOrNull(
                compareBy<IndexedValue<ShaderPackProfile>>(
                    { -it.value.optionValues.size },
                    IndexedValue<ShaderPackProfile>::index,
                ),
            )
            ?.value
    }
}
