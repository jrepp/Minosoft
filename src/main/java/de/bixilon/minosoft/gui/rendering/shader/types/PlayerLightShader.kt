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

package de.bixilon.minosoft.gui.rendering.shader.types

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.gui.rendering.light.PlayerLightFalloff
import de.bixilon.minosoft.gui.rendering.shader.AbstractShader
import de.bixilon.minosoft.gui.rendering.shader.uniform.primitive.FloatShaderUniform
import de.bixilon.minosoft.gui.rendering.shader.uniform.vec.Vec3fShaderUniform

interface PlayerLightShader : AbstractShader {
    var playerLightPosition: Vec3f
    var playerLightIntensity: Float
    var playerLightRadius: Float

    fun playerLightPosition(): Vec3fShaderUniform {
        return uniform(Vec3fShaderUniform(this, Vec3f(), "uPlayerLightPosition"))
    }

    fun playerLightIntensity(): FloatShaderUniform {
        val default = native.context.session.profiles.rendering.light.playerLightIntensity
        return uniform(FloatShaderUniform(this, default, "uPlayerLightIntensity"))
    }

    fun playerLightRadius(): FloatShaderUniform {
        return uniform(FloatShaderUniform(this, PlayerLightFalloff.RADIUS, "uPlayerLightRadius"))
    }
}
