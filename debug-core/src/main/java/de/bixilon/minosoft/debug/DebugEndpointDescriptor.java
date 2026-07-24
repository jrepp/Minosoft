/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class DebugEndpointDescriptor {
    public static final String SCHEMA = "minosoft.debug.endpoint/v1";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Set<String> SUPPORTED_TRANSPORTS = Set.of("unix", "windows-pipe");

    private final String schema;
    private final String id;
    private final DebugEndpointRole role;
    private final long pid;
    private final Instant processStart;
    private final String trajectory;
    private final int generation;
    private final String transport;
    private final String address;
    private final String credential;
    private final int protocolMin;
    private final int protocolMax;

    @JsonCreator
    public DebugEndpointDescriptor(
        @JsonProperty("schema") String schema,
        @JsonProperty("id") String id,
        @JsonProperty("role") DebugEndpointRole role,
        @JsonProperty("pid") long pid,
        @JsonProperty("processStart") Instant processStart,
        @JsonProperty("trajectory") String trajectory,
        @JsonProperty("generation") int generation,
        @JsonProperty("transport") String transport,
        @JsonProperty("address") String address,
        @JsonProperty("credential") String credential,
        @JsonProperty("protocolMin") int protocolMin,
        @JsonProperty("protocolMax") int protocolMax
    ) {
        this.schema = Objects.requireNonNull(schema, "schema");
        if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("unsupported endpoint schema: " + schema);
        this.id = requireIdentifier(id);
        this.role = Objects.requireNonNull(role, "role");
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        this.pid = pid;
        this.processStart = Objects.requireNonNull(processStart, "processStart");
        this.trajectory = requireText(trajectory, "trajectory", 256);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
        this.transport = requireText(transport, "transport", 32);
        if (!SUPPORTED_TRANSPORTS.contains(this.transport)) {
            throw new IllegalArgumentException("unsupported transport: " + this.transport);
        }
        this.address = requireText(address, "address", 4_096);
        this.credential = requireText(credential, "credential", 512);
        if (protocolMin < 1 || protocolMax < protocolMin) throw new IllegalArgumentException("invalid protocol range");
        this.protocolMin = protocolMin;
        this.protocolMax = protocolMax;
    }

    public String getSchema() { return schema; }
    public String getId() { return id; }
    public DebugEndpointRole getRole() { return role; }
    public long getPid() { return pid; }
    public Instant getProcessStart() { return processStart; }
    public String getTrajectory() { return trajectory; }
    public int getGeneration() { return generation; }
    public String getTransport() { return transport; }
    public String getAddress() { return address; }
    public String getCredential() { return credential; }
    public int getProtocolMin() { return protocolMin; }
    public int getProtocolMax() { return protocolMax; }

    public boolean isProcessAlive() {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(handle ->
            handle.info().startInstant().map(processStart::equals).orElse(true)
        ).orElse(false);
    }

    private static String requireIdentifier(String value) {
        String id = requireText(value, "id", 128);
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id contains unsafe characters");
        }
        return id;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        if (value.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        return value;
    }
}
