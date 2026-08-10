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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentPackComposerTest {
    @TempDir
    Path temporary;

    @Test
    void laterDirectoryArchiveAndModSourcesWin() throws Exception {
        Path loose = Files.createDirectories(temporary.resolve("loose/assets/demo/textures"));
        Files.writeString(loose.resolve("shared.txt"), "loose");
        Path pack = archive(temporary.resolve("pack.zip"), "assets/demo/textures/shared.txt", "pack");
        Path mods = Files.createDirectories(temporary.resolve("mods"));
        archive(mods.resolve("a.jar"), "assets/demo/textures/shared.txt", "mod-a");
        archive(mods.resolve("z.jar"), "assets/demo/textures/shared.txt", "mod-z");

        List<ContentPackComposer.Source> sources = List.of(
            new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, temporary.resolve("loose"), "loose"),
            new ContentPackComposer.Source(ContentPackComposer.SourceType.ARCHIVE, pack, "pack"),
            new ContentPackComposer.Source(ContentPackComposer.SourceType.MODS, mods, "mods")
        );
        ContentPackComposer.Result first = ContentPackComposer.compose(sources, temporary.resolve("store"), "fixture");
        ContentPackComposer.Result second = ContentPackComposer.compose(sources, temporary.resolve("store"), "fixture");

        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals("mod-z", Files.readString(first.resourcePack().resolve("assets/demo/textures/shared.txt")));
        assertEquals(4, first.sources().size());
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("pack.mcmeta")));
        assertTrue(Files.readString(first.resourcePack().resolve("provenance.json")).contains("later files replace earlier files"));
    }

    @Test
    void archiveTraversalIsRejected() throws Exception {
        Path archive = archive(temporary.resolve("unsafe.zip"), "assets/../../outside.txt", "unsafe");
        List<ContentPackComposer.Source> sources = List.of(
            new ContentPackComposer.Source(ContentPackComposer.SourceType.ARCHIVE, archive, "unsafe")
        );

        assertThrows(IllegalArgumentException.class, () -> ContentPackComposer.compose(sources, temporary.resolve("store"), "unsafe"));
        assertTrue(Files.notExists(temporary.resolve("store/content-stacks/unsafe/outside.txt")));
    }

    @Test
    void sourceNoticesAccompanyComposedAssetsWithoutCopyingUnrelatedRootFiles() throws Exception {
        Path loose = Files.createDirectories(temporary.resolve("loose/assets/demo"));
        Files.writeString(loose.resolve("loose.txt"), "loose");
        Files.writeString(temporary.resolve("loose/LEGAL.md"), "loose notice\n");
        Path faithful = archive(
            temporary.resolve("faithful.zip"),
            Map.of(
                "assets/minecraft/textures/font/ascii.png", "font",
                "LICENSE.txt", "faithful notice\n",
                "README.txt", "not a required notice\n"
            )
        );

        ContentPackComposer.Result result = ContentPackComposer.compose(
            List.of(
                new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, temporary.resolve("loose"), "loose"),
                new ContentPackComposer.Source(ContentPackComposer.SourceType.ARCHIVE, faithful, "faithful")
            ),
            temporary.resolve("store"),
            "notices"
        );

        assertEquals("loose notice\n", Files.readString(result.resourcePack().resolve("third-party-notices/000-loose/LEGAL.md")));
        assertEquals("faithful notice\n", Files.readString(result.resourcePack().resolve("third-party-notices/001-faithful/LICENSE.txt")));
        assertFalse(Files.exists(result.resourcePack().resolve("third-party-notices/001-faithful/README.txt")));
    }

    @Test
    void explicitAttributionNoticeDoesNotRequireAnAssetTree() throws Exception {
        Path notice = Files.createDirectories(temporary.resolve("notices")).resolve("NOTICE.md");
        Files.writeString(notice, "Open content attribution\n");
        Path pack = archive(temporary.resolve("pack.zip"), "assets/demo/value.txt", "value");

        ContentPackComposer.Result result = ContentPackComposer.compose(
            List.of(
                new ContentPackComposer.Source(ContentPackComposer.SourceType.NOTICE, notice, "attribution"),
                new ContentPackComposer.Source(ContentPackComposer.SourceType.ARCHIVE, pack, "pack")
            ),
            temporary.resolve("store"),
            "explicit-notice"
        );

        assertEquals(
            "Open content attribution\n",
            Files.readString(result.resourcePack().resolve("third-party-notices/000-attribution/NOTICE.md"))
        );
    }

    @Test
    void ignoresMacOsAndEditorMetadataSoCompositionStaysDeterministic() throws Exception {
        Path loose = Files.createDirectories(temporary.resolve("loose/assets/minecraft/textures/block"));
        writePng(loose.resolve("stone.png"), 16, 16);
        Files.writeString(loose.resolve("stone.png.mcmeta"), "{\"animation\":{\"frames\":[0]}}\n");
        Files.writeString(temporary.resolve("loose/assets/.DS_Store"), "metadata");
        Files.writeString(temporary.resolve("loose/assets/._quarantine"), "metadata");
        Files.writeString(temporary.resolve("loose/assets/backup~"), "metadata");
        Path pack = archive(temporary.resolve("pack.zip"), "assets/minecraft/textures/archived.txt", "archived");

        List<ContentPackComposer.Source> sources = List.of(
            new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, temporary.resolve("loose"), "loose"),
            new ContentPackComposer.Source(ContentPackComposer.SourceType.ARCHIVE, pack, "pack")
        );
        ContentPackComposer.Result first = ContentPackComposer.compose(sources, temporary.resolve("store-a"), "metadata");
        ContentPackComposer.Result second = ContentPackComposer.compose(sources, temporary.resolve("store-b"), "metadata");

        assertEquals(first.fingerprint(), second.fingerprint());
        assertFalse(Files.exists(first.resourcePack().resolve("assets/.DS_Store")));
        assertFalse(Files.exists(first.resourcePack().resolve("assets/._quarantine")));
        assertFalse(Files.exists(first.resourcePack().resolve("assets/backup~")));
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/block/stone.png")));
        assertTrue(Files.isRegularFile(first.resourcePack().resolve("assets/minecraft/textures/archived.txt")));
    }

    @Test
    void removesStaleAnimationMetadataAfterAHigherPriorityStaticTextureWins() throws Exception {
        Path lower = Files.createDirectories(temporary.resolve("lower/assets/minecraft/textures/block"));
        writePng(lower.resolve("prismarine.png"), 16, 64);
        Files.writeString(lower.resolve("prismarine.png.mcmeta"), "{\"animation\":{\"frames\":[0,1,2,3]}}\n");
        Path higher = Files.createDirectories(temporary.resolve("higher/assets/minecraft/textures/block"));
        writePng(higher.resolve("prismarine.png"), 16, 16);

        ContentPackComposer.Result result = ContentPackComposer.compose(
            List.of(
                new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, temporary.resolve("lower"), "lower"),
                new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, temporary.resolve("higher"), "higher")
            ),
            temporary.resolve("store"),
            "animation-sidecars"
        );

        assertTrue(Files.isRegularFile(result.resourcePack().resolve("assets/minecraft/textures/block/prismarine.png")));
        assertTrue(Files.notExists(result.resourcePack().resolve("assets/minecraft/textures/block/prismarine.png.mcmeta")));
    }

    @Test
    void archiveOverrideReplacesCopiedSourceWithoutCorruptingIt() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source/assets/minecraft/textures/item"));
        Files.writeString(source.resolve("item.png"), "generated-placeholder");
        Path higher = archive(temporary.resolve("higher.zip"), "assets/minecraft/textures/item/item.png", "authored-override");

        List<ContentPackComposer.Source> sources = List.of(
            new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, temporary.resolve("source"), "generated"),
            new ContentPackComposer.Source(ContentPackComposer.SourceType.ARCHIVE, higher, "higher")
        );
        ContentPackComposer.Result result = ContentPackComposer.compose(sources, temporary.resolve("store"), "copy-override");

        assertEquals("authored-override", Files.readString(result.resourcePack().resolve("assets/minecraft/textures/item/item.png")));
        assertEquals("generated-placeholder", Files.readString(source.resolve("item.png")));
    }

    @Test
    void publishedPackDoesNotChangeWhenADirectorySourceIsMutated() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("mutable/assets/demo"));
        Path input = source.resolve("value.txt");
        Files.writeString(input, "original");

        ContentPackComposer.Result result = ContentPackComposer.compose(
            List.of(new ContentPackComposer.Source(
                ContentPackComposer.SourceType.DIRECTORY,
                temporary.resolve("mutable"),
                "mutable"
            )),
            temporary.resolve("store"),
            "immutable-output"
        );
        Files.writeString(input, "changed after publication");

        assertEquals("original", Files.readString(result.resourcePack().resolve("assets/demo/value.txt")));
    }

    @Test
    void fingerprintDoesNotDependOnTheAbsoluteSourceLocation() throws Exception {
        Path first = Files.createDirectories(temporary.resolve("first/assets/demo"));
        Path second = Files.createDirectories(temporary.resolve("second/assets/demo"));
        Files.writeString(first.resolve("value.txt"), "identical");
        Files.writeString(second.resolve("value.txt"), "identical");

        ContentPackComposer.Result firstResult = ContentPackComposer.compose(
            List.of(new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, first.getParent(), "source")),
            temporary.resolve("first-store"),
            "relocatable"
        );
        ContentPackComposer.Result secondResult = ContentPackComposer.compose(
            List.of(new ContentPackComposer.Source(ContentPackComposer.SourceType.DIRECTORY, second.getParent(), "source")),
            temporary.resolve("second-store"),
            "relocatable"
        );

        assertEquals(firstResult.fingerprint(), secondResult.fingerprint());
    }

    private static void writePng(Path path, int width, int height) throws Exception {
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", path.toFile()));
    }

    private static Path archive(Path path, String name, String value) throws Exception {
        return archive(path, Map.of(name, value));
    }

    private static Path archive(Path path, Map<String, String> entries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes());
                output.closeEntry();
            }
        }
        return path;
    }
}
