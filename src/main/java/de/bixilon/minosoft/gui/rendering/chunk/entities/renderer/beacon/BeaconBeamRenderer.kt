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

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.entities.block.BeaconBlockEntity
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.entities.BlockEntityRenderer
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.SimpleTextureMeshBuilder

/**
 * Native beacon beam producer. Geometry is retained in beacon-local space so
 * camera-origin changes only update [matrix], while column mutations rebuild
 * the bounded colored segment mesh.
 */
class BeaconBeamRenderer(
    override val entity: BeaconBlockEntity,
    private val context: RenderContext,
) : BlockEntityRenderer {
    override val hasTranslucentPass = true
    private val matrix = MMat4f()
    private var mesh: Mesh? = null
    private var key: BeamKey? = null

    override fun draw() = Unit

    override fun drawTranslucent() {
        ensureMesh()
        val mesh = mesh ?: return
        if (mesh.state == MeshStates.PREPARING) mesh.load()

        val cameraOffset = context.camera.offset.offset
        matrix.clearAssign()
        matrix.translateAssign(
            (entity.position.x - cameraOffset.x).toFloat(),
            (entity.position.y - cameraOffset.y).toFloat(),
            (entity.position.z - cameraOffset.z).toFloat(),
        )

        val system = context.system
        try {
            system.reset(
                blending = true,
                faceCulling = false,
                depthMask = false,
                sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                destinationRGB = BlendingFunctions.ONE,
                sourceAlpha = BlendingFunctions.ONE,
                destinationAlpha = BlendingFunctions.ONE,
            )
            val shader = context.shaders.beaconBeamShader
            shader.use()
            shader.matrix = matrix.unsafe
            shader.textureOffset = -((entity.session.world.time.age % TEXTURE_PERIOD_TICKS).toFloat() / TEXTURE_PERIOD_TICKS)
            mesh.draw()
        } finally {
            system.reset()
        }
    }

    override fun load() {
        ensureMesh()
        mesh?.takeIf { it.state == MeshStates.PREPARING }?.load()
    }

    override fun unload() = clear()

    override fun drop() = clear()

    private fun ensureMesh() {
        val nextKey = BeamKey(
            blockRevision = entity.session.world.blockRevision,
            levels = entity.levels,
            maxY = entity.session.world.dimension.maxY,
        )
        if (key == nextKey) return
        key = nextKey
        clearMesh()
        if (nextKey.levels <= 0) return

        val segments = segments()
        if (segments.isEmpty()) return
        val builder = SimpleTextureMeshBuilder(context, segments.size * 8)
        val texture = context.shaders.beaconBeamTexture
        for (segment in segments) {
            addPrism(builder, texture, segment, OUTER_RADIUS, segment.color.with(alpha = OUTER_ALPHA))
            addPrism(builder, texture, segment, INNER_RADIUS, segment.color.with(alpha = INNER_ALPHA))
        }
        mesh = builder.bake()
    }

    private fun segments(): List<BeamSegment> {
        val world = entity.session.world
        val base = entity.position
        val maxY = world.dimension.maxY + 1
        var start = 1.0f
        var color = WHITE
        var stained = false
        val result = mutableListOf<BeamSegment>()

        for (worldY in (base.y + 1) until maxY) {
            val state = world[base.with(y = worldY)]
            val glass = state?.block?.identifier?.path?.let(::glassColor)
            if (glass != null) {
                val localY = (worldY - base.y).toFloat()
                if (localY > start) result += BeamSegment(start, localY, color)
                color = if (stained) color.mixRGB(glass) else glass
                stained = true
                start = localY
                continue
            }
            if (state != null && BlockStateFlags.FULL_OPAQUE in state.flags) {
                val end = (worldY - base.y).toFloat()
                if (end > start) result += BeamSegment(start, end, color)
                return result
            }
        }
        val end = (maxY - base.y).toFloat()
        if (end > start) result += BeamSegment(start, end, color)
        return result
    }

    private fun clear() {
        key = null
        clearMesh()
    }

    private fun clearMesh() {
        val previous = mesh
        mesh = null
        when (previous?.state) {
            MeshStates.PREPARING -> previous.drop()
            MeshStates.LOADED -> previous.unload()
            MeshStates.UNLOADED,
            null,
            -> Unit
        }
    }

    private data class BeamKey(
        val blockRevision: Int,
        val levels: Int,
        val maxY: Int,
    )

    private data class BeamSegment(
        val start: Float,
        val end: Float,
        val color: RGBAColor,
    )

    private companion object {
        const val TEXTURE_PERIOD_TICKS = 80
        const val OUTER_RADIUS = 0.25f
        const val INNER_RADIUS = 0.125f
        const val OUTER_ALPHA = 0.125f
        const val INNER_ALPHA = 0.875f
        val WHITE = RGBAColor(1.0f, 1.0f, 1.0f)

        val GLASS_COLORS = mapOf(
            "white" to RGBAColor(1.0f, 1.0f, 1.0f),
            "orange" to RGBAColor(0.85f, 0.50f, 0.20f),
            "magenta" to RGBAColor(0.70f, 0.30f, 0.85f),
            "light_blue" to RGBAColor(0.40f, 0.60f, 0.85f),
            "yellow" to RGBAColor(0.90f, 0.90f, 0.20f),
            "lime" to RGBAColor(0.50f, 0.80f, 0.10f),
            "pink" to RGBAColor(0.95f, 0.50f, 0.65f),
            "gray" to RGBAColor(0.30f, 0.30f, 0.30f),
            "light_gray" to RGBAColor(0.60f, 0.60f, 0.60f),
            "cyan" to RGBAColor(0.30f, 0.50f, 0.60f),
            "purple" to RGBAColor(0.50f, 0.25f, 0.70f),
            "blue" to RGBAColor(0.20f, 0.30f, 0.70f),
            "brown" to RGBAColor(0.40f, 0.30f, 0.20f),
            "green" to RGBAColor(0.40f, 0.50f, 0.20f),
            "red" to RGBAColor(0.60f, 0.20f, 0.20f),
            "black" to RGBAColor(0.10f, 0.10f, 0.10f),
        )

        fun glassColor(path: String): RGBAColor? {
            val name = when {
                path.endsWith("_stained_glass_pane") -> path.removeSuffix("_stained_glass_pane")
                path.endsWith("_stained_glass") -> path.removeSuffix("_stained_glass")
                else -> return null
            }
            return GLASS_COLORS[name]
        }

        fun addPrism(
            builder: SimpleTextureMeshBuilder,
            texture: de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture,
            segment: BeamSegment,
            radius: Float,
            color: RGBAColor,
        ) {
            val x0 = 0.5f - radius
            val x1 = 0.5f + radius
            val z0 = 0.5f - radius
            val z1 = 0.5f + radius
            val v1 = (segment.end - segment.start) * 0.5f
            addFace(builder, texture, x0, z0, x1, z0, segment.start, segment.end, v1, color)
            addFace(builder, texture, x1, z0, x1, z1, segment.start, segment.end, v1, color)
            addFace(builder, texture, x1, z1, x0, z1, segment.start, segment.end, v1, color)
            addFace(builder, texture, x0, z1, x0, z0, segment.start, segment.end, v1, color)
        }

        fun addFace(
            builder: SimpleTextureMeshBuilder,
            texture: de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture,
            x0: Float,
            z0: Float,
            x1: Float,
            z1: Float,
            y0: Float,
            y1: Float,
            v1: Float,
            color: RGBAColor,
        ) {
            builder.addQuad(
                positions = arrayOf(
                    Vec3f(x0, y0, z0),
                    Vec3f(x1, y0, z1),
                    Vec3f(x1, y1, z1),
                    Vec3f(x0, y1, z0),
                ),
                uvs = arrayOf(
                    Vec2f(0.0f, 0.0f),
                    Vec2f(1.0f, 0.0f),
                    Vec2f(1.0f, v1),
                    Vec2f(0.0f, v1),
                ),
                texture = texture,
                color = color,
            )
        }
    }
}
