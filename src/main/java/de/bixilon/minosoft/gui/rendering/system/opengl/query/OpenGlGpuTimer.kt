/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.system.opengl.query

import de.bixilon.kutil.primitive.BooleanUtil.toBoolean
import de.bixilon.minosoft.gui.rendering.system.base.query.GpuPassTiming
import de.bixilon.minosoft.gui.rendering.system.base.query.GpuTimingDiagnostics
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE
import org.lwjgl.opengl.GL15.glBeginQuery
import org.lwjgl.opengl.GL15.glDeleteQueries
import org.lwjgl.opengl.GL15.glEndQuery
import org.lwjgl.opengl.GL15.glGenQueries
import org.lwjgl.opengl.GL15.glGetQueryObjecti
import org.lwjgl.opengl.GL33.GL_TIME_ELAPSED
import org.lwjgl.opengl.GL33.glGetQueryObjectui64
import java.util.ArrayDeque

/** Bounded, non-blocking GL_TIME_ELAPSED query pool owned by one render context. */
internal class OpenGlGpuTimer(
    private val system: OpenGlRenderSystem,
    private val maximumPending: Int = 256,
    private val sampleRate: Int = 4,
    private val maximumPasses: Int = 512,
) : AutoCloseable {
    init {
        require(maximumPending > 0)
        require(sampleRate > 0)
        require(maximumPasses > 0)
    }

    private data class Pending(val name: String, val query: Int)

    private class Histogram {
        var samples = 0L
        var last = 0L
        var total = 0L
        var minimum = Long.MAX_VALUE
        var maximum = 0L
        val buckets = LongArray(BOUNDS.size)

        fun record(nanos: Long) {
            samples++
            last = nanos
            total = Math.addExact(total, nanos)
            minimum = minOf(minimum, nanos)
            maximum = maxOf(maximum, nanos)
            val bucket = BOUNDS.indexOfFirst { nanos <= it }.let { if (it < 0) BOUNDS.lastIndex else it }
            buckets[bucket]++
        }

        fun snapshot() = GpuPassTiming(
            samples = samples,
            lastNanos = last,
            totalNanos = total,
            minimumNanos = if (samples == 0L) 0L else minimum,
            maximumNanos = maximum,
            medianUpperBoundNanos = percentileBound(0.50),
            p95UpperBoundNanos = percentileBound(0.95),
            bucketUpperBoundsNanos = BOUNDS.copyOf(),
            buckets = buckets.copyOf(),
        )

        private fun percentileBound(percentile: Double): Long {
            if (samples == 0L) return 0L
            val target = kotlin.math.ceil(samples * percentile).toLong()
            var cumulative = 0L
            buckets.forEachIndexed { index, count ->
                cumulative += count
                if (cumulative >= target) return BOUNDS[index]
            }
            return BOUNDS.last()
        }
    }

    private val pending = ArrayDeque<Pending>()
    private val available = ArrayDeque<Int>()
    private val histograms = linkedMapOf<String, Histogram>()
    private val calls = linkedMapOf<String, Long>()
    private var active = false
    private var dropped = 0L

    fun <T> measure(name: String, action: () -> T): T {
        if (active) return action()
        collectAvailable()
        if (name.isBlank() || name.length > MAXIMUM_PASS_NAME_LENGTH || name !in calls && calls.size >= maximumPasses) {
            dropped = Math.incrementExact(dropped)
            return action()
        }
        val call = Math.incrementExact(calls[name] ?: 0L).also { calls[name] = it }
        val offset = name.hashCode().ushr(1) % sampleRate
        if ((call + offset) % sampleRate != 0L) return action()
        if (pending.size >= maximumPending) {
            dropped = Math.incrementExact(dropped)
            return action()
        }
        val query = acquire()
        try {
            gl { glBeginQuery(GL_TIME_ELAPSED, query) }
        } catch (error: Throwable) {
            discard(query, error)
            throw error
        }
        active = true
        var failure: Throwable? = null
        try {
            return action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            active = false
            try {
                gl { glEndQuery(GL_TIME_ELAPSED) }
                pending.addLast(Pending(name, query))
            } catch (endError: Throwable) {
                discard(query, endError)
                failure?.addSuppressed(endError) ?: throw endError
            }
        }
    }

    fun diagnostics(): GpuTimingDiagnostics {
        collectAvailable()
        return GpuTimingDiagnostics(
            sampleRate = sampleRate,
            pendingQueries = pending.size,
            droppedSamples = dropped,
            passes = histograms.mapValues { it.value.snapshot() },
        )
    }

    private fun collectAvailable() {
        while (true) {
            val next = pending.peekFirst() ?: return
            val ready = gl { glGetQueryObjecti(next.query, GL_QUERY_RESULT_AVAILABLE) }.toBoolean()
            if (!ready) return
            val nanos = gl { glGetQueryObjectui64(next.query, GL_QUERY_RESULT) }
            pending.removeFirst()
            try {
                histograms.getOrPut(next.name, ::Histogram).record(nanos)
            } catch (error: Throwable) {
                available.addLast(next.query)
                throw error
            }
            available.addLast(next.query)
        }
    }

    private fun acquire(): Int {
        available.pollFirst()?.let { return it }
        val query = gl { glGenQueries() }
        try {
            system.resources.created(OpenGlResourceType.QUERY, query)
        } catch (error: Throwable) {
            try {
                gl { glDeleteQueries(query) }
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
        return query
    }

    private fun discard(query: Int, original: Throwable) {
        try {
            delete(query)
        } catch (cleanup: Throwable) {
            original.addSuppressed(cleanup)
        }
    }

    private fun delete(query: Int) {
        gl { glDeleteQueries(query) }
        system.resources.deleted(OpenGlResourceType.QUERY, query)
    }

    override fun close() {
        val queries = buildList {
            pending.forEach { add(it.query) }
            available.forEach(::add)
        }
        var failure: Throwable? = null
        try {
            queries.forEach { query ->
                try {
                    delete(query)
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
        } finally {
            pending.clear()
            available.clear()
            histograms.clear()
            calls.clear()
            active = false
        }
        failure?.let { throw it }
    }

    private companion object {
        const val MAXIMUM_PASS_NAME_LENGTH = 256
        val BOUNDS = longArrayOf(
            100_000L,
            250_000L,
            500_000L,
            1_000_000L,
            2_000_000L,
            4_000_000L,
            8_000_000L,
            16_000_000L,
            33_000_000L,
            66_000_000L,
            Long.MAX_VALUE,
        )
    }
}
