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

package de.bixilon.minosoft.gui.rendering.system.base.shader

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.util.KUtil.toResourceLocation

interface ShaderManagement : Iterable<Shader> {
    var shader: Shader?

    fun create(vertex: ResourceLocation, geometry: ResourceLocation? = null, fragment: ResourceLocation): NativeShader
    fun create(vertex: NativeShaderSource, geometry: NativeShaderSource? = null, fragment: NativeShaderSource): NativeShader
    fun createGraphics(
        vertex: ResourceLocation,
        tessellationControl: ResourceLocation?,
        tessellationEvaluation: ResourceLocation?,
        geometry: ResourceLocation?,
        fragment: ResourceLocation,
        patchVertices: Int? = null,
    ): NativeShader {
        require((tessellationControl == null) == (tessellationEvaluation == null)) {
            "Tessellation control and evaluation stages must be provided together"
        }
        require((tessellationControl == null) == (patchVertices == null)) {
            "Tessellation stages require an explicit patch vertex count"
        }
        require(tessellationControl == null) {
            "Tessellation shaders are not supported by this rendering backend"
        }
        return create(vertex, geometry, fragment)
    }

    fun createGraphics(
        vertex: NativeShaderSource,
        tessellationControl: NativeShaderSource?,
        tessellationEvaluation: NativeShaderSource?,
        geometry: NativeShaderSource?,
        fragment: NativeShaderSource,
        patchVertices: Int? = null,
    ): NativeShader {
        require((tessellationControl == null) == (tessellationEvaluation == null)) {
            "Tessellation control and evaluation stages must be provided together"
        }
        require((tessellationControl == null) == (patchVertices == null)) {
            "Tessellation stages require an explicit patch vertex count"
        }
        require(tessellationControl == null) {
            "Tessellation shaders are not supported by this rendering backend"
        }
        return create(vertex, geometry, fragment)
    }

    fun createCompute(compute: NativeShaderSource): NativeShader {
        throw UnsupportedOperationException("Compute shaders are not supported by this rendering backend")
    }

    fun create(path: ResourceLocation) = create(
        vertex = "$path.vsh".toResourceLocation(),
        geometry = "$path.gsh".toResourceLocation(),
        fragment = "$path.fsh".toResourceLocation(),
    )


    fun <T : Shader> create(path: ResourceLocation, creator: (native: NativeShader) -> T): T {
        return creator.invoke(create(path))
    }

    operator fun plusAssign(shader: Shader)
    operator fun minusAssign(shader: Shader)


    fun reload() {
        for (shader in this) {
            shader.reload()
        }
    }
}

data class NativeShaderSource(
    val id: ResourceLocation,
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "Shader source must not be blank: $id" }
    }
}
