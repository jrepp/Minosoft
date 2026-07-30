/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.chunk.entities

import de.bixilon.minosoft.data.entities.block.BlockEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockEntityRendererTest {
    @Test
    fun `translucent main pass does not imply a translucent shadow`() {
        val draws = mutableListOf<String>()
        val renderer = renderer(
            draws = draws,
            hasTranslucentPass = true,
            castsTranslucentShadow = false,
        )

        renderer.drawShadow()

        assertEquals(listOf("base"), draws)
    }

    @Test
    fun `opted in translucent shadow remains inside the base block entity submission`() {
        val draws = mutableListOf<String>()
        val renderer = renderer(
            draws = draws,
            hasTranslucentPass = true,
            castsTranslucentShadow = true,
        )

        renderer.drawShadow()

        assertEquals(listOf("base", "translucent"), draws)
    }

    private fun renderer(
        draws: MutableList<String>,
        hasTranslucentPass: Boolean,
        castsTranslucentShadow: Boolean,
    ) = object : BlockEntityRenderer {
        override val entity: BlockEntity
            get() = error("The draw-order contract does not access block-entity state")
        override val hasTranslucentPass = hasTranslucentPass
        override val castsTranslucentShadow = castsTranslucentShadow

        override fun load() = Unit
        override fun unload() = Unit
        override fun drop() = Unit
        override fun draw() {
            draws += "base"
        }

        override fun drawTranslucent() {
            draws += "translucent"
        }
    }
}
