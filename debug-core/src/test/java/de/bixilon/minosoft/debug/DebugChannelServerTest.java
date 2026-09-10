/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugChannelServerTest {
    @TempDir
    Path temporary;

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void authenticatesDispatchesAndCleansUp() throws Exception {
        Path runtime = Path.of("/tmp", "md-" + UUID.randomUUID().toString().substring(0, 8));
        DebugPaths paths = new DebugPaths(temporary.resolve("state"), runtime);
        DebugChannelServer server = new DebugChannelServer(paths, DebugEndpointRole.CLIENT, "acceptance", 3);
        server.setStatusSupplier(() -> DebugJson.MAPPER.createObjectNode()
            .put("ready", true)
            .put("endpointId", "spoofed")
            .put("pid", -1));
        server.operations().register("test.mod", "test.echo", (context, body) ->
            CompletableFuture.completedFuture(DebugOperationResult.json(DebugJson.MAPPER.createObjectNode()
                .put("requestId", context.requestId())
                .set("echo", body)))
        );
        server.operations().register("test.mod", "test.attachment", (context, body) ->
            CompletableFuture.completedFuture(DebugOperationResult.attachment(
                DebugJson.MAPPER.createObjectNode().put("kind", "fixture"), new byte[] {1, 2, 3}, "application/octet-stream", "fixture.bin"))
        );
        server.operations().register("test.mod", "test.never", (context, body) -> new CompletableFuture<>());
        server.operations().register("test.mod", "test.oversized", (context, body) ->
            CompletableFuture.completedFuture(DebugOperationResult.attachment(
                DebugJson.MAPPER.createObjectNode(),
                new byte[DebugFrameCodec.DEFAULT_MAX_PAYLOAD + 1],
                "application/octet-stream",
                "oversized.bin"))
        );
        AutoCloseable temporary = server.operations().register("test.generation", "test.temporary", (context, body) ->
            CompletableFuture.completedFuture(DebugOperationResult.json(DebugJson.MAPPER.createObjectNode().put("active", true)))
        );
        temporary.close();

        server.start();
        DebugEndpointDescriptor endpoint = server.endpoint();
        try {
            assertEquals(1, new DebugDiscovery(paths).list(false).size());
            DebugClientException denied = assertThrows(DebugClientException.class,
                () -> DebugClient.connect(endpoint, "wrong-token"));
            assertEquals("unauthenticated", denied.code());

            try (DebugClient client = DebugClient.connect(paths, endpoint)) {
                assertEquals(endpoint.getId(), client.hello().path("endpointId").asText());
                assertEquals("client", client.request("core.ping").path("role").asText());
                JsonNode status = client.request("core.status");
                assertTrue(status.path("ready").asBoolean());
                assertEquals(endpoint.getId(), status.path("endpointId").asText());
                assertEquals(endpoint.getPid(), status.path("pid").asLong());
                JsonNode capabilities = client.request("core.capabilities");
                assertTrue(capabilities.path("operations").toString().contains("test.echo"));
                assertTrue(capabilities.path("operations").toString().contains("metrics.snapshot"));
                assertFalse(capabilities.path("operations").toString().contains("test.temporary"));
                assertEquals("value", client.request("test.echo",
                    DebugJson.MAPPER.createObjectNode().put("key", "value"), 1_000).path("echo").path("key").asText());
                DebugResponse attachment = client.requestWithAttachment("test.attachment", DebugJson.MAPPER.createObjectNode(), 1_000);
                assertTrue(attachment.hasAttachment());
                assertEquals(3, attachment.attachment().length);
                assertEquals("fixture.bin", attachment.attachmentName());
                DebugClientException unsupported = assertThrows(DebugClientException.class,
                    () -> client.request("missing.operation"));
                assertEquals("unsupported_operation", unsupported.code());
                DebugClientException oversized = assertThrows(DebugClientException.class,
                    () -> client.request("test.oversized"));
                assertEquals("limit_exceeded", oversized.code());
                assertEquals("client", client.request("core.ping").path("role").asText());
                DebugClientException deadline = assertThrows(DebugClientException.class,
                    () -> client.request("test.never", DebugJson.MAPPER.createObjectNode(), 25));
                assertEquals("deadline_exceeded", deadline.code());
                JsonNode metrics = client.request("metrics.snapshot");
                assertEquals("client", metrics.path("role").asText());
                assertTrue(metrics.path("latencyBucketUpperBoundsNanos").size() > 10);
                JsonNode echoMetrics = findMetric(metrics, "test.echo");
                assertEquals(1, echoMetrics.path("count").asInt());
                assertEquals(1, echoMetrics.path("success").asInt());
                assertEquals(echoMetrics.path("latencyBuckets").size(),
                    metrics.path("latencyBucketUpperBoundsNanos").size() + 1);
                JsonNode timeoutMetrics = findMetric(metrics, "test.never");
                assertEquals(1, timeoutMetrics.path("timeout").asInt());
                assertEquals("client", client.request("core.ping").path("role").asText());
            }
        } finally {
            server.close();
        }

        assertFalse(Files.exists(paths.endpoint(endpoint.getId())));
        assertFalse(Files.exists(paths.credential(endpoint.getId() + ".token")));
        assertFalse(Files.exists(paths.socket(endpoint.getId())));
        Files.deleteIfExists(runtime);
    }

    private static JsonNode findMetric(JsonNode snapshot, String operation) {
        for (JsonNode metric : snapshot.path("operations")) {
            if (operation.equals(metric.path("operation").asText())) return metric;
        }
        throw new AssertionError("Missing metric " + operation + " in " + snapshot);
    }
}
