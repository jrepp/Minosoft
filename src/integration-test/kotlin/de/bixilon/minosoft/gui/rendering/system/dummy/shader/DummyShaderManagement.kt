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

package de.bixilon.minosoft.gui.rendering.system.dummy.shader

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.system.base.shader.ShaderManagement
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShaderSource
import de.bixilon.minosoft.gui.rendering.system.dummy.DummyRenderSystem

class DummyShaderManagement(val system: DummyRenderSystem) : ShaderManagement {
    private val shaders: MutableSet<Shader> = mutableSetOf()

    override var shader: Shader? = null
        set(value) {
            if (value?.native === field?.native) return

            if (value == null) {
                field = null
                return
            }
            val native = value.native

            assert(native is DummyNativeShader)

            field = value
        }


    override fun create(vertex: ResourceLocation, geometry: ResourceLocation?, fragment: ResourceLocation): DummyNativeShader {
        return DummyNativeShader(system.context)
    }

    override fun create(vertex: NativeShaderSource, geometry: NativeShaderSource?, fragment: NativeShaderSource): DummyNativeShader {
        return DummyNativeShader(system.context)
    }

    override fun createGraphics(
        vertex: ResourceLocation,
        tessellationControl: ResourceLocation?,
        tessellationEvaluation: ResourceLocation?,
        geometry: ResourceLocation?,
        fragment: ResourceLocation,
        patchVertices: Int?,
    ): DummyNativeShader {
        validateTessellation(tessellationControl, tessellationEvaluation, patchVertices)
        return DummyNativeShader(system.context)
    }

    override fun createGraphics(
        vertex: NativeShaderSource,
        tessellationControl: NativeShaderSource?,
        tessellationEvaluation: NativeShaderSource?,
        geometry: NativeShaderSource?,
        fragment: NativeShaderSource,
        patchVertices: Int?,
    ): DummyNativeShader {
        validateTessellation(tessellationControl, tessellationEvaluation, patchVertices)
        return DummyNativeShader(system.context)
    }

    override fun plusAssign(shader: Shader) {
        this.shaders += shader
    }

    override fun minusAssign(shader: Shader) {
        this.shaders -= shader
    }

    override fun iterator() = shaders.iterator()

    private fun validateTessellation(control: Any?, evaluation: Any?, patchVertices: Int?) {
        require((control == null) == (evaluation == null)) {
            "Tessellation control and evaluation stages must be provided together"
        }
        require((control == null) == (patchVertices == null)) {
            "Tessellation stages require an explicit patch vertex count"
        }
        require(patchVertices == null || patchVertices > 0) {
            "Tessellation patch vertex count must be positive"
        }
    }
}
