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

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.entities.feature.mesh.MeshedFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderStateKeys
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityLayer
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.integrated.SimpleTextureMeshBuilder
import kotlin.time.Duration

/** Camera-facing, full-bright vanilla fire sheets surrounding a burning entity. */
class EntityFlameFeature(
    private val entityRenderer: EntityRenderer<*>,
) : MeshedFeature<Mesh>(entityRenderer, EntityRenderStateKeys.FLAME) {
    private val matrix = MMat4f()
    private var key: Key? = null
    private var layout = EntityFlameGeometry.Layout(0.0f, 0.0f, 0.0f, emptyList())

    override val layer get() = EntityLayer.Opaque
    override val castsShadow get() = true
    override val priority get() = 100

    override fun isVisible() = super.isVisible() && entityRenderer.entity.isOnFire

    override fun update(delta: Duration) {
        super.update(delta)
        val entity = entityRenderer.entity
        val next = Key(entity.dimensions.x, entity.dimensions.y)
        if (unload || mesh == null || key != next) {
            key = next
            createMesh(next)
        }
        updateMatrix()
    }

    private fun createMesh(key: Key) {
        layout = EntityFlameGeometry.layout(key.width, key.height)
        if (layout.quads.isEmpty()) {
            mesh = null
            return
        }
        val context = entityRenderer.renderer.context
        val textures = entityRenderer.renderer.features.flame.textures
        val builder = SimpleTextureMeshBuilder(context, layout.quads.size)
        for (quad in layout.quads) {
            val texture = textures[quad.texture]
            builder.addQuad(
                positions = Array(quad.vertices.size) { quad.vertices[it].position },
                uvs = Array(quad.vertices.size) { quad.vertices[it].uv },
                texture = texture,
                color = ChatColors.WHITE,
            )
        }
        mesh = builder.bake()
    }

    private fun updateMatrix() {
        if (layout.quads.isEmpty()) return
        val context = entityRenderer.renderer.context
        val position = entityRenderer.info.position
        val offset = context.camera.offset.offset
        val camera = context.camera.view.view.rotation
        matrix.clearAssign()
        matrix.translateAssign(
            (position.x - offset.x).toFloat(),
            (position.y - offset.y).toFloat(),
            (position.z - offset.z).toFloat(),
        )
        matrix.scaleAssign(layout.scale)
        matrix.rotateYAssign((EntityRotation.HALF_CIRCLE_DEGREE - camera.yaw).rad)
        matrix.rotateXAssign((EntityRotation.HALF_CIRCLE_DEGREE - camera.pitch).rad)
        matrix.translateZAssign(layout.zOffset)
    }

    override fun draw(mesh: Mesh) {
        val context = entityRenderer.renderer.context
        context.system.set(EntityLayer.Opaque.settings)
        val shader = entityRenderer.renderer.features.flame.shader
        shader.use()
        shader.matrix = matrix.unsafe
        super.draw(mesh)
    }

    private data class Key(
        val width: Float,
        val height: Float,
    )
}
