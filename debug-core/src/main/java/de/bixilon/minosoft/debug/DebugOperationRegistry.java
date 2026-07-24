/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class DebugOperationRegistry {
    public static final class Operation {
        private final String name;
        private final String owner;
        private final DebugOperationHandler handler;

        private Operation(String name, String owner, DebugOperationHandler handler) {
            this.name = name;
            this.owner = owner;
            this.handler = handler;
        }

        public String name() { return name; }
        public String owner() { return owner; }
        public DebugOperationHandler handler() { return handler; }
    }

    private final ConcurrentHashMap<String, Operation> operations = new ConcurrentHashMap<>();

    public AutoCloseable register(String owner, String name, DebugOperationHandler handler) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
        if (name == null || !name.matches("[a-z][a-z0-9_.-]*")) throw new IllegalArgumentException("invalid operation name");
        Operation operation = new Operation(name, owner, Objects.requireNonNull(handler, "handler"));
        if (operations.putIfAbsent(name, operation) != null) throw new IllegalStateException("operation already registered: " + name);
        return () -> operations.remove(name, operation);
    }

    public Operation find(String name) { return operations.get(name); }

    public List<Operation> snapshot() {
        List<Operation> result = new java.util.ArrayList<>(operations.values());
        result.sort(Comparator.comparing(Operation::name));
        return List.copyOf(result);
    }
}
