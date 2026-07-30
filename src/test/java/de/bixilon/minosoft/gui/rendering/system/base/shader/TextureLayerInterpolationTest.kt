/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.system.base.shader

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TextureLayerInterpolationTest {
    @Test
    fun `texture array layer is flat across every rasterized primitive`() {
        val source = requireNotNull(
            javaClass.getResourceAsStream(
                "/assets/minosoft/rendering/shader/includes/animation.glsl",
            ),
        ).bufferedReader().use { it.readText() }

        assertContains(source, "flat out float finTextureLayer;")
        assertContains(source, "flat in float finTextureLayer;")
        assertFalse(Regex("""(?m)^\s*(?:in|out)\s+float\s+finTextureLayer\s*;""").containsMatchIn(source))
    }
}
