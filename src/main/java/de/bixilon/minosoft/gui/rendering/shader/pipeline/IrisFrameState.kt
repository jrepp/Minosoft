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
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec3.i.Vec3i
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.minosoft.data.abilities.Gamemodes
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.entities.entities.LightningBolt
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.registries.biomes.Biome
import de.bixilon.minosoft.data.registries.biomes.BiomePrecipitation
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.effects.attributes.MinecraftAttributes
import de.bixilon.minosoft.data.registries.effects.vision.VisionEffect
import de.bixilon.minosoft.data.registries.fluid.fluids.LavaFluid
import de.bixilon.minosoft.data.registries.fluid.fluids.WaterFluid
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.block.BlockItem
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.BlockPositionUtil.center
import de.bixilon.minosoft.data.world.time.WorldTime
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.camera.CameraUtil
import de.bixilon.minosoft.gui.rendering.camera.view.person.FirstPersonView
import de.bixilon.minosoft.gui.rendering.chunk.outline.BlockOutlineRenderer
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.light.VisionEffectIntensity
import de.bixilon.minosoft.gui.rendering.sky.SkyRenderer
import de.bixilon.minosoft.gui.rendering.terrain.distant.DistantTerrainRenderer
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.tags.MinecraftTagTypes.BIOME
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * Immutable, frame-pinned host state exposed to Iris-compatible programs.
 *
 * Scene renderers retain their own exact uniforms. These values are the shared
 * frame inputs that real shader packs expect independently of a host mesh ABI.
 */
data class IrisDistantFrameState(
    val near: Float,
    val far: Float,
    val renderDistance: Int,
    val projection: Mat4f,
    val projectionInverse: Mat4f,
    val previousProjection: Mat4f,
) {
    init {
        require(near.isFinite() && near > 0.0f)
        require(far.isFinite() && far > near)
        require(renderDistance > 0)
    }

    companion object {
        const val DEFAULT_RENDER_DISTANCE = 4_096
        private const val FAR_MARGIN = 512.0f

        fun host(
            near: Float,
            far: Float,
            projection: Mat4f,
            projectionInverse: Mat4f,
            previousProjection: Mat4f,
        ) = IrisDistantFrameState(
            near = near,
            far = far,
            renderDistance = far.toInt().coerceAtLeast(1),
            projection = projection,
            projectionInverse = projectionInverse,
            previousProjection = previousProjection,
        )

        fun create(
            hostProjection: Mat4f,
            near: Float,
            renderDistance: Int = DEFAULT_RENDER_DISTANCE,
            previousProjection: Mat4f? = null,
        ): IrisDistantFrameState {
            require(hostProjection[0, 0].isFinite() && hostProjection[0, 0] != 0.0f)
            require(hostProjection[1, 1].isFinite() && hostProjection[1, 1] != 0.0f)
            val far = (renderDistance + FAR_MARGIN) * sqrt(2.0f)
            val fovY = 2.0f * atan(1.0f / hostProjection[1, 1])
            val aspect = hostProjection[1, 1] / hostProjection[0, 0]
            val projection = CameraUtil.perspective(fovY, aspect, near, far)
            return IrisDistantFrameState(
                near = near,
                far = far,
                renderDistance = renderDistance,
                projection = projection,
                projectionInverse = projection.inverse(),
                previousProjection = previousProjection ?: projection,
            )
        }
    }
}

