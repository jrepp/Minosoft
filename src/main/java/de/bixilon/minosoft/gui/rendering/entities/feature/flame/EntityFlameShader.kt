/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.flame

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.gui.rendering.camera.fog.FogManager
import de.bixilon.minosoft.gui.rendering.shader.SceneProgramFamily
import de.bixilon.minosoft.gui.rendering.shader.SceneShaderContract
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import de.bixilon.minosoft.gui.rendering.shader.Shader
import de.bixilon.minosoft.gui.rendering.shader.types.FogShader
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.shader.types.ViewProjectionShader
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager

class EntityFlameShader(
    native: NativeShader,
) : Shader(native), TextureShader, ViewProjectionShader, FogShader {
    override val sceneContract = SceneShaderContract(
        SceneProgramFamily.ENTITY,
        SceneVertexAbi.POSITION_TEXTURE,
        SceneStateAbi.ENTITY_FLAME,
    )
    override var textures: TextureManager by textureManager()
    override var viewProjectionMatrix: Mat4f by viewProjectionMatrix()
    override var cameraPosition: Vec3f by cameraPosition()
    override var fog: FogManager by fog()
    var matrix: Mat4f by uniform("uMatrix", Mat4f())
}
