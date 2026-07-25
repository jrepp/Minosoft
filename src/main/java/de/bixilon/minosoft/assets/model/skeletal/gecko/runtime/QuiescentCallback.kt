/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Prevents a removed owner callback from starting again and lets external
 * close callers wait for callbacks that already crossed the invocation edge.
 */
internal class QuiescentCallback<T>(val value: T) {
    private val lock = ReentrantLock()
    private val quiescent = lock.newCondition()
    private val callers = linkedMapOf<Thread, Int>()
    private var active = true
    private var inFlight = 0

    val isActive: Boolean get() = lock.withLock { active }

    fun <R> invoke(action: (T) -> R): R? {
        val thread = Thread.currentThread()
        lock.withLock {
            if (!active) return null
            inFlight++
            callers[thread] = (callers[thread] ?: 0) + 1
        }
        try {
            return action(value)
        } finally {
            lock.withLock {
                inFlight--
                val depth = requireNotNull(callers[thread]) - 1
                if (depth == 0) callers.remove(thread) else callers[thread] = depth
                quiescent.signalAll()
            }
        }
    }

    fun close() {
        var interrupted = false
        lock.withLock {
            active = false
            val ownCalls = callers[Thread.currentThread()] ?: 0
            while (inFlight > ownCalls) {
                try {
                    quiescent.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
