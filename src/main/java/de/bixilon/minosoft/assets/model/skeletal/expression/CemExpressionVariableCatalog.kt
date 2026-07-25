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

package de.bixilon.minosoft.assets.model.skeletal.expression

/**
 * EMF 3.0.17's public OptiFine-expression input names.
 *
 * This catalog is deliberately independent of entity and renderer classes so
 * parsers, headless tests, and renderers can share the same compatibility
 * boundary. A known value that a caller cannot currently observe resolves to
 * its safe zero/false default; unknown names continue to fail explicitly.
 */
object CemExpressionVariableCatalog {
    val FLOATS = setOf(
        "limb_swing",
        "frame_time",
        "limb_speed",
        "age",
        "head_pitch",
        "head_yaw",
        "swing_progress",
        "hurt_time",
        "dimension",
        "time",
        "player_pos_x",
        "player_pos_y",
        "player_pos_z",
        "pos_x",
        "pos_y",
        "pos_z",
        "player_rot_x",
        "player_rot_y",
        "rot_x",
        "rot_y",
        "health",
        "death_time",
        "anger_time",
        "max_health",
        "id",
        "day_time",
        "day_count",
        "rule_index",
        "anger_time_start",
        "move_forward",
        "move_strafing",
        "height_above_ground",
        "fluid_depth",
        "fluid_depth_down",
        "fluid_depth_up",
        "nan",
        "distance",
        "frame_counter",
    )

    val BOOLEANS = setOf(
        "is_hovered",
        "is_paused",
        "is_first_person_hand",
        "is_right_handed",
        "is_swinging_right_arm",
        "is_swinging_left_arm",
        "is_holding_item_right",
        "is_holding_item_left",
        "is_using_item",
        "is_swimming",
        "is_gliding",
        "is_blocking",
        "is_crawling",
        "is_climbing",
        "is_child",
        "is_in_water",
        "is_riding",
        "is_on_ground",
        "is_burning",
        "is_alive",
        "is_glowing",
        "is_aggressive",
        "is_hurt",
        "is_in_hand",
        "is_in_item_frame",
        "is_in_ground",
        "is_in_gui",
        "is_in_lava",
        "is_invisible",
        "is_on_head",
        "is_on_shoulder",
        "is_ridden",
        "is_sitting",
        "is_sneaking",
        "is_sprinting",
        "is_tamed",
        "is_wet",
        "is_jumping",
    )

    val ALIASES = mapOf(
        "is_agressive" to "is_aggressive",
        "is_aggresive" to "is_aggressive",
        "is_agresive" to "is_aggressive",
        "is_riden" to "is_ridden",
        "frame_count" to "frame_counter",
    )

    fun canonicalName(name: String): String {
        val normalized = name.lowercase()
        return ALIASES[normalized] ?: normalized
    }

    fun context(
        variables: Map<String, Double> = emptyMap(),
        variableResolver: (String) -> Double? = { null },
        rawFunctionResolver: (String, List<String>) -> Double? = { _, _ -> null },
        random: () -> Double = { 0.0 },
    ): SkeletalExpressionContext {
        return SkeletalExpressionContext(
            variableResolver = { raw ->
                val name = canonicalName(raw)
                variables[name]
                    ?: variables[raw]
                    ?: variableResolver(name)
                    ?: default(name)
            },
            rawFunctionResolver = rawFunctionResolver,
            random = random,
        )
    }

    private fun default(name: String): Double? = when {
        name == "nan" -> Double.NaN
        name in FLOATS || name in BOOLEANS -> 0.0
        else -> null
    }
}