data class IrisFrameState(
    val frameCounter: Int,
    val frameTime: Float,
    val frameTimeCounter: Float,
    val frameTimeSmooth: Float = frameTime,
    val viewWidth: Float,
    val viewHeight: Float,
    val near: Float,
    val far: Float,
    val modelViewMatrix: Mat4f,
    val modelViewMatrixInverse: Mat4f,
    val previousModelViewMatrix: Mat4f,
    val projectionMatrix: Mat4f,
    val projectionMatrixInverse: Mat4f,
    val previousProjectionMatrix: Mat4f,
    val shadowModelView: Mat4f,
    val shadowModelViewInverse: Mat4f,
    val shadowProjection: Mat4f,
    val shadowProjectionInverse: Mat4f,
    val cameraPosition: Vec3d,
    val previousCameraPosition: Vec3d,
    val worldTime: Int,
    val worldDay: Int,
    val currentDate: Vec3i,
    val currentTime: Vec3i,
    val currentYearTime: Vec2i,
    val playerState: IrisPlayerFrameState,
    val eyeBrightness: Vec2i,
    val skyColor: Vec3f,
    val hideGui: Boolean,
    val rainStrength: Float,
    val thunderStrength: Float,
    val eyeAltitude: Float,
    val sunAngle: Float,
    val moonPhase: Int,
    val screenBrightness: Float,
    val fogStart: Float,
    val fogEnd: Float,
    val fogColor: Vec4f,
    val visionState: IrisVisionFrameState = IrisVisionFrameState.EMPTY,
    val eyeBrightnessSmooth: Vec2i = eyeBrightness,
    val wetness: Float = rainStrength,
    val biomeState: IrisBiomeFrameState = IrisBiomeFrameState.EMPTY,
    val heldItems: IrisHeldItemsFrameState = IrisHeldItemsFrameState.EMPTY,
    val playerMood: Float = 0.0f,
    val constantMood: Float = 0.0f,
    val eyePosition: Vec3d = cameraPosition,
    val relativeEyePosition: Vec3d = Vec3d.EMPTY,
    val lightningBoltPosition: Vec4f = Vec4f.EMPTY,
    val playerLookVector: Vec3f = Vec3f.EMPTY,
    val playerBodyVector: Vec3f = Vec3f.EMPTY,
    val worldInfo: IrisWorldInfoFrameState = IrisWorldInfoFrameState.EMPTY,
    val cloudTime: Float = 0.0f,
    val selectedBlock: IrisSelectedBlockFrameState = IrisSelectedBlockFrameState.EMPTY,
    val renderOrigin: Vec3d = Vec3d.EMPTY,
    val shadowLightDirectionWorld: Vec3f = Vec3f(0.0f, 1.0f, 0.0f),
    val shadowMapResolution: Int = 1,
    val shadowCulling: IrisShadowCullingSet = IrisShadowCullingSet.uncull(cameraPosition),
    val distantHorizons: IrisDistantFrameState = IrisDistantFrameState.host(
        near = near,
        far = far,
        projection = projectionMatrix,
        projectionInverse = projectionMatrixInverse,
        previousProjection = previousProjectionMatrix,
    ),
) {
    init {
        require(playerMood in 0.0f..1.0f)
        require(constantMood in 0.0f..1.0f)
        require(frameTimeSmooth.isFinite() && frameTimeSmooth >= 0.0f)
        require(shadowMapResolution > 0)
    }

    fun uploadTo(
        native: NativeShader,
        declaredUniforms: Set<String>,
        sunPathRotation: Float = 0.0f,
    ): Int {
        var uploads = 0
        val celestial = if (declaredUniforms.any(CELESTIAL_UNIFORMS::contains)) {
            celestialState(sunPathRotation)
        } else {
            null
        }
        for (uniform in declaredUniforms) {
            if (uniform !in SUPPORTED_UNIFORMS) continue
            // GLSL is allowed to optimize a source-declared standard uniform
            // out of the linked program. Iris treats those inputs as optional.
            if (!native.hasUniform(uniform)) continue
            when (uniform) {
                "modelViewMatrix", "gbufferModelView" -> native.setMat4f(uniform, modelViewMatrix)
                "modelViewMatrixInverse", "gbufferModelViewInverse" ->
                    native.setMat4f(uniform, modelViewMatrixInverse)
                "gbufferPreviousModelView" -> native.setMat4f(uniform, previousModelViewMatrix)

                "projectionMatrix", "gbufferProjection" -> native.setMat4f(uniform, projectionMatrix)
                "projectionMatrixInverse", "gbufferProjectionInverse" ->
                    native.setMat4f(uniform, projectionMatrixInverse)
                "gbufferPreviousProjection" -> native.setMat4f(uniform, previousProjectionMatrix)

                "shadowModelView" -> native.setMat4f(uniform, shadowModelView)
                "shadowModelViewInverse" -> native.setMat4f(uniform, shadowModelViewInverse)
                "shadowProjection" -> native.setMat4f(uniform, shadowProjection)
                "shadowProjectionInverse" -> native.setMat4f(uniform, shadowProjectionInverse)
                "shadowMapResolution" -> native.setInt(uniform, shadowMapResolution)
                "dhProjection" -> native.setMat4f(uniform, distantHorizons.projection)
                "dhProjectionInverse" -> native.setMat4f(uniform, distantHorizons.projectionInverse)
                "dhPreviousProjection" -> native.setMat4f(uniform, distantHorizons.previousProjection)

                "cameraPosition" -> native.setVec3f(uniform, Vec3f(cameraPosition))
                "previousCameraPosition" -> native.setVec3f(uniform, Vec3f(previousCameraPosition))
                "eyePosition" -> native.setVec3f(uniform, Vec3f(eyePosition))
                "relativeEyePosition" -> native.setVec3f(uniform, Vec3f(relativeEyePosition))
                "lightningBoltPosition" -> native.setVec4f(uniform, lightningBoltPosition)
                "Moon_Weather_properties" -> native.setVec4f(uniform, Vec4f.EMPTY)
                "playerLookVector" -> native.setVec3f(uniform, playerLookVector)
                "playerBodyVector" -> native.setVec3f(uniform, playerBodyVector)
                "currentSelectedBlockId" -> native.setInt(uniform, selectedBlock.id)
                "currentSelectedBlockPos" -> native.setVec3f(uniform, selectedBlock.position)
                "cameraPositionInt" -> native.setVec3i(uniform, cameraPosition.integerPart())
                "cameraPositionFract" -> native.setVec3f(uniform, cameraPosition.fractionalPart())
                "previousCameraPositionInt" -> native.setVec3i(uniform, previousCameraPosition.integerPart())
                "previousCameraPositionFract" ->
                    native.setVec3f(uniform, previousCameraPosition.fractionalPart())
                "viewWidth" -> native.setFloat(uniform, viewWidth)
                "viewHeight" -> native.setFloat(uniform, viewHeight)
                "aspectRatio" -> native.setFloat(uniform, viewWidth / viewHeight)
                "near" -> native.setFloat(uniform, near)
                "far" -> native.setFloat(uniform, far)
                "dhNearPlane" -> native.setFloat(uniform, distantHorizons.near)
                "dhFarPlane" -> native.setFloat(uniform, distantHorizons.far)
                "dhRenderDistance" -> native.setInt(uniform, distantHorizons.renderDistance)
                "farPlane" -> native.setFloat(uniform, distantHorizons.far)
                "frameCounter" -> native.setInt(uniform, frameCounter)
                "frameTime" -> native.setFloat(uniform, frameTime)
                "frameTimeCounter" -> native.setFloat(uniform, frameTimeCounter)
                "frameTimeSmooth" -> native.setFloat(uniform, frameTimeSmooth)
                "velocity" -> {
                    val x = cameraPosition.x - previousCameraPosition.x
                    val y = cameraPosition.y - previousCameraPosition.y
                    val z = cameraPosition.z - previousCameraPosition.z
                    val distance = sqrt(x * x + y * y + z * z)
                    native.setFloat(uniform, (distance / frameTime.coerceAtLeast(0.000001f)).toFloat())
                }
                "worldTime" -> native.setInt(uniform, worldTime)
                "worldDay" -> native.setInt(uniform, worldDay)
                "currentDate" -> native.setVec3i(uniform, currentDate)
                "currentTime" -> native.setVec3i(uniform, currentTime)
                "currentYearTime" -> native.setVec2i(uniform, currentYearTime)
                "cloudTime" -> native.setFloat(uniform, cloudTime)
                "currentColorSpace" -> native.setInt(uniform, SRGB_COLOR_SPACE)
                "isEyeInWater" -> native.setInt(uniform, playerState.eyeMedium)
                "is_sneaking" -> native.setBoolean(uniform, playerState.sneaking)
                "is_sprinting" -> native.setBoolean(uniform, playerState.sprinting)
                "is_hurt" -> native.setBoolean(uniform, playerState.hurt)
                "is_invisible" -> native.setBoolean(uniform, playerState.invisible)
                "is_burning" -> native.setBoolean(uniform, playerState.burning)
                "is_on_ground" -> native.setBoolean(uniform, playerState.onGround)
                "hideGUI" -> native.setBoolean(uniform, hideGui)
                "isRightHanded" -> native.setBoolean(uniform, playerState.rightHanded)
                "currentPlayerHealth" -> native.setFloat(uniform, playerState.currentHealth)
                "maxPlayerHealth" -> native.setFloat(uniform, playerState.maxHealth)
                "currentPlayerHunger" -> native.setFloat(uniform, playerState.currentHunger)
                "maxPlayerHunger" -> native.setFloat(uniform, MAX_PLAYER_HUNGER)
                "currentPlayerArmor" -> native.setFloat(uniform, playerState.currentArmor)
                "maxPlayerArmor" -> native.setFloat(uniform, MAX_PLAYER_ARMOR)
                "currentPlayerAir" -> native.setFloat(uniform, playerState.currentAir)
                "maxPlayerAir" -> native.setFloat(uniform, playerState.maxAir)
                "firstPersonCamera" -> native.setBoolean(uniform, playerState.firstPersonCamera)
                "isSpectator" -> native.setBoolean(uniform, playerState.spectator)
                "blindness" -> native.setFloat(uniform, visionState.blindness)
                "darknessFactor" -> native.setFloat(uniform, visionState.darknessFactor)
                "darknessLightFactor" -> native.setFloat(uniform, visionState.darknessLightFactor)
                "nightVision" -> native.setFloat(uniform, visionState.nightVision)
                "playerMood" -> native.setFloat(uniform, playerMood)
                "constantMood" -> native.setFloat(uniform, constantMood)
                "biome" -> native.setInt(uniform, biomeState.id)
                "biome_category" -> native.setInt(uniform, biomeState.category)
                "biome_precipitation" -> native.setInt(uniform, biomeState.precipitation)
                "rainfall" -> native.setFloat(uniform, biomeState.rainfall)
                "temperature" -> native.setFloat(uniform, biomeState.temperature)
                "eyeBrightness" -> native.setVec2i(uniform, eyeBrightness)
                "eyeBrightnessSmooth" -> native.setVec2i(uniform, eyeBrightnessSmooth)
                "skyColor" -> native.setVec3f(uniform, skyColor)
                "pi" -> native.setFloat(uniform, PI)
                "rainStrength" -> native.setFloat(uniform, rainStrength)
                "wetness" -> native.setFloat(uniform, wetness)
                "thunderStrength" -> native.setFloat(uniform, thunderStrength)
                "eyeAltitude" -> native.setFloat(uniform, eyeAltitude)
                "sunAngle" -> native.setFloat(uniform, sunAngle)
                "sunPosition" -> native.setVec3f(uniform, celestial!!.sunPosition)
                "moonPosition" -> native.setVec3f(uniform, celestial!!.moonPosition)
                "shadowAngle" -> native.setFloat(uniform, celestial!!.shadowAngle)
                "shadowLightPosition" -> native.setVec3f(uniform, celestial!!.shadowLightPosition)
                "upPosition" -> native.setVec3f(uniform, celestial!!.upPosition)
                "moonPhase" -> native.setInt(uniform, moonPhase)
                "screenBrightness" -> native.setFloat(uniform, screenBrightness)
                "fogMode" -> native.setInt(uniform, LINEAR_FOG_MODE)
                "fogShape" -> native.setInt(uniform, SPHERICAL_FOG_SHAPE)
                "fogDensity", "iris_FogDensity" -> native.setFloat(uniform, 0.0f)
                "fogStart", "iris_FogStart" -> native.setFloat(uniform, fogStart)
                "fogEnd", "iris_FogEnd" -> native.setFloat(uniform, fogEnd)
                "fogColor" -> native.setVec3f(uniform, Vec3f(fogColor.x, fogColor.y, fogColor.z))
                "iris_FogColor" -> native.setVec4f(uniform, fogColor)
                "bedrockLevel" -> native.setInt(uniform, worldInfo.bedrockLevel)
                "cloudHeight" -> native.setFloat(uniform, worldInfo.cloudHeight)
                "heightLimit" -> native.setInt(uniform, worldInfo.heightLimit)
                "logicalHeightLimit" -> native.setInt(uniform, worldInfo.logicalHeightLimit)
                "hasCeiling" -> native.setBoolean(uniform, worldInfo.hasCeiling)
                "hasSkylight" -> native.setBoolean(uniform, worldInfo.hasSkylight)
                "ambientLight" -> native.setFloat(uniform, worldInfo.ambientLight)
            }
            uploads++
        }
        return uploads
    }

    private fun celestialState(sunPathRotation: Float): CelestialFrameState {
        val skyAngle = (sunAngle + SKY_ANGLE_OFFSET) % 1.0f
        val celestialTransform = MMat4f(modelViewMatrix).apply {
            rotateYAssign(-90.0f * DEGREES_TO_RADIANS)
            rotateZAssign(sunPathRotation * DEGREES_TO_RADIANS)
            rotateXAssign(skyAngle * 360.0f * DEGREES_TO_RADIANS)
        }.unsafe
        val sunPosition = (celestialTransform * Vec4f(0.0f, CELESTIAL_DISTANCE, 0.0f, 0.0f)).xyz()
        val moonPosition = (celestialTransform * Vec4f(0.0f, -CELESTIAL_DISTANCE, 0.0f, 0.0f)).xyz()
        val daytime = sunAngle <= HALF_CYCLE
        val upTransform = MMat4f(modelViewMatrix).apply {
            rotateYAssign(-90.0f * DEGREES_TO_RADIANS)
        }.unsafe
        val upPosition = (upTransform * Vec4f(0.0f, CELESTIAL_DISTANCE, 0.0f, 0.0f)).xyz()
        return CelestialFrameState(
            sunPosition = sunPosition,
            moonPosition = moonPosition,
            shadowAngle = if (daytime) sunAngle else sunAngle - HALF_CYCLE,
            shadowLightPosition = if (daytime) sunPosition else moonPosition,
            upPosition = upPosition,
        )
    }

    internal fun customExpressionVariables(sunPathRotation: Float): Map<String, Double> {
        val celestial = celestialState(sunPathRotation)
        return buildMap {
            put("viewWidth", viewWidth.toDouble())
            put("viewHeight", viewHeight.toDouble())
            put("near", near.toDouble())
            put("far", far.toDouble())
            put("frameCounter", frameCounter.toDouble())
            put("frameTime", frameTime.toDouble())
            put("frameTimeCounter", frameTimeCounter.toDouble())
            put("frameTimeSmooth", frameTimeSmooth.toDouble())
            put("aspectRatio", (viewWidth / viewHeight).toDouble())
            put("velocity", cameraVelocity())
            put("worldTime", worldTime.toDouble())
            put("worldDay", worldDay.toDouble())
            put("cloudTime", cloudTime.toDouble())
            put("currentColorSpace", SRGB_COLOR_SPACE.toDouble())
            put("isEyeInWater", playerState.eyeMedium.toDouble())
            put("is_sneaking", playerState.sneaking.number())
            put("is_sprinting", playerState.sprinting.number())
            put("is_hurt", playerState.hurt.number())
            put("is_invisible", playerState.invisible.number())
            put("is_burning", playerState.burning.number())
            put("is_on_ground", playerState.onGround.number())
            put("hideGUI", hideGui.number())
            put("isRightHanded", playerState.rightHanded.number())
            put("firstPersonCamera", playerState.firstPersonCamera.number())
            put("isSpectator", playerState.spectator.number())
            put("currentPlayerHealth", playerState.currentHealth.toDouble())
            put("maxPlayerHealth", playerState.maxHealth.toDouble())
            put("currentPlayerHunger", playerState.currentHunger.toDouble())
            put("maxPlayerHunger", MAX_PLAYER_HUNGER.toDouble())
            put("currentPlayerArmor", playerState.currentArmor.toDouble())
            put("maxPlayerArmor", MAX_PLAYER_ARMOR.toDouble())
            put("currentPlayerAir", playerState.currentAir.toDouble())
            put("maxPlayerAir", playerState.maxAir.toDouble())
            put("blindness", visionState.blindness.toDouble())
            put("darknessFactor", visionState.darknessFactor.toDouble())
            put("darknessLightFactor", visionState.darknessLightFactor.toDouble())
            put("nightVision", visionState.nightVision.toDouble())
            put("playerMood", playerMood.toDouble())
            put("constantMood", constantMood.toDouble())
            // Iris exposes this newer effect input to multi-version packs. It
            // is neutral on the pinned 1.20.4 runtime where the effect is absent.
            put("endFlashIntensity", 0.0)
            put("eyeAltitude", eyeAltitude.toDouble())
            put("sunAngle", sunAngle.toDouble())
            put("moonPhase", moonPhase.toDouble())
            put("rainStrength", rainStrength.toDouble())
            put("wetness", wetness.toDouble())
            put("thunderStrength", thunderStrength.toDouble())
            put("screenBrightness", screenBrightness.toDouble())
            put("biome", biomeState.id.toDouble())
            put("biome_category", biomeState.category.toDouble())
            put("biome_precipitation", biomeState.precipitation.toDouble())
            put("rainfall", biomeState.rainfall.toDouble())
            put("temperature", biomeState.temperature.toDouble())
            put("eyeBrightness.x", eyeBrightness.x.toDouble())
            put("eyeBrightness.y", eyeBrightness.y.toDouble())
            put("eyeBrightnessSmooth.x", eyeBrightnessSmooth.x.toDouble())
            put("eyeBrightnessSmooth.y", eyeBrightnessSmooth.y.toDouble())
            putVector("cameraPosition", cameraPosition)
            putVector("previousCameraPosition", previousCameraPosition)
            putVector("eyePosition", eyePosition)
            putVector("relativeEyePosition", relativeEyePosition)
            putVector("playerLookVector", playerLookVector)
            putVector("playerBodyVector", playerBodyVector)
            put("currentSelectedBlockId", selectedBlock.id.toDouble())
            putVector("currentSelectedBlockPos", selectedBlock.position)
            putVector("skyColor", skyColor)
            putVector("sunPosition", celestial.sunPosition)
            putVector("moonPosition", celestial.moonPosition)
            putVector("shadowLightPosition", celestial.shadowLightPosition)
            putVector("upPosition", celestial.upPosition)
            putVector("lightningBoltPosition", lightningBoltPosition)
            put("shadowAngle", celestial.shadowAngle.toDouble())
            put("bedrockLevel", worldInfo.bedrockLevel.toDouble())
            put("cloudHeight", worldInfo.cloudHeight.toDouble())
            put("heightLimit", worldInfo.heightLimit.toDouble())
            put("logicalHeightLimit", worldInfo.logicalHeightLimit.toDouble())
            put("hasCeiling", worldInfo.hasCeiling.number())
            put("hasSkylight", worldInfo.hasSkylight.number())
            put("ambientLight", worldInfo.ambientLight.toDouble())
            putMatrix("gbufferModelView", modelViewMatrix)
            putMatrix("gbufferModelViewInverse", modelViewMatrixInverse)
            putMatrix("gbufferPreviousModelView", previousModelViewMatrix)
            putMatrix("gbufferProjection", projectionMatrix)
            putMatrix("gbufferProjectionInverse", projectionMatrixInverse)
            putMatrix("gbufferPreviousProjection", previousProjectionMatrix)
            putMatrix("shadowModelView", shadowModelView)
            putMatrix("shadowModelViewInverse", shadowModelViewInverse)
            putMatrix("shadowProjection", shadowProjection)
            putMatrix("shadowProjectionInverse", shadowProjectionInverse)
            put("shadowMapResolution", shadowMapResolution.toDouble())
            put("dhNearPlane", distantHorizons.near.toDouble())
            put("dhFarPlane", distantHorizons.far.toDouble())
            put("dhRenderDistance", distantHorizons.renderDistance.toDouble())
            putMatrix("dhProjection", distantHorizons.projection)
            putMatrix("dhProjectionInverse", distantHorizons.projectionInverse)
            putMatrix("dhPreviousProjection", distantHorizons.previousProjection)
            putAll(CUSTOM_EXPRESSION_CONSTANTS)
            putAll(IrisBiomeFrameState.EXPRESSION_CONSTANTS)
        }
    }

    private fun cameraVelocity(): Double {
        val x = cameraPosition.x - previousCameraPosition.x
        val y = cameraPosition.y - previousCameraPosition.y
        val z = cameraPosition.z - previousCameraPosition.z
        val distance = sqrt(x * x + y * y + z * z)
        return distance / frameTime.coerceAtLeast(0.000001f)
    }

    companion object {
        val SUPPORTED_UNIFORMS = setOf(
            "modelViewMatrix",
            "modelViewMatrixInverse",
            "projectionMatrix",
            "projectionMatrixInverse",
            "gbufferModelView",
            "gbufferModelViewInverse",
            "gbufferPreviousModelView",
            "gbufferProjection",
            "gbufferProjectionInverse",
            "gbufferPreviousProjection",
            "shadowModelView",
            "shadowModelViewInverse",
            "shadowProjection",
            "shadowProjectionInverse",
            "shadowMapResolution",
            "dhProjection",
            "dhProjectionInverse",
            "dhPreviousProjection",
            "cameraPosition",
            "previousCameraPosition",
            "eyePosition",
            "relativeEyePosition",
            "lightningBoltPosition",
            "Moon_Weather_properties",
            "playerLookVector",
            "playerBodyVector",
            "currentSelectedBlockId",
            "currentSelectedBlockPos",
            "cameraPositionInt",
            "cameraPositionFract",
            "previousCameraPositionInt",
            "previousCameraPositionFract",
            "viewWidth",
            "viewHeight",
            "aspectRatio",
            "near",
            "far",
            "dhNearPlane",
            "dhFarPlane",
            "dhRenderDistance",
            "farPlane",
            "frameCounter",
            "frameTime",
            "frameTimeCounter",
            "frameTimeSmooth",
            "velocity",
            "worldTime",
            "worldDay",
            "currentDate",
            "currentTime",
            "currentYearTime",
            "cloudTime",
            "currentColorSpace",
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
            "playerMood",
            "constantMood",
            "biome",
            "biome_category",
            "biome_precipitation",
            "rainfall",
            "temperature",
            "eyeBrightness",
            "eyeBrightnessSmooth",
            "skyColor",
            "pi",
            "rainStrength",
            "wetness",
            "thunderStrength",
            "eyeAltitude",
            "sunAngle",
            "sunPosition",
            "moonPosition",
            "shadowAngle",
            "shadowLightPosition",
            "upPosition",
            "moonPhase",
            "screenBrightness",
            "fogMode",
            "fogShape",
            "fogDensity",
            "fogStart",
            "fogEnd",
            "fogColor",
            "iris_FogDensity",
            "iris_FogStart",
            "iris_FogEnd",
            "iris_FogColor",
            "bedrockLevel",
            "cloudHeight",
            "heightLimit",
            "logicalHeightLimit",
            "hasCeiling",
            "hasSkylight",
            "ambientLight",
        )

        // OpenGL constants returned by Iris 1.7.2's FogUniforms for its
        // normalized linear/spherical fog path.
        private const val LINEAR_FOG_MODE = 0x0801
        private const val SPHERICAL_FOG_SHAPE = 0
        private const val CELESTIAL_DISTANCE = 100.0f
        private const val SKY_ANGLE_OFFSET = 0.75f
        private const val HALF_CYCLE = 0.5f
        private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
        private const val MAX_PLAYER_HUNGER = 20.0f
        private const val MAX_PLAYER_ARMOR = 50.0f
        private const val PI = Math.PI.toFloat()
        private const val SRGB_COLOR_SPACE = 0
        private val CELESTIAL_UNIFORMS = setOf(
            "sunPosition",
            "moonPosition",
            "shadowAngle",
            "shadowLightPosition",
            "upPosition",
        )
        private val CUSTOM_EXPRESSION_CONSTANTS = mapOf(
            "PPT_NONE" to 0.0,
            "PPT_RAIN" to 1.0,
            "PPT_SNOW" to 2.0,
            "CAT_NONE" to 0.0,
            "CAT_TAIGA" to 1.0,
            "CAT_EXTREME_HILLS" to 2.0,
            "CAT_JUNGLE" to 3.0,
            "CAT_MESA" to 4.0,
            "CAT_PLAINS" to 5.0,
            "CAT_SAVANNA" to 6.0,
            "CAT_ICY" to 7.0,
            "CAT_THE_END" to 8.0,
            "CAT_BEACH" to 9.0,
            "CAT_FOREST" to 10.0,
            "CAT_OCEAN" to 11.0,
            "CAT_DESERT" to 12.0,
            "CAT_RIVER" to 13.0,
            "CAT_SWAMP" to 14.0,
            "CAT_MUSHROOM" to 15.0,
            "CAT_NETHER" to 16.0,
            "CAT_MOUNTAIN" to 17.0,
            "CAT_UNDERGROUND" to 18.0,
        )
    }
}

