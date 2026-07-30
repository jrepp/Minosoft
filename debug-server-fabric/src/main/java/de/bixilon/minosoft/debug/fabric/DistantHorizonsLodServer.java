/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.debug.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional managed-server producer for Minosoft's source-native DH channel.
 *
 * Requests are explicit, radius checked, queue bounded, and processed at a
 * small per-tick budget. Calling {@code getChunk} is the intentional
 * authoritative unexplored-generation boundary.
 */
final class DistantHorizonsLodServer {
    private static final Identifier CHANNEL = new Identifier("minosoft", "distant_horizons_lod");
    private static final int MAGIC = 0x4D44484E;
    private static final int VERSION = 1;
    private static final int TYPE_HELLO = 0;
    private static final int TYPE_REQUEST = 1;
    private static final int TYPE_RESPONSE = 2;
    private static final int MAX_PAYLOAD_BYTES = 1 << 20;
    private static final int MAX_TILES_PER_REQUEST = 32;
    private static final int MAX_RADIUS_CHUNKS = 256;
    private static final int MAX_QUEUED_TILES = 128;
    private static final int TILES_PER_PLAYER_INTERVAL = 1;
    private static final int GENERATION_INTERVAL_TICKS = 20;
    private static final int MAX_PALETTE_SIZE = 1_024;
    private static final int MAX_STRING_BYTES = 512;
    private static final Map<UUID, ArrayDeque<TileRequest>> REQUESTS = new LinkedHashMap<>();
    private static final AtomicLong REQUEST_COUNT = new AtomicLong();
    private static final AtomicLong TILE_COUNT = new AtomicLong();
    private static long tickCount;

    private DistantHorizonsLodServer() {
    }

