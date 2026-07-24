/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels

fun interface FabricEntityVisibilityHook {
    fun refine(renderer: EntityRenderer<*>, native: EntityVisibilityLevels): EntityVisibilityLevels
}

object FabricEntityVisibilityHooks {
    private val hooks = FabricHookRegistry<FabricEntityVisibilityHook>("entity-visibility")

    fun register(owner: String, hook: FabricEntityVisibilityHook): AutoCloseable = hooks.register(owner, hook)

    fun registrations(): List<FabricHostHook<FabricEntityVisibilityHook>> = hooks.snapshot()

    fun refine(renderer: EntityRenderer<*>, native: EntityVisibilityLevels): EntityVisibilityLevels {
        return hooks.snapshot().fold(native) { visibility, registration ->
            val started = System.nanoTime()
            try {
                registration.hook.refine(renderer, visibility)
            } finally {
                FabricModDiagnostics.hookInvoked(registration.owner, "entity-visibility", System.nanoTime() - started)
            }
        }
    }
}
