/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local, allocation-bounded operation metrics for the debug control plane.
 *
 * <p>Series count and bucket count are both capped. The histogram stores counters
 * only; it never retains individual observations.</p>
 */
public final class DebugMetrics {
    static final long[] LATENCY_BUCKET_NANOS = {
        100_000L, 250_000L, 500_000L, 1_000_000L, 2_500_000L, 5_000_000L,
        10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L,
        500_000_000L, 1_000_000_000L, 2_500_000_000L, 5_000_000_000L
    };
    static final int MAX_SERIES = 256;
    private static final String OVERFLOW = "_overflow";

    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final ConcurrentHashMap<String, Series> operations = new ConcurrentHashMap<>();

    public void record(String operation, long elapsedNanos, String outcome) {
        Series series = operations.get(operation);
        if (series == null) {
            synchronized (operations) {
                series = operations.get(operation);
                if (series == null) {
                    String key = operations.size() >= MAX_SERIES - 1 ? OVERFLOW : operation;
                    series = operations.computeIfAbsent(key, ignored -> new Series());
                }
            }
        }
        series.record(Math.max(0L, elapsedNanos), outcome);
    }

    public ObjectNode snapshot(DebugEndpointDescriptor endpoint) {
        ObjectNode result = DebugJson.MAPPER.createObjectNode();
        result.put("schema", 1);
        result.put("endpointId", endpoint.getId());
        result.put("role", endpoint.getRole().wireName());
        result.put("generation", endpoint.getGeneration());
        result.put("startedAt", startedAt.toString());
        result.put("uptimeNanos", Math.max(0L, System.nanoTime() - startedNanos));
        result.put("maxSeries", MAX_SERIES);
        ArrayNode bounds = result.putArray("latencyBucketUpperBoundsNanos");
        for (long bound : LATENCY_BUCKET_NANOS) bounds.add(bound);

        ArrayNode series = result.putArray("operations");
        List<String> names = new ArrayList<>(operations.keySet());
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            Series value = operations.get(name);
            if (value == null) continue;
            ObjectNode item = series.addObject();
            item.put("operation", name);
            item.put("count", value.count.sum());
            item.put("success", value.success.sum());
            item.put("error", value.error.sum());
            item.put("timeout", value.timeout.sum());
            item.put("cancelled", value.cancelled.sum());
            item.put("totalNanos", value.totalNanos.sum());
            item.put("maxNanos", Math.max(0L, value.maxNanos.get()));
            ArrayNode buckets = item.putArray("latencyBuckets");
            for (LongAdder bucket : value.buckets) buckets.add(bucket.sum());
        }
        return result;
    }

    private static final class Series {
        private final LongAdder count = new LongAdder();
        private final LongAdder success = new LongAdder();
        private final LongAdder error = new LongAdder();
        private final LongAdder timeout = new LongAdder();
        private final LongAdder cancelled = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final LongAccumulator maxNanos = new LongAccumulator(Long::max, 0L);
        private final LongAdder[] buckets;

        private Series() {
            buckets = new LongAdder[LATENCY_BUCKET_NANOS.length + 1];
            for (int index = 0; index < buckets.length; index++) buckets[index] = new LongAdder();
        }

        private void record(long nanos, String outcome) {
            count.increment();
            totalNanos.add(nanos);
            maxNanos.accumulate(nanos);
            if ("success".equals(outcome)) success.increment();
            else if ("timeout".equals(outcome)) timeout.increment();
            else if ("cancelled".equals(outcome)) cancelled.increment();
            else error.increment();

            int bucket = 0;
            while (bucket < LATENCY_BUCKET_NANOS.length && nanos > LATENCY_BUCKET_NANOS[bucket]) bucket++;
            buckets[bucket].increment();
        }
    }
}
