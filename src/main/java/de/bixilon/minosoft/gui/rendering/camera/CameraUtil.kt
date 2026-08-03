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

package de.bixilon.minosoft.gui.rendering.camera

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import kotlin.math.abs
import kotlin.math.tan

object CameraUtil {

    fun perspective(fovY: Float, aspect: Float, near: Float, far: Float): Mat4f {
        assert(abs(aspect - Float.MIN_VALUE) > 0.0f)

        val tan = tan(fovY / 2.0f)

        val mat = MMat4f(0.0f)

        mat[0, 0] = 1.0f / (aspect * tan)
        mat[1, 1] = 1.0f / tan
        mat[2, 2] = -(far + near) / (far - near)
        mat[3, 2] = -1.0f
        mat[2, 3] = -(2.0f * far * near) / (far - near)

        return mat.unsafe
    }

    fun orthographic(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): Mat4f {
        require(right > left && top > bottom && far > near) { "Invalid orthographic projection bounds" }
        val mat = MMat4f(1.0f)
        mat[0, 0] = 2.0f / (right - left)
        mat[1, 1] = 2.0f / (top - bottom)
        mat[2, 2] = -2.0f / (far - near)
        mat[0, 3] = -(right + left) / (right - left)
        mat[1, 3] = -(top + bottom) / (top - bottom)
        mat[2, 3] = -(far + near) / (far - near)
        return mat.unsafe
    }

    fun lookAt(eye: Vec3f, center: Vec3f, up: Vec3f): Mat4f {
        val forward = center - eye
        val forwardLength = forward.length2()
        val f = if (forwardLength.isFinite() && forwardLength > LOOK_AT_EPSILON) {
            forward.normalize()
        } else {
            DEFAULT_FORWARD
        }
        var side = f cross up
        if (!side.length2().isFinite() || side.length2() <= LOOK_AT_EPSILON) {
            // Looking exactly along the conventional world-up axis has no unique roll. Choose a
            // stable orthogonal axis so the view stays invertible at the legal +/-90 degree pitch.
            val fallbackUp = if (abs(f.z) < 0.9f) Vec3f(0.0f, 0.0f, 1.0f) else Vec3f(1.0f, 0.0f, 0.0f)
            side = f cross fallbackUp
        }
        val s = side.normalize()
        val u = s cross f

        val mat = MMat4f(1.0f)
        mat[0, 0] = s.x
        mat[0, 1] = s.y
        mat[0, 2] = s.z
        mat[1, 0] = u.x
        mat[1, 1] = u.y
        mat[1, 2] = u.z
        mat[2, 0] = -f.x
        mat[2, 1] = -f.y
        mat[2, 2] = -f.z
        mat[0, 3] = -(s dot eye)
        mat[1, 3] = -(u dot eye)
        mat[2, 3] = (f dot eye)

        return mat.unsafe
    }

    private const val LOOK_AT_EPSILON = 1.0e-12f
    private val DEFAULT_FORWARD = Vec3f(0.0f, 0.0f, -1.0f)
}
