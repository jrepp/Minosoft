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

package de.bixilon.minosoft.data.world.audio

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.registries.blocks.types.building.stone.StoneBlock
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.input.interaction.InteractionTestUtil.tick
import de.bixilon.minosoft.input.interaction.InteractionTestUtil.unsafePress
import de.bixilon.minosoft.input.interaction.breaking.BreakHandler
import de.bixilon.minosoft.input.interaction.breaking.BreakHandlerTest
import de.bixilon.minosoft.input.interaction.breaking.executor.TestExecutor
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["audio"])
class BlockHitAudioTest {
    private data class PlayedSound(
        val sound: ResourceLocation,
        val position: Vec3d?,
        val volume: Float,
        val pitch: Float,
    )

    private class RecordingAudioPlayer : AbstractAudioPlayer {
        val played = mutableListOf<PlayedSound>()

        override fun play(sound: ResourceLocation, position: Vec3d?, volume: Float, pitch: Float) {
            played += PlayedSound(sound, position, volume, pitch)
        }

        override fun stopAll() = Unit

        override fun stop(sound: ResourceLocation) = Unit
    }

    fun localHitUsesBlockCenterAndHitSoundParameters() {
        val session = createSession(1)
        val position = BlockPosition(1, 2, 3)
        val state = session.registries.block[StoneBlock.Block]!!.states.default
        val group = state.block.soundGroup!!
        val audio = RecordingAudioPlayer()
        session.world.audio = audio

        BlockHitAudio.play(session, position, state)

        assertEquals(
            audio.played,
            listOf(PlayedSound(group.hit!!, Vec3d(1.5, 2.5, 3.5), (group.volume + 1.0f) / 8.0f, group.pitch * 0.5f)),
        )
    }

    fun remoteHitUsesWorldBlockAndIgnoresLocalPlayerAndCompletion() {
        val session = createSession(1)
        val position = BlockPosition(1, 2, 3)
        val state = session.registries.block[StoneBlock.Block]!!.states.default
        session.world[position] = state
        session.player.id = 7
        val audio = RecordingAudioPlayer()
        session.world.audio = audio

        BlockHitAudio.playRemote(session, breakerId = 12, position, progress = 0.25f)
        BlockHitAudio.playRemote(session, breakerId = 7, position, progress = 0.5f)
        BlockHitAudio.playRemote(session, breakerId = 12, position, progress = null)

        assertEquals(audio.played.size, 1)
        assertEquals(audio.played.single().position, Vec3d(1.5, 2.5, 3.5))
        assertEquals(audio.played.single().sound, state.block.soundGroup!!.hit)
    }

    fun survivalMiningPlaysFirstHitThenUsesBoundedCadence() {
        val session = createSession(1)
        session.player.physics.onGround = true
        BreakHandlerTest.createTarget(session, StoneBlock.Block.identifier, 1.0)
        val audio = RecordingAudioPlayer()
        session.world.audio = audio
        val handler = BreakHandler(session.camera.interactions)
        handler::executor.forceSet(TestExecutor(handler))

        handler.unsafePress()
        assertEquals(audio.played.size, 1)

        repeat(10) { handler.tick() }
        assertEquals(audio.played.size, 1)
        assertEquals(audio.played.map { it.position }.distinct(), listOf(Vec3d(1.5, 2.5, 3.5)))
    }
}
