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

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministically rasterizes compatibility scaffolding textures for every
 * audited missing texture target. Each texture is a pure function of its
 * resource path: the same input always produces byte-identical output, and no
 * target depends on mutable global state. Higher-priority authored packs still
 * replace these images in the composed view.
 */
final class GeneratedTextureLibrary {
    private static final int SIZE = 16;

    static final String FAMILY_SPAWN_EGG = "spawn_egg";
    static final String FAMILY_BANNER = "banner";
    static final String FAMILY_WAXED_COPPER = "waxed_copper";
    static final String FAMILY_CANDLE_CAKE = "candle_cake";
    static final String FAMILY_GLASS_PANE = "glass_pane";
    static final String FAMILY_HANGING_SIGN = "hanging_sign";
    static final String FAMILY_BED = "bed";
    static final String FAMILY_SHULKER_BOX = "shulker_box";
    static final String FAMILY_DOOR = "door";
    static final String FAMILY_WOOD = "wood";
    static final String FAMILY_CORAL_FAN = "coral_fan";
    static final String FAMILY_INFESTED = "infested";
    static final String FAMILY_POTTED_PLANT = "potted_plant";
    static final String FAMILY_HEAD = "head";
    static final String FAMILY_GENERIC_BLOCK = "generic_block";
    static final String FAMILY_GENERIC_ITEM = "generic_item";

    private static final Map<String, int[]> DYES = Map.ofEntries(
        Map.entry("white", rgb(233, 236, 236)),
        Map.entry("orange", rgb(240, 118, 19)),
        Map.entry("magenta", rgb(196, 78, 189)),
        Map.entry("light_blue", rgb(59, 178, 218)),
        Map.entry("yellow", rgb(249, 195, 33)),
        Map.entry("lime", rgb(107, 199, 32)),
        Map.entry("pink", rgb(240, 148, 168)),
        Map.entry("gray", rgb(78, 78, 78)),
        Map.entry("light_gray", rgb(147, 147, 147)),
        Map.entry("cyan", rgb(22, 136, 156)),
        Map.entry("purple", rgb(130, 53, 180)),
        Map.entry("blue", rgb(57, 78, 167)),
        Map.entry("brown", rgb(98, 60, 34)),
        Map.entry("green", rgb(85, 119, 21)),
        Map.entry("red", rgb(154, 34, 30)),
        Map.entry("black", rgb(21, 22, 26))
    );
    private static final Map<String, int[]> WOODS = Map.ofEntries(
        Map.entry("oak", rgb(171, 137, 93)),
        Map.entry("spruce", rgb(101, 78, 54)),
        Map.entry("birch", rgb(195, 177, 125)),
        Map.entry("jungle", rgb(142, 107, 71)),
        Map.entry("acacia", rgb(162, 88, 44)),
        Map.entry("dark_oak", rgb(66, 48, 32)),
        Map.entry("mangrove", rgb(105, 52, 40)),
        Map.entry("cherry", rgb(222, 173, 172)),
        Map.entry("bamboo", rgb(210, 198, 118)),
        Map.entry("crimson", rgb(111, 43, 50)),
        Map.entry("warped", rgb(46, 104, 105))
    );
    private static final List<String> WOOD_NAMES = List.of(
        "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak", "jungle",
        "mangrove", "oak", "spruce", "warped"
    );
    private static final Map<String, int[]> COPPER = Map.of(
        "copper", rgb(181, 116, 80),
        "exposed_copper", rgb(140, 147, 128),
        "weathered_copper", rgb(108, 150, 134),
        "oxidized_copper", rgb(81, 135, 120)
    );
    private static final Map<String, int[]> CORAL = Map.of(
        "tube_coral", rgb(98, 145, 255),
        "brain_coral", rgb(231, 99, 158),
        "bubble_coral", rgb(170, 76, 201),
        "fire_coral", rgb(231, 91, 83),
        "horn_coral", rgb(231, 166, 63),
        "dead_tube_coral", rgb(117, 122, 129),
        "dead_brain_coral", rgb(117, 122, 129),
        "dead_bubble_coral", rgb(117, 122, 129),
        "dead_fire_coral", rgb(117, 122, 129),
        "dead_horn_coral", rgb(117, 122, 129)
    );
    private static final int[] WHITE = rgb(233, 236, 236);
    private static final List<String> DYE_NAMES = List.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    private GeneratedTextureLibrary() {}

