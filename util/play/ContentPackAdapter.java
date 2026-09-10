/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a non-source-controlled Minecraft resource-pack view over VoxeLibre media. */
final class ContentPackAdapter {
    private static final String ADAPTER_VERSION = "10";
    private static final int MAX_TABLE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MAPPINGS = 10_000;
    private static final int MAX_IMAGE_DIMENSION = 8_192;
    private static final long MAX_IMAGE_PIXELS = 64L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> DOJO_TEXTURES = Map.of(
        "stone", "stone",
        "grass_block", "grass_block_top",
        "stone_brick_stairs", "stone_bricks",
        "farmland", "farmland",
        "torch", "torch",
        "glowstone", "glowstone",
        "sea_lantern", "sea_lantern",
        "oak_slab", "oak_planks"
    );

    record Result(Path resourcePack, String fingerprint, int mappedTextures) {}

    private record TexturePart(Path source, int sourceX, int sourceY, int targetX, int targetY, int width, int height) {
        static TexturePart whole(Path source) {
            return new TexturePart(source, 0, 0, 0, 0, -1, -1);
        }

        boolean whole() {
            return width < 0;
        }
    }

    private ContentPackAdapter() {}

    static Result prepareVoxeLibre(Path sourceRoot, Path storeRoot) throws IOException {
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        storeRoot = storeRoot.toAbsolutePath().normalize();
        Path textures = sourceRoot.resolve("textures");
        Path table = sourceRoot.resolve("tools/Conversion_Table.csv");
        Path legal = sourceRoot.resolve("LEGAL.md");
        require(Files.isDirectory(textures), "VoxeLibre texture directory is missing: " + textures);
        require(Files.isRegularFile(table), "VoxeLibre conversion table is missing: " + table);
        require(Files.size(table) <= MAX_TABLE_BYTES, "VoxeLibre conversion table exceeds the bounded parser limit: " + table);
        require(Files.isRegularFile(legal), "VoxeLibre media license inventory is missing: " + legal);

        Map<Path, List<TexturePart>> mappings = readMappings(table, textures);
        require(!mappings.isEmpty(), "VoxeLibre conversion table did not yield any safe texture mappings.");
        Path parent = storeRoot.resolve("content-providers/voxelibre");
        Files.createDirectories(parent);
        Path candidate = parent.resolve(".candidate-" + ProcessHandle.current().pid());
        deleteTree(candidate);
        try {
            Files.createDirectories(candidate);
            for (Map.Entry<Path, List<TexturePart>> mapping : mappings.entrySet()) {
                Path target = contained(candidate, mapping.getKey(), "Unsafe generated resource path");
                writeMappedTexture(mapping.getValue(), target);
            }
            writeGeneratedSupport(candidate, textures);
            Files.copy(legal, candidate.resolve("LEGAL.md"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            String fingerprint = fingerprint(candidate);
            Path output = parent.resolve(fingerprint);
            Path marker = output.resolve("provenance.json");
            if (Files.isRegularFile(marker)) {
                deleteTree(candidate);
                return new Result(output, fingerprint, mappings.size());
            }
            writeProvenance(candidate, sourceRoot, table, legal, fingerprint, mappings.size());
            publish(candidate, output, marker);
            require(Files.isRegularFile(marker), "VoxeLibre adapter did not publish its provenance marker: " + marker);
            return new Result(output, fingerprint, mappings.size());
        } catch (Throwable error) {
            deleteTree(candidate);
            throw error;
        }
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

    private static Map<Path, List<TexturePart>> readMappings(Path table, Path textures) throws IOException {
        List<String> lines = Files.readAllLines(table, StandardCharsets.UTF_8);
        require(!lines.isEmpty(), "VoxeLibre conversion table is empty: " + table);
        Map<Path, List<TexturePart>> mappings = new LinkedHashMap<>();
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            List<String> fields = csv(lines.get(lineNumber - 1));
            if (fields.size() < 10) continue;
            String sourcePath = fields.get(0).trim();
            String sourceFile = fields.get(1).trim();
            String targetFile = fields.get(2).trim();
            boolean sliced = fields.subList(3, 9).stream().anyMatch(value -> !value.isBlank());
            boolean blacklisted = fields.get(9).trim().equalsIgnoreCase("y");
            if (blacklisted || !sourcePath.startsWith("/assets/") || sourceFile.isBlank() || targetFile.isBlank()) continue;
            Path relative;
            try {
                relative = Path.of(sourcePath.substring(1)).resolve(sourceFile).normalize();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (relative.isAbsolute() || relative.startsWith("..") || !relative.toString().replace('\\', '/').startsWith("assets/")) continue;
            Path source;
            try {
                source = contained(textures, Path.of(targetFile), "Unsafe VoxeLibre texture mapping at line " + lineNumber);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (!Files.isRegularFile(source)) continue;
            if (!sliced) {
                mappings.putIfAbsent(relative, new ArrayList<>(List.of(TexturePart.whole(source))));
            } else {
                int[] coordinates = parseSlice(fields, lineNumber);
                if (coordinates == null) continue;
                TexturePart part = new TexturePart(
                    source,
                    coordinates[4], coordinates[5],
                    coordinates[0], coordinates[1],
                    coordinates[2], coordinates[3]
                );
                List<TexturePart> parts = mappings.computeIfAbsent(relative, ignored -> new ArrayList<>());
                if (parts.stream().noneMatch(TexturePart::whole)) parts.add(part);
            }
            require(mappings.size() <= MAX_MAPPINGS, "VoxeLibre conversion table exceeds " + MAX_MAPPINGS + " safe mappings.");
        }
        return mappings;
    }

    private static int[] parseSlice(List<String> fields, int lineNumber) {
        int[] values = new int[6];
        try {
            for (int index = 0; index < values.length; index++) values[index] = Integer.parseInt(fields.get(index + 3).trim());
        } catch (NumberFormatException error) {
            return null;
        }
        if (values[0] < 0 || values[1] < 0 || values[2] <= 0 || values[3] <= 0 || values[4] < 0 || values[5] < 0) return null;
        return values;
    }

    private static void writeMappedTexture(List<TexturePart> parts, Path target) throws IOException {
        require(!parts.isEmpty(), "Texture mapping has no source parts: " + target);
        if (parts.size() == 1 && parts.get(0).whole()) {
            copy(parts.get(0).source(), target);
            return;
        }
        int width = parts.stream().mapToInt(part -> Math.addExact(part.targetX(), part.width())).max().orElseThrow();
        int height = parts.stream().mapToInt(part -> Math.addExact(part.targetY(), part.height())).max().orElseThrow();
        requireImageBounds(width, height, "Mapped VoxeLibre texture is too large: " + target);
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            for (TexturePart part : parts) {
                require(!part.whole(), "Cannot combine a whole-file and sliced texture mapping: " + target);
                BufferedImage source = ImageIO.read(part.source().toFile());
                require(source != null, "VoxeLibre texture is not a readable PNG: " + part.source());
                requireImageBounds(source.getWidth(), source.getHeight(), "VoxeLibre source texture is too large: " + part.source());
                require((long) part.sourceX() + part.width() <= source.getWidth() && (long) part.sourceY() + part.height() <= source.getHeight(),
                    "VoxeLibre texture slice exceeds its source image: " + part.source());
                graphics.drawImage(
                    source,
                    part.targetX(), part.targetY(), part.targetX() + part.width(), part.targetY() + part.height(),
                    part.sourceX(), part.sourceY(), part.sourceX() + part.width(), part.sourceY() + part.height(),
                    null
                );
            }
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(target.getParent());
        require(ImageIO.write(output, "png", target.toFile()), "No PNG encoder is available for " + target);
    }

    private static List<String> csv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(value);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static String fingerprint(Path root) throws IOException {
        MessageDigest digest = digest();
        digest.update(ADAPTER_VERSION.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                .toList();
        }
        for (Path file : files) {
            digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            update(digest, file);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void update(MessageDigest digest, Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
    }

    private static void writeGeneratedSupport(Path root, Path textures) throws IOException {
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":22,\"description\":\"Generated VoxeLibre adapter view for Minosoft local testing\"}}\n", StandardCharsets.UTF_8);
        writeAsciiFont(root.resolve("assets/minecraft/textures/font/ascii.png"));
        Path fontIndex = root.resolve("assets/minecraft/font/default.json");
        Files.createDirectories(fontIndex.getParent());
        ObjectNode font = JSON.createObjectNode();
        ObjectNode provider = font.putArray("providers").addObject();
        provider.put("type", "bitmap");
        provider.put("file", "minecraft:font/ascii.png");
        provider.put("ascent", 7);
        var chars = provider.putArray("chars");
        for (int row = 0; row < 16; row++) {
            StringBuilder values = new StringBuilder(16);
            for (int column = 0; column < 16; column++) {
                int codePoint = (row * 16) + column;
                values.append(codePoint >= 32 && codePoint < 127 ? (char) codePoint : '\0');
            }
            chars.add(values.toString());
        }
        Files.writeString(fontIndex, JSON.writeValueAsString(font) + "\n", StandardCharsets.UTF_8);
        writeGuiSupport(root, textures);
        for (String fluid : List.of("water_still", "water_flow", "lava_still", "lava_flow")) {
            Path texture = root.resolve("assets/minecraft/textures/block/" + fluid + ".png");
            if (Files.isRegularFile(texture)) {
                normalizePng(texture);
                Files.writeString(texture.resolveSibling(texture.getFileName() + ".mcmeta"), "{\"animation\":{\"frametime\":2}}\n", StandardCharsets.UTF_8);
            }
        }
        for (Map.Entry<String, String> entry : DOJO_TEXTURES.entrySet()) {
            writeCubeModel(root, entry.getKey(), entry.getValue());
        }
    }

    private static void writeCubeModel(Path root, String block, String texture) throws IOException {
        Path state = root.resolve("assets/minecraft/blockstates/" + block + ".json");
        Path model = root.resolve("assets/minecraft/models/block/" + block + ".json");
        Files.createDirectories(state.getParent());
        Files.createDirectories(model.getParent());
        Files.writeString(state, "{\"variants\":{\"\":{\"model\":\"minecraft:block/" + block + "\"}}}\n", StandardCharsets.UTF_8);
        String faces = "\"down\":{\"texture\":\"#all\",\"cullface\":\"down\"},"
            + "\"up\":{\"texture\":\"#all\",\"cullface\":\"up\"},"
            + "\"north\":{\"texture\":\"#all\",\"cullface\":\"north\"},"
            + "\"south\":{\"texture\":\"#all\",\"cullface\":\"south\"},"
            + "\"west\":{\"texture\":\"#all\",\"cullface\":\"west\"},"
            + "\"east\":{\"texture\":\"#all\",\"cullface\":\"east\"}";
        Files.writeString(model, "{\"textures\":{\"all\":\"minecraft:block/" + texture + "\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{" + faces + "}}]}\n", StandardCharsets.UTF_8);
    }

    private static void writeAsciiFont(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        int cell = 16;
        BufferedImage image = new BufferedImage(cell * 16, cell * 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setComposite(java.awt.AlphaComposite.Clear);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setComposite(java.awt.AlphaComposite.SrcOver);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int code = 32; code < 127; code++) {
                int x = (code % 16) * cell;
                int y = (code / 16) * cell;
                graphics.drawString(Character.toString((char) code), x + 3, y + 14);
                if (code != 32) {
                    // BitmapFontType derives advance width from occupied pixels. These almost-transparent
                    // sentinels give the fallback a stable six-pixel logical advance on every platform.
                    image.setRGB(x + 2, y, 0x01FFFFFF);
                    image.setRGB(x + 13, y, 0x01FFFFFF);
                }
            }
        } finally {
            graphics.dispose();
        }
        require(ImageIO.write(image, "png", target.toFile()), "Java runtime could not encode the generated fallback font.");
    }

    private static void writeGuiSupport(Path root, Path textures) throws IOException {
        copyTexture(textures, "mcl_inventory_hotbar.png", root, "gui/sprites/hud/hotbar.png", 182, 22);
        copyTexture(textures, "mcl_inventory_hotbar_selected.png", root, "gui/sprites/hud/hotbar_selection.png", 24, 23);

        BufferedImage heart = readTexture(textures, "heart.png");
        BufferedImage heartEmpty = readTexture(textures, "hudbars_bgicon_health.png");
        writeHeartFamily(root, heart, heartEmpty);

        BufferedImage armor = readTexture(textures, "hbarmor_icon.png");
        BufferedImage armorEmpty = readTexture(textures, "hbarmor_bgicon.png");
        writeTexture(root, "gui/sprites/hud/armor_full.png", armor, 9, 9);
        writeTexture(root, "gui/sprites/hud/armor_half.png", halfTexture(armor), 9, 9);
        writeTexture(root, "gui/sprites/hud/armor_empty.png", armorEmpty, 9, 9);

        BufferedImage air = readTexture(textures, "bubble.png");
        writeTexture(root, "gui/sprites/hud/air.png", air, 9, 9);
        writeTexture(root, "gui/sprites/hud/air_bursting.png", air, 9, 9);

        BufferedImage food = readTexture(textures, "hbhunger_icon.png");
        BufferedImage foodEmpty = readTexture(textures, "hbhunger_bgicon.png");
        BufferedImage foodHunger = readTexture(textures, "mcl_hunger_icon_foodpoison.png");
        writeTexture(root, "gui/sprites/hud/food_full.png", food, 9, 9);
        writeTexture(root, "gui/sprites/hud/food_half.png", halfTexture(food), 9, 9);
        writeTexture(root, "gui/sprites/hud/food_empty.png", foodEmpty, 9, 9);
        writeTexture(root, "gui/sprites/hud/food_full_hunger.png", foodHunger, 9, 9);
        writeTexture(root, "gui/sprites/hud/food_half_hunger.png", halfTexture(foodHunger), 9, 9);
        writeTexture(root, "gui/sprites/hud/food_empty_hunger.png", foodEmpty, 9, 9);

        writeRotatedTexture(textures, "mcl_experience_bar_background.png", root, "gui/sprites/hud/experience_bar_background.png", 182, 5);
        writeRotatedTexture(textures, "mcl_experience_bar.png", root, "gui/sprites/hud/experience_bar_progress.png", 182, 5);
        writeCrosshair(root);
        writeOffhandFrames(root);
        writeButtons(root, textures);
    }

    private static void writeHeartFamily(Path root, BufferedImage full, BufferedImage empty) throws IOException {
        BufferedImage half = halfTexture(full);
        for (String name : List.of(
            "full", "full_blinking", "hardcore_full", "hardcore_full_blinking",
            "poisoned_full", "poisoned_full_blinking", "poisoned_hardcore_full", "poisoned_hardcore_full_blinking",
            "withered_full", "withered_full_blinking", "withered_hardcore_full", "withered_hardcore_full_blinking",
            "absorbing_full", "absorbing_full_blinking", "absorbing_hardcore_full", "absorbing_hardcore_full_blinking",
            "frozen_full", "frozen_full_blinking", "frozen_hardcore_full", "frozen_hardcore_full_blinking", "vehicle_full"
        )) writeTexture(root, "gui/sprites/hud/heart/" + name + ".png", full, 9, 9);
        for (String name : List.of(
            "half", "half_blinking", "hardcore_half", "hardcore_half_blinking",
            "poisoned_half", "poisoned_half_blinking", "poisoned_hardcore_half", "poisoned_hardcore_half_blinking",
            "withered_half", "withered_half_blinking", "withered_hardcore_half", "withered_hardcore_half_blinking",
            "absorbing_half", "absorbing_half_blinking", "absorbing_hardcore_half", "absorbing_hardcore_half_blinking",
            "frozen_half", "frozen_half_blinking", "frozen_hardcore_half", "frozen_hardcore_half_blinking", "vehicle_half"
        )) writeTexture(root, "gui/sprites/hud/heart/" + name + ".png", half, 9, 9);
        for (String name : List.of(
            "container", "container_blinking", "container_hardcore", "container_hardcore_blinking", "vehicle_container"
        )) writeTexture(root, "gui/sprites/hud/heart/" + name + ".png", empty, 9, 9);
    }

    private static BufferedImage readTexture(Path textures, String name) throws IOException {
        Path source = contained(textures, Path.of(name), "Unsafe generated GUI source");
        require(Files.isRegularFile(source), "VoxeLibre GUI source texture is missing: " + source);
        BufferedImage image = ImageIO.read(source.toFile());
        require(image != null, "VoxeLibre GUI source is not a readable PNG: " + source);
        requireImageBounds(image.getWidth(), image.getHeight(), "VoxeLibre GUI source is too large: " + source);
        return image;
    }

    private static void copyTexture(Path textures, String source, Path root, String target, int width, int height) throws IOException {
        writeTexture(root, target, readTexture(textures, source), width, height);
    }

    private static void writeRotatedTexture(Path textures, String source, Path root, String target, int width, int height) throws IOException {
        BufferedImage input = readTexture(textures, source);
        BufferedImage rotated = new BufferedImage(input.getHeight(), input.getWidth(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = rotated.createGraphics();
        try {
            graphics.translate(rotated.getWidth(), 0);
            graphics.rotate(Math.PI / 2.0);
            graphics.drawImage(input, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        writeTexture(root, target, rotated, width, height);
    }

    private static BufferedImage halfTexture(BufferedImage source) {
        BufferedImage half = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = half.createGraphics();
        try {
            int visible = (source.getWidth() + 1) / 2;
            graphics.drawImage(source, 0, 0, visible, source.getHeight(), 0, 0, visible, source.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return half;
    }

    private static void writeCrosshair(Path root) throws IOException {
        BufferedImage image = new BufferedImage(15, 15, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(255, 255, 255, 224));
            graphics.fillRect(7, 2, 1, 11);
            graphics.fillRect(2, 7, 11, 1);
            graphics.setColor(new Color(0, 0, 0, 160));
            graphics.drawRect(6, 1, 2, 12);
            graphics.drawRect(1, 6, 12, 2);
        } finally {
            graphics.dispose();
        }
        writeTexture(root, "gui/sprites/hud/crosshair.png", image, 15, 15);
    }

    private static void writeOffhandFrames(Path root) throws IOException {
        for (String side : List.of("left", "right")) {
            BufferedImage image = new BufferedImage(29, 24, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(new Color(32, 32, 32, 220));
                graphics.fillRect(2, 1, 24, 22);
                graphics.setColor(new Color(224, 224, 224, 255));
                graphics.drawRect(2, 1, 23, 21);
            } finally {
                graphics.dispose();
            }
            writeTexture(root, "gui/sprites/hud/hotbar_offhand_" + side + ".png", image, 29, 24);
        }
    }

    private static void writeButtons(Path root, Path textures) throws IOException {
        BufferedImage normal = readTexture(textures, "mcl_base_textures_button9.png");
        BufferedImage pressed = readTexture(textures, "mcl_base_textures_button9_pressed.png");
        writeTexture(root, "gui/sprites/widget/button.png", nineSlice(normal, 200, 20, 2), 200, 20);
        writeTexture(root, "gui/sprites/widget/button_highlighted.png", nineSlice(pressed, 200, 20, 2), 200, 20);
        BufferedImage disabled = new BufferedImage(200, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = disabled.createGraphics();
        try {
            graphics.setColor(new Color(48, 48, 48, 224));
            graphics.fillRect(0, 0, 200, 20);
            graphics.setColor(new Color(96, 96, 96, 255));
            graphics.drawRect(0, 0, 199, 19);
        } finally {
            graphics.dispose();
        }
        writeTexture(root, "gui/sprites/widget/button_disabled.png", disabled, 200, 20);
    }

    private static BufferedImage nineSlice(BufferedImage source, int width, int height, int border) {
        require(source.getWidth() > border * 2 && source.getHeight() > border * 2, "Nine-slice source is too small for its border.");
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int sourceRight = source.getWidth() - border;
            int sourceBottom = source.getHeight() - border;
            int targetRight = width - border;
            int targetBottom = height - border;
            drawSlice(graphics, source, 0, 0, border, border, 0, 0, border, border);
            drawSlice(graphics, source, border, 0, sourceRight, border, border, 0, targetRight, border);
            drawSlice(graphics, source, sourceRight, 0, source.getWidth(), border, targetRight, 0, width, border);
            drawSlice(graphics, source, 0, border, border, sourceBottom, 0, border, border, targetBottom);
            drawSlice(graphics, source, border, border, sourceRight, sourceBottom, border, border, targetRight, targetBottom);
            drawSlice(graphics, source, sourceRight, border, source.getWidth(), sourceBottom, targetRight, border, width, targetBottom);
            drawSlice(graphics, source, 0, sourceBottom, border, source.getHeight(), 0, targetBottom, border, height);
            drawSlice(graphics, source, border, sourceBottom, sourceRight, source.getHeight(), border, targetBottom, targetRight, height);
            drawSlice(graphics, source, sourceRight, sourceBottom, source.getWidth(), source.getHeight(), targetRight, targetBottom, width, height);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static void drawSlice(
        Graphics2D graphics,
        BufferedImage source,
        int sourceX1,
        int sourceY1,
        int sourceX2,
        int sourceY2,
        int targetX1,
        int targetY1,
        int targetX2,
        int targetY2
    ) {
        graphics.drawImage(source, targetX1, targetY1, targetX2, targetY2, sourceX1, sourceY1, sourceX2, sourceY2, null);
    }

    private static void writeTexture(Path root, String relative, BufferedImage source, int width, int height) throws IOException {
        Path target = contained(root.resolve("assets/minecraft/textures"), Path.of(relative), "Unsafe generated GUI target");
        Files.createDirectories(target.getParent());
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        require(ImageIO.write(output, "png", target.toFile()), "Java runtime could not encode generated GUI texture: " + target);
    }

    private static void normalizePng(Path target) throws IOException {
        BufferedImage source = ImageIO.read(target.toFile());
        require(source != null, "VoxeLibre mapped texture is not a readable PNG: " + target);
        requireImageBounds(source.getWidth(), source.getHeight(), "VoxeLibre mapped texture is too large: " + target);
        BufferedImage normalized = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        Files.delete(target); // removes only the generated hard link, never the source file
        require(ImageIO.write(normalized, "png", target.toFile()), "Java runtime could not normalize mapped PNG: " + target);
    }

    private static void writeProvenance(Path root, Path sourceRoot, Path table, Path legal, String fingerprint, int mappings) throws IOException {
        ObjectNode provenance = JSON.createObjectNode();
        provenance.put("provider", "voxelibre");
        provenance.put("adapter_version", ADAPTER_VERSION);
        provenance.put("fingerprint", fingerprint);
        provenance.put("source_root", sourceRoot.toString());
        provenance.put("conversion_table", table.toString());
        provenance.put("media_license_inventory", legal.toString());
        provenance.put("mapped_whole_file_textures", mappings);
        provenance.put("generated_support", "pack metadata, fluid animation metadata, local-dojo block models, and a fallback ASCII bitmap font");
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("provenance.json").toFile(), provenance);
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static Path contained(Path root, Path relative, String message) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        require(target.startsWith(normalizedRoot), message + ": " + relative);
        return target;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static void requireImageBounds(int width, int height, String message) {
        require(width > 0 && height > 0 && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION
            && (long) width * height <= MAX_IMAGE_PIXELS, message + " (" + width + "x" + height + ")");
    }

}
