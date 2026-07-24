/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.session

import de.bixilon.minosoft.assets.multi.PriorityAssetsManager

/**
 * Effective `data/<namespace>/...` view for one session content generation.
 *
 * There is intentionally no client-JAR or network fallback. Only data packs
 * explicitly supplied by the user or the selected trajectory are mounted.
 */
class SessionDataPackManager : PriorityAssetsManager()