    static BufferedImage generate(String target) {
        String name = name(target);
        return switch (family(target)) {
            case FAMILY_SPAWN_EGG -> spawnEgg(name);
            case FAMILY_BANNER -> banner(name.endsWith("_wall_banner") ? colorBefore(name, "_wall_banner") : colorBefore(name, "_banner"));
            case FAMILY_WAXED_COPPER -> waxedCopper(name);
            case FAMILY_CANDLE_CAKE -> candleCake(name.equals("candle_cake") ? "white" : colorBefore(name, "_candle_cake"));
            case FAMILY_GLASS_PANE -> glassPane(name.equals("glass_pane") ? "white" : colorBefore(name, "_stained_glass_pane"));
            case FAMILY_HANGING_SIGN -> hangingSign(woodColor(name));
            case FAMILY_BED -> bed(colorBefore(name, "_bed"));
            case FAMILY_SHULKER_BOX -> shulkerBox(name.equals("shulker_box") ? "purple" : colorBefore(name, "_shulker_box"));
            case FAMILY_DOOR -> door(name);
            case FAMILY_WOOD -> wood(woodColor(name));
            case FAMILY_CORAL_FAN -> coralFan(coralColor(name));
            case FAMILY_INFESTED -> infested();
            case FAMILY_POTTED_PLANT -> pottedPlant();
            case FAMILY_HEAD -> head(name);
            case FAMILY_GENERIC_ITEM -> genericItem(name);
            case FAMILY_GENERIC_BLOCK -> genericBlock(name);
            default -> throw new IllegalStateException("Unclassified generated texture target: " + target);
        };
    }

    /**
     * Deterministic, path-keyed classifier for the raster family a missing
     * texture target belongs to. Used by both the generator and the content
     * submission queue so classification cannot drift from generation.
     */
    static String family(String target) {
        String name = name(target);
        if (name.endsWith("_spawn_egg")) return FAMILY_SPAWN_EGG;
        if (name.endsWith("_wall_banner") || name.endsWith("_banner")) return FAMILY_BANNER;
        if (name.startsWith("waxed_")) return FAMILY_WAXED_COPPER;
        if (name.endsWith("_candle_cake") || name.equals("candle_cake")) return FAMILY_CANDLE_CAKE;
        if (name.endsWith("_stained_glass_pane") || name.equals("glass_pane")) return FAMILY_GLASS_PANE;
        if (name.endsWith("_wall_hanging_sign") || name.endsWith("_hanging_sign")) return FAMILY_HANGING_SIGN;
        if (name.endsWith("_bed")) return FAMILY_BED;
        if (name.endsWith("_shulker_box") || name.equals("shulker_box")) return FAMILY_SHULKER_BOX;
        if (name.endsWith("_door")) return FAMILY_DOOR;
        if (name.endsWith("_wood") || name.endsWith("_hyphae") || name.endsWith("_log")) return FAMILY_WOOD;
        if (name.endsWith("_wall_fan")) return FAMILY_CORAL_FAN;
        if (name.startsWith("infested_")) return FAMILY_INFESTED;
        if (name.startsWith("potted_")) return FAMILY_POTTED_PLANT;
        if (name.endsWith("_wall_head") || name.endsWith("_head")
            || name.endsWith("_wall_skull") || name.endsWith("_skull")) return FAMILY_HEAD;
        if (target.contains("/item/")) return FAMILY_GENERIC_ITEM;
        return FAMILY_GENERIC_BLOCK;
    }

    /** Whether the raster family produces a visually distinct tile rather than a generic placeholder. */
    static boolean distinct(String family) {
        return !family.equals(FAMILY_GENERIC_BLOCK) && !family.equals(FAMILY_GENERIC_ITEM);
    }

    private static String name(String target) {
        return target.substring(target.lastIndexOf('/') + 1, target.length() - ".png".length());
    }

