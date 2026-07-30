/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.sky.box

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkyboxRendererTest {

    @Test
    fun `reference texture override is reversible and does not replace authored state`() {
        val authored = ResourceLocation.of("minecraft:environment/end_sky")
        val override = ResourceLocation.of("minosoft:debug/sky")

        assertNull(SkyboxRenderer.selectedTexture(null, null))
        assertEquals(authored, SkyboxRenderer.selectedTexture(authored, null))
        assertEquals(override, SkyboxRenderer.selectedTexture(authored, override))
        assertEquals(authored, SkyboxRenderer.selectedTexture(authored, null))
    }
}
