/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.animator

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.texture.animator.SpriteUtil.mapNext
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.TextureData
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoaderResult
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class TextureAnimationTimelineTest {
    @Test
    fun `timeline position wraps independently from the current frame offset`() {
        val frames = arrayOf(
            AnimationFrame(50.milliseconds, TextureData(RGBA8Buffer(Vec2i(1, 1)))),
            AnimationFrame(50.milliseconds, TextureData(RGBA8Buffer(Vec2i(1, 1)))),
        )
        frames.mapNext()
        val animation = TextureAnimation(Texture(Loader), frames, interpolate = false)

        animation.update(75.milliseconds)
        assertEquals(75.milliseconds, animation.timelinePosition)
        assertEquals(frames[1], animation.frame)

        animation.update(50.milliseconds)
        assertEquals(25.milliseconds, animation.timelinePosition)
        assertEquals(frames[0], animation.frame)
    }

    private object Loader : TextureLoader {
        override fun load(context: RenderContext): TextureLoaderResult = error("not used")
    }
}
