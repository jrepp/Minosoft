/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.preview

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationLoop
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationController
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.gui.rendering.camera.CameraDefinition
import de.bixilon.minosoft.gui.rendering.camera.CameraUtil
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.models.block.element.FaceVertexData
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import de.bixilon.minosoft.gui.rendering.skeletal.instance.TransformInstance
import de.bixilon.minosoft.gui.rendering.skeletal.instance.apply
import de.bixilon.minosoft.gui.rendering.skeletal.instance.index
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalQuadConsumer
import de.bixilon.minosoft.gui.rendering.skeletal.model.textures.SkeletalInstanceTextureMap
import de.bixilon.minosoft.gui.rendering.system.base.buffer.vertex.PrimitiveTypes
import de.bixilon.minosoft.gui.rendering.system.base.texture.shader.ShaderTexture
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.PackedUVArray
import de.bixilon.minosoft.gui.rendering.util.mesh.uv.array.UnpackedUVArray
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

data class SkeletalPreviewQuad(
    val positions: FaceVertexData,
    val uv: PackedUVArray,
    val texture: ShaderTexture,
    val material: ResourceLocation?,
    val transform: Int,
)

/**
 * CPU-retained neutral-pose view of one production skeletal bake.
 *
 * The GUI uses painter-sorted textured quads, so previews require no separate
 * framebuffer, world entity, animation manager, or GPU mesh ownership.
 */
class SkeletalPreview(
    val quads: List<SkeletalPreviewQuad>,
    private val transform: BakedSkeletalTransform? = null,
    val animations: Map<String, SkeletalAnimationClip> = emptyMap(),
    private val format: SkeletalContentFormat? = null,
) {
    private val neutralTransforms = transform?.poseMatrices().orEmpty()
    val isEmpty: Boolean get() = quads.isEmpty()

    fun createPlayer(): SkeletalPreviewPlayer? {
        val transform = transform ?: return null
        return SkeletalPreviewPlayer(transform, animations, format)
    }

    fun render(
        offset: Vec2f,
        size: Vec2f,
        consumer: GuiVertexConsumer,
        options: GUIVertexOptions?,
        materialTextures: Map<ResourceLocation, ShaderTexture> = emptyMap(),
        yaw: Float = 0.0f,
        transforms: Map<Int, Mat4f> = neutralTransforms,
        excludedMaterials: Set<ResourceLocation> = emptySet(),
    ) {
        if (quads.isEmpty() || size.x <= 0.0f || size.y <= 0.0f) return
        val projected = ArrayList<ProjectedQuad>(quads.size)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (quad in quads.take(MAX_QUADS)) {
            if (quad.material in excludedMaterials) continue
            val transform = transforms[quad.transform] ?: continue
            val points = Array(PrimitiveTypes.QUAD.vertices) { index ->
                val source = index * Vec3f.LENGTH
                val posed = transform * Vec3f(
                    quad.positions[source],
                    quad.positions[source + 1],
                    quad.positions[source + 2],
                )
                val value = VIEW_MATRIX * rotateY(posed, yaw)
                minX = minOf(minX, value.x)
                minY = minOf(minY, -value.y)
                maxX = maxOf(maxX, value.x)
                maxY = maxOf(maxY, -value.y)
                Vec3f(value.x, -value.y, value.z)
            }
            projected += ProjectedQuad(
                points,
                quad.uv,
                quad.material?.let(materialTextures::get) ?: quad.texture,
                points.sumOf { it.z.toDouble() }.toFloat() / PrimitiveTypes.QUAD.vertices,
            )
        }

        val width = maxX - minX
        val height = maxY - minY
        if (!width.isFinite() || !height.isFinite() || width <= 0.0f || height <= 0.0f) return
        val scale = min(size.x / width, size.y / height) * CONTENT_SCALE
        val sourceCenter = Vec2f((minX + maxX) / 2.0f, (minY + maxY) / 2.0f)
        val targetCenter = offset + size / 2.0f

        // Camera-space z is negative in front of the camera. Draw the farthest
        // faces first because GUI meshes intentionally do not own depth state.
        projected.sortBy(ProjectedQuad::depth)
        for (quad in projected) {
            val positions = FloatArray(PrimitiveTypes.QUAD.vertices * Vec2f.LENGTH)
            for ((index, point) in quad.positions.withIndex()) {
                val centered = (Vec2f(point.x, point.y) - sourceCenter) * scale + targetCenter
                positions[index * Vec2f.LENGTH] = centered.x
                positions[index * Vec2f.LENGTH + 1] = centered.y
            }
            consumer.addQuad(positions, quad.uv, quad.texture, ChatColors.WHITE, options)
        }
    }

    private data class ProjectedQuad(
        val positions: Array<Vec3f>,
        val uv: PackedUVArray,
        val texture: ShaderTexture,
        val depth: Float,
    )

    companion object {
        const val CONTENT_SCALE = 0.9f
        const val MAX_QUADS = 65_536
        val VIEW_MATRIX: Mat4f = CameraUtil.lookAt(
            Vec3f(2.5f, 1.75f, -3.5f),
            Vec3f(0.0f, 0.75f, 0.0f),
            CameraDefinition.CAMERA_UP_VEC3,
        )
        val EMPTY = SkeletalPreview(emptyList())

        internal fun rotateY(point: Vec3f, yaw: Float): Vec3f {
            if (yaw == 0.0f) return point
            val cosine = cos(yaw)
            val sine = sin(yaw)
            return Vec3f(
                cosine * point.x + sine * point.z,
                point.y,
                -sine * point.x + cosine * point.z,
            )
        }
    }
}

