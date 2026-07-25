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

package de.bixilon.minosoft.gui.rendering.gui.mesh

import de.bixilon.kmath.vec.vec2.f.Vec2f

data class GuiClipVertex(
    val x: Float,
    val y: Float,
    val u: Float,
    val v: Float,
) {
    fun interpolate(other: GuiClipVertex, progress: Float): GuiClipVertex {
        return GuiClipVertex(
            x = x + (other.x - x) * progress,
            y = y + (other.y - y) * progress,
            u = u + (other.u - u) * progress,
            v = v + (other.v - v) * progress,
        )
    }
}

data class GuiClipRect(
    val start: Vec2f,
    val end: Vec2f,
) {
    init {
        require(start.x.isFinite() && start.y.isFinite() && end.x.isFinite() && end.y.isFinite()) {
            "GUI clip bounds must be finite."
        }
        require(end.x >= start.x && end.y >= start.y) { "GUI clip end must not precede its start." }
    }

    fun clip(vertices: List<GuiClipVertex>): List<GuiClipVertex> {
        if (vertices.isEmpty() || start.x == end.x || start.y == end.y) return emptyList()
        if (vertices.any { !it.x.isFinite() || !it.y.isFinite() || !it.u.isFinite() || !it.v.isFinite() }) return emptyList()
        var output = vertices
        output = clipEdge(output, { it.x >= start.x }) { first, second ->
            first.interpolate(second, (start.x - first.x) / (second.x - first.x))
        }
        output = clipEdge(output, { it.x <= end.x }) { first, second ->
            first.interpolate(second, (end.x - first.x) / (second.x - first.x))
        }
        output = clipEdge(output, { it.y >= start.y }) { first, second ->
            first.interpolate(second, (start.y - first.y) / (second.y - first.y))
        }
        return clipEdge(output, { it.y <= end.y }) { first, second ->
            first.interpolate(second, (end.y - first.y) / (second.y - first.y))
        }
    }

    private fun clipEdge(
        input: List<GuiClipVertex>,
        inside: (GuiClipVertex) -> Boolean,
        intersection: (GuiClipVertex, GuiClipVertex) -> GuiClipVertex,
    ): List<GuiClipVertex> {
        if (input.isEmpty()) return input
        val output = ArrayList<GuiClipVertex>(input.size + 1)
        var previous = input.last()
        var previousInside = inside(previous)
        for (current in input) {
            val currentInside = inside(current)
            if (currentInside != previousInside) output += intersection(previous, current)
            if (currentInside) output += current
            previous = current
            previousInside = currentInside
        }
        return output
    }
}
