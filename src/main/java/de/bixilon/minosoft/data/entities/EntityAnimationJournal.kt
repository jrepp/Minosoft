/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.entities

import java.util.ArrayDeque

data class SequencedEntityAnimation(
    val sequence: Long,
    val animation: EntityAnimations,
    val entityAge: Int,
)

data class EntityAnimationBatch(
    val events: List<SequencedEntityAnimation>,
    val latestSequence: Long,
    val overflowed: Boolean,
)

/**
 * Bounded per-entity journal for transient protocol animation events.
 *
 * Render consumers keep independent sequence cursors, so an off-screen or
 * temporarily replaced renderer can catch up without registering callbacks on
 * the network thread. Overflow keeps the newest events and is reported to the
 * reader instead of growing entity lifetime state without a bound.
 */
class EntityAnimationJournal(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val events = ArrayDeque<SequencedEntityAnimation>(capacity)
    private var sequence = 0L

    init {
        require(capacity > 0) { "Entity animation journal capacity must be positive." }
    }

    @Synchronized
    fun record(animation: EntityAnimations, entityAge: Int): Long {
        require(entityAge >= 0) { "Entity animation age must not be negative." }
        check(sequence < Long.MAX_VALUE) { "Entity animation journal sequence exhausted." }
        val event = SequencedEntityAnimation(++sequence, animation, entityAge)
        if (events.size == capacity) events.removeFirst()
        events.addLast(event)
        return event.sequence
    }

    @get:Synchronized
    val latestSequence get() = sequence

    @Synchronized
    fun readAfter(afterSequence: Long): EntityAnimationBatch {
        require(afterSequence in 0..sequence) {
            "Entity animation cursor $afterSequence is outside 0..$sequence."
        }
        val firstRetained = events.peekFirst()?.sequence ?: sequence + 1
        return EntityAnimationBatch(
            events = events.filter { it.sequence > afterSequence },
            latestSequence = sequence,
            overflowed = afterSequence < firstRetained - 1,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
