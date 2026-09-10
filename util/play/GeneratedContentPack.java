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
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds a deterministic resource-pack scaffold from cumulative live content audits. */
final class GeneratedContentPack {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GENERATOR_VERSION = "3";
    private static final int MAX_AUDITS = 64;
    private static final long MAX_AUDIT_BYTES = 32L * 1024 * 1024;
    private static final int MAX_TARGETS = 50_000;
    private static final Set<String> KINDS = Set.of("blockstates", "models", "textures");
    private static final Map<String, String> IMAGE_GENERATION_REFERENCES = Map.of(
        "water_overlay", "7565ef547e23f5b7cfb110f4e0591facf2f659fd04d7abb29a7179398f79236d",
        "enchanted_glint_entity", "dda71847adb8380b07af1df3f5b4e54d0baca239012ad3b97b483f8d92af5e2a",
        "shadow", "06c54bedbd47dfb554e976a565d7e62d2aed8ad6df96b2691eb684f7eecf6b6e",
        "vignette", "9d91912ee74ef53a3c90e3ef37ee6f7f818aee634ffb8c86263ac17da2640a4a"
    );
    private static final Set<String> WOODS = Set.of(
        "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak", "jungle",
        "mangrove", "oak", "spruce", "warped"
    );

    record Result(
        Path resourcePack,
        String fingerprint,
        int audits,
        int generatedBlockstates,
        int generatedModels,
        int generatedTextures
    ) {}

    record Targets(TreeMap<String, TreeSet<String>> byKind, int audits) {
        int size() {
            return byKind.values().stream().mapToInt(Set::size).sum();
        }
    }

    private GeneratedContentPack() {}

    static Result prepare(Path auditSource, Path storeRoot) throws IOException {
        Targets targets = readTargets(auditSource);
        String fingerprint = fingerprint(targets);
        Path parent = storeRoot.toAbsolutePath().normalize().resolve("content-providers/generated-compatibility");
        Path output = parent.resolve(fingerprint);
        Path marker = output.resolve("provenance.json");
        if (Files.isRegularFile(marker)) return result(output, fingerprint, targets);

        Files.createDirectories(parent);
        Path candidate = parent.resolve("." + fingerprint + ".candidate-" + ProcessHandle.current().pid());
        deleteTree(candidate);
        try {
            Files.createDirectories(candidate);
            writePackMetadata(candidate);
            writeGeneratedTextures(candidate, targets);
            writeAuditedScaffolding(candidate, targets);
            writeProvenance(candidate, fingerprint, auditSource, targets);
            publish(candidate, output, marker);
        } catch (Throwable error) {
            deleteTree(candidate);
            throw error;
        }
        require(Files.isRegularFile(marker), "Generated compatibility pack did not publish its provenance marker: " + marker);
        return result(output, fingerprint, targets);
    }

