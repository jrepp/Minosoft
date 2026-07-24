/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.renderer.living

/**
 * Renderers whose retained skeletal feature can be replaced at the next safe
 * entity-prepare boundary after a content generation swap.
 */
interface ContentModelReloadable {
    fun reloadContentModel()
}
