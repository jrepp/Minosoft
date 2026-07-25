/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEventType
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class GeckoLibRuntimeEventContext(
    val animation: String,
    val event: SkeletalAnimationEvent,
    val position: Vec3d,
    val entityId: Int? = null,
    val entityUuid: UUID? = null,
)

fun interface GeckoLibRuntimeEventListener {
    fun onEvent(context: GeckoLibRuntimeEventContext)
}

/**
 * Owner-scoped source API for adapted mods that consume GeckoLib timeline
 * instructions or need to observe sound/particle keyframes. Closing the
 * registration removes it without retaining the adapter generation.
 */
object GeckoLibRuntimeEvents {
    private data class Registration(
        val id: Long,
        val owner: String,
        val listener: QuiescentCallback<GeckoLibRuntimeEventListener>,
    )

    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<Long, Registration>()

    @Synchronized
    fun register(owner: String, listener: GeckoLibRuntimeEventListener): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib runtime event owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib runtime events exceed the $MAX_REGISTRATIONS registration limit."
        }
        val registration = Registration(nextId.incrementAndGet(), owner, QuiescentCallback(listener))
        registrations[registration.id] = registration
        return AutoCloseable {
            val removed = synchronized(this) {
                registrations.remove(registration.id, registration)
            }
            if (removed) registration.listener.close()
        }
    }

    @Synchronized
    fun owners(): List<String> = registrations.values.map(Registration::owner)

    fun dispatch(context: GeckoLibRuntimeEventContext) {
        val listeners = synchronized(this) { registrations.values.toList() }
        for (registration in listeners) {
            try {
                registration.listener.invoke { it.onEvent(context) }
            } catch (error: Throwable) {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                    "GECKOLIB_EVENT_FAILED owner=${registration.owner} type=${context.event.type} error=${error::class.java.simpleName}: ${error.message}"
                }
            }
        }
    }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_REGISTRATIONS = 1_024
}

interface GeckoLibEventPlaybackTarget {
    fun playSound(context: GeckoLibRuntimeEventContext, sound: ResourceLocation)
    fun spawnParticle(context: GeckoLibRuntimeEventContext, particle: ResourceLocation)
    fun customInstruction(context: GeckoLibRuntimeEventContext)
    fun rejected(context: GeckoLibRuntimeEventContext, reason: String)
}

/**
 * Headless routing and validation boundary. Platform targets decide how to
 * resolve actual sounds, particles, and custom instruction callbacks.
 */
object GeckoLibEventPlayback {
    fun dispatch(context: GeckoLibRuntimeEventContext, target: GeckoLibEventPlaybackTarget): Boolean {
        when (context.event.type) {
            SkeletalAnimationEventType.SOUND -> {
                val identifier = effect(context, target) ?: return false
                target.playSound(context, identifier)
            }
            SkeletalAnimationEventType.PARTICLE -> {
                val identifier = effect(context, target) ?: return false
                target.spawnParticle(context, identifier)
            }
            SkeletalAnimationEventType.CUSTOM_INSTRUCTION -> target.customInstruction(context)
        }
        return true
    }

    private fun effect(
        context: GeckoLibRuntimeEventContext,
        target: GeckoLibEventPlaybackTarget,
    ): ResourceLocation? {
        val value = context.event.payload.trim()
        if (!EFFECT.matches(value)) {
            target.rejected(context, "Invalid or missing effect resource location '${context.event.payload}'.")
            return null
        }
        return try {
            ResourceLocation.of(value)
        } catch (error: IllegalArgumentException) {
            target.rejected(context, error.message ?: "Invalid effect resource location.")
            null
        }
    }

    private val EFFECT = Regex("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+")
}
