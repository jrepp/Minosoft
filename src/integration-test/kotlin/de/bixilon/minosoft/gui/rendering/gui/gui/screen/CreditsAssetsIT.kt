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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen

import de.bixilon.kutil.latch.SimpleLatch
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.assets.AssetsLoader
import de.bixilon.minosoft.assets.minecraft.MinecraftAssetsManager
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["assets"])
class CreditsAssetsIT {

    fun `credits come from Minosoft or locally installed extension assets`() {
        val session = createSession(5)
        val latch = SimpleLatch(1)
        session::assets.forceSet(AssetsLoader.create(session.profiles.resources, session.version))
        session.assets.load(latch)
        latch.dec()
        latch.await()

        val provider = session.assets.getAssetsManager(CreditsContent.RESOURCE)
        val lines = CreditsContent.load(session.assets)

        assertTrue(provider != null)
        assertFalse(provider is MinecraftAssetsManager)
        assertTrue(lines.any { it.style == CreditsLineStyle.SECTION })
        assertTrue(lines.any { it.style == CreditsLineStyle.NAME })
        assertTrue(lines.any { it.text == "Minosoft contributors" })
        assertFalse(lines.any { "PLAYERNAME" in it.text })
        session.assets.unload()
    }
}
