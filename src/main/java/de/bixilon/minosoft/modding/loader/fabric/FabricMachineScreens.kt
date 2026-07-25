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

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer

data class MachineGaugeState(
    val value: Long,
    val capacity: Long,
) {
    init {
        require(capacity > 0L) { "Machine gauge capacity must be positive." }
        require(value in 0L..capacity) { "Machine gauge value must be within its capacity." }
    }

    val progress: Float get() = value.toFloat() / capacity
}

data class MachineTankState(
    val id: String,
    val fluid: ResourceLocation?,
    val amount: Long,
    val capacity: Long,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid machine tank id: $id" }
        require(capacity > 0L) { "Machine tank capacity must be positive." }
        require(amount in 0L..capacity) { "Machine tank amount must be within its capacity." }
        require(fluid != null || amount == 0L) { "A non-empty tank must identify its fluid." }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]{0,127}")
    }
}

data class MachineScreenSnapshot(
    val revision: Long,
    val energy: MachineGaugeState? = null,
    val progress: MachineGaugeState? = null,
    val tanks: List<MachineTankState> = emptyList(),
    val activeTab: String = "main",
    val properties: Map<String, Long> = emptyMap(),
) {
    init {
        require(revision >= 0L) { "Machine snapshot revision must be non-negative." }
        require(tanks.size <= MAX_TANKS) { "Machine snapshot exceeds $MAX_TANKS tanks." }
        require(tanks.map(MachineTankState::id).distinct().size == tanks.size) { "Machine tank ids must be unique." }
        require(properties.size <= MAX_PROPERTIES) { "Machine snapshot exceeds $MAX_PROPERTIES properties." }
        require(properties.keys.all(ID_PATTERN::matches)) { "Machine snapshot contains an invalid property id." }
        require(ID_PATTERN.matches(activeTab)) { "Invalid machine tab id: $activeTab" }
    }

    companion object {
        const val MAX_TANKS = 64
        const val MAX_PROPERTIES = 1_024
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]{0,127}")
    }
}

class SynchronizedMachineScreenState(
    initial: MachineScreenSnapshot,
) {
    var snapshot: MachineScreenSnapshot = initial
        private set

    @Synchronized
    fun update(candidate: MachineScreenSnapshot): Boolean {
        if (candidate.revision <= snapshot.revision) return false
        snapshot = candidate
        return true
    }
}

class FabricMachineControl(
    val channel: ResourceLocation,
    private val payload: () -> ByteArray,
) {
    fun send(renderer: GUIRenderer) {
        val bytes = payload()
        require(bytes.size <= FabricClientPayloadChannels.MAX_PAYLOAD_BYTES) {
            "Machine control payload exceeds ${FabricClientPayloadChannels.MAX_PAYLOAD_BYTES} bytes."
        }
        FabricClientPayloadChannels.send(renderer.session, channel, bytes)
    }
}