private fun MutableMap<String, Double>.putVector(name: String, value: Vec3f) {
    put("$name.x", value.x.toDouble())
    put("$name.y", value.y.toDouble())
    put("$name.z", value.z.toDouble())
    put("$name.r", value.x.toDouble())
    put("$name.g", value.y.toDouble())
    put("$name.b", value.z.toDouble())
}

private fun MutableMap<String, Double>.putVector(name: String, value: Vec3d) {
    put("$name.x", value.x)
    put("$name.y", value.y)
    put("$name.z", value.z)
    put("$name.r", value.x)
    put("$name.g", value.y)
    put("$name.b", value.z)
}

private fun Boolean.number(): Double = if (this) 1.0 else 0.0

private fun MutableMap<String, Double>.putVector(name: String, value: Vec4f) {
    put("$name.x", value.x.toDouble())
    put("$name.y", value.y.toDouble())
    put("$name.z", value.z.toDouble())
    put("$name.w", value.w.toDouble())
    put("$name.r", value.x.toDouble())
    put("$name.g", value.y.toDouble())
    put("$name.b", value.z.toDouble())
    put("$name.a", value.w.toDouble())
}

private fun MutableMap<String, Double>.putMatrix(name: String, value: Mat4f) {
    for (column in 0..3) {
        for (row in 0..3) {
            put("$name.$column.$row", value[row, column].toDouble())
        }
    }
}

