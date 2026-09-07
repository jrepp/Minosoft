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

package de.bixilon.minosoft.data.world.view

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil
import de.bixilon.minosoft.protocol.packets.s2c.play.block.chunk.ViewDistanceS2CP
import de.bixilon.minosoft.protocol.protocol.buffers.play.PlayInByteBuffer
import org.testng.Assert.assertEquals
import org.testng.Assert.expectThrows
import org.testng.annotations.Test

class WorldViewServerDistanceTest {
    @Test
    fun `unannounced fallback follows incomplete loaded chunk bounds`() {
        val session = session()
        session.player.physics.forceTeleport(Vec3d(10.0 * 16.0, 64.0, 20.0 * 16.0))
        session.world.chunks.size.onCreate(ChunkPosition(4, 14))
        session.world.chunks.size.onCreate(ChunkPosition(16, 26))

        session.world.view.updateServerDistance()
        assertEquals(session.world.view.serverViewDistance, 6)

        session.player.physics.forceTeleport(Vec3d(11.0 * 16.0, 64.0, 20.0 * 16.0))
        session.world.view.updateServerDistance()
        assertEquals(session.world.view.serverViewDistance, 5)
    }

    @Test
    fun `announced packet distance survives movement and incomplete chunk arrival`() {
        val session = session()
        val packet = ViewDistanceS2CP(PlayInByteBuffer(byteArrayOf(6), session))

        packet.handle(session)
        assertEquals(session.world.view.serverViewDistance, 6)

        session.player.physics.forceTeleport(Vec3d(-31.0 * 16.0, 64.0, 47.0 * 16.0))
        session.world.chunks.size.onCreate(ChunkPosition(-32, 47))
        session.world.chunks.size.onCreate(ChunkPosition(-30, 48))
        session.world.view.updateServerDistance()
        assertEquals(session.world.view.serverViewDistance, 6)

        session.world.chunks.clear()
        assertEquals(session.world.view.serverViewDistance, 6)

        ViewDistanceS2CP(PlayInByteBuffer(byteArrayOf(4), session)).handle(session)
        assertEquals(session.world.view.serverViewDistance, 4)
        assertEquals(session.world.view.viewDistance, minOf(4, session.profiles.block.viewDistance))

        ViewDistanceS2CP(PlayInByteBuffer(byteArrayOf(12), session)).handle(session)
        assertEquals(session.world.view.serverViewDistance, 12)
        assertEquals(session.world.view.viewDistance, session.profiles.block.viewDistance)
    }

    @Test
    fun `negative announced distance rejects at the world boundary`() {
        val session = session()

        expectThrows(IllegalArgumentException::class.java) {
            session.world.view.announceServerViewDistance(-1)
        }
    }

    private fun session(): PlaySession = SessionTestUtil.createSession(version = "1.20.4").also { session ->
        session.world::view.forceSet(WorldView(session))
    }
}
