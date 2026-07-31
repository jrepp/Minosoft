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

package de.bixilon.minosoft.gui.rendering.system.window.glfw

data class OpenGlContextRequest(
    val major: Int,
    val minor: Int,
    val coreProfile: Boolean,
) {
    companion object {
        /**
         * Request the OpenGL 4.3 compute/storage tier used by the Iris
         * execution substrate, then preserve Minosoft's OpenGL 3.3 baseline as
         * a compatibility fallback. GLFW treats the requested version as a
         * minimum and may return a newer context. Apple exposes at most 4.1.
         */
        fun candidates(isMac: Boolean, preferQuads: Boolean): List<OpenGlContextRequest> {
            if (preferQuads) {
                return listOf(OpenGlContextRequest(3, 0, coreProfile = false))
            }
            if (isMac) {
                return listOf(
                    OpenGlContextRequest(4, 1, coreProfile = true),
                    OpenGlContextRequest(3, 3, coreProfile = true),
                )
            }
            return listOf(
                OpenGlContextRequest(4, 3, coreProfile = true),
                OpenGlContextRequest(3, 3, coreProfile = true),
            )
        }
    }
}
