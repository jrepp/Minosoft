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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrajectoryDiagnosticsTest {
    @TempDir
    Path temporary;

    @Test
    void captureProducesBoundedManifestWithoutLiveEndpoints() throws IOException {
        Path logs = temporary.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("events.jsonl"), "{\"type\":\"parent_started\"}\n");
        Files.writeString(logs.resolve("client.log"), "client line\n");
        Files.writeString(logs.resolve("server.log"), "server line\n");
        Path output = temporary.resolve("diagnostics");
        TrajectoryDiagnostics diagnostics = new TrajectoryDiagnostics(
            logs.resolve("events.jsonl"),
            logs.resolve("client.log"),
            logs.resolve("server.log"),
            temporary.resolve("store"),
            ""
        );
        ObjectNode status = DebugJson.MAPPER.createObjectNode().put("target", "both");

        ObjectNode manifest = diagnostics.capture(
            "diagnostics-test-no-live-endpoint",
            output,
            false,
            status
        );

        assertTrue(manifest.path("complete").asBoolean());
        assertTrue(manifest.path("warningCount").asInt() >= 2);
        assertTrue(Files.isRegularFile(output.resolve("status.json")));
        assertTrue(Files.isRegularFile(output.resolve("endpoints.json")));
        assertTrue(Files.isRegularFile(output.resolve("fixtures.json")));
        assertTrue(Files.isRegularFile(output.resolve("play-events.jsonl")));
        JsonNode written = DebugJson.MAPPER.readTree(output.resolve("manifest.json").toFile());
        assertEquals("diagnostics-test-no-live-endpoint", written.path("trajectory").asText());
        assertEquals(0, DebugJson.MAPPER.readTree(output.resolve("endpoints.json").toFile()).size());
    }
}
