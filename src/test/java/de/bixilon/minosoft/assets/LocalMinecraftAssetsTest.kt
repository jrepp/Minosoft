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
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMinecraftAssetsTest {

    @Test
    fun `enables both local Minecraft sources by default`() {
        assertEquals(LocalMinecraftAssets.ALL, LocalMinecraftAssets.from(ResourcesProfile()))
    }

    @Test
    fun `preserves independent profile source controls`() {
        val profile = ResourcesProfile()

        profile.assets.disableIndexAssets = true
        assertEquals(LocalMinecraftAssets(index = false, clientJar = true), LocalMinecraftAssets.from(profile))

        profile.assets.disableJarAssets = true
        assertEquals(LocalMinecraftAssets.NONE, LocalMinecraftAssets.from(profile))
    }
}
