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

package de.bixilon.minosoft.terrain.distant.lighting

/** Directional light propagation detached from Minecraft block objects. */
enum class DistantLightDirection(
    val offsetX: Int,
    val offsetY: Int,
    val offsetZ: Int,
) {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0),
    ;

    val mask: Int get() = 1 shl ordinal

    val opposite: DistantLightDirection
        get() = when (this) {
            DOWN -> UP
            UP -> DOWN
            NORTH -> SOUTH
            SOUTH -> NORTH
            WEST -> EAST
            EAST -> WEST
        }

    companion object {
        const val ALL_MASK: Int = (1 shl 6) - 1
    }
}

/** Immutable primitive-packed light behavior for one detached voxel. */
@JvmInline
value class DistantLightVoxel private constructor(private val packed: Int) {
    constructor(
        emission: Int = 0,
        propagationMask: Int = DistantLightDirection.ALL_MASK,
        skylightEnters: Boolean = true,
        filtersSkylight: Boolean = false,
    ) : this(pack(emission, propagationMask, skylightEnters, filtersSkylight))

    val emission: Int get() = packed and LIGHT_MASK
    val propagationMask: Int get() = packed ushr PROPAGATION_SHIFT and DistantLightDirection.ALL_MASK
    val skylightEnters: Boolean get() = packed and SKY_ENTERS_MASK != 0
    val filtersSkylight: Boolean get() = packed and SKY_FILTERS_MASK != 0

    companion object {
        private fun pack(
            emission: Int,
            propagationMask: Int,
            skylightEnters: Boolean,
            filtersSkylight: Boolean,
        ): Int {
            require(emission in MINIMUM_LIGHT..MAXIMUM_LIGHT) {
                "Distant detached emission must be within $MINIMUM_LIGHT..$MAXIMUM_LIGHT"
            }
            require(propagationMask and DistantLightDirection.ALL_MASK == propagationMask) {
                "Distant detached propagation mask contains unknown directions"
            }
            return emission or
                (propagationMask shl PROPAGATION_SHIFT) or
                (if (skylightEnters) SKY_ENTERS_MASK else 0) or
                (if (filtersSkylight) SKY_FILTERS_MASK else 0)
        }

        private const val LIGHT_MASK = 0x0F
        private const val PROPAGATION_SHIFT = 4
        private const val SKY_ENTERS_MASK = 1 shl 10
        private const val SKY_FILTERS_MASK = 1 shl 11
    }
}

@JvmInline
value class DistantLightSample private constructor(private val packed: Int) {
    constructor(block: Int, sky: Int) : this(pack(block, sky))

    val block: Int get() = packed and LIGHT_MASK
    val sky: Int get() = packed ushr SKY_SHIFT and LIGHT_MASK

    companion object {
        private fun pack(block: Int, sky: Int): Int {
            require(block in MINIMUM_LIGHT..MAXIMUM_LIGHT && sky in MINIMUM_LIGHT..MAXIMUM_LIGHT) {
                "Distant detached light sample must be within $MINIMUM_LIGHT..$MAXIMUM_LIGHT"
            }
            return block or (sky shl SKY_SHIFT)
        }

        private const val LIGHT_MASK = 0x0F
        private const val SKY_SHIFT = 4
    }
}

/** Fixed primitive result; no source voxel or accessor is retained. */
class DistantDetachedLightVolume internal constructor(
    val widthX: Int,
    val minimumY: Int,
    val maximumYExclusive: Int,
    val widthZ: Int,
    private val blockLight: ByteArray,
    private val skyLight: ByteArray,
) {
    val height: Int = maximumYExclusive - minimumY

    operator fun get(x: Int, y: Int, z: Int): DistantLightSample {
        require(x in 0 until widthX && y in minimumY until maximumYExclusive && z in 0 until widthZ) {
            "Distant detached light coordinate is outside the volume: $x,$y,$z"
        }
        val index = ((y - minimumY) * widthZ + z) * widthX + x
        return DistantLightSample(blockLight[index].toInt(), skyLight[index].toInt())
    }
}

fun interface DistantLightVoxelAccessor {
    fun sample(x: Int, y: Int, z: Int): DistantLightVoxel
}

/**
 * Deterministic bounded light propagation over a detached voxel volume.
 *
 * Callers provide a halo at least [MAXIMUM_LIGHT] blocks wide around any
 * retained output. No source outside that halo can contribute a non-zero
 * value. Direct sky columns follow the same height-boundary semantics as the
 * live chunk light heightmap; indirect sky and block light lose one level per
 * step and honor directional entry/exit masks.
 */
