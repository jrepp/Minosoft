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

package de.bixilon.minosoft.gui.rendering.renderer.renderer

import de.bixilon.kutil.cast.CastUtil.unsafeCast
import de.bixilon.kutil.concurrent.worker.unconditional.UnconditionalWorker
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.latch.ParentLatch
import de.bixilon.kutil.latch.SimpleLatch
import de.bixilon.kutil.profiler.stack.StackedProfiler.Companion.invoke
import de.bixilon.kutil.reflection.ReflectionUtil.realName
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.RenderUtil.runAsync
import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable
import de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.RendererPipeline
import de.bixilon.minosoft.gui.rendering.renderer.renderer.world.WorldRenderer
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class RendererManager(
    val context: RenderContext,
) : Drawable, Iterable<Renderer> {
    private val list: MutableList<Renderer> = mutableListOf()
    private val renderers: MutableMap<RendererBuilder<*>, Renderer> = linkedMapOf()
    val pipeline = RendererPipeline(this)
    private val session = context.session
    private val preparedRenderers = LinkedBlockingQueue<Renderer>()
    private val preparationFailures = ConcurrentHashMap<Renderer, Throwable>()


    fun <T : Renderer> register(renderer: T): T {
        this.list += renderer

        return renderer
    }

    fun <T : Renderer> register(builder: RendererBuilder<T>): T? {
        val renderer = builder.build(session, context) ?: return null
        val previous = renderers.put(builder, renderer)
        if (previous != null) {
            Log.log(LogMessageType.RENDERING, LogLevels.WARN) { "Renderer $previous ($builder) got replaced by $renderer!" }
        }
        list += renderer
        pipeline += renderer
        return renderer
    }

    operator fun plusAssign(builder: RendererBuilder<*>) {
        register(builder)
    }

    operator fun <T : Renderer> get(builder: RendererBuilder<T>): T? {
        return renderers[builder].unsafeCast()
    }

    private fun runAsync(latch: AbstractLatch?, runnable: (Renderer, AbstractLatch) -> Unit) {
        val inner = if (latch == null) SimpleLatch(0) else ParentLatch(0, latch)

        val worker = UnconditionalWorker()
        for (renderer in list) {
            worker += { runnable.invoke(renderer, inner) }
        }
        worker.work(inner)
    }

    fun init(latch: AbstractLatch) {
        for (renderer in list) {
            if (renderer !is WorldRenderer) continue
            renderer.registerPasses()
        }
        pipeline.rebuild()

        runAsync(latch, Renderer::asyncInit)

        for (renderer in list) {
            renderer.init(latch)
        }
    }

    fun postInit(latch: AbstractLatch) {
        for (renderer in list) {
            renderer.postInit(latch)
        }
    }

    private fun prepare() {
        preparedRenderers.clear()
        preparationFailures.clear()
        val total = list.size

        for (renderer in list) {
            val name = renderer::class.java.realName
            val preFailure = try {
                context.profiler("pre $name") { renderer.prePrepareDraw() }
                null
            } catch (error: Throwable) {
                error
            }
            if (preFailure != null) {
                preparationFailures[renderer] = preFailure
                preparedRenderers += renderer
                continue
            }
            if (renderer is AsyncRenderer) {
                context.profiler("?async $name") {
                    try {
                        context.runAsync {
                            val failure = try {
                                renderer.prepareDrawAsync()
                                null
                            } catch (error: Throwable) {
                                error
                            }
                            if (failure != null) preparationFailures[renderer] = failure
                            preparedRenderers += renderer
                        }
                    } catch (error: Throwable) {
                        preparationFailures[renderer] = error
                        preparedRenderers += renderer
                    }
                }
            } else {
                preparedRenderers += renderer
            }
        }

        var done = 0
        var failure: Throwable? = null
        while (true) {
            if (done >= total) break
            val renderer = context.profiler("wait") { preparedRenderers.take() }
            val asyncFailure = preparationFailures.remove(renderer)
            if (asyncFailure == null) {
                val name = renderer::class.java.realName
                try {
                    context.profiler("post $name") { renderer.postPrepareDraw() }
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            } else {
                failure?.addSuppressed(asyncFailure) ?: run { failure = asyncFailure }
            }
            done++
        }
        failure?.let { throw it }
    }

    private fun finishFrame() {
        var failure: Throwable? = null
        for (renderer in list) {
            try {
                renderer.postDraw()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    override fun draw() {
        context.profiler("draw") {
            pipeline.draw(
                prepare = { context.profiler("prepare") { prepare() } },
                complete = { context.profiler("post draw") { finishFrame() } },
            )
        }
    }

    override fun iterator(): Iterator<Renderer> {
        return list.iterator()
    }

    fun unload() {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        cleanup(pipeline::close)
        list.forEach { candidate -> cleanup(candidate::unload) }
        failure?.let { throw it }
    }
}