data class IrisHeldItemFrameState(
    val identifier: ResourceLocation?,
    val lightValue: Int,
    val lightColor: Vec3f = Vec3f(1.0f),
) {
    companion object {
        val EMPTY = IrisHeldItemFrameState(null, 0)
    }
}

data class IrisHeldItemsFrameState(
    val main: IrisHeldItemFrameState,
    val off: IrisHeldItemFrameState,
) {
    internal fun mainLight(oldHandLight: Boolean): IrisHeldItemFrameState =
        if (oldHandLight && off.lightValue > main.lightValue) off else main

    companion object {
        val EMPTY = IrisHeldItemsFrameState(IrisHeldItemFrameState.EMPTY, IrisHeldItemFrameState.EMPTY)
        val SUPPORTED_UNIFORMS = setOf(
            "heldItemId",
            "heldItemId2",
            "heldBlockLightValue",
            "heldBlockLightValue2",
            "heldBlockLightColor",
            "heldBlockLightColor2",
        )
    }
}

private data class CelestialFrameState(
    val sunPosition: Vec3f,
    val moonPosition: Vec3f,
    val shadowAngle: Float,
    val shadowLightPosition: Vec3f,
    val upPosition: Vec3f,
)

data class IrisPlayerFrameState(
    val eyeMedium: Int,
    val sneaking: Boolean,
    val sprinting: Boolean,
    val hurt: Boolean,
    val invisible: Boolean,
    val burning: Boolean,
    val onGround: Boolean,
    val rightHanded: Boolean,
    val currentHealth: Float,
    val maxHealth: Float,
    val currentHunger: Float,
    val currentArmor: Float,
    val currentAir: Float,
    val maxAir: Float,
    val firstPersonCamera: Boolean,
    val spectator: Boolean,
) {
    init {
        require(eyeMedium in AIR_EYE_MEDIUM..POWDER_SNOW_EYE_MEDIUM) {
            "Iris eye medium must use the pinned 0..3 ABI"
        }
    }

    companion object {
        const val AIR_EYE_MEDIUM = 0
        const val WATER_EYE_MEDIUM = 1
        const val LAVA_EYE_MEDIUM = 2
        const val POWDER_SNOW_EYE_MEDIUM = 3
        val EMPTY = IrisPlayerFrameState(
            eyeMedium = AIR_EYE_MEDIUM,
            sneaking = false,
            sprinting = false,
            hurt = false,
            invisible = false,
            burning = false,
            onGround = false,
            rightHanded = true,
            currentHealth = -1.0f,
            maxHealth = -1.0f,
            currentHunger = -1.0f,
            currentArmor = -1.0f,
            currentAir = -1.0f,
            maxAir = -1.0f,
            firstPersonCamera = true,
            spectator = false,
        )
    }
}

