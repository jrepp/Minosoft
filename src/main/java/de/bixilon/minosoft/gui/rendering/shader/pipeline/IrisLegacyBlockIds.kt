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

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * Iris 1.7.2's exact fallback table when a shader pack omits
 * `block.properties`. A present file, including an empty one, replaces this
 * table completely.
 *
 * The negative emerald value is intentional: the pinned Iris bytecode pushes
 * byte `0x85` as the signed integer `-123`.
 */
object IrisLegacyBlockIds {
    private val colors = listOf(
        "white",
        "orange",
        "magenta",
        "light_blue",
        "yellow",
        "lime",
        "pink",
        "gray",
        "light_gray",
        "cyan",
        "purple",
        "blue",
        "brown",
        "green",
        "red",
        "black",
    )
    private val woodTypes = listOf(
        "oak",
        "birch",
        "jungle",
        "spruce",
        "acacia",
        "dark_oak",
    )

    val RULES: List<IrisBlockIdRule> = buildList {
        add(1, "stone", "granite", "diorite", "andesite")
        add(2, "grass_block")
        add(4, "cobblestone")
        add(50, "torch")
        add(89, "glowstone")
        add(124, "redstone_lamp")
        add(12, "sand")
        add(24, "sandstone")
        add(41, "gold_block")
        add(42, "iron_block")
        add(57, "diamond_block")
        add(-123, "emerald_block")
        add(35, *colors.map { "${it}_wool" }.toTypedArray())
        add(9, "water")
        add(11, "lava")
        add(79, "ice")
        add(18, *woodTypes.map { "${it}_leaves" }.toTypedArray())
        add(95, *colors.map { "${it}_stained_glass" }.toTypedArray())
        add(160, *colors.map { "${it}_stained_glass_pane" }.toTypedArray())
        add(31, "grass", "seagrass", "sweet_berry_bush")
        add(59, "wheat", "carrots", "potatoes")
        add(
            37,
            "dandelion",
            "poppy",
            "blue_orchid",
            "allium",
            "azure_bluet",
            "red_tulip",
            "pink_tulip",
            "white_tulip",
            "orange_tulip",
            "oxeye_daisy",
            "cornflower",
            "lily_of_the_valley",
            "wither_rose",
        )
        add(
            175,
            "sunflower",
            "lilac",
            "tall_grass",
            "large_fern",
            "rose_bush",
            "peony",
            "tall_seagrass",
        )
        add(51, "fire")
        add(111, "lily_pad")
    }

    private fun MutableList<IrisBlockIdRule>.add(id: Int, vararg paths: String) {
        paths.forEach { path ->
            add(IrisBlockIdRule(id, ResourceLocation("minecraft", path)))
        }
    }
}
