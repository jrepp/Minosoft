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

package de.bixilon.minosoft.gui.rendering.shader.uniform

import de.bixilon.minosoft.gui.rendering.shader.AbstractShader
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader

abstract class ShaderUniform(
    protected val shader: AbstractShader,
    val name: String,
) {

    fun upload() {
        shader.use()
        // Shader-pack scene selection happens in use(). A preceding draw may
        // have left a binding for another host shader, so checking acceptance
        // before activation can route this upload to an incompatible selected
        // program (notably a shadow variant that optimized the uniform out).
        if (!shader.acceptsUniform(name)) return
        val target = shader.uniformTarget()
        // Source-level scene contracts describe which values a variant may
        // consume. The linked driver program remains authoritative: constant
        // folding can legally remove one of those uniforms.
        if (!target.hasUniform(name)) return
        uploadTo(target)
    }

    abstract fun uploadTo(target: NativeShader)
}
