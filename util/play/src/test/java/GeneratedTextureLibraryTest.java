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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratedTextureLibraryTest {
    @TempDir
    Path temporary;

    @Test
    void generatesEveryAuditedMissingTextureTargetDeterministically() throws Exception {
        Path audits = Files.createDirectories(temporary.resolve("audits"));
        Path finalAudit = Path.of(System.getProperty("minosoft.audit", "")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(finalAudit)) {
            System.err.println("Skipping full-audit coverage; set -Dminosoft.audit to the final audit JSON.");
            return;
        }
        String raw = Files.readString(finalAudit);
        Path audit = audits.resolve("00-audit.json");
        Files.writeString(audit, raw);

        GeneratedContentPack.Result result = GeneratedContentPack.prepare(audits, temporary.resolve("store"));

        List<String> targets = missingTextureTargets(raw);
        assertEquals(targets.size(), result.generatedTextures() - 5, "Every audited missing texture target must be generated.");
        for (String target : targets) {
            Path file = result.resourcePack().resolve(target);
            assertTrue(Files.isRegularFile(file), "Generated pack is missing texture " + target);
            BufferedImage image = ImageIO.read(file.toFile());
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0, "Generated texture has no pixels: " + target);
        }
    }

    @Test
    void familyTexturesAreDeterministicDistinctAndSized() throws Exception {
        List<String> targets = List.of(
            "assets/minecraft/textures/item/axolotl_spawn_egg.png",
            "assets/minecraft/textures/item/zombie_spawn_egg.png",
            "assets/minecraft/textures/block/red_banner.png",
            "assets/minecraft/textures/block/white_candle_cake.png",
            "assets/minecraft/textures/block/white_stained_glass_pane.png",
            "assets/minecraft/textures/block/oak_door.png",
            "assets/minecraft/textures/block/oak_wood.png",
            "assets/minecraft/textures/block/crimson_hyphae.png",
            "assets/minecraft/textures/block/oak_hanging_sign.png",
            "assets/minecraft/textures/block/potted_oak_sapling.png",
            "assets/minecraft/textures/block/red_bed.png",
            "assets/minecraft/textures/block/red_shulker_box.png",
            "assets/minecraft/textures/item/stone.png",
            "assets/minecraft/textures/block/ancient_debris.png"
        );

        List<BufferedImage> images = new ArrayList<>();
        for (String target : targets) {
            BufferedImage first = GeneratedTextureLibrary.generate(target);
            BufferedImage second = GeneratedTextureLibrary.generate(target);
            assertEquals(16, first.getWidth());
            assertEquals(16, first.getHeight());
            assertArrayEquals(imageBytes(first), imageBytes(second), "Texture must be deterministic: " + target);
            images.add(first);
        }
        for (int index = 0; index < images.size(); index++) {
            for (int other = index + 1; other < images.size(); other++) {
                assertFalse(Arrays.equals(imageBytes(images.get(index)), imageBytes(images.get(other))),
                    "Distinct families must not rasterize to identical bytes.");
            }
        }
    }

    @Test
    void bannerVariantsDifferByColorWhileWallAndPlainShareStructure() throws Exception {
        BufferedImage red = GeneratedTextureLibrary.generate("assets/minecraft/textures/block/red_banner.png");
        BufferedImage blue = GeneratedTextureLibrary.generate("assets/minecraft/textures/block/blue_banner.png");
        BufferedImage redWall = GeneratedTextureLibrary.generate("assets/minecraft/textures/block/red_wall_banner.png");
        assertFalse(Arrays.equals(imageBytes(red), imageBytes(blue)));
        assertArrayEquals(imageBytes(red), imageBytes(redWall));
    }

    private static List<String> missingTextureTargets(String raw) throws Exception {
        String[] segments = raw.split("\"textures\":");
        require(segments.length == 2, "Audit JSON has no textures inventory.");
        String inventory = segments[1].substring(0, segments[1].indexOf(']') + 1);
        List<String> targets = new ArrayList<>();
        int index = 0;
        while ((index = inventory.indexOf("\"target\":", index)) >= 0) {
            int start = inventory.indexOf('"', index + "\"target\":".length()) + 1;
            int end = inventory.indexOf('"', start);
            String target = inventory.substring(start, end);
            if (target.endsWith(".png")) targets.add(target);
            index = end;
        }
        return targets;
    }

    private static byte[] imageBytes(BufferedImage image) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", buffer), "Java runtime could not encode a generated texture.");
        return buffer.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
