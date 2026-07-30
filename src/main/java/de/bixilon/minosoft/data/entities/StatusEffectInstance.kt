/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */
package de.bixilon.minosoft.data.entities

import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.minosoft.data.Tickable
import de.bixilon.minosoft.data.registries.effects.StatusEffectType
import de.bixilon.minosoft.data.registries.effects.vision.VisionEffect
import de.bixilon.minosoft.protocol.network.session.play.tick.Ticks

data class StatusEffectInstance(
    val type: StatusEffectType,
    val amplifier: Int,
    val duration: Ticks,
) : Tickable {
    val start = now()
    val end = start + duration.duration
    var remaining = duration
        private set
    var factorCalculationData: StatusEffectFactorCalculationData? =
        if (type === VisionEffect.Darkness) StatusEffectFactorCalculationData(DARKNESS_PADDING_TICKS) else null
        private set

    val infinite: Boolean
        get() = duration.ticks == INFINITE_DURATION
    val expired: Boolean
        get() = !infinite && remaining.ticks <= 0
    val progress: Float
        get() = if (infinite) 1.0f else ((end - now()) / duration.duration).toFloat()


    override fun tick() {
        if (!infinite) {
            remaining--
        }
        factorCalculationData?.update(this)
    }

    fun setFactorCalculationData(data: StatusEffectFactorCalculationData?) {
        factorCalculationData = data
    }

    companion object {
        const val INFINITE_DURATION = -1
        private const val DARKNESS_PADDING_TICKS = 22
    }
}