    static void install() {
        boolean registered = ServerPlayNetworking.registerGlobalReceiver(
            CHANNEL,
            (server, player, handler, buffer, responseSender) -> receive(server, player, buffer)
        );
        if (!registered) {
            throw new IllegalStateException("Distant Horizons LOD server channel is already registered");
        }
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            send(handler.player, hello())
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            synchronized (REQUESTS) {
                REQUESTS.remove(handler.player.getUuid());
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(DistantHorizonsLodServer::tick);
        System.out.println(
            "MINOSOFT_DH_LOD_SERVER_READY version=" + VERSION +
                " maxRadiusChunks=" + MAX_RADIUS_CHUNKS +
                " tilesPerInterval=" + TILES_PER_PLAYER_INTERVAL +
                " intervalTicks=" + GENERATION_INTERVAL_TICKS
        );
    }

    private static void receive(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buffer) {
        int size = buffer.readableBytes();
        if (size <= 0 || size > MAX_PAYLOAD_BYTES) return;
        byte[] payload = new byte[size];
        buffer.readBytes(payload);
        Request request;
        try {
            request = decodeRequest(payload);
        } catch (RuntimeException | IOException error) {
            System.err.println("Rejected malformed Minosoft distant LOD request: " + error.getMessage());
            return;
        }
        server.execute(() -> enqueue(player, request));
    }

    static Request decodeRequest(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            require(input.readInt() == MAGIC, "invalid magic");
            require(input.readUnsignedByte() == VERSION, "unsupported version");
            require(input.readUnsignedByte() == TYPE_REQUEST, "unexpected message type");
            int requestId = input.readInt();
            int count = input.readUnsignedShort();
            require(count >= 1 && count <= MAX_TILES_PER_REQUEST, "tile count is out of bounds");
            List<ChunkPos> positions = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                positions.add(new ChunkPos(input.readInt(), input.readInt()));
            }
            require(input.read() == -1, "trailing request data");
            return new Request(requestId, positions);
        }
    }

    private static void enqueue(ServerPlayerEntity player, Request request) {
        ChunkPos center = player.getChunkPos();
        REQUEST_COUNT.incrementAndGet();
        synchronized (REQUESTS) {
            ArrayDeque<TileRequest> queue = REQUESTS.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>());
            for (ChunkPos position : request.positions) {
                if (queue.size() >= MAX_QUEUED_TILES) break;
                if (!withinRadius(position, center)) continue;
                queue.addLast(new TileRequest(request.requestId, position));
            }
        }
    }

    private static void tick(MinecraftServer server) {
        if (++tickCount % GENERATION_INTERVAL_TICKS != 0L) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            for (int index = 0; index < TILES_PER_PLAYER_INTERVAL; index++) {
                TileRequest request;
                synchronized (REQUESTS) {
                    ArrayDeque<TileRequest> queue = REQUESTS.get(player.getUuid());
                    request = queue == null ? null : queue.pollFirst();
                    if (queue != null && queue.isEmpty()) REQUESTS.remove(player.getUuid());
                }
                if (request == null) break;
                ChunkPos center = player.getChunkPos();
                if (!withinRadius(request.position, center)) continue;
                try {
                    send(player, response(request.requestId, capture(player.getServerWorld(), request.position)));
                    TILE_COUNT.incrementAndGet();
                } catch (RuntimeException | IOException error) {
                    System.err.println("Could not produce Minosoft distant LOD tile " + request.position + ": " + error);
                }
            }
        }
    }

    static boolean withinRadius(ChunkPos position, ChunkPos center) {
        return Math.abs((long) position.x - center.x) <= MAX_RADIUS_CHUNKS
            && Math.abs((long) position.z - center.z) <= MAX_RADIUS_CHUNKS;
    }

    private static Tile capture(ServerWorld world, ChunkPos position) {
        world.getChunk(position.x, position.z);
        Column[] columns = new Column[16 * 16];
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = position.getStartX() + x;
                int worldZ = position.getStartZ() + z;
                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ) - 1;
                String material = null;
                BlockState surface = null;
                while (surfaceY >= world.getBottomY()) {
                    surface = world.getBlockState(mutable.set(worldX, surfaceY, worldZ));
                    if (!surface.isAir()) {
                        material = Registries.BLOCK.getId(surface.getBlock()).toString();
                        break;
                    }
                    surfaceY--;
                }
                if (material == null || surface == null) {
                    columns[(z << 4) | x] = Column.empty();
                    continue;
                }
                int solidY = surfaceY;
                String solidMaterial = material;
                if (!surface.getFluidState().isEmpty()) {
                    solidY = surfaceY - 1;
                    solidMaterial = null;
                    while (solidY >= world.getBottomY()) {
                        BlockState candidate = world.getBlockState(mutable.set(worldX, solidY, worldZ));
                        if (!candidate.isAir() && candidate.getFluidState().isEmpty()) {
                            solidMaterial = Registries.BLOCK.getId(candidate.getBlock()).toString();
                            break;
                        }
                        solidY--;
                    }
                    if (solidMaterial == null) solidY = Integer.MIN_VALUE;
                }
                columns[(z << 4) | x] = new Column(surfaceY, material, solidY, solidMaterial);
            }
        }
        return new Tile(position, columns);
    }

    static byte[] hello() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(TYPE_HELLO);
                output.writeShort(MAX_TILES_PER_REQUEST);
                output.writeShort(MAX_RADIUS_CHUNKS);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static byte[] response(int requestId, Tile tile) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(TYPE_RESPONSE);
            output.writeInt(requestId);
            output.writeShort(1);
            writeTile(output, tile);
        }
        byte[] payload = bytes.toByteArray();
        require(payload.length <= MAX_PAYLOAD_BYTES, "response exceeds payload limit");
        return payload;
    }

    private static void writeTile(DataOutputStream output, Tile tile) throws IOException {
        output.writeInt(tile.position.x);
        output.writeInt(tile.position.z);
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (Column column : tile.columns) {
            if (column.material != null) palette.computeIfAbsent(column.material, ignored -> palette.size() + 1);
            if (column.solidMaterial != null) palette.computeIfAbsent(column.solidMaterial, ignored -> palette.size() + 1);
        }
        require(palette.size() <= MAX_PALETTE_SIZE, "tile palette exceeds limit");
        output.writeShort(palette.size());
        for (String material : palette.keySet()) writeString(output, material);
        for (Column column : tile.columns) {
            output.writeInt(column.surfaceY);
            output.writeShort(column.material == null ? 0 : palette.get(column.material));
            output.writeInt(column.solidY);
            output.writeShort(column.solidMaterial == null ? 0 : palette.get(column.solidMaterial));
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_STRING_BYTES, "material identifier exceeds limit");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void send(ServerPlayerEntity player, byte[] payload) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeBytes(payload);
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static long requestCount() {
        return REQUEST_COUNT.get();
    }

    static long tileCount() {
        return TILE_COUNT.get();
    }

    static int queuedTiles() {
        synchronized (REQUESTS) {
            return REQUESTS.values().stream().mapToInt(ArrayDeque::size).sum();
        }
    }

    record Request(int requestId, List<ChunkPos> positions) {
    }

    private record TileRequest(int requestId, ChunkPos position) {
    }

    record Tile(ChunkPos position, Column[] columns) {
    }

    record Column(int surfaceY, String material, int solidY, String solidMaterial) {
        static Column empty() {
            return new Column(Integer.MIN_VALUE, null, Integer.MIN_VALUE, null);
        }
    }
}
