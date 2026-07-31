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
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.UUID;

final class TrajectoryCheckpointStore {
    private static final int SCHEMA_VERSION = 1;
    private static final double POSITION_TOLERANCE = 0.01;
    private static final double ANGLE_TOLERANCE = 0.05;

    private final Clock clock;

    TrajectoryCheckpointStore() {
        this(Clock.systemUTC());
    }

    TrajectoryCheckpointStore(Clock clock) {
        this.clock = clock;
    }

    ObjectNode create(Path path, String trajectory, ObjectNode endpoint, ObjectNode pose) throws IOException {
        requirePose(pose, "original");
        ObjectNode checkpoint = DebugJson.MAPPER.createObjectNode()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("token", UUID.randomUUID().toString())
            .put("trajectory", requireText(trajectory, "trajectory"))
            .put("createdAt", clock.instant().toString())
            .put("state", "active");
        checkpoint.putArray("fields").add("player-pose");
        checkpoint.set("clientEndpoint", requireObject(endpoint, "clientEndpoint").deepCopy());
        checkpoint.set("original", pose.deepCopy());
        checkpoint.set("expectedCurrent", pose.deepCopy());
        write(path, checkpoint, false);
        return checkpoint;
    }

    ObjectNode read(Path path) throws IOException {
        JsonNode value = DebugJson.MAPPER.readTree(path.toFile());
        if (!(value instanceof ObjectNode checkpoint)
            || checkpoint.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
            || checkpoint.path("token").asText().isBlank()
            || checkpoint.path("trajectory").asText().isBlank()) {
            throw new IllegalArgumentException("invalid trajectory checkpoint: " + path);
        }
        try {
            UUID.fromString(checkpoint.path("token").asText());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid trajectory checkpoint token: " + path);
        }
        requirePose(checkpoint.path("original"), "original");
        requirePose(checkpoint.path("expectedCurrent"), "expectedCurrent");
        return checkpoint;
    }

    ObjectNode mark(Path path, ObjectNode endpoint, ObjectNode current) throws IOException {
        requirePose(current, "current");
        ObjectNode checkpoint = read(path);
        requireActive(checkpoint);
        checkpoint.set("clientEndpoint", requireObject(endpoint, "clientEndpoint").deepCopy());
        checkpoint.set("expectedCurrent", current.deepCopy());
        checkpoint.put("markedAt", clock.instant().toString());
        write(path, checkpoint, true);
        return checkpoint;
    }

    boolean matchesExpected(ObjectNode checkpoint, JsonNode current) {
        requirePose(current, "current");
        JsonNode expected = checkpoint.path("expectedCurrent");
        requirePose(expected, "expectedCurrent");
        return expected.path("dimension").asText().equals(current.path("dimension").asText())
            && close(expected, current, "x", POSITION_TOLERANCE)
            && close(expected, current, "y", POSITION_TOLERANCE)
            && close(expected, current, "z", POSITION_TOLERANCE)
            && angleDistance(expected.path("yaw").asDouble(), current.path("yaw").asDouble()) <= ANGLE_TOLERANCE
            && close(expected, current, "pitch", ANGLE_TOLERANCE);
    }

    ObjectNode recordConflict(Path path, JsonNode current) throws IOException {
        requirePose(current, "current");
        ObjectNode checkpoint = read(path);
        requireActive(checkpoint);
        checkpoint.put("state", "conflict");
        checkpoint.put("conflictedAt", clock.instant().toString());
        checkpoint.set("conflictingCurrent", current.deepCopy());
        write(path, checkpoint, true);
        return checkpoint;
    }

    ObjectNode recordRestored(Path path, JsonNode current) throws IOException {
        requirePose(current, "current");
        ObjectNode checkpoint = read(path);
        requireActive(checkpoint);
        checkpoint.put("state", "restored");
        checkpoint.put("restoredAt", clock.instant().toString());
        checkpoint.set("restoredCurrent", current.deepCopy());
        write(path, checkpoint, true);
        return checkpoint;
    }

    private static ObjectNode requireObject(JsonNode value, String label) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return object;
    }

    private static void requirePose(JsonNode pose, String label) {
        requireObject(pose, label);
        if (pose.path("dimension").asText().isBlank()) {
            throw new IllegalArgumentException(label + ".dimension must not be blank");
        }
        for (String field : new String[]{"x", "y", "z", "yaw", "pitch"}) {
            if (!pose.path(field).isNumber() || !Double.isFinite(pose.path(field).asDouble())) {
                throw new IllegalArgumentException(label + "." + field + " must be a finite number");
            }
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static void requireActive(ObjectNode checkpoint) {
        if (!checkpoint.path("state").asText().equals("active")) {
            throw new IllegalStateException(
                "checkpoint is not active: " + checkpoint.path("state").asText("unknown")
            );
        }
    }

    private static boolean close(JsonNode first, JsonNode second, String field, double tolerance) {
        return Math.abs(first.path(field).asDouble() - second.path(field).asDouble()) <= tolerance;
    }

    private static double angleDistance(double first, double second) {
        double delta = (first - second) % 360.0;
        if (delta > 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return Math.abs(delta);
    }

    private static void write(Path target, ObjectNode value, boolean replace) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path candidate = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(
            candidate,
            DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW
        );
        try {
            if (replace) {
                Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(target, candidate);
            }
        } finally {
            Files.deleteIfExists(candidate);
        }
    }
}
