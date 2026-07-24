/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.dev.canary

import de.bixilon.minosoft.modding.loader.mod.ModMain

object HotReloadCanary : ModMain() {
    const val MARKER = "canary-v1"

    override fun init() {
        logger.info { "HOT_RELOAD_CANARY marker=$MARKER" }
    }
}
