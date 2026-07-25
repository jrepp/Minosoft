/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.elements.grid

import kotlin.math.ceil
import kotlin.math.floor

data class GridCellRange(
    val first: Int,
    val lastExclusive: Int,
) {
    val isEmpty: Boolean get() = first >= lastExclusive
}

class VirtualGridState(
    columns: Int,
    cellHeight: Float,
) {
    var columns: Int = columns
        set(value) {
            require(value in 1..MAX_COLUMNS) { "Grid columns must be in 1..$MAX_COLUMNS." }
            field = value
        }
    var cellHeight: Float = cellHeight
        set(value) {
            require(value.isFinite() && value > 0.0f) { "Grid cell height must be finite and positive." }
            field = value
        }

    init {
        this.columns = columns
        this.cellHeight = cellHeight
    }

    fun rowCount(itemCount: Int): Int {
        require(itemCount >= 0) { "Item count must be non-negative." }
        return if (itemCount == 0) 0 else 1 + (itemCount - 1) / columns
    }

    fun contentHeight(itemCount: Int): Float = rowCount(itemCount) * cellHeight

    fun visibleItems(itemCount: Int, scrollOffset: Float, viewportHeight: Float): GridCellRange {
        require(itemCount >= 0) { "Item count must be non-negative." }
        require(scrollOffset.isFinite() && scrollOffset >= 0.0f) { "Scroll offset must be finite and non-negative." }
        require(viewportHeight.isFinite() && viewportHeight >= 0.0f) { "Viewport height must be finite and non-negative." }
        if (itemCount == 0 || viewportHeight == 0.0f) return GridCellRange(0, 0)
        val firstRow = floor(scrollOffset / cellHeight).toInt().coerceAtLeast(0)
        val lastRow = ceil((scrollOffset + viewportHeight) / cellHeight).toInt().coerceAtMost(rowCount(itemCount))
        return GridCellRange(
            first = (firstRow * columns).coerceAtMost(itemCount),
            lastExclusive = (lastRow * columns).coerceAtMost(itemCount),
        )
    }

    fun indexAt(x: Float, y: Float, cellWidth: Float, scrollOffset: Float, itemCount: Int): Int? {
        if (!x.isFinite() || !y.isFinite() || !cellWidth.isFinite() || !scrollOffset.isFinite()) return null
        if (x < 0.0f || y < 0.0f || cellWidth <= 0.0f || scrollOffset < 0.0f) return null
        val column = floor(x / cellWidth).toInt()
        if (column !in 0 until columns) return null
        val row = floor((y + scrollOffset) / cellHeight).toInt()
        val index = row * columns + column
        return index.takeIf { it in 0 until itemCount }
    }

    companion object {
        const val MAX_COLUMNS = 4_096
    }
}
