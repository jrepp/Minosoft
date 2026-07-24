/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FabricPayloadChannelsTest {
    @Test
    fun `payload registrations are ordered owner scoped and independently removable`() {
        val channel = ResourceLocation.of("test:canary")
        val first = FabricClientPayloadChannels.register("test:first", channel) { }
        val second = FabricClientPayloadChannels.register("test:second", channel) { }

        assertEquals(listOf("test:first", "test:second"), FabricClientPayloadChannels.registrations().getValue(channel))
        first.close()
        first.close()
        assertEquals(listOf("test:second"), FabricClientPayloadChannels.registrations().getValue(channel))
        second.close()
        assertTrue(FabricClientPayloadChannels.registrations().isEmpty())
    }

    @Test
    fun `payload limit is a stable one mebibyte contract`() {
        assertEquals(1_048_576, FabricClientPayloadChannels.MAX_PAYLOAD_BYTES)
    }
}