data class IrisWorldInfoFrameState(
    val bedrockLevel: Int,
    val cloudHeight: Float,
    val heightLimit: Int,
    val logicalHeightLimit: Int,
    val hasCeiling: Boolean,
    val hasSkylight: Boolean,
    val ambientLight: Float,
) {
    init {
        require(heightLimit > 0)
        require(logicalHeightLimit in 1..heightLimit)
        require(ambientLight in 0.0f..1.0f)
    }

    companion object {
        val EMPTY = IrisWorldInfoFrameState(
            bedrockLevel = 0,
            cloudHeight = 192.0f,
            heightLimit = 256,
            logicalHeightLimit = 256,
            hasCeiling = false,
            hasSkylight = true,
            ambientLight = 0.0f,
        )
    }
}

data class IrisSelectedBlockFrameState(
    val state: BlockState?,
    val id: Int,
    val position: Vec3f,
) {
    companion object {
        val EMPTY = IrisSelectedBlockFrameState(
            state = null,
            id = 0,
            position = Vec3f(-256.0f),
        )
    }
}

data class IrisVisionFrameState(
    val blindness: Float,
    val darknessFactor: Float,
    val darknessLightFactor: Float,
    val nightVision: Float,
) {
    init {
        require(blindness in 0.0f..1.0f)
        require(darknessFactor in 0.0f..1.0f)
        require(darknessLightFactor in 0.0f..1.0f)
        require(nightVision in 0.0f..1.0f)
    }

    companion object {
        val EMPTY = IrisVisionFrameState(
            blindness = 0.0f,
            darknessFactor = 0.0f,
            darknessLightFactor = 0.0f,
            nightVision = 0.0f,
        )
    }
}

/**
 * Iris 1.7.2's player-position biome ABI.
 *
 * The `biome` value is not Minosoft's session registry index. Iris assigns
 * fixed IDs while vanilla 1.20.4's BiomeKeys class initializes, returns zero
 * for unknown/modded keys, and derives the legacy category through an ordered
 * tag probe.
 */
