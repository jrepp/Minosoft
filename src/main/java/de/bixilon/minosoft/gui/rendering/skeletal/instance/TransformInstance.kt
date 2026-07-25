/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.instance

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4Operations
import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.MVec3f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemTransformProperty
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement.Companion.BLOCK_SIZE
import java.nio.FloatBuffer

class TransformInstance(
    val id: Int,
    val pivot: Vec3f,
    val children: Map<String, TransformInstance>,
    val baseRotation: Vec3f = Vec3f.EMPTY,
    val baseScale: Vec3f = Vec3f(1.0f),
) {
    private val array = children.values.toTypedArray()
    val nPivot = -pivot
    val matrix = MMat4f()
    internal val renderMatrix = MMat4f()
    private val partPivot = MVec3f(pivot * BLOCK_SIZE)
    private val partRotation = MVec3f(baseRotation)
    private val partScale = MVec3f(baseScale)
    var visible = true
        internal set
    /** Mirrors Mojang ModelPart.hidden, exposed by EMF as visible_boxes. */
    var hidden = false
        internal set


    fun reset() {
        partPivot.x = pivot.x * BLOCK_SIZE
        partPivot.y = pivot.y * BLOCK_SIZE
        partPivot.z = pivot.z * BLOCK_SIZE
        partRotation.x = baseRotation.x
        partRotation.y = baseRotation.y
        partRotation.z = baseRotation.z
        partScale.x = baseScale.x
        partScale.y = baseScale.y
        partScale.z = baseScale.z
        visible = true
        hidden = false
        this.matrix.apply {
            clearAssign()
            translateAssign(nPivot)
            rotateRadAssign(baseRotation)
            scaleAssign(baseScale)
            translateAssign(pivot)
        }

        for (child in array) {
            child.reset()
        }
    }

    fun transform(parent: Mat4f, parentVisible: Boolean = true) {
        Mat4Operations.times(parent, matrix.unsafe, matrix)
        val subtreeVisible = parentVisible && visible
        renderMatrix.set(matrix.unsafe)
        if (!subtreeVisible || hidden) renderMatrix.scaleAssign(0.0f)

        for (child in array) {
            child.transform(this.matrix.unsafe, subtreeVisible)
        }
    }

    fun pack(buffer: FloatBuffer) {
        buffer.position(this.id * Mat4f.LENGTH)
        buffer.put(renderMatrix._0.array, 0, Mat4f.LENGTH)

        for (child in this.array) {
            child.pack(buffer)
        }
    }

    operator fun get(name: String): TransformInstance? {
        return this.children[name]
    }

    internal fun recordTranslationPixels(value: Vec3f) {
        partPivot.x += value.x
        partPivot.y += value.y
        partPivot.z += value.z
    }

    internal fun recordTranslationBlocks(value: Vec3f) = recordTranslationPixels(value * BLOCK_SIZE)

    internal fun recordRotation(value: Vec3f) {
        partRotation.x += value.x
        partRotation.y += value.y
        partRotation.z += value.z
    }

    internal fun recordScale(value: Vec3f) {
        partScale.x *= value.x
        partScale.y *= value.y
        partScale.z *= value.z
    }

    internal fun setPartState(
        pivot: Vec3f,
        rotation: Vec3f,
        scale: Vec3f,
        visible: Boolean,
        hidden: Boolean,
    ) {
        partPivot.x = pivot.x
        partPivot.y = pivot.y
        partPivot.z = pivot.z
        partRotation.x = rotation.x
        partRotation.y = rotation.y
        partRotation.z = rotation.z
        partScale.x = scale.x
        partScale.y = scale.y
        partScale.z = scale.z
        this.visible = visible
        this.hidden = hidden
    }

    internal fun partProperties(): Map<CemTransformProperty, Float> = mapOf(
        CemTransformProperty.TRANSLATE_X to partPivot.x,
        CemTransformProperty.TRANSLATE_Y to partPivot.y,
        CemTransformProperty.TRANSLATE_Z to partPivot.z,
        CemTransformProperty.ROTATE_X to partRotation.x,
        CemTransformProperty.ROTATE_Y to partRotation.y,
        CemTransformProperty.ROTATE_Z to partRotation.z,
        CemTransformProperty.SCALE_X to partScale.x,
        CemTransformProperty.SCALE_Y to partScale.y,
        CemTransformProperty.SCALE_Z to partScale.z,
        CemTransformProperty.VISIBLE to if (visible) 1.0f else 0.0f,
        CemTransformProperty.VISIBLE_BOXES to if (hidden) 1.0f else 0.0f,
    )
}
