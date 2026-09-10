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
import de.bixilon.minosoft.local.LocalConnection;
import de.bixilon.minosoft.data.world.positions.ChunkPosition;
import de.bixilon.minosoft.protocol.network.session.play.PlaySession;
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil;
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness;
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalColumn;
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage;
import de.bixilon.minosoft.terrain.distant.network.DistantProtocolWorld;
import de.bixilon.minosoft.terrain.distant.network.DistantRequestedPage;
import de.bixilon.minosoft.terrain.distant.network.DistantResponseRejection;
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2;
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainProtocolV2;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public final class DistantLodNetworkLifecycleTest {
    @Test
    public void missingTileInsideNativeViewIsRequestedWithoutDuplicatingResidentOrPendingTiles() {
        final PlaySession session = session();
        // SessionTestUtil starts the player at the origin. Leave (1,1) missing.
        final long gap = (1L << ChunkPosition.SHIFT_Z) | 1L;
        final List<DistantTerrainMessageV2> sent = new ArrayList<>();
        final DistantLodNetworkClient client = new DistantLodNetworkClient(
            session,
            DistantHorizonsOptions.Companion.inMemory(),
            16_384,
            position -> position.getRaw() != gap,
            ignored -> null,
            ignored -> Unit.INSTANCE,
            (tile, page) -> Unit.INSTANCE,
            payload -> {
                sent.add(DistantTerrainProtocolV2.INSTANCE.decode(payload));
                return Unit.INSTANCE;
            }
        );
        try {
            client.receive(new DistantTerrainMessageV2.Hello(world(session, 23L), 32, 32, 0));
            client.run();
            assertEquals(sent.size(), 1, "Missing tile inside the native view square needs LOD coverage");
            final DistantTerrainMessageV2.Request request = (DistantTerrainMessageV2.Request) sent.getFirst();
            assertEquals(request.getPages().size(), 1);
            assertEquals(request.getPages().getFirst().getKey().getX(), 1L);
            assertEquals(request.getPages().getFirst().getKey().getZ(), 1L);
            client.run();
            assertEquals(sent.size(), 1, "Pending gap must not be requested twice");
        } finally {
            client.close();
        }
    }

    @Test
    public void v2HelloBeforePlayableWorldJoinSurvivesStateReplacement() {
        final PlaySession session = session();
        final List<Function0<Unit>> scheduled = new ArrayList<>();
        final DistantHorizonsLodController controller = new DistantHorizonsLodController(
            DistantHorizonsOptions.Companion.inMemory(),
            null,
            task -> {
                task.invoke();
                return Unit.INSTANCE;
            },
            task -> {
                scheduled.add(task);
                return Unit.INSTANCE;
            }
        );
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
            assertEquals(scheduled.size(), 1);

            controller.onWorldJoined(new FabricWorldEventContext(
                session,
                null,
                new FabricWorldIdentity(session.getWorld().getDimension(), session.getWorld().getName()),
                FabricWorldChangeCause.INITIALIZE,
                1L
            ));
            scheduled.getFirst().invoke();

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
    public void persistenceHydrationIsDispatchedAwayFromWorldJoin() throws Exception {
        final PlaySession session = persistentLocalSession();
        final Path persistenceRoot = Files.createTempDirectory("minosoft-dh-hydration-");
        final List<Function0<Unit>> scheduled = new ArrayList<>();
        final DistantHorizonsLodController controller = new DistantHorizonsLodController(
            DistantHorizonsOptions.Companion.inMemory(),
            persistenceRoot,
            task -> {
                scheduled.add(task);
                return Unit.INSTANCE;
            },
            task -> {
                task.invoke();
                return Unit.INSTANCE;
            }
        );
        try {
            controller.onWorldJoined(new FabricWorldEventContext(
                session,
                null,
                new FabricWorldIdentity(session.getWorld().getDimension(), session.getWorld().getName()),
                FabricWorldChangeCause.INITIALIZE,
                1L
            ));

            assertEquals(scheduled.size(), 1);
            assertNull(controller.storeInspection$de_bixilon_minosoft_minosoft(session));

            scheduled.getFirst().invoke();

            assertNotNull(controller.storeInspection$de_bixilon_minosoft_minosoft(session));
        } finally {
            controller.close();
            try (var paths = Files.walk(persistenceRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    public void unfingerprintedRemoteWorldDoesNotDispatchPersistenceHydration() throws Exception {
        final PlaySession session = session();
        final Path persistenceRoot = Files.createTempDirectory("minosoft-dh-remote-persistence-");
        final List<Function0<Unit>> scheduled = new ArrayList<>();
        final DistantHorizonsLodController controller = new DistantHorizonsLodController(
            DistantHorizonsOptions.Companion.inMemory(),
            persistenceRoot,
            task -> {
                scheduled.add(task);
                return Unit.INSTANCE;
            },
            task -> {
                task.invoke();
                return Unit.INSTANCE;
            }
        );
        try {
            controller.onWorldJoined(new FabricWorldEventContext(
                session,
                null,
                new FabricWorldIdentity(session.getWorld().getDimension(), session.getWorld().getName()),
                FabricWorldChangeCause.INITIALIZE,
                1L
            ));

            assertTrue(scheduled.isEmpty());
            assertNull(controller.storeInspection$de_bixilon_minosoft_minosoft(session));
        } finally {
            controller.close();
            try (var paths = Files.walk(persistenceRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test(timeOut = 5_000)
    public void networkPayloadEntryDoesNotAcquireTheSessionStateMonitor() throws Exception {
        final PlaySession session = session();
        final DistantHorizonsLodController controller = new DistantHorizonsLodController(
            DistantHorizonsOptions.Companion.inMemory(),
            null,
            task -> {
                task.invoke();
                return Unit.INSTANCE;
            },
            task -> {
                task.invoke();
                return Unit.INSTANCE;
            }
        );
        try {
            controller.onWorldJoined(new FabricWorldEventContext(
                session,
                null,
                new FabricWorldIdentity(session.getWorld().getDimension(), session.getWorld().getName()),
                FabricWorldChangeCause.INITIALIZE,
                1L
            ));
            final Object state = sessionState(controller, session);
            final DistantTerrainMessageV2.Hello hello = new DistantTerrainMessageV2.Hello(
                world(session, 7L),
                32,
                256,
                0
            );
            final FabricClientPayloadContext payload = new FabricClientPayloadContext(
                session,
                DistantLodProtocol.INSTANCE.getCHANNEL(),
                DistantTerrainProtocolV2.INSTANCE.encode(hello)
            );
            final CountDownLatch completed = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread receiver = new Thread(() -> {
                try {
                    controller.onNetworkPayload(payload);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    completed.countDown();
                }
            }, "dh-network-lock-order-test");

            final boolean completedWhileStateLocked;
            synchronized (state) {
                receiver.start();
                completedWhileStateLocked = completed.await(1, TimeUnit.SECONDS);
            }
            receiver.join();

            assertTrue(completedWhileStateLocked, "Network entry waited for the session-state monitor");
            assertNull(failure.get());
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
    public void fullRemoteWindowSurvivesTheManagedServerDrainInterval() {
        final PlaySession session = session();
        final List<DistantTerrainMessageV2> sent = new ArrayList<>();
        final DistantLodNetworkClient client = client(session, sent);
        final DistantProtocolWorld world = world(session, 9L);
        client.receive(new DistantTerrainMessageV2.Hello(world, 32, 32, 0));

        for (int tick = 0; tick < 20 * 40; tick++) client.run();

        final DistantLodNetworkClient.Inspection inspection = client.inspect();
        assertEquals(inspection.getPendingPages(), 32);
        assertEquals(inspection.getCancellationsSent(), 0L);
        assertFalse(inspection.getOutstandingRequestIds().isEmpty());
        assertTrue(sent.stream().noneMatch(DistantTerrainMessageV2.Cancel.class::isInstance));
        client.close();
    }

    @Test
    public void renegotiationClearsThePreviousLedgerWithoutWritingFromTheReceivePath() {
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

        assertEquals(sent, List.of(request));
        assertTrue(client.inspect().getOutstandingRequestIds().isEmpty());
        assertEquals(client.inspect().getWorldResets(), 1L);
        client.close();
    }

    @Test
    public void staleResponseStreamIsConsumedWithoutPublicationOrUnknownRequestCascade() {
        final PlaySession session = session();
        final List<DistantTerrainMessageV2> sent = new ArrayList<>();
        final DistantLodNetworkClient client = new DistantLodNetworkClient(
            session,
            DistantHorizonsOptions.Companion.inMemory(),
            16_384,
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
        for (DistantRequestedPage requested : request.getPages()) {
            final DistantVerticalPage stale = new DistantVerticalPage(
                requested.getKey(),
                1,
                0,
                1L,
                DistantSourceCompleteness.COMPLETE,
                List.of(new DistantVerticalColumn(List.of()))
            );
            client.receive(new DistantTerrainMessageV2.Response(world, request.getRequestId(), List.of(stale)));
        }

        final DistantLodNetworkClient.Inspection inspection = client.inspect();
        assertEquals(
            inspection.getResponseRejections().get(DistantResponseRejection.STALE_PAGE),
            Long.valueOf(request.getPages().size())
        );
        assertEquals(inspection.getResponseRejections().get(DistantResponseRejection.UNKNOWN_REQUEST), Long.valueOf(0L));
        assertTrue(inspection.getOutstandingRequestIds().isEmpty());
        assertEquals(inspection.getPendingPages(), 0);
        assertEquals(inspection.getCancellationsSent(), 0L);
        assertEquals(sent, List.of(request));
        client.close();
    }

    @Test
    public void acceptedRemotePageIsRekeyedToTheLocalRenderEpoch() {
        final PlaySession session = session();
        final List<DistantTerrainMessageV2> sent = new ArrayList<>();
        final AtomicReference<DistantVerticalPage> published = new AtomicReference<>();
        final DistantLodNetworkClient client = new DistantLodNetworkClient(
            session,
            DistantHorizonsOptions.Companion.inMemory(),
            16_384,
            ignored -> false,
            ignored -> null,
            ignored -> {
                throw new AssertionError("unexpected v1 publication");
            },
            (tile, page) -> {
                published.set(page);
                return Unit.INSTANCE;
            },
            payload -> {
                sent.add(DistantTerrainProtocolV2.INSTANCE.decode(payload));
                return Unit.INSTANCE;
            }
        );
        final long localWorldEpoch = session.getWorld().getTerrainEpoch();
        final DistantProtocolWorld remoteWorld = world(session, 23L, Math.addExact(localWorldEpoch, 41L));
        client.receive(new DistantTerrainMessageV2.Hello(remoteWorld, 4, 32, 0));
        client.run();
        final DistantTerrainMessageV2.Request request = (DistantTerrainMessageV2.Request) sent.getFirst();
        final DistantVerticalPage remotePage = new DistantVerticalPage(
            request.getPages().getFirst().getKey(),
            16,
            0,
            1L,
            DistantSourceCompleteness.COMPLETE,
            Collections.nCopies(256, new DistantVerticalColumn(List.of()))
        );

        client.receive(new DistantTerrainMessageV2.Response(remoteWorld, request.getRequestId(), List.of(remotePage)));

        assertNotNull(published.get());
        assertEquals(published.get().getKey().getWorldEpoch(), localWorldEpoch);
        assertEquals(published.get().getKey().getX(), remotePage.getKey().getX());
        assertEquals(published.get().getKey().getZ(), remotePage.getKey().getZ());
        assertEquals(client.inspect().getReceivedPages(), 1L);
        client.close();
    }

    private static PlaySession session() {
        final PlaySession session = SessionTestUtil.INSTANCE.createSession(0, false, null, null);
        session.getWorld().setName(new ResourceLocation("minecraft", "overworld"));
        return session;
    }

    private static PlaySession persistentLocalSession() throws Exception {
        final PlaySession session = session();
        final LocalConnection connection = new LocalConnection(
            ignored -> {
                throw new AssertionError("local generator must not be opened by this test");
            },
            ignored -> {
                throw new AssertionError("local storage must not be opened by this test");
            },
            "test:stable-world"
        );
        final var connectionField = PlaySession.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(session, connection);
        session.getWorld().updateTerrainPersistenceFingerprint("test:stable-world");
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Object sessionState(DistantHorizonsLodController controller, PlaySession session) throws Exception {
        final var field = DistantHorizonsLodController.class.getDeclaredField("states");
        field.setAccessible(true);
        final IdentityHashMap<PlaySession, Object> states = (IdentityHashMap<PlaySession, Object>) field.get(controller);
        return states.get(session);
    }

    private static DistantProtocolWorld world(PlaySession session, long connectionEpoch) {
        return world(session, connectionEpoch, session.getWorld().getTerrainEpoch());
    }

    private static DistantProtocolWorld world(PlaySession session, long connectionEpoch, long worldEpoch) {
        return new DistantProtocolWorld(
            connectionEpoch,
            worldEpoch,
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
            16_384,
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