data class IrisBiomeFrameState(
    val id: Int,
    val category: Int,
    val precipitation: Int,
    val rainfall: Float,
    val temperature: Float,
) {
    companion object {
        val EMPTY = IrisBiomeFrameState(
            id = 0,
            category = CATEGORY_NONE,
            precipitation = PRECIPITATION_NONE,
            rainfall = 0.0f,
            temperature = 0.0f,
        )

        fun of(
            biome: Biome?,
            position: BlockPosition,
            tags: Set<ResourceLocation>,
        ): IrisBiomeFrameState {
            if (biome == null) return EMPTY
            return IrisBiomeFrameState(
                id = BIOME_IDS[biome.identifier] ?: 0,
                category = CATEGORY_TAGS.firstOrNull { it.first in tags }?.second ?: CATEGORY_PLAINS,
                precipitation = when (biome.precipitationAt(position)) {
                    null -> PRECIPITATION_NONE
                    BiomePrecipitation.RAIN -> PRECIPITATION_RAIN
                    BiomePrecipitation.SNOW -> PRECIPITATION_SNOW
                },
                rainfall = biome.downfall,
                temperature = biome.temperature,
            )
        }

        private const val PRECIPITATION_NONE = 0
        private const val PRECIPITATION_RAIN = 1
        private const val PRECIPITATION_SNOW = 2

        // Exact BiomeCategories ordinal ABI pinned from Iris 1.7.2.
        private const val CATEGORY_NONE = 0
        private const val CATEGORY_TAIGA = 1
        private const val CATEGORY_EXTREME_HILLS = 2
        private const val CATEGORY_JUNGLE = 3
        private const val CATEGORY_MESA = 4
        private const val CATEGORY_PLAINS = 5
        private const val CATEGORY_ICY = 7
        private const val CATEGORY_THE_END = 8
        private const val CATEGORY_BEACH = 9
        private const val CATEGORY_FOREST = 10
        private const val CATEGORY_OCEAN = 11
        private const val CATEGORY_DESERT = 12
        private const val CATEGORY_RIVER = 13
        private const val CATEGORY_SWAMP = 14
        private const val CATEGORY_MUSHROOM = 15
        private const val CATEGORY_NETHER = 16
        private const val CATEGORY_MOUNTAIN = 17
        private const val CATEGORY_UNDERGROUND = 18

        private val CATEGORY_TAGS = listOf(
            minecraft("without_wandering_trader_spawns") to CATEGORY_NONE,
            minecraft("village_snowy_has_structure") to CATEGORY_ICY,
            minecraft("is_hill") to CATEGORY_EXTREME_HILLS,
            minecraft("is_taiga") to CATEGORY_TAIGA,
            minecraft("is_ocean") to CATEGORY_OCEAN,
            minecraft("is_jungle") to CATEGORY_JUNGLE,
            minecraft("is_forest") to CATEGORY_FOREST,
            minecraft("is_badlands") to CATEGORY_MESA,
            minecraft("is_nether") to CATEGORY_NETHER,
            minecraft("is_end") to CATEGORY_THE_END,
            minecraft("is_beach") to CATEGORY_BEACH,
            minecraft("desert_pyramid_has_structure") to CATEGORY_DESERT,
            minecraft("is_river") to CATEGORY_RIVER,
            minecraft("has_closer_water_fog") to CATEGORY_SWAMP,
            minecraft("plays_underwater_music") to CATEGORY_UNDERGROUND,
            minecraft("without_zombie_sieges") to CATEGORY_MUSHROOM,
            minecraft("is_mountain") to CATEGORY_MOUNTAIN,
        )

        private val BIOME_IDS = listOf(
            "the_void",
            "plains",
            "sunflower_plains",
            "snowy_plains",
            "ice_spikes",
            "desert",
            "swamp",
            "mangrove_swamp",
            "forest",
            "flower_forest",
            "birch_forest",
            "dark_forest",
            "old_growth_birch_forest",
            "old_growth_pine_taiga",
            "old_growth_spruce_taiga",
            "taiga",
            "snowy_taiga",
            "savanna",
            "savanna_plateau",
            "windswept_hills",
            "windswept_gravelly_hills",
            "windswept_forest",
            "windswept_savanna",
            "jungle",
            "sparse_jungle",
            "bamboo_jungle",
            "badlands",
            "eroded_badlands",
            "wooded_badlands",
            "meadow",
            "cherry_grove",
            "grove",
            "snowy_slopes",
            "frozen_peaks",
            "jagged_peaks",
            "stony_peaks",
            "river",
            "frozen_river",
            "beach",
            "snowy_beach",
            "stony_shore",
            "warm_ocean",
            "lukewarm_ocean",
            "deep_lukewarm_ocean",
            "ocean",
            "deep_ocean",
            "cold_ocean",
            "deep_cold_ocean",
            "frozen_ocean",
            "deep_frozen_ocean",
            "mushroom_fields",
            "dripstone_caves",
            "lush_caves",
            "deep_dark",
            "nether_wastes",
            "warped_forest",
            "crimson_forest",
            "soul_sand_valley",
            "basalt_deltas",
            "the_end",
            "end_highlands",
            "end_midlands",
            "small_end_islands",
            "end_barrens",
        ).mapIndexed { id, path -> minecraft(path) to id }.toMap()

        internal val EXPRESSION_CONSTANTS = BIOME_IDS.entries.associate { (identifier, id) ->
            "BIOME_${identifier.path.uppercase()}" to id.toDouble()
        } + ("BIOME_PALE_GARDEN" to 0.0)
    }
}

private fun Vec4f.xyz() = Vec3f(x, y, z)

internal fun irisShadowProjection(directives: IrisShadowDirectives): Mat4f =
    directives.mapFov?.let { fov ->
        // Pinned Iris uses the legacy fixed depth range for perspective shadow maps.
        CameraUtil.perspective(
            fov * (Math.PI / 180.0).toFloat(),
            1.0f,
            0.05f,
            256.0f,
        )
    } ?: CameraUtil.orthographic(
        -directives.distance,
        directives.distance,
        -directives.distance,
        directives.distance,
        directives.nearPlane,
        directives.farPlane,
    )

internal fun irisSnappedShadowCamera(camera: Vec3d, intervalSize: Float): Vec3d {
    if (abs(intervalSize) == 0.0f) return camera
    // Iris deliberately narrows the absolute camera coordinate before applying
    // Java's signed remainder. Preserve that behavior at large/negative
    // coordinates, then express the snapped point back in world doubles.
    val half = intervalSize * 0.5f
    return Vec3d(
        camera.x - (camera.x.toFloat() % intervalSize - half),
        camera.y - (camera.y.toFloat() % intervalSize - half),
        camera.z - (camera.z.toFloat() % intervalSize - half),
    )
}

internal fun irisShadowModelView(
    shadowAngle: Float,
    sunPathRotation: Float,
    intervalSize: Float,
    cameraWorld: Vec3d,
    renderOrigin: Vec3d,
): Mat4f {
    val skyAngle = if (shadowAngle < 0.25f) shadowAngle + 0.75f else shadowAngle - 0.25f
    val snappedCamera = irisSnappedShadowCamera(cameraWorld, intervalSize)
    val renderCamera = snappedCamera - renderOrigin
    val radians = (Math.PI / 180.0).toFloat()
    return MMat4f().apply {
        clearAssign()
        translateAssign(0.0f, 0.0f, -100.0f)
        rotateXAssign(90.0f * radians)
        rotateZAssign(skyAngle * -360.0f * radians)
        rotateXAssign(sunPathRotation * radians)
        translateAssign(
            -renderCamera.x.toFloat(),
            -renderCamera.y.toFloat(),
            -renderCamera.z.toFloat(),
        )
    }.unsafe
}

internal class IrisFrameStateClock {
    private var previousNanos: Long? = null
    private var counter = 0.0f
    private var smoothFrameTime = 0.0f
    private var previousCameraPosition = Vec3d.EMPTY
    private var previousModelViewMatrix = Mat4f()
    private var previousProjectionMatrix = Mat4f()
    private var previousDistantProjection: Mat4f? = null

