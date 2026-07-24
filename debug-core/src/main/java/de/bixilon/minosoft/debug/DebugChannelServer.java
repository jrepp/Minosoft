/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class DebugChannelServer implements AutoCloseable {
    public static final int PROTOCOL_VERSION = 1;
    public static final long MAX_DEADLINE_MS = 30_000;
    private static final int MAX_CONNECTIONS = 8;
    private static final int MAX_QUEUED_CONNECTIONS = 32;

    private final DebugPaths paths;
    private final DebugDiscovery discovery;
    private final DebugEndpointDescriptor endpoint;
    private final String token;
    private final DebugFrameCodec codec = new DebugFrameCodec();
    private final DebugOperationRegistry operations = new DebugOperationRegistry();
    private final DebugMetrics metrics = new DebugMetrics();
    private final ExecutorService acceptExecutor;
    private final ExecutorService connectionExecutor;
    private volatile Supplier<JsonNode> statusSupplier = () -> DebugJson.MAPPER.createObjectNode();
    private volatile Supplier<JsonNode> metricsSupplier = () -> DebugJson.MAPPER.createObjectNode();
    private volatile DebugTransportListener server;

    public DebugChannelServer(DebugPaths paths, DebugEndpointRole role, String trajectory, int generation) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.discovery = new DebugDiscovery(paths);
        long pid = ProcessHandle.current().pid();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String id = role.wireName() + "-" + pid + "-" + generation + "-" + suffix;
        Instant started = ProcessHandle.current().info().startInstant().orElseGet(Instant::now);
        this.endpoint = new DebugEndpointDescriptor(
            DebugEndpointDescriptor.SCHEMA,
            id,
            role,
            pid,
            started,
            trajectory,
            generation,
            DebugTransports.name(),
            DebugTransports.address(paths, id),
            "../credentials/" + id + ".token",
            PROTOCOL_VERSION,
            PROTOCOL_VERSION
        );
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        this.acceptExecutor = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "minosoft-debug-accept-" + role.wireName()));
        this.connectionExecutor = new ThreadPoolExecutor(
            MAX_CONNECTIONS,
            MAX_CONNECTIONS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_CONNECTIONS),
            runnable -> daemon(runnable, "minosoft-debug-connection-" + role.wireName()),
            new ThreadPoolExecutor.AbortPolicy()
        );
        registerCoreOperations();
    }

    public DebugEndpointDescriptor endpoint() { return endpoint; }
    public DebugOperationRegistry operations() { return operations; }

    public void setStatusSupplier(Supplier<JsonNode> statusSupplier) {
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
    }

    public void setMetricsSupplier(Supplier<JsonNode> metricsSupplier) {
        this.metricsSupplier = Objects.requireNonNull(metricsSupplier, "metricsSupplier");
    }

    public synchronized void start() throws IOException {
        if (server != null) throw new IllegalStateException("debug channel already started");
        paths.initialize();
        if ("unix".equals(endpoint.getTransport())) Files.deleteIfExists(paths.socket(endpoint.getId()));
        DebugTransportListener opened = DebugTransports.listen(endpoint.getTransport(), endpoint.getAddress());
        try {
            if ("unix".equals(endpoint.getTransport())) DebugPaths.makePrivateFile(paths.socket(endpoint.getId()));
            discovery.publish(endpoint, token);
            server = opened;
        } catch (IOException | RuntimeException error) {
            opened.close();
            if ("unix".equals(endpoint.getTransport())) Files.deleteIfExists(paths.socket(endpoint.getId()));
            throw error;
        }
        acceptExecutor.execute(this::acceptLoop);
    }

    private void registerCoreOperations() {
        operations.register("core", "core.ping", (context, body) -> CompletableFuture.completedFuture(
            DebugOperationResult.json(DebugJson.MAPPER.createObjectNode()
                .put("endpointId", endpoint.getId())
                .put("role", endpoint.getRole().wireName())
                .put("generation", endpoint.getGeneration())
                .put("time", Instant.now().toString()))
        ));
        operations.register("core", "core.capabilities", (context, body) -> {
            ObjectNode result = DebugJson.MAPPER.createObjectNode();
            ArrayNode available = result.putArray("operations");
            for (DebugOperationRegistry.Operation operation : operations.snapshot()) {
                available.addObject().put("name", operation.name()).put("owner", operation.owner());
            }
            result.put("maxPayloadBytes", DebugFrameCodec.DEFAULT_MAX_PAYLOAD);
            result.put("maxDeadlineMs", MAX_DEADLINE_MS);
            return CompletableFuture.completedFuture(DebugOperationResult.json(result));
        });
        operations.register("core", "core.status", (context, body) -> {
            ObjectNode result = DebugJson.MAPPER.createObjectNode();
            JsonNode supplied = statusSupplier.get();
            if (supplied != null && supplied.isObject()) result.setAll((ObjectNode) supplied);
            result.put("endpointId", endpoint.getId());
            result.put("role", endpoint.getRole().wireName());
            result.put("pid", endpoint.getPid());
            result.put("trajectory", endpoint.getTrajectory());
            result.put("generation", endpoint.getGeneration());
            return CompletableFuture.completedFuture(DebugOperationResult.json(result));
        });
        operations.register("core", "metrics.snapshot", (context, body) -> {
            ObjectNode result = metrics.snapshot(endpoint);
            JsonNode supplied = metricsSupplier.get();
            if (supplied != null && supplied.isObject()) result.set("runtime", supplied);
            return CompletableFuture.completedFuture(DebugOperationResult.json(result));
        });
    }

    private void acceptLoop() {
        while (server != null && server.isOpen()) {
            try {
                DebugTransportConnection connection = server.accept();
                try {
                    connectionExecutor.execute(() -> handleConnection(connection));
                } catch (RejectedExecutionException saturated) {
                    connection.close();
                }
            } catch (AsynchronousCloseException ignored) {
                return;
            } catch (IOException error) {
                if (server != null && server.isOpen()) error.printStackTrace(System.err);
                return;
            }
        }
    }

    private void handleConnection(DebugTransportConnection connection) {
        try (connection) {
            InputStream input = connection.input;
            OutputStream output = connection.output;
            if (!authenticate(input, output)) return;
            while (true) {
                DebugFrame frame;
                try {
                    frame = codec.read(input);
                } catch (EOFException end) {
                    return;
                }
                if (frame.kind() != DebugFrameKind.REQUEST) {
                    sendError(output, null, "invalid_request", "expected request frame");
                    continue;
                }
                handleRequest(output, frame);
            }
        } catch (IOException ignored) {
            // A local debugging client can disconnect at any point.
        }
    }

    private boolean authenticate(InputStream input, OutputStream output) throws IOException {
        DebugFrame frame = codec.read(input);
        if (frame.kind() != DebugFrameKind.HELLO || frame.version() != PROTOCOL_VERSION) {
            sendError(output, null, "unauthenticated", "valid hello required");
            return false;
        }
        JsonNode hello = DebugJson.MAPPER.readTree(frame.payload());
        String endpointId = hello.path("endpointId").asText("");
        String suppliedToken = hello.path("token").asText("");
        boolean valid = endpoint.getId().equals(endpointId) && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
        if (!valid) {
            sendError(output, null, "unauthenticated", "invalid endpoint credential");
            return false;
        }
        ObjectNode response = DebugJson.MAPPER.createObjectNode();
        response.put("protocolVersion", PROTOCOL_VERSION);
        response.put("endpointId", endpoint.getId());
        response.put("role", endpoint.getRole().wireName());
        response.put("generation", endpoint.getGeneration());
        response.put("maxPayloadBytes", DebugFrameCodec.DEFAULT_MAX_PAYLOAD);
        codec.write(output, jsonFrame(DebugFrameKind.HELLO, response));
        return true;
    }

    private void handleRequest(OutputStream output, DebugFrame frame) throws IOException {
        JsonNode request;
        try {
            request = DebugJson.MAPPER.readTree(frame.payload());
        } catch (RuntimeException | IOException malformed) {
            sendError(output, null, "invalid_request", "request body is not valid JSON");
            return;
        }
        String id = request.path("id").asText("");
        String operationName = request.path("operation").asText("");
        long deadlineMs = request.path("deadlineMs").asLong(5_000);
        if (id.isBlank() || operationName.isBlank() || deadlineMs < 1 || deadlineMs > MAX_DEADLINE_MS) {
            sendError(output, id, "invalid_request", "invalid id, operation, or deadline");
            return;
        }
        DebugOperationRegistry.Operation operation = operations.find(operationName);
        if (operation == null) {
            sendError(output, id, "unsupported_operation", "operation is not available: " + operationName);
            return;
        }
        Instant deadline = Instant.now().plusMillis(deadlineMs);
        DebugRequestContext context = new DebugRequestContext(id, operationName, deadline, endpoint);
        JsonNode body = request.path("body");
        long startedNanos = System.nanoTime();
        String outcome = "error";
        CompletableFuture<DebugOperationResult> pending = null;
        try {
            pending = operation.handler().handle(context, body).toCompletableFuture();
            DebugOperationResult operationResult = pending.get(
                Math.max(1, Duration.between(Instant.now(), deadline).toMillis()),
                TimeUnit.MILLISECONDS
            );
            byte[] attachment = operationResult == null ? null : operationResult.attachment();
            if (attachment != null && attachment.length > DebugFrameCodec.DEFAULT_MAX_PAYLOAD) {
                throw new DebugOperationException(
                    "limit_exceeded",
                    "attachment exceeds the " + DebugFrameCodec.DEFAULT_MAX_PAYLOAD + " byte limit"
                );
            }
            ObjectNode response = DebugJson.MAPPER.createObjectNode().put("id", id);
            JsonNode result = operationResult == null ? null : operationResult.result();
            response.set("result", result == null ? DebugJson.MAPPER.nullNode() : result);
            if (attachment != null) {
                ObjectNode metadata = response.putObject("attachment");
                metadata.put("length", attachment.length);
                metadata.put("mediaType", operationResult.attachmentType());
                if (operationResult.attachmentName() != null) metadata.put("name", operationResult.attachmentName());
            }
            codec.write(output, jsonFrame(DebugFrameKind.RESPONSE, response));
            if (attachment != null) {
                codec.write(output, new DebugFrame(PROTOCOL_VERSION, DebugFrameKind.BINARY, 0, attachment));
            }
            outcome = "success";
        } catch (TimeoutException error) {
            outcome = "timeout";
            if (pending != null) pending.cancel(true);
            sendError(output, id, "deadline_exceeded", "operation did not complete before its deadline");
        } catch (InterruptedException error) {
            outcome = "cancelled";
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            sendError(output, id, "cancelled", "operation was interrupted");
        } catch (Exception error) {
            Throwable cause = unwrap(error);
            if (cause instanceof DebugOperationException) {
                DebugOperationException operationError = (DebugOperationException) cause;
                sendError(output, id, operationError.code(), operationError.getMessage());
            } else {
                System.err.println("Debug operation failed: " + operationName);
                cause.printStackTrace(System.err);
                sendError(output, id, "internal_error", "operation failed");
            }
        } finally {
            metrics.record(operationName, System.nanoTime() - startedNanos, outcome);
        }
    }

    private void sendError(OutputStream output, String id, String code, String message) throws IOException {
        ObjectNode response = DebugJson.MAPPER.createObjectNode();
        if (id != null && !id.isBlank()) response.put("id", id);
        response.putObject("error").put("code", code).put("message", message);
        codec.write(output, jsonFrame(DebugFrameKind.ERROR, response));
    }

    private static DebugFrame jsonFrame(DebugFrameKind kind, JsonNode value) throws IOException {
        return new DebugFrame(PROTOCOL_VERSION, kind, 0, DebugJson.MAPPER.writeValueAsBytes(value));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public synchronized void close() throws IOException {
        DebugTransportListener opened = server;
        server = null;
        if (opened != null) opened.close();
        acceptExecutor.shutdownNow();
        connectionExecutor.shutdownNow();
        discovery.remove(endpoint);
        if ("unix".equals(endpoint.getTransport())) Files.deleteIfExists(paths.socket(endpoint.getId()));
    }
}
