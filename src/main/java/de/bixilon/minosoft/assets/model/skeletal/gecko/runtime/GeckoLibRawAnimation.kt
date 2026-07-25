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

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

enum class GeckoLibRawLoopType {
    DEFAULT,
    PLAY_ONCE,
    HOLD_ON_LAST_FRAME,
    LOOP,
}

data class GeckoLibRawAnimationStage(
    val animation: String,
    val loopType: GeckoLibRawLoopType,
    val additionalTicks: Int = 0,
    val customLoopType: String? = null,
) {
    val isWait get() = animation == WAIT

    init {
        require(animation.isNotBlank() && animation.length <= MAX_NAME_LENGTH) {
            "GeckoLib raw animation stage name must contain 1..$MAX_NAME_LENGTH characters."
        }
        require(additionalTicks in 0..MAX_WAIT_TICKS) {
            "GeckoLib raw animation wait must be within 0..$MAX_WAIT_TICKS ticks."
        }
        require(isWait || additionalTicks == 0) {
            "Only GeckoLib wait stages may contain additional ticks."
        }
        require(customLoopType == null || !isWait && customLoopType.isNotBlank() && customLoopType.length <= MAX_LOOP_TYPE_LENGTH) {
            "GeckoLib custom loop type must contain 1..$MAX_LOOP_TYPE_LENGTH characters on a non-wait stage."
        }
    }

    companion object {
        const val WAIT = "internal.wait"
        private const val MAX_NAME_LENGTH = 1_024
        private const val MAX_LOOP_TYPE_LENGTH = 256
        private const val MAX_WAIT_TICKS = 72_000
    }
}

/**
 * Source-native form of GeckoLib 4.4.4 RawAnimation. Like the pinned API, its
 * builder methods append stages and return the same instance for chaining.
 */
class GeckoLibRawAnimation private constructor(
    private val mutableStages: MutableList<GeckoLibRawAnimationStage>,
) {
    val stages: List<GeckoLibRawAnimationStage> get() = mutableStages.toList()

    fun thenPlay(animation: String) = then(animation, GeckoLibRawLoopType.DEFAULT)

    fun thenLoop(animation: String) = then(animation, GeckoLibRawLoopType.LOOP)

    fun thenWait(ticks: Int): GeckoLibRawAnimation {
        append(GeckoLibRawAnimationStage(GeckoLibRawAnimationStage.WAIT, GeckoLibRawLoopType.PLAY_ONCE, ticks))
        return this
    }

    fun thenPlayAndHold(animation: String) = then(animation, GeckoLibRawLoopType.HOLD_ON_LAST_FRAME)

    fun thenPlayXTimes(animation: String, count: Int): GeckoLibRawAnimation {
        require(count in 0..MAX_REPEAT_COUNT) {
            "GeckoLib raw animation repeat count must be within 0..$MAX_REPEAT_COUNT."
        }
        repeat(count) { index ->
            then(
                animation,
                if (index == count - 1) GeckoLibRawLoopType.DEFAULT else GeckoLibRawLoopType.PLAY_ONCE,
            )
        }
        return this
    }

    fun then(animation: String, loopType: GeckoLibRawLoopType): GeckoLibRawAnimation {
        append(GeckoLibRawAnimationStage(animation, loopType))
        return this
    }

    fun then(animation: String, loopType: String): GeckoLibRawAnimation {
        val normalized = loopType.lowercase()
        val builtIn = when (normalized) {
            "default" -> GeckoLibRawLoopType.DEFAULT
            "false", "play_once" -> GeckoLibRawLoopType.PLAY_ONCE
            "hold_on_last_frame" -> GeckoLibRawLoopType.HOLD_ON_LAST_FRAME
            "true", "loop" -> GeckoLibRawLoopType.LOOP
            else -> null
        }
        append(
            if (builtIn == null) {
                GeckoLibRawAnimationStage(animation, GeckoLibRawLoopType.DEFAULT, customLoopType = normalized)
            } else {
                GeckoLibRawAnimationStage(animation, builtIn)
            },
        )
        return this
    }

    private fun append(stage: GeckoLibRawAnimationStage) {
        require(mutableStages.size < MAX_STAGES) {
            "GeckoLib raw animation exceeds the $MAX_STAGES stage limit."
        }
        mutableStages += stage
    }

    override fun equals(other: Any?): Boolean {
        return other is GeckoLibRawAnimation && mutableStages == other.mutableStages
    }

    override fun hashCode(): Int = mutableStages.hashCode()

    companion object {
        private const val MAX_STAGES = 4_096
        private const val MAX_REPEAT_COUNT = 4_096

        fun begin() = GeckoLibRawAnimation(mutableListOf())

        fun copyOf(animation: GeckoLibRawAnimation) = GeckoLibRawAnimation(animation.mutableStages.toMutableList())
    }
}
