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

import com.mojang.datafixers.util.Either;
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness;
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalJavaInterop;
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage;
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalSampler;
import de.bixilon.minosoft.terrain.distant.network.DistantProtocolWorld;
import de.bixilon.minosoft.terrain.distant.network.DistantRequestedPage;
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2;
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainProtocolV2;
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain;
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey;
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
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional managed-server producer for Minosoft's source-native DH channel.
 *
 * Requests are explicit, radius checked, queue bounded, and processed at a
 * small per-tick budget. FULL-chunk futures are the explicit authoritative
 * unexplored-generation boundary; the normal server tick never blocks on a
 * synchronous chunk lookup for production v2 work.
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
    private static final int MAX_IN_FLIGHT_PAGES = 16;
    private static final int MAX_PALETTE_SIZE = 1_024;
    private static final int MAX_STRING_BYTES = 512;
    private static final boolean ALLOW_V1_REQUESTS = false;
    private static final Map<UUID, ArrayDeque<TileRequest>> REQUESTS = new LinkedHashMap<>();
    private static final Map<UUID, PlayerWorldState> PLAYER_WORLDS = new LinkedHashMap<>();
    private static final Map<PageWorkKey, CompletableFuture<DistantVerticalPage>> IN_FLIGHT = new LinkedHashMap<>();
    private static final Map<PlayerRequestKey, Integer> V2_REMAINING = new LinkedHashMap<>();
    private static final AtomicLong REQUEST_COUNT = new AtomicLong();
    private static final AtomicLong TILE_COUNT = new AtomicLong();
    private static final AtomicLong CONNECTION_EPOCH = new AtomicLong();
    private static final AtomicLong SOURCE_REVISION = new AtomicLong();
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
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerWorldState state = new PlayerWorldState(
                CONNECTION_EPOCH.updateAndGet(Math::incrementExact),
                0L,
                levelKey(handler.player.getServerWorld())
            );
            synchronized (REQUESTS) {
                PLAYER_WORLDS.put(handler.player.getUuid(), state);
            }
            send(handler.player, helloV2(state));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            synchronized (REQUESTS) {
                REQUESTS.remove(handler.player.getUuid());
                PLAYER_WORLDS.remove(handler.player.getUuid());
                V2_REMAINING.keySet().removeIf(key -> key.playerId.equals(handler.player.getUuid()));
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
        try {
            if (DistantTerrainProtocolV2.INSTANCE.isV2(payload)) {
                DistantTerrainMessageV2 message = DistantTerrainProtocolV2.INSTANCE.decode(payload);
                server.execute(() -> {
                    try {
                        receiveV2(player, message);
                    } catch (RuntimeException error) {
                        System.err.println("Rejected Minosoft distant v2 request: " + error.getMessage());
                    }
                });
            } else {
                require(ALLOW_V1_REQUESTS, "legacy distant requests are disabled after v2 negotiation");
                Request request = decodeRequest(payload);
                server.execute(() -> enqueue(player, request));
            }
        } catch (RuntimeException | IOException error) {
            System.err.println("Rejected malformed Minosoft distant LOD request: " + error.getMessage());
        }
    }

    private static void receiveV2(ServerPlayerEntity player, DistantTerrainMessageV2 message) {
        if (message instanceof DistantTerrainMessageV2.Cancel cancel) {
            synchronized (REQUESTS) {
                PlayerWorldState state = PLAYER_WORLDS.get(player.getUuid());
                require(state != null && state.protocolWorld().equals(cancel.getWorld()), "cancel world/epoch is stale");
                ArrayDeque<TileRequest> queue = REQUESTS.get(player.getUuid());
                if (queue != null) queue.removeIf(request ->
                    request.v2 && request.requestId == cancel.getRequestId() && request.worldState.equals(state)
                );
                V2_REMAINING.remove(new PlayerRequestKey(player.getUuid(), cancel.getRequestId(), cancel.getWorld()));
            }
            return;
        }
        if (!(message instanceof DistantTerrainMessageV2.Request request)) {
            throw new IllegalArgumentException("unexpected client v2 message");
        }
        require(request.getPages().size() <= MAX_TILES_PER_REQUEST, "page count is out of bounds");
        ChunkPos center = player.getChunkPos();
        List<TileRequest> admitted = new ArrayList<>(request.getPages().size());
        for (DistantRequestedPage page : request.getPages()) {
            TerrainPageKey key = page.getKey();
            require(key.getDetailLevel() == 0, "server currently supports base pages only");
            ChunkPos position = new ChunkPos(Math.toIntExact(key.getX()), Math.toIntExact(key.getZ()));
            require(withinRadius(position, center), "requested page is outside negotiated radius");
            admitted.add(new TileRequest(
                request.getRequestId(), position, true, key, null, page.getMinimumSourceRevision()
            ));
        }
        synchronized (REQUESTS) {
            PlayerWorldState state = PLAYER_WORLDS.get(player.getUuid());
            require(state != null && state.protocolWorld().equals(request.getWorld()), "request world/epoch is stale");
            PlayerRequestKey requestKey = new PlayerRequestKey(player.getUuid(), request.getRequestId(), request.getWorld());
            require(!V2_REMAINING.containsKey(requestKey), "request ID is already active");
            ArrayDeque<TileRequest> queue = REQUESTS.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>());
            require(queue.size() + request.getPages().size() <= MAX_QUEUED_TILES, "player page queue is saturated");
            for (TileRequest page : admitted) {
                queue.addLast(new TileRequest(
                    page.requestId, page.position, true, page.pageKey, state, page.minimumSourceRevision
                ));
            }
            V2_REMAINING.put(requestKey, request.getPages().size());
        }
        incrementSaturating(REQUEST_COUNT);
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
        incrementSaturating(REQUEST_COUNT);
        synchronized (REQUESTS) {
            ArrayDeque<TileRequest> queue = REQUESTS.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>());
            for (ChunkPos position : request.positions) {
                if (queue.size() >= MAX_QUEUED_TILES) break;
                if (!withinRadius(position, center)) continue;
                queue.addLast(new TileRequest(request.requestId, position, false, null, null, 0L));
            }
        }
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) refreshWorld(player);
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
                if (!withinRadius(request.position, center)) {
                    if (request.v2) completeV2Page(player.getUuid(), request);
                    continue;
                }
                if (request.v2) {
                    if (!beginV2(server, player, request)) {
                        synchronized (REQUESTS) {
                            REQUESTS.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>()).addFirst(request);
                        }
                    }
                    continue;
                }
                try {
                    send(player, response(Math.toIntExact(request.requestId), capture(player.getServerWorld(), request.position)));
                    incrementSaturating(TILE_COUNT);
                } catch (RuntimeException | IOException error) {
                    System.err.println("Could not produce Minosoft distant LOD tile " + request.position + ": " + error);
                }
            }
        }
    }

    private static void refreshWorld(ServerPlayerEntity player) {
        String level = levelKey(player.getServerWorld());
        PlayerWorldState next = null;
        synchronized (REQUESTS) {
            PlayerWorldState current = PLAYER_WORLDS.get(player.getUuid());
            if (current == null) return;
            if (!current.levelKey.equals(level)) {
                next = new PlayerWorldState(current.connectionEpoch, Math.addExact(current.worldEpoch, 1L), level);
                PLAYER_WORLDS.put(player.getUuid(), next);
                REQUESTS.remove(player.getUuid());
                V2_REMAINING.keySet().removeIf(key -> key.playerId.equals(player.getUuid()));
            }
        }
        if (next != null) send(player, helloV2(next));
    }

    private static boolean beginV2(MinecraftServer server, ServerPlayerEntity player, TileRequest request) {
        PlayerWorldState current;
        synchronized (REQUESTS) {
            current = PLAYER_WORLDS.get(player.getUuid());
        }
        if (current == null || !current.equals(request.worldState) ||
            !levelKey(player.getServerWorld()).equals(request.worldState.levelKey)) {
            completeV2Page(player.getUuid(), request);
            return true;
        }
        ServerWorld world = player.getServerWorld();
        PageWorkKey workKey = new PageWorkKey(
            world,
            request.worldState.levelKey,
            request.pageKey.getDetailLevel(),
            request.pageKey.getX(),
            request.pageKey.getY(),
            request.pageKey.getZ()
        );
        CompletableFuture<DistantVerticalPage> future;
        synchronized (IN_FLIGHT) {
            future = IN_FLIGHT.get(workKey);
            if (future == null) {
                if (IN_FLIGHT.size() >= MAX_IN_FLIGHT_PAGES) return false;
                CompletableFuture<DistantVerticalPage> created = world.getChunkManager()
                    .getChunkFutureSyncOnMainThread(request.position.x, request.position.z, ChunkStatus.FULL, true)
                    .thenApplyAsync(result -> captureV2(world, request.pageKey, requireChunk(result)), server);
                future = created;
                IN_FLIGHT.put(workKey, future);
                created.whenComplete((page, error) -> {
                    synchronized (IN_FLIGHT) {
                        IN_FLIGHT.remove(workKey, created);
                    }
                });
            }
        }
        future.whenCompleteAsync((page, error) -> {
            try {
                if (error != null) {
                    System.err.println("Could not produce Minosoft distant v2 page " + request.position + ": " + error);
                    return;
                }
                PlayerWorldState active;
                boolean requested;
                synchronized (REQUESTS) {
                    active = PLAYER_WORLDS.get(player.getUuid());
                    requested = V2_REMAINING.containsKey(
                        new PlayerRequestKey(player.getUuid(), request.requestId, request.worldState.protocolWorld())
                    );
                }
                if (!request.worldState.equals(active) || !requested || player.isDisconnected()) return;
                send(player, DistantTerrainProtocolV2.INSTANCE.encode(
                    new DistantTerrainMessageV2.Response(
                        request.worldState.protocolWorld(), request.requestId,
                        List.of(rekey(page, request.pageKey, request.minimumSourceRevision))
                    )
                ));
                incrementSaturating(TILE_COUNT);
            } catch (RuntimeException sendError) {
                System.err.println("Could not publish Minosoft distant v2 page " + request.position + ": " + sendError);
            } finally {
                completeV2Page(player.getUuid(), request);
            }
        }, server);
        return true;
    }

    private static void completeV2Page(UUID playerId, TileRequest request) {
        synchronized (REQUESTS) {
            PlayerRequestKey key = new PlayerRequestKey(playerId, request.requestId, request.worldState.protocolWorld());
            V2_REMAINING.computeIfPresent(key, (ignored, remaining) -> remaining <= 1 ? null : remaining - 1);
        }
    }

    private static DistantVerticalPage rekey(
        DistantVerticalPage page,
        TerrainPageKey key,
        long minimumSourceRevision
    ) {
        return new DistantVerticalPage(
            key,
            page.getWidth(),
            page.getOriginY(),
            Math.max(page.getSourceRevision(), minimumSourceRevision),
            page.getCompleteness(),
            page.getColumns()
        );
    }

    private static Chunk requireChunk(Either<Chunk, net.minecraft.server.world.ChunkHolder.Unloaded> result) {
        return result.left().orElseThrow(() -> new IllegalStateException("generated chunk remained unloaded"));
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

    private static DistantVerticalPage captureV2(ServerWorld world, TerrainPageKey key, Chunk chunk) {
        ChunkPos position = chunk.getPos();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        return DistantVerticalSampler.INSTANCE.capture(
            key,
            16,
            world.getBottomY(),
            world.getTopY(),
            SOURCE_REVISION.updateAndGet(Math::incrementExact),
            DistantSourceCompleteness.COMPLETE,
            (x, y, z) -> {
                BlockPos blockPosition = mutable.set(position.getStartX() + x, y, position.getStartZ() + z);
                BlockState state = chunk.getBlockState(blockPosition);
                int blockLight = world.getLightLevel(LightType.BLOCK, blockPosition);
                int skyLight = world.getLightLevel(LightType.SKY, blockPosition);
                if (state.isAir()) {
                    return DistantVerticalJavaInterop.voxel(
                        null, null, 0, null, blockLight, skyLight, null, false, false, true, 100
                    );
                }
                String materialId = Registries.BLOCK.getId(state.getBlock()).toString();
                var fluidState = state.getFluidState();
                String fluidId = null;
                int fluidLevel = 0;
                if (!fluidState.isEmpty()) {
                    fluidId = Registries.FLUID.getId(fluidState.getFluid()).toString();
                    fluidLevel = Math.max(0, Math.min(15, fluidState.getLevel()));
                }
                String biome = world.getBiome(blockPosition).getKey()
                    .map(registryKey -> registryKey.getValue().toString())
                    .orElse(null);
                return DistantVerticalJavaInterop.voxel(
                    materialId,
                    fluidId,
                    fluidLevel,
                    fluidId,
                    blockLight,
                    skyLight,
                    biome,
                    state.isOpaque(),
                    state.getLuminance() > 0,
                    true,
                    100
                );
            }
        );
    }

    private static byte[] helloV2(PlayerWorldState state) {
        return helloV2(state.connectionEpoch, state.worldEpoch, state.levelKey);
    }

    static byte[] helloV2(long connectionEpoch, long worldEpoch, String levelKey) {
        return DistantTerrainProtocolV2.INSTANCE.encode(
            new DistantTerrainMessageV2.Hello(
                new DistantProtocolWorld(connectionEpoch, worldEpoch, levelKey),
                MAX_TILES_PER_REQUEST,
                MAX_RADIUS_CHUNKS,
                0
            )
        );
    }

    private static String levelKey(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
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

    private static void incrementSaturating(AtomicLong counter) {
        counter.updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1L);
    }

    static int queuedTiles() {
        synchronized (REQUESTS) {
            return REQUESTS.values().stream().mapToInt(ArrayDeque::size).sum();
        }
    }

    static int inFlightPages() {
        synchronized (IN_FLIGHT) {
            return IN_FLIGHT.size();
        }
    }

    static int activeV2Requests() {
        synchronized (REQUESTS) {
            return V2_REMAINING.size();
        }
    }

    record Request(int requestId, List<ChunkPos> positions) {
    }

    private record TileRequest(
        long requestId,
        ChunkPos position,
        boolean v2,
        TerrainPageKey pageKey,
        PlayerWorldState worldState,
        long minimumSourceRevision
    ) {
    }

    private record PlayerWorldState(long connectionEpoch, long worldEpoch, String levelKey) {
        DistantProtocolWorld protocolWorld() {
            return new DistantProtocolWorld(connectionEpoch, worldEpoch, levelKey);
        }
    }

    private record PageWorkKey(ServerWorld world, String levelKey, int detailLevel, long x, long y, long z) {
    }

    private record PlayerRequestKey(UUID playerId, long requestId, DistantProtocolWorld world) {
    }

    record Tile(ChunkPos position, Column[] columns) {
    }

    record Column(int surfaceY, String material, int solidY, String solidMaterial) {
        static Column empty() {
            return new Column(Integer.MIN_VALUE, null, Integer.MIN_VALUE, null);
        }
    }
}
