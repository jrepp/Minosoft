/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.time.Instant;

public final class DebugRequestContext {
    private final String requestId;
    private final String operation;
    private final Instant deadline;
    private final DebugEndpointDescriptor endpoint;

    public DebugRequestContext(String requestId, String operation, Instant deadline, DebugEndpointDescriptor endpoint) {
        this.requestId = requestId;
        this.operation = operation;
        this.deadline = deadline;
        this.endpoint = endpoint;
    }

    public String requestId() { return requestId; }
    public String operation() { return operation; }
    public Instant deadline() { return deadline; }
    public DebugEndpointDescriptor endpoint() { return endpoint; }
    public boolean isExpired() { return Instant.now().isAfter(deadline); }
}
