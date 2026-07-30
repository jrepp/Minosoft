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

sealed interface GeckoLibRuntimeEffectResolution {
    data object PassThrough : GeckoLibRuntimeEffectResolution
    data object Ignore : GeckoLibRuntimeEffectResolution
    data class Play(val effect: ResourceLocation) : GeckoLibRuntimeEffectResolution
}

fun interface GeckoLibRuntimeEffectResolver {
    fun resolve(context: GeckoLibRuntimeEventContext): GeckoLibRuntimeEffectResolution
}

/**
 * Owner-scoped equivalent of a dependent mod's GeckoLib keyframe handler.
 *
 * Gecko animation JSON stores handler-defined aliases, not necessarily
 * resource locations. A consumer binds the exact registration generation when
 * its retained model instance is created. Closing or replacing the adapter
 * makes that old binding inert instead of invoking a new generation.
 */
object GeckoLibRuntimeEffectRegistry {
    class Binding internal constructor(
        private val identity: SkeletalContentIdentity,
        private val registration: QuiescentCallback<GeckoLibRuntimeEffectResolver>,
    ) {
        fun resolve(context: GeckoLibRuntimeEventContext): GeckoLibRuntimeEffectResolution {
            if (context.contentIdentity != identity) return GeckoLibRuntimeEffectResolution.Ignore
            return registration.invoke { it.resolve(context) }
                ?: GeckoLibRuntimeEffectResolution.Ignore
        }
    }

    private data class Registration(
        val owner: String,
        val resolver: QuiescentCallback<GeckoLibRuntimeEffectResolver>,
    )

    private val registrations = linkedMapOf<SkeletalContentIdentity, Registration>()

    @Synchronized
    fun register(
        owner: String,
        identity: SkeletalContentIdentity,
        resolver: GeckoLibRuntimeEffectResolver,
    ): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib runtime-effect owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        require(identity.format == SkeletalContentFormat.GECKOLIB) {
            "GeckoLib runtime effects require a GeckoLib content identity."
        }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib runtime-effect registrations exceed the $MAX_REGISTRATIONS limit."
        }
        require(identity !in registrations) {
            "GeckoLib runtime effects for $identity are already registered."
        }
        val registration = Registration(owner, QuiescentCallback(resolver))
        registrations[identity] = registration
        return AutoCloseable {
            val removed = synchronized(this) { registrations.remove(identity, registration) }
            if (removed) registration.resolver.close()
        }
    }

    internal fun bind(identity: SkeletalContentIdentity): Binding? {
        val registration = synchronized(this) { registrations[identity] } ?: return null
        return Binding(identity, registration.resolver)
    }

    @Synchronized
    fun owners(): Map<SkeletalContentIdentity, String> = registrations.mapValues { it.value.owner }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_REGISTRATIONS = 4_096
}
