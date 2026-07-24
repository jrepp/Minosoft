/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.textures.properties

import com.fasterxml.jackson.annotation.JsonProperty
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.protocol.network.session.play.tick.Ticks.Companion.ticks
import kotlin.time.Duration

data class AnimationProperties(
    val interpolate: Boolean = false,
    val width: Int = -1,
    val height: Int = -1,
    @JsonProperty("frametime") val frameTime: Int = 1,
    val frames: List<Any> = emptyList(),
) {


    fun create(size: Vec2i): FrameData {
        require(size.x > 0 && size.y > 0) { "Animation texture dimensions must be positive: $size" }
        val width = if (this.width <= 0) size.x else this.width
        val height = if (this.height <= 0) width else this.height
        require(width > 0 && height > 0) { "Animation frame dimensions must be positive: ${width}x$height" }
        require(size.x % width == 0 && size.y % height == 0) { "Animation ${size.x}x${size.y} is not divisible into ${width}x$height frames" }
        require(frameTime > 0) { "Animation frametime must be positive: $frameTime" }

        val columns = size.x / width
        val count = columns.toLong() * (size.y / height)
        require(count in 1..MAX_ANIMATION_FRAMES.toLong()) {
            "Animation frame count $count exceeds the $MAX_ANIMATION_FRAMES frame limit."
        }

        val frames: MutableList<Frame> = mutableListOf()
        val frameTime = this.frameTime.ticks.duration

        if (this.frames.isEmpty()) {
            // automatic
            for (i in 0 until count.toInt()) {
                frames += Frame(frameTime, i)
            }
        } else {
            require(this.frames.size <= MAX_ANIMATION_FRAMES) {
                "Animation frame sequence exceeds the $MAX_ANIMATION_FRAMES frame limit."
            }
            for (frame in this.frames) {
                when (frame) {
                    is Number -> frames += Frame(frameTime, frame.toInt())
                    is Map<*, *> -> {
                        val index = (frame["index"] as? Number)?.toInt() ?: continue
                        val ticks = (frame["time"] as? Number)?.toInt() ?: this.frameTime
                        require(ticks > 0) { "Animation frame time must be positive: $ticks" }
                        frames += Frame(ticks.ticks.duration, index)
                    }
                }
            }
            require(frames.isNotEmpty()) { "Animation frame sequence contains no valid frames." }
        }
        require(frames.all { it.texture in 0 until count.toInt() }) {
            "Animation frame index must be between 0 and ${count - 1}."
        }

        return FrameData(frames, count.toInt(), Vec2i(width, height), columns)
    }

    data class FrameData(
        val frames: List<Frame>,
        val textures: Int,
        val size: Vec2i,
        val columns: Int,
    ) {
        fun origin(index: Int) = Vec2i((index % columns) * size.x, (index / columns) * size.y)
    }

    data class Frame(
        val time: Duration,
        val texture: Int,
    )

    private companion object {
        const val MAX_ANIMATION_FRAMES = 65_536
    }
}
