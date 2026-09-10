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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratedContentPackTest {
    @TempDir
    Path temporary;

    @Test
    void unionsAuditsAndPublishesDeterministicScaffoldingAndAuthoredTextures() throws Exception {
        Path audits = Files.createDirectories(temporary.resolve("audits"));
        Files.writeString(audits.resolve("00-baseline.json"), audit(
            "[{\"target\":\"assets/minecraft/blockstates/acacia_stairs.json\"}]",
            "[{\"target\":\"assets/minecraft/models/item/acacia_stairs.json\"},{\"target\":\"assets/minecraft/models/item/apple.json\"}]",
            "[]"
        ));
        Files.writeString(audits.resolve("01-structure.json"), audit(
            "[]",
            "[{\"target\":\"assets/minecraft/models/block/acacia_stairs.json\"}]",
            "[{\"target\":\"assets/minecraft/textures/misc/vignette.png\"}]"
        ));

        GeneratedContentPack.Result first = GeneratedContentPack.prepare(audits, temporary.resolve("store-a"));
        GeneratedContentPack.Result second = GeneratedContentPack.prepare(audits, temporary.resolve("store-b"));

        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.audits(), second.audits());
        assertEquals(first.generatedBlockstates(), second.generatedBlockstates());
        assertEquals(first.generatedModels(), second.generatedModels());
        assertEquals(first.generatedTextures(), second.generatedTextures());
        assertEquals(2, first.audits());
        assertEquals(1, first.generatedBlockstates());
        assertEquals(3, first.generatedModels());
        assertEquals(5, first.generatedTextures());
        assertTrue(Files.readString(first.resourcePack().resolve("assets/minecraft/models/item/acacia_stairs.json"))
            .contains("minecraft:block/acacia_stairs"));
        assertTrue(Files.readString(first.resourcePack().resolve("assets/minecraft/models/item/apple.json"))
            .contains("minecraft:item/apple"));
        assertTrue(Files.readString(first.resourcePack().resolve("assets/minecraft/models/block/acacia_stairs.json"))
            .contains("minecraft:block/acacia_planks"));

        BufferedImage water = ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/block/water_overlay.png").toFile());
        BufferedImage glint = ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/misc/enchanted_glint_entity.png").toFile());
        BufferedImage shadow = ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/misc/shadow.png").toFile());
        BufferedImage vignette = ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/misc/vignette.png").toFile());
        BufferedImage etfNose = ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/etf_nose.png").toFile());
        assertEquals(32, water.getWidth());
        assertEquals(64, glint.getWidth());
        assertEquals(0, shadow.getRGB(0, 0) >>> 24);
        assertTrue((shadow.getRGB(shadow.getWidth() / 2, shadow.getHeight() / 2) >>> 24) > 150);
        assertEquals(0, vignette.getRGB(vignette.getWidth() / 2, vignette.getHeight() / 2) >>> 24);
        assertTrue((vignette.getRGB(0, 0) >>> 24) > 200);
        assertEquals(8, etfNose.getWidth());
        assertEquals(0, etfNose.getRGB(4, 4) >>> 24);
        assertTrue(Files.readString(first.resourcePack().resolve("provenance.json")).contains("image_generation_reference_sha256"));
    }

    @Test
    void rejectsTargetsThatEscapeResourcePackLayout() throws Exception {
        Path audit = temporary.resolve("unsafe.json");
        Files.writeString(audit, audit(
            "[{\"target\":\"assets/minecraft/blockstates/../outside.json\"}]",
            "[]",
            "[]"
        ));

        assertThrows(IllegalArgumentException.class, () -> GeneratedContentPack.prepare(audit, temporary.resolve("store")));
    }

    private static String audit(String blockstates, String models, String textures) {
        return "{\"schema\":1,\"missing\":{\"blockstates\":" + blockstates
            + ",\"models\":" + models + ",\"textures\":" + textures + "}}";
    }
}
