/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature

import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable

interface FeatureDrawable : Drawable, Comparable<FeatureDrawable> {
    val layer: EntityLayer get() = EntityLayer.Opaque
    val additionalLayers: Set<EntityLayer> get() = emptySet()
    val castsShadow: Boolean get() = false
    val priority: Int get() = 0
    val sort: Int // sorting purposes
    /**
     * Stable final ordering for otherwise equivalent drawables.
     *
     * Entity preparation runs in parallel, so collection order must not decide
     * which equal-depth fragment wins at model-part intersections.
     */
    val stableOrder: Long get() = 0L
    val distance2: Double

    fun prepare() = Unit

    /**
     * Draws only the geometry owned by [layer]. Most features own one layer;
     * composite model features can expose secondary translucent geometry
     * without replaying their opaque base mesh.
     */
    fun drawLayer(layer: EntityLayer) {
        if (layer == this.layer) draw()
    }

    override fun compareTo(other: FeatureDrawable): Int {
        var compare = priority.compareTo(other.priority)
        if (compare != 0) return compare

        compare = sort.compareTo(other.sort)
        if (compare != 0) return compare

        return 0
    }
}
