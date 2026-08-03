/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.system.opengl.buffer.frame.attachment

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.AttachmentStates
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import org.lwjgl.opengl.GL30.*

abstract class OpenGlBufferAttachment(
    system: OpenGlRenderSystem,
    size: Vec2i,
) : OpenGlFramebufferAttachment(system, size) {
    var id = -1
        private set

    protected abstract val glMode: Int
    abstract val glAttachment: Int

    override fun init() {
        check(state == AttachmentStates.PREPARING) { "Can not init renderbuffer in $state" }
        id = gl { glGenRenderbuffers() }
        system.resources.created(OpenGlResourceType.RENDERBUFFER, id)
        try {
            unsafeBind()
            gl { glRenderbufferStorage(GL_RENDERBUFFER, glMode, size.x, size.y) }
            state = AttachmentStates.GENERATED
        } catch (error: Throwable) {
            try {
                deleteRenderbuffer()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            state = AttachmentStates.UNLOADED
            throw error
        }
    }

    fun bind() {
        check(state == AttachmentStates.GENERATED) { "Can not bind renderbuffer in $state" }
        unsafeBind()
    }

    fun unsafeBind() {
        system.work.bufferBindRequest()
        gl { glBindRenderbuffer(GL_RENDERBUFFER, id) }
    }

    override fun unload() {
        check(state == AttachmentStates.GENERATED) { "Can not unload renderbuffer in $state" }
        deleteRenderbuffer()
        state = AttachmentStates.UNLOADED
    }

    private fun deleteRenderbuffer() {
        if (id < 0) return
        gl { glDeleteRenderbuffers(id) }
        system.resources.deleted(OpenGlResourceType.RENDERBUFFER, id)
        id = -1
    }
}
