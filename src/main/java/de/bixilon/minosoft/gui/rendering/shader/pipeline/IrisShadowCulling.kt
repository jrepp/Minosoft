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

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f

/**
 * Exact values accepted by pinned Iris 1.7.2's `shadow.culling` property.
 */
enum class IrisShadowCullingMode {
    DEFAULT,
    ADVANCED,
    REVERSED,
    DISTANCE,
}

enum class IrisShadowCullingKind {
    UNCULLED,
    CULL_EVERYTHING,
    DISTANCE,
    ADVANCED,
    REVERSED,
}

/**
 * One immutable, frame-pinned shadow visibility decision.
 *
 * The advanced plane construction is a host-coordinate port of pinned Iris
 * 1.7.2's AdvancedShadowCullingFrustum. Iris subtracts the camera because its
 * captured player view is camera-relative. Minosoft's player view is relative
 * to the floating render origin, so plane tests subtract [coordinateOrigin]
 * instead. The resulting eye-space points are equivalent while preserving
 * Minosoft's large-world precision boundary.
 */
class IrisShadowCullingVolume private constructor(
    val kind: IrisShadowCullingKind,
    private val camera: Vec3d,
    private val coordinateOrigin: Vec3d,
    private val planes: Array<Vec4f> = emptyArray(),
    private val distanceBox: DistanceBox? = null,
    private val reversedInnerBox: DistanceBox? = null,
    private val reversedOuterBox: DistanceBox? = null,
) {
    fun allowsAabb(
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ): Boolean {
        when (kind) {
            IrisShadowCullingKind.UNCULLED -> return true
            IrisShadowCullingKind.CULL_EVERYTHING -> return false
            IrisShadowCullingKind.DISTANCE ->
                return requireNotNull(distanceBox).allows(camera, minX, minY, minZ, maxX, maxY, maxZ)

            IrisShadowCullingKind.ADVANCED -> {
                if (
                    distanceBox != null &&
                    !distanceBox.allows(camera, minX, minY, minZ, maxX, maxY, maxZ)
                ) {
                    return false
                }
            }

            IrisShadowCullingKind.REVERSED -> {
                if (
                    reversedOuterBox != null &&
                    !reversedOuterBox.allows(camera, minX, minY, minZ, maxX, maxY, maxZ)
                ) {
                    return false
                }
                if (
                    reversedInnerBox != null &&
                    reversedInnerBox.allows(camera, minX, minY, minZ, maxX, maxY, maxZ)
                ) {
                    return true
                }
            }
        }

        val localMinX = (minX - coordinateOrigin.x).toFloat()
        val localMinY = (minY - coordinateOrigin.y).toFloat()
        val localMinZ = (minZ - coordinateOrigin.z).toFloat()
        val localMaxX = (maxX - coordinateOrigin.x).toFloat()
        val localMaxY = (maxY - coordinateOrigin.y).toFloat()
        val localMaxZ = (maxZ - coordinateOrigin.z).toFloat()
        for (plane in planes) {
            val outsideX = if (plane.x < 0.0f) localMinX else localMaxX
            val outsideY = if (plane.y < 0.0f) localMinY else localMaxY
            val outsideZ = if (plane.z < 0.0f) localMinZ else localMaxZ
            if (
                Math.fma(
                    plane.x,
                    outsideX,
                    Math.fma(plane.y, outsideY, plane.z * outsideZ),
                ) < -plane.w
            ) {
                return false
            }
        }
        return true
    }

    private data class DistanceBox(val distance: Double) {
        fun allows(
            camera: Vec3d,
            minX: Double,
            minY: Double,
            minZ: Double,
            maxX: Double,
            maxY: Double,
            maxZ: Double,
        ): Boolean {
            if (maxX < camera.x - distance || minX > camera.x + distance) return false
            if (maxY < camera.y - distance || minY > camera.y + distance) return false
            return maxZ >= camera.z - distance && minZ <= camera.z + distance
        }
    }

    companion object {
        private const val MAX_CLIPPING_PLANES = 13
        private val NEIGHBORS = arrayOf(
            intArrayOf(2, 3, 4, 5),
            intArrayOf(0, 1, 4, 5),
            intArrayOf(0, 1, 2, 3),
        )

        fun uncull(camera: Vec3d, coordinateOrigin: Vec3d) = IrisShadowCullingVolume(
            kind = IrisShadowCullingKind.UNCULLED,
            camera = camera,
            coordinateOrigin = coordinateOrigin,
        )

        fun cullEverything(camera: Vec3d, coordinateOrigin: Vec3d) = IrisShadowCullingVolume(
            kind = IrisShadowCullingKind.CULL_EVERYTHING,
            camera = camera,
            coordinateOrigin = coordinateOrigin,
        )

        fun distance(camera: Vec3d, coordinateOrigin: Vec3d, distance: Double) =
            IrisShadowCullingVolume(
                kind = IrisShadowCullingKind.DISTANCE,
                camera = camera,
                coordinateOrigin = coordinateOrigin,
                distanceBox = DistanceBox(distance),
            )

        fun advanced(
            camera: Vec3d,
            coordinateOrigin: Vec3d,
            playerView: Mat4f,
            playerProjection: Mat4f,
            shadowLightDirection: Vec3f,
            distance: Double?,
        ) = IrisShadowCullingVolume(
            kind = IrisShadowCullingKind.ADVANCED,
            camera = camera,
            coordinateOrigin = coordinateOrigin,
            planes = advancedPlanes(playerView, playerProjection, shadowLightDirection),
            distanceBox = distance?.let(::DistanceBox),
        )

        fun reversed(
            camera: Vec3d,
            coordinateOrigin: Vec3d,
            playerView: Mat4f,
            playerProjection: Mat4f,
            shadowLightDirection: Vec3f,
            innerDistance: Double,
            outerDistance: Double,
        ) = IrisShadowCullingVolume(
            kind = IrisShadowCullingKind.REVERSED,
            camera = camera,
            coordinateOrigin = coordinateOrigin,
            planes = advancedPlanes(playerView, playerProjection, shadowLightDirection),
            reversedInnerBox = DistanceBox(innerDistance),
            reversedOuterBox = DistanceBox(outerDistance),
        )

        private fun advancedPlanes(
            playerView: Mat4f,
            playerProjection: Mat4f,
            shadowLightDirection: Vec3f,
        ): Array<Vec4f> {
            val transform = (playerProjection * playerView).transpose()
            fun base(x: Float, y: Float, z: Float) =
                (transform * Vec4f(x, y, z, 1.0f)).normalize()

            val base = arrayOf(
                base(-1.0f, 0.0f, 0.0f),
                base(1.0f, 0.0f, 0.0f),
                base(0.0f, -1.0f, 0.0f),
                base(0.0f, 1.0f, 0.0f),
                base(0.0f, 0.0f, -1.0f),
                base(0.0f, 0.0f, 1.0f),
            )
            val result = ArrayList<Vec4f>(MAX_CLIPPING_PLANES)
            val back = BooleanArray(base.size)
            for (index in base.indices) {
                val plane = base[index]
                val dot = plane.xyz dot shadowLightDirection
                back[index] = dot > 0.0f
                if (back[index] || dot == 0.0f) result += plane
            }
            for (index in base.indices) {
                if (!back[index]) continue
                for (neighbor in NEIGHBORS[index ushr 1]) {
                    if (!back[neighbor]) result += edgePlane(base[index], base[neighbor], shadowLightDirection)
                }
            }
            check(result.size <= MAX_CLIPPING_PLANES) {
                "Iris advanced shadow frustum produced ${result.size} clipping planes"
            }
            return result.toTypedArray()
        }

        private fun edgePlane(back: Vec4f, front: Vec4f, shadowLightDirection: Vec3f): Vec4f {
            val backNormal = back.xyz
            val frontNormal = front.xyz
            val intersection = backNormal cross frontNormal
            val edgeNormal = intersection cross shadowLightDirection
            val point = (
                (intersection cross backNormal) * -front.w +
                    (frontNormal cross intersection) * -back.w
                ) * (1.0f / intersection.length2())
            val distance = edgeNormal dot point
            return Vec4f(edgeNormal.x, edgeNormal.y, edgeNormal.z, -distance)
        }
    }
}

