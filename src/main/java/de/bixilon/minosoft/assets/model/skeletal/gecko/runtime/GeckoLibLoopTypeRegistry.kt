/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import java.util.concurrent.atomic.AtomicLong

enum class GeckoLibLoopDecision {
    ADVANCE,
    REPEAT,
    HOLD,
}

data class GeckoLibLoopContext(
    val controller: String,
    val animation: String,
    val state: GeckoLibAnimationState,
    val completedCycles: Int,
)

fun interface GeckoLibLoopType {
    fun shouldPlayAgain(context: GeckoLibLoopContext): GeckoLibLoopDecision
}

/**
 * Lifecycle-safe source-native equivalent of GeckoLib's global LoopType map.
 *
 * GeckoLib's callback can repeat a clip or mutate its controller into a
 * paused state. The native contract represents those outcomes explicitly as
 * REPEAT and HOLD so adapted code does not need access to controller internals.
 */
object GeckoLibLoopTypeRegistry {
    private data class Registration(
        val id: Long,
        val owner: String,
        val name: String,
        val loopType: QuiescentCallback<GeckoLibLoopType>,
    )

    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<String, Registration>()

    @Synchronized
    fun register(owner: String, name: String, loopType: GeckoLibLoopType): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib loop-type owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        val key = name.lowercase()
        require(key.length <= MAX_NAME_LENGTH && NAME.matches(key)) {
            "GeckoLib loop-type name '$name' must be a lowercase namespaced-safe identifier."
        }
        require(key !in BUILT_INS) { "GeckoLib built-in loop type '$name' cannot be replaced." }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib custom loop types exceed the $MAX_REGISTRATIONS registration limit."
        }
        require(key !in registrations) { "GeckoLib loop type '$name' is already registered." }

        val registration = Registration(nextId.incrementAndGet(), owner, key, QuiescentCallback(loopType))
        registrations[key] = registration
        return AutoCloseable {
            val removed = synchronized(this) {
                registrations.remove(key, registration)
            }
            if (removed) registration.loopType.close()
        }
    }

    fun decide(name: String, context: GeckoLibLoopContext): GeckoLibLoopDecision? {
        val registration = synchronized(this) { registrations[name.lowercase()] } ?: return null
        return registration.loopType.invoke { it.shouldPlayAgain(context) }
    }

    @Synchronized
    fun contains(name: String): Boolean = name.lowercase() in registrations

    @Synchronized
    fun owners(): Map<String, String> = registrations.mapValues { it.value.owner }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_NAME_LENGTH = 256
    private const val MAX_REGISTRATIONS = 4_096
    private val NAME = Regex("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]{1,256}")
    private val BUILT_INS = setOf("default", "false", "play_once", "hold_on_last_frame", "true", "loop")
}
