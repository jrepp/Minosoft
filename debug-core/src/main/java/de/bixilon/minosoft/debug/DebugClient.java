/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebugClient implements AutoCloseable {
    private static final long HANDSHAKE_TIMEOUT_MS = 5_000;
    private static final long RESPONSE_GRACE_MS = 2_000;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Minosoft debug client timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final DebugEndpointDescriptor endpoint;
    private final DebugTransportConnection connection;
    private final InputStream input;
    private final OutputStream output;
    private final DebugFrameCodec codec = new DebugFrameCodec();
    private final JsonNode hello;

    private DebugClient(DebugEndpointDescriptor endpoint, String token) throws IOException {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (endpoint.getProtocolMin() > DebugChannelServer.PROTOCOL_VERSION
            || endpoint.getProtocolMax() < DebugChannelServer.PROTOCOL_VERSION) {
            throw new DebugProtocolException("endpoint does not support debug protocol " + DebugChannelServer.PROTOCOL_VERSION);
        }
        this.connection = DebugTransports.connect(endpoint.getTransport(), endpoint.getAddress());
        try {
            input = connection.input;
            output = connection.output;
            ObjectNode request = DebugJson.MAPPER.createObjectNode();
            request.put("endpointId", endpoint.getId());
            request.put("token", token);
            request.put("protocolMin", DebugChannelServer.PROTOCOL_VERSION);
            request.put("protocolMax", DebugChannelServer.PROTOCOL_VERSION);
            DebugFrame response = withTransportTimeout(HANDSHAKE_TIMEOUT_MS, "debug handshake timed out", () -> {
                codec.write(output, jsonFrame(DebugFrameKind.HELLO, request));
                return codec.read(input);
            });
            JsonNode body = DebugJson.MAPPER.readTree(response.payload());
            if (response.kind() == DebugFrameKind.ERROR) throw remoteError(body);
            if (response.kind() != DebugFrameKind.HELLO || response.version() != DebugChannelServer.PROTOCOL_VERSION) {
                throw new DebugProtocolException("expected compatible server hello");
            }
            if (body.path("protocolVersion").asInt(-1) != DebugChannelServer.PROTOCOL_VERSION) {
                throw new DebugProtocolException("server hello protocol mismatch");
            }
            if (!endpoint.getId().equals(body.path("endpointId").asText())) throw new DebugProtocolException("server hello endpoint mismatch");
            hello = body;
        } catch (IOException | RuntimeException error) {
            connection.close();
            throw error;
        }
    }

    public static DebugClient connect(DebugPaths paths, DebugEndpointDescriptor endpoint) throws IOException {
        return new DebugClient(endpoint, new DebugDiscovery(paths).readCredential(endpoint));
    }

    public static DebugClient connect(DebugEndpointDescriptor endpoint, String token) throws IOException {
        return new DebugClient(endpoint, token);
    }

    public DebugEndpointDescriptor endpoint() { return endpoint; }
    public JsonNode hello() { return hello.deepCopy(); }

    public synchronized JsonNode request(String operation, JsonNode body, long deadlineMs) throws IOException {
        return requestWithAttachment(operation, body, deadlineMs).result();
    }

    public synchronized DebugResponse requestWithAttachment(String operation, JsonNode body, long deadlineMs) throws IOException {
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
        if (deadlineMs < 1 || deadlineMs > DebugChannelServer.MAX_DEADLINE_MS) throw new IllegalArgumentException("invalid deadline");
        String id = UUID.randomUUID().toString();
        ObjectNode request = DebugJson.MAPPER.createObjectNode();
        request.put("id", id);
        request.put("operation", operation);
        request.put("deadlineMs", deadlineMs);
        request.set("body", body == null ? DebugJson.MAPPER.createObjectNode() : body);
        return withTransportTimeout(deadlineMs + RESPONSE_GRACE_MS, "debug request timed out: " + operation, () -> {
            codec.write(output, jsonFrame(DebugFrameKind.REQUEST, request));
            DebugFrame response = codec.read(input);
            JsonNode responseBody = DebugJson.MAPPER.readTree(response.payload());
            if (response.kind() == DebugFrameKind.ERROR) throw remoteError(responseBody);
            if (response.kind() != DebugFrameKind.RESPONSE) throw new DebugProtocolException("expected response frame");
            if (!id.equals(responseBody.path("id").asText())) throw new DebugProtocolException("response id mismatch");
            JsonNode attachment = responseBody.path("attachment");
            if (!attachment.isObject()) return new DebugResponse(responseBody.get("result"), null, null, null);
            int expectedLength = attachment.path("length").asInt(-1);
            DebugFrame binary = codec.read(input);
            if (binary.kind() != DebugFrameKind.BINARY) throw new DebugProtocolException("expected binary attachment frame");
            byte[] bytes = binary.payload();
            if (expectedLength < 0 || bytes.length != expectedLength) throw new DebugProtocolException("binary attachment length mismatch");
            return new DebugResponse(responseBody.get("result"), bytes,
                attachment.path("mediaType").asText(null), attachment.path("name").asText(null));
        });
    }

    public JsonNode request(String operation) throws IOException {
        return request(operation, DebugJson.MAPPER.createObjectNode(), 5_000);
    }

    private static DebugClientException remoteError(JsonNode body) {
        JsonNode error = body.path("error");
        return new DebugClientException(error.path("code").asText("internal_error"), error.path("message").asText("debug request failed"));
    }

    private static DebugFrame jsonFrame(DebugFrameKind kind, JsonNode value) throws IOException {
        return new DebugFrame(DebugChannelServer.PROTOCOL_VERSION, kind, 0, DebugJson.MAPPER.writeValueAsBytes(value));
    }

    private <T> T withTransportTimeout(long timeoutMs, String message, IoOperation<T> operation) throws IOException {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = TIMEOUTS.schedule(() -> {
            timedOut.set(true);
            try {
                connection.close();
            } catch (IOException ignored) {
                // The pending transport operation will surface its own failure.
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            return operation.run();
        } catch (IOException error) {
            if (!timedOut.get()) throw error;
            DebugClientException timeout = new DebugClientException("deadline_exceeded", message);
            timeout.initCause(error);
            throw timeout;
        } finally {
            watchdog.cancel(false);
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