    private static BufferedImage spawnEgg(String name) {
        BufferedImage image = canvas();
        Random random = random(name);
        int[] base = pastel(random.nextDouble());
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            if (eggShape(x, y)) image.setRGB(x, y, argb(255, base));
        }
        for (int spot = 0; spot < 14; spot++) {
            int cx = 3 + random.nextInt(10);
            int cy = 3 + random.nextInt(10);
            int radius = 1 + random.nextInt(2);
            int[] color = mix(base, dye(random.nextInt(DYES.size())), 0.55 + random.nextDouble() * 0.3);
            fillCircle(image, cx, cy, radius, argb(255, color));
        }
        return image;
    }

    private static BufferedImage banner(String color) {
        BufferedImage image = canvas();
        int[] base = dyeColor(color);
        fill(image, base);
        int[] emblem = mix(base, WHITE, 0.7);
        for (int x = 0; x < SIZE; x++) {
            if (x >= 6 && x <= 9) continue;
            for (int y = 0; y < SIZE; y++) {
                if (y == 1 || y == 2) image.setRGB(x, y, argb(255, emblem));
            }
        }
        for (int x = 7; x <= 8; x++) for (int y = 5; y <= 10; y++) image.setRGB(x, y, argb(255, emblem));
        return image;
    }

    private static BufferedImage candleCake(String color) {
        BufferedImage image = canvas();
        fill(image, rgb(232, 217, 173));
        for (int x = 0; x < SIZE; x++) for (int y = 0; y < 5; y++) image.setRGB(x, y, argb(255, rgb(240, 225, 214)));
        int[] candle = dyeColor(color);
        for (int x = 7; x <= 8; x++) for (int y = 5; y <= 9; y++) image.setRGB(x, y, argb(255, candle));
        for (int x = 6; x <= 9; x++) image.setRGB(x, 4, argb(255, candle));
        image.setRGB(8, 3, argb(255, rgb(255, 220, 110)));
        image.setRGB(7, 2, argb(255, rgb(255, 200, 60)));
        return image;
    }

    private static BufferedImage glassPane(String color) {
        BufferedImage image = canvas();
        int[] tint = dyeColor(color);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            boolean frame = x <= 1 || x >= SIZE - 2 || y <= 1 || y >= SIZE - 2;
            int alpha = frame ? 235 : 120;
            image.setRGB(x, y, argb(alpha, tint));
        }
        for (int y = 2; y < SIZE - 2; y++) for (int x = 2; x < SIZE - 2; x++) {
            image.setRGB(x, y, argb(90, mix(tint, WHITE, 0.35)));
        }
        return image;
    }

    private static BufferedImage hangingSign(int[] wood) {
        BufferedImage image = canvas();
        fill(image, wood);
        for (int x = 0; x < SIZE; x++) {
            image.setRGB(x, 0, argb(255, mix(wood, rgb(0, 0, 0), 0.5)));
            image.setRGB(x, SIZE - 1, argb(255, mix(wood, rgb(0, 0, 0), 0.4)));
        }
        for (int x = 4; x <= 5; x++) for (int y = 1; y <= 2; y++) image.setRGB(x, y, argb(255, mix(wood, rgb(0, 0, 0), 0.7)));
        for (int x = 10; x <= 11; x++) for (int y = 1; y <= 2; y++) image.setRGB(x, y, argb(255, mix(wood, rgb(0, 0, 0), 0.7)));
        Random random = random("grain-" + wood[0] + "-" + wood[1] + "-" + wood[2]);
        for (int y = 3; y < SIZE - 1; y++) for (int x = 0; x < SIZE; x++) {
            if (random.nextInt(100) < 6) image.setRGB(x, y, argb(255, mix(wood, rgb(0, 0, 0), 0.3)));
        }
        return image;
    }

    private static BufferedImage bed(String color) {
        BufferedImage image = canvas();
        int[] dye = dyeColor(color);
        fill(image, mix(dye, WHITE, 0.25));
        for (int x = 0; x < 6; x++) for (int y = 2; y < SIZE - 1; y++) image.setRGB(x, y, argb(255, rgb(244, 240, 234)));
        for (int x = 0; x < SIZE; x++) for (int y = 1; y < SIZE; y++) {
            if (x < 1 || x >= SIZE - 1 || y >= SIZE - 1) image.setRGB(x, y, argb(255, mix(dye, rgb(0, 0, 0), 0.4)));
        }
        return image;
    }

    private static BufferedImage shulkerBox(String color) {
        BufferedImage image = canvas();
        int[] dye = dyeColor(color);
        fill(image, dye);
        for (int x = 0; x < SIZE; x++) for (int y = 0; y < 4; y++) {
            image.setRGB(x, y, argb(255, mix(dye, WHITE, 0.3)));
        }
        for (int x = 0; x < SIZE; x++) {
            image.setRGB(x, 4, argb(255, mix(dye, rgb(0, 0, 0), 0.35)));
            image.setRGB(x, SIZE - 1, argb(255, mix(dye, rgb(0, 0, 0), 0.45)));
        }
        return image;
    }

    private static BufferedImage door(String name) {
        int[] base = doorColor(name);
        BufferedImage image = canvas();
        Random random = random(name);
        fill(image, base);
        for (int x = 0; x < SIZE; x++) for (int y = 0; y < SIZE; y++) {
            if (x <= 1 || x >= SIZE - 2 || y <= 1 || y >= SIZE - 2) {
                image.setRGB(x, y, argb(255, mix(base, rgb(0, 0, 0), 0.4)));
            }
        }
        for (int x = 3; x <= 6; x++) for (int y = 3; y <= 6; y++) {
            image.setRGB(x, y, argb(255, mix(base, rgb(0, 0, 0), 0.22)));
        }
        for (int x = 9; x <= 12; x++) for (int y = 3; y <= 6; y++) {
            image.setRGB(x, y, argb(255, mix(base, rgb(0, 0, 0), 0.22)));
        }
        for (int x = 3; x <= 12; x++) for (int y = 9; y <= 12; y++) {
            image.setRGB(x, y, argb(255, mix(base, rgb(0, 0, 0), 0.22)));
        }
        image.setRGB(13, 8, argb(255, mix(base, WHITE, 0.35)));
        image.setRGB(13, 9, argb(255, mix(base, WHITE, 0.35)));
        for (int y = 3; y < 13; y++) {
            if (random.nextInt(100) < 12) image.setRGB(8, y, argb(255, mix(base, rgb(0, 0, 0), 0.3)));
        }
        return image;
    }

    private static BufferedImage wood(int[] bark) {
        BufferedImage image = canvas();
        Random random = random("wood" + bark[0] + "-" + bark[1] + "-" + bark[2]);
        for (int x = 0; x < SIZE; x++) {
            int streak = 1 + random.nextInt(2);
            int[] tone = mix(bark, rgb(0, 0, 0), random.nextDouble() * 0.35);
            for (int y = 0; y < SIZE; y++) image.setRGB(x, y, argb(255, tone));
            for (int y = 0; y < SIZE; y++) {
                if (random.nextInt(100) < 8) image.setRGB(x, y, argb(255, mix(tone, WHITE, 0.2)));
                if (random.nextInt(100) < 8) image.setRGB(x, y, argb(255, mix(tone, rgb(0, 0, 0), 0.25)));
            }
        }
        for (int y = 4; y < 5; y++) for (int x = 0; x < SIZE; x++) {
            image.setRGB(x, y, argb(255, mix(bark, rgb(0, 0, 0), 0.45)));
        }
        return image;
    }

    private static BufferedImage coralFan(int[] coral) {
        BufferedImage image = canvas();
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int dx = x - SIZE / 2;
            int dy = y - SIZE / 2;
            double distance = Math.sqrt(dx * dx + dy * dy);
            double angle = Math.atan2(dy, dx);
            double ribs = Math.abs(Math.sin(angle * 5.0));
            if (distance < 7.0 && (ribs > 0.75 || distance < 2.0)) {
                image.setRGB(x, y, argb(255, coral));
            } else {
                image.setRGB(x, y, argb(0, 0, 0, 0));
            }
        }
        return image;
    }

    private static BufferedImage infested() {
        BufferedImage image = canvas();
        fill(image, rgb(125, 125, 125));
        Random random = random("infested");
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int noise = random.nextInt(16) - 8;
            image.setRGB(x, y, argb(255, shade(rgb(125, 125, 125), noise)));
        }
        drawCrack(image, random);
        drawCrack(image, random);
        return image;
    }

    private static BufferedImage pottedPlant() {
        BufferedImage image = canvas();
        for (int x = 3; x <= 12; x++) for (int y = 9; y <= 13; y++) image.setRGB(x, y, argb(255, rgb(150, 93, 61)));
        for (int x = 5; x <= 10; x++) for (int y = 14; y <= 14; y++) image.setRGB(x, y, argb(255, rgb(150, 93, 61)));
        for (int y = 3; y <= 8; y++) image.setRGB(8, y, argb(255, rgb(70, 120, 50)));
        for (int y = 4; y <= 7; y++) {
            image.setRGB(8 - (y - 3), y, argb(255, rgb(88, 145, 62)));
            image.setRGB(8 + (y - 3), y, argb(255, rgb(88, 145, 62)));
        }
        return image;
    }

    private static BufferedImage head(String name) {
        int[] skin = name.contains("creeper") ? rgb(104, 188, 96)
            : name.contains("wither") ? rgb(66, 66, 66)
            : name.contains("skeleton") ? rgb(200, 200, 200)
            : name.contains("piglin") ? rgb(233, 172, 150)
            : name.contains("zombie") ? rgb(108, 148, 98)
            : name.contains("dragon") ? rgb(62, 62, 62)
            : rgb(224, 181, 149);
        BufferedImage image = canvas();
        fill(image, skin);
        Random random = random(name);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int noise = random.nextInt(10) - 5;
            image.setRGB(x, y, argb(255, shade(skin, noise)));
        }
        for (int x = 4; x <= 5; x++) for (int y = 6; y <= 7; y++) image.setRGB(x, y, argb(255, rgb(60, 60, 70)));
        for (int x = 10; x <= 11; x++) for (int y = 6; y <= 7; y++) image.setRGB(x, y, argb(255, rgb(60, 60, 70)));
        for (int x = 7; x <= 8; x++) for (int y = 11; y <= 12; y++) image.setRGB(x, y, argb(255, rgb(80, 80, 90)));
        return image;
    }

    private static BufferedImage genericBlock(String name) {
        BufferedImage image = canvas();
        Random random = random(name);
        int[] base = blockTone(name, random);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int noise = random.nextInt(14) - 7;
            image.setRGB(x, y, argb(255, shade(base, noise)));
        }
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            if (random.nextInt(100) < 4) image.setRGB(x, y, argb(255, shade(base, -26)));
        }
        return image;
    }

    private static BufferedImage genericItem(String name) {
        BufferedImage image = canvas();
        Random random = random(name);
        int[] base = itemTone(name, random);
        for (int y = 3; y < SIZE - 3; y++) for (int x = 3; x < SIZE - 3; x++) {
            int noise = random.nextInt(12) - 6;
            image.setRGB(x, y, argb(255, shade(base, noise)));
        }
        for (int y = 2; y < 5; y++) for (int x = 3; x < SIZE - 3; x++) {
            image.setRGB(x, y, argb(255, shade(base, 30)));
        }
        return image;
    }

    private static BufferedImage waxedCopper(String name) {
        int[] base = COPPER.get("copper");
        if (name.contains("exposed")) base = COPPER.get("exposed_copper");
        else if (name.contains("weathered")) base = COPPER.get("weathered_copper");
        else if (name.contains("oxidized")) base = COPPER.get("oxidized_copper");
        BufferedImage image = canvas();
        Random random = random(name);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int noise = random.nextInt(16) - 8;
            image.setRGB(x, y, argb(255, shade(base, noise)));
        }
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            if (random.nextInt(100) < 6) image.setRGB(x, y, argb(255, shade(base, 26)));
        }
        return image;
    }

    private static void drawCrack(BufferedImage image, Random random) {
        int x = random.nextInt(SIZE);
        int y = 0;
        while (y < SIZE) {
            image.setRGB(x, y, argb(255, rgb(70, 70, 70)));
            x = Math.max(0, Math.min(SIZE - 1, x + (random.nextInt(3) - 1)));
            y++;
        }
    }

    private static int[] doorColor(String name) {
        if (name.contains("iron")) return rgb(176, 176, 176);
        if (name.contains("copper")) return COPPER.get(name.contains("exposed") ? "exposed_copper"
            : name.contains("weathered") ? "weathered_copper"
            : name.contains("oxidized") ? "oxidized_copper" : "copper");
        for (String wood : WOOD_NAMES) {
            if (name.startsWith(wood + "_")) return WOODS.get(wood);
        }
        return rgb(171, 137, 93);
    }

    private static int[] woodColor(String name) {
        if (name.startsWith("stripped_")) name = name.substring("stripped_".length());
        for (String wood : WOOD_NAMES) {
            if (name.startsWith(wood + "_")) return WOODS.get(wood);
        }
        return rgb(171, 137, 93);
    }

    private static int[] coralColor(String name) {
        int[] color = CORAL.get(name.substring(0, name.length() - "_wall_fan".length()));
        return color != null ? color : rgb(180, 180, 180);
    }

    private static int[] blockTone(String name, Random random) {
        return switch (name) {
            case "ancient_debris", "basalt", "polished_basalt", "reinforced_deepslate", "deepslate_brick",
                "deepslate_tile", "tuff_brick", "mud_brick", "snow_block", "frosted_ice", "smooth_quartz",
                "smooth_sandstone", "smooth_red_sandstone", "quartz_block", "purpur", "bone_block",
                "prismarine_brick", "mossy_stone_brick" -> rgb(150, 148, 150);
            case "soul_fire", "fire" -> rgb(80, 140, 220);
            case "magma_block", "respawn_anchor", "tnt", "campfire", "soul_campfire", "lantern" -> rgb(190, 90, 50);
            case "hay_block", "target", "dried_kelp_block", "cartography_table", "fletching_table", "lectern",
                "loom", "smithing_table", "scaffolding", "barrel", "composter", "beehive", "bee_nest",
                "jukebox" -> rgb(176, 142, 84);
            case "cactus", "melon", "azalea", "flowering_azalea", "large_fern", "lilac", "peony", "rose_bush",
                "sunflower", "pitcher_plant", "wheat", "beetroots", "carrots", "potatoes", "nether_wart",
                "sweet_berry_bush", "torchflower_crop", "pitcher_crop", "small_dripleaf", "big_dripleaf",
                "mangrove_roots", "muddy_mangrove_roots", "tall_grass", "tall_seagrass", "cocoa" -> rgb(80, 130, 60);
            case "suspicious_sand", "dirt_path", "podzol", "mycelium" -> rgb(140, 110, 70);
            case "suspicious_gravel" -> rgb(120, 116, 112);
            case "sculk_catalyst", "sculk_sensor", "sculk_shrieker", "calibrated_sculk_sensor" -> rgb(16, 44, 54);
            case "ender_chest", "end_portal", "end_gateway", "end_portal_frame", "chest", "trapped_chest" -> rgb(90, 70, 48);
            case "furnace", "blast_furnace", "smoker", "stonecutter", "grindstone", "anvil", "chipped_anvil",
                "damaged_anvil" -> rgb(122, 120, 122);
            case "enchanting_table", "bookshelf", "chiseled_bookshelf", "cauldron", "water_cauldron",
                "lava_cauldron", "powder_snow_cauldron", "hopper" -> rgb(104, 80, 70);
            case "bell", "lightning_rod", "light_weighted_pressure_plate", "heavy_weighted_pressure_plate",
                "polished_blackstone_button", "stone_button", "polished_blackstone_pressure_plate",
                "stone_pressure_plate", "nether_brick_fence" -> rgb(128, 128, 132);
            case "bubble_column" -> rgb(70, 120, 180);
            case "structure_void", "light", "barrier" -> rgb(180, 40, 40);
            case "jigsaw", "command_block", "chain_command_block", "repeating_command_block",
                "trial_spawner" -> rgb(160, 90, 60);
            case "lodestone" -> rgb(98, 98, 104);
            case "daylight_detector" -> rgb(186, 178, 130);
            case "moss_wool" -> rgb(106, 122, 70);
            case "piston", "moving_piston", "sticky_piston" -> rgb(130, 122, 100);
            case "redstone_wire", "redstone_lamp" -> rgb(190, 60, 40);
            case "honey_block" -> rgb(238, 160, 40);
            case "pointed_dripstone" -> rgb(164, 164, 168);
            case "sniffer_egg" -> rgb(170, 160, 110);
            case "decorated_pot", "decorated_pot_base" -> rgb(178, 96, 62);
            case "petrified_oak" -> rgb(150, 150, 150);
            default -> rgb(120 + random.nextInt(40), 120 + random.nextInt(40), 120 + random.nextInt(40));
        };
    }

    private static int[] itemTone(String name, Random random) {
        return switch (name) {
            case "clock" -> rgb(232, 208, 120);
            case "compass", "recovery_compass" -> rgb(210, 190, 130);
            case "crossbow" -> rgb(130, 100, 70);
            case "debug_stick" -> rgb(90, 210, 220);
            case "enchanted_golden_apple" -> rgb(230, 120, 40);
            case "farmland", "grass_block", "stone", "stone_brick_stairs", "oak_slab" -> rgb(140, 120, 100);
            case "glowstone", "sea_lantern" -> rgb(225, 200, 120);
            case "shield" -> rgb(170, 140, 90);
            case "tipped_arrow" -> rgb(200, 130, 60);
            case "tooting_goat_horn" -> rgb(200, 170, 120);
            default -> rgb(150 + random.nextInt(40), 150 + random.nextInt(40), 150 + random.nextInt(40));
        };
    }

    private static boolean eggShape(int x, int y) {
        double nx = (x - SIZE / 2.0 + 0.5) / (SIZE * 0.32);
        double ny = (y - SIZE / 2.0 + 0.5) / (SIZE * 0.38);
        return nx * nx + ny * ny <= 1.0;
    }

    private static String colorBefore(String name, String suffix) {
        return name.substring(0, name.length() - suffix.length());
    }

    private static int[] dyeColor(String color) {
        int[] value = DYES.get(color);
        return value != null ? value : WHITE;
    }

    private static int[] dye(int index) {
        return dyeColor(DYE_NAMES.get(Math.floorMod(index, DYE_NAMES.size())));
    }

    private static Random random(String seed) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < seed.length(); i++) {
            hash ^= seed.charAt(i);
            hash *= 0x100000001b3L;
        }
        return new Random(hash);
    }

    private static int[] pastel(double index) {
        int hue = (int) (index * 360);
        return hsv(hue, 0.35, 0.92);
    }

    private static int[] hsv(int hue, double saturation, double value) {
        double chroma = value * saturation;
        double x = chroma * (1 - Math.abs(((hue / 60.0) % 2) - 1));
        double[] channel = hue < 60 ? new double[]{chroma, x, 0}
            : hue < 120 ? new double[]{x, chroma, 0}
            : hue < 180 ? new double[]{0, chroma, x}
            : hue < 240 ? new double[]{0, x, chroma}
            : hue < 300 ? new double[]{x, 0, chroma}
            : new double[]{chroma, 0, x};
        double m = value - chroma;
        return rgb((int) ((channel[0] + m) * 255), (int) ((channel[1] + m) * 255), (int) ((channel[2] + m) * 255));
    }

    private static int[] mix(int[] first, int[] second, double amount) {
        return rgb(
            (int) (first[0] + (second[0] - first[0]) * amount),
            (int) (first[1] + (second[1] - first[1]) * amount),
            (int) (first[2] + (second[2] - first[2]) * amount)
        );
    }

    private static int[] shade(int[] color, int delta) {
        return rgb(clamp(color[0] + delta), clamp(color[1] + delta), clamp(color[2] + delta));
    }

    private static int[] rgb(int red, int green, int blue) {
        return new int[]{clamp(red), clamp(green), clamp(blue)};
    }

    private static int argb(int alpha, int[] color) {
        return argb(alpha, color[0], color[1], color[2]);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static BufferedImage canvas() {
        return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
    }

    private static void fill(BufferedImage image, int[] color) {
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) image.setRGB(x, y, argb(255, color));
    }

    private static void fillCircle(BufferedImage image, int centerX, int centerY, int radius, int argb) {
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) continue;
                int dx = x - centerX;
                int dy = y - centerY;
                if (dx * dx + dy * dy <= radius * radius) image.setRGB(x, y, argb);
            }
        }
    }
}
