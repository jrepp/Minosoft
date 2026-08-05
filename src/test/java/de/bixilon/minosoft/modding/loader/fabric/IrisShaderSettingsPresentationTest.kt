/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import kotlin.test.Test
import kotlin.test.assertEquals

class IrisShaderSettingsPresentationTest {
    @Test
    fun `fallback labels split defines while preserving short acronyms`() {
        assertEquals("Shadow Map Resolution", shaderSettingLabel("shadowMapResolution"))
        assertEquals("Clouds Quality", shaderSettingLabel("CLOUDS_QUALITY"))
        assertEquals("LPV Enabled", shaderSettingLabel("LPV_ENABLED"))
        assertEquals("Screen Space Contact Shadows", shaderSettingLabel("SCREEN_SPACE_CONTACT_SHADOWS"))
    }

    @Test
    fun `boolean values use readable fallback labels`() {
        assertEquals("Enabled", shaderSettingValueLabel("true"))
        assertEquals("Disabled", shaderSettingValueLabel("false"))
        assertEquals("0.5", shaderSettingValueLabel("0.5"))
    }
}
