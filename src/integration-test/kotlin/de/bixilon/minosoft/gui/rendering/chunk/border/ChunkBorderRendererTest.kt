/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.border

import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["rendering"])
class ChunkBorderRendererTest {
    fun `reference gate overrides presentation without mutating the profile`() {
        val context = EntityRendererTestUtil.createContext()
        val profile = context.session.profiles.rendering.chunkBorder
        val renderer = ChunkBorderRenderer(context.session, context)
        val configured = profile.enabled

        renderer.referenceEnabledOverride = !configured
        assertEquals(renderer.effectiveEnabled, !configured)
        assertEquals(profile.enabled, configured)

        renderer.referenceEnabledOverride = null
        assertEquals(renderer.effectiveEnabled, configured)
        assertEquals(profile.enabled, configured)
    }
}