object DistantDetachedLighting {
    fun calculate(
        widthX: Int,
        minimumY: Int,
        maximumYExclusive: Int,
        widthZ: Int,
        hasSkyLight: Boolean,
        accessor: DistantLightVoxelAccessor,
    ): DistantDetachedLightVolume {
        require(widthX > 0 && widthZ > 0) { "Distant detached light dimensions must be positive" }
        val height = Math.subtractExact(maximumYExclusive, minimumY)
        require(height > 0) { "Distant detached light height must be positive" }
        val horizontalArea = Math.multiplyExact(widthX, widthZ)
        val voxelCount = Math.multiplyExact(horizontalArea, height)
        require(voxelCount <= MAXIMUM_VOXELS) {
            "Distant detached light volume exceeds $MAXIMUM_VOXELS voxels"
        }

        val propagation = ByteArray(voxelCount)
        val skyBehavior = ByteArray(voxelCount)
        val block = ByteArray(voxelCount)
        val sky = ByteArray(voxelCount)
        val blockBuckets = Array(MAXIMUM_LIGHT + 1) { PrimitiveIndexList() }
        for (yIndex in 0 until height) {
            val y = minimumY + yIndex
            for (z in 0 until widthZ) {
                for (x in 0 until widthX) {
                    val index = (yIndex * widthZ + z) * widthX + x
                    val voxel = accessor.sample(x, y, z)
                    propagation[index] = voxel.propagationMask.toByte()
                    skyBehavior[index] = (
                        (if (voxel.skylightEnters) SKY_ENTERS else 0) or
                            (if (voxel.filtersSkylight) SKY_FILTERS else 0)
                        ).toByte()
                    if (voxel.emission > 0) {
                        block[index] = voxel.emission.toByte()
                        blockBuckets[voxel.emission].add(index)
                    }
                }
            }
        }
        propagate(widthX, height, widthZ, propagation, block, blockBuckets)

        if (hasSkyLight) {
            val skyBuckets = Array(MAXIMUM_LIGHT + 1) { PrimitiveIndexList() }
            for (z in 0 until widthZ) {
                for (x in 0 until widthX) {
                    val boundary = skyBoundary(
                        x,
                        z,
                        widthX,
                        height,
                        widthZ,
                        propagation,
                        skyBehavior,
                    )
                    for (yIndex in boundary until height) {
                        val index = (yIndex * widthZ + z) * widthX + x
                        sky[index] = MAXIMUM_LIGHT.toByte()
                        skyBuckets[MAXIMUM_LIGHT].add(index)
                    }
                }
            }
            propagate(widthX, height, widthZ, propagation, sky, skyBuckets)
        }

        return DistantDetachedLightVolume(
            widthX,
            minimumY,
            maximumYExclusive,
            widthZ,
            block,
            sky,
        )
    }

    private fun skyBoundary(
        x: Int,
        z: Int,
        widthX: Int,
        height: Int,
        widthZ: Int,
        propagation: ByteArray,
        skyBehavior: ByteArray,
    ): Int {
        for (yIndex in height - 1 downTo 0) {
            val index = (yIndex * widthZ + z) * widthX + x
            val behavior = skyBehavior[index].toInt()
            if (behavior and SKY_ENTERS == 0) return yIndex + 1
            if (behavior and SKY_FILTERS != 0 ||
                propagation[index].toInt() and DistantLightDirection.DOWN.mask == 0
            ) {
                return yIndex
            }
        }
        return 0
    }

    private fun propagate(
        widthX: Int,
        height: Int,
        widthZ: Int,
        propagation: ByteArray,
        light: ByteArray,
        buckets: Array<PrimitiveIndexList>,
    ) {
        val layerSize = Math.multiplyExact(widthX, widthZ)
        for (level in MAXIMUM_LIGHT downTo 2) {
            val bucket = buckets[level]
            for (entry in 0 until bucket.size) {
                val index = bucket[entry]
                if (light[index].toInt() != level) continue
                val yIndex = index / layerSize
                val remainder = index - yIndex * layerSize
                val z = remainder / widthX
                val x = remainder - z * widthX
                val sourceMask = propagation[index].toInt()
                for (direction in DistantLightDirection.entries) {
                    if (sourceMask and direction.mask == 0) continue
                    val nextX = x + direction.offsetX
                    val nextY = yIndex + direction.offsetY
                    val nextZ = z + direction.offsetZ
                    if (nextX !in 0 until widthX || nextY !in 0 until height || nextZ !in 0 until widthZ) {
                        continue
                    }
                    val nextIndex = (nextY * widthZ + nextZ) * widthX + nextX
                    if (propagation[nextIndex].toInt() and direction.opposite.mask == 0) continue
                    val nextLevel = level - 1
                    if (light[nextIndex].toInt() >= nextLevel) continue
                    light[nextIndex] = nextLevel.toByte()
                    buckets[nextLevel].add(nextIndex)
                }
            }
        }
    }

    private class PrimitiveIndexList(initialCapacity: Int = 16) {
        private var values = IntArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(Math.multiplyExact(values.size, 2))
            values[size++] = value
        }

        operator fun get(index: Int): Int = values[index]
    }

    private const val SKY_ENTERS = 1
    private const val SKY_FILTERS = 2
    private const val MAXIMUM_VOXELS = 4 * 1024 * 1024
}

private const val MINIMUM_LIGHT = 0
private const val MAXIMUM_LIGHT = 15
