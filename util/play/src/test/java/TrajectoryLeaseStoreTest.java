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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrajectoryLeaseStoreTest {
    @TempDir
    Path temporary;

    @Test
    void clientLeasesConflictOnlyWithinTheirTrajectory() throws IOException {
        TrajectoryLeaseStore leases = store(Instant.parse("2026-07-30T12:00:00Z"));
        ObjectNode first = leases.acquire("client", "graphics-a", Duration.ofMinutes(20), "first");
        ObjectNode second = leases.acquire("client", "graphics-b", Duration.ofMinutes(20), "second");

        assertTrue(leases.owns(first.path("token").asText(), "client", "graphics-a"));
        assertTrue(leases.owns(second.path("token").asText(), "client", "graphics-b"));
        assertThrows(
            IllegalStateException.class,
            () -> leases.acquire("client", "graphics-a", Duration.ofMinutes(20), "conflict")
        );
    }

    @Test
    void sharedScopesConflictAcrossTrajectoriesAndReleaseByToken() throws IOException {
        TrajectoryLeaseStore leases = store(Instant.parse("2026-07-30T12:00:00Z"));
        ObjectNode first = leases.acquire("server-world", "world-a", Duration.ofMinutes(20), "first");
        String token = first.path("token").asText();

        assertThrows(
            IllegalStateException.class,
            () -> leases.acquire("server-world", "world-b", Duration.ofMinutes(20), "conflict")
        );
        assertTrue(leases.release(token).path("released").asBoolean());
        assertFalse(leases.owns(token, "server-world", "world-a"));
        assertEquals(0, leases.status().path("leases").size());
    }

    @Test
    void expiredLeasesAreRemovedBeforeConflictChecks() throws IOException {
        TrajectoryLeaseStore initial = store(Instant.parse("2026-07-30T12:00:00Z"));
        initial.acquire("source", "audit-a", Duration.ofMinutes(1), "initial");

        TrajectoryLeaseStore later = store(Instant.parse("2026-07-30T12:02:00Z"));
        ObjectNode replacement = later.acquire("source", "audit-b", Duration.ofMinutes(1), "replacement");

        assertEquals("audit-b", replacement.path("trajectory").asText());
        assertEquals(1, later.status().path("leases").size());
    }

    private TrajectoryLeaseStore store(Instant now) {
        return new TrajectoryLeaseStore(temporary, Clock.fixed(now, ZoneOffset.UTC));
    }
}
