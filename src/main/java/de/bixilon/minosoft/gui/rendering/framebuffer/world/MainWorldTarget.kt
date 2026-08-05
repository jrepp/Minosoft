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

package de.bixilon.minosoft.gui.rendering.framebuffer.world

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.cast.CastUtil.unsafeNull
import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferMeshBuilder
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.gui.rendering.framebuffer.IntegratedFramebuffer
import de.bixilon.minosoft.gui.rendering.framebuffer.world.`fun`.FunEffectManager
import de.bixilon.minosoft.gui.rendering.framebuffer.world.overlay.OverlayManager
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorAttachment
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourceId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetDescriptor
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.Framebuffer
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.depth.DepthModes
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.texture.TextureModes
import kotlin.math.max
import kotlin.math.min

internal fun effectiveWorldScale(configured: Float, retinaLimit: Float, systemScale: Float): Float {
    require(configured.isFinite() && configured > 0.0f) { "Configured world scale must be finite and positive" }
    require(retinaLimit.isFinite() && retinaLimit > 0.0f) { "Retina world scale must be finite and positive" }
    require(systemScale.isFinite() && systemScale > 0.0f) { "Window system scale must be finite and positive" }
    return if (systemScale > 1.25f) min(configured, retinaLimit) else configured
}

class MainWorldTarget(
    override val context: RenderContext,
) : IntegratedFramebuffer {
    val overlay = OverlayManager(context)
    val `fun` = FunEffectManager(context)
    private val defaultShader = context.system.shader.create(minosoft("framebuffer/world")) { FramebufferShader(it) }
    val descriptor = RenderTargetDescriptor(
        id = RenderResourceId("minosoft:main-world"),
        size = RenderTargetSize.Relative(1.0f),
        colorAttachments = listOf(
            RenderColorAttachment(
                id = RenderResourceId("minosoft:main-world-color"),
                format = RenderColorFormat.RGBA8,
                clear = RenderClearPolicy.CLEAR,
            ),
        ),
        depth = RenderDepthFormat.DEPTH24,
    )
    override val shader: FramebufferShader get() = defaultShader
    override var framebuffer: Framebuffer = unsafeNull()
    override val mesh = FramebufferMeshBuilder(context).bake()
    override var polygonMode: PolygonModes = PolygonModes.DEFAULT

    override var size = Vec2i(1, 1)
    override var scale = 1.0f
    private var configuredScale = 1.0f
    private var retinaLimit = 0.75f

    private fun updateScale() {
        val systemScale = max(context.window.systemScale.x, context.window.systemScale.y)
        scale = effectiveWorldScale(configuredScale, retinaLimit, systemScale)
    }

    override fun init() {
        super.init() // TODO: init defaultShader

        overlay.init()
        context.session.profiles.rendering.quality.resolution::worldScale.observe(this, instant = true) {
            configuredScale = it
            updateScale()
        }
        context.session.profiles.rendering.quality.resolution::retinaWorldScale.observe(this, instant = true) {
            retinaLimit = it
            updateScale()
        }
        context.window::systemScale.observe(this, instant = true) { updateScale() }
    }

    override fun create() = context.system.createFramebuffer(this.size, this.scale, texture = TextureModes.NEAREST, depth = DepthModes.DEPTH24)

    override fun postInit() {
        super.postInit()
        overlay.postInit()
    }

    override fun draw() {
        `fun`.preDraw()
        val effect = `fun`.shader
        if (effect != null) {
            super.draw(effect)
        } else {
            context.shaderPipeline.withPipeline { pipeline ->
                super.draw(pipeline.composite(defaultShader))
            }
        }
    }
}
