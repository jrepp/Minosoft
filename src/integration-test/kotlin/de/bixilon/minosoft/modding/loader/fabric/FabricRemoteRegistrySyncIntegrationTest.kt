/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PacketTestUtil.assertPacket
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.protocol.packets.c2s.common.ChannelC2SP
import de.bixilon.minosoft.protocol.protocol.buffers.OutByteBuffer
import de.bixilon.minosoft.protocol.protocol.buffers.play.PlayInByteBuffer
import de.bixilon.minosoft.protocol.protocol.buffers.play.PlayOutByteBuffer
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

@Test(groups = ["fabric", "entities"])
class FabricRemoteRegistrySyncIntegrationTest {
    fun `configuration handshake materializes a routed remote living entity at its server id`() {
        IT.VERSION // initialize the integration-test version catalog
        val session = createSession(version = "1.20.4")
        val identifier = ResourceLocation.of("test:remote_animal")
        val provider = FabricRemoteRegistrySync.install("test:fabric-api")
        val definition = FabricRemoteRegistrySync.register(
            "test:dependent-mod",
            listOf(FabricRemoteEntityDefinition(identifier, 1.25f, 2.5f)),
        )
        try {
            val advertised = FabricRemoteRegistrySync.COMPLETE.toString().toByteArray(StandardCharsets.US_ASCII)
            assertTrue(FabricRemoteRegistrySync.handleConfiguration(session, FabricRemoteRegistrySync.REGISTER, advertised))
            val registration = session.assertPacket(ChannelC2SP::class.java)
            assertEquals(registration.channel, FabricRemoteRegistrySync.REGISTER)
            assertTrue(registration.rawData)
            assertEquals(
                registration.data.toString(StandardCharsets.US_ASCII).split('\u0000'),
                listOf(FabricRemoteRegistrySync.DIRECT.toString(), "minosoft:registry_sync"),
            )

            val direct = directEntityRegistry(identifier, 250)
            assertTrue(FabricRemoteRegistrySync.handleConfiguration(session, FabricRemoteRegistrySync.DIRECT, direct))
            assertTrue(FabricRemoteRegistrySync.handleConfiguration(session, FabricRemoteRegistrySync.DIRECT, byteArrayOf()))
            val complete = session.assertPacket(ChannelC2SP::class.java)
            assertEquals(complete.channel, FabricRemoteRegistrySync.COMPLETE)
            assertTrue(complete.rawData)
            assertTrue(complete.data.isEmpty())

            val type = requireNotNull(session.registries.entityType.getOrNull(250))
            assertEquals(type.identifier, identifier)
            assertEquals(type.width, 1.25f)
            assertEquals(type.height, 2.5f)
            val entity = requireNotNull(type.build(session, Vec3d.EMPTY, EntityRotation.EMPTY, null, UUID.randomUUID(), session.version.versionId))
            assertTrue(entity is LivingEntity)
            assertEquals(FabricRemoteRegistrySync.state(session)?.entityTypes?.get(identifier), 250)
        } finally {
            definition.close()
            provider.close()
        }
        assertNull(FabricRemoteRegistrySync.state(session))
    }

    fun `modern custom payload preserves the protocol byte array envelope`() {
        IT.VERSION
        val session = createSession(version = "1.20.4")
        val payload = byteArrayOf(0x01, 0x02, 0x7F)
        val packet = ChannelC2SP(ResourceLocation.of("test:payload"), payload)
        val output = PlayOutByteBuffer(session)

        packet.write(output)

        val input = PlayInByteBuffer(output.toArray(), session)
        assertEquals(input.readResourceLocation(), packet.channel)
        assertEquals(input.readByteArray().toList(), payload.toList())
        assertTrue(input.readRemaining().isEmpty())
    }

    fun `raw custom payload writes the packet remainder`() {
        IT.VERSION
        val session = createSession(version = "1.20.4")
        val payload = byteArrayOf(0x01, 0x02, 0x7F)
        val packet = ChannelC2SP(ResourceLocation.of("test:payload"), payload, rawData = true)
        val output = PlayOutByteBuffer(session)

        packet.write(output)

        val input = PlayInByteBuffer(output.toArray(), session)
        assertEquals(input.readResourceLocation(), packet.channel)
        assertEquals(input.readRemaining().toList(), payload.toList())
    }

    private fun directEntityRegistry(identifier: ResourceLocation, rawId: Int): ByteArray = OutByteBuffer().apply {
        writeVarInt(1)
        writeString("")
        writeVarInt(1)
        writeString("entity_type")
        writeVarInt(1)
        writeString(identifier.namespace)
        writeVarInt(1)
        writeVarInt(rawId)
        writeVarInt(1)
        writeString(identifier.path)
    }.toArray()

}
