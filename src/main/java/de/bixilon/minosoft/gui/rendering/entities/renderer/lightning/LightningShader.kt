/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.lightning

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.types.ViewProjectionShader
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader

class LightningShader(native: NativeShader) : Shader(native), ViewProjectionShader {
    override val sceneContract = SceneShaderContract(
        SceneProgramFamily.LIGHTNING,
        SceneVertexAbi.POSITION_COLOR,
        SceneStateAbi.LIGHTNING,
    )
    override var viewProjectionMatrix: Mat4f by viewProjectionMatrix()
    var matrix: Mat4f by uniform("uMatrix", Mat4f())
}
