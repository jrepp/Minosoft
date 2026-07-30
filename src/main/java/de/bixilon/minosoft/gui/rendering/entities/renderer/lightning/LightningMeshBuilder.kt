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

package de.bixilon.minosoft.gui.rendering.entities.renderer.lightning

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.MeshUtil.buffer
import de.bixilon.minosoft.gui.rendering.util.mesh.builder.quad.QuadMeshBuilder
import de.bixilon.minosoft.gui.rendering.util.mesh.struct.MeshStruct
import kotlin.math.sqrt

/** Retained position/color lightning mesh with one real normal per ribbon. */
class LightningMeshBuilder(
    context: RenderContext,
    estimate: Int,
) : QuadMeshBuilder(context, LightningMeshStruct, estimate) {

    private fun addVertex(
        x: Float,
        y: Float,
        z: Float,
        color: RGBAColor,
        normal: Vec3f,
    ) {
        data.add(
            x, y, z,
            color.rgba.buffer(),
            normal.x, normal.y, normal.z,
        )
    }

    fun addCrossedSegment(
        x0: Float,
        y0: Float,
        z0: Float,
        x1: Float,
        y1: Float,
        z1: Float,
        width0: Float,
        width1: Float,
        color: RGBAColor,
    ) {
        val basis = LightningRibbonBasis.calculate(x1 - x0, y1 - y0, z1 - z0)

        addVertex(x0 - width0, y0, z0, color, basis.xRibbonNormal)
        addVertex(x0 + width0, y0, z0, color, basis.xRibbonNormal)
        addVertex(x1 + width1, y1, z1, color, basis.xRibbonNormal)
        addVertex(x1 - width1, y1, z1, color, basis.xRibbonNormal)
        addIndexQuad()

        addVertex(x0, y0, z0 - width0, color, basis.zRibbonNormal)
        addVertex(x0, y0, z0 + width0, color, basis.zRibbonNormal)
        addVertex(x1, y1, z1 + width1, color, basis.zRibbonNormal)
        addVertex(x1, y1, z1 - width1, color, basis.zRibbonNormal)
        addIndexQuad()
    }

    data class LightningMeshStruct(
        val position: Vec3f,
        val color: RGBAColor,
        val normal: Vec3f,
    ) {
        companion object : MeshStruct(LightningMeshStruct::class)
    }
}

data class LightningRibbonBasis(
    val xRibbonNormal: Vec3f,
    val zRibbonNormal: Vec3f,
) {
    companion object {
        private const val EPSILON = 1.0e-8f

        /**
         * Matches the logical p0→p1→p2 winding of both crossed ribbon quads.
         * Width changes do not affect either cross product.
         */
        fun calculate(deltaX: Float, deltaY: Float, deltaZ: Float): LightningRibbonBasis =
            LightningRibbonBasis(
                xRibbonNormal = normalize(0.0f, -deltaZ, deltaY, Vec3f(0.0f, 0.0f, 1.0f)),
                zRibbonNormal = normalize(-deltaY, deltaX, 0.0f, Vec3f(-1.0f, 0.0f, 0.0f)),
            )

        private fun normalize(
            x: Float,
            y: Float,
            z: Float,
            fallback: Vec3f,
        ): Vec3f {
            val length = sqrt(x * x + y * y + z * z)
            if (!length.isFinite() || length <= EPSILON) return fallback
            return Vec3f(x / length, y / length, z / length)
        }
    }
}
