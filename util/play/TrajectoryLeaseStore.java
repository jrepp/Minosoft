/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugJson;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TrajectoryLeaseStore {
    static final Set<String> SCOPES = Set.of("client", "server-world", "pack", "source");
    private static final int SCHEMA_VERSION = 1;
    private static final Duration MIN_TTL = Duration.ofMillis(1);
    private static final Duration MAX_TTL = Duration.ofHours(24);

    private final Path directory;
    private final Path lockFile;
    private final Clock clock;

    TrajectoryLeaseStore(Path runDirectory) {
        this(runDirectory, Clock.systemUTC());
    }

    TrajectoryLeaseStore(Path runDirectory, Clock clock) {
        this.directory = runDirectory.resolve("leases");
        this.lockFile = directory.resolve(".lock");
        this.clock = clock;
    }

    ObjectNode acquire(String scope, String trajectory, Duration ttl, String owner) throws IOException {
        requireScope(scope);
        requireText(trajectory, "trajectory", 128);
        requireText(owner, "owner", 128);
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("lease ttl must be between 1 millisecond and 24 hours");
        }
        return locked(() -> {
            List<ObjectNode> active = loadActive();
            for (ObjectNode lease : active) {
                if (conflicts(scope, trajectory, lease.path("scope").asText(), lease.path("trajectory").asText())) {
                    throw new IllegalStateException(
                        "lease conflicts with active " + lease.path("scope").asText() +
                            " lease " + lease.path("token").asText() +
                            " for trajectory " + lease.path("trajectory").asText()
                    );
                }
            }

            Instant acquiredAt = clock.instant();
            String token = UUID.randomUUID().toString();
            ObjectNode lease = DebugJson.MAPPER.createObjectNode()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("token", token)
                .put("scope", scope)
                .put("trajectory", trajectory)
                .put("owner", owner)
                .put("acquiredByPid", ProcessHandle.current().pid())
                .put("acquiredAt", acquiredAt.toString())
                .put("expiresAt", acquiredAt.plus(ttl).toString());
            writeAtomically(path(token), lease);
            return lease;
        });
    }

    ObjectNode status() throws IOException {
        return locked(() -> {
            List<ObjectNode> active = loadActive();
            ObjectNode result = DebugJson.MAPPER.createObjectNode().put("schemaVersion", SCHEMA_VERSION);
            ArrayNode leases = result.putArray("leases");
            active.forEach(leases::add);
            return result;
        });
    }

    ObjectNode release(String token) throws IOException {
        validateToken(token);
        return locked(() -> {
            Path leasePath = path(token);
            if (!Files.isRegularFile(leasePath)) {
                throw new IllegalStateException("lease token is not active: " + token);
            }
            ObjectNode lease = readLease(leasePath);
            Files.delete(leasePath);
            ObjectNode result = lease.deepCopy();
            result.put("released", true);
            result.put("releasedAt", clock.instant().toString());
            return result;
        });
    }

    boolean owns(String token, String scope, String trajectory) throws IOException {
        validateToken(token);
        requireScope(scope);
        return locked(() -> loadActive().stream().anyMatch(lease ->
            lease.path("token").asText().equals(token) &&
                lease.path("scope").asText().equals(scope) &&
                lease.path("trajectory").asText().equals(trajectory)
        ));
    }

    private List<ObjectNode> loadActive() throws IOException {
        Files.createDirectories(directory);
        Instant now = clock.instant();
        List<ObjectNode> active = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                ObjectNode lease;
                try {
                    lease = readLease(file);
                } catch (RuntimeException | IOException invalid) {
                    quarantine(file);
                    continue;
                }
                Instant expiresAt;
                try {
                    expiresAt = Instant.parse(lease.path("expiresAt").asText());
                } catch (RuntimeException invalid) {
                    quarantine(file);
                    continue;
                }
                if (!expiresAt.isAfter(now)) {
                    Files.deleteIfExists(file);
                    continue;
                }
                active.add(lease);
            }
        }
        active.sort(Comparator
            .comparing((ObjectNode lease) -> lease.path("scope").asText())
            .thenComparing(lease -> lease.path("trajectory").asText())
            .thenComparing(lease -> lease.path("acquiredAt").asText()));
        return active;
    }

    private ObjectNode readLease(Path file) throws IOException {
        JsonNode value = DebugJson.MAPPER.readTree(file.toFile());
        if (!(value instanceof ObjectNode lease) ||
            lease.path("schemaVersion").asInt() != SCHEMA_VERSION ||
            !SCOPES.contains(lease.path("scope").asText()) ||
            lease.path("trajectory").asText().isBlank() ||
            lease.path("owner").asText().isBlank() ||
            lease.path("token").asText().isBlank()) {
            throw new IllegalArgumentException("invalid trajectory lease: " + file);
        }
        String token = lease.path("token").asText();
        validateToken(token);
        if (!file.getFileName().toString().equals(token + ".json")) {
            throw new IllegalArgumentException("trajectory lease token does not match its filename: " + file);
        }
        return lease;
    }

    private void quarantine(Path file) throws IOException {
        Path invalid = file.resolveSibling(file.getFileName() + ".invalid-" + clock.instant().toEpochMilli());
        try {
            Files.move(file, invalid, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(file, invalid);
        }
    }

    private static boolean conflicts(String requestedScope, String requestedTrajectory, String activeScope, String activeTrajectory) {
        if (!requestedScope.equals(activeScope)) return false;
        return !requestedScope.equals("client") || requestedTrajectory.equals(activeTrajectory);
    }

    private static void requireScope(String scope) {
        if (!SCOPES.contains(scope)) {
            throw new IllegalArgumentException("lease scope must be one of: " + String.join(", ", SCOPES.stream().sorted().toList()));
        }
    }

    private static void requireText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + " must contain 1.." + maximumLength + " characters");
        }
    }

    private Path path(String token) {
        return directory.resolve(token + ".json");
    }

    private static void validateToken(String token) {
        try {
            UUID.fromString(token);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid lease token");
        }
    }

    private static void writeAtomically(Path target, ObjectNode value) throws IOException {
        Path candidate = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(
            candidate,
            DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW
        );
        try {
            Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    private <T> T locked(IoSupplier<T> work) throws IOException {
        Files.createDirectories(directory);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            if (!lock.isValid()) {
                throw new IOException("Could not acquire trajectory lease lock " + lockFile);
            }
            return work.get();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
