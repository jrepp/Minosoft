/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.util.concurrent.atomic.AtomicLong

class GeckoLibEntityTextureState(
    val entity: ResourceLocation,
    val name: String?,
    val baby: Boolean,
    val aggressive: Boolean,
    private val trackedValue: (Int) -> Any?,
) {
    fun int(index: Int, default: Int = 0): Int = (trackedValue(index) as? Number)?.toInt() ?: default

    fun boolean(index: Int, default: Boolean = false): Boolean = when (val value = trackedValue(index)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> default
    }
}

fun interface GeckoLibEntityTextureSelector {
    fun select(state: GeckoLibEntityTextureState): ResourceLocation
}

data class GeckoLibEntityTextureDefinition(
    val fallback: ResourceLocation,
    val textures: Set<ResourceLocation>,
    val selector: GeckoLibEntityTextureSelector = GeckoLibEntityTextureSelector { fallback },
) {
    init {
        require(textures.isNotEmpty() && textures.size <= MAX_TEXTURES) {
            "GeckoLib entity-texture definitions require 1..$MAX_TEXTURES textures."
        }
        require(fallback in textures) { "GeckoLib entity-texture fallback $fallback is not declared." }
    }

    private companion object {
        const val MAX_TEXTURES = 256
    }
}

/**
 * Owner-scoped source-native equivalent of GeoModel#getTextureResource.
 *
 * A bake snapshot declares every possible texture up front. Runtime selection
 * remains tied to the exact registration ID so a retained old model cannot
 * invoke a replacement generation's selector.
 */
object GeckoLibEntityTextureRegistry {
    internal data class Snapshot(
        val registrationId: Long,
        val fallback: ResourceLocation,
        val textures: Set<ResourceLocation>,
    )

    private data class Registration(
        val id: Long,
        val owner: String,
        val definition: QuiescentCallback<GeckoLibEntityTextureDefinition>,
    )

    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<SkeletalContentIdentity, Registration>()

    @Synchronized
    fun register(
        owner: String,
        identity: SkeletalContentIdentity,
        definition: GeckoLibEntityTextureDefinition,
    ): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib entity-texture owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        require(identity.format == SkeletalContentFormat.GECKOLIB) {
            "GeckoLib entity textures require a GeckoLib content identity."
        }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib entity-texture registrations exceed the $MAX_REGISTRATIONS limit."
        }
        require(identity !in registrations) {
            "GeckoLib entity textures for $identity are already registered."
        }
        val retainedDefinition = definition.copy(textures = definition.textures.toSet())
        val registration = Registration(
            nextId.incrementAndGet(),
            owner,
            QuiescentCallback(retainedDefinition),
        )
        registrations[identity] = registration
        return AutoCloseable {
            val removed = synchronized(this) { registrations.remove(identity, registration) }
            if (removed) registration.definition.close()
        }
    }

    internal fun snapshot(identity: SkeletalContentIdentity): Snapshot? {
        val registration = synchronized(this) { registrations[identity] } ?: return null
        return registration.definition.invoke {
            Snapshot(registration.id, it.fallback, it.textures.toSet())
        }
    }

    fun select(
        identity: SkeletalContentIdentity,
        registrationId: Long,
        state: GeckoLibEntityTextureState,
    ): ResourceLocation? {
        val registration = synchronized(this) { registrations[identity] }
            ?.takeIf { it.id == registrationId }
            ?: return null
        return registration.definition.invoke { definition ->
            definition.selector.select(state).takeIf { it in definition.textures }
                ?: definition.fallback
        }
    }

    @Synchronized
    fun owners(): Map<SkeletalContentIdentity, String> = registrations.mapValues { it.value.owner }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_REGISTRATIONS = 4_096
}