internal class SkeletalPreviewCollector(
    private val transform: BakedSkeletalTransform,
    textures: SkeletalInstanceTextureMap,
    private val animations: Map<String, SkeletalAnimationClip>,
    private val format: SkeletalContentFormat?,
) : SkeletalQuadConsumer {
    private val materials = IdentityHashMap<ShaderTexture, ResourceLocation>()
    private val quads = mutableListOf<SkeletalPreviewQuad>()

    init {
        for ((material, texture) in textures) {
            materials.putIfAbsent(texture.texture, material)
        }
    }

    override fun addQuad(
        positions: FaceVertexData,
        uv: UnpackedUVArray,
        transform: Int,
        normal: Vec3f,
        texture: ShaderTexture,
        path: String,
    ) {
        if (quads.size >= SkeletalPreview.MAX_QUADS) return
        quads += SkeletalPreviewQuad(positions.copyOf(), uv.pack(), texture, materials[texture], transform)
    }

    fun build(): SkeletalPreview = if (quads.isEmpty()) {
        SkeletalPreview.EMPTY
    } else {
        SkeletalPreview(quads.toList(), transform, animations, format)
    }
}

class SkeletalPreviewPlayer internal constructor(
    transform: BakedSkeletalTransform,
    animations: Map<String, SkeletalAnimationClip>,
    private val format: SkeletalContentFormat?,
) {
    private val root = transform.instance()
    private val namedTransforms = root.index()
    private val controller = SkeletalAnimationController(animations)
    private val identity = MMat4f().apply { clearAssign() }
    var matrices: Map<Int, Mat4f> = root.poseMatrices(identity)
        private set
    var animation: String? = null
        private set

    fun select(animation: String?) {
        if (this.animation == animation) return
        if (animation == null) {
            controller.stop()
        } else {
            // This is an inspection control: even attacks and other
            // production one-shots must remain observable until the tester
            // chooses another clip.
            controller.play(
                animation,
                restart = true,
                loopOverride = SkeletalAnimationLoop.LOOP,
            )
        }
        this.animation = animation
        update(0.0f)
    }

    fun tick() = update(PREVIEW_TICK_SECONDS)

    private fun update(deltaSeconds: Float) {
        root.reset()
        if (animation != null) {
            val animationTime = controller.elapsedSeconds + deltaSeconds
            val expressionContext = SkeletalExpressionContext(
                mapOf(
                    "query.anim_time" to animationTime.toDouble(),
                    "query.animation_time" to animationTime.toDouble(),
                    "q.anim_time" to animationTime.toDouble(),
                    "q.animation_time" to animationTime.toDouble(),
                    "query.life_time" to animationTime.toDouble(),
                    "query.delta_time" to deltaSeconds.toDouble(),
                    "query.vertical_speed" to 0.0,
                    "q.modified_move_speed" to 1.0,
                    "q.target_y_rotation" to 0.0,
                ),
            )
            controller.update(deltaSeconds, expressionContext).apply(namedTransforms, format)
        }
        root.transform(identity.unsafe)
        matrices = root.collectPoseMatrices()
    }

    private companion object {
        val PREVIEW_TICK_SECONDS = 50.milliseconds.inWholeNanoseconds / 1_000_000_000.0f
    }
}

private fun BakedSkeletalTransform.poseMatrices(): Map<Int, Mat4f> {
    val identity = MMat4f().apply { clearAssign() }
    return instance().poseMatrices(identity)
}

private fun TransformInstance.poseMatrices(identity: MMat4f): Map<Int, Mat4f> {
    reset()
    transform(identity.unsafe)
    return collectPoseMatrices()
}

private fun TransformInstance.collectPoseMatrices(): Map<Int, Mat4f> = buildMap {
    fun collect(transform: TransformInstance) {
        put(transform.id, transform.renderMatrix.unsafe)
        transform.children.values.forEach(::collect)
    }
    collect(this@collectPoseMatrices)
}
