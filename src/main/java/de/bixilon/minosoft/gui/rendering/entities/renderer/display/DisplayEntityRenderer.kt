/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.display

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4Operations
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.entities.entities.display.DisplayEntity
import de.bixilon.minosoft.data.entities.entities.display.DisplayTransformationInterpolator
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.kutil.primitive.FloatUtil.rad
import de.bixilon.minosoft.data.entities.EntityRotation
import kotlin.math.sqrt
import kotlin.time.Duration

abstract class DisplayEntityRenderer<E : DisplayEntity>(
    renderer: EntitiesRenderer,
    entity: E,
) : EntityRenderer<E>(renderer, entity) {
    private val transformation = DisplayTransformationInterpolator(entity.transformation)

    override fun updateMatrix(delta: Duration) {
        super.updateMatrix(delta)
        applyBillboard()
        transformation.target(
            entity.transformation,
            entity.interpolationStartDeltaTicks,
            entity.interpolationDurationTicks,
        )
        val transform = transformation.advance(delta)
        matrix.translateAssign(transform.translation)
        matrix.rotateQuaternionAssign(transform.leftRotation)
        matrix.scaleAssign(transform.scale)
        matrix.rotateQuaternionAssign(transform.rightRotation)
    }

    override fun updateLight(delta: Duration) {
        val brightness = entity.brightness
        if (brightness == null) return super.updateLight(delta)
        light.push(renderer.context.light.map.buffer[brightness.index])
        light.add(1.0f)
    }

    private fun applyBillboard() {
        val entityRotation = info.rotation
        val cameraRotation = renderer.context.camera.view.view.rotation
        when (DisplayBillboard.of(entity.billboard)) {
            DisplayBillboard.FIXED -> {
                matrix.rotateYAssign(-entityRotation.yaw.rad)
                matrix.rotateXAssign(entityRotation.pitch.rad)
            }
            DisplayBillboard.VERTICAL -> matrix.rotateYAssign((EntityRotation.HALF_CIRCLE_DEGREE - cameraRotation.yaw).rad)
            DisplayBillboard.HORIZONTAL -> {
                matrix.rotateYAssign(-entityRotation.yaw.rad)
                matrix.rotateXAssign((180.0f - cameraRotation.pitch).rad)
            }
            DisplayBillboard.CENTER -> {
                matrix.rotateYAssign((EntityRotation.HALF_CIRCLE_DEGREE - cameraRotation.yaw).rad)
                matrix.rotateXAssign((180.0f - cameraRotation.pitch).rad)
            }
        }
    }
}

enum class DisplayBillboard(val id: Byte) {
    FIXED(0),
    VERTICAL(1),
    HORIZONTAL(2),
    CENTER(3),
    ;

    companion object {
        fun of(id: Byte) = entries.getOrElse(id.toInt()) { FIXED }
    }
}

internal fun MMat4f.rotateQuaternionAssign(quaternion: Vec4f) {
    val lengthSquared = quaternion.x.toDouble() * quaternion.x +
        quaternion.y.toDouble() * quaternion.y +
        quaternion.z.toDouble() * quaternion.z +
        quaternion.w.toDouble() * quaternion.w
    if (!lengthSquared.isFinite() || lengthSquared <= 0.000000000001) return
    val inverseLength = (1.0 / sqrt(lengthSquared)).toFloat()
    val x = quaternion.x * inverseLength
    val y = quaternion.y * inverseLength
    val z = quaternion.z * inverseLength
    val w = quaternion.w * inverseLength
    val xx = x * x
    val yy = y * y
    val zz = z * z
    val xy = x * y
    val xz = x * z
    val yz = y * z
    val wx = w * x
    val wy = w * y
    val wz = w * z
    val rotation = MMat4f(
        1.0f - 2.0f * (yy + zz), 2.0f * (xy - wz), 2.0f * (xz + wy), 0.0f,
        2.0f * (xy + wz), 1.0f - 2.0f * (xx + zz), 2.0f * (yz - wx), 0.0f,
        2.0f * (xz - wy), 2.0f * (yz + wx), 1.0f - 2.0f * (xx + yy), 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
    )
    Mat4Operations.times(this.unsafe, rotation.unsafe, this)
}
