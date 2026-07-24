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

package de.bixilon.minosoft.gui.rendering.sky

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.world.time.MoonPhases
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3fUtil.interpolateLinear
import kotlin.math.abs

/** Shared night-visibility policy for renderers that visually respond to the moon phase. */
object NightLighting {
    const val MINIMUM_MOON_VISIBILITY = 0.35f
    private val TERRAIN_MAX = Vec3f(0.10f, 0.10f, 0.30f)

    /** Outdoor terrain keeps baseline skylight even when the moon itself is dark. */
    fun terrainLight(brightness: Float, nightProgress: Float): Vec3f {
        return interpolateLinear((abs(nightProgress - 0.6f) + 0.4f), TERRAIN_MAX * 0.1f, TERRAIN_MAX) * brightness
    }

    fun moonVisibility(moon: MoonPhases): Float {
        return MINIMUM_MOON_VISIBILITY + moon.light * (1.0f - MINIMUM_MOON_VISIBILITY)
    }
}
