/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.outline

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.formatting.color.Colors
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferMeshBuilder
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.system.base.BlendingFunctions
import de.bixilon.minosoft.gui.rendering.system.base.IntegratedBufferTypes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.Framebuffer
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.FramebufferState
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.scaledFramebufferSize
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.texture.TextureModes
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates

/**
 * Draws entity geometry into a texture-only mask, then expands its outer edge
 * by one physical framebuffer pixel and composites it into the main world
 * target. The candidate framebuffer is published only after initialization.
 */
class EntityOutlineRenderer(
    private val context: RenderContext,
) {
    private val commands = ArrayList<Command>()
    private val mesh = FramebufferMeshBuilder(context).bake()
    private val shader = context.system.shader.create(minosoft("entities/outline/composite")) { EntityOutlineShader(it) }
    private var framebuffer: Framebuffer? = null
    private var initialized = false

    val size: Int get() = commands.size
    val isEmpty: Boolean get() = commands.isEmpty()

    operator fun plusAssign(command: Command) {
        commands += command
    }

    fun clear() {
        commands.clear()
    }

    fun prepare() {
        for (command in commands) command.feature.prepareOutline()
    }

    fun init() {
        check(!initialized) { "Entity outline renderer was already initialized." }
        try {
            mesh.load()
            ensureFramebuffer()
            initialized = true
        } catch (error: Throwable) {
            cleanup(error)
            throw error
        }
    }

    fun postInit() {
        shader.load()
    }

    fun draw() {
        if (commands.isEmpty()) return
        ensureFramebuffer()
        val framebuffer = requireNotNull(framebuffer)
        val system = context.system

        try {
            system.framebuffer = framebuffer
            system.reset(
                depthTest = false,
                blending = false,
                faceCulling = false,
                depthMask = false,
                clearColor = Colors.TRANSPARENT,
            )
            system.clear(IntegratedBufferTypes.COLOR_BUFFER)
            context.shaderPipeline.withInternalTarget {
                for (command in commands) {
                    command.feature.drawOutline(command.color)
                }
            }

            bindMainTarget()
            system.reset(
                depthTest = false,
                blending = true,
                faceCulling = false,
                depthMask = false,
                sourceRGB = BlendingFunctions.SOURCE_ALPHA,
                destinationRGB = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
                sourceAlpha = BlendingFunctions.ONE,
                destinationAlpha = BlendingFunctions.ONE_MINUS_SOURCE_ALPHA,
            )
            framebuffer.bindTexture()
            shader.use()
            shader.texelSize = Vec2f(1.0f / framebuffer.size.x, 1.0f / framebuffer.size.y)
            mesh.draw()
        } finally {
            bindMainTarget()
            system.reset()
        }
    }

    fun unload() {
        var failure: Throwable? = null
        if (mesh.state == MeshStates.LOADED) {
            try {
                mesh.unload()
            } catch (error: Throwable) {
                failure = error
            }
        } else if (mesh.state == MeshStates.PREPARING) {
            mesh.drop()
        }
        if (shader.native.loaded) {
            try {
                shader.unload()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        framebuffer?.let {
            if (it.state == FramebufferState.COMPLETE) {
                try {
                    it.delete()
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
        }
        framebuffer = null
        initialized = false
        commands.clear()
        failure?.let { throw it }
    }

    private fun ensureFramebuffer() {
        val main = context.framebuffer.main
        val size = scaledFramebufferSize(main.size, main.scale)
        val current = framebuffer
        if (current?.size == size) return

        val candidate = context.system.createFramebuffer(size, 1.0f, texture = TextureModes.NEAREST)
        candidate.init()
        framebuffer = candidate
        if (current?.state == FramebufferState.COMPLETE) current.delete()
    }

    private fun bindMainTarget() {
        context.shaderPipeline.withPipeline { pipeline ->
            pipeline.bindViewTarget(RenderViewId.MAIN, context.framebuffer.main::bind)
        }
    }

    private fun cleanup(original: Throwable) {
        try {
            unload()
        } catch (cleanup: Throwable) {
            original.addSuppressed(cleanup)
        }
    }

    data class Command(
        val feature: EntityOutlineFeature,
        val color: RGBAColor,
    )
}
