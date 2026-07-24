/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererBuilder

object FabricRendererRegistry {
    private val hooks = FabricHookRegistry<RendererBuilder<*>>("renderer", "chunk-render-scheduling")

    fun register(owner: String, builder: RendererBuilder<*>): AutoCloseable = hooks.register(owner, builder)

    fun snapshot(): List<RendererBuilder<*>> = hooks.snapshot().map { it.hook }

    fun registrations(): List<FabricHostHook<RendererBuilder<*>>> = hooks.snapshot()
}
