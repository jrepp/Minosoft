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

package de.bixilon.minosoft.modding.loader.fabric;

import de.bixilon.minosoft.data.registries.identified.ResourceLocation;
import de.bixilon.minosoft.protocol.network.session.play.PlaySession;
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil;
import de.bixilon.minosoft.terrain.distant.network.DistantProtocolWorld;
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2;
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainProtocolV2;
import kotlin.Unit;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public final class DistantLodNetworkLifecycleTest {
    @Test
    public void worldRetirementSendsExactCorrelatedCancellationAndClearsTheLedger() {
        final PlaySession session = session();
        final List<DistantTerrainMessageV2> sent = new ArrayList<>();
        final DistantLodNetworkClient client = client(session, sent);
        final DistantProtocolWorld world = world(session, 7L);
        client.receive(new DistantTerrainMessageV2.Hello(world, 4, 32, 0));

        client.run();
        assertEquals(sent.size(), 1);
        final DistantTerrainMessageV2.Request request = (DistantTerrainMessageV2.Request) sent.getFirst();
        assertTrue(!request.getPages().isEmpty());
        assertEquals(client.inspect().getOutstandingRequestIds(), List.of(request.getRequestId()));

        client.close(true);

        final DistantTerrainMessageV2.Cancel cancellation = (DistantTerrainMessageV2.Cancel) sent.getLast();
        assertEquals(cancellation.getWorld(), request.getWorld());
        assertEquals(cancellation.getRequestId(), request.getRequestId());
        final DistantLodNetworkClient.Inspection inspection = client.inspect();
        assertTrue(inspection.getOutstandingRequestIds().isEmpty());
        assertEquals(inspection.getPendingPages(), 0);
        assertEquals(inspection.getCancellationsSent(), 1L);
        assertEquals(inspection.getCancellationFailures(), 0L);
    }

    @Test
    public void renegotiationCancelsThePreviousLedgerBeforeAcceptingTheNewEpoch() {
        final PlaySession session = session();
        final List<DistantTerrainMessageV2> sent = new ArrayList<>();
        final DistantLodNetworkClient client = client(session, sent);
        final DistantProtocolWorld firstWorld = world(session, 11L);
        client.receive(new DistantTerrainMessageV2.Hello(firstWorld, 4, 32, 0));
        client.run();
        final DistantTerrainMessageV2.Request request = (DistantTerrainMessageV2.Request) sent.getFirst();
        final DistantProtocolWorld secondWorld = new DistantProtocolWorld(
            12L,
            firstWorld.getWorldEpoch(),
            firstWorld.getLevelKey()
        );

        client.receive(new DistantTerrainMessageV2.Hello(secondWorld, 4, 32, 0));

        final DistantTerrainMessageV2.Cancel cancellation = (DistantTerrainMessageV2.Cancel) sent.getLast();
        assertEquals(cancellation.getRequestId(), request.getRequestId());
        assertEquals(cancellation.getWorld(), firstWorld);
        assertTrue(client.inspect().getOutstandingRequestIds().isEmpty());
        assertEquals(client.inspect().getWorldResets(), 1L);
        client.close();
    }

    private static PlaySession session() {
        final PlaySession session = SessionTestUtil.INSTANCE.createSession(0, false, null, null);
        session.getWorld().setName(new ResourceLocation("minecraft", "overworld"));
        return session;
    }

    private static DistantProtocolWorld world(PlaySession session, long connectionEpoch) {
        return new DistantProtocolWorld(
            connectionEpoch,
            session.getWorld().getTerrainEpoch(),
            "minecraft:overworld"
        );
    }

    private static DistantLodNetworkClient client(
        PlaySession session,
        List<DistantTerrainMessageV2> sent
    ) {
        return new DistantLodNetworkClient(
            session,
            DistantHorizonsOptions.Companion.inMemory(),
            ignored -> false,
            ignored -> null,
            ignored -> {
                throw new AssertionError("unexpected v1 publication");
            },
            (tile, page) -> {
                throw new AssertionError("unexpected v2 publication");
            },
            payload -> {
                sent.add(DistantTerrainProtocolV2.INSTANCE.decode(payload));
                return Unit.INSTANCE;
            }
        );
    }
}
