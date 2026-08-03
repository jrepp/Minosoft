/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.entities.feature

import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable

interface FeatureDrawable : Drawable {
    val layer: EntityLayer get() = EntityLayer.Opaque
    val additionalLayers: Set<EntityLayer> get() = emptySet()
    val castsShadow: Boolean get() = false
    val priority: Int get() = 0
    val renderStateKey: EntityRenderStateKey
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
}

/** Producer-declared immutable grouping key for opaque and shadow queues. */
data class EntityRenderStateKey(
    val programFamily: String,
    val vertexAbi: String,
    val stateAbi: String,
    val materialLayer: String,
    val meshGroup: String,
) : Comparable<EntityRenderStateKey> {
    private val sortHash = hashCode()

    override fun compareTo(other: EntityRenderStateKey): Int {
        var result = sortHash.compareTo(other.sortHash)
        if (result != 0) return result
        result = programFamily.compareTo(other.programFamily)
        if (result != 0) return result
        result = vertexAbi.compareTo(other.vertexAbi)
        if (result != 0) return result
        result = stateAbi.compareTo(other.stateAbi)
        if (result != 0) return result
        result = materialLayer.compareTo(other.materialLayer)
        if (result != 0) return result
        return meshGroup.compareTo(other.meshGroup)
    }

    companion object {
        val TEST = EntityRenderStateKey("test", "test", "test", "test", "test")
    }
}

object EntityRenderStateKeys {
    val SKELETAL = EntityRenderStateKey("entity", "skeletal", "skeletal-tinted", "base", "skeletal-model")
    val PLAYER = EntityRenderStateKey("entity", "player-skeletal", "player", "player", "player-model")
    val ARMOR_BASE = EntityRenderStateKey("entity", "player-skeletal", "player", "armor-base", "armor")
    val ARMOR_DECORATION = EntityRenderStateKey("armor-glint", "player-skeletal", "player", "armor-decoration", "armor")
    val GECKO_ARMOR = EntityRenderStateKey("entity", "skeletal", "skeletal-tinted", "gecko-armor", "gecko-model")
    val LEASH = EntityRenderStateKey("entity", "line", "line", "leash", "retained-line")
    val SHADOW = EntityRenderStateKey("entity", "textured", "entity-shadow", "shadow", "retained-shadow")
    val HITBOX = EntityRenderStateKey("line", "line", "line", "hitbox", "retained-line")
    val LIGHTNING = EntityRenderStateKey("lightning", "lightning", "lightning", "base", "lightning")
    val FLAME = EntityRenderStateKey("entity", "simple-texture", "entity-flame", "flame", "flame")
    val ITEM_BLOCK = EntityRenderStateKey("entity", "block-feature", "block", "item-block", "item-model")
    val ITEM_SKELETAL = EntityRenderStateKey("entity", "skeletal", "skeletal-tinted", "item-skeletal", "gecko-model")
    val BLOCK = EntityRenderStateKey("block", "block-feature", "block", "base", "block-model")
    val BILLBOARD_TEXT = EntityRenderStateKey("entity", "billboard-text", "billboard-text", "text", "font")
    val TEXT_DISPLAY = EntityRenderStateKey("entity", "billboard-text", "billboard-text", "display-text", "font")
}
