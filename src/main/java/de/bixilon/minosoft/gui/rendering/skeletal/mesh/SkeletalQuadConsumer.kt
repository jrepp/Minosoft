/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.mesh

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray

/**
 * Neutral consumer boundary shared by GPU skeletal meshes and CPU GUI
 * previews. Preview collection therefore observes the exact geometry and UVs
 * emitted by the production skeletal bake without owning another renderer.
 */
fun interface SkeletalQuadConsumer {
    fun addQuad(
        positions: FaceVertexData,
        uv: UnpackedUVArray,
        transform: Int,
        normal: Vec3f,
        texture: ShaderTexture,
        path: String,
    )
}
