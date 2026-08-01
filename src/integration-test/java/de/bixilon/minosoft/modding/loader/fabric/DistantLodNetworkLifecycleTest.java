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
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness;
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalColumn;
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage;
import de.bixilon.minosoft.terrain.distant.network.DistantProtocolWorld;
import de.bixilon.minosoft.terrain.distant.network.DistantResponseRejection;
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
    public void v2HelloBeforePlayableWorldJoinSurvivesStateReplacement() {
        final PlaySession session = session();
        final DistantHorizonsLodController controller = new DistantHorizonsLodController();
        try {
            final DistantTerrainMessageV2.Hello hello = new DistantTerrainMessageV2.Hello(
                world(session, 7L),
                32,
                256,
                0
            );
            controller.onNetworkPayload(new FabricClientPayloadContext(
                session,
                DistantLodProtocol.INSTANCE.getCHANNEL(),
                DistantTerrainProtocolV2.INSTANCE.encode(hello)
            ));

            controller.onWorldJoined(new FabricWorldEventContext(
                session,
                null,
                new FabricWorldIdentity(session.getWorld().getDimension(), session.getWorld().getName()),
                FabricWorldChangeCause.INITIALIZE,
                1L
            ));

            final DistantLodNetworkClient.Inspection inspection =
                controller.networkInspection$de_bixilon_minosoft_minosoft(session);
            assertEquals(inspection.getProtocolVersion(), 2);
            assertEquals(inspection.getServerMaximumPages(), 32);
            assertEquals(inspection.getServerMaximumRadius(), 256);
        } finally {
            controller.close();
        }
    }

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

    @Test
    public void staleResponseIsRejectedWithoutPublicationOrConnectionFailure() {
        final PlaySession session = session();
        final List<DistantTerrainMessageV2> sent = new ArrayList<>();
        final DistantLodNetworkClient client = new DistantLodNetworkClient(
            session,
            DistantHorizonsOptions.Companion.inMemory(),
            ignored -> false,
            ignored -> 10L,
            ignored -> {
                throw new AssertionError("unexpected v1 publication");
            },
            (tile, page) -> {
                throw new AssertionError("stale v2 page published");
            },
            payload -> {
                sent.add(DistantTerrainProtocolV2.INSTANCE.decode(payload));
                return Unit.INSTANCE;
            }
        );
        final DistantProtocolWorld world = world(session, 19L);
        client.receive(new DistantTerrainMessageV2.Hello(world, 4, 32, 0));
        client.run();
        final DistantTerrainMessageV2.Request request = (DistantTerrainMessageV2.Request) sent.getFirst();
        final DistantVerticalPage stale = new DistantVerticalPage(
            request.getPages().getFirst().getKey(),
            1,
            0,
            1L,
            DistantSourceCompleteness.COMPLETE,
            List.of(new DistantVerticalColumn(List.of()))
        );

        client.receive(new DistantTerrainMessageV2.Response(world, request.getRequestId(), List.of(stale)));

        final DistantLodNetworkClient.Inspection inspection = client.inspect();
        assertEquals(inspection.getResponseRejections().get(DistantResponseRejection.STALE_PAGE), Long.valueOf(1L));
        assertTrue(inspection.getOutstandingRequestIds().isEmpty());
        assertEquals(inspection.getPendingPages(), 0);
        assertEquals(inspection.getCancellationsSent(), 1L);
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
