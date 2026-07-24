/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.gui.rendering.RenderContext

interface FabricFrameHook {
    fun beforeFrame(context: RenderContext) = Unit
    fun beforeQueueFlush(context: RenderContext) = Unit
    fun afterFrame(context: RenderContext) = Unit
}

object FabricFrameHooks {
    private val hooks = FabricHookRegistry<FabricFrameHook>("frame", "frame-batching")

    fun register(owner: String, hook: FabricFrameHook): AutoCloseable = hooks.register(owner, hook)

    fun registrations(): List<FabricHostHook<FabricFrameHook>> = hooks.snapshot()

    fun beforeFrame(context: RenderContext) = invoke { it.beforeFrame(context) }

    fun beforeQueueFlush(context: RenderContext) = invoke { it.beforeQueueFlush(context) }

    fun afterFrame(context: RenderContext) = invoke { it.afterFrame(context) }

    private inline fun invoke(invocation: (FabricFrameHook) -> Unit) {
        hooks.snapshot().forEach { registration ->
            val started = System.nanoTime()
            try {
                invocation(registration.hook)
            } finally {
                FabricModDiagnostics.hookInvoked(registration.owner, "frame-batching", System.nanoTime() - started)
            }
        }
    }
}
