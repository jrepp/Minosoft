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
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentPackAdapterTest {
    @TempDir
    Path temporary;

    @Test
    void preparesHashAddressedSafeResourceView() throws Exception {
        Path source = temporary.resolve("VoxeLibre");
        Path textures = Files.createDirectories(source.resolve("textures"));
        Path tools = Files.createDirectories(source.resolve("tools"));
        Files.writeString(source.resolve("LEGAL.md"), "fixture media license\n");
        writePng(textures.resolve("stone.png"), 16, 16);
        writePng(textures.resolve("water.png"), 16, 32);
        writePng(textures.resolve("atlas.png"), 8, 8);
        writeGuiFixtures(textures);
        Files.writeString(
            tools.resolve("Conversion_Table.csv"),
            "Source path,Source file,Target file,xs,ys,xl,yl,xt,yt,Blacklisted?\n"
                + "/assets/minecraft/textures/block,stone.png,stone.png,,,,,,,\n"
                + "/assets/minecraft/textures/block,water_still.png,water.png,,,,,,,\n"
                + "/assets/minecraft/textures/block,sliced.png,atlas.png,0,0,4,4,2,2,\n"
                + "/assets/minecraft/textures/block,ignored.png,stone.png,,,,,,,y\n"
                + "/assets/minecraft/textures/block,ignored_slice.png,atlas.png,0,0,4,4,2,2,y\n"
                + "/assets/minecraft/textures/block,unsafe.png,../outside.png,,,,,,,\n"
        );

        ContentPackAdapter.Result first = ContentPackAdapter.prepareVoxeLibre(source, temporary.resolve("store"));
        ContentPackAdapter.Result second = ContentPackAdapter.prepareVoxeLibre(source, temporary.resolve("store"));

        assertEquals(first, second);
        assertEquals(3, first.mappedTextures());
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/block/stone.png")));
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/block/water_still.png.mcmeta")));
        BufferedImage sliced = ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/block/sliced.png").toFile());
        assertEquals(4, sliced.getWidth());
        assertEquals(4, sliced.getHeight());
        assertTrue(Files.notExists(first.resourcePack().resolve("assets/minecraft/textures/block/ignored_slice.png")));
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/font/ascii.png")));
        BufferedImage font = ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/font/ascii.png").toFile());
        assertEquals(256, font.getWidth());
        assertEquals(256, font.getHeight());
        JsonNode fontIndex = new ObjectMapper().readTree(first.resourcePack().resolve("assets/minecraft/font/default.json").toFile());
        assertEquals(16, fontIndex.at("/providers/0/chars").size());
        assertEquals(" !\"#$%&'()*+,-./", fontIndex.at("/providers/0/chars/2").asText());
        assertEquals("pqrstuvwxyz{|}~\u0000", fontIndex.at("/providers/0/chars/7").asText());
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/gui/sprites/hud/crosshair.png")));
        assertEquals(
            23,
            ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png").toFile()).getHeight()
        );
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/gui/sprites/hud/heart/full.png")));
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/gui/sprites/widget/button.png")));
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/blockstates/stone.json")));
        assertEquals("fixture media license\n", Files.readString(first.resourcePack().resolve("LEGAL.md")));
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("provenance.json")));
        assertTrue(first.resourcePack().startsWith(temporary.resolve("store/content-providers/voxelibre")));

        Files.writeString(textures.resolve("stone.png"), "changed after publication");
        assertTrue(ImageIO.read(first.resourcePack().resolve("assets/minecraft/textures/block/stone.png").toFile()) != null);
    }

    private static void writePng(Path target, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        assertTrue(ImageIO.write(image, "png", target.toFile()));
    }

    private static void writeGuiFixtures(Path textures) throws Exception {
        writePng(textures.resolve("mcl_inventory_hotbar.png"), 182, 22);
        writePng(textures.resolve("mcl_inventory_hotbar_selected.png"), 24, 24);
        for (String name : new String[] {
            "heart.png", "hudbars_bgicon_health.png", "hbarmor_icon.png", "hbarmor_bgicon.png",
            "bubble.png", "hbhunger_icon.png", "hbhunger_bgicon.png", "mcl_hunger_icon_foodpoison.png"
        }) writePng(textures.resolve(name), 9, 9);
        writePng(textures.resolve("mcl_experience_bar_background.png"), 5, 182);
        writePng(textures.resolve("mcl_experience_bar.png"), 5, 182);
        writePng(textures.resolve("mcl_base_textures_button9.png"), 6, 6);
        writePng(textures.resolve("mcl_base_textures_button9_pressed.png"), 6, 6);
    }
}
