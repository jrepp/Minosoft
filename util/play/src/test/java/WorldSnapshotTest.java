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
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class WorldSnapshotTest {
    @TempDir
    Path temporary;

    @Test
    void stoppedWorldIsCopiedAndHashedBeforePublication() throws IOException {
        Path world = temporary.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("region/r.0.0.mca"), "region");
        Path output = temporary.resolve("snapshots/run-1");

        ObjectNode manifest = new WorldSnapshot().create(world, output, "worldgen-a");

        assertEquals(2, manifest.path("files").asInt());
        assertEquals(11L, manifest.path("bytes").asLong());
        assertEquals(64, manifest.path("treeSha256").asText().length());
        assertEquals("region", Files.readString(output.resolve("region/r.0.0.mca")));
        JsonNode written = DebugJson.MAPPER.readTree(output.resolve("snapshot-manifest.json").toFile());
        assertEquals("worldgen-a", written.path("trajectory").asText());
        assertEquals(manifest.path("treeSha256"), written.path("treeSha256"));
    }

    @Test
    void snapshotRejectsExistingOrNestedTargets() throws IOException {
        Path world = temporary.resolve("world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "level");
        Path existing = temporary.resolve("existing");
        Files.createDirectories(existing);

        assertThrows(IllegalArgumentException.class, () -> new WorldSnapshot().create(world, existing, "test"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorldSnapshot().create(world, world.resolve("snapshot"), "test")
        );
        assertTrue(Files.isRegularFile(world.resolve("level.dat")));
    }

    @Test
    void snapshotRejectsTargetRoutedIntoWorldThroughSymlink() throws IOException {
        Path world = temporary.resolve("world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "level");
        Path alias = temporary.resolve("world-alias");
        try {
            Files.createSymbolicLink(alias, world);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "symbolic links are unavailable: " + unavailable.getMessage());
        }

        assertThrows(
            IllegalArgumentException.class,
            () -> new WorldSnapshot().create(world, alias.resolve("snapshot"), "test")
        );
        assertTrue(Files.isRegularFile(world.resolve("level.dat")));
    }

    @Test
    void snapshotDoesNotReplaceDanglingOutputSymlink() throws IOException {
        Path world = temporary.resolve("world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "level");
        Path output = temporary.resolve("snapshot");
        try {
            Files.createSymbolicLink(output, temporary.resolve("missing"));
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "symbolic links are unavailable: " + unavailable.getMessage());
        }

        assertThrows(
            IllegalArgumentException.class,
            () -> new WorldSnapshot().create(world, output, "test")
        );
        assertTrue(Files.isSymbolicLink(output));
    }
}
