/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.opengl

/**
 * Exact cumulative work submitted by one OpenGL context.
 *
 * Production writes are confined to the render thread. Volatile primitive
 * fields keep increments allocation-free while allowing the debug thread to
 * take a bounded, eventually consistent snapshot without stopping rendering.
 */
class OpenGlWorkCounters {
    @Volatile private var drawArrays = 0L
    @Volatile private var drawElements = 0L
    @Volatile private var drawElementsBaseVertex = 0L
    @Volatile private var multiDrawElementsBaseVertex = 0L
    @Volatile private var logicalDrawCommands = 0L
    @Volatile private var submittedElements = 0L
    @Volatile private var programRequests = 0L
    @Volatile private var programChanges = 0L
    @Volatile private var framebufferBindRequests = 0L
    @Volatile private var framebufferBinds = 0L
    @Volatile private var framebufferAttachmentChanges = 0L
    @Volatile private var drawBufferChanges = 0L
    @Volatile private var readBufferChanges = 0L
    @Volatile private var framebufferCompletenessChecks = 0L
    @Volatile private var activeTextureRequests = 0L
    @Volatile private var activeTextureChanges = 0L
    @Volatile private var textureBindRequests = 0L
    @Volatile private var textureBinds2d = 0L
    @Volatile private var textureBinds2dArray = 0L
    @Volatile private var textureBindsOther = 0L
    @Volatile private var imageBindRequests = 0L
    @Volatile private var imageBinds = 0L
    @Volatile private var samplerParameterChanges = 0L
    @Volatile private var vaoBindRequests = 0L
    @Volatile private var vaoBinds = 0L
    @Volatile private var bufferBindRequests = 0L
    @Volatile private var bufferBinds = 0L
    @Volatile private var scalarUniformUploads = 0L
    @Volatile private var vectorUniformUploads = 0L
    @Volatile private var matrixUniformUploads = 0L
    @Volatile private var samplerUniformUploads = 0L
    @Volatile private var uniformBlockBindings = 0L
    @Volatile private var uniformBufferUploads = 0L
    @Volatile private var uniformBufferUploadBytes = 0L

    fun drawArrays(vertices: Int) {
        drawArrays++
        logicalDrawCommands++
        submittedElements += vertices.toLong()
    }

    fun drawElements(vertices: Int) {
        drawElements++
        logicalDrawCommands++
        submittedElements += vertices.toLong()
    }

    fun drawElementsBaseVertex(vertices: Int) {
        drawElementsBaseVertex++
        logicalDrawCommands++
        submittedElements += vertices.toLong()
    }

    fun multiDrawElementsBaseVertex(commands: Int, vertices: Int) {
        multiDrawElementsBaseVertex++
        logicalDrawCommands += commands.toLong()
        submittedElements += vertices.toLong()
    }

    fun programRequest(changed: Boolean) {
        programRequests++
        if (changed) programChanges++
    }

    fun framebufferBindRequest(changed: Boolean) {
        framebufferBindRequests++
        if (changed) framebufferBinds++
    }

    fun framebufferAttachmentChange() { framebufferAttachmentChanges++ }
    fun drawBufferChange() { drawBufferChanges++ }
    fun readBufferChange() { readBufferChanges++ }
    fun framebufferCompletenessCheck() { framebufferCompletenessChecks++ }

    fun activeTextureRequest(changed: Boolean) {
        activeTextureRequests++
        if (changed) activeTextureChanges++
    }

    fun textureBindRequest(changed: Boolean, target: Int) {
        textureBindRequests++
        if (!changed) return
        when (target) {
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D -> textureBinds2d++
            org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY -> textureBinds2dArray++
            else -> textureBindsOther++
        }
    }

    fun imageBindRequest(changed: Boolean) {
        imageBindRequests++
        if (changed) imageBinds++
    }

    fun samplerParameterChange() { samplerParameterChanges++ }

    fun vaoBindRequest(changed: Boolean) {
        vaoBindRequests++
        if (changed) vaoBinds++
    }

    fun bufferBindRequest(changed: Boolean = true) {
        bufferBindRequests++
        if (changed) bufferBinds++
    }

