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

import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalAnimationEvaluator
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalEasingResolver
import java.util.concurrent.atomic.AtomicLong

fun interface GeckoLibEasing {
    fun transform(value: Double, argument: Double?): Double
}

/**
 * Lifecycle-safe source-native equivalent of GeckoLib's custom easing map.
 * Registrations are owner-scoped so an adapted mod or content generation can
 * remove only its own transformer during unload.
 */
object GeckoLibEasingRegistry : SkeletalEasingResolver {
    private data class Registration(
        val id: Long,
        val owner: String,
        val name: String,
        val easing: QuiescentCallback<GeckoLibEasing>,
    )

    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<String, Registration>()

    @Synchronized
    fun register(owner: String, name: String, easing: GeckoLibEasing): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib easing owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        val key = name.lowercase()
        require(key.length <= MAX_NAME_LENGTH && NAME.matches(key)) {
            "GeckoLib easing name '$name' must be a lowercase namespaced-safe identifier."
        }
        require(!SkeletalAnimationEvaluator.isBuiltInEasing(key)) {
            "GeckoLib built-in easing '$name' cannot be replaced."
        }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib custom easings exceed the $MAX_REGISTRATIONS registration limit."
        }
        require(key !in registrations) { "GeckoLib easing '$name' is already registered." }

        val registration = Registration(nextId.incrementAndGet(), owner, key, QuiescentCallback(easing))
        registrations[key] = registration
        return AutoCloseable {
            val removed = synchronized(this) {
                registrations.remove(key, registration)
            }
            if (removed) registration.easing.close()
        }
    }

    override fun transform(name: String, value: Float, arguments: List<Float>): Float? {
        val registration = synchronized(this) { registrations[name.lowercase()] } ?: return null
        val transformed = registration.easing.invoke {
            it.transform(value.toDouble(), arguments.firstOrNull()?.toDouble())
        } ?: return null
        require(transformed.isFinite()) {
            "GeckoLib easing '${registration.name}' owned by '${registration.owner}' returned a non-finite value."
        }
        return transformed.toFloat().also {
            require(it.isFinite()) {
                "GeckoLib easing '${registration.name}' owned by '${registration.owner}' exceeds the finite float range."
            }
        }
    }

    @Synchronized
    fun owners(): Map<String, String> = registrations.mapValues { it.value.owner }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_NAME_LENGTH = 256
    private const val MAX_REGISTRATIONS = 4_096
    private val NAME = Regex("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]{1,256}")
}
