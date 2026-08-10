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

package de.bixilon.minosoft.assets

import de.bixilon.minosoft.config.profile.profiles.resources.ResourcesProfile

data class LocalMinecraftAssets(
    val index: Boolean,
    val clientJar: Boolean,
) {
    companion object {
        val ALL = LocalMinecraftAssets(index = true, clientJar = true)
        val NONE = LocalMinecraftAssets(index = false, clientJar = false)

        fun from(profile: ResourcesProfile) = LocalMinecraftAssets(
            index = !profile.assets.disableIndexAssets,
            clientJar = !profile.assets.disableJarAssets,
        )
    }
}
