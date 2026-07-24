/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class DebugFrameCodec {
    public static final int MAGIC = 0x4d444247;
    public static final int DEFAULT_MAX_PAYLOAD = 16 * 1024 * 1024;

    private final int maxPayload;

    public DebugFrameCodec() { this(DEFAULT_MAX_PAYLOAD); }

    public DebugFrameCodec(int maxPayload) {
        if (maxPayload < 1) throw new IllegalArgumentException("maxPayload must be positive");
        this.maxPayload = maxPayload;
    }

    public void write(OutputStream output, DebugFrame frame) throws IOException {
        byte[] payload = frame.payload();
        if (payload.length > maxPayload) throw new DebugProtocolException("frame exceeds payload limit");
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(frame.version());
        data.writeByte(frame.kind().code());
        data.writeByte(frame.flags());
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    public DebugFrame read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException end) {
            throw end;
        }
        if (magic != MAGIC) throw new DebugProtocolException("invalid frame magic");
        int version = data.readUnsignedShort();
        DebugFrameKind kind = DebugFrameKind.fromCode(data.readUnsignedByte());
        int flags = data.readUnsignedByte();
        int length = data.readInt();
        if (length < 0 || length > maxPayload) throw new DebugProtocolException("invalid payload length: " + length);
        byte[] payload = data.readNBytes(length);
        if (payload.length != length) throw new EOFException("truncated frame payload");
        return new DebugFrame(version, kind, flags, payload);
    }
}
