/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DebugWindowsPipesTest {
    private String address() {
        return "\\\\.\\pipe\\minosoft-test-" + UUID.randomUUID();
    }

    @Test
    void listenerIsConnectableBeforeAcceptStarts() throws Exception {
        String address = address();
        try (DebugTransportListener listener = DebugWindowsPipes.listen(address);
             DebugTransportConnection client = DebugWindowsPipes.connect(address);
             DebugTransportConnection server = listener.accept()) {
            client.output.write(42);
            assertEquals(42, server.input.read());
        }
        assertThrows(IOException.class, () -> DebugWindowsPipes.connect(address));
    }

    @Test
    void closeReleasesBlockedAcceptAndRemovesTheEndpoint() throws Exception {
        String address = address();
        DebugTransportListener listener = DebugWindowsPipes.listen(address);
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "named-pipe-accept-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CountDownLatch started = new CountDownLatch(1);
            var accepted = executor.submit(() -> {
                started.countDown();
                return assertThrows(IOException.class, listener::accept);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> accepted.get(100, TimeUnit.MILLISECONDS));
            listener.close();
            listener.close();
            assertEquals("named debug pipe is closed", accepted.get(2, TimeUnit.SECONDS).getMessage());
            assertFalse(listener.isOpen());
            assertThrows(IOException.class, () -> DebugWindowsPipes.connect(address));
        } finally {
            listener.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeRacingAcceptDoesNotLeaveAPipeInstance() throws Exception {
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "named-pipe-close-race-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int attempt = 0; attempt < 32; attempt++) {
                String address = address();
                try (DebugTransportListener listener = DebugWindowsPipes.listen(address)) {
                    var accepted = executor.submit(() -> assertThrows(IOException.class, listener::accept));
                    listener.close();
                    accepted.get(2, TimeUnit.SECONDS);
                }
                assertThrows(IOException.class, () -> DebugWindowsPipes.connect(address));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void peerCanDrainAResponseAfterTheServerCloses() throws Exception {
        String address = address();
        byte[] response = {1, 2, 3, 4};
        try (DebugTransportListener listener = DebugWindowsPipes.listen(address);
             DebugTransportConnection client = DebugWindowsPipes.connect(address)) {
            try (DebugTransportConnection server = listener.accept()) {
                server.output.write(response);
            }
            assertArrayEquals(response, client.input.readNBytes(response.length));
            assertEquals(-1, client.input.read());
        }
    }
}
