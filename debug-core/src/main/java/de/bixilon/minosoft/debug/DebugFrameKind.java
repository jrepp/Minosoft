/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

public enum DebugFrameKind {
    HELLO(1),
    REQUEST(2),
    RESPONSE(3),
    ERROR(4),
    EVENT(5),
    BINARY(6),
    CANCEL(7);

    private final int code;

    DebugFrameKind(int code) { this.code = code; }

    public int code() { return code; }

    public static DebugFrameKind fromCode(int code) throws DebugProtocolException {
        for (DebugFrameKind kind : values()) if (kind.code == code) return kind;
        throw new DebugProtocolException("unknown frame kind: " + code);
    }
}
