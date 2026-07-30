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

package de.bixilon.minosoft.gui.rendering.skeletal.shader

import de.bixilon.minosoft.gui.rendering.light.LightmapBuffer
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.types.LightShader
import de.bixilon.minosoft.gui.rendering.shader.types.PlayerLightShader
import de.bixilon.minosoft.gui.rendering.system.base.buffer.uniform.FloatUniformBuffer
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader

class LightmapSkeletalShader(native: NativeShader, buffer: FloatUniformBuffer) : BaseSkeletalShader(native, buffer), LightShader, PlayerLightShader {
    override val sceneContract = SceneShaderContract(
        SceneProgramFamily.ENTITY,
        SceneVertexAbi.SKELETAL,
        SceneStateAbi.SKELETAL_LIGHTMAP,
    )
    var light by uniform("uLight", 0xFF, NativeShader::setUInt) // TODO: LightLevel
    override val lightmap: LightmapBuffer by lightmap()
    override var playerLightPosition by playerLightPosition()
    override var playerLightIntensity by playerLightIntensity()
    override var playerLightRadius by playerLightRadius()
}
