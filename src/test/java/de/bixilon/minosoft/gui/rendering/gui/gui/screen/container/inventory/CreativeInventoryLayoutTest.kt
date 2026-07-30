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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreativeInventoryLayoutTest {

    @Test
    fun `compact viewports retain the established catalog dimensions`() {
        val size = CreativeInventoryLayout.bodySize(Vec2f(320.0f, 200.0f), Vec2f(176.0f, 166.0f))

        assertEquals(CreativeInventoryLayout.COMPACT_BODY_SIZE, size)
    }

    @Test
    fun `large viewports allocate proportional catalog space within caps`() {
        val size = CreativeInventoryLayout.bodySize(Vec2f(800.0f, 480.0f), Vec2f(176.0f, 166.0f))

        assertEquals(336.0f, size.x)
        assertEquals(330.0f, size.y)
    }

    @Test
    fun `creative catalog and inventory are centered as one workspace`() {
        val viewport = Vec2f(800.0f, 480.0f)
        val inventory = Vec2f(176.0f, 166.0f)
        val catalog = Vec2f(336.0f, 348.0f)
        val inventoryOffset = CreativeInventoryLayout.containerOffset(viewport, inventory, catalog)
        val groupLeft = inventoryOffset.x - CreativeInventoryLayout.INVENTORY_GAP - catalog.x
        val groupRight = inventoryOffset.x + inventory.x
        val groupTop = inventoryOffset.y - CreativeInventoryCatalogElement.TAB_HEIGHT
        val groupBottom = maxOf(inventoryOffset.y + inventory.y, groupTop + catalog.y)

        assertTrue(kotlin.math.abs(groupLeft - (viewport.x - groupRight)) < 0.001f)
        assertTrue(kotlin.math.abs(groupTop - (viewport.y - groupBottom)) < 0.001f)
    }
}
