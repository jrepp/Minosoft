/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.util.Arrays;

public final class DebugFrame {
    private final int version;
    private final DebugFrameKind kind;
    private final int flags;
    private final byte[] payload;

    public DebugFrame(int version, DebugFrameKind kind, int flags, byte[] payload) {
        if (version < 1 || version > 0xffff) throw new IllegalArgumentException("invalid protocol version");
        if (kind == null) throw new NullPointerException("kind");
        if (flags < 0 || flags > 0xff) throw new IllegalArgumentException("invalid flags");
        this.version = version;
        this.kind = kind;
        this.flags = flags;
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public int version() { return version; }
    public DebugFrameKind kind() { return kind; }
    public int flags() { return flags; }
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