    private static void publish(Path candidate, Path output, Path marker) throws IOException {
        try {
            Files.move(candidate, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            publishNonAtomic(candidate, output, marker);
        } catch (IOException collision) {
            if (!Files.isRegularFile(marker)) throw collision;
            deleteTree(candidate);
        }
    }

    private static void publishNonAtomic(Path candidate, Path output, Path marker) throws IOException {
        try {
            Files.move(candidate, output);
        } catch (IOException collision) {
            if (!Files.isRegularFile(marker)) throw collision;
            deleteTree(candidate);
        }
    }

    private static Result result(Path output, String fingerprint, Targets targets) {
        return new Result(
            output,
            fingerprint,
            targets.audits,
            targets.byKind.get("blockstates").size(),
            targets.byKind.get("models").size(),
            generatedTextureCount(targets)
        );
    }

    private static int generatedTextureCount(Targets targets) {
        int authored = AUTHORED_TEXTURE_TARGETS.size();
        int audited = 0;
        for (String target : targets.byKind.get("textures")) {
            if (!AUTHORED_TEXTURE_TARGETS.contains(target)) audited++;
        }
        return authored + audited;
    }

    static Targets readTargets(Path auditSource) throws IOException {
        Map<String, TreeSet<String>> byKind = new TreeMap<>();
        for (String kind : KINDS) byKind.put(kind, new TreeSet<>());
        if (auditSource == null || !Files.exists(auditSource)) return new Targets(copyByKind(byKind), 0);

        List<Path> audits;
        if (Files.isRegularFile(auditSource)) {
            audits = List.of(auditSource);
        } else {
            require(Files.isDirectory(auditSource), "Generated content audit source is neither a file nor directory: " + auditSource);
            try (var entries = Files.list(auditSource)) {
                audits = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            }
        }
        require(audits.size() <= MAX_AUDITS, "Generated content source exceeds " + MAX_AUDITS + " audit files: " + auditSource);
        for (Path audit : audits) {
            require(Files.size(audit) <= MAX_AUDIT_BYTES, "Content audit exceeds the 32 MiB parser limit: " + audit);
            JsonNode root = JSON.readTree(audit.toFile());
            require(root.isObject() && root.path("schema").asInt(-1) == 1, "Content audit requires schema 1: " + audit);
            JsonNode missing = root.path("missing");
            require(missing.isObject(), "Content audit is missing its resource inventory: " + audit);
            for (String kind : KINDS) {
                JsonNode entries = missing.path(kind);
                require(entries.isArray(), "Content audit is missing array '" + kind + "': " + audit);
                for (JsonNode entry : entries) {
                    String target = entry.path("target").asText("").trim().replace('\\', '/');
                    require(isSafeTarget(kind, target), "Content audit has an unsafe " + kind + " target: " + target);
                    byKind.get(kind).add(target);
                    require(byKind.values().stream().mapToInt(Set::size).sum() <= MAX_TARGETS,
                        "Cumulative content audits exceed " + MAX_TARGETS + " targets.");
                }
            }
        }
        return new Targets(copyByKind(byKind), audits.size());
    }

    private static TreeMap<String, TreeSet<String>> copyByKind(Map<String, TreeSet<String>> source) {
        TreeMap<String, TreeSet<String>> copy = new TreeMap<>();
        for (String kind : source.keySet()) copy.put(kind, new TreeSet<>(source.get(kind)));
        return copy;
    }

    private static boolean isSafeTarget(String kind, String target) {
        if (!target.matches("assets/[a-z0-9_.-]+/[a-z0-9/._-]+")) return false;
        if (target.split("/").length < 4 || target.contains("/../") || target.endsWith("/..")) return false;
        return switch (kind) {
            case "blockstates" -> target.contains("/blockstates/") && target.endsWith(".json");
            case "models" -> target.contains("/models/") && target.endsWith(".json");
            case "textures" -> target.contains("/textures/") && target.endsWith(".png");
            default -> false;
        };
    }

    private static String fingerprint(Targets targets) {
        MessageDigest digest = digest();
        digest.update(GENERATOR_VERSION.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (Map.Entry<String, TreeSet<String>> group : targets.byKind.entrySet()) {
            digest.update(group.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (String target : group.getValue()) {
                digest.update(target.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        }
        IMAGE_GENERATION_REFERENCES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void writePackMetadata(Path root) throws IOException {
        Files.writeString(
            root.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":22,\"description\":\"Minosoft audit-generated standalone compatibility scaffolding\"}}\n",
            StandardCharsets.UTF_8
        );
    }

    private static void writeAuditedScaffolding(Path root, Targets targets) throws IOException {
        Set<String> blockIdentifiers = new TreeSet<>();
        for (String target : targets.byKind.get("blockstates")) {
            ResourceTarget resource = resourceTarget(target, "blockstates");
            blockIdentifiers.add(resource.namespace + ":" + resource.path);
            Path file = contained(root, Path.of(target));
            Files.createDirectories(file.getParent());
            Files.writeString(
                file,
                "{\"variants\":{\"\":{\"model\":\"" + resource.namespace + ":block/" + resource.path + "\"}}}\n",
                StandardCharsets.UTF_8
            );
        }
        for (String target : targets.byKind.get("models")) {
            if (target.contains("/models/item/")) {
                ResourceTarget resource = resourceTarget(target, "models/item");
                writeItemModel(root, target, resource, blockIdentifiers.contains(resource.namespace + ":" + resource.path));
            } else if (target.contains("/models/block/")) {
                ResourceTarget resource = resourceTarget(target, "models/block");
                writeBlockModel(root, target, resource);
            }
        }
    }

    private record ResourceTarget(String namespace, String path) {}

    private static ResourceTarget resourceTarget(String target, String directory) {
        String[] segments = target.split("/", 4);
        String namespace = segments[1];
        String marker = "/" + directory + "/";
        int start = target.indexOf(marker);
        require(start >= 0 && target.endsWith(".json"), "Unexpected generated target: " + target);
        String path = target.substring(start + marker.length(), target.length() - ".json".length());
        return new ResourceTarget(namespace, path);
    }

    private static void writeItemModel(Path root, String target, ResourceTarget resource, boolean blockItem) throws IOException {
        Path file = contained(root, Path.of(target));
        Files.createDirectories(file.getParent());
        String json;
        if (blockItem) {
            json = "{\"parent\":\"" + resource.namespace + ":block/" + resource.path + "\"}\n";
        } else {
            json = "{\"textures\":{\"layer0\":\"" + resource.namespace + ":item/" + resource.path + "\"}}\n";
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static void writeBlockModel(Path root, String target, ResourceTarget resource) throws IOException {
        Path file = contained(root, Path.of(target));
        Files.createDirectories(file.getParent());
        String id = resource.path.substring(resource.path.lastIndexOf('/') + 1);
        String texture = resource.namespace + ":block/" + inferBlockTexture(id);
        String json = switch (shape(id)) {
            case EMPTY -> "{}\n";
            case CROSS -> crossModel(texture);
            case SLAB -> boxModel(texture, 0, 0, 0, 16, 8, 16, false);
            case CARPET -> boxModel(texture, 0, 0, 0, 16, 1, 16, false);
            case THIN -> boxModel(texture, 0, 0, 7, 16, 16, 9, false);
            case STAIRS -> stairModel(texture);
            case CUBE -> boxModel(texture, 0, 0, 0, 16, 16, 16, true);
        };
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    enum Shape { EMPTY, CROSS, SLAB, CARPET, THIN, STAIRS, CUBE }

    static Shape shape(String id) {
        if (id.equals("air") || id.endsWith("_air")) return Shape.EMPTY;
        if (id.contains("stairs")) return Shape.STAIRS;
        if (id.contains("slab")) return Shape.SLAB;
        if (id.endsWith("_carpet") || id.equals("moss_carpet")) return Shape.CARPET;
        if (id.contains("door") || id.contains("trapdoor") || id.contains("pane") || id.contains("rail")) return Shape.THIN;
        if (isCross(id)) return Shape.CROSS;
        return Shape.CUBE;
    }

    private static boolean isCross(String id) {
        return id.contains("sapling") || id.contains("flower") || id.contains("mushroom") || id.contains("fungus")
            || id.contains("roots") || id.contains("grass") || id.contains("fern") || id.contains("coral_fan")
            || id.equals("torch") || id.endsWith("_torch") || id.contains("seagrass") || id.contains("crop")
            || id.startsWith("potted_") || id.equals("bamboo") || id.equals("sugar_cane") || id.equals("dead_bush")
            || id.equals("cobweb") || id.equals("lily_of_the_valley") || id.equals("allium") || id.equals("dandelion")
            || id.equals("poppy") || id.equals("azure_bluet") || id.equals("blue_orchid") || id.equals("cornflower");
    }

    private static String inferBlockTexture(String model) {
        String id = model
            .replaceAll("_(inner|outer|top|bottom|inventory|open|pressed|raised|on|off|side|post|tall)$", "")
            .replaceAll("_(north|south|east|west|ne|nw|se|sw)$", "");
        for (String wood : WOODS) {
            if (!id.startsWith(wood + "_")) continue;
            if (id.matches(".*_(stairs|slab|button|pressure_plate|fence|fence_gate)$")) return wood + "_planks";
        }
        if (id.endsWith("_stairs")) id = id.substring(0, id.length() - "_stairs".length());
        if (id.endsWith("_slab")) id = id.substring(0, id.length() - "_slab".length());
        if (id.endsWith("_wall")) id = id.substring(0, id.length() - "_wall".length());
        if (id.endsWith("_carpet")) return id.substring(0, id.length() - "_carpet".length()) + "_wool";
        return switch (id) {
            case "brick" -> "bricks";
            case "end_stone_brick" -> "end_stone_bricks";
            case "nether_brick" -> "nether_bricks";
            case "polished_blackstone_brick" -> "polished_blackstone_bricks";
            case "quartz" -> "quartz_block_side";
            case "red_nether_brick" -> "red_nether_bricks";
            case "stone_brick" -> "stone_bricks";
            default -> id;
        };
    }

    private static String crossModel(String texture) {
        return "{\"ambientocclusion\":false,\"textures\":{\"cross\":\"" + texture + "\"},\"elements\":["
            + crossElement("[0,0,0]", "[16,16,16]", "north", "south") + ","
            + crossElement("[0,0,16]", "[16,16,0]", "north", "south") + "]}\n";
    }

    private static String crossElement(String from, String to, String first, String second) {
        return "{\"from\":" + from + ",\"to\":" + to + ",\"rotation\":{\"origin\":[8,8,8],\"axis\":\"y\",\"angle\":45},"
            + "\"shade\":false,\"faces\":{\"" + first + "\":{\"texture\":\"#cross\"},\"" + second + "\":{\"texture\":\"#cross\"}}}";
    }

    private static String stairModel(String texture) {
        String lower = boxElement(0, 0, 0, 16, 8, 16, false);
        String upper = boxElement(0, 8, 8, 16, 16, 16, false);
        return "{\"textures\":{\"all\":\"" + texture + "\"},\"elements\":[" + lower + "," + upper + "]}\n";
    }

    private static String boxModel(String texture, int x1, int y1, int z1, int x2, int y2, int z2, boolean cull) {
        return "{\"textures\":{\"all\":\"" + texture + "\"},\"elements\":[" + boxElement(x1, y1, z1, x2, y2, z2, cull) + "]}\n";
    }

    private static String boxElement(int x1, int y1, int z1, int x2, int y2, int z2, boolean cull) {
        StringBuilder faces = new StringBuilder();
        for (String face : List.of("down", "up", "north", "south", "west", "east")) {
            if (!faces.isEmpty()) faces.append(',');
            faces.append('"').append(face).append("\":{\"texture\":\"#all\"");
            if (cull) faces.append(",\"cullface\":\"").append(face).append('"');
            faces.append('}');
        }
        return "{\"from\":[" + x1 + ',' + y1 + ',' + z1 + "],\"to\":[" + x2 + ',' + y2 + ',' + z2 + "],\"faces\":{" + faces + "}}";
    }

    private static void writeGeneratedTextures(Path root, Targets targets) throws IOException {
        writeTexture(root, "assets/minecraft/textures/block/water_overlay.png", waterOverlay(32));
        writeTexture(root, "assets/minecraft/textures/misc/enchanted_glint_entity.png", enchantedGlint(64));
        writeTexture(root, "assets/minecraft/textures/misc/shadow.png", shadowMask(64, 32));
        writeTexture(root, "assets/minecraft/textures/misc/vignette.png", vignetteMask(256));
        writeTexture(root, "assets/minecraft/textures/etf_nose.png", new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB));
        for (String target : targets.byKind.get("textures")) {
            if (AUTHORED_TEXTURE_TARGETS.contains(target)) continue;
            writeTexture(root, target, GeneratedTextureLibrary.generate(target));
        }
    }

    private static final Set<String> AUTHORED_TEXTURE_TARGETS = Set.of(
        "assets/minecraft/textures/block/water_overlay.png",
        "assets/minecraft/textures/misc/enchanted_glint_entity.png",
        "assets/minecraft/textures/misc/shadow.png",
        "assets/minecraft/textures/misc/vignette.png",
        "assets/minecraft/textures/etf_nose.png"
    );

    private static BufferedImage waterOverlay(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double first = Math.sin((x + y * 2.0) * Math.PI / 8.0);
            double second = Math.cos((x * 2.0 - y) * Math.PI / 16.0);
            double caustic = Math.pow(Math.max(0.0, (first + second) * 0.5), 3.0);
            int red = clamp(15 + (int) Math.round(caustic * 40));
            int green = clamp(90 + (int) Math.round(caustic * 80));
            int blue = clamp(125 + (int) Math.round(caustic * 90));
            int alpha = clamp(64 + (int) Math.round(caustic * 40));
            image.setRGB(x, y, argb(alpha, red, green, blue));
        }
        return image;
    }

    private static BufferedImage enchantedGlint(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int diagonal = Math.floorMod(x + y * 2, 16);
            int secondary = Math.floorMod(x * 3 - y, 32);
            int intensity = diagonal <= 1 ? 190 : (diagonal == 2 ? 92 : 0);
            if (secondary == 0 && Math.floorMod(x + y, 8) == 0) intensity = 230;
            int red = clamp(90 + Math.floorMod(x * 5 + y * 3, 96));
            int green = clamp(36 + Math.floorMod(x * 2 + y * 7, 96));
            int blue = clamp(180 + Math.floorMod(x * 3 + y * 5, 76));
            image.setRGB(x, y, argb(intensity, red, green, blue));
        }
        return image;
    }

    private static BufferedImage shadowMask(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double nx = (x + 0.5 - width / 2.0) / (width * 0.36);
            double ny = (y + 0.5 - height / 2.0) / (height * 0.34);
            double radius = nx * nx + ny * ny;
            int alpha = clamp((int) Math.round(180.0 * Math.exp(-3.4 * radius)));
            if (radius >= 1.0) alpha = 0;
            image.setRGB(x, y, argb(alpha, 255, 255, 255));
        }
        return image;
    }

    private static BufferedImage vignetteMask(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double nx = Math.abs((x + 0.5 - size / 2.0) / (size / 2.0));
            double ny = Math.abs((y + 0.5 - size / 2.0) / (size / 2.0));
            double radius = Math.pow(Math.pow(nx, 4.0) + Math.pow(ny, 4.0), 0.25);
            double opacity = smoothStep(0.62, 1.0, radius);
            int alpha = clamp((int) Math.round(opacity * 224.0));
            image.setRGB(x, y, argb(alpha, 255, 255, 255));
        }
        return image;
    }

    private static double smoothStep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void writeTexture(Path root, String target, BufferedImage image) throws IOException {
        Path file = contained(root, Path.of(target));
        Files.createDirectories(file.getParent());
        require(ImageIO.write(image, "png", file.toFile()), "Java runtime could not encode generated texture: " + file);
    }

    private static void writeProvenance(Path root, String fingerprint, Path auditSource, Targets targets) throws IOException {
        ObjectNode provenance = JSON.createObjectNode();
        provenance.put("provider", "minosoft-generated-compatibility");
        provenance.put("generator_version", GENERATOR_VERSION);
        provenance.put("fingerprint", fingerprint);
        provenance.put("audit_source_kind", auditSource == null ? "none" : Files.isDirectory(auditSource) ? "directory" : "file");
        provenance.put("audits", targets.audits);
        provenance.put("generated_blockstates", targets.byKind.get("blockstates").size());
        provenance.put("generated_models", targets.byKind.get("models").size());
        provenance.put("generated_textures", generatedTextureCount(targets));
        provenance.put("generation_policy", "Cumulative missing targets produce visible compatibility scaffolding; higher-priority authored packs replace it.");
        ObjectNode references = provenance.putObject("image_generation_reference_sha256");
        IMAGE_GENERATION_REFERENCES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> references.put(entry.getKey(), entry.getValue()));
        ArrayNode paths = provenance.putArray("generated_texture_paths");
        AUTHORED_TEXTURE_TARGETS.stream().sorted().forEach(paths::add);
        targets.byKind.get("textures").stream()
            .filter(target -> !AUTHORED_TEXTURE_TARGETS.contains(target))
            .forEach(paths::add);
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("provenance.json").toFile(), provenance);
    }

    private static Path contained(Path root, Path relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        require(target.startsWith(normalizedRoot), "Generated content target escapes its resource-pack root: " + relative);
        return target;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
                return;
            } catch (DirectoryNotEmptyException raced) {
                last = raced;
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while retrying content tree cleanup: " + root, interrupted);
                }
            }
        }
        throw last;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
