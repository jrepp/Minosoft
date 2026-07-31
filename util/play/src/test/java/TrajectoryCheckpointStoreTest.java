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

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrajectoryCheckpointStoreTest {
    @TempDir
    Path temporary;

    @Test
    void markEstablishesCompareAndRestoreBoundary() throws IOException {
        Path path = temporary.resolve("checkpoint.json");
        TrajectoryCheckpointStore store = store();
        store.create(path, "graphics", endpoint(1), pose(1.0, 10.0));

        ObjectNode marked = store.mark(path, endpoint(2), pose(2.0, 20.0));

        assertEquals(2, marked.path("clientEndpoint").path("generation").asInt());
        assertTrue(store.matchesExpected(marked, pose(2.005, 20.04)));
        assertFalse(store.matchesExpected(marked, pose(2.02, 20.0)));
        assertFalse(store.matchesExpected(marked, pose(2.0, 20.06)));
    }

    @Test
    void restoredAndConflictedCheckpointsCannotBeReused() throws IOException {
        TrajectoryCheckpointStore store = store();
        Path restored = temporary.resolve("restored.json");
        store.create(restored, "graphics", endpoint(1), pose(1.0, 10.0));
        assertEquals("restored", store.recordRestored(restored, pose(1.0, 10.0)).path("state").asText());
        assertThrows(IllegalStateException.class, () -> store.mark(restored, endpoint(2), pose(2.0, 20.0)));

        Path conflicted = temporary.resolve("conflicted.json");
        store.create(conflicted, "graphics", endpoint(1), pose(1.0, 10.0));
        assertEquals("conflict", store.recordConflict(conflicted, pose(3.0, 30.0)).path("state").asText());
        assertThrows(IllegalStateException.class, () -> store.recordRestored(conflicted, pose(1.0, 10.0)));
    }

    @Test
    void createNeverOverwritesAnExistingCheckpoint() throws IOException {
        Path path = temporary.resolve("checkpoint.json");
        TrajectoryCheckpointStore store = store();
        store.create(path, "graphics", endpoint(1), pose(1.0, 10.0));

        assertThrows(IOException.class, () -> store.create(path, "other", endpoint(2), pose(2.0, 20.0)));
        assertEquals("graphics", store.read(path).path("trajectory").asText());
    }

    private static ObjectNode endpoint(int generation) {
        return DebugJson.MAPPER.createObjectNode()
            .put("id", "client-" + generation)
            .put("generation", generation);
    }

    private static ObjectNode pose(double x, double yaw) {
        return DebugJson.MAPPER.createObjectNode()
            .put("dimension", "minecraft:overworld")
            .put("x", x)
            .put("y", 64.0)
            .put("z", -2.0)
            .put("yaw", yaw)
            .put("pitch", 4.0);
    }

    private static TrajectoryCheckpointStore store() {
        return new TrajectoryCheckpointStore(
            Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC)
        );
    }
}
