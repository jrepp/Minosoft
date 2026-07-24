/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugMetricsTest {
    @Test
    void boundsSeriesAndObservations() {
        DebugMetrics metrics = new DebugMetrics();
        for (int index = 0; index < DebugMetrics.MAX_SERIES + 50; index++) {
            metrics.record("operation." + index, index * 100_000L, index % 2 == 0 ? "success" : "error");
        }
        DebugEndpointDescriptor endpoint = new DebugEndpointDescriptor(
            DebugEndpointDescriptor.SCHEMA, "client-1-1-test", DebugEndpointRole.CLIENT, 1,
            Instant.EPOCH, "test", 1, "unix", "/tmp/test", "../credentials/test.token", 1, 1
        );

        JsonNode snapshot = metrics.snapshot(endpoint);
        assertTrue(snapshot.path("operations").size() <= DebugMetrics.MAX_SERIES);
        assertEquals(DebugMetrics.LATENCY_BUCKET_NANOS.length, snapshot.path("latencyBucketUpperBoundsNanos").size());
        boolean overflow = false;
        for (JsonNode series : snapshot.path("operations")) {
            assertEquals(DebugMetrics.LATENCY_BUCKET_NANOS.length + 1, series.path("latencyBuckets").size());
            long buckets = 0;
            for (JsonNode bucket : series.path("latencyBuckets")) buckets += bucket.asLong();
            assertEquals(series.path("count").asLong(), buckets);
            if (series.path("operation").asText().equals("_overflow")) overflow = true;
        }
        assertTrue(overflow);
    }
}
