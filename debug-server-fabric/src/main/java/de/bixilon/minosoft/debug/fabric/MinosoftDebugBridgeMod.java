/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugChannelServer;
import de.bixilon.minosoft.debug.DebugEndpointRole;
import de.bixilon.minosoft.debug.DebugJson;
import de.bixilon.minosoft.debug.DebugOperationException;
import de.bixilon.minosoft.debug.DebugOperationResult;
import de.bixilon.minosoft.debug.DebugPaths;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class MinosoftDebugBridgeMod implements ModInitializer {
    private static final int MAX_BLOCKS = 32_768;
    private static final double MAX_TELEPORT_COORDINATE = 30_000_000.0;
    private volatile DebugChannelServer channel;
    private volatile MinecraftServer minecraft;
    private volatile ServerSnapshot snapshot = ServerSnapshot.empty();

    @Override
    public void onInitialize() {
        if (!enabled()) return;
        DistantHorizonsLodServer.install();
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> close());
        ServerTickEvents.END_SERVER_TICK.register(this::updateSnapshot);
    }

    private synchronized void start(MinecraftServer server) {
        if (channel != null) return;
        minecraft = server;
        updateSnapshot(server);
        int generation = parsePositive(System.getenv("MINOSOFT_DEBUG_GENERATION"), 1);
        String trajectory = text(System.getenv("MINOSOFT_TRAJECTORY"), "default");
        DebugChannelServer created = new DebugChannelServer(DebugPaths.system(), DebugEndpointRole.SERVER, trajectory, generation);
        created.setStatusSupplier(this::status);
        created.setMetricsSupplier(this::metrics);
        register(created);
        try {
            created.start();
            channel = created;
            System.out.println("MINOSOFT_DEBUG_READY role=server endpoint=" + created.endpoint().getId());
        } catch (IOException error) {
            try {
                created.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw new IllegalStateException("Could not start Minosoft server debug channel", error);
        }
    }

    private void register(DebugChannelServer server) {
        server.operations().register("fabric-server", "state.sample", (context, body) -> onServer(() -> sampleState(body)));
        server.operations().register("fabric-server", "world.blocks.sample", (context, body) -> onServer(() -> sampleBlocks(body)));
        server.operations().register("fabric-server", "world.aoi", (context, body) -> onServer(() -> sampleBlocks(body)));
        server.operations().register("fabric-server", "world.teleport-player", (context, body) -> onServer(() -> teleportPlayer(body)));
        server.operations().register("fabric-server", "mods.debug", (context, body) -> onServer(this::mods));
    }

    private JsonNode status() {
        ServerSnapshot current = snapshot;
        return DebugJson.MAPPER.createObjectNode()
            .put("ready", current.ready)
            .put("ticks", current.ticks)
            .put("playerCount", current.playerCount)
            .put("worldCount", current.worldCount)
            .put("version", current.version);
    }

    private JsonNode metrics() {
        ServerSnapshot current = snapshot;
        return DebugJson.MAPPER.createObjectNode()
            .put("ready", current.ready)
            .put("ticks", current.ticks)
            .put("players", current.playerCount)
            .put("worlds", current.worldCount)
            .put("distantLodRequests", DistantHorizonsLodServer.requestCount())
            .put("distantLodTiles", DistantHorizonsLodServer.tileCount())
            .put("distantLodQueued", DistantHorizonsLodServer.queuedTiles());
    }

    private DebugOperationResult sampleState(JsonNode body) {
        MinecraftServer server = requireServer();
        String view = body.path("view").asText("server.summary");
        if (!view.equals("server.summary") && !view.equals("server.players") && !view.equals("server.world")) {
            throw new DebugOperationException("invalid_request", "unknown server state view: " + view);
        }
        ObjectNode result = (ObjectNode) status();
        result.put("view", view);
        ArrayNode players = result.putArray("players");
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            players.addObject()
                .put("uuid", player.getUuidAsString())
                .put("name", player.getGameProfile().getName())
                .put("dimension", player.getServerWorld().getRegistryKey().getValue().toString())
                .put("x", player.getX()).put("y", player.getY()).put("z", player.getZ());
        }
        ArrayNode worlds = result.putArray("worlds");
        for (ServerWorld world : server.getWorlds()) {
            worlds.addObject()
                .put("dimension", world.getRegistryKey().getValue().toString())
                .put("time", world.getTimeOfDay())
                .put("players", world.getPlayers().size());
        }
        return DebugOperationResult.json(result);
    }

    private DebugOperationResult sampleBlocks(JsonNode body) {
        ServerWorld world = selectWorld(requireServer(), body.path("dimension").asText(null));
        BlockPos min = position(body.path("min"));
        BlockPos max = position(body.path("max"));
        long sx = (long) max.getX() - min.getX() + 1;
        long sy = (long) max.getY() - min.getY() + 1;
        long sz = (long) max.getZ() - min.getZ() + 1;
        int volume = checkedBlockVolume(sx, sy, sz);
        Map<String, Integer> palette = new LinkedHashMap<>();
        List<Integer> indices = new ArrayList<>(volume);
        int notLoaded = 0;
        for (long y = min.getY(); y <= max.getY(); y++) {
            for (long z = min.getZ(); z <= max.getZ(); z++) {
                for (long x = min.getX(); x <= max.getX(); x++) {
                    BlockPos position = new BlockPos((int) x, (int) y, (int) z);
                    String stateName;
                    ChunkPos chunk = new ChunkPos(position);
                    if (!world.getChunkManager().isChunkLoaded(chunk.x, chunk.z)) {
                        stateName = "minosoft:not_loaded";
                        notLoaded++;
                    } else {
                        BlockState state = world.getBlockState(position);
                        stateName = Registries.BLOCK.getId(state.getBlock()) + state.getEntries().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(Object::toString)))
                            .map(entry -> entry.getKey().getName() + "=" + entry.getValue())
                            .collect(java.util.stream.Collectors.joining(",", state.getEntries().isEmpty() ? "" : "[", state.getEntries().isEmpty() ? "" : "]"));
                    }
                    Integer index = palette.get(stateName);
                    if (index == null) {
                        index = palette.size();
                        palette.put(stateName, index);
                    }
                    indices.add(index);
                }
            }
        }
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.set("min", positionJson(min)); result.set("max", positionJson(max));
        result.put("dimension", world.getRegistryKey().getValue().toString());
        result.put("order", "y,z,x"); result.put("volume", volume); result.put("notLoaded", notLoaded);
        ArrayNode paletteNode = result.putArray("palette");
        palette.keySet().forEach(paletteNode::add);
        ArrayNode runs = result.putArray("runs");
        for (int index = 0; index < indices.size();) {
            int value = indices.get(index);
            int count = 1;
            while (index + count < indices.size() && indices.get(index + count) == value) count++;
            runs.addArray().add(value).add(count);
            index += count;
        }
        return DebugOperationResult.json(result);
    }

    private DebugOperationResult teleportPlayer(JsonNode body) {
        MinecraftServer server = requireServer();
        ServerPlayerEntity player = selectPlayer(server, optionalText(body, "player"));
        String dimension = requiredText(body, "dimension");
        ServerWorld target = selectWorld(server, dimension);
        JsonNode position = body.path("position");
        if (!position.isObject()) {
            throw new DebugOperationException("invalid_request", "position must be an object");
        }
        double x = finiteCoordinate(position, "x");
        double y = finiteCoordinate(position, "y");
        double z = finiteCoordinate(position, "z");
        float yaw = finiteAngle(body, "yaw", player.getYaw());
        float pitch = finiteAngle(body, "pitch", player.getPitch());

        ObjectNode previous = playerPosition(player);
        player.teleport(target, x, y, z, yaw, pitch);

        ObjectNode result = DebugJson.MAPPER.createObjectNode()
            .put("player", player.getGameProfile().getName());
        result.set("previous", previous);
        result.set("current", playerPosition(player));
        return DebugOperationResult.json(result);
    }

    private static ServerPlayerEntity selectPlayer(MinecraftServer server, String selector) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (selector == null) {
            if (players.size() != 1) {
                throw new DebugOperationException(
                    "invalid_request",
                    "player is required unless exactly one player is connected"
                );
            }
            return players.get(0);
        }
        for (ServerPlayerEntity player : players) {
            if (player.getGameProfile().getName().equals(selector) || player.getUuidAsString().equals(selector)) {
                return player;
            }
        }
        throw new DebugOperationException("not_found", "connected player was not found: " + selector);
    }

    static String requiredText(JsonNode body, String field) {
        String value = optionalText(body, field);
        if (value == null) {
            throw new DebugOperationException("invalid_request", field + " must be a non-empty string");
        }
        return value;
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new DebugOperationException("invalid_request", field + " must be a non-empty string");
        }
        return node.textValue();
    }

    static double finiteCoordinate(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isNumber()) {
            throw new DebugOperationException("invalid_request", "position." + field + " must be numeric");
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || Math.abs(value) > MAX_TELEPORT_COORDINATE) {
            throw new DebugOperationException(
                "limit_exceeded",
                "position." + field + " must be finite and within +/-" + (long) MAX_TELEPORT_COORDINATE
            );
        }
        return value;
    }

    static float finiteAngle(JsonNode body, String field, float fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return fallback;
        if (!node.isNumber()) {
            throw new DebugOperationException("invalid_request", field + " must be numeric");
        }
        float value = node.floatValue();
        if (!Float.isFinite(value)) {
            throw new DebugOperationException("invalid_request", field + " must be finite");
        }
        return value;
    }

    private static ObjectNode playerPosition(ServerPlayerEntity player) {
        return DebugJson.MAPPER.createObjectNode()
            .put("dimension", player.getServerWorld().getRegistryKey().getValue().toString())
            .put("x", player.getX())
            .put("y", player.getY())
            .put("z", player.getZ())
            .put("yaw", player.getYaw())
            .put("pitch", player.getPitch());
    }

    private DebugOperationResult mods() {
        ObjectNode result = DebugJson.MAPPER.createObjectNode().put("active", true);
        ArrayNode mods = result.putArray("mods");
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            mods.addObject()
                .put("id", container.getMetadata().getId())
                .put("name", container.getMetadata().getName())
                .put("version", container.getMetadata().getVersion().getFriendlyString())
                .put("environment", container.getMetadata().getEnvironment().toString());
        }
        return DebugOperationResult.json(result);
    }

    private CompletionStage<DebugOperationResult> onServer(Supplier<DebugOperationResult> work) {
        MinecraftServer server = requireServer();
        CompletableFuture<DebugOperationResult> future = new CompletableFuture<>();
        server.execute(() -> {
            if (future.isDone()) return;
            try {
                future.complete(work.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private ServerWorld selectWorld(MinecraftServer server, String dimension) {
        if (dimension == null || dimension.isBlank()) return server.getOverworld();
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(dimension)) return world;
        }
        throw new DebugOperationException("not_loaded", "server world is not loaded: " + dimension);
    }

    private static BlockPos position(JsonNode node) {
        if (!node.isObject() || !node.path("x").isIntegralNumber() || !node.path("x").canConvertToInt()
            || !node.path("y").isIntegralNumber() || !node.path("y").canConvertToInt()
            || !node.path("z").isIntegralNumber() || !node.path("z").canConvertToInt()) {
            throw new DebugOperationException("invalid_request", "min and max block coordinates are required");
        }
        return new BlockPos(node.path("x").intValue(), node.path("y").intValue(), node.path("z").intValue());
    }

    private static int checkedBlockVolume(long sx, long sy, long sz) {
        if (sx < 1 || sx > MAX_BLOCKS || sy < 1 || sy > MAX_BLOCKS || sz < 1 || sz > MAX_BLOCKS) {
            throw new DebugOperationException("limit_exceeded", "block sample must contain 1.." + MAX_BLOCKS + " blocks");
        }
        long area = sx * sy;
        if (area > MAX_BLOCKS || sz > MAX_BLOCKS / area) {
            throw new DebugOperationException("limit_exceeded", "block sample must contain 1.." + MAX_BLOCKS + " blocks");
        }
        return (int) (area * sz);
    }

    private static ObjectNode positionJson(BlockPos position) {
        return DebugJson.MAPPER.createObjectNode().put("x", position.getX()).put("y", position.getY()).put("z", position.getZ());
    }

    private MinecraftServer requireServer() {
        MinecraftServer server = minecraft;
        if (server == null || !server.isRunning()) throw new DebugOperationException("not_ready", "Minecraft server is not running");
        return server;
    }

    private void updateSnapshot(MinecraftServer server) {
        snapshot = new ServerSnapshot(true, server.getTicks(), server.getPlayerManager().getCurrentPlayerCount(),
            size(server.getWorlds()), server.getVersion());
    }

    private static int size(Iterable<?> values) {
        int size = 0;
        for (Object ignored : values) size++;
        return size;
    }

    private synchronized void close() {
        minecraft = null;
        snapshot = ServerSnapshot.empty();
        DebugChannelServer current = channel;
        channel = null;
        if (current == null) return;
        try {
            current.close();
        } catch (IOException error) {
            error.printStackTrace(System.err);
        }
    }

    private static boolean enabled() {
        String value = System.getenv("MINOSOFT_DEBUG");
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("on"));
    }

    private static int parsePositive(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ServerSnapshot(boolean ready, int ticks, int playerCount, int worldCount, String version) {
        private static ServerSnapshot empty() { return new ServerSnapshot(false, 0, 0, 0, "1.20.4"); }
    }
}
