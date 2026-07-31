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

package de.bixilon.minosoft.terrain.model.page

data class TerrainAbsoluteBlockPosition(
    val x: Long,
    val y: Long,
    val z: Long,
) {
    fun offset(deltaX: Long, deltaY: Long, deltaZ: Long): TerrainAbsoluteBlockPosition =
        TerrainAbsoluteBlockPosition(
            Math.addExact(x, deltaX),
            Math.addExact(y, deltaY),
            Math.addExact(z, deltaZ),
        )

    fun containingPage(extent: TerrainPageExtent): TerrainPageOrigin = TerrainPageOrigin(
        TerrainAbsoluteBlockPosition(
            floorAligned(x, extent.x),
            floorAligned(y, extent.y),
            floorAligned(z, extent.z),
        ),
    )

    private fun floorAligned(coordinate: Long, extent: Long): Long =
        Math.multiplyExact(Math.floorDiv(coordinate, extent), extent)
}

data class TerrainPageExtent(
    val x: Long,
    val y: Long,
    val z: Long,
) {
    init {
        require(x > 0L && y > 0L && z > 0L) { "Terrain page extents must be positive" }
    }
}

data class TerrainPageOrigin(
    val blocks: TerrainAbsoluteBlockPosition,
)

data class TerrainPageRelativePosition(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Terrain page-relative position must be finite"
        }
    }

    fun requireWithin(bounds: TerrainPageRelativeBounds): TerrainPageRelativePosition {
        require(this in bounds) { "Terrain page-relative position is outside the physical layout bounds" }
        return this
    }
}

data class TerrainPageRelativeBounds(
    val minimum: TerrainPageRelativePosition,
    val maximum: TerrainPageRelativePosition,
) {
    init {
        require(
            minimum.x <= maximum.x &&
                minimum.y <= maximum.y &&
                minimum.z <= maximum.z,
        ) { "Terrain page-relative bounds must not be inverted" }
    }

    operator fun contains(position: TerrainPageRelativePosition): Boolean =
        position.x in minimum.x..maximum.x &&
            position.y in minimum.y..maximum.y &&
            position.z in minimum.z..maximum.z
}

data class TerrainCameraPosition(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Terrain camera position must be finite"
        }
    }
}

data class TerrainRenderOrigin(
    val blocks: TerrainAbsoluteBlockPosition,
)

data class TerrainFramePosition(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Terrain frame-relative position must be finite"
        }
    }
}

class TerrainFrameTransform(
    val camera: TerrainCameraPosition,
    val renderOrigin: TerrainRenderOrigin,
) {
    val cameraRelativeToRenderOrigin: TerrainFramePosition = TerrainFramePosition(
        checkedFloat(camera.x - renderOrigin.blocks.x.toDouble(), "camera x"),
        checkedFloat(camera.y - renderOrigin.blocks.y.toDouble(), "camera y"),
        checkedFloat(camera.z - renderOrigin.blocks.z.toDouble(), "camera z"),
    )

    fun page(pageOrigin: TerrainPageOrigin): TerrainPageFrameTransform =
        TerrainPageFrameTransform(
            pageOrigin,
            TerrainFramePosition(
                checkedFloat(
                    Math.subtractExact(pageOrigin.blocks.x, renderOrigin.blocks.x).toDouble(),
                    "page-origin x",
                ),
                checkedFloat(
                    Math.subtractExact(pageOrigin.blocks.y, renderOrigin.blocks.y).toDouble(),
                    "page-origin y",
                ),
                checkedFloat(
                    Math.subtractExact(pageOrigin.blocks.z, renderOrigin.blocks.z).toDouble(),
                    "page-origin z",
                ),
            ),
        )
}

data class TerrainPageFrameTransform(
    val pageOrigin: TerrainPageOrigin,
    val pageOriginRelativeToRenderOrigin: TerrainFramePosition,
) {
    fun position(
        pageRelative: TerrainPageRelativePosition,
        bounds: TerrainPageRelativeBounds,
    ): TerrainFramePosition {
        pageRelative.requireWithin(bounds)
        return TerrainFramePosition(
            checkedFloat(pageOriginRelativeToRenderOrigin.x.toDouble() + pageRelative.x, "vertex x"),
            checkedFloat(pageOriginRelativeToRenderOrigin.y.toDouble() + pageRelative.y, "vertex y"),
            checkedFloat(pageOriginRelativeToRenderOrigin.z.toDouble() + pageRelative.z, "vertex z"),
        )
    }
}

private fun checkedFloat(value: Double, component: String): Float {
    require(value.isFinite() && value >= -Float.MAX_VALUE.toDouble() && value <= Float.MAX_VALUE.toDouble()) {
        "Terrain $component is outside the finite Float range"
    }
    return value.toFloat()
}

enum class TerrainClipSpaceDepthRange {
    NEGATIVE_ONE_TO_ONE,
}

enum class TerrainProjectionDepthDirection {
    FORWARD,
}

enum class TerrainDepthCompare {
    LESS,
    LESS_OR_EQUAL,
}

enum class TerrainDistantDepthSnapshotSemantics {
    NONE,
    BEFORE_DISTANT_TRANSLUCENCY,
}

data class TerrainDepthPassDeclaration(
    val compare: TerrainDepthCompare,
    val writesDepth: Boolean,
)

/**
 * The initial production depth contract. A reversed-depth implementation must
 * be introduced as a different complete pipeline convention.
 */
data object TerrainForwardOpenGlDepthConvention {
    val clipSpaceDepthRange: TerrainClipSpaceDepthRange =
        TerrainClipSpaceDepthRange.NEGATIVE_ONE_TO_ONE
    val projectionDirection: TerrainProjectionDepthDirection =
        TerrainProjectionDepthDirection.FORWARD
    const val nearClipDepth: Double = -1.0
    const val farClipDepth: Double = 1.0
    const val supportsReversedDepth: Boolean = false
}
