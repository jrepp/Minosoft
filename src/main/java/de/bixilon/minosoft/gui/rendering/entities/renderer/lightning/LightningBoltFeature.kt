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

import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKeys
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import java.util.Random

/** Retained jagged lightning ribbons in entity-local space. */
class LightningBoltFeature(
    private val lightningRenderer: LightningBoltRenderer,
) : MeshedFeature<Mesh>(lightningRenderer, EntityRenderStateKeys.LIGHTNING), FeatureDrawable {
    override val layer = EntityLayer.Translucent
    override val priority = -200

    init {
        mesh = buildMesh()
    }

    override fun draw(mesh: Mesh) {
        val context = lightningRenderer.renderer.context
        try {
            context.system.reset(
                blending = true,
                faceCulling = false,
                depthMask = false,
                sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                destinationRGB = BlendingFunctions.ONE,
                sourceAlpha = BlendingFunctions.ONE,
                destinationAlpha = BlendingFunctions.ONE,
            )
            val shader = context.shaders.lightningShader
            shader.use()
            shader.matrix = lightningRenderer.matrix.unsafe
            super.draw(mesh)
        } finally {
            context.system.set(EntityLayer.Translucent.settings)
        }
    }

    private fun buildMesh(): Mesh {
        val random = Random(seed())
        val builder = LightningMeshBuilder(lightningRenderer.renderer.context, SEGMENTS * 3)
        var x = 0.0f
        var z = 0.0f
        for (segment in 0 until SEGMENTS) {
            val nextX = x + (random.nextFloat() - 0.5f) * JITTER
            val nextZ = z + (random.nextFloat() - 0.5f) * JITTER
            val y0 = segment * SEGMENT_HEIGHT
            val y1 = y0 + SEGMENT_HEIGHT
            val width0 = BASE_WIDTH * (1.0f - segment.toFloat() / (SEGMENTS * 1.5f))
            val width1 = BASE_WIDTH * (1.0f - (segment + 1.0f) / (SEGMENTS * 1.5f))
            builder.addCrossedSegment(x, y0, z, nextX, y1, nextZ, width0, width1, CORE_COLOR)

            if (segment in BRANCH_SEGMENTS) {
                val branchX = nextX + (random.nextFloat() - 0.5f) * BRANCH_REACH
                val branchZ = nextZ + (random.nextFloat() - 0.5f) * BRANCH_REACH
                builder.addCrossedSegment(
                    nextX,
                    y1,
                    nextZ,
                    branchX,
                    y1 + SEGMENT_HEIGHT * 0.75f,
                    branchZ,
                    width1,
                    width1 * 0.35f,
                    BRANCH_COLOR,
                )
            }
            x = nextX
            z = nextZ
        }
        return builder.bake()
    }

    private fun seed(): Long {
        val entity = lightningRenderer.entity
        val position = entity.physics.position
        return entity.id?.toLong()
            ?: java.lang.Double.doubleToLongBits(position.x) xor
                java.lang.Double.doubleToLongBits(position.y) xor
                java.lang.Double.doubleToLongBits(position.z)
    }

    private companion object {
        const val SEGMENTS = 8
        const val SEGMENT_HEIGHT = 2.0f
        const val JITTER = 1.2f
        const val BRANCH_REACH = 2.5f
        const val BASE_WIDTH = 0.22f
        val BRANCH_SEGMENTS = setOf(2, 4, 6)
        val CORE_COLOR = RGBAColor(0.65f, 0.65f, 0.85f, 0.75f)
        val BRANCH_COLOR = RGBAColor(0.45f, 0.45f, 0.70f, 0.45f)

    }
}
