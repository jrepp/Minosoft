/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.beacon

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.shader.types.ViewProjectionShader
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager

class BeaconBeamShader(native: NativeShader) : Shader(native), TextureShader, ViewProjectionShader {
    override val sceneContract = SceneShaderContract(
        SceneProgramFamily.BEACON_BEAM,
        SceneVertexAbi.POSITION_TEXTURE,
        SceneStateAbi.BEACON_BEAM,
    )
    override var textures: TextureManager by textureManager()
    override var viewProjectionMatrix: Mat4f by viewProjectionMatrix()
    var matrix: Mat4f by uniform("uMatrix", Mat4f())
    var textureOffset by uniform("uTextureOffset", 0.0f)
}
