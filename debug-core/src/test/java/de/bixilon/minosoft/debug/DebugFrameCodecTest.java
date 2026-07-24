/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebugFrameCodecTest {
    @Test
    void roundTripsFramedPayload() throws Exception {
        DebugFrameCodec codec = new DebugFrameCodec(128);
        DebugFrame expected = new DebugFrame(1, DebugFrameKind.REQUEST, 3, "hello".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        codec.write(bytes, expected);
        DebugFrame actual = codec.read(new ByteArrayInputStream(bytes.toByteArray()));

        assertEquals(expected.version(), actual.version());
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.flags(), actual.flags());
        assertArrayEquals(expected.payload(), actual.payload());
    }

    @Test
    void rejectsOversizedAndTruncatedPayloads() throws Exception {
        DebugFrameCodec codec = new DebugFrameCodec(4);
        assertThrows(DebugProtocolException.class, () -> codec.write(new ByteArrayOutputStream(),
            new DebugFrame(1, DebugFrameKind.REQUEST, 0, new byte[5])));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(DebugFrameCodec.MAGIC);
        output.writeShort(1);
        output.writeByte(DebugFrameKind.REQUEST.code());
        output.writeByte(0);
        output.writeInt(4);
        output.write(new byte[2]);
        assertThrows(java.io.EOFException.class, () -> codec.read(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
