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
import de.bixilon.minosoft.data.physics.PhysicsTestUtil.createPlayer
import de.bixilon.minosoft.data.physics.PhysicsTestUtil.runTicks
import de.bixilon.minosoft.data.registries.blocks.types.building.stone.StoneBlock
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.WorldTestUtil.fill
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.input.camera.PlayerMovementInput
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["audio"])
class StepFallAudioTest {
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

    private fun stoneSession(size: Int = 5): PlaySession {
        val session = createSession(size)
        val state = session.registries.block[StoneBlock.Block]!!.states.default
        session.world.fill(BlockPosition(-20, 0, -20), BlockPosition(20, 0, 20), state)
        return session
    }

    fun stepAudioUsesStepSoundAndGroupParameters() {
        val session = createSession(1)
        val position = BlockPosition(1, 2, 3)
        val state = session.registries.block[StoneBlock.Block]!!.states.default
        val group = state.block.soundGroup!!
        val audio = RecordingAudioPlayer()
        session.world.audio = audio

        BlockStepAudio.play(session, position, state)

        assertEquals(audio.played.single().sound, group.step)
        assertEquals(audio.played.single().volume, group.volume * 0.15f)
        assertEquals(audio.played.single().pitch, group.pitch)
    }

    fun fallAudioUsesFallSoundAndGroupParameters() {
        val session = createSession(1)
        val position = BlockPosition(1, 2, 3)
        val state = session.registries.block[StoneBlock.Block]!!.states.default
        val group = state.block.soundGroup!!
        val audio = RecordingAudioPlayer()
        session.world.audio = audio

        BlockFallAudio.play(session, position, state)

        assertEquals(audio.played.single().sound, group.fall)
        assertEquals(audio.played.single().volume, (group.volume + 1.0f) / 2.0f)
        assertEquals(audio.played.single().pitch, group.pitch * 0.75f)
    }

    fun fallingPlaysFallSoundOnLanding() {
        val session = stoneSession()
        val state = session.registries.block[StoneBlock.Block]!!.states.default
        val group = state.block.soundGroup!!
        val player = createPlayer(session)
        val audio = RecordingAudioPlayer()
        session.world.audio = audio

        player.forceTeleport(Vec3d(6.0, 5.0, 6.0))
        player.runTicks(20)

        assertTrue(audio.played.any { it.sound == group.fall })
    }

    fun walkingPlaysStepSounds() {
        val session = stoneSession()
        val state = session.registries.block[StoneBlock.Block]!!.states.default
        val group = state.block.soundGroup!!
        val player = createPlayer(session)
        val audio = RecordingAudioPlayer()
        session.world.audio = audio

        player.forceTeleport(Vec3d(6.0, 1.0, 6.0))
        player.input = PlayerMovementInput(forward = true)
        player.runTicks(50)

        assertTrue(audio.played.any { it.sound == group.step })
    }
}
