/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugDiscoveryTest {
    @TempDir
    Path temporary;

    @Test
    void publishesPrivateFilesAndRemovesThem() throws Exception {
        DebugPaths paths = new DebugPaths(temporary.resolve("state"), temporary.resolve("runtime"));
        DebugDiscovery discovery = new DebugDiscovery(paths);
        long pid = ProcessHandle.current().pid();
        Instant started = ProcessHandle.current().info().startInstant().orElseThrow();
        DebugEndpointDescriptor endpoint = new DebugEndpointDescriptor(
            DebugEndpointDescriptor.SCHEMA, "client-test", DebugEndpointRole.CLIENT, pid, started,
            "test", 1, "unix", paths.socket("client-test").toString(), "../credentials/client-test.token", 1, 1
        );

        discovery.publish(endpoint, "secret");

        assertEquals("secret", discovery.readCredential(endpoint));
        assertEquals(1, discovery.list(false).size());
        assertEquals("client", Files.readString(paths.endpoint(endpoint.getId())).contains("\"role\" : \"client\"") ? "client" : "missing");
        if (Files.getFileStore(paths.root()).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(paths.credential("client-test.token")));
        }

        discovery.remove(endpoint);
        assertFalse(Files.exists(paths.endpoint(endpoint.getId())));
        assertFalse(Files.exists(paths.credential("client-test.token")));
    }

    @Test
    void rejectsAndCleansStaleProcessDescriptors() throws Exception {
        DebugPaths paths = new DebugPaths(temporary.resolve("stale-state"), temporary.resolve("stale-runtime"));
        DebugDiscovery discovery = new DebugDiscovery(paths);
        DebugEndpointDescriptor endpoint = new DebugEndpointDescriptor(
            DebugEndpointDescriptor.SCHEMA, "server-stale", DebugEndpointRole.SERVER, Long.MAX_VALUE, Instant.EPOCH,
            "test", 1, "unix", paths.socket("server-stale").toString(), "../credentials/server-stale.token", 1, 1
        );
        discovery.publish(endpoint, "secret");

        assertTrue(discovery.list(true).isEmpty());
        assertFalse(Files.exists(paths.endpoint(endpoint.getId())));
        assertFalse(Files.exists(paths.credential("server-stale.token")));
    }

    @Test
    void derivesPortableBoundedTransportAddresses() {
        DebugPaths paths = new DebugPaths(temporary.resolve("address-state"), temporary.resolve("address-runtime"));

        assertTrue(paths.socket("client-with-an-intentionally-long-endpoint-identifier").getFileName().toString().matches("[0-9a-f]{24}\\.sock"));
        assertTrue(paths.pipe("client-with-an-intentionally-long-endpoint-identifier").matches("\\\\\\\\\\.\\\\pipe\\\\minosoft-debug-[0-9a-f]{24}"));
    }

    @Test
    void rejectsEndpointIdentifiersThatCouldEscapeDiscoveryDirectories() {
        assertThrows(IllegalArgumentException.class, () -> new DebugEndpointDescriptor(
            DebugEndpointDescriptor.SCHEMA, "../../outside", DebugEndpointRole.CLIENT, 1, Instant.EPOCH,
            "test", 1, "unix", "/tmp/debug.sock", "../credentials/debug.token", 1, 1
        ));
    }

    @Test
    void rejectsSymbolicLinkDebugDirectories() throws Exception {
        Path target = temporary.resolve("target");
        Files.createDirectory(target);
        Path link = temporary.resolve("debug-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException error) {
            return;
        }

        assertThrows(java.io.IOException.class, () -> DebugPaths.createPrivateDirectory(link));
    }
}