/**
 * Terrain and entity frusta are distinct only when the pack requests Iris's
 * nonnegative entity multiplier. Block entities retain the terrain-section
 * gate and then apply Iris's separate raw distance box in that case.
 */
class IrisShadowCullingSet private constructor(
    val terrain: IrisShadowCullingVolume,
    val entities: IrisShadowCullingVolume,
    private val blockEntityDistance: Double?,
    private val camera: Vec3d,
) {
    val hasDistinctEntityVolume: Boolean get() = entities !== terrain
    val blockEntityDistanceLimit: Double? get() = blockEntityDistance

    fun allowsTerrainSection(sectionX: Int, sectionY: Int, sectionZ: Int): Boolean {
        val minX = sectionX.toDouble() * SECTION_SIZE
        val minY = sectionY.toDouble() * SECTION_SIZE
        val minZ = sectionZ.toDouble() * SECTION_SIZE
        return terrain.allowsAabb(
            minX,
            minY,
            minZ,
            minX + SECTION_SIZE,
            minY + SECTION_SIZE,
            minZ + SECTION_SIZE,
        )
    }

    fun allowsEntityBounds(
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ) = entities.allowsAabb(minX, minY, minZ, maxX, maxY, maxZ)

    fun allowsBlockEntityBounds(blockX: Int, blockY: Int, blockZ: Int): Boolean {
        val distance = blockEntityDistance ?: return true
        if (blockX + 1.0 < camera.x - distance || blockX - 1.0 > camera.x + distance) return false
        if (blockY + 1.0 < camera.y - distance || blockY - 1.0 > camera.y + distance) return false
        return blockZ + 1.0 >= camera.z - distance && blockZ - 1.0 <= camera.z + distance
    }

    companion object {
        private const val SECTION_SIZE = 16.0

        fun uncull(camera: Vec3d = Vec3d.EMPTY): IrisShadowCullingSet {
            val volume = IrisShadowCullingVolume.uncull(camera, Vec3d.EMPTY)
            return IrisShadowCullingSet(
                terrain = volume,
                entities = volume,
                blockEntityDistance = null,
                camera = camera,
            )
        }

        fun create(
            directives: IrisShadowDirectives,
            playerView: Mat4f,
            playerProjection: Mat4f,
            shadowLightDirection: Vec3f,
            camera: Vec3d,
            coordinateOrigin: Vec3d,
            effectiveRenderDistanceBlocks: Double,
        ): IrisShadowCullingSet {
            require(effectiveRenderDistanceBlocks.isFinite() && effectiveRenderDistanceBlocks > 0.0)

            fun volume(initialMultiplier: Float): IrisShadowCullingVolume {
                var multiplier = initialMultiplier
                val distanceOnly =
                    directives.cullingMode == IrisShadowCullingMode.DISTANCE ||
                        directives.cullingMode == IrisShadowCullingMode.DEFAULT &&
                        directives.voxelizationDetected
                if (distanceOnly) {
                    val distance = directives.distance * multiplier
                    return if (distance <= 0.0 || distance > effectiveRenderDistanceBlocks) {
                        IrisShadowCullingVolume.uncull(camera, coordinateOrigin)
                    } else {
                        IrisShadowCullingVolume.distance(camera, coordinateOrigin, distance.toDouble())
                    }
                }

                val reversed = directives.cullingMode == IrisShadowCullingMode.REVERSED
                if (reversed && multiplier < 0.0f) multiplier = 1.0f
                var distance = (
                    if (reversed) directives.voxelDistance else directives.distance
                    ) * multiplier
                if (multiplier < 0.0f) {
                    // Minosoft has no independent Iris shadow-distance slider.
                    // Its loaded-section boundary is already the effective
                    // render distance, which is equivalent to Iris omitting
                    // this box when its user distance reaches that boundary.
                    distance = effectiveRenderDistanceBlocks.toFloat()
                }
                if (!reversed && distance == 0.0f) {
                    return IrisShadowCullingVolume.cullEverything(camera, coordinateOrigin)
                }
                val distanceBox = distance.toDouble().takeUnless {
                    !reversed && it >= effectiveRenderDistanceBlocks
                }
                if (reversed) {
                    return IrisShadowCullingVolume.reversed(
                        camera = camera,
                        coordinateOrigin = coordinateOrigin,
                        playerView = playerView,
                        playerProjection = playerProjection,
                        shadowLightDirection = shadowLightDirection,
                        innerDistance = distance.toDouble(),
                        outerDistance = directives.distance * multiplier.toDouble(),
                    )
                }
                return IrisShadowCullingVolume.advanced(
                    camera = camera,
                    coordinateOrigin = coordinateOrigin,
                    playerView = playerView,
                    playerProjection = playerProjection,
                    shadowLightDirection = shadowLightDirection,
                    distance = distanceBox,
                )
            }

            val terrain = volume(directives.distanceRenderMultiplier)
            val distinctEntities =
                directives.entityShadowDistanceMultiplier != 1.0f &&
                    directives.entityShadowDistanceMultiplier >= 0.0f
            val entityMultiplier =
                directives.distanceRenderMultiplier * directives.entityShadowDistanceMultiplier
            val entities = if (distinctEntities) volume(entityMultiplier) else terrain
            return IrisShadowCullingSet(
                terrain = terrain,
                entities = entities,
                blockEntityDistance = if (distinctEntities) {
                    directives.distance * entityMultiplier.toDouble()
                } else {
                    null
                },
                camera = camera,
            )
        }
    }
}

internal fun irisShadowLightDirectionWorld(
    sunAngle: Float,
    sunPathRotation: Float,
): Vec3f {
    val skyAngle = (sunAngle + 0.75f) % 1.0f
    val transform = MMat4f().apply {
        rotateYAssign(-90.0f * DEGREES_TO_RADIANS)
        rotateZAssign(sunPathRotation * DEGREES_TO_RADIANS)
        rotateXAssign(skyAngle * 360.0f * DEGREES_TO_RADIANS)
    }.unsafe
    val celestialY = if (sunAngle <= 0.5f) 1.0f else -1.0f
    return (transform * Vec4f(0.0f, celestialY, 0.0f, 0.0f)).xyz.normalize()
}

private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
