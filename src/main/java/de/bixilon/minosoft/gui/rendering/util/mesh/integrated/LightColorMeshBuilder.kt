/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.util.mesh.integrated

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct

class LightColorMeshBuilder(
    context: RenderContext,
    estimate: Int = 100,
) : QuadMeshBuilder(context, LightColorMeshStruct, estimate) {

    fun addVertex(position: Vec3f, color: RGBAColor, light: LightLevel, normal: Vec3f) {
        data.add(
            position.x,
            position.y,
            position.z,
            color.rgba.buffer(),
            light.index.buffer(),
        )
        data.add(normal.x, normal.y, normal.z)
    }

    data class LightColorMeshStruct(
        val position: Vec3f,
        val color: RGBColor,
        val light: Int,
        val normal: Vec3f,
    ) {
        companion object : MeshStruct(LightColorMeshStruct::class)
    }
}
