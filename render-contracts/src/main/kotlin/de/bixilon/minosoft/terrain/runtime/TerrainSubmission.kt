/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.terrain.runtime

import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class TerrainSubmissionSerial(val value: Long) : Comparable<TerrainSubmissionSerial> {
    init {
        require(value > 0L) { "Terrain submission serial must be positive" }
    }

    override fun compareTo(other: TerrainSubmissionSerial): Int = value.compareTo(other.value)

    fun next(): TerrainSubmissionSerial = TerrainSubmissionSerial(Math.addExact(value, 1L))
}

data class TerrainSubmission(
    val deviceRuntime: TerrainDeviceRuntimeId,
    val serial: TerrainSubmissionSerial,
)

/**
 * One monotonic serial source for every terrain submission issued by a device
 * runtime. Sharing this sequencer across providers prevents two independent
 * storage domains from assigning the same device submission identity.
 */
class TerrainSubmissionSequencer(
    val deviceRuntime: TerrainDeviceRuntimeId,
) {
    private val nextSerial = AtomicLong(1L)

    fun next(): TerrainSubmission = TerrainSubmission(
        deviceRuntime = deviceRuntime,
        serial = TerrainSubmissionSerial(
            nextSerial.getAndUpdate { current -> Math.incrementExact(current) },
        ),
    )

    fun latest(): TerrainSubmissionSerial? = nextSerial.get().let { next ->
        if (next == 1L) null else TerrainSubmissionSerial(next - 1L)
    }
}

enum class TerrainSubmissionState(val permitsRangeReuse: Boolean) {
    PENDING(false),
    COMPLETE(true),
    FAILED(false),
    DEVICE_INVALIDATED(false),
}

/**
 * Device-neutral completion boundary. Implementations may use graphics fences,
 * but neither a native fence nor a graphics API handle crosses this interface.
 *
 * Completion is queried per serial so delayed submissions may complete out of
 * order without implying completion of an earlier serial.
 */
interface TerrainSubmissionCompletion {
    val deviceRuntime: TerrainDeviceRuntimeId

    fun state(serial: TerrainSubmissionSerial): TerrainSubmissionState
}
