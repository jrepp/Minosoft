/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.protocol.buffers.OutByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FabricRemoteRegistrySyncTest {
    @Test
    fun `direct codec decodes grouped namespaces and delta encoded ids`() {
        val payload = OutByteBuffer().apply {
            writeVarInt(1)
            writeString("")
            writeVarInt(1)
            writeString("entity_type")
            writeVarInt(2)

            writeString("")
            writeVarInt(1)
            writeVarInt(0)
            writeVarInt(2)
            writeString("pig")
            writeString("cow")

            writeString("naturalist")
            writeVarInt(1)
            writeVarInt(149)
            writeVarInt(2)
            writeString("alligator")
            writeString("lizard_tail")
        }.toArray()

        val decoded = FabricDirectRegistryCodec.decode(payload)

        assertEquals(
            mapOf(
                ResourceLocation.of("minecraft:pig") to 0,
                ResourceLocation.of("minecraft:cow") to 1,
                ResourceLocation.of("naturalist:alligator") to 150,
                ResourceLocation.of("naturalist:lizard_tail") to 151,
            ),
            decoded.getValue(FabricRemoteRegistrySync.ENTITY_TYPE),
        )
    }

    @Test
    fun `direct codec rejects truncation and trailing storage`() {
        assertFailsWith<IllegalArgumentException> {
            FabricDirectRegistryCodec.decode(byteArrayOf(1, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            FabricDirectRegistryCodec.decode(byteArrayOf(0, 1))
        }
    }

    @Test
    fun `remote definitions are owner scoped and collision checked`() {
        val definition = FabricRemoteEntityDefinition(ResourceLocation.of("test:animal"), 1.0f, 2.0f)
        val registration = FabricRemoteRegistrySync.register("test:first", listOf(definition))
        try {
            assertEquals("test:first", FabricRemoteRegistrySync.definitions()[definition.identifier])
            assertFailsWith<IllegalArgumentException> {
                FabricRemoteRegistrySync.register("test:second", listOf(definition))
            }
        } finally {
            registration.close()
            registration.close()
        }
        assertTrue(definition.identifier !in FabricRemoteRegistrySync.definitions())
    }
}
