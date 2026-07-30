/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.camera.arm

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.chunk.mesh.BlockVertexConsumer
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureTransparencies
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray

/**
 * Retains the material boundary that Iris uses to distinguish first-person
 * hand geometry from translucent hand/water geometry.
 */
internal class HeldItemMaterialConsumer(
    private val opaque: BlockVertexConsumer,
    private val translucent: BlockVertexConsumer,
) : BlockVertexConsumer {
    override fun ensureSize(primitives: Int) {
        opaque.ensureSize(primitives)
        translucent.ensureSize(primitives)
    }

    override fun addQuad(
        offset: Vec3f,
        positions: FaceVertexData,
        uv: PackedUVArray,
        texture: ShaderTexture,
        light: Int,
        tint: RGBColor,
        ao: IntArray,
    ) {
        val consumer = if (texture.transparency == TextureTransparencies.TRANSLUCENT) {
            translucent
        } else {
            opaque
        }
        consumer.addQuad(offset, positions, uv, texture, light, tint, ao)
    }
}
