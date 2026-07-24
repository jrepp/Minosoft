/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.datapack

import de.bixilon.minosoft.data.entities.entities.Entity

/**
 * Narrow world boundary for the local datapack command authority. Keeping
 * selection and removal behind this interface lets the function runtime remain
 * usable in headless parser/evaluator tests.
 */
interface DataPackEntityAccess {
    fun select(selector: String, context: DataPackCommandContext): List<Entity>
    fun synchronize(entity: Entity)
    fun remove(entity: Entity)
}
