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

package de.bixilon.minosoft.gui.rendering.shader.uniform

import de.bixilon.minosoft.gui.rendering.shader.AbstractShader
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader

abstract class ShaderUniform(
    protected val shader: AbstractShader,
    val name: String,
) {

    fun upload() {
        val owner = shader as? Shader
        val revision = owner?.beginUniformUpload()
        var target: NativeShader? = null
        var completed = false
        try {
            shader.use()
            // Shader-pack scene selection happens in use(). A preceding draw
            // may have left a binding for another host shader.
            target = shader.uniformTarget()
            if (shader.acceptsUniform(name) && target.hasUniform(name)) uploadTo(target)
            completed = true
        } finally {
            if (revision != null) owner.finishUniformUpload(target.takeIf { completed }, revision)
        }
    }

    abstract fun uploadTo(target: NativeShader)
}
