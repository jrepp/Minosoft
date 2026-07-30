/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.debug.fabric;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DistantHorizonsLodServerTest {
    @Test
    void requestCodecIsVersionedBoundedAndExact() throws IOException {
        byte[] request = request(37, List.of(new ChunkPos(-4, 9), new ChunkPos(18, -21)));
        DistantHorizonsLodServer.Request decoded = DistantHorizonsLodServer.decodeRequest(request);

        assertEquals(37, decoded.requestId());
        assertEquals(List.of(new ChunkPos(-4, 9), new ChunkPos(18, -21)), decoded.positions());

        byte[] trailing = java.util.Arrays.copyOf(request, request.length + 1);
        assertThrows(
            IllegalArgumentException.class,
            () -> DistantHorizonsLodServer.decodeRequest(trailing)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DistantHorizonsLodServer.decodeRequest(
                request(1, java.util.Collections.nCopies(33, new ChunkPos(0, 0)))
            )
        );
    }

    @Test
    void radiusCheckCannotOverflowAtIntegerCoordinateLimits() {
        ChunkPos center = new ChunkPos(0, 0);

        assertTrue(DistantHorizonsLodServer.withinRadius(new ChunkPos(256, -256), center));
        assertFalse(DistantHorizonsLodServer.withinRadius(new ChunkPos(257, 0), center));
        assertFalse(DistantHorizonsLodServer.withinRadius(new ChunkPos(Integer.MIN_VALUE, 0), center));
        assertFalse(DistantHorizonsLodServer.withinRadius(new ChunkPos(Integer.MAX_VALUE, 0), center));
        assertFalse(
            DistantHorizonsLodServer.withinRadius(
                new ChunkPos(Integer.MIN_VALUE, Integer.MAX_VALUE),
                new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE)
            )
        );
    }

    @Test
    void helloAndResponseUseTheClientWireContract() throws IOException {
        byte[] hello = DistantHorizonsLodServer.hello();
        assertEquals(10, hello.length);
        assertEquals(0x4D, hello[0] & 0xFF);
        assertEquals(0, hello[5] & 0xFF);

        DistantHorizonsLodServer.Column[] columns = new DistantHorizonsLodServer.Column[256];
        java.util.Arrays.fill(
            columns,
            new DistantHorizonsLodServer.Column(70, "minecraft:grass_block", 70, "minecraft:grass_block")
        );
        byte[] response = DistantHorizonsLodServer.response(
            72,
            new DistantHorizonsLodServer.Tile(new ChunkPos(3, -7), columns)
        );
        assertEquals(0x4D, response[0] & 0xFF);
        assertEquals(2, response[5] & 0xFF);
    }

    private static byte[] request(int requestId, List<ChunkPos> positions) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4D44484E);
            output.writeByte(1);
            output.writeByte(1);
            output.writeInt(requestId);
            output.writeShort(positions.size());
            for (ChunkPos position : positions) {
                output.writeInt(position.x);
                output.writeInt(position.z);
            }
        }
        return bytes.toByteArray();
    }
}