    fun scalarUniformUpload() { scalarUniformUploads++ }
    fun vectorUniformUpload() { vectorUniformUploads++ }
    fun matrixUniformUpload() { matrixUniformUploads++ }
    fun samplerUniformUpload() { samplerUniformUploads++ }
    fun uniformBlockBinding() { uniformBlockBindings++ }
    fun uniformBufferUpload(bytes: Long) {
        uniformBufferUploads++
        uniformBufferUploadBytes += bytes
    }

    fun snapshot() = OpenGlWorkSnapshot(
        drawArrays = drawArrays,
        drawElements = drawElements,
        drawElementsBaseVertex = drawElementsBaseVertex,
        multiDrawElementsBaseVertex = multiDrawElementsBaseVertex,
        logicalDrawCommands = logicalDrawCommands,
        submittedElements = submittedElements,
        programRequests = programRequests,
        programChanges = programChanges,
        framebufferBindRequests = framebufferBindRequests,
        framebufferBinds = framebufferBinds,
        framebufferAttachmentChanges = framebufferAttachmentChanges,
        drawBufferChanges = drawBufferChanges,
        readBufferChanges = readBufferChanges,
        framebufferCompletenessChecks = framebufferCompletenessChecks,
        activeTextureRequests = activeTextureRequests,
        activeTextureChanges = activeTextureChanges,
        textureBindRequests = textureBindRequests,
        textureBinds2d = textureBinds2d,
        textureBinds2dArray = textureBinds2dArray,
        textureBindsOther = textureBindsOther,
        imageBindRequests = imageBindRequests,
        imageBinds = imageBinds,
        samplerParameterChanges = samplerParameterChanges,
        vaoBindRequests = vaoBindRequests,
        vaoBinds = vaoBinds,
        bufferBindRequests = bufferBindRequests,
        bufferBinds = bufferBinds,
        scalarUniformUploads = scalarUniformUploads,
        vectorUniformUploads = vectorUniformUploads,
        matrixUniformUploads = matrixUniformUploads,
        samplerUniformUploads = samplerUniformUploads,
        uniformBlockBindings = uniformBlockBindings,
        uniformBufferUploads = uniformBufferUploads,
        uniformBufferUploadBytes = uniformBufferUploadBytes,
    )
}

data class OpenGlWorkSnapshot(
    val drawArrays: Long,
    val drawElements: Long,
    val drawElementsBaseVertex: Long,
    val multiDrawElementsBaseVertex: Long,
    val logicalDrawCommands: Long,
    /** Sum of OpenGL draw-count arguments: vertices for arrays, indices for elements. */
    val submittedElements: Long,
    val programRequests: Long,
    val programChanges: Long,
    val framebufferBindRequests: Long,
    val framebufferBinds: Long,
    val framebufferAttachmentChanges: Long,
    val drawBufferChanges: Long,
    val readBufferChanges: Long,
    val framebufferCompletenessChecks: Long,
    val activeTextureRequests: Long,
    val activeTextureChanges: Long,
    val textureBindRequests: Long,
    val textureBinds2d: Long,
    val textureBinds2dArray: Long,
    val textureBindsOther: Long,
    val imageBindRequests: Long,
    val imageBinds: Long,
    val samplerParameterChanges: Long,
    val vaoBindRequests: Long,
    val vaoBinds: Long,
    val bufferBindRequests: Long,
    val bufferBinds: Long,
    val scalarUniformUploads: Long,
    val vectorUniformUploads: Long,
    val matrixUniformUploads: Long,
    val samplerUniformUploads: Long,
    val uniformBlockBindings: Long,
    val uniformBufferUploads: Long,
    val uniformBufferUploadBytes: Long,
) {
    val physicalDrawCalls: Long
        get() = drawArrays + drawElements + drawElementsBaseVertex + multiDrawElementsBaseVertex
    val textureBinds: Long
        get() = textureBinds2d + textureBinds2dArray + textureBindsOther
    val uniformUploads: Long
        get() = scalarUniformUploads + vectorUniformUploads + matrixUniformUploads + samplerUniformUploads + uniformBlockBindings + uniformBufferUploads
}
