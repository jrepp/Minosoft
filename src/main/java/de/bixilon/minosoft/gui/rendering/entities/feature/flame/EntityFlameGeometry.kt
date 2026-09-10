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

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import kotlin.math.floor

/**
 * Minecraft 1.20.4 EntityRenderDispatcher.renderFire expressed without client
 * renderer types. Coordinates remain in the normalized billboard space; the
 * feature matrix applies the entity-width scale and camera rotation.
 */
object EntityFlameGeometry {
    data class Vertex(
        val position: Vec3f,
        val uv: Vec2f,
    )

    data class Quad(
        val texture: Int,
        val vertices: List<Vertex>,
    )

    data class Layout(
        val scale: Float,
        val normalizedHeight: Float,
        val zOffset: Float,
        val quads: List<Quad>,
    )

    fun layout(width: Float, height: Float): Layout {
        val scale = width * WIDTH_SCALE
        if (!scale.isFinite() || scale <= 0.0f || !height.isFinite() || height <= 0.0f) {
            return Layout(0.0f, 0.0f, 0.0f, emptyList())
        }
        val normalizedHeight = height / scale
        var remaining = normalizedHeight
        var halfWidth = INITIAL_HALF_WIDTH
        var yOffset = 0.0f
        var z = 0.0f
        var layer = 0
        val quads = ArrayList<Quad>()
        while (remaining > 0.0f) {
            val flipped = (layer / 2) % 2 == 0
            val rightU = if (flipped) 0.0f else 1.0f
            val leftU = if (flipped) 1.0f else 0.0f
            quads += Quad(
                texture = layer % 2,
                vertices = listOf(
                    Vertex(Vec3f(+halfWidth, -yOffset, z), Vec2f(rightU, 1.0f)),
                    Vertex(Vec3f(-halfWidth, -yOffset, z), Vec2f(leftU, 1.0f)),
                    Vertex(Vec3f(-halfWidth, QUAD_HEIGHT - yOffset, z), Vec2f(leftU, 0.0f)),
                    Vertex(Vec3f(+halfWidth, QUAD_HEIGHT - yOffset, z), Vec2f(rightU, 0.0f)),
                ),
            )
            remaining -= LAYER_STEP
            yOffset -= LAYER_STEP
            halfWidth *= WIDTH_DECAY
            z += DEPTH_STEP
            layer++
        }
        return Layout(
            scale = scale,
            normalizedHeight = normalizedHeight,
            zOffset = BASE_Z + floor(normalizedHeight) * INTEGER_HEIGHT_Z_STEP,
            quads = quads,
        )
    }

    private const val WIDTH_SCALE = 1.4f
    private const val INITIAL_HALF_WIDTH = 0.5f
    private const val QUAD_HEIGHT = 1.4f
    private const val LAYER_STEP = 0.45f
    private const val WIDTH_DECAY = 0.9f
    private const val DEPTH_STEP = 0.03f
    private const val BASE_Z = -0.3f
    private const val INTEGER_HEIGHT_Z_STEP = 0.02f
}
