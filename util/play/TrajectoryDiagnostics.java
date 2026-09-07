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
import de.bixilon.minosoft.debug.DebugClient;
import de.bixilon.minosoft.debug.DebugDiscovery;
import de.bixilon.minosoft.debug.DebugEndpointDescriptor;
import de.bixilon.minosoft.debug.DebugEndpointRole;
import de.bixilon.minosoft.debug.DebugJson;
import de.bixilon.minosoft.debug.DebugPaths;
import de.bixilon.minosoft.debug.DebugResponse;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class TrajectoryDiagnostics {
    private static final int MAX_LOG_BYTES = 64 * 1024;
    private static final int MAX_SCREENSHOT_BYTES = 64 * 1024 * 1024;

    private final Path eventLog;
    private final Path clientLog;
    private final Path serverLog;
    private final Path modpackStore;
    private final String configuredModpack;

    TrajectoryDiagnostics(
        Path eventLog,
        Path clientLog,
        Path serverLog,
        Path modpackStore,
        String configuredModpack
    ) {
        this.eventLog = eventLog;
        this.clientLog = clientLog;
        this.serverLog = serverLog;
        this.modpackStore = modpackStore;
        this.configuredModpack = configuredModpack;
    }

    ObjectNode capture(String trajectory, Path output, boolean visual, ObjectNode processStatus) throws IOException {
        if (Files.exists(output)) throw new IllegalArgumentException("diagnostic output already exists: " + output);
        Files.createDirectories(output);

        ObjectNode manifest = DebugJson.MAPPER.createObjectNode()
            .put("schemaVersion", 1)
            .put("createdAt", Instant.now().toString())
            .put("trajectory", trajectory)
            .put("output", output.toString())
            .put("visualRequested", visual);
        ArrayNode artifacts = manifest.putArray("artifacts");
        ArrayNode warnings = manifest.putArray("warnings");

        writeJson(output, "status.json", processStatus, artifacts);

        List<DebugEndpointDescriptor> endpoints = new DebugDiscovery(DebugPaths.system()).list(true).stream()
            .filter(endpoint -> endpoint.getTrajectory().equals(trajectory))
            .sorted(Comparator
                .comparing((DebugEndpointDescriptor endpoint) -> endpoint.getRole().wireName())
                .thenComparing(DebugEndpointDescriptor::getGeneration, Comparator.reverseOrder())
                .thenComparing(DebugEndpointDescriptor::getProcessStart, Comparator.reverseOrder()))
            .toList();
        ArrayNode endpointJson = DebugJson.MAPPER.createArrayNode();
        endpoints.forEach(endpoint -> endpointJson.add(endpoint(endpoint)));
        writeJson(output, "endpoints.json", endpointJson, artifacts);

        Map<DebugEndpointRole, DebugEndpointDescriptor> selected = new TreeMap<>(
            Comparator.comparing(DebugEndpointRole::wireName)
        );
        for (DebugEndpointDescriptor endpoint : endpoints) selected.putIfAbsent(endpoint.getRole(), endpoint);
        if (selected.isEmpty()) warnings.add("No live debug endpoint matched the trajectory.");

        for (Map.Entry<DebugEndpointRole, DebugEndpointDescriptor> entry : selected.entrySet()) {
            captureEndpoint(output, entry.getKey(), entry.getValue(), visual, artifacts, warnings);
        }
        captureFixtures(output, trajectory, artifacts, warnings);
        captureLog(output, "play-events.jsonl", eventLog, artifacts, warnings);
        captureLog(output, "client.log", clientLog, artifacts, warnings);
        captureLog(output, "server.log", serverLog, artifacts, warnings);

        manifest.put("complete", true);
        manifest.put("warningCount", warnings.size());
        writeJson(output, "manifest.json", manifest, null);
        return manifest;
    }

    private void captureEndpoint(
        Path output,
        DebugEndpointRole role,
        DebugEndpointDescriptor endpoint,
        boolean visual,
        ArrayNode artifacts,
        ArrayNode warnings
    ) {
        String prefix = role.wireName();
        try (DebugClient client = DebugClient.connect(DebugPaths.system(), endpoint)) {
            captureJson(output, prefix + "-status.json", client, "core.status", empty(), 5_000, artifacts, warnings);
            captureJson(output, prefix + "-capabilities.json", client, "core.capabilities", empty(), 5_000, artifacts, warnings);
            captureJson(output, prefix + "-metrics.json", client, "metrics.snapshot", empty(), 5_000, artifacts, warnings);
            captureJson(output, prefix + "-mods.json", client, "mods.debug", empty(), 5_000, artifacts, warnings);
            if (role == DebugEndpointRole.CLIENT) {
                captureJson(
                    output,
                    "client-player.json",
                    client,
                    "state.sample",
                    DebugJson.MAPPER.createObjectNode().put("view", "client.player"),
                    5_000,
                    artifacts,
                    warnings
                );
                captureJson(output, "render-substrate.json", client, "render.substrate", empty(), 10_000, artifacts, warnings);
                if (visual) captureVisual(output, client, artifacts, warnings);
            } else {
                captureJson(
                    output,
                    "server-world.json",
                    client,
                    "state.sample",
                    DebugJson.MAPPER.createObjectNode().put("view", "server.world"),
                    5_000,
                    artifacts,
                    warnings
                );
            }
        } catch (Exception error) {
            warnings.add(prefix + " endpoint connection failed: " + concise(error));
        }
    }

    private void captureJson(
        Path output,
        String name,
        DebugClient client,
        String operation,
        ObjectNode body,
        int timeoutMillis,
        ArrayNode artifacts,
        ArrayNode warnings
    ) {
        try {
            writeJson(output, name, client.request(operation, body, timeoutMillis), artifacts);
        } catch (Exception error) {
            warnings.add(operation + " failed: " + concise(error));
        }
    }

    private void captureVisual(Path output, DebugClient client, ArrayNode artifacts, ArrayNode warnings) {
        try {
            DebugResponse response = client.requestWithAttachment(
                "visual.capture",
                DebugJson.MAPPER.createObjectNode().put("includeScene", true),
                10_000
            );
            if (!response.hasAttachment()) throw new IOException("operation returned no attachment");
            if (response.attachment().length > MAX_SCREENSHOT_BYTES) throw new IOException("attachment exceeded 64 MiB");
            String actual = sha256(response.attachment());
            String expected = response.result().path("sha256").asText("");
            if (!expected.isBlank() && !expected.equals(actual)) throw new IOException("attachment hash mismatch");
            writeBytes(output, "frame.png", response.attachment(), artifacts);
            ObjectNode metadata = response.result().deepCopy();
            JsonNode scene = metadata.remove("scene");
            metadata.put("verifiedSha256", actual);
            metadata.put("bytes", response.attachment().length);
            writeJson(output, "frame.json", metadata, artifacts);
            if (scene != null && scene.isObject()) writeJson(output, "scene.json", scene, artifacts);
        } catch (Exception error) {
            warnings.add("visual.capture failed: " + concise(error));
        }
    }

    private void captureFixtures(Path output, String trajectory, ArrayNode artifacts, ArrayNode warnings) throws IOException {
        ObjectNode result = DebugJson.MAPPER.createObjectNode().put("configuredModpack", configuredModpack);
        ArrayNode fixtures = result.putArray("fixtures");
        if (!configuredModpack.isBlank()) {
            Path root = modpackStore.resolve("trajectories").resolve(trajectory).resolve(configuredModpack).resolve("content-fixtures");
            if (Files.isDirectory(root)) {
                try (var ids = Files.list(root)) {
                    for (Path id : ids.filter(Files::isDirectory).sorted().toList()) {
                        try (var generations = Files.list(id)) {
                            for (Path generation : generations.filter(Files::isDirectory).sorted().toList()) {
                                fixtures.addObject()
                                    .put("id", id.getFileName().toString())
                                    .put("generation", generation.getFileName().toString())
                                    .put("path", generation.toString());
                            }
                        }
                    }
                }
            }
        } else {
            warnings.add("No configured modpack was available for fixture discovery.");
        }
        writeJson(output, "fixtures.json", result, artifacts);
    }

    private void captureLog(
        Path output,
        String name,
        Path source,
        ArrayNode artifacts,
        ArrayNode warnings
    ) throws IOException {
        if (!Files.isRegularFile(source)) {
            warnings.add("Log is unavailable: " + source);
            return;
        }
        byte[] bytes = tail(source, MAX_LOG_BYTES);
        writeBytes(output, name, bytes, artifacts);
    }

    private static byte[] tail(Path source, int maximum) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "r")) {
            long length = file.length();
            int count = (int) Math.min(length, maximum);
            byte[] bytes = new byte[count];
            file.seek(length - count);
            file.readFully(bytes);
            return bytes;
        }
    }

    private static void writeJson(Path output, String name, JsonNode value, ArrayNode artifacts) throws IOException {
        byte[] bytes = (DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8);
        writeBytes(output, name, bytes, artifacts);
    }

    private static void writeBytes(Path output, String name, byte[] bytes, ArrayNode artifacts) throws IOException {
        Path target = output.resolve(name);
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        if (artifacts != null) {
            artifacts.addObject()
                .put("path", name)
                .put("bytes", bytes.length)
                .put("sha256", sha256(bytes));
        }
    }

    private static ObjectNode endpoint(DebugEndpointDescriptor endpoint) {
        return DebugJson.MAPPER.createObjectNode()
            .put("id", endpoint.getId())
            .put("role", endpoint.getRole().wireName())
            .put("pid", endpoint.getPid())
            .put("processStart", endpoint.getProcessStart().toString())
            .put("trajectory", endpoint.getTrajectory())
            .put("generation", endpoint.getGeneration())
            .put("transport", endpoint.getTransport())
            .put("address", endpoint.getAddress())
            .put("protocolMin", endpoint.getProtocolMin())
            .put("protocolMax", endpoint.getProtocolMax());
    }

    private static ObjectNode empty() {
        return DebugJson.MAPPER.createObjectNode();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
