/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.texture.entity

/**
 * Pack-scoped values needed by ETF properties that do not belong to an entity
 * or play session. The Fabric pack owner installs one immutable snapshot and
 * removes exactly that snapshot when its registration scope closes.
 */
object EntityTextureRuntimeEnvironment {
    @Volatile
    private var loadedModIds: Set<String> = emptySet()

    @Synchronized
    fun installLoadedMods(ids: Collection<String>): AutoCloseable {
        check(loadedModIds.isEmpty()) { "An entity-texture runtime environment is already installed." }
        val installed = ids.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
            .toSet()
        loadedModIds = installed
        return AutoCloseable {
            synchronized(this) {
                if (loadedModIds === installed) loadedModIds = emptySet()
            }
        }
    }

    fun loadedMods(): Set<String> = loadedModIds
}