    fun capture(
        context: RenderContext,
        plan: ShaderPipelinePlan? = null,
        nowNanos: Long = System.nanoTime(),
    ): IrisFrameState {
        val previous = previousNanos
        val frameTime = if (previous == null || nowNanos <= previous) {
            0.0f
        } else {
            ((nowNanos - previous) / 1_000_000_000.0).toFloat().coerceIn(0.0f, 1.0f)
        }
        previousNanos = nowNanos
        if (frameTime > 0.0f) {
            smoothFrameTime = if (smoothFrameTime == 0.0f) {
                frameTime
            } else {
                smoothFrameTime + (frameTime - smoothFrameTime) * FRAME_TIME_SMOOTHING
            }
        }
        counter = (counter + frameTime) % FRAME_TIME_WRAP_SECONDS

        val matrix = context.camera.matrix
        val world = context.session.world
        val dimension = world.dimension
        val time = world.time
        val player = context.session.player
        val playerPosition = player.physics.positionInfo.position
        val playerBiome = player.physics.positionInfo.biome
        val playerBiomeTags = linkedSetOf(
            *context.session.tags[BIOME]?.matching(playerBiome).orEmpty().toTypedArray(),
            *context.session.legacyTags[BIOME]?.matching(playerBiome).orEmpty().toTypedArray(),
        )
        val biomeState = IrisBiomeFrameState.of(playerBiome, playerPosition, playerBiomeTags)
        val survival = player.gamemode.survival
        val maxHealth = player.attributes[MinecraftAttributes.MAX_HEALTH].toFloat()
        val maxAir = MAX_PLAYER_AIR
        val playerState = IrisPlayerFrameState(
            eyeMedium = when (player.physics.submersion.eye) {
                is WaterFluid -> IrisPlayerFrameState.WATER_EYE_MEDIUM
                is LavaFluid -> IrisPlayerFrameState.LAVA_EYE_MEDIUM
                else -> IrisPlayerFrameState.AIR_EYE_MEDIUM
            },
            sneaking = player.isSneaking,
            sprinting = player.isSprinting,
            hurt = player.hurtTime > 0,
            invisible = player.isInvisible,
            burning = player.isOnFire,
            onGround = player.physics.onGround,
            rightHanded = player.mainArm == Arms.RIGHT,
            currentHealth = if (survival) player.health.toFloat() / maxHealth else -1.0f,
            maxHealth = if (survival) maxHealth else -1.0f,
            currentHunger = if (survival) player.healthCondition.hunger / MAX_PLAYER_HUNGER else -1.0f,
            currentArmor = if (survival) {
                player.attributes[MinecraftAttributes.ARMOR].toFloat() / MAX_PLAYER_ARMOR
            } else {
                -1.0f
            },
            currentAir = if (survival) player.airSupply / maxAir else -1.0f,
            maxAir = if (survival) maxAir else -1.0f,
            firstPersonCamera = context.camera.view.view is FirstPersonView,
            spectator = player.gamemode == Gamemodes.SPECTATOR,
        )
        fun held(slot: EquipmentSlots): IrisHeldItemFrameState {
            val stack = player.equipment[slot] ?: return IrisHeldItemFrameState.EMPTY
            val item = stack.item
            val light = (item as? BlockItem<*>)?.block?.states?.default?.luminance ?: 0
            return IrisHeldItemFrameState(item.identifier, light.coerceAtLeast(0))
        }
        val localTime = LocalDateTime.now()
        val yearSeconds = (localTime.dayOfYear - 1) * SECONDS_PER_DAY +
            localTime.hour * SECONDS_PER_HOUR +
            localTime.minute * SECONDS_PER_MINUTE +
            localTime.second
        val secondsInYear = localTime.toLocalDate().lengthOfYear() * SECONDS_PER_DAY
        val weather = world.weather
        val fog = context.camera.fog.state
        val fogColor = fog.color ?: context.system.clearColor
        val cameraEntity = context.session.camera.entity
        val eyePosition = player.renderInfo.eyePosition
        val camera = cameraEntity.renderInfo.eyePosition - context.camera.offset.offset
        val lightningBoltPosition = world.entities.lock.acquired {
            world.entities.entities
                .firstOrNull { it is LightningBolt }
                ?.let { bolt ->
                    val relative = bolt.renderInfo.position - camera
                    Vec4f(relative.x.toFloat(), relative.y.toFloat(), relative.z.toFloat(), 1.0f)
                }
                ?: Vec4f.EMPTY
        }
        val cameraPartialTick = cameraEntity.renderInfo.partialTick
        val cameraLiving = cameraEntity as? LivingEntity
        val playerLookVector = cameraEntity.physics.rotation.front
        val playerBodyVector = cameraLiving?.renderInfo?.rotation?.front ?: Vec3f.EMPTY
        val cameraDarkness = cameraLiving?.effects?.get(VisionEffect.Darkness)
        val localDarknessFactor = player.effects[VisionEffect.Darkness]
            ?.factorCalculationData
            ?.interpolate(cameraPartialTick)
            ?: 0.0f
        val visionState = IrisVisionFrameState(
            blindness = VisionEffectIntensity.blindness(cameraLiving?.effects?.get(VisionEffect.Blindness)),
            darknessFactor = cameraDarkness?.factorCalculationData?.interpolate(cameraPartialTick) ?: 0.0f,
            darknessLightFactor = VisionEffectIntensity.darknessLightFactor(
                player.age,
                localDarknessFactor,
                cameraPartialTick,
            ),
            nightVision = VisionEffectIntensity.nightVision(
                cameraLiving?.effects?.get(VisionEffect.NightVision),
                cameraPartialTick,
            ),
        )
        val eyeLight = world.getLight(cameraEntity.physics.positionInfo.eyePosition)
        val eyeBrightness = eyeLight.irisEyeBrightness()
        val skyColor = context.renderer[SkyRenderer]?.box?.color?.calculateWorldColor()?.toVec3f() ?: Vec3f.EMPTY
        val hideGui = context.renderer[GUIRenderer]?.hud?.enabled?.not() ?: false
        val shadow = plan?.shadowDirectives ?: IrisShadowDirectives()
        val size = context.window.size
        val sunAngle = time.time / WorldTime.TICKS_PER_DAYf
        val shadowAngle = if (sunAngle <= 0.5f) sunAngle else sunAngle - 0.5f
        val renderOrigin = context.camera.offset.offset
        val shadowModelView = irisShadowModelView(
            shadowAngle = shadowAngle,
            sunPathRotation = plan?.sunPathRotation ?: 0.0f,
            intervalSize = shadow.intervalSize,
            cameraWorld = cameraEntity.renderInfo.eyePosition,
            renderOrigin = Vec3d(
                renderOrigin.x.toDouble(),
                renderOrigin.y.toDouble(),
                renderOrigin.z.toDouble(),
            ),
        )
        val shadowProjection = irisShadowProjection(shadow)
        val shadowMapResolution = (
            plan?.buffers?.get(ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0))?.size as? RenderTargetSize.Fixed
        )?.width ?: 1
        val modelViewMatrix = matrix.viewMatrix.copyImmutable()
        val projectionMatrix = matrix.projectionMatrix.copyImmutable()
        var distantRenderDistance = IrisDistantFrameState.DEFAULT_RENDER_DISTANCE
        for (renderer in context.renderer) {
            if (renderer !is DistantTerrainRenderer) continue
            distantRenderDistance = renderer.renderDistanceBlocks
            break
        }
        val distantHorizons = IrisDistantFrameState.create(
            hostProjection = projectionMatrix,
            near = matrix.nearPlane,
            renderDistance = distantRenderDistance,
            previousProjection = previousDistantProjection,
        )
        val shadowLightDirectionWorld = irisShadowLightDirectionWorld(
            sunAngle = sunAngle,
            sunPathRotation = plan?.sunPathRotation ?: 0.0f,
        )
        val shadowCulling = IrisShadowCullingSet.create(
            directives = shadow,
            playerView = modelViewMatrix,
            playerProjection = projectionMatrix,
            shadowLightDirection = shadowLightDirectionWorld,
            camera = camera,
            coordinateOrigin = Vec3d(
                renderOrigin.x.toDouble(),
                renderOrigin.y.toDouble(),
                renderOrigin.z.toDouble(),
            ),
            effectiveRenderDistanceBlocks =
                context.session.world.view.viewDistance.coerceAtLeast(1).toDouble() * 16.0,
        )
        val selectedTarget = context.renderer[BlockOutlineRenderer]?.irisSelectedTarget()
        val selectedBlock = selectedTarget?.let {
            IrisSelectedBlockFrameState(
                state = it.state,
                id = 0,
                position = Vec3f(it.blockPosition.center - camera),
            )
        } ?: IrisSelectedBlockFrameState.EMPTY
        val state = IrisFrameState(
            frameCounter = (context.frameNumber and Int.MAX_VALUE.toLong()).toInt(),
            frameTime = frameTime,
            frameTimeCounter = counter,
            frameTimeSmooth = smoothFrameTime,
            viewWidth = size.x.toFloat().coerceAtLeast(1.0f),
            viewHeight = size.y.toFloat().coerceAtLeast(1.0f),
            near = matrix.nearPlane,
            far = matrix.farPlane,
            modelViewMatrix = modelViewMatrix,
            modelViewMatrixInverse = modelViewMatrix.inverse(),
            previousModelViewMatrix = previousModelViewMatrix,
            projectionMatrix = projectionMatrix,
            projectionMatrixInverse = projectionMatrix.inverse(),
            previousProjectionMatrix = previousProjectionMatrix,
            shadowModelView = shadowModelView,
            shadowModelViewInverse = shadowModelView.inverse(),
            shadowProjection = shadowProjection,
            shadowProjectionInverse = shadowProjection.inverse(),
            cameraPosition = camera,
            previousCameraPosition = previousCameraPosition,
            worldTime = time.time,
            worldDay = (time.age / WorldTime.TICKS_PER_DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            currentDate = Vec3i(localTime.year, localTime.monthValue, localTime.dayOfMonth),
            currentTime = Vec3i(localTime.hour, localTime.minute, localTime.second),
            currentYearTime = Vec2i(yearSeconds, secondsInYear - yearSeconds),
            playerState = playerState,
            eyeBrightness = eyeBrightness,
            skyColor = skyColor,
            hideGui = hideGui,
            rainStrength = weather.rain.coerceIn(0.0f, 1.0f),
            thunderStrength = weather.thunder.coerceIn(0.0f, 1.0f),
            eyeAltitude = camera.y.toFloat(),
            sunAngle = sunAngle,
            moonPhase = time.moonPhase.ordinal,
            screenBrightness = context.profile.light.gamma.coerceIn(0.0f, 1.0f),
            fogStart = fog.start,
            fogEnd = fog.end,
            fogColor = fogColor.toVec4f(),
            visionState = visionState,
            biomeState = biomeState,
            heldItems = IrisHeldItemsFrameState(
                main = held(EquipmentSlots.MAIN_HAND),
                off = held(EquipmentSlots.OFF_HAND),
            ),
            playerMood = world.mood.playerMood,
            constantMood = world.mood.constantMood,
            eyePosition = eyePosition,
            relativeEyePosition = camera - eyePosition,
            lightningBoltPosition = lightningBoltPosition,
            playerLookVector = playerLookVector,
            playerBodyVector = playerBodyVector,
            worldInfo = IrisWorldInfoFrameState(
                bedrockLevel = dimension.minY,
                cloudHeight = if (dimension.effects.clouds) {
                    dimension.effects.getCloudHeight(context.session).first.toFloat()
                } else {
                    Float.NaN
                },
                heightLimit = dimension.height,
                logicalHeightLimit = dimension.logicalHeight,
                hasCeiling = dimension.hasCeiling,
                hasSkylight = dimension.skyLight,
                ambientLight = dimension.ambientLight.base,
            ),
            cloudTime = (cameraEntity.age + cameraPartialTick) * CLOUD_TIME_SCALE,
            selectedBlock = selectedBlock,
            renderOrigin = Vec3d(
                renderOrigin.x.toDouble(),
                renderOrigin.y.toDouble(),
                renderOrigin.z.toDouble(),
            ),
            shadowLightDirectionWorld = shadowLightDirectionWorld,
            shadowMapResolution = shadowMapResolution,
            shadowCulling = shadowCulling,
            distantHorizons = distantHorizons,
        )
        previousCameraPosition = camera
        previousModelViewMatrix = modelViewMatrix
        previousProjectionMatrix = projectionMatrix
        previousDistantProjection = distantHorizons.projection
        return state
    }

