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

package de.bixilon.minosoft.gui.rendering.chunk.entities

import de.bixilon.minosoft.data.entities.block.BlockEntity
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable

interface BlockEntityRenderer : Drawable {
    val entity: BlockEntity
    val hasTranslucentPass: Boolean get() = false
    val castsTranslucentShadow: Boolean get() = false

    fun load()
    fun unload()

    fun drop()

    fun drawTranslucent() = Unit

    /**
     * Completes every shadow-casting block-entity layer before Iris snapshots
     * the opaque/entity shadow depth. Most renderers have only their base draw.
     * Renderers whose retained model splits translucent/additive layers may
     * opt those layers into this same block-entity submission. Beacon beams
     * deliberately do not opt in, matching pinned Iris's shadow cancellation.
     */
    fun drawShadow() {
        draw()
        if (castsTranslucentShadow) drawTranslucent()
    }

    fun update(light: LightLevel) = Unit
}
