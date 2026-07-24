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

package de.bixilon.minosoft.assets.source

import java.io.IOException

class LocalAssetUnavailableException(
    kind: String,
    hash: String,
) : IOException(
    "Local $kind $hash is unavailable. Install or import a local asset pack or mod; network retrieval from official Minecraft services is disabled."
)

object LocalAssetSource {

    fun <T : Any> require(kind: String, hash: String, value: T?): T {
        return value ?: throw LocalAssetUnavailableException(kind, hash)
    }
}