    private companion object {
        const val FRAME_TIME_WRAP_SECONDS = 3_600.0f
        const val FRAME_TIME_SMOOTHING = 0.1f
        const val SECONDS_PER_MINUTE = 60
        const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
        const val SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR
        const val MAX_PLAYER_HUNGER = 20.0f
        const val MAX_PLAYER_ARMOR = 50.0f
        const val MAX_PLAYER_AIR = 300.0f
        const val CLOUD_TIME_SCALE = 0.03f
    }
}

private fun Vec3d.integerPart() = Vec3i(
    floor(x).toInt(),
    floor(y).toInt(),
    floor(z).toInt(),
)

private fun Vec3d.fractionalPart() = Vec3f(
    (x - floor(x)).toFloat(),
    (y - floor(y)).toFloat(),
    (z - floor(z)).toFloat(),
)

internal fun LightLevel.irisEyeBrightness() = Vec2i(
    block * 16,
    sky * 16,
)

internal fun Mat4f.copyImmutable() = Mat4f(
    this[0, 0], this[0, 1], this[0, 2], this[0, 3],
    this[1, 0], this[1, 1], this[1, 2], this[1, 3],
    this[2, 0], this[2, 1], this[2, 2], this[2, 3],
    this[3, 0], this[3, 1], this[3, 2], this[3, 3],
)

/**
 * Gauss-Jordan inversion is used here instead of assuming a rigid camera
 * transform because projection matrices also cross this boundary.
 */
internal fun Mat4f.inverse(): Mat4f {
    val augmented = Array(4) { row ->
        DoubleArray(8) { column ->
            when {
                column < 4 -> this[row, column].toDouble()
                column - 4 == row -> 1.0
                else -> 0.0
            }
        }
    }
    for (column in 0 until 4) {
        var pivot = column
        for (row in column + 1 until 4) {
            if (abs(augmented[row][column]) > abs(augmented[pivot][column])) pivot = row
        }
        require(abs(augmented[pivot][column]) > 1.0e-12) { "Iris frame matrix is singular" }
        if (pivot != column) {
            val swap = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = swap
        }
        val divisor = augmented[column][column]
        for (entry in 0 until 8) augmented[column][entry] /= divisor
        for (row in 0 until 4) {
            if (row == column) continue
            val factor = augmented[row][column]
            for (entry in 0 until 8) augmented[row][entry] -= factor * augmented[column][entry]
        }
    }
    return Mat4f(
        augmented[0][4].toFloat(), augmented[0][5].toFloat(), augmented[0][6].toFloat(), augmented[0][7].toFloat(),
        augmented[1][4].toFloat(), augmented[1][5].toFloat(), augmented[1][6].toFloat(), augmented[1][7].toFloat(),
        augmented[2][4].toFloat(), augmented[2][5].toFloat(), augmented[2][6].toFloat(), augmented[2][7].toFloat(),
        augmented[3][4].toFloat(), augmented[3][5].toFloat(), augmented[3][6].toFloat(), augmented[3][7].toFloat(),
    )
}
