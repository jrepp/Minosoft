/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class DebugDiscovery {
    private final DebugPaths paths;

    public DebugDiscovery(DebugPaths paths) {
        this.paths = paths;
    }

    public void publish(DebugEndpointDescriptor endpoint, String token) throws IOException {
        paths.initialize();
        Path credential = credentialPath(endpoint);
        writeAtomically(credential, token.getBytes(StandardCharsets.UTF_8));
        try {
            writeAtomically(paths.endpoint(endpoint.getId()), DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(endpoint));
        } catch (IOException error) {
            Files.deleteIfExists(credential);
            throw error;
        }
    }

    public List<DebugEndpointDescriptor> list(boolean cleanStale) throws IOException {
        paths.initialize();
        List<DebugEndpointDescriptor> endpoints = new ArrayList<>();
        try (var files = Files.list(paths.endpoints())) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).collect(Collectors.toList())) {
                try {
                    DebugEndpointDescriptor endpoint = DebugJson.MAPPER.readValue(file.toFile(), DebugEndpointDescriptor.class);
                    if (!endpoint.isProcessAlive()) {
                        if (cleanStale) remove(endpoint);
                        continue;
                    }
                    endpoints.add(endpoint);
                } catch (RuntimeException | IOException malformed) {
                    if (cleanStale) Files.deleteIfExists(file);
                }
            }
        }
        endpoints.sort(Comparator.comparing(DebugEndpointDescriptor::getRole)
            .thenComparing(DebugEndpointDescriptor::getTrajectory)
            .thenComparingInt(DebugEndpointDescriptor::getGeneration)
            .thenComparing(DebugEndpointDescriptor::getId));
        return List.copyOf(endpoints);
    }

    public String readCredential(DebugEndpointDescriptor endpoint) throws IOException {
        return Files.readString(credentialPath(endpoint), StandardCharsets.UTF_8).trim();
    }

    public void remove(DebugEndpointDescriptor endpoint) throws IOException {
        Files.deleteIfExists(paths.endpoint(endpoint.getId()));
        Files.deleteIfExists(credentialPath(endpoint));
    }

    private Path credentialPath(DebugEndpointDescriptor endpoint) throws IOException {
        Path path = paths.endpoint(endpoint.getId()).getParent().resolve(endpoint.getCredential()).normalize();
        Path allowed = paths.credentials().toAbsolutePath().normalize();
        if (!path.toAbsolutePath().normalize().startsWith(allowed)) {
            throw new IOException("credential path escapes private credential directory");
        }
        return path;
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            DebugPaths.makePrivateFile(temporary);
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            DebugPaths.makePrivateFile(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
