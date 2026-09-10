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
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrisShaderPackPlannerTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `external shader pack crosses the complete planner contract when configured`() {
        val configured = System.getenv("MINOSOFT_IRIS_TEST_PACK")
        assumeTrue(!configured.isNullOrBlank(), "MINOSOFT_IRIS_TEST_PACK is not configured")
        val options = System.getenv("MINOSOFT_IRIS_TEST_OPTIONS").orEmpty()
            .split(';')
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0 && separator < entry.lastIndex) {
                    "MINOSOFT_IRIS_TEST_OPTIONS entries must use name=value"
                }
                entry.substring(0, separator) to entry.substring(separator + 1)
            }

        val distantHorizons = System.getenv("MINOSOFT_IRIS_TEST_DISTANT_HORIZONS").toBoolean()
        val plan = IrisShaderPackPlanner.plan(
            Path.of(configured),
            overrides = options,
            dimension = ResourceLocation.of("minecraft:overworld"),
            preprocessorDefines = IrisShaderPackPlanner.standardEnvironmentDefines(
                12004,
                perBufferBlending = true,
                distantHorizons = distantHorizons,
            ).let { environment ->
                if (System.getenv("MINOSOFT_IRIS_TEST_NON_MAC").toBoolean()) {
                    environment - "MC_OS_MAC"
                } else {
                    environment
                }
            },
        )
        val settings = IrisShaderPackPlanner.settings(Path.of(configured))

        assertTrue(plan.programs.isNotEmpty())
        assertTrue(settings.options.isNotEmpty())
        val groupedOptions = settings.optionGroups().flatMap(ShaderPackOptionGroup::optionNames)
        assertEquals(settings.options.map(ShaderPackOption::name).toSet(), groupedOptions.toSet())
        assertEquals(groupedOptions.size, groupedOptions.distinct().size)
        IrisWorldShaderPipeline.validateProgramContract(plan)
        val mainSceneStates = plan.programs.asSequence()
            .filter { it.phase != ShaderProgramPhase.SHADOW }
            .flatMap { it.sceneBridges.asSequence() }
            .mapTo(linkedSetOf()) { it.stateAbi }
        val requiredMainStates = SceneStateAbi.entries.toSet() -
            SceneStateAbi.TERRAIN -
            if (distantHorizons) emptySet() else setOf(SceneStateAbi.DISTANT_TERRAIN)
        val missingMainStates = requiredMainStates - mainSceneStates
        assertTrue(
            missingMainStates.isEmpty(),
            "External pack has no executable main-view bridge for $missingMainStates; " +
                "available states are $mainSceneStates",
        )
        if (Path.of(configured).fileName.toString().contains("ComplementaryUnbound")) {
            val waterBlend = plan.programs.single { it.name == "gbuffers_water" }.blendOverride
            assertEquals(
                IrisBlendMode.Off,
                waterBlend.buffers[ShaderBufferId(ShaderBufferKind.COLORTEX, 4)],
            )
            assertEquals(
                IrisBlendMode.Off,
                waterBlend.buffers[ShaderBufferId(ShaderBufferKind.COLORTEX, 8)],
            )
            if (options["WORLD_SPACE_REFLECTIONS"] == "1") {
                val coloredLighting = options["COLORED_LIGHTING"]?.toIntOrNull() ?: 0
                val water = plan.programs.single { it.name == "gbuffers_water" }
                assertTrue(
                    ShaderBufferId(ShaderBufferKind.COLORTEX, 4) in water.resourceUsage.colorWrites,
                    water.inspectionFragment.orEmpty().takeLast(4_000),
                )
                assertContains(
                    water.resourceUsage.colorWrites,
                    ShaderBufferId(ShaderBufferKind.COLORTEX, 8),
                )
                if (
                    System.getenv("MINOSOFT_IRIS_TEST_NON_MAC").toBoolean() &&
                    coloredLighting > 0
                ) {
                    assertContains(plan.customResources.images.map { it.name }, "wsr_img")
                    assertContains(plan.customResources.images.map { it.name }, "wsr_lod_img")
                    assertContains(plan.customResources.shaderStorageBuffers.map { it.index }, 0)
                    assertContains(plan.computePrograms.map { it.name }, "shadowcomp")
                } else {
                    assertTrue(plan.customResources.images.isEmpty())
                    assertTrue(plan.customResources.shaderStorageBuffers.isEmpty())
                    assertTrue(plan.computePrograms.isEmpty())
                }
            }
        }
        if (plan.shadowDirectives.enabled && plan.shadowDirectives.entities) {
            val shadowStates = plan.programs.asSequence()
                .filter { it.phase == ShaderProgramPhase.SHADOW }
                .flatMap { it.sceneBridges.asSequence() }
                .mapTo(linkedSetOf()) { it.stateAbi }
            val requiredShadowStates = setOf(
                SceneStateAbi.ENTITY_FLAME,
                SceneStateAbi.BLOCK,
                SceneStateAbi.FLASHING_BLOCK,
                SceneStateAbi.SKELETAL_TINTED,
                SceneStateAbi.SKELETAL_LIGHTMAP,
                SceneStateAbi.PLAYER,
            )
            assertTrue(
                shadowStates.containsAll(requiredShadowStates),
                "External pack has no executable shadow bridge for " +
                    "${requiredShadowStates - shadowStates}; available states are $shadowStates",
            )
        }
    }

    @Test
    fun `external Bliss archive crosses the executable DH contract`() {
        val configured = System.getenv("MINOSOFT_BLISS_TEST_PACK")
        assumeTrue(!configured.isNullOrBlank(), "MINOSOFT_BLISS_TEST_PACK is not configured")
        val path = Path.of(configured)
        val plan = IrisShaderPackPlanner.plan(
            path,
            dimension = ResourceLocation.of("minecraft:overworld"),
            preprocessorDefines = IrisShaderPackPlanner.standardEnvironmentDefines(
                12004,
                perBufferBlending = true,
                distantHorizons = true,
            ),
        )
        val settings = IrisShaderPackPlanner.settings(path)

        assertEquals("world0", plan.programDirectory)
        assertTrue(plan.programs.isNotEmpty())
        assertTrue(settings.options.isNotEmpty())
        val groupedOptions = settings.optionGroups().flatMap(ShaderPackOptionGroup::optionNames)
        assertEquals(settings.options.map(ShaderPackOption::name).toSet(), groupedOptions.toSet())
        assertEquals(groupedOptions.size, groupedOptions.distinct().size)
        assertEquals("Shadow Distance", settings.language.option("shadowDistance"))
        assertTrue(settings.optionGroups().size < settings.subScreens.size)
        IrisWorldShaderPipeline.validateProgramContract(plan)
        assertContains(plan.programs.map(ShaderProgramSource::name), "dh_terrain")
        assertContains(plan.programs.map(ShaderProgramSource::name), "dh_water")
        assertTrue(
            plan.programs.single { it.name == "gbuffers_textured" }
                .sceneBridges.any { it.stateAbi == SceneStateAbi.WORLD_BORDER },
            "Bliss gbuffers_textured has no executable world-border bridge",
        )
        val shadowStates = plan.programs.asSequence()
            .filter { it.phase == ShaderProgramPhase.SHADOW }
            .flatMap { it.sceneBridges.asSequence() }
            .mapTo(linkedSetOf()) { it.stateAbi }
        assertTrue(
            shadowStates.containsAll(
                setOf(
                    SceneStateAbi.ENTITY_FLAME,
                    SceneStateAbi.BLOCK,
                    SceneStateAbi.FLASHING_BLOCK,
                    SceneStateAbi.SKELETAL_TINTED,
                    SceneStateAbi.SKELETAL_LIGHTMAP,
                    SceneStateAbi.PLAYER,
                ),
            ),
            "Bliss shadow program has no executable retained bridge for " +
                "${SceneStateAbi.entries.toSet() - shadowStates}",
        )
        val suppressedClouds = plan.programs.single { it.name == "gbuffers_clouds" }
        assertContains(
            suppressedClouds.vertex,
            "// minosoft:scene_bridge CLOUD CLOUD",
        )
        assertContains(suppressedClouds.fragment, "discard;")
        val bloomBlur = plan.programs.single { it.name == "composite9" }
        assertEquals(
            ShaderBufferId(ShaderBufferKind.COLORTEX, 6),
            bloomBlur.resourceUsage.sampledBuffers["colortex6"],
        )
        assertFalse("colortex6" in bloomBlur.resourceUsage.sampledCustomTextures)
        assertContains(
            plan.programs.single { it.name == "gbuffers_water" }.fragment,
            "float minosoftWaterDiffuseScale",
        )
        assertContains(
            plan.programs.single { it.name == "gbuffers_water" }.fragment,
            "if (rtPos.z < 1.0 && !isWater){",
        )
        assertContains(
            plan.programs.single { it.name == "composite4" }.fragment,
            "color.rgb * mix(vec3(1.0), thresholdAbsorbedColor, 0.65)",
        )
        assertContains(
            plan.programs.single { it.name == "composite6" }.fragment,
            "if(hand) blendingFactor = 1.0; // minosoft: reject world history on the hand",
        )
        assertContains(
            plan.programs.single { it.name == "composite6" }.fragment,
            "bool hand = abs(dataUnpacked-0.75) < 0.01 && texture(depthtex0,taauTC).x < 1.0;",
        )
        assertFalse(
            "texture(depthtex1,taauTC).x < 1.0" in
                plan.programs.single { it.name == "composite6" }.fragment,
        )
        val composite = plan.programs.single { it.name == "composite1" }.fragment
        assertContains(composite, "#ifdef Ambient_SSS")
        assertEquals(2, Regex("""#ifdef\s+Ambient_SSS""").findAll(composite).count())
        val inspectedComposite = requireNotNull(
            plan.programs.single { it.name == "composite1" }.inspectionFragment,
        )
        assertFalse(
            Regex("""(?m)^\s*#(?:ifdef\s+Ambient_SSS|endif)\s*$""")
                .containsMatchIn(inspectedComposite),
        )
        assertContains(inspectedComposite, "float maxR2_2 = viewPos.z;")
        plan.programs.filter {
            it.name in setOf(
                "gbuffers_damagedblock",
                "gbuffers_particles",
                "gbuffers_skytextured",
                "gbuffers_textured",
                "gbuffers_textured_lit",
                "gbuffers_weather",
            )
        }.forEach { source ->
            val transformed = IrisLegacyShaderTransformer.transform(
                source.name,
                source.phase,
                source.vertex,
                source.fragment,
            )
            assertFalse(
                Regex("""\bftransform\s*\(""").containsMatchIn(transformed.vertex),
                "${source.name} retained ftransform",
            )
            if ("minosoftSampler" in transformed.fragment) {
                assertTrue(
                    "texture2D_POMSwitch(int minosoftSampler," in transformed.fragment,
                    "${source.name} uses the material selector without declaring it:\n" +
                        transformed.fragment.lineSequence()
                            .filter { "minosoftSampler" in it }
                            .joinToString("\n"),
                )
            }
        }
    }

    @Test
    fun `distant horizons environment defines the Iris informational selector`() {
        val withoutDistantHorizons = IrisShaderPackPlanner.standardEnvironmentDefines(12004)
        val withDistantHorizons = IrisShaderPackPlanner.standardEnvironmentDefines(
            12004,
            distantHorizons = true,
        )

        assertFalse("DH_KNOWN_ISSUES" in withoutDistantHorizons)
        assertEquals("0", withDistantHorizons["DH_KNOWN_ISSUES"])
        assertEquals("0", withDistantHorizons["DH_BLOCK_UNKNOWN"])
        assertEquals("1", withDistantHorizons["DH_BLOCK_LEAVES"])
        assertEquals("15", withDistantHorizons["DH_BLOCK_ILLUMINATED"])
    }

    @Test
    fun `plans paired terrain shadow composite programs headlessly`() {
        val shaders = temporary.resolve("pack/shaders").createDirectories()
        write(shaders, "lib/common.glsl", "uniform float frameTimeCounter;")
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                #include "lib/common.glsl"
                in vec3 vaPosition;
                in vec2 vaUV0;
                in vec4 vaColor;
                in ivec2 vaUV2;
                uniform int mc_Entity;
                void main() { gl_Position = vec4(vaPosition, 1.0); }
            """.trimIndent(),
        )
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                uniform sampler2D gtexture;
                out vec4 color;
                void main() { color = texture(gtexture, vec2(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "shadow.vsh", SIMPLE_VERTEX)
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(shaders, "composite.fsh", SIMPLE_FRAGMENT)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("pack"))

        assertEquals(IrisShaderPackPlanner.OWNER, plan.owner)
        assertEquals(setOf(RenderViewId.MAIN, IrisShaderPackPlanner.SHADOW_VIEW), plan.views)
        assertEquals(
            setOf(ShaderProgramPhase.TERRAIN, ShaderProgramPhase.SHADOW, ShaderProgramPhase.COMPOSITE),
            plan.programs.mapTo(mutableSetOf(), ShaderProgramSource::phase),
        )
        assertTrue(VertexSemantic.BLOCK_ID in plan.requiredTerrainSemantics)
        assertTrue(plan.programs.single { it.phase == ShaderProgramPhase.TERRAIN }.samplers.contains("gtexture"))
        assertTrue(plan.fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(0.0f, plan.sunPathRotation)
        assertEquals(IrisSmoothingDirectives(), plan.smoothingDirectives)
        assertEquals(2, plan.resources.targets.size)
        assertEquals(
            (0..7).map { ShaderBufferId(ShaderBufferKind.COLORTEX, it) },
            plan.programs.single { it.phase == ShaderProgramPhase.TERRAIN }.resourceUsage.colorWrites,
        )
    }

    @Test
    fun `annotated texture size uniforms transform to the per-array table`() {
        val shaders = temporary.resolve("texture-size-transform/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                uniform ivec2 atlasSize;
                // minosoft:texture_array_index textureArray
                flat out uint textureArray;
                void main() {
                    textureArray = 3u;
                    gl_Position = vec4(float(atlasSize.x), 0.0, 0.0, 1.0);
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                uniform ivec2 gtextureSize;
                // minosoft:texture_array_index textureArray
                flat in uint textureArray;
                out vec4 color;
                void main() { color = vec4(vec2(gtextureSize), 0.0, 1.0); }
            """.trimIndent(),
        )
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(shaders, "composite.fsh", SIMPLE_FRAGMENT)

        val terrain = IrisShaderPackPlanner.plan(temporary.resolve("texture-size-transform"))
            .programs.single { it.name == "gbuffers_terrain" }

        assertEquals(setOf(IrisTextureArrayState.UNIFORM), terrain.uniforms)
        assertContains(terrain.vertex, "uTextureSizes[int(textureArray)]")
        assertContains(terrain.fragment, "uTextureSizes[int(textureArray)]")
        assertTrue("uniform ivec2 atlasSize" !in terrain.vertex)
        assertTrue("uniform ivec2 gtextureSize" !in terrain.fragment)
    }

    @Test
    fun `draw wide Iris texture size rejects without an array index marker`() {
        val shaders = temporary.resolve("texture-size-unmarked/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                uniform ivec2 atlasSize;
                void main() { gl_Position = vec4(float(atlasSize.x)); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(shaders, "composite.fsh", SIMPLE_FRAGMENT)

        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("texture-size-unmarked"))
            }.message.orEmpty(),
            "requires exactly one minosoft:texture_array_index marker",
        )
    }

    @Test
    fun `unused Iris texture size declarations do not require an array index`() {
        val shaders = temporary.resolve("texture-size-unused/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                uniform ivec2 atlasSize;
                void main() { gl_Position = vec4(0.0); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        val terrain = IrisShaderPackPlanner.plan(temporary.resolve("texture-size-unused"))
            .programs.single { it.name == "gbuffers_terrain" }

        assertTrue("atlasSize" !in terrain.vertex)
    }

    @Test
    fun `source native fullscreen block atlas derives size and sampling from reflected face`() {
        val shaders = temporary.resolve("source-native-fullscreen-atlas/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 330 core
                uniform sampler2D colortex0;
                uniform sampler2D textureAtlas;
                uniform ivec2 atlasSize;
                struct ReflectedFace {
                    uint minosoftTextureArray;
                    uint minosoftTextureLayer;
                };
                out vec4 color;
                void main() {
                    ReflectedFace faceData = ReflectedFace(7u, 4u);
                    vec2 textureCoord = vec2(0.5);
                    float lod = 0.0;
                    color = texture(colortex0, textureCoord) +
                        texture2DLod(textureAtlas, textureCoord, lod) +
                        vec4(atlasSize, 0.0, 0.0);
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "shaders.properties",
            "customTexture.textureAtlas=minecraft:textures/atlas/blocks.png",
        )

        val composite = IrisShaderPackPlanner.plan(
            temporary.resolve("source-native-fullscreen-atlas"),
        ).programs.single { it.name == "composite" }

        assertContains(composite.uniforms, "uTextures")
        assertFalse("textureAtlas" in composite.samplers)
        assertFalse("atlasSize" in composite.uniforms)
        assertContains(composite.fragment, "uniform sampler2DArray uTextures[16];")
        assertContains(
            composite.fragment,
            "minosoftSampleTextureArraySize(faceData.minosoftTextureArray, 0)",
        )
        assertContains(
            composite.fragment,
            "minosoftSampleTextureArrayLod(" +
                "faceData.minosoftTextureArray, float(faceData.minosoftTextureLayer), textureCoord, lod)",
        )
    }

    @Test
    fun `parses Iris entity item and block material maps with pinned defaults`() {
        val shaders = temporary.resolve("material-maps/shaders").createDirectories()
        writeProgramSet(shaders, null, "MATERIAL_MAPS")
        write(
            shaders,
            "entity.properties",
            """
                entity.7=minecraft:sheep naturalist:boar
                entity.invalid=minecraft:ignored
                entity.8=minecraft:butterfly:variant=blue
            """.trimIndent(),
        )
        write(
            shaders,
            "item.properties",
            """
                item.12=minecraft:torch \
                  naturalist:glow_goop
                item.13=minecraft:lantern
            """.trimIndent(),
        )
        write(
            shaders,
            "block.properties",
            """
                block.41=minecraft:chest minecraft:furnace:lit=true
                block.42=%minecraft:logs:axis=y naturalist:snail_block
            """.trimIndent(),
        )

        val maps = IrisShaderPackPlanner.plan(temporary.resolve("material-maps")).idMaps

        assertEquals(7, maps.entities[ResourceLocation.of("minecraft:sheep")])
        assertEquals(7, maps.entities[ResourceLocation.of("naturalist:boar")])
        assertEquals(-1, maps.entities[ResourceLocation.of("minecraft:zombie")])
        assertEquals(12, maps.items[ResourceLocation.of("minecraft:torch")])
        assertEquals(12, maps.items[ResourceLocation.of("naturalist:glow_goop")])
        assertEquals(13, maps.items[ResourceLocation.of("minecraft:lantern")])
        assertEquals(-1, maps.items[ResourceLocation.of("minecraft:stick")])
        assertEquals(
            listOf(
                IrisBlockIdRule(41, ResourceLocation.of("minecraft:chest")),
                IrisBlockIdRule(
                    41,
                    ResourceLocation.of("minecraft:furnace"),
                    properties = mapOf("lit" to "true"),
                ),
                IrisBlockIdRule(
                    42,
                    ResourceLocation.of("minecraft:logs"),
                    tag = true,
                    properties = mapOf("axis" to "y"),
                ),
                IrisBlockIdRule(42, ResourceLocation.of("naturalist:snail_block")),
            ),
            maps.blocks,
        )
    }

    @Test
    fun `material maps use the pinned Iris option and environment preprocessor`() {
        val shaders = temporary.resolve("preprocessed-material-maps/shaders").createDirectories()
        writeProgramSet(shaders, null, "PREPROCESSED_MATERIAL_MAPS")
        write(
            shaders,
            "block.properties",
            """
                #if MC_VERSION >= 11300
                block.10232=sand suspicious_sand
                #else
                block.10232=sand:variant=sand
                #endif
            """.trimIndent(),
        )

        val modern = IrisShaderPackPlanner.plan(
            temporary.resolve("preprocessed-material-maps"),
            preprocessorDefines = IrisShaderPackPlanner.standardEnvironmentDefines(12004),
        ).idMaps

        assertEquals(
            listOf(
                IrisBlockIdRule(10232, ResourceLocation.of("minecraft:sand")),
                IrisBlockIdRule(10232, ResourceLocation.of("minecraft:suspicious_sand")),
            ),
            modern.blocks,
        )
    }

    @Test
    fun `absent Iris entity and item maps retain zero while blocks use pinned legacy defaults`() {
        val shaders = temporary.resolve("absent-material-maps/shaders").createDirectories()
        writeProgramSet(shaders, null, "NO_MATERIAL_MAPS")

        val maps = IrisShaderPackPlanner.plan(temporary.resolve("absent-material-maps")).idMaps

        assertEquals(0, maps.entities[ResourceLocation.of("minecraft:sheep")])
        assertEquals(0, maps.items[ResourceLocation.of("minecraft:torch")])
        assertEquals(100, maps.blocks.size)
        assertEquals(
            listOf("stone", "granite", "diorite", "andesite"),
            maps.blocks.filter { it.id == 1 }.map { it.identifier.path },
        )
        assertEquals(35, maps.blocks.single { it.identifier.path == "white_wool" }.id)
        assertEquals(18, maps.blocks.single { it.identifier.path == "dark_oak_leaves" }.id)
        assertEquals(95, maps.blocks.single { it.identifier.path == "black_stained_glass" }.id)
        assertEquals(160, maps.blocks.single { it.identifier.path == "red_stained_glass_pane" }.id)
        assertEquals(-123, maps.blocks.single { it.identifier.path == "emerald_block" }.id)
        assertEquals(111, maps.blocks.last().id)
        assertEquals("lily_pad", maps.blocks.last().identifier.path)
    }

    @Test
    fun `present empty block map suppresses Iris legacy defaults`() {
        val shaders = temporary.resolve("empty-block-map/shaders").createDirectories()
        writeProgramSet(shaders, null, "EMPTY_BLOCK_MAP")
        write(shaders, "block.properties", "")

        val maps = IrisShaderPackPlanner.plan(temporary.resolve("empty-block-map")).idMaps

        assertTrue(maps.blocks.isEmpty())
    }

    @Test
    fun `plans typed buffer formats sizes samplers and flips`() {
        val shaders = temporary.resolve("buffers/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                /* RENDERTARGETS: 0 */
                uniform sampler2D depthtex0;
                out vec4 color;
                void main() { color = texture(depthtex0, vec2(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "shadow.vsh", SIMPLE_VERTEX)
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 330 core
                /* RENDERTARGETS: 2 */
                const int colortex2Format = RGBA16F;
                const bool colortex2Clear = false;
                const vec4 colortex2ClearColor = vec4(0.1, 0.2, 0.3, 1.0);
                const bool colortex0MipmapEnabled = true;
                uniform sampler2D colortex0;
                uniform sampler2DShadow shadowtex1;
                out vec4 color;
                void main() {
                    color = texture(colortex0, vec2(0.0)) + vec4(texture(shadowtex1, vec3(0.5)));
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "shaders.properties",
            """
                size.buffer.colortex2=0.5 0.25
                flip.composite.colortex2=true
            """.trimIndent(),
        )

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("buffers"))
        val composite = plan.programs.single { it.name == "composite" }
        val colortex2 = requireNotNull(plan.buffers[ShaderBufferId(ShaderBufferKind.COLORTEX, 2)])

        assertEquals(
            listOf(ShaderBufferId(ShaderBufferKind.COLORTEX, 2)),
            composite.resourceUsage.colorWrites,
        )
        assertEquals(
            mapOf(
                "colortex0" to ShaderBufferId(ShaderBufferKind.COLORTEX, 0),
                "shadowtex1" to ShaderBufferId(ShaderBufferKind.SHADOWTEX, 1),
            ),
            composite.resourceUsage.sampledBuffers,
        )
        assertEquals(setOf("shadowtex1"), composite.resourceUsage.shadowComparisonSamplers)
        assertEquals(setOf(ShaderBufferId(ShaderBufferKind.COLORTEX, 2)), composite.resourceUsage.flipsAfter)
        assertEquals(
            setOf(ShaderBufferId(ShaderBufferKind.COLORTEX, 0)),
            composite.resourceUsage.mipmapsBefore,
        )
        assertTrue(requireNotNull(plan.buffers[ShaderBufferId(ShaderBufferKind.COLORTEX, 0)]).mipmapped)
        assertEquals(ShaderBufferFormat.Color(RenderColorFormat.RGBA16F), colortex2.format)
        assertEquals(RenderTargetSize.Relative(0.5f, 0.25f), colortex2.size)
        assertEquals(RenderClearPolicy.LOAD, colortex2.clear)
        assertEquals(
            ShaderBufferClearColor.Fixed(listOf(0.1f, 0.2f, 0.3f, 1.0f)),
            colortex2.clearColor,
        )
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `reads Iris buffer declarations from comment blocks`() {
        val shaders = temporary.resolve("commented-buffers/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 330 core
                /*
                const int colortex2Format = RGBA16;
                const bool colortex2Clear = false;
                const vec4 colortex2ClearColor = vec4(0.2, 0.3, 0.4, 1.0);
                */
                /* RENDERTARGETS: 2 */
                uniform sampler2D colortex0;
                out vec4 color;
                void main() { color = texture(colortex0, vec2(0.0)); }
            """.trimIndent(),
        )

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("commented-buffers"))
        val descriptor = requireNotNull(plan.buffers[ShaderBufferId(ShaderBufferKind.COLORTEX, 2)])

        assertEquals(ShaderBufferFormat.Color(RenderColorFormat.RGBA16), descriptor.format)
        assertEquals(RenderClearPolicy.LOAD, descriptor.clear)
        assertEquals(
            ShaderBufferClearColor.Fixed(listOf(0.2f, 0.3f, 0.4f, 1.0f)),
            descriptor.clearColor,
        )
    }

    @Test
    fun `plans stage scoped PNG textures custom noise and mcmeta filtering`() {
        val shaders = temporary.resolve("custom-textures/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                uniform sampler2D referenceLut;
                uniform sampler2D noisetex;
                out vec4 color;
                void main() {
                    color = texture(referenceLut, vec2(0.0)) * texture(noisetex, vec2(0.0));
                }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                texture.gbuffers.referenceLut=/textures/reference.png
                texture.noise=textures/noise.png
            """.trimIndent(),
        )
        writeBytes(shaders, "textures/reference.png", ONE_PIXEL_PNG)
        writeBytes(shaders, "textures/noise.png", ONE_PIXEL_PNG)
        write(
            shaders,
            "textures/reference.png.mcmeta",
            """{"texture":{"blur":true,"clamp":true}}""",
        )

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("custom-textures"))
        val terrain = plan.programs.single { it.name == "gbuffers_terrain" }
        val binding = plan.textures.custom.single()
        val texture = (binding.texture as IrisCustomTextureDescriptor.Png).value

        assertEquals(IrisTextureStage.GBUFFERS_AND_SHADOW, binding.stage)
        assertEquals("referenceLut", binding.sampler)
        assertEquals(1, texture.width)
        assertEquals(1, texture.height)
        assertEquals(true, texture.blur)
        assertEquals(true, texture.clamp)
        assertEquals(
            IrisNoiseTextureDescriptor.Custom(
                IrisPngTextureDescriptor(
                    id = IrisTextureId("noise"),
                    path = "textures/noise.png",
                    content = IrisTextureBytes(ONE_PIXEL_PNG),
                    width = 1,
                    height = 1,
                ),
            ),
            plan.textures.noise,
        )
        assertEquals(
            mapOf(
                "noisetex" to IrisTextureId("noise"),
                "referenceLut" to binding.texture.id,
            ),
            terrain.resourceUsage.sampledCustomTextures,
        )
        assertTrue(terrain.resourceUsage.sampledBuffers.isEmpty())
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `plans bounded raw three dimensional custom textures`() {
        val shaders = temporary.resolve("raw-textures/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "final.fsh",
            """
                #version 430 core
                uniform sampler3D scattering;
                out vec4 color;
                void main() { color = texture(scattering, vec3(0.0)); }
            """.trimIndent(),
        )
        write(
            shaders,
            "shaders.properties",
            "texture.composite.scattering=image/scattering.dat TEXTURE_3D R8 2 2 2 RED UNSIGNED_BYTE",
        )
        writeBytes(shaders, "image/scattering.dat", ByteArray(8) { it.toByte() })

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("raw-textures"))
        val binding = plan.textures.custom.single()
        val raw = (binding.texture as IrisCustomTextureDescriptor.Raw).value

        assertEquals(IrisRawTextureTarget.TEXTURE_3D, raw.target)
        assertEquals(listOf(2, 2, 2), listOf(raw.width, raw.height, raw.depth))
        assertEquals(IrisRawTextureFormat.RED, raw.format)
        assertEquals(IrisRawTextureType.UNSIGNED_BYTE, raw.type)
        assertTrue(raw.blur)
        assertTrue(raw.clamp)
        assertEquals(
            mapOf("scattering" to raw.id),
            plan.programs.single { it.phase == ShaderProgramPhase.FINAL }.resourceUsage.sampledCustomTextures,
        )
    }

    @Test
    fun `stage custom render target override ends after its first composite flip`() {
        val shaders = temporary.resolve("custom-texture-first-flip/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        listOf("composite", "composite1", "final").forEach { name ->
            write(shaders, "$name.vsh", SIMPLE_VERTEX)
        }
        write(
            shaders,
            "composite.fsh",
            """
                #version 330 core
                uniform sampler2D colortex6;
                /* DRAWBUFFERS:6 */
                out vec4 color;
                void main() { color = texture(colortex6, vec2(0.0)); }
            """.trimIndent(),
        )
        write(
            shaders,
            "composite1.fsh",
            """
                #version 330 core
                uniform sampler2D colortex6;
                /* DRAWBUFFERS:3 */
                out vec4 color;
                void main() { color = texture(colortex6, vec2(0.0)); }
            """.trimIndent(),
        )
        write(
            shaders,
            "final.fsh",
            """
                #version 330 core
                uniform sampler2D colortex6;
                out vec4 color;
                void main() { color = texture(colortex6, vec2(0.0)); }
            """.trimIndent(),
        )
        write(
            shaders,
            "shaders.properties",
            "texture.composite.colortex6=textures/blue-noise.png",
        )
        writeBytes(shaders, "textures/blue-noise.png", ONE_PIXEL_PNG)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("custom-texture-first-flip"))
        val first = plan.programs.single { it.name == "composite" }.resourceUsage
        val second = plan.programs.single { it.name == "composite1" }.resourceUsage
        val final = plan.programs.single { it.name == "final" }.resourceUsage
        val color6 = ShaderBufferId(ShaderBufferKind.COLORTEX, 6)

        assertEquals(setOf("colortex6"), first.sampledCustomTextures.keys)
        assertTrue(first.sampledBuffers.isEmpty())
        assertEquals(mapOf("colortex6" to color6), second.sampledBuffers)
        assertTrue(second.sampledCustomTextures.isEmpty())
        assertEquals(mapOf("colortex6" to color6), final.sampledBuffers)
        assertTrue(final.sampledCustomTextures.isEmpty())
    }

    @Test
    fun `plans pinned customTexture raw alias directives`() {
        val shaders = temporary.resolve("raw-custom-texture-alias/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 430 core
                uniform sampler3D scattering;
                out vec4 color;
                void main() { color = texture(scattering, vec3(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            "customTexture.scattering=image/scattering.dat TEXTURE_3D R8 2 2 2 RED UNSIGNED_BYTE",
        )
        writeBytes(shaders, "image/scattering.dat", ByteArray(8) { it.toByte() })
        write(
            shaders,
            "image/scattering.dat.mcmeta",
            """{"texture":{"blur":false,"clamp":false}}""",
        )

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("raw-custom-texture-alias"))
        val binding = plan.textures.custom.single()
        val raw = (binding.texture as IrisCustomTextureDescriptor.Raw).value

        assertEquals(IrisTextureStage.GLOBAL, binding.stage)
        assertEquals("scattering", binding.sampler)
        assertEquals(IrisRawTextureTarget.TEXTURE_3D, raw.target)
        assertFalse(raw.blur)
        assertFalse(raw.clamp)
        assertEquals(
            mapOf("scattering" to raw.id),
            plan.programs.single { it.name == "composite" }.resourceUsage.sampledCustomTextures,
        )
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `plans pinned global customTexture PNG directives for every sampled stage`() {
        val shaders = temporary.resolve("global-custom-texture-png/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 330 core
                uniform sampler2D referenceLut;
                out vec4 color;
                void main() { color = texture(referenceLut, vec2(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "final.fsh",
            """
                #version 330 core
                uniform sampler2D referenceLut;
                out vec4 color;
                void main() { color = texture(referenceLut, vec2(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "shaders.properties", "customTexture.referenceLut=textures/reference.png")
        writeBytes(shaders, "textures/reference.png", ONE_PIXEL_PNG)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("global-custom-texture-png"))
        val binding = plan.textures.custom.single()
        val texture = (binding.texture as IrisCustomTextureDescriptor.Png).value

        assertEquals(IrisTextureStage.GLOBAL, binding.stage)
        assertEquals("referenceLut", binding.sampler)
        assertFalse(texture.blur)
        assertFalse(texture.clamp)
        assertEquals(
            mapOf("referenceLut" to binding.texture.id),
            plan.programs.single { it.name == "composite" }.resourceUsage.sampledCustomTextures,
        )
        assertEquals(
            mapOf("referenceLut" to binding.texture.id),
            plan.programs.single { it.phase == ShaderProgramPhase.FINAL }.resourceUsage.sampledCustomTextures,
        )
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `global customTexture aliases override stage bindings independent of property order`() {
        val shaders = temporary.resolve("global-custom-texture-precedence/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                uniform sampler2D referenceLut;
                out vec4 color;
                void main() { color = texture(referenceLut, vec2(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                customTexture.referenceLut=textures/global.png
                texture.gbuffers.referenceLut=textures/stage.png
            """.trimIndent(),
        )
        writeBytes(shaders, "textures/global.png", ONE_PIXEL_PNG)
        writeBytes(shaders, "textures/stage.png", ONE_PIXEL_PNG)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("global-custom-texture-precedence"))
        val bindings = plan.textures.custom.associateBy { it.stage }

        assertEquals(
            mapOf("referenceLut" to bindings.getValue(IrisTextureStage.GLOBAL).texture.id),
            plan.programs.single { it.name == "gbuffers_terrain" }.resourceUsage.sampledCustomTextures,
        )
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `plans pinned one dimensional and rectangle raw texture targets`() {
        val shaders = temporary.resolve("raw-targets/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 430 core
                uniform sampler1D curve;
                uniform sampler2DRect lookup;
                out vec4 color;
                void main() {
                    color = texture(curve, 0.0) + texture(lookup, vec2(0.0));
                }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                customTexture.curve=image/curve.dat TEXTURE_1D R8 2 RED UNSIGNED_BYTE
                customTexture.lookup=image/lookup.dat TEXTURE_RECTANGLE R8 2 2 RED UNSIGNED_BYTE
            """.trimIndent(),
        )
        writeBytes(shaders, "image/curve.dat", byteArrayOf(0, 1))
        writeBytes(shaders, "image/lookup.dat", byteArrayOf(0, 1, 2, 3))

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("raw-targets"))
        val textures = plan.textures.custom.associate { binding ->
            binding.sampler to (binding.texture as IrisCustomTextureDescriptor.Raw).value
        }

        assertEquals(IrisRawTextureTarget.TEXTURE_1D, textures.getValue("curve").target)
        assertEquals(IrisRawTextureTarget.TEXTURE_RECTANGLE, textures.getValue("lookup").target)
        assertEquals(
            setOf("curve", "lookup"),
            plan.programs.single { it.name == "composite" }.resourceUsage.sampledCustomTextures.keys,
        )
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `raw custom texture aliases apply only to matching sampler types`() {
        val shaders = temporary.resolve("typed-raw-alias/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 430 core
                uniform sampler3D colortex0;
                out vec4 color;
                void main() { color = texture(colortex0, vec3(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "final.fsh",
            """
                #version 430 core
                uniform sampler2D colortex0;
                out vec4 color;
                void main() { color = texture(colortex0, vec2(0.0)); }
            """.trimIndent(),
        )
        write(
            shaders,
            "shaders.properties",
            "texture.composite.colortex0=image/noise.dat TEXTURE_3D R8 2 2 2 RED UNSIGNED_BYTE",
        )
        writeBytes(shaders, "image/noise.dat", ByteArray(8) { it.toByte() })

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("typed-raw-alias"))
        val composite = plan.programs.single { it.name == "composite" }
        val final = plan.programs.single { it.name == "final" }

        assertEquals(setOf("colortex0"), composite.resourceUsage.sampledCustomTextures.keys)
        assertTrue(composite.resourceUsage.sampledBuffers.isEmpty())
        assertTrue(final.resourceUsage.sampledCustomTextures.isEmpty())
        assertEquals(
            mapOf("colortex0" to ShaderBufferId(ShaderBufferKind.COLORTEX, 0)),
            final.resourceUsage.sampledBuffers,
        )
    }

    @Test
    fun `generated noise follows the bounded generation directive`() {
        val shaders = temporary.resolve("generated-noise/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            "$SIMPLE_VERTEX\nconst int noiseTextureResolution = 64;",
        )
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                uniform sampler2D noisetex;
                out vec4 color;
                void main() { color = texture(noisetex, vec2(0.0)); }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("generated-noise"))

        assertEquals(IrisNoiseTextureDescriptor.Generated(64), plan.textures.noise)
        assertEquals(
            mapOf("noisetex" to IrisTextureId("noise")),
            plan.programs.single { it.name == "gbuffers_terrain" }.resourceUsage.sampledCustomTextures,
        )

        write(
            shaders,
            "final.vsh",
            "$SIMPLE_VERTEX\nconst int noiseTextureResolution = 8192;",
        )
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("generated-noise"))
            }.message.orEmpty(),
            "inconsistently",
        )
    }

    @Test
    fun `custom images are typed while unsafe texture paths fail closed`() {
        val shaders = temporary.resolve("unsupported-textures/shaders").createDirectories()
        writeProgramSet(shaders, null, "TEXTURES")
        write(
            shaders,
            "shaders.properties",
            """
                image.history_img=historySampler RGBA RGBA8 UNSIGNED_BYTE true false 4 2
                image.volume_img=volumeSampler RED_INTEGER R16UI UNSIGNED_INT false false 8 4 8
                image.relative_img=none RGBA RGBA16F HALF_FLOAT true true 0.5 0.25
                bufferObject.0=50855936
                bufferObject.3=16 true 0.5 0.25
            """.trimIndent(),
        )
        val resources = IrisShaderPackPlanner.plan(temporary.resolve("unsupported-textures")).customResources
        assertEquals(
            listOf(
                IrisCustomImageDescriptor(
                    "history_img",
                    "historySampler",
                    IrisCustomImageTarget.TEXTURE_2D,
                    IrisCustomImageFormat.RGBA,
                    IrisCustomImageInternalFormat.RGBA8,
                    IrisCustomImageType.UNSIGNED_BYTE,
                    true,
                    IrisCustomImageSize.Absolute(4, 2),
                ),
                IrisCustomImageDescriptor(
                    "volume_img",
                    "volumeSampler",
                    IrisCustomImageTarget.TEXTURE_3D,
                    IrisCustomImageFormat.RED_INTEGER,
                    IrisCustomImageInternalFormat.R16UI,
                    IrisCustomImageType.UNSIGNED_INT,
                    false,
                    IrisCustomImageSize.Absolute(8, 4, 8),
                ),
                IrisCustomImageDescriptor(
                    "relative_img",
                    null,
                    IrisCustomImageTarget.TEXTURE_2D,
                    IrisCustomImageFormat.RGBA,
                    IrisCustomImageInternalFormat.RGBA16F,
                    IrisCustomImageType.HALF_FLOAT,
                    true,
                    IrisCustomImageSize.Relative(0.5f, 0.25f),
                ),
            ),
            resources.images,
        )
        assertEquals(
            listOf(
                IrisShaderStorageBufferDescriptor(0, 50_855_936),
                IrisShaderStorageBufferDescriptor(3, 16, true, 0.5f, 0.25f),
            ),
            resources.shaderStorageBuffers,
        )

        write(shaders, "shaders.properties", "texture.gbuffers.referenceLut=../outside.png")
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("unsupported-textures"))
            }.message.orEmpty(),
            "escapes",
        )

        write(shaders, "shaders.properties", "texture.gbuffers.referenceLut=minecraft:textures/block/stone.png")
        val inactive = IrisShaderPackPlanner.plan(temporary.resolve("unsupported-textures"))
        assertEquals(
            IrisCustomTextureDescriptor.Resource(
                IrisResourceTextureDescriptor(
                    IrisTextureId("custom:gbuffers_and_shadow:referenceLut"),
                    "minecraft:textures/block/stone.png",
                ),
            ),
            inactive.textures.custom.single().texture,
        )
        assertEquals(1, inactive.textures.physicalTextureCount)

        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                uniform sampler2D referenceLut;
                out vec4 color;
                void main() { color = texture(referenceLut, vec2(0.0)); }
            """.trimIndent(),
        )
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("unsupported-textures"))
            }.message.orEmpty(),
            "source-native bridge",
        )

        write(shaders, "shaders.properties", "texture.gbuffers.referenceLut=minecraft:../stone.png")
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("unsupported-textures"))
            }.message.orEmpty(),
            "escapes its namespace",
        )
    }

    @Test
    fun `standard environment publishes the pinned Iris mac platform guard`() {
        val mac = IrisShaderPackPlanner.standardEnvironmentDefines(12004, "Mac OS X")
        val linux = IrisShaderPackPlanner.standardEnvironmentDefines(12004, "Linux")
        val indexedBlend = IrisShaderPackPlanner.standardEnvironmentDefines(
            12004,
            "Linux",
            perBufferBlending = true,
        )

        assertTrue("MC_OS_MAC" in mac)
        assertFalse("MC_OS_MAC" in linux)
        assertFalse("IRIS_FEATURE_PER_BUFFER_BLENDING" in linux)
        assertTrue("IRIS_FEATURE_PER_BUFFER_BLENDING" in indexedBlend)
        assertEquals("12004", mac["MC_VERSION"])
    }

    @Test
    fun `conflicting render target directives reject`() {
        val shaders = temporary.resolve("targets/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "gbuffers_terrain.fsh",
            "$SIMPLE_FRAGMENT\n/* RENDERTARGETS: 0 */\n/* DRAWBUFFERS:01 */",
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("targets"))
        }
    }

    @Test
    fun `missing composite rejects candidate`() {
        val shaders = temporary.resolve("missing/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)

        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("missing"))
        }
    }

    @Test
    fun `include cycles reject candidate`() {
        val shaders = temporary.resolve("cycle/shaders").createDirectories()
        write(shaders, "a.glsl", "#include \"b.glsl\"")
        write(shaders, "b.glsl", "#include \"a.glsl\"")
        write(shaders, "gbuffers_terrain.vsh", "#version 330 core\n#include \"a.glsl\"\nvoid main() {}")
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("cycle"))
        }
    }

    @Test
    fun `discovers bounded define options and applies validated overrides`() {
        val shaders = temporary.resolve("options/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                #define SHADOW_QUALITY 1 // [1 2 4]
                //#define WAVING_LEAVES
                const int SHADOW_SAMPLES = 4; // descriptive text [2 4 8]
                void main() { gl_Position = vec4(float(SHADOW_QUALITY)); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        val pack = temporary.resolve("options")

        assertEquals(
            listOf(
                ShaderPackOption("SHADOW_QUALITY", "1", listOf("1", "2", "4")),
                ShaderPackOption("SHADOW_SAMPLES", "4", listOf("2", "4", "8")),
                ShaderPackOption("WAVING_LEAVES", "false", listOf("false", "true")),
            ),
            IrisShaderPackPlanner.options(pack),
        )
        val plan = IrisShaderPackPlanner.plan(
            pack,
            mapOf("SHADOW_QUALITY" to "4", "SHADOW_SAMPLES" to "8", "WAVING_LEAVES" to "true"),
        )
        val vertex = plan.programs.single { it.phase == ShaderProgramPhase.TERRAIN }.vertex
        assertTrue("#define SHADOW_QUALITY 4" in vertex)
        assertTrue("const int SHADOW_SAMPLES = 8;" in vertex)
        assertTrue("#define WAVING_LEAVES" in vertex)
        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(pack, mapOf("SHADOW_QUALITY" to "8"))
        }
    }

    @Test
    fun `adjacent CRLF boolean defines remain distinct shader options`() {
        val shaders = temporary.resolve("crlf-options/shaders").createDirectories()
        write(
            shaders,
            "settings.glsl",
            "#define FIRST_FEATURE\r\n#define SECOND_FEATURE\r\n",
        )

        assertEquals(
            listOf(
                ShaderPackOption("FIRST_FEATURE", "true", listOf("false", "true")),
                ShaderPackOption("SECOND_FEATURE", "true", listOf("false", "true")),
            ),
            IrisShaderPackPlanner.options(temporary.resolve("crlf-options")),
        )
    }

    @Test
    fun `inconsistent conditional defines are not exposed as boolean options`() {
        val shaders = temporary.resolve("conditional-defines/shaders").createDirectories()
        write(
            shaders,
            "settings.glsl",
            """
                #define USER_FEATURE
                #ifdef USER_FEATURE
                    #define INTERNAL_FEATURE
                #endif
                #ifdef OTHER_FEATURE
                    // #define INTERNAL_FEATURE
                #endif
            """.trimIndent(),
        )

        assertEquals(
            listOf(ShaderPackOption("USER_FEATURE", "true", listOf("false", "true"))),
            IrisShaderPackPlanner.options(temporary.resolve("conditional-defines")),
        )
    }

    @Test
    fun `preserves an authored default outside the slider choices`() {
        val shaders = temporary.resolve("slider-default/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                #define CLOUD_THICKNESS 32.00 // [0.50 1.00 2.00]
                void main() { gl_Position = vec4(CLOUD_THICKNESS); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(shaders, "shaders.properties", "profile.POTATO=CLOUD_THICKNESS=32.00")
        val pack = temporary.resolve("slider-default")

        assertEquals(
            ShaderPackOption("CLOUD_THICKNESS", "32.00", listOf("0.50", "1.00", "2.00")),
            IrisShaderPackPlanner.options(pack).single(),
        )
        assertContains(
            IrisShaderPackPlanner.plan(pack).programs
                .single { it.phase == ShaderProgramPhase.TERRAIN }.vertex,
            "#define CLOUD_THICKNESS 32.00",
        )
        assertEquals("POTATO", IrisShaderPackPlanner.plan(pack).selectedProfile)
        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(pack, mapOf("CLOUD_THICKNESS" to "16.00"))
        }
    }

    @Test
    fun `accepts shader sliders across the complete bounded UI range`() {
        val shaders = temporary.resolve("large-slider/shaders").createDirectories()
        val values = (64..320).map(Int::toString)
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                #define CLOUD_ALTITUDE 192 // [${values.joinToString(" ")}]
                void main() { gl_Position = vec4(float(CLOUD_ALTITUDE)); }
            """.trimIndent(),
        )

        assertEquals(
            ShaderPackOption("CLOUD_ALTITUDE", "192", values),
            IrisShaderPackPlanner.options(temporary.resolve("large-slider")).single(),
        )
    }

    @Test
    fun `preprocesses conditional shader properties with effective option values`() {
        val shaders = temporary.resolve("conditional-properties/shaders").createDirectories()
        write(
            shaders,
            "settings.glsl",
            "#define CLOUD_SCALE 2 // [1 2 3]",
        )
        write(
            shaders,
            "shaders.properties",
            """
                #if CLOUD_SCALE == 1
                screen.columns = 1
                separateEntityDraws = false
                #elif CLOUD_SCALE == 2
                screen.columns = 2
                separateEntityDraws = true
                #else
                screen.columns = 3
                separateEntityDraws = false
                #endif
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        val pack = temporary.resolve("conditional-properties")

        assertEquals(2, IrisShaderPackPlanner.settings(pack).mainScreen?.columns)
        assertEquals(true, IrisShaderPackPlanner.plan(pack).separateEntityDraws)
        assertEquals(false, IrisShaderPackPlanner.plan(pack, mapOf("CLOUD_SCALE" to "3")).separateEntityDraws)
    }

    @Test
    fun `most specific matching profile inherits options and disables programs`() {
        val shaders = temporary.resolve("profiles/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                //#define BLOOM
                #define QUALITY 1 // [1 2 3]
                void main() { gl_Position = vec4(float(QUALITY)); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(shaders, "composite.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                profile.base=!BLOOM QUALITY=1 !REMOVED_OPTION
                profile.high=profile.base BLOOM \
                    QUALITY:2 !program.composite
            """.trimIndent(),
        )
        val pack = temporary.resolve("profiles")

        val base = IrisShaderPackPlanner.plan(pack)
        val high = IrisShaderPackPlanner.plan(pack, mapOf("BLOOM" to "true", "QUALITY" to "2"))

        assertEquals("base", base.selectedProfile)
        assertTrue(base.programs.any { it.name == "composite" })
        assertEquals("high", high.selectedProfile)
        assertTrue(high.programs.none { it.name == "composite" })
        assertTrue(high.programs.any { it.name == "final" })
    }

    @Test
    fun `profile selection prefers authored order when equally specific`() {
        val shaders = temporary.resolve("profile-precedence/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                //#define BLOOM
                #define QUALITY 1 // [1 2]
                void main() { gl_Position = vec4(float(QUALITY)); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                profile.first=!BLOOM
                profile.second=QUALITY=1
                profile.more-specific=!BLOOM QUALITY=1
            """.trimIndent(),
        )
        val pack = temporary.resolve("profile-precedence")

        assertEquals("more-specific", IrisShaderPackPlanner.plan(pack).selectedProfile)

        write(
            shaders,
            "shaders.properties",
            """
                profile.first=!BLOOM
                profile.second=QUALITY=1
            """.trimIndent(),
        )
        assertEquals("first", IrisShaderPackPlanner.plan(pack).selectedProfile)
    }

    @Test
    fun `shader settings retain authored profiles sliders and option screens`() {
        val shaders = temporary.resolve("settings-metadata/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                //#define BLOOM
                #define QUALITY 1 // [1 2 3]
                void main() { gl_Position = vec4(float(QUALITY)); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                sliders=QUALITY
                profile.low=!BLOOM QUALITY=1
                profile.high=BLOOM QUALITY=3
                screen=<profile> QUALITY [advanced] *
                screen.columns=2
                screen.advanced=BLOOM <empty>
                screen.advanced.columns=1
            """.trimIndent(),
        )

        val settings = IrisShaderPackPlanner.settings(temporary.resolve("settings-metadata"))

        assertEquals(listOf("QUALITY"), settings.sliders)
        assertEquals(listOf("low", "high"), settings.profiles.map(ShaderPackProfile::name))
        assertEquals(
            ShaderPackScreen(null, listOf("<profile>", "QUALITY", "[advanced]", "*"), 2),
            settings.mainScreen,
        )
        assertEquals(
            listOf(ShaderPackScreen("advanced", listOf("BLOOM", "<empty>"), 1)),
            settings.subScreens,
        )
        assertEquals("low", settings.selectedProfile(emptyMap())?.name)
        assertEquals("high", settings.selectedProfile(mapOf("BLOOM" to "true", "QUALITY" to "3"))?.name)
    }

    @Test
    fun `shader settings localize authored labels and collapse nested screens into main groups`() {
        val shaders = temporary.resolve("localized-settings/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                //#define FEATURE
                #define QUALITY 1 // [1 2]
                #define AMBIENT 0 // [0 1]
                #define HIDDEN 5 // [5 6]
                void main() { gl_Position = vec4(float(QUALITY)); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                profile.high=FEATURE QUALITY=2
                screen=[lighting] *
                screen.lighting=[shadows] AMBIENT
                screen.shadows=QUALITY FEATURE
            """.trimIndent(),
        )
        write(
            shaders,
            "lang/en_us.lang",
            """
                profile.high=High fidelity
                screen.lighting=Lighting
                option.QUALITY=Shadow quality
                option.QUALITY.comment=Controls the shadow sample budget.
                value.QUALITY.2=High
                option.AMBIENT=Ambient light
            """.trimIndent(),
        )
        write(shaders, "lang/pt_BR.LANG", "option.QUALITY=Qualidade das sombras")

        val settings = IrisShaderPackPlanner.settings(temporary.resolve("localized-settings"), "pt-BR")

        assertEquals("Qualidade das sombras", settings.language.option("QUALITY"))
        assertEquals("Controls the shadow sample budget.", settings.language.optionComment("QUALITY"))
        assertEquals("High", settings.language.value("QUALITY", "2"))
        assertEquals("High fidelity", settings.language.profile("high"))
        assertEquals("Lighting", settings.language.screen("lighting"))
        assertEquals(
            listOf(
                ShaderPackOptionGroup(null, listOf("HIDDEN")),
                ShaderPackOptionGroup("lighting", listOf("QUALITY", "FEATURE", "AMBIENT")),
            ),
            settings.optionGroups(),
        )
    }

    @Test
    fun `invalid profile inheritance and option screen references fail closed`() {
        val shaders = temporary.resolve("invalid-settings/shaders").createDirectories()
        writeProgramSet(shaders, null, "INVALID_SETTINGS")
        write(
            shaders,
            "gbuffers_terrain.vsh",
            "$SIMPLE_VERTEX\n//#define FEATURE",
        )
        val pack = temporary.resolve("invalid-settings")

        write(
            shaders,
            "shaders.properties",
            """
                profile.a=profile.b
                profile.b=profile.a
            """.trimIndent(),
        )
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.settings(pack) }.message.orEmpty(),
            "inheritance cycle",
        )

        write(shaders, "shaders.properties", "screen=[missing]")
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.settings(pack) }.message.orEmpty(),
            "unknown subscreen",
        )

        write(shaders, "shaders.properties", "profile.invalid=FEATURE=maybe")
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.settings(pack) }.message.orEmpty(),
            "invalid value",
        )

        write(shaders, "shaders.properties", "screen.columns=17")
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.settings(pack) }.message.orEmpty(),
            "integer in 1..16",
        )

        write(
            shaders,
            "shaders.properties",
            """
                screen=[first]
                screen.first=[second]
                screen.second=[first]
            """.trimIndent(),
        )
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.settings(pack) }.message.orEmpty(),
            "screen cycle",
        )
    }

    @Test
    fun `classifies pinned Iris program families without composite fallthrough`() {
        val shaders = temporary.resolve("families/shaders").createDirectories()
        val expected = linkedMapOf(
            "shadowcomp" to ShaderProgramPhase.SHADOW_COMPOSITE,
            "deferred12" to ShaderProgramPhase.DEFERRED,
            "gbuffers_water" to ShaderProgramPhase.TERRAIN,
            "gbuffers_block_translucent" to ShaderProgramPhase.BLOCK,
            "gbuffers_item" to ShaderProgramPhase.ITEM,
            "gbuffers_entities_glowing" to ShaderProgramPhase.ENTITY,
            "gbuffers_particles_translucent" to ShaderProgramPhase.PARTICLE,
            "gbuffers_skytextured" to ShaderProgramPhase.SKY,
            "gbuffers_weather" to ShaderProgramPhase.WEATHER,
            "gbuffers_hand_water" to ShaderProgramPhase.HAND,
            "gbuffers_textured_lit" to ShaderProgramPhase.BASIC,
        )
        val ignored = setOf("dh_terrain", "not_an_iris_program")
        (expected.keys + ignored + "gbuffers_terrain" + "shadow" + "final").forEach { name ->
            write(shaders, "$name.vsh", SIMPLE_VERTEX)
            write(shaders, "$name.fsh", SIMPLE_FRAGMENT)
        }

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("families"))

        expected.forEach { (name, phase) ->
            assertEquals(phase, plan.programs.single { it.name == name }.phase, name)
        }
        assertTrue(plan.programs.none { it.name in ignored })

        val failure = assertThrows<IllegalArgumentException> {
            IrisWorldShaderPipeline.validateProgramContract(plan)
        }
        assertTrue("gbuffers_water=TERRAIN" !in failure.message.orEmpty())
        assertTrue("gbuffers_entities_glowing=ENTITY" !in failure.message.orEmpty())
        assertContains(
            failure.message.orEmpty(),
            "gbuffers_entities_glowing has no retained scene-producer route",
        )
        assertTrue("shadowcomp=SHADOW_COMPOSITE" !in failure.message.orEmpty())
    }

    @Test
    fun `rejects scene bridges that no retained producer can select`() {
        val shaders = temporary.resolve("unreachable-scene/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "gbuffers_entities_glowing.vsh",
            """
            // minosoft:scene_bridge POSITION_COLOR COLOR uViewProjectionMatrix
            $SIMPLE_VERTEX
            """.trimIndent(),
        )
        write(shaders, "gbuffers_entities_glowing.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("unreachable-scene"))
        val failure = assertThrows<IllegalArgumentException> {
            IrisWorldShaderPipeline.validateProgramContract(plan)
        }

        assertContains(
            failure.message.orEmpty(),
            "gbuffers_entities_glowing has no retained scene-producer route",
        )
    }

    @Test
    fun `orders fullscreen family suffixes numerically`() {
        val shaders = temporary.resolve("fullscreen-order/shaders").createDirectories()
        listOf(
            "gbuffers_terrain",
            "composite",
            "composite2",
            "composite10",
            "final",
        ).forEach { name ->
            write(shaders, "$name.vsh", SIMPLE_VERTEX)
            write(shaders, "$name.fsh", SIMPLE_FRAGMENT)
        }
        listOf("composite2", "composite10").forEach { name ->
            write(
                shaders,
                "$name.csh",
                """
                    #version 430 core
                    layout(local_size_x = 1, local_size_y = 1) in;
                    void main() {}
                """.trimIndent(),
            )
        }

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("fullscreen-order"))

        assertEquals(
            listOf("composite", "composite2", "composite10"),
            IrisWorldShaderPipeline.fullscreenProgramOrder(plan, ShaderProgramPhase.COMPOSITE),
        )
        assertEquals(
            listOf("composite", "compute:composite2", "composite2", "compute:composite10", "composite10"),
            IrisWorldShaderPipeline.fullscreenExecutionOrder(plan, ShaderProgramPhase.COMPOSITE),
        )
    }

    @Test
    fun `alpha test directives are typed and inherited by a resolved particle fallback`() {
        val shaders = temporary.resolve("alpha-tests/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_textured.vsh",
            """
                #version 120
                void main() {
                    gl_Position = ftransform();
                    gl_FrontColor = gl_Color;
                    gl_TexCoord[0] = gl_MultiTexCoord0;
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "gbuffers_textured.fsh",
            """
                #version 120
                uniform sampler2D texture;
                void main() {
                    gl_FragData[0] = texture2D(texture, gl_TexCoord[0].st) * gl_Color;
                }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                alphaTest.gbuffers_textured=GREATER 0.004
                alphaTest.final=NOT_A_FUNCTION 1.0
            """.trimIndent(),
        )

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("alpha-tests"))
        val expected = IrisAlphaTest(IrisAlphaTestFunction.GREATER, 0.004f)

        assertEquals(expected, plan.programs.single { it.name == "gbuffers_textured" }.alphaTest)
        assertEquals(expected, plan.programs.single { it.name == "gbuffers_particles" }.alphaTest)
        assertEquals(null, plan.programs.single { it.name == "final" }.alphaTest)
    }

    @Test
    fun `alpha test off is retained as an explicit always override`() {
        val shaders = temporary.resolve("alpha-test-off/shaders").createDirectories()
        writeProgramSet(shaders, null, "alpha-test-off")
        write(shaders, "shaders.properties", "alphaTest.gbuffers_terrain=off")

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("alpha-test-off"))

        assertEquals(
            IrisAlphaTest.ALWAYS,
            plan.programs.single { it.name == "gbuffers_terrain" }.alphaTest,
        )
    }

    @Test
    fun `program and per-buffer blend directives are typed and inherited`() {
        val shaders = temporary.resolve("blend-overrides/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                /* RENDERTARGETS: 0,4 */
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec4 auxiliary;
                void main() {
                    color = vec4(1.0);
                    auxiliary = vec4(1.0);
                }
            """.trimIndent(),
        )
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                blend.gbuffers_terrain=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA
                blend.gbuffers_terrain.colortex4=off
            """.trimIndent(),
        )

        val terrain = IrisShaderPackPlanner.plan(temporary.resolve("blend-overrides"))
            .programs.single { it.name == "gbuffers_terrain" }

        assertEquals(
            IrisBlendMode.Enabled(
                de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState(
                    de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions.SOURCE_ALPHA,
                    de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                    de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions.ONE,
                    de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                ),
            ),
            terrain.blendOverride.program,
        )
        assertEquals(
            IrisBlendMode.Off,
            terrain.blendOverride.buffers[ShaderBufferId(ShaderBufferKind.COLORTEX, 4)],
        )
        assertTrue(terrain.blendOverride.requiresPerBufferBlending)
    }

    @Test
    fun `invalid blend directives fail before pipeline publication`() {
        val shaders = temporary.resolve("invalid-blend/shaders").createDirectories()
        writeProgramSet(shaders, null, "invalid-blend")
        write(
            shaders,
            "shaders.properties",
            "blend.gbuffers_terrain=SRC_ALPHA INVALID ONE ONE_MINUS_SRC_ALPHA",
        )

        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("invalid-blend"))
            }.message.orEmpty(),
            "Invalid Iris blend factor 'INVALID'",
        )
    }

    @Test
    fun `project reference pack has stable executable contract`() {
        val plan = IrisShaderPackPlanner.plan(
            Path.of("src/integration-test/resources/render_substrate/iris-reference"),
        )

        assertEquals("iris-reference", plan.packName)
        assertEquals("77ea5a23b0b29e149ff78ffdca11b3f236293124cc8e7c6ce32ecbc0ee124eef", plan.fingerprint)
        assertEquals("default", plan.selectedProfile)
        assertEquals(18.0f, plan.sunPathRotation)
        assertEquals(IrisSmoothingDirectives(40.0f, 12.0f, 4.0f), plan.smoothingDirectives)
        assertEquals(IrisNoiseTextureDescriptor.Generated(64), plan.textures.noise)
        assertEquals(
            mapOf("noisetex" to IrisTextureId("noise")),
            plan.programs.single { it.name == "final" }.resourceUsage.sampledCustomTextures,
        )
        assertEquals(true, plan.separateEntityDraws)
        assertEquals(7, plan.idMaps.entities[ResourceLocation.of("naturalist:boar")])
        assertEquals(12, plan.idMaps.items[ResourceLocation.of("minecraft:torch")])
        assertEquals(20, plan.idMaps.items[ResourceLocation.of("minecraft:iron_boots")])
        assertEquals(21, plan.idMaps.items[ResourceLocation.of("minecraft:iron_leggings")])
        assertEquals(22, plan.idMaps.items[ResourceLocation.of("minecraft:iron_chestplate")])
        assertEquals(23, plan.idMaps.items[ResourceLocation.of("minecraft:iron_helmet")])
        assertEquals(41, plan.idMaps.blocks.single { it.identifier == ResourceLocation.of("minecraft:chest") }.id)
        assertEquals(setOf(RenderViewId.MAIN, IrisShaderPackPlanner.SHADOW_VIEW), plan.views)
        assertEquals(
            setOf(
                ShaderProgramPhase.TERRAIN,
                ShaderProgramPhase.BEGIN,
                ShaderProgramPhase.SHADOW,
                ShaderProgramPhase.SHADOW_COMPOSITE,
                ShaderProgramPhase.PREPARE,
                ShaderProgramPhase.BASIC,
                ShaderProgramPhase.BLOCK,
                ShaderProgramPhase.SKY,
                ShaderProgramPhase.ENTITY,
                ShaderProgramPhase.PARTICLE,
                ShaderProgramPhase.HAND,
                ShaderProgramPhase.WEATHER,
                ShaderProgramPhase.DEFERRED,
                ShaderProgramPhase.COMPOSITE,
                ShaderProgramPhase.FINAL,
            ),
            plan.programs.mapTo(mutableSetOf(), ShaderProgramSource::phase),
        )
        assertEquals(
            setOf("gbuffers_damagedblock", "gbuffers_terrain", "gbuffers_terrain_solid"),
            plan.programs.filter { it.phase == ShaderProgramPhase.TERRAIN }.mapTo(mutableSetOf(), ShaderProgramSource::name),
        )
        assertEquals(
            setOf(
                SceneProgramBridge(
                    SceneVertexAbi.POSITION_COLOR,
                    SceneStateAbi.COLOR,
                    setOf("uViewProjectionMatrix"),
                ),
                SceneProgramBridge(
                    SceneVertexAbi.POSITION_COLOR_LIGHT,
                    SceneStateAbi.LIGHT_COLOR,
                    setOf("uLightMapBuffer", "uViewProjectionMatrix"),
                ),
            ),
            plan.programs.single { it.name == "gbuffers_basic" }.sceneBridges,
        )
        assertContains(
            plan.programs.single { it.name == "gbuffers_particles" }.geometry.orEmpty(),
            "layout (points) in",
        )
        assertEquals(
            setOf("shadow", "shadow_solid", "shadow_cutout"),
            plan.programs.filter { it.phase == ShaderProgramPhase.SHADOW }
                .mapTo(mutableSetOf(), ShaderProgramSource::name),
        )
        assertTrue(plan.programs.any { it.name == "gbuffers_entities_translucent" })
        assertTrue(plan.programs.any { it.name == "gbuffers_block_translucent" })
        assertTrue(plan.programs.any { it.name == "gbuffers_line" })
        assertTrue(plan.programs.any { it.name == "gbuffers_spidereyes" })
        assertTrue(plan.programs.any { it.name == "gbuffers_armor_glint" })
        assertTrue(plan.programs.any { it.name == "gbuffers_beaconbeam" })
        assertTrue(plan.programs.any { it.name == "gbuffers_lightning" })
        assertTrue(plan.programs.any { it.name == "gbuffers_hand_water" })
        assertTrue(
            plan.programs.filter { it.name in setOf("gbuffers_terrain", "gbuffers_terrain_solid", "shadow_solid") }
                .all { IrisTextureArrayState.UNIFORM in it.uniforms },
        )
        assertTrue(
            plan.programs.single { it.name == "final" }.uniforms.containsAll(
                IrisFrameState.SUPPORTED_UNIFORMS.filterTo(mutableSetOf()) {
                    it.startsWith("fog") || it.startsWith("iris_Fog")
                } + IrisRenderStage.SUPPORTED_UNIFORMS + setOf(
                    "sunPosition",
                    "moonPosition",
                    "shadowAngle",
                    "shadowLightPosition",
                    "upPosition",
                    "gbufferPreviousModelView",
                    "gbufferPreviousProjection",
                    "currentDate",
                    "currentTime",
                    "currentYearTime",
                    "isEyeInWater",
                    "is_sneaking",
                    "is_sprinting",
                    "is_hurt",
                    "is_invisible",
                    "is_burning",
                    "is_on_ground",
                    "hideGUI",
                    "isRightHanded",
                    "currentPlayerHealth",
                    "maxPlayerHealth",
                    "currentPlayerHunger",
                    "maxPlayerHunger",
                    "currentPlayerArmor",
                    "maxPlayerArmor",
                    "currentPlayerAir",
                    "maxPlayerAir",
                    "firstPersonCamera",
                    "isSpectator",
                    "blindness",
                    "darknessFactor",
                    "darknessLightFactor",
                    "nightVision",
                    "biome",
                    "biome_category",
                    "biome_precipitation",
                    "rainfall",
                    "temperature",
                    "eyeBrightness",
                    "eyeBrightnessSmooth",
                    "skyColor",
                    "pi",
                    "wetness",
                    "playerLookVector",
                    "playerBodyVector",
                    "bedrockLevel",
                    "cloudHeight",
                    "heightLimit",
                    "logicalHeightLimit",
                    "hasCeiling",
                    "hasSkylight",
                    "ambientLight",
                    "cloudTime",
                    "currentColorSpace",
                    "currentSelectedBlockId",
                    "currentSelectedBlockPos",
                ),
            ),
        )
        val settings = IrisShaderPackPlanner.settings(
            Path.of("src/integration-test/resources/render_substrate/iris-reference"),
        )
        assertEquals(listOf("REFERENCE_QUALITY"), settings.sliders)
        assertEquals(listOf("default", "validation"), settings.profiles.map(ShaderPackProfile::name))
        assertEquals(2, settings.mainScreen?.columns)
        assertEquals(listOf("advanced"), settings.subScreens.mapNotNull(ShaderPackScreen::id))
        assertTrue(
            plan.programs.single { it.name == "gbuffers_block" }.sceneBridges.any {
                it.vertexAbi == SceneVertexAbi.SKELETAL &&
                    it.stateAbi == SceneStateAbi.SKELETAL_LIGHTMAP
            },
        )
        assertTrue(
            plan.programs.single { it.name == "gbuffers_block_translucent" }.sceneBridges.any {
                it.vertexAbi == SceneVertexAbi.SKELETAL &&
                    it.stateAbi == SceneStateAbi.SKELETAL_LIGHTMAP
            },
        )
        assertEquals(
            setOf(
                ShaderBufferId(ShaderBufferKind.COLORTEX, 0),
                ShaderBufferId(ShaderBufferKind.DEPTHTEX, 0),
                ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0),
                ShaderBufferId(ShaderBufferKind.SHADOWTEX, 1),
                ShaderBufferId(ShaderBufferKind.SHADOWCOLOR, 0),
            ),
            plan.buffers.buffers.mapTo(mutableSetOf(), ShaderBufferDescriptor::id),
        )
        assertEquals(
            setOf(
                SceneStateAbi.SKELETAL_TINTED,
                SceneStateAbi.SKELETAL_LIGHTMAP,
                SceneStateAbi.PLAYER,
                SceneStateAbi.BLOCK,
                SceneStateAbi.FLASHING_BLOCK,
                SceneStateAbi.ENTITY_FLAME,
                SceneStateAbi.TERRAIN,
            ),
            plan.programs.single { it.name == "shadow" }.sceneBridges
                .mapTo(mutableSetOf(), SceneProgramBridge::stateAbi),
        )
        assertEquals(
            SceneStateAbi.entries.toSet() -
                setOf(SceneStateAbi.TERRAIN, SceneStateAbi.DISTANT_TERRAIN),
            plan.programs.filter { it.phase != ShaderProgramPhase.SHADOW }.flatMapTo(mutableSetOf()) { program ->
                program.sceneBridges.map(SceneProgramBridge::stateAbi)
            },
        )
        assertEquals(
            setOf(
                VertexSemantic.POSITION,
                VertexSemantic.TEXTURE_COORDINATE,
                VertexSemantic.TEXTURE_LAYER,
                VertexSemantic.PACKED_LIGHT_COLOR,
                VertexSemantic.BLOCK_ID,
                VertexSemantic.MID_TEXTURE_COORDINATE,
                VertexSemantic.TANGENT,
                VertexSemantic.NORMAL,
                VertexSemantic.MID_BLOCK,
            ),
            plan.requiredTerrainSemantics,
        )
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `rejects inconsistent sun path rotation directives`() {
        val shaders = temporary.resolve("sun-path-conflict/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "gbuffers_terrain.fsh",
            """
                #version 330 core
                const float sunPathRotation = 10.0;
                out vec4 color;
                void main() { color = vec4(1.0); }
            """.trimIndent(),
        )
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            """
                #version 330 core
                const float sunPathRotation = 20.0;
                out vec4 color;
                void main() { color = vec4(1.0); }
            """.trimIndent(),
        )

        val error = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("sun-path-conflict"))
        }

        assertContains(error.message.orEmpty(), "inconsistent sunPathRotation")
    }

    @Test
    fun `smoothing directives are finite non-negative and generation consistent`() {
        val shaders = temporary.resolve("smoothing-directives/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            "$SIMPLE_VERTEX\nconst float wetnessHalflife = 10.0;",
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "composite.fsh",
            "$SIMPLE_FRAGMENT\nconst float wetnessHalflife = 20.0;",
        )

        val inconsistent = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("smoothing-directives"))
        }
        assertContains(inconsistent.message.orEmpty(), "inconsistent wetnessHalflife")

        write(
            shaders,
            "composite.fsh",
            "$SIMPLE_FRAGMENT\nconst float wetnessHalflife = -1.0;",
        )
        write(
            shaders,
            "gbuffers_terrain.vsh",
            "$SIMPLE_VERTEX\nconst float wetnessHalflife = -1.0;",
        )
        val negative = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("smoothing-directives"))
        }
        assertContains(negative.message.orEmpty(), "wetness half-life")
    }

    @Test
    fun `float directives use a bounded scan across large expanded stages`() {
        val shaders = temporary.resolve("bounded-float-directives/shaders").createDirectories()
        val expanded = buildString {
            appendLine(SIMPLE_VERTEX)
            repeat(12_000) { index ->
                append("const float ignoredDirective")
                append(index)
                appendLine(" = 1.0;")
            }
            appendLine("const\nfloat\nwetnessHalflife\n=\n4.0f\n;")
            appendLine("const float shadowDistance = 192.0;")
        }
        write(shaders, "gbuffers_terrain.vsh", expanded)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("bounded-float-directives"))

        assertEquals(4.0f, plan.smoothingDirectives.wetnessHalfLife)
        assertEquals(192.0f, plan.shadowDirectives.distance)

        write(
            shaders,
            "gbuffers_terrain.vsh",
            "$SIMPLE_VERTEX\nconst float shadowDistance = 1e999;",
        )
        val nonFinite = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("bounded-float-directives"))
        }
        assertContains(nonFinite.message.orEmpty(), "non-finite shadowDistance")
    }

    @Test
    fun `uniform inspection stays bounded across large expanded programs`() {
        val shaders = temporary.resolve("bounded-uniform-inspection/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        val fragment = buildString {
            appendLine("#version 330 core")
            appendLine("uniform highp sampler2D colortex0 [ 1 ];")
            appendLine("uniform float viewWidth;")
            appendLine("uniform float unusedValue;")
            repeat(12_000) { index ->
                append("float unrelatedIdentifier")
                append(index)
                appendLine(" = 0.0;")
            }
            appendLine("out vec4 color;")
            appendLine("void main() { color = texture(colortex0, vec2(0.5)) + vec4(viewWidth * 0.0); }")
        }
        write(shaders, "final.fsh", fragment)

        val final = IrisShaderPackPlanner.plan(temporary.resolve("bounded-uniform-inspection"))
            .programs.single { it.name == "final" }

        assertEquals(setOf("colortex0", "viewWidth"), final.uniforms)
        assertEquals(setOf("colortex0"), final.samplers)
    }

    @Test
    fun `runtime contract rejects declared bindings the host does not upload`() {
        val shaders = temporary.resolve("bindings/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            "$SIMPLE_VERTEX\n// uniform float ignoredComment;\nuniform float frameTimeCounter;",
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(
            shaders,
            "final.fsh",
            """
                #version 330 core
                uniform sampler2D unsupportedSampler;
                uniform float unsupportedMood;
                out vec4 foutColor;
                void main() {
                    foutColor = texture(unsupportedSampler, vec2(0.5)) * unsupportedMood;
                }
            """.trimIndent(),
        )

        val failure = assertThrows<IllegalArgumentException> {
            IrisWorldShaderPipeline.validateProgramContract(
                IrisShaderPackPlanner.plan(temporary.resolve("bindings")),
            )
        }

        assertContains(failure.message.orEmpty(), "unsupportedSampler")
        assertContains(failure.message.orEmpty(), "unsupportedMood")
        assertTrue("frameTimeCounter" !in failure.message.orEmpty())
        assertTrue("ignoredComment" !in failure.message.orEmpty())
    }

    @Test
    fun `runtime contract accepts the distant region draw offset`() {
        val shaders = temporary.resolve("distant-offset/shaders").createDirectories()
        write(shaders, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "dh_terrain.vsh",
            """
                #version 330 compatibility
                varying vec4 color;
                void main() {
                    color = gl_Color;
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "dh_terrain.fsh",
            """
                #version 330 compatibility
                varying vec4 color;
                void main() { gl_FragData[0] = color; }
            """.trimIndent(),
        )

        val plan = IrisShaderPackPlanner.plan(
            temporary.resolve("distant-offset"),
            preprocessorDefines = IrisShaderPackPlanner.standardEnvironmentDefines(
                minecraftVersion = 12004,
                perBufferBlending = true,
                distantHorizons = true,
            ),
        )

        val distant = plan.programs.single { it.name == "dh_terrain" }
        assertContains(distant.uniforms, "uPageOffset")
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `malformed custom images and shader storage fail before allocation`() {
        val shaders = temporary.resolve("invalid-custom-resources/shaders").createDirectories()
        writeProgramSet(shaders, null, "RESOURCES")

        write(
            shaders,
            "shaders.properties",
            "image.bad=badSampler RGBA R16UI UNSIGNED_INT true false 4 4",
        )
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("invalid-custom-resources"))
            }.message.orEmpty(),
            "incompatible",
        )

        write(shaders, "shaders.properties", "image.bad=badSampler RGBA RGBA8 UNSIGNED_BYTE true true 0.5")
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("invalid-custom-resources"))
            }.message.orEmpty(),
            "width and height scales",
        )

        write(shaders, "shaders.properties", "bufferObject.9=16")
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("invalid-custom-resources"))
            }.message.orEmpty(),
            "0..8",
        )

        write(shaders, "shaders.properties", "bufferObject.0=16 false 1 1")
        assertContains(
            assertThrows<IllegalArgumentException> {
                IrisShaderPackPlanner.plan(temporary.resolve("invalid-custom-resources"))
            }.message.orEmpty(),
            "relative=true",
        )
    }

    @Test
    fun `focused shadow pack authors exact fallback caster routes`() {
        val pack = Path.of("src/integration-test/resources/render_substrate/iris-block-entity-shadow")
        val plan = IrisShaderPackPlanner.plan(pack)

        assertEquals(false, plan.shadowDirectives.terrain)
        assertEquals(false, plan.shadowDirectives.translucentTerrain)
        assertEquals(false, plan.shadowDirectives.entities)
        assertEquals(false, plan.shadowDirectives.player)
        assertEquals(true, plan.shadowDirectives.blockEntities)
        assertEquals(false, plan.shadowDirectives.lightBlockEntities)
        assertEquals(setOf(RenderViewId.MAIN, IrisShaderPackPlanner.SHADOW_VIEW), plan.views)
        assertEquals(
            setOf(
                SceneProgramBridge(
                    SceneVertexAbi.SKELETAL,
                    SceneStateAbi.SKELETAL_LIGHTMAP,
                    setOf("uTextures", "uSkeletalBuffer"),
                ),
                SceneProgramBridge(
                    SceneVertexAbi.PLAYER_SKELETAL,
                    SceneStateAbi.PLAYER,
                    setOf(
                        "uTextures",
                        "uSkeletalBuffer",
                        "uIndexLayer",
                        "uSkinParts",
                        "uInflate",
                        "uHideBase",
                        "uFeaturePart",
                    ),
                ),
            ),
            plan.programs.single { it.phase == ShaderProgramPhase.SHADOW }.sceneBridges,
        )
        val playerOnly = IrisShaderPackPlanner.plan(pack, mapOf("SHADOW_CASTER_MODE" to "1"))
        assertEquals(false, playerOnly.shadowDirectives.entities)
        assertEquals(true, playerOnly.shadowDirectives.player)
        assertEquals(false, playerOnly.shadowDirectives.blockEntities)
        assertEquals(false, playerOnly.shadowDirectives.lightBlockEntities)

        val lightBlockEntities = IrisShaderPackPlanner.plan(pack, mapOf("SHADOW_CASTER_MODE" to "2"))
        assertEquals(false, lightBlockEntities.shadowDirectives.entities)
        assertEquals(false, lightBlockEntities.shadowDirectives.player)
        assertEquals(false, lightBlockEntities.shadowDirectives.blockEntities)
        assertEquals(true, lightBlockEntities.shadowDirectives.lightBlockEntities)
    }

    @Test
    fun `paired tessellation stages are retained while incomplete graphics stages reject`() {
        val staged = temporary.resolve("staged/shaders").createDirectories()
        write(
            staged,
            "gbuffers_terrain.vsh",
            """
                #version 120
                varying vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                }
            """.trimIndent(),
        )
        write(
            staged,
            "gbuffers_terrain.fsh",
            """
                #version 120
                varying vec2 uv;
                uniform sampler2D texture;
                uniform sampler2D lightmap;
                void main() {
                    gl_FragData[0] = texture2D(texture, uv);
                }
            """.trimIndent(),
        )
        write(
            staged,
            "gbuffers_terrain.tcs",
            """
                #version 400 compatibility
                layout(vertices = 3) out;
                void main() {
                    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                    gl_TessLevelOuter[0] = 1.0;
                    gl_TessLevelOuter[1] = 1.0;
                    gl_TessLevelOuter[2] = 1.0;
                    gl_TessLevelInner[0] = 1.0;
                }
            """.trimIndent(),
        )
        write(
            staged,
            "gbuffers_terrain.tes",
            """
                #version 400 compatibility
                layout(triangles, equal_spacing, cw) in;
                void main() {
                    gl_Position =
                        gl_TessCoord.x * gl_in[0].gl_Position +
                        gl_TessCoord.y * gl_in[1].gl_Position +
                        gl_TessCoord.z * gl_in[2].gl_Position;
                }
            """.trimIndent(),
        )
        write(staged, "final.vsh", SIMPLE_VERTEX)
        write(staged, "final.fsh", SIMPLE_FRAGMENT)
        val terrain = IrisShaderPackPlanner.plan(temporary.resolve("staged"))
            .programs.single { it.name == "gbuffers_terrain" }
        assertContains(terrain.tessellationControl.orEmpty(), "#version 400 core")
        assertContains(terrain.tessellationEvaluation.orEmpty(), "#version 400 core")
        assertContains(terrain.tessellationControl.orEmpty(), "minosoftTessellationTextureArray")
        assertEquals(3, terrain.tessellationPatchVertices)

        write(
            staged,
            "gbuffers_terrain.gsh",
            """
                #version 400 core
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                void main() {
                    for (int vertex = 0; vertex < 3; vertex++) {
                        gl_Position = gl_in[vertex].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
            """.trimIndent(),
        )
        val geometryFailure = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("staged"))
        }
        assertContains(geometryFailure.message.orEmpty(), "explicit geometry-stage bridge")
        Files.delete(staged.resolve("gbuffers_terrain.gsh"))

        Files.delete(staged.resolve("gbuffers_terrain.tes"))
        val stageFailure = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("staged"))
        }
        assertContains(stageFailure.message.orEmpty(), "paired tessellation")

        val incomplete = temporary.resolve("incomplete/shaders").createDirectories()
        write(incomplete, "gbuffers_terrain.vsh", SIMPLE_VERTEX)
        write(incomplete, "final.vsh", SIMPLE_VERTEX)
        write(incomplete, "final.fsh", SIMPLE_FRAGMENT)
        val pairFailure = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("incomplete"))
        }
        assertContains(pairFailure.message.orEmpty(), "gbuffers_terrain")
        assertContains(pairFailure.message.orEmpty(), "paired vertex and fragment")
    }

    @Test
    fun `compute setup programs follow option enablement and retain their distinct phase`() {
        val shaders = temporary.resolve("disabled-compute/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(
            shaders,
            "settings.glsl",
            """
                //#define COMPUTE_LIGHTING
            """.trimIndent(),
        )
        write(
            shaders,
            "setup.csh",
            """
                #version 430 core
                layout(local_size_x = 8, local_size_y = 8) in;
                void main() {}
            """.trimIndent(),
        )
        write(
            shaders,
            "shaders.properties",
            "program.setup.enabled=COMPUTE_LIGHTING",
        )

        val pack = temporary.resolve("disabled-compute")
        assertTrue(IrisShaderPackPlanner.plan(pack).computePrograms.none { it.name == "setup" })

        val setup = IrisShaderPackPlanner.plan(
            pack,
            mapOf("COMPUTE_LIGHTING" to "true"),
        ).computePrograms.single { it.name == "setup" }
        assertEquals(ShaderProgramPhase.SETUP, setup.phase)
        assertEquals(IrisComputeDispatch.Relative(1.0f, 1.0f, 8, 8), setup.dispatch)
    }

    @Test
    fun `plans compute dispatch with writable images samplers and shader storage`() {
        val shaders = temporary.resolve("compute/shaders").createDirectories()
        writeProgramSet(shaders, null, "COMPUTE")
        write(shaders, "shadow.vsh", SIMPLE_VERTEX)
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(shaders, "shadowcomp.vsh", SIMPLE_VERTEX)
        write(shaders, "shadowcomp.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shadowcomp.csh",
            """
                #version 430 compatibility
                layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;
                const ivec3 workGroups = ivec3(4, 2, 4);
                layout(std430, binding = 0) buffer Data { uint values[]; };
                uniform usampler3D voxel_sampler;
                writeonly uniform uimage3D voxel_img;
                void main() {
                    uint value = texelFetch(voxel_sampler, ivec3(gl_GlobalInvocationID), 0).r;
                    values[gl_GlobalInvocationID.x] = value;
                    imageStore(voxel_img, ivec3(gl_GlobalInvocationID), uvec4(value));
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "shaders.properties",
            """
                image.voxel_img=voxel_sampler RED_INTEGER R16UI UNSIGNED_INT true false 32 16 32
                bufferObject.0=4096
            """.trimIndent(),
        )

        val compute = IrisShaderPackPlanner.plan(temporary.resolve("compute")).computePrograms.single()

        assertEquals("shadowcomp", compute.name)
        assertEquals(ShaderProgramPhase.SHADOW_COMPOSITE, compute.phase)
        assertEquals(IrisComputeDispatch.Absolute(4, 2, 4), compute.dispatch)
        assertEquals(mapOf("voxel_sampler" to "voxel_img"), compute.resourceUsage.sampledCustomImages)
        assertEquals(setOf("voxel_img"), compute.resourceUsage.customImages)
        assertContains(compute.source, "#version 430 core")
    }

    @Test
    fun `plans bounded indirect compute dispatch from shader storage`() {
        val shaders = temporary.resolve("indirect-compute/shaders").createDirectories()
        writeProgramSet(shaders, null, "INDIRECT_COMPUTE")
        write(
            shaders,
            "shadowcomp.csh",
            """
                #version 430 core
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(std430, binding = 0) buffer Dispatch { uint values[]; };
                void main() { values[gl_GlobalInvocationID.x] += 1u; }
            """.trimIndent(),
        )
        val pack = temporary.resolve("indirect-compute")

        write(
            shaders,
            "shaders.properties",
            """
                bufferObject.0=64
                indirect.shadowcomp=0 16
            """.trimIndent(),
        )
        val compute = IrisShaderPackPlanner.plan(pack).computePrograms.single()
        assertEquals(IrisComputeDispatch.Indirect(0, 16), compute.dispatch)

        write(
            shaders,
            "shaders.properties",
            """
                bufferObject.0=64
                indirect.shadowcomp=1 16
            """.trimIndent(),
        )
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.plan(pack) }.message.orEmpty(),
            "undeclared bufferObject.1",
        )

        write(
            shaders,
            "shaders.properties",
            """
                bufferObject.0=64
                indirect.shadowcomp=0 2
            """.trimIndent(),
        )
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.plan(pack) }.message.orEmpty(),
            "non-negative multiple of 4",
        )

        write(
            shaders,
            "shaders.properties",
            """
                bufferObject.0=64
                indirect.shadowcomp=0 56
            """.trimIndent(),
        )
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.plan(pack) }.message.orEmpty(),
            "reads 12 bytes",
        )
    }

    @Test
    fun `plans shadow and final compute families with render target images`() {
        val shaders = temporary.resolve("boundary-compute/shaders").createDirectories()
        writeProgramSet(shaders, null, "BOUNDARY_COMPUTE")
        write(shaders, "shadow.vsh", SIMPLE_VERTEX)
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shadow.csh",
            """
                #version 430 core
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(rgba8) uniform image2D colorimg4;
                layout(rgba8) uniform image2D shadowcolorimg2;
                void main() {
                    ivec2 position = ivec2(gl_GlobalInvocationID.xy);
                    imageStore(colorimg4, position, imageLoad(shadowcolorimg2, position));
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "shadow2.csh",
            """
                #version 430 core
                layout(local_size_x = 4, local_size_y = 4) in;
                void main() {}
            """.trimIndent(),
        )
        write(
            shaders,
            "final.csh",
            """
                #version 430 core
                layout(local_size_x = 16, local_size_y = 8) in;
                layout(rgba8) uniform image2D colorimg4;
                void main() {
                    ivec2 position = ivec2(gl_GlobalInvocationID.xy);
                    imageStore(colorimg4, position, imageLoad(colorimg4, position));
                }
            """.trimIndent(),
        )
        write(
            shaders,
            "final3.csh",
            """
                #version 430 core
                layout(local_size_x = 2, local_size_y = 2) in;
                void main() {}
            """.trimIndent(),
        )

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("boundary-compute"))
        val shadow = plan.computePrograms.single { it.name == "shadow" }
        val final = plan.computePrograms.single { it.name == "final" }

        assertEquals(ShaderProgramPhase.SHADOW, shadow.phase)
        assertEquals(ShaderProgramPhase.SHADOW, plan.computePrograms.single { it.name == "shadow2" }.phase)
        assertEquals(
            mapOf(
                "colorimg4" to ShaderBufferId(ShaderBufferKind.COLORTEX, 4),
                "shadowcolorimg2" to ShaderBufferId(ShaderBufferKind.SHADOWCOLOR, 2),
            ),
            shadow.resourceUsage.renderTargetImages,
        )
        assertEquals(ShaderProgramPhase.FINAL, final.phase)
        assertEquals(ShaderProgramPhase.FINAL, plan.computePrograms.single { it.name == "final3" }.phase)
        assertEquals(
            mapOf("colorimg4" to ShaderBufferId(ShaderBufferKind.COLORTEX, 4)),
            final.resourceUsage.renderTargetImages,
        )
        assertTrue(plan.buffers[ShaderBufferId(ShaderBufferKind.COLORTEX, 4)] != null)
        assertTrue(plan.buffers[ShaderBufferId(ShaderBufferKind.SHADOWCOLOR, 2)] != null)
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `generic textured program is an executable terrain fallback`() {
        val shaders = temporary.resolve("generic-terrain/shaders").createDirectories()
        write(shaders, "gbuffers_textured.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_textured.fsh", SIMPLE_FRAGMENT)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("generic-terrain"))

        assertEquals(
            ShaderProgramPhase.BASIC,
            plan.programs.single { it.name == "gbuffers_textured" }.phase,
        )
        IrisWorldShaderPipeline.validateProgramContract(plan)
    }

    @Test
    fun `legacy world directories select only the active dimension programs`() {
        val shaders = temporary.resolve("legacy-dimensions/shaders").createDirectories()
        writeProgramSet(shaders, "world0", "OVERWORLD")
        writeProgramSet(shaders, "world-1", "NETHER")
        writeProgramSet(shaders, "world1", "END")
        write(shaders, "world1/inactive.csh", "#version 430 core")

        val overworld = IrisShaderPackPlanner.plan(
            temporary.resolve("legacy-dimensions"),
            dimension = ResourceLocation.of("minecraft:overworld"),
        )
        val nether = IrisShaderPackPlanner.plan(
            temporary.resolve("legacy-dimensions"),
            dimension = ResourceLocation.of("minecraft:the_nether"),
        )

        assertEquals("world0", overworld.programDirectory)
        assertContains(overworld.programs.single { it.name == "gbuffers_terrain" }.vertex, "OVERWORLD")
        assertEquals("world-1", nether.programDirectory)
        assertContains(nether.programs.single { it.name == "gbuffers_terrain" }.vertex, "NETHER")
        assertTrue(overworld.fingerprint != nether.fingerprint)
        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(
                temporary.resolve("legacy-dimensions"),
                dimension = ResourceLocation.of("minecraft:the_end"),
            )
        }
    }

    @Test
    fun `dimension properties prefer exact mappings then wildcard and otherwise use base`() {
        val shaders = temporary.resolve("mapped-dimensions/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        writeProgramSet(shaders, "nether", "NETHER")
        writeProgramSet(shaders, "modded", "MODDED")
        write(
            shaders,
            "dimension.properties",
            """
                dimension.nether=the_nether
                dimension.modded=example:moon
                dimension.optional=
            """.trimIndent(),
        )

        val nether = IrisShaderPackPlanner.plan(
            temporary.resolve("mapped-dimensions"),
            dimension = ResourceLocation.of("minecraft:the_nether"),
        )
        val base = IrisShaderPackPlanner.plan(
            temporary.resolve("mapped-dimensions"),
            dimension = ResourceLocation.of("minecraft:overworld"),
        )

        assertEquals("nether", nether.programDirectory)
        assertContains(nether.programs.single { it.name == "gbuffers_terrain" }.vertex, "NETHER")
        assertEquals(null, base.programDirectory)
        assertContains(base.programs.single { it.name == "gbuffers_terrain" }.vertex, "BASE")

        write(
            shaders,
            "dimension.properties",
            """
                dimension.fallback=*
                dimension.modded=example:moon
            """.trimIndent(),
        )
        writeProgramSet(shaders, "fallback", "FALLBACK")
        val exact = IrisShaderPackPlanner.plan(
            temporary.resolve("mapped-dimensions"),
            dimension = ResourceLocation.of("example:moon"),
        )
        val fallback = IrisShaderPackPlanner.plan(
            temporary.resolve("mapped-dimensions"),
            dimension = ResourceLocation.of("example:other"),
        )

        assertEquals("modded", exact.programDirectory)
        assertContains(exact.programs.single { it.name == "gbuffers_terrain" }.vertex, "MODDED")
        assertEquals("fallback", fallback.programDirectory)
        assertContains(fallback.programs.single { it.name == "gbuffers_terrain" }.vertex, "FALLBACK")
    }

    @Test
    fun `program enabled expressions use validated boolean shader options`() {
        val shaders = temporary.resolve("program-conditions/shaders").createDirectories()
        write(
            shaders,
            "gbuffers_terrain.vsh",
            """
                #version 330 core
                //#define BLOOM
                #define SSR
                void main() { gl_Position = vec4(0.0); }
            """.trimIndent(),
        )
        write(shaders, "gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite.vsh", SIMPLE_VERTEX)
        write(shaders, "composite.fsh", SIMPLE_FRAGMENT)
        write(shaders, "composite2.vsh", SIMPLE_VERTEX)
        write(shaders, "final.vsh", SIMPLE_VERTEX)
        write(shaders, "final.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                program.composite.enabled=!BLOOM && (SSR || false)
                program.composite2.enabled=false
            """.trimIndent(),
        )
        val pack = temporary.resolve("program-conditions")

        val defaultPlan = IrisShaderPackPlanner.plan(pack)
        val bloomPlan = IrisShaderPackPlanner.plan(pack, mapOf("BLOOM" to "true", "SSR" to "false"))

        assertTrue(defaultPlan.programs.any { it.name == "composite" })
        assertTrue(defaultPlan.programs.none { it.name == "composite2" })
        assertTrue(bloomPlan.programs.none { it.name == "composite" })
        assertTrue(bloomPlan.programs.none { it.name == "composite2" })
        assertTrue(bloomPlan.programs.any { it.name == "final" })
    }

    @Test
    fun `active defensive program error disables only that optional program`() {
        val shaders = temporary.resolve("defensive-program-error/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(
            shaders,
            "prepare.vsh",
            """
                #version 330 core
                //#define CLOUD_SHADOWS
                void main() { gl_Position = vec4(0.0); }
                #ifndef CLOUD_SHADOWS
                #error "This program should be disabled if Cloud Shadows are disabled"
                #endif
            """.trimIndent(),
        )
        write(shaders, "prepare.fsh", SIMPLE_FRAGMENT)
        val pack = temporary.resolve("defensive-program-error")

        val disabled = IrisShaderPackPlanner.plan(pack)
        val enabled = IrisShaderPackPlanner.plan(pack, mapOf("CLOUD_SHADOWS" to "true"))

        assertTrue(disabled.programs.none { it.name == "prepare" })
        assertTrue(enabled.programs.any { it.name == "prepare" })
    }

    @Test
    fun `dimension qualified program conditions do not affect other program sets`() {
        val shaders = temporary.resolve("dimension-conditions/shaders").createDirectories()
        writeProgramSet(shaders, "world0", "OVERWORLD")
        writeProgramSet(shaders, "world-1", "NETHER")
        write(
            shaders,
            "shaders.properties",
            """
                program.world0/final.enabled=false
                program.world-1/final.enabled=true
            """.trimIndent(),
        )

        assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(
                temporary.resolve("dimension-conditions"),
                dimension = ResourceLocation.of("minecraft:overworld"),
            )
        }
        val nether = IrisShaderPackPlanner.plan(
            temporary.resolve("dimension-conditions"),
            dimension = ResourceLocation.of("minecraft:the_nether"),
        )
        assertTrue(nether.programs.any { it.name == "final" })
    }

    @Test
    fun `invalid program conditions reject before publication`() {
        val shaders = temporary.resolve("invalid-condition/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(
            shaders,
            "shaders.properties",
            "program.final.enabled=UNKNOWN_OPTION || true",
        )

        val failure = assertThrows<IllegalArgumentException> {
            IrisShaderPackPlanner.plan(temporary.resolve("invalid-condition"))
        }
        assertContains(failure.message.orEmpty(), "UNKNOWN_OPTION")
    }

    @Test
    fun `particle ordering honors current and legacy properties with Iris defaults`() {
        val shaders = temporary.resolve("particle-ordering/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        val pack = temporary.resolve("particle-ordering")

        assertEquals(IrisParticleOrdering.MIXED, IrisShaderPackPlanner.plan(pack).particlesOrdering)

        write(shaders, "deferred.vsh", SIMPLE_VERTEX)
        write(shaders, "deferred.fsh", SIMPLE_FRAGMENT)
        assertEquals(IrisParticleOrdering.AFTER, IrisShaderPackPlanner.plan(pack).particlesOrdering)

        write(shaders, "shaders.properties", "particles.before.deferred=true")
        assertEquals(IrisParticleOrdering.BEFORE, IrisShaderPackPlanner.plan(pack).particlesOrdering)

        write(
            shaders,
            "shaders.properties",
            """
                particles.before.deferred=true
                particles.ordering=mixed
            """.trimIndent(),
        )
        assertEquals(IrisParticleOrdering.MIXED, IrisShaderPackPlanner.plan(pack).particlesOrdering)
    }

    @Test
    fun `separate entity draws is typed and defaults off`() {
        val shaders = temporary.resolve("separate-entities/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        val pack = temporary.resolve("separate-entities")

        assertEquals(false, IrisShaderPackPlanner.plan(pack).separateEntityDraws)

        write(shaders, "shaders.properties", "separateEntityDraws=true")
        assertEquals(true, IrisShaderPackPlanner.plan(pack).separateEntityDraws)

        write(shaders, "shaders.properties", "separateEntityDraws=sometimes")
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.plan(pack) }.message.orEmpty(),
            "separateEntityDraws must be true or false",
        )
    }

    @Test
    fun `old hand light is typed and retains the Iris compatibility default`() {
        val shaders = temporary.resolve("old-hand-light/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        val pack = temporary.resolve("old-hand-light")

        assertEquals(true, IrisShaderPackPlanner.plan(pack).oldHandLight)

        write(shaders, "shaders.properties", "oldHandLight=false")
        assertEquals(false, IrisShaderPackPlanner.plan(pack).oldHandLight)

        write(shaders, "shaders.properties", "oldHandLight=sometimes")
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.plan(pack) }.message.orEmpty(),
            "oldHandLight must be true or false",
        )
    }

    @Test
    fun `underwater overlay is typed and retains the Iris compatibility default`() {
        val shaders = temporary.resolve("underwater-overlay/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        val pack = temporary.resolve("underwater-overlay")

        assertEquals(true, IrisShaderPackPlanner.plan(pack).underwaterOverlay)

        write(shaders, "shaders.properties", "underwaterOverlay=false")
        assertEquals(false, IrisShaderPackPlanner.plan(pack).underwaterOverlay)

        write(shaders, "shaders.properties", "underwaterOverlay=sometimes")
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.plan(pack) }.message.orEmpty(),
            "underwaterOverlay must be true or false",
        )
    }

    @Test
    fun `skip all rendering is typed and defaults off`() {
        val shaders = temporary.resolve("skip-all-rendering/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        val pack = temporary.resolve("skip-all-rendering")

        assertEquals(false, IrisShaderPackPlanner.plan(pack).skipAllRendering)

        write(shaders, "shaders.properties", "skipAllRendering=true")
        assertEquals(true, IrisShaderPackPlanner.plan(pack).skipAllRendering)

        write(shaders, "shaders.properties", "skipAllRendering=sometimes")
        assertContains(
            assertThrows<IllegalArgumentException> { IrisShaderPackPlanner.plan(pack) }.message.orEmpty(),
            "skipAllRendering must be true or false",
        )
    }

    @Test
    fun `clouds off retains the scene route as an exact no-op`() {
        val shaders = temporary.resolve("clouds-off/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(shaders, "gbuffers_clouds.vsh", SIMPLE_VERTEX)
        write(shaders, "gbuffers_clouds.fsh", SIMPLE_FRAGMENT)
        write(shaders, "shaders.properties", "clouds=off")

        val cloud = IrisShaderPackPlanner.plan(temporary.resolve("clouds-off"))
            .programs.single { it.name == "gbuffers_clouds" }

        assertContains(cloud.vertex, "minosoft:scene_bridge CLOUD CLOUD")
        assertContains(cloud.fragment, "discard;")
    }

    @Test
    fun `shadow routing directives retain exact terrain entity and block entity fallbacks`() {
        val shaders = temporary.resolve("shadow-routing/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(
            shaders,
            "shadow.vsh",
            """
                $SIMPLE_VERTEX
                const int shadowMapResolution = 2048;
                const float shadowDistance = 192.0;
                const float shadowNearPlane = 0.25;
                const float shadowFarPlane = 384.0;
                const float shadowMapFov = 75.0;
                const float shadowIntervalSize = 4.0;
                const float shadowDistanceRenderMul = 1.0;
                const float entityShadowDistanceMul = 0.125;
                const float voxelDistance = 12.0;
            """.trimIndent(),
        )
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shaders.properties",
            """
                shadowTerrain=false
                shadowTranslucent=false
                shadowEntities=false
                shadowPlayer=false
                shadowBlockEntities=false
                shadow.culling=true
            """.trimIndent(),
        )
        val pack = temporary.resolve("shadow-routing")

        val plan = IrisShaderPackPlanner.plan(pack)
        assertEquals(
            IrisShadowDirectives(
                enabled = true,
                terrain = false,
                translucentTerrain = false,
                entities = false,
                player = false,
                blockEntities = false,
                lightBlockEntities = false,
                distance = 192.0f,
                nearPlane = 0.25f,
                farPlane = 384.0f,
                mapFov = 75.0f,
                intervalSize = 4.0f,
                distanceRenderMultiplier = 1.0f,
                entityShadowDistanceMultiplier = 0.125f,
                voxelDistance = 12.0f,
                cullingMode = IrisShadowCullingMode.ADVANCED,
            ),
            plan.shadowDirectives,
        )
        assertEquals(192.0f, plan.shadowDirectives.terrainDistanceLimit)
        assertEquals(24.0f, plan.shadowDirectives.entityDistanceLimit)
        assertEquals(true, plan.shadowDirectives.allowsTerrainSection(200, 0, 0))
        assertEquals(false, plan.shadowDirectives.allowsTerrainSection(201, 0, 0))
        assertEquals(
            true,
            plan.shadowDirectives.allowsEntityBounds(
                cameraX = 0.0,
                cameraY = 64.0,
                cameraZ = 0.0,
                minX = 23.9,
                minY = 63.0,
                minZ = -0.5,
                maxX = 24.9,
                maxY = 65.0,
                maxZ = 0.5,
            ),
        )
        assertEquals(
            false,
            plan.shadowDirectives.allowsEntityBounds(
                cameraX = 0.0,
                cameraY = 64.0,
                cameraZ = 0.0,
                minX = 24.1,
                minY = 63.0,
                minZ = -0.5,
                maxX = 25.1,
                maxY = 65.0,
                maxZ = 0.5,
            ),
        )
        assertEquals(
            RenderTargetSize.Fixed(2048, 2048),
            plan.resources.targets.single { it.id.value == "iris:shadow" }.size,
        )
        assertTrue(IrisShaderPackPlanner.SHADOW_VIEW in plan.views)

        write(
            shaders,
            "shaders.properties",
            """
                shadowEntities=false
                shadowPlayer=true
            """.trimIndent(),
        )
        val playerOnly = IrisShaderPackPlanner.plan(pack).shadowDirectives
        assertEquals(false, playerOnly.entities)
        assertEquals(true, playerOnly.player)
        assertEquals(false, playerOnly.allowsEntity(isPlayer = false))
        assertEquals(true, playerOnly.allowsEntity(isPlayer = true))

        write(
            shaders,
            "shaders.properties",
            """
                shadowEntities=true
                shadowPlayer=false
            """.trimIndent(),
        )
        val entitiesWithoutPlayer = IrisShaderPackPlanner.plan(pack).shadowDirectives
        assertEquals(true, entitiesWithoutPlayer.allowsEntity(isPlayer = false))
        assertEquals(true, entitiesWithoutPlayer.allowsEntity(isPlayer = true))

        write(
            shaders,
            "shaders.properties",
            """
                shadowBlockEntities=false
                shadowLightBlockEntities=true
            """.trimIndent(),
        )
        val lightBlockEntities = IrisShaderPackPlanner.plan(pack).shadowDirectives
        assertEquals(false, lightBlockEntities.blockEntities)
        assertEquals(true, lightBlockEntities.lightBlockEntities)
        assertEquals(false, lightBlockEntities.allowsBlockEntity(luminance = 0))
        assertEquals(true, lightBlockEntities.allowsBlockEntity(luminance = 1))

        write(shaders, "shaders.properties", "shadowTranslucent=true")
        assertEquals(true, IrisShaderPackPlanner.plan(pack).shadowDirectives.translucentTerrain)

        write(shaders, "shaders.properties", "")
        assertEquals(true, IrisShaderPackPlanner.plan(pack).shadowDirectives.translucentTerrain)

        write(shaders, "shaders.properties", "shadow.culling=reversed")
        assertEquals(
            IrisShadowCullingMode.REVERSED,
            IrisShaderPackPlanner.plan(pack).shadowDirectives.cullingMode,
        )
        write(shaders, "shaders.properties", "shadow.culling=false")
        assertEquals(
            IrisShadowCullingMode.DISTANCE,
            IrisShaderPackPlanner.plan(pack).shadowDirectives.cullingMode,
        )
        // Pinned Iris diagnoses an unknown value and retains DEFAULT.
        write(shaders, "shaders.properties", "shadow.culling=unexpected")
        assertEquals(
            IrisShadowCullingMode.DEFAULT,
            IrisShaderPackPlanner.plan(pack).shadowDirectives.cullingMode,
        )
    }

    @Test
    fun `legacy shadow comment aliases survive preprocessing and const directives override them`() {
        val shaders = temporary.resolve("legacy-shadow-directives/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(
            shaders,
            "shadow.vsh",
            """
                /* SHADOWRES:1536 */
                /* SHADOWHPL:96.0 */
                /* SHADOWFOV:68.0 */
                $SIMPLE_VERTEX
            """.trimIndent(),
        )
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        val pack = temporary.resolve("legacy-shadow-directives")

        val legacy = IrisShaderPackPlanner.plan(pack)
        assertEquals(96.0f, legacy.shadowDirectives.distance)
        assertEquals(68.0f, legacy.shadowDirectives.mapFov)
        assertEquals(
            RenderTargetSize.Fixed(1536, 1536),
            legacy.resources.targets.single { it.id.value == "iris:shadow" }.size,
        )

        write(
            shaders,
            "shadow.vsh",
            """
                /* SHADOWRES:1536 */
                /* SHADOWHPL:96.0 */
                /* SHADOWFOV:68.0 */
                $SIMPLE_VERTEX
                const int shadowMapResolution = 2048;
                const float shadowDistance = 192.0;
                const float shadowMapFov = 75.0;
            """.trimIndent(),
        )

        val current = IrisShaderPackPlanner.plan(pack)
        assertEquals(192.0f, current.shadowDirectives.distance)
        assertEquals(75.0f, current.shadowDirectives.mapFov)
        assertEquals(
            RenderTargetSize.Fixed(2048, 2048),
            current.resources.targets.single { it.id.value == "iris:shadow" }.size,
        )
    }

    @Test
    fun `primary shadow geometry selects pinned Iris voxel distance culling`() {
        val shaders = temporary.resolve("shadow-voxelization/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(shaders, "shadow.vsh", SIMPLE_VERTEX)
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(
            shaders,
            "shadow.gsh",
            """
                #version 330 core
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                void main() {
                    for (int vertex = 0; vertex < 3; vertex++) {
                        gl_Position = gl_in[vertex].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
            """.trimIndent(),
        )
        val pack = temporary.resolve("shadow-voxelization")

        val voxel = IrisShaderPackPlanner.plan(pack).shadowDirectives
        assertEquals(IrisShadowCullingMode.DEFAULT, voxel.cullingMode)
        assertTrue(voxel.voxelizationDetected)

        Files.delete(shaders.resolve("shadow.gsh"))
        assertFalse(IrisShaderPackPlanner.plan(pack).shadowDirectives.voxelizationDetected)
    }

    @Test
    fun `shadow enabled false removes only the auxiliary view`() {
        val shaders = temporary.resolve("shadow-disabled/shaders").createDirectories()
        writeProgramSet(shaders, null, "BASE")
        write(shaders, "shadow.vsh", SIMPLE_VERTEX)
        write(shaders, "shadow.fsh", SIMPLE_FRAGMENT)
        write(shaders, "shaders.properties", "shadow.enabled=false")

        val plan = IrisShaderPackPlanner.plan(temporary.resolve("shadow-disabled"))

        assertEquals(false, plan.shadowDirectives.enabled)
        assertEquals(setOf(RenderViewId.MAIN), plan.views)
        assertTrue(plan.resources.targets.none { it.id.value == "iris:shadow" })
        assertTrue(plan.programs.any { it.phase == ShaderProgramPhase.SHADOW })
    }

    private fun write(root: Path, relative: String, value: String) {
        val target = root.resolve(relative)
        target.parent.createDirectories()
        Files.writeString(target, value)
    }

    private fun writeBytes(root: Path, relative: String, value: ByteArray) {
        val target = root.resolve(relative)
        target.parent.createDirectories()
        Files.write(target, value)
    }

    private fun writeProgramSet(root: Path, directory: String?, marker: String) {
        val prefix = directory?.let { "$it/" }.orEmpty()
        write(root, "${prefix}gbuffers_terrain.vsh", "$SIMPLE_VERTEX\n// $marker")
        write(root, "${prefix}gbuffers_terrain.fsh", SIMPLE_FRAGMENT)
        write(root, "${prefix}final.vsh", SIMPLE_VERTEX)
        write(root, "${prefix}final.fsh", SIMPLE_FRAGMENT)
    }

    private companion object {
        val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZnjkAAAAASUVORK5CYII=",
        )
        const val SIMPLE_VERTEX = "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }"
        const val SIMPLE_FRAGMENT = "#version 330 core\nout vec4 color;\nvoid main() { color = vec4(1.0); }"
    }
}
