/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.system.base.query

data class GpuPassTiming(
    val samples: Long,
    val lastNanos: Long,
    val totalNanos: Long,
    val minimumNanos: Long,
    val maximumNanos: Long,
    val medianUpperBoundNanos: Long,
    val p95UpperBoundNanos: Long,
    val bucketUpperBoundsNanos: LongArray,
    val buckets: LongArray,
)

data class GpuTimingDiagnostics(
    val sampleRate: Int = 1,
    val pendingQueries: Int = 0,
    val droppedSamples: Long = 0,
    val passes: Map<String, GpuPassTiming> = emptyMap(),
)
