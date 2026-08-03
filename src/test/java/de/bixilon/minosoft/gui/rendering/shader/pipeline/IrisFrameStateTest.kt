/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec3.i.Vec3i
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.registries.biomes.Biome
import de.bixilon.minosoft.data.registries.biomes.BiomePrecipitation
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.buffer.uniform.UniformBuffer
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrisFrameStateTest {
    @Test
    fun `fullscreen player model view excludes rebased camera translation`() {
        val host = MMat4f(1.0f).apply {
            this[0, 0] = 0.25f
            this[0, 1] = -0.75f
            this[1, 0] = 0.5f
            this[2, 2] = -1.0f
            this[0, 3] = 32.0f
            this[1, 3] = -79.0f
            this[2, 3] = 96.0f
        }.unsafe

        val player = irisPlayerModelView(host)

        assertEquals(0.25f, player[0, 0])
        assertEquals(-0.75f, player[0, 1])
        assertEquals(0.5f, player[1, 0])
        assertEquals(-1.0f, player[2, 2])
        assertEquals(0.0f, player[0, 3])
        assertEquals(0.0f, player[1, 3])
        assertEquals(0.0f, player[2, 3])
        assertEquals(1.0f, player[3, 3])
    }

    @Test
    fun `distant projection retains host field of view with an independent far plane`() {
        val host = de.bixilon.minosoft.gui.rendering.camera.CameraUtil.perspective(
            fovY = 1.1f,
            aspect = 16.0f / 9.0f,
            near = 0.05f,
            far = 512.0f,
        )
        val distant = IrisDistantFrameState.create(
            hostProjection = host,
            near = 0.05f,
            renderDistance = 2_048,
        )

        assertTrue(kotlin.math.abs(host[0, 0] - distant.projection[0, 0]) < 1.0e-6f)
        assertTrue(kotlin.math.abs(host[1, 1] - distant.projection[1, 1]) < 1.0e-6f)
        assertEquals(0.05f, distant.near)
        assertEquals(2_048, distant.renderDistance)
        assertTrue(distant.far > distant.renderDistance)
        assertEquals(distant.projection, distant.previousProjection)
    }

    @Test
    fun `shadow projection follows retained pack distance planes and fov`() {
        val orthographic = irisShadowProjection(
            IrisShadowDirectives(
                distance = 192.0f,
                nearPlane = 0.25f,
                farPlane = 384.0f,
            ),
        )
        assertEquals(1.0f / 192.0f, orthographic[0, 0])
        assertEquals(-2.0f / (384.0f - 0.25f), orthographic[2, 2])

        val perspective = irisShadowProjection(IrisShadowDirectives(mapFov = 90.0f))
        assertEquals(1.0f, perspective[0, 0])
        assertEquals(-1.0f, perspective[3, 2])
    }

    @Test
    fun `shadow camera snaps to the pinned interval cell center`() {
        assertEquals(
            Vec3d(11.0, 65.0, -1.0),
            irisSnappedShadowCamera(Vec3d(10.25, 64.75, -2.25), 2.0f),
        )
        assertEquals(
            Vec3d(10.25, 64.75, -2.25),
            irisSnappedShadowCamera(Vec3d(10.25, 64.75, -2.25), 0.0f),
        )
    }

    @Test
    fun `shadow model view matches pinned Iris dawn baseline and interval`() {
        val camera = Vec3d(0.646045982837677, 82.53274536132812, -514.0264282226562)
        val actual = irisShadowModelView(
            shadowAngle = 0.03451777f,
            sunPathRotation = 0.0f,
            intervalSize = 2.0f,
            cameraWorld = camera,
            renderOrigin = camera,
        )
        // The pinned Iris transform sequence evaluated through Minosoft's
        // matrix implementation. This remains within Iris's own 5e-4 fixture
        // tolerance while keeping our regression threshold substantially tighter.
        val expected = Mat4f(
            0.21517062f, -0.97657645f, 0.0f, 0.38014936f,
            0.0f, 0.0f, -1.0f, 1.0264282f,
            0.97657645f, 0.21517062f, 0.0f, -100.446205f,
            0.0f, 0.0f, 0.0f, 1.0f,
        )
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                assertTrue(
                    kotlin.math.abs(expected[row, column] - actual[row, column]) < 1.0e-4f,
                    "[$row,$column] expected=${expected[row, column]} actual=${actual[row, column]}",
                )
            }
        }
    }

    @Test
    fun `old hand light exposes the brighter offhand light without changing item identity`() {
        val main = IrisHeldItemFrameState(minecraft("torch"), 7, Vec3f(1.0f, 0.5f, 0.25f))
        val off = IrisHeldItemFrameState(minecraft("soul_torch"), 10, Vec3f(0.25f, 0.5f, 1.0f))
        val held = IrisHeldItemsFrameState(main, off)

        assertEquals(off, held.mainLight(oldHandLight = true))
        assertEquals(main, held.mainLight(oldHandLight = false))
        assertEquals(main.identifier, held.main.identifier)
        assertEquals(off.identifier, held.off.identifier)
    }

    @Test
    fun `frame matrices are copied and inverted`() {
        val matrix = Mat4f(
            2.0f, 0.0f, 0.0f, 4.0f,
            0.0f, 3.0f, 0.0f, -6.0f,
            0.0f, 0.0f, 4.0f, 8.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        )

        val product = matrix * matrix.inverse()

        for (row in 0 until 4) {
            for (column in 0 until 4) {
                val expected = if (row == column) 1.0f else 0.0f
                assertTrue(kotlin.math.abs(product[row, column] - expected) < 1.0e-5f)
            }
        }
        assertEquals(matrix, matrix.copyImmutable())
    }

    @Test
    fun `only declared standard frame uniforms upload`() {
        val native = RecordingNativeShader(missingUniforms = setOf("fogEnd"))
        val state = IrisFrameState(
            frameCounter = 7,
            frameTime = 0.05f,
            frameTimeCounter = 12.5f,
            frameTimeSmooth = 0.04f,
            viewWidth = 1920.0f,
            viewHeight = 1080.0f,
            near = 0.05f,
            far = 512.0f,
            modelViewMatrix = Mat4f(),
            modelViewMatrixInverse = Mat4f(),
            previousModelViewMatrix = Mat4f(2.0f),
            projectionMatrix = Mat4f(),
            projectionMatrixInverse = Mat4f(),
            previousProjectionMatrix = Mat4f(3.0f),
            shadowModelView = Mat4f(),
            shadowModelViewInverse = Mat4f(),
            shadowProjection = Mat4f(),
            shadowProjectionInverse = Mat4f(),
            cameraPosition = Vec3d(1.0, 2.0, 3.0),
            previousCameraPosition = Vec3d(0.0, 1.0, 2.0),
            worldTime = 6000,
            worldDay = 4,
            currentDate = Vec3i(2026, 7, 27),
            currentTime = Vec3i(5, 10, 15),
            currentYearTime = Vec2i(17_980_215, 13_555_785),
            playerState = IrisPlayerFrameState.EMPTY,
            eyeBrightness = Vec2i(48, 240),
            skyColor = Vec3f(0.25f, 0.5f, 0.75f),
            hideGui = false,
            rainStrength = 0.25f,
            thunderStrength = 0.5f,
            eyeAltitude = 65.0f,
            sunAngle = 0.25f,
            moonPhase = 2,
            screenBrightness = 0.75f,
            fogStart = 24.0f,
            fogEnd = 96.0f,
            fogColor = Vec4f(0.1f, 0.2f, 0.3f, 0.4f),
            shadowMapResolution = 1024,
        )

        val uploads = state.uploadTo(
            native,
            setOf(
                "frameCounter",
                "frameTime",
                "frameTimeSmooth",
                "gbufferProjection",
                "gbufferPreviousModelView",
                "gbufferPreviousProjection",
                "shadowProjection",
                "shadowMapResolution",
                "cameraPosition",
                "fogMode",
                "fogStart",
                "fogEnd",
                "fogColor",
                "iris_FogColor",
                "notSupported",
            ),
        )

        assertEquals(13, uploads)
        assertEquals(7, native.ints["frameCounter"])
        assertEquals(0.05f, native.floats["frameTime"])
        assertEquals(0.04f, native.floats["frameTimeSmooth"])
        assertEquals(Mat4f(), native.matrices["gbufferProjection"])
        assertEquals(Mat4f(2.0f), native.matrices["gbufferPreviousModelView"])
        assertEquals(Mat4f(3.0f), native.matrices["gbufferPreviousProjection"])
        assertEquals(Mat4f(), native.matrices["shadowProjection"])
        assertEquals(1024, native.ints["shadowMapResolution"])
        assertEquals(Vec3f(1.0f, 2.0f, 3.0f), native.vectors["cameraPosition"])
        assertEquals(0x0801, native.ints["fogMode"])
        assertEquals(24.0f, native.floats["fogStart"])
        assertEquals(null, native.floats["fogEnd"])
        assertEquals(Vec3f(0.1f, 0.2f, 0.3f), native.vectors["fogColor"])
        assertEquals(Vec4f(0.1f, 0.2f, 0.3f, 0.4f), native.vectors4["iris_FogColor"])
    }

    @Test
    fun `camera position precision split matches Iris floor semantics`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d(30_000_000.25, -12.25, -30_000_000.875),
            previousCameraPosition = Vec3d(-1.5, 64.125, 2.75),
        )

        val uploads = state.uploadTo(
            native,
            setOf(
                "cameraPositionInt",
                "cameraPositionFract",
                "previousCameraPositionInt",
                "previousCameraPositionFract",
            ),
        )

        assertEquals(4, uploads)
        assertEquals(Vec3i(30_000_000, -13, -30_000_001), native.integerVectors["cameraPositionInt"])
        assertEquals(Vec3f(0.25f, 0.75f, 0.125f), native.vectors["cameraPositionFract"])
        assertEquals(Vec3i(-2, 64, 2), native.integerVectors["previousCameraPositionInt"])
        assertEquals(Vec3f(0.5f, 0.125f, 0.75f), native.vectors["previousCameraPositionFract"])
    }

    @Test
    fun `calendar uniforms mirror pinned Iris local time vectors`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
            currentDate = Vec3i(2026, 7, 27),
            currentTime = Vec3i(5, 10, 15),
            currentYearTime = Vec2i(17_980_215, 13_555_785),
        )

        val uploads = state.uploadTo(
            native,
            setOf("currentDate", "currentTime", "currentYearTime"),
        )

        assertEquals(3, uploads)
        assertEquals(Vec3i(2026, 7, 27), native.integerVectors["currentDate"])
        assertEquals(Vec3i(5, 10, 15), native.integerVectors["currentTime"])
        assertEquals(Vec2i(17_980_215, 13_555_785), native.integerVectors2["currentYearTime"])
    }

    @Test
    fun `eye brightness uses pinned block sky ordering and lightmap scale`() {
        assertEquals(Vec2i(80, 224), LightLevel(block = 5, sky = 14).irisEyeBrightness())
    }

    @Test
    fun `biome state uses pinned Iris ids ordered category tags and precipitation ABI`() {
        val cherry = Biome(
            identifier = minecraft("cherry_grove"),
            temperature = 0.5f,
            downfall = 0.8f,
            precipitation = BiomePrecipitation.RAIN,
        )
        val cherryState = IrisBiomeFrameState.of(
            biome = cherry,
            position = BlockPosition(0, 64, 0),
            tags = setOf(minecraft("is_mountain"), minecraft("is_forest")),
        )

        assertEquals(30, cherryState.id)
        assertEquals(10, cherryState.category)
        assertEquals(1, cherryState.precipitation)
        assertEquals(0.8f, cherryState.rainfall)
        assertEquals(0.5f, cherryState.temperature)

        val moddedCold = IrisBiomeFrameState.of(
            biome = Biome(
                identifier = minecraft("unknown_test_biome"),
                temperature = -1.0f,
                downfall = 0.25f,
                precipitation = BiomePrecipitation.RAIN,
            ),
            position = BlockPosition(0, 64, 0),
            tags = emptySet(),
        )
        assertEquals(0, moddedCold.id)
        assertEquals(5, moddedCold.category)
        assertEquals(2, moddedCold.precipitation)
    }

    @Test
    fun `biome uniforms upload the immutable player position snapshot`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(
            biomeState = IrisBiomeFrameState(
                id = 30,
                category = 10,
                precipitation = 1,
                rainfall = 0.8f,
                temperature = 0.5f,
            ),
        )

        assertEquals(
            5,
            state.uploadTo(
                native,
                setOf("biome", "biome_category", "biome_precipitation", "rainfall", "temperature"),
            ),
        )
        assertEquals(30, native.ints["biome"])
        assertEquals(10, native.ints["biome_category"])
        assertEquals(1, native.ints["biome_precipitation"])
        assertEquals(0.8f, native.floats["rainfall"])
        assertEquals(0.5f, native.floats["temperature"])
    }

    @Test
    fun `mood uniforms upload the immutable tick snapshot`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(
            playerMood = 0.25f,
            constantMood = 0.75f,
        )

        assertEquals(2, state.uploadTo(native, setOf("playerMood", "constantMood")))
        assertEquals(0.25f, native.floats["playerMood"])
        assertEquals(0.75f, native.floats["constantMood"])
    }

    @Test
    fun `player state uniforms use pinned camera medium and boolean ABI`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
            playerState = IrisPlayerFrameState(
                eyeMedium = IrisPlayerFrameState.LAVA_EYE_MEDIUM,
                sneaking = true,
                sprinting = false,
                hurt = true,
                invisible = false,
                burning = true,
                onGround = false,
                rightHanded = false,
                currentHealth = 0.5f,
                maxHealth = 20.0f,
                currentHunger = 0.75f,
                currentArmor = 0.2f,
                currentAir = 0.9f,
                maxAir = 300.0f,
                firstPersonCamera = false,
                spectator = true,
            ),
            eyeBrightness = Vec2i(80, 224),
            skyColor = Vec3f(0.25f, 0.5f, 0.75f),
            hideGui = true,
        )

        val uploads = state.uploadTo(
            native,
            setOf(
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
                "eyeBrightness",
                "skyColor",
                "pi",
            ),
        )

        assertEquals(22, uploads)
        assertEquals(IrisPlayerFrameState.LAVA_EYE_MEDIUM, native.ints["isEyeInWater"])
        assertEquals(true, native.booleans["is_sneaking"])
        assertEquals(false, native.booleans["is_sprinting"])
        assertEquals(true, native.booleans["is_hurt"])
        assertEquals(false, native.booleans["is_invisible"])
        assertEquals(true, native.booleans["is_burning"])
        assertEquals(false, native.booleans["is_on_ground"])
        assertEquals(true, native.booleans["hideGUI"])
        assertEquals(false, native.booleans["isRightHanded"])
        assertEquals(0.5f, native.floats["currentPlayerHealth"])
        assertEquals(20.0f, native.floats["maxPlayerHealth"])
        assertEquals(0.75f, native.floats["currentPlayerHunger"])
        assertEquals(20.0f, native.floats["maxPlayerHunger"])
        assertEquals(0.2f, native.floats["currentPlayerArmor"])
        assertEquals(50.0f, native.floats["maxPlayerArmor"])
        assertEquals(0.9f, native.floats["currentPlayerAir"])
        assertEquals(300.0f, native.floats["maxPlayerAir"])
        assertEquals(false, native.booleans["firstPersonCamera"])
        assertEquals(true, native.booleans["isSpectator"])
        assertEquals(Vec2i(80, 224), native.integerVectors2["eyeBrightness"])
        assertEquals(Vec3f(0.25f, 0.5f, 0.75f), native.vectors["skyColor"])
        assertEquals(Math.PI.toFloat(), native.floats["pi"])
    }

    @Test
    fun `vision uniforms upload the immutable gameplay effect snapshot`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(
            visionState = IrisVisionFrameState(
                blindness = 0.25f,
                darknessFactor = 0.5f,
                darknessLightFactor = 0.125f,
                nightVision = 0.75f,
            ),
        )

        assertEquals(
            4,
            state.uploadTo(
                native,
                setOf("blindness", "darknessFactor", "darknessLightFactor", "nightVision"),
            ),
        )
        assertEquals(0.25f, native.floats["blindness"])
        assertEquals(0.5f, native.floats["darknessFactor"])
        assertEquals(0.125f, native.floats["darknessLightFactor"])
        assertEquals(0.75f, native.floats["nightVision"])
    }

    @Test
    fun `smoothed inputs retain the Iris integer and float upload ABI`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(
            eyeBrightnessSmooth = Vec2i(79, 223),
            wetness = 0.625f,
        )

        assertEquals(2, state.uploadTo(native, setOf("eyeBrightnessSmooth", "wetness")))
        assertEquals(Vec2i(79, 223), native.integerVectors2["eyeBrightnessSmooth"])
        assertEquals(0.625f, native.floats["wetness"])
    }

    @Test
    fun `lightning position exposes the Iris vec4 uniform and expression components`() {
        val position = Vec4f(3.0f, 4.0f, 5.0f, 1.0f)
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(lightningBoltPosition = position)
        val native = RecordingNativeShader()

        assertEquals(1, state.uploadTo(native, setOf("lightningBoltPosition")))
        assertEquals(position, native.vectors4["lightningBoltPosition"])
        val variables = state.customExpressionVariables(0.0f)
        assertEquals(3.0, variables["lightningBoltPosition.x"])
        assertEquals(1.0, variables["lightningBoltPosition.w"])
        assertEquals(1.0, variables["lightningBoltPosition.a"])
    }

    @Test
    fun `pinned Iris world information and player vectors upload from one frame snapshot`() {
        val look = Vec3f(0.25f, -0.5f, 0.75f)
        val body = Vec3f(-0.75f, 0.0f, 0.25f)
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(
            playerLookVector = look,
            playerBodyVector = body,
            worldInfo = IrisWorldInfoFrameState(
                bedrockLevel = -64,
                cloudHeight = 192.0f,
                heightLimit = 384,
                logicalHeightLimit = 256,
                hasCeiling = true,
                hasSkylight = false,
                ambientLight = 0.1f,
            ),
        )
        val native = RecordingNativeShader()
        val uniforms = setOf(
            "playerLookVector",
            "playerBodyVector",
            "bedrockLevel",
            "cloudHeight",
            "heightLimit",
            "logicalHeightLimit",
            "hasCeiling",
            "hasSkylight",
            "ambientLight",
        )

        assertEquals(9, state.uploadTo(native, uniforms))
        assertEquals(look, native.vectors["playerLookVector"])
        assertEquals(body, native.vectors["playerBodyVector"])
        assertEquals(-64, native.ints["bedrockLevel"])
        assertEquals(192.0f, native.floats["cloudHeight"])
        assertEquals(384, native.ints["heightLimit"])
        assertEquals(256, native.ints["logicalHeightLimit"])
        assertEquals(true, native.booleans["hasCeiling"])
        assertEquals(false, native.booleans["hasSkylight"])
        assertEquals(0.1f, native.floats["ambientLight"])

        val variables = state.customExpressionVariables(0.0f)
        assertEquals(0.25, variables["playerLookVector.x"])
        assertEquals(0.25, variables["playerBodyVector.z"])
        assertEquals(192.0, variables["cloudHeight"])
        assertEquals(0.0, variables["hasSkylight"])
    }

    @Test
    fun `selected block cloud clock and output color space use the pinned Iris ABI`() {
        val selectedPosition = Vec3f(4.5f, -2.5f, 8.5f)
        val state = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(
            cloudTime = 12.75f,
            selectedBlock = IrisSelectedBlockFrameState(
                state = null,
                id = 812,
                position = selectedPosition,
            ),
        )
        val native = RecordingNativeShader()

        assertEquals(
            4,
            state.uploadTo(
                native,
                setOf(
                    "cloudTime",
                    "currentColorSpace",
                    "currentSelectedBlockId",
                    "currentSelectedBlockPos",
                ),
            ),
        )
        assertEquals(12.75f, native.floats["cloudTime"])
        assertEquals(0, native.ints["currentColorSpace"])
        assertEquals(812, native.ints["currentSelectedBlockId"])
        assertEquals(selectedPosition, native.vectors["currentSelectedBlockPos"])

        val variables = state.customExpressionVariables(0.0f)
        assertEquals(12.75, variables["cloudTime"])
        assertEquals(0.0, variables["currentColorSpace"])
        assertEquals(812.0, variables["currentSelectedBlockId"])
        assertEquals(8.5, variables["currentSelectedBlockPos.z"])
    }

    @Test
    fun `custom uniforms resolve Iris gameplay vectors biome constants and numbered smoothing`() {
        val native = RecordingNativeShader()
        val state = frameState(
            cameraPosition = Vec3d(1.0, 2.0, 3.0),
            previousCameraPosition = Vec3d.EMPTY,
        ).copy(
            visionState = IrisVisionFrameState(
                blindness = 0.25f,
                darknessFactor = 0.5f,
                darknessLightFactor = 0.125f,
                nightVision = 0.75f,
            ),
        )
        val evaluator = IrisCustomUniformEvaluator(
            IrisCustomUniformPlan(
                listOf(
                    IrisCustomUniformDefinition(
                        name = "compatibilityProbe",
                        type = IrisCustomUniformType.FLOAT,
                        expression = "smooth(202, max(blindness, darknessFactor) + cameraPosition.x + BIOME_NETHER_WASTES + endFlashIntensity, 6, 12)",
                        uniform = true,
                    ),
                ),
            ),
            sunPathRotation = 0.0f,
        )

        assertEquals(1, evaluator.uploadTo(native, setOf("compatibilityProbe"), state))
        assertEquals(55.5f, native.floats["compatibilityProbe"])
    }

    @Test
    fun `celestial uniforms follow Iris day night and sun path transforms`() {
        val dayNative = RecordingNativeShader()
        val day = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
            sunAngle = 0.25f,
        )
        val celestialUniforms = setOf(
            "sunPosition",
            "moonPosition",
            "shadowAngle",
            "shadowLightPosition",
            "upPosition",
        )

        assertEquals(5, day.uploadTo(dayNative, celestialUniforms, sunPathRotation = 90.0f))
        assertVec3Close(Vec3f(0.0f, 0.0f, -100.0f), dayNative.vectors.getValue("sunPosition"))
        assertVec3Close(Vec3f(0.0f, 0.0f, 100.0f), dayNative.vectors.getValue("moonPosition"))
        assertEquals(0.25f, dayNative.floats["shadowAngle"])
        assertVec3Close(
            dayNative.vectors.getValue("sunPosition"),
            dayNative.vectors.getValue("shadowLightPosition"),
        )
        assertVec3Close(Vec3f(0.0f, 100.0f, 0.0f), dayNative.vectors.getValue("upPosition"))

        val nightNative = RecordingNativeShader()
        val night = frameState(
            cameraPosition = Vec3d.EMPTY,
            previousCameraPosition = Vec3d.EMPTY,
            sunAngle = 0.75f,
        )

        assertEquals(5, night.uploadTo(nightNative, celestialUniforms))
        assertEquals(0.25f, nightNative.floats["shadowAngle"])
        assertVec3Close(
            nightNative.vectors.getValue("moonPosition"),
            nightNative.vectors.getValue("shadowLightPosition"),
        )
    }

    @Test
    fun `optimized out render stage is an optional Iris input`() {
        val native = RecordingNativeShader(missingUniforms = setOf(IrisRenderStage.UNIFORM))

        assertEquals(0, IrisRenderStage.TERRAIN_SOLID.uploadTo(native, setOf(IrisRenderStage.UNIFORM)))
        assertEquals(null, native.ints[IrisRenderStage.UNIFORM])
    }

    @Test
    fun `resolved draw state uploads exact Iris identity ABI`() {
        val native = RecordingNativeShader()
        val color = Vec4f(0.25f, 0.5f, 0.75f, 0.4f)

        val uploads = IrisResolvedDrawState(
            entityId = 7,
            blockEntityId = 41,
            currentRenderedItemId = 12,
            entityColor = color,
        ).uploadTo(native, IrisResolvedDrawState.SUPPORTED_UNIFORMS + "unrelated")

        assertEquals(5, uploads)
        assertEquals(7, native.ints["entityId"])
        assertEquals(41, native.ints["blockEntityId"])
        assertEquals(12, native.ints["currentRenderedItemId"])
        assertEquals(color, native.vectors4["entityColor"])
        assertEquals(IrisBlendFunction.EMPTY, native.integerVectors4["blendFunc"])
        assertEquals(null, native.ints["unrelated"])
    }

    @Test
    fun `optimized out draw uniform is skipped without shifting other identities`() {
        val native = RecordingNativeShader(missingUniforms = setOf("blockEntityId"))

        val uploads = IrisResolvedDrawState(
            entityId = -1,
            blockEntityId = -1,
            currentRenderedItemId = 0,
            entityColor = Vec4f.EMPTY,
        ).uploadTo(native, IrisResolvedDrawState.SUPPORTED_UNIFORMS)

        assertEquals(4, uploads)
        assertEquals(-1, native.ints["entityId"])
        assertEquals(null, native.ints["blockEntityId"])
        assertEquals(0, native.ints["currentRenderedItemId"])
    }

    @Test
    fun `texture array state uploads each retained physical size by sampler slot`() {
        val sizes = List(16) { index -> Vec2i(16 shl (index % 4), 16 shl (index % 4)) }
        val native = RecordingNativeShader(missingUniforms = setOf("uTextureSizes[7]"))

        val uploads = IrisTextureArrayState(sizes).uploadTo(
            native,
            IrisTextureArrayState.SUPPORTED_UNIFORMS,
        )

        assertEquals(15, uploads)
        assertEquals(Vec2i(16, 16), native.integerVectors2["uTextureSizes[0]"])
        assertEquals(Vec2i(128, 128), native.integerVectors2["uTextureSizes[3]"])
        assertEquals(null, native.integerVectors2["uTextureSizes[7]"])
        assertEquals("64x64", IrisTextureArrayState(sizes).diagnostics()[10])
    }

    private class RecordingNativeShader(
        private val missingUniforms: Set<String> = emptySet(),
    ) : NativeShader {
        override val loaded = true
        override val context: RenderContext get() = error("not used")
        override val defines = mutableMapOf<String, Any>()
        val ints = mutableMapOf<String, Int>()
        val booleans = mutableMapOf<String, Boolean>()
        val floats = mutableMapOf<String, Float>()
        val matrices = mutableMapOf<String, Mat4f>()
        val vectors = mutableMapOf<String, Vec3f>()
        val integerVectors2 = mutableMapOf<String, Vec2i>()
        val integerVectors = mutableMapOf<String, Vec3i>()
        val vectors4 = mutableMapOf<String, Vec4f>()
        val integerVectors4 = mutableMapOf<String, IrisBlendFunction>()

        override fun load() = Unit
        override fun unload() = Unit
        override fun reload() = Unit
        override fun hasUniform(uniform: String) = uniform !in missingUniforms
        override fun setBoolean(uniform: String, boolean: Boolean) {
            booleans[uniform] = boolean
        }
        override fun setFloat(uniform: String, value: Float) {
            floats[uniform] = value
        }

        override fun setInt(uniform: String, value: Int) {
            ints[uniform] = value
        }

        override fun setUInt(uniform: String, value: Int) = Unit
        override fun setMat4f(uniform: String, mat4: Mat4f) {
            matrices[uniform] = mat4
        }

        override fun setVec2f(uniform: String, vec2: Vec2f) = Unit
        override fun setVec2i(uniform: String, vec2: Vec2i) {
            integerVectors2[uniform] = vec2
        }

        override fun setVec3f(uniform: String, vec3: Vec3f) {
            vectors[uniform] = vec3
        }

        override fun setVec3i(uniform: String, vec3: Vec3i) {
            integerVectors[uniform] = vec3
        }

        override fun setVec4f(uniform: String, vec4: Vec4f) {
            vectors4[uniform] = vec4
        }
        override fun setVec4i(uniform: String, x: Int, y: Int, z: Int, w: Int) {
            integerVectors4[uniform] = IrisBlendFunction(x, y, z, w)
        }
        override fun setRGBColor(uniform: String, color: RGBColor) = Unit
        override fun setRGBAColor(uniform: String, color: RGBAColor) = Unit
        override fun setTexture(uniform: String, textureId: Int) = Unit
        override fun setUniformBuffer(uniform: String, buffer: UniformBuffer) = Unit
    }

    private fun frameState(
        cameraPosition: Vec3d,
        previousCameraPosition: Vec3d,
        sunAngle: Float = 0.0f,
        currentDate: Vec3i = Vec3i.EMPTY,
        currentTime: Vec3i = Vec3i.EMPTY,
        currentYearTime: Vec2i = Vec2i.EMPTY,
        playerState: IrisPlayerFrameState = IrisPlayerFrameState.EMPTY,
        eyeBrightness: Vec2i = Vec2i.EMPTY,
        skyColor: Vec3f = Vec3f.EMPTY,
        hideGui: Boolean = false,
    ) = IrisFrameState(
        frameCounter = 0,
        frameTime = 0.0f,
        frameTimeCounter = 0.0f,
        viewWidth = 1.0f,
        viewHeight = 1.0f,
        near = 0.05f,
        far = 1.0f,
        modelViewMatrix = Mat4f(),
        modelViewMatrixInverse = Mat4f(),
        previousModelViewMatrix = Mat4f(),
        projectionMatrix = Mat4f(),
        projectionMatrixInverse = Mat4f(),
        previousProjectionMatrix = Mat4f(),
        shadowModelView = Mat4f(),
        shadowModelViewInverse = Mat4f(),
        shadowProjection = Mat4f(),
        shadowProjectionInverse = Mat4f(),
        cameraPosition = cameraPosition,
        previousCameraPosition = previousCameraPosition,
        worldTime = 0,
        worldDay = 0,
        currentDate = currentDate,
        currentTime = currentTime,
        currentYearTime = currentYearTime,
        playerState = playerState,
        eyeBrightness = eyeBrightness,
        skyColor = skyColor,
        hideGui = hideGui,
        rainStrength = 0.0f,
        thunderStrength = 0.0f,
        eyeAltitude = cameraPosition.y.toFloat(),
        sunAngle = sunAngle,
        moonPhase = 0,
        screenBrightness = 0.0f,
        fogStart = 0.0f,
        fogEnd = 1.0f,
        fogColor = Vec4f.EMPTY,
    )

    private fun assertVec3Close(expected: Vec3f, actual: Vec3f) {
        assertTrue(kotlin.math.abs(expected.x - actual.x) < 1.0e-4f, "x: expected=$expected actual=$actual")
        assertTrue(kotlin.math.abs(expected.y - actual.y) < 1.0e-4f, "y: expected=$expected actual=$actual")
        assertTrue(kotlin.math.abs(expected.z - actual.z) < 1.0e-4f, "z: expected=$expected actual=$actual")
    }
}
