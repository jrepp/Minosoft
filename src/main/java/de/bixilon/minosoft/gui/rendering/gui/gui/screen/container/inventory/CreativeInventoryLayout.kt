/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.kmath.vec.vec2.f.Vec2f
import kotlin.math.min

/**
 * Keeps the compact inventory layout intact while assigning a proportional
 * portion of larger viewports to the Creative catalog.
 */
object CreativeInventoryLayout {
    val COMPACT_BODY_SIZE = Vec2f(170.0f, 166.0f)
    private val MAX_BODY_SIZE = Vec2f(340.0f, 330.0f)
    private const val VIEWPORT_WIDTH_SHARE = 0.42f
    private const val VIEWPORT_HEIGHT_SHARE = 0.72f
    private const val OUTER_MARGIN = 12.0f
    const val INVENTORY_GAP = 4.0f

    fun bodySize(viewport: Vec2f, inventory: Vec2f): Vec2f {
        if (!viewport.x.isFinite() || !viewport.y.isFinite()) return COMPACT_BODY_SIZE
        val availableWidth = viewport.x - inventory.x - INVENTORY_GAP - OUTER_MARGIN * 2.0f
        val availableHeight = viewport.y - CreativeInventoryCatalogElement.TAB_HEIGHT - OUTER_MARGIN * 2.0f
        return Vec2f(
            min(viewport.x * VIEWPORT_WIDTH_SHARE, min(availableWidth, MAX_BODY_SIZE.x))
                .coerceAtLeast(COMPACT_BODY_SIZE.x),
            min(viewport.y * VIEWPORT_HEIGHT_SHARE, min(availableHeight, MAX_BODY_SIZE.y))
                .coerceAtLeast(COMPACT_BODY_SIZE.y),
        )
    }

    fun containerOffset(
        viewport: Vec2f,
        inventory: Vec2f,
        catalog: Vec2f,
    ): Vec2f {
        val groupWidth = catalog.x + INVENTORY_GAP + inventory.x
        val bodyHeight = catalog.y - CreativeInventoryCatalogElement.TAB_HEIGHT
        val groupHeight = CreativeInventoryCatalogElement.TAB_HEIGHT + maxOf(inventory.y, bodyHeight)
        return Vec2f(
            (viewport.x - groupWidth) / 2.0f + catalog.x + INVENTORY_GAP,
            (viewport.y - groupHeight) / 2.0f + CreativeInventoryCatalogElement.TAB_HEIGHT,
        )
    }
}
