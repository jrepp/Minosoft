/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.util.concurrent.atomic.AtomicLong

data class GeckoLibControllerBindingContext(
    val identity: SkeletalContentIdentity,
    val animations: Map<String, SkeletalAnimationClip>,
)

fun interface GeckoLibControllerBindingFactory {
    fun create(context: GeckoLibControllerBindingContext): List<GeckoLibControllerDefinition>
}

fun interface GeckoLibRenderLayerPredicate {
    fun visible(state: GeckoLibAnimationState): Boolean
}

enum class GeckoLibRenderLayerBlend {
    OPAQUE,
    TRANSLUCENT,
    ADDITIVE,
}

data class GeckoLibRenderLayerDefinition(
    val name: String,
    val texture: ResourceLocation,
    val blend: GeckoLibRenderLayerBlend = GeckoLibRenderLayerBlend.TRANSLUCENT,
    val fullBright: Boolean = false,
    val predicate: GeckoLibRenderLayerPredicate = GeckoLibRenderLayerPredicate { true },
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) {
            "GeckoLib render-layer name must contain 1..$MAX_NAME_LENGTH characters."
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 256
    }
}

/**
 * Connects an adapted mod's source-native controller definitions to a stable
 * Gecko geometry identity. Closing a registration immediately disables its
 * callbacks for existing instances and prevents new bindings.
 */
object GeckoLibControllerBindingRegistry {
    internal class Binding internal constructor(
        private val registration: QuiescentCallback<GeckoLibControllerBindingFactory>,
        val definitions: List<GeckoLibControllerDefinition>,
    ) {
        val active get() = registration.isActive

        fun <R> invoke(action: () -> R): R? = registration.invoke { action() }
    }

    private class Registration(
        val id: Long,
        val owner: String,
        val identity: SkeletalContentIdentity,
        val factory: QuiescentCallback<GeckoLibControllerBindingFactory>,
    )

    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<SkeletalContentIdentity, Registration>()

    @Synchronized
    fun register(
        owner: String,
        identity: SkeletalContentIdentity,
        factory: GeckoLibControllerBindingFactory,
    ): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib controller-binding owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        require(identity.format == SkeletalContentFormat.GECKOLIB) {
            "GeckoLib controller bindings require a GeckoLib content identity."
        }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib controller bindings exceed the $MAX_REGISTRATIONS registration limit."
        }
        require(identity !in registrations) {
            "GeckoLib controller binding for $identity is already registered."
        }

        val registration = Registration(nextId.incrementAndGet(), owner, identity, QuiescentCallback(factory))
        registrations[identity] = registration
        return AutoCloseable {
            val removed = synchronized(this) { registrations.remove(identity, registration) }
            if (removed) registration.factory.close()
        }
    }

    internal fun bind(
        identity: SkeletalContentIdentity,
        animations: Map<String, SkeletalAnimationClip>,
    ): Binding? {
        val registration = synchronized(this) { registrations[identity] } ?: return null
        val definitions = registration.factory.invoke {
            it.create(GeckoLibControllerBindingContext(identity, animations.toMap()))
        } ?: return null
        require(definitions.isNotEmpty()) {
            "GeckoLib controller binding for $identity did not create any controllers."
        }
        return Binding(registration.factory, definitions.toList())
    }

    @Synchronized
    fun owners(): Map<SkeletalContentIdentity, String> = registrations.mapValues { it.value.owner }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_REGISTRATIONS = 4_096
}

enum class GeckoLibModelTarget {
    ENTITY,
    BLOCK_ENTITY,
    ITEM,
    ARMOR,
}

data class GeckoLibModelRoute(
    val target: GeckoLibModelTarget,
    val identifier: ResourceLocation,
)

/**
 * Routes a source-game render target to one adapted GeckoLib geometry
 * identity. The lookup remains dynamic: registrations never retain a content
 * generation, and renderer instances acquire their own generation lease from
 * the resolved baked model.
 */
object GeckoLibModelRouteRegistry {
    private data class Registration(
        val owner: String,
        val identity: SkeletalContentIdentity,
    )

    private val registrations = linkedMapOf<GeckoLibModelRoute, Registration>()

    @Synchronized
    fun register(
        owner: String,
        target: GeckoLibModelTarget,
        identifier: ResourceLocation,
        identity: SkeletalContentIdentity,
    ): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib model-route owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        require(identity.format == SkeletalContentFormat.GECKOLIB) {
            "GeckoLib model routes require a GeckoLib content identity."
        }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib model routes exceed the $MAX_REGISTRATIONS registration limit."
        }
        val route = GeckoLibModelRoute(target, identifier)
        require(route !in registrations) {
            "GeckoLib $target model route for $identifier is already registered."
        }
        val registration = Registration(owner, identity)
        registrations[route] = registration
        return AutoCloseable {
            synchronized(this) {
                registrations.remove(route, registration)
            }
        }
    }

    @Synchronized
    fun identity(target: GeckoLibModelTarget, identifier: ResourceLocation): SkeletalContentIdentity? =
        registrations[GeckoLibModelRoute(target, identifier)]?.identity

    @Synchronized
    fun owners(): Map<GeckoLibModelRoute, String> = registrations.mapValues { it.value.owner }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_REGISTRATIONS = 4_096
}

object GeckoLibEntityModelRegistry {
    fun register(owner: String, entity: ResourceLocation, identity: SkeletalContentIdentity) =
        GeckoLibModelRouteRegistry.register(owner, GeckoLibModelTarget.ENTITY, entity, identity)

    fun identity(entity: ResourceLocation) =
        GeckoLibModelRouteRegistry.identity(GeckoLibModelTarget.ENTITY, entity)

    fun owners(): Map<ResourceLocation, String> = GeckoLibModelRouteRegistry.owners()
        .filterKeys { it.target == GeckoLibModelTarget.ENTITY }
        .mapKeys { it.key.identifier }
}

object GeckoLibBlockEntityModelRegistry {
    fun register(owner: String, block: ResourceLocation, identity: SkeletalContentIdentity) =
        GeckoLibModelRouteRegistry.register(owner, GeckoLibModelTarget.BLOCK_ENTITY, block, identity)

    fun identity(block: ResourceLocation) =
        GeckoLibModelRouteRegistry.identity(GeckoLibModelTarget.BLOCK_ENTITY, block)

    fun owners(): Map<ResourceLocation, String> = GeckoLibModelRouteRegistry.owners()
        .filterKeys { it.target == GeckoLibModelTarget.BLOCK_ENTITY }
        .mapKeys { it.key.identifier }
}

object GeckoLibItemModelRegistry {
    fun register(owner: String, item: ResourceLocation, identity: SkeletalContentIdentity) =
        GeckoLibModelRouteRegistry.register(owner, GeckoLibModelTarget.ITEM, item, identity)

    fun identity(item: ResourceLocation) =
        GeckoLibModelRouteRegistry.identity(GeckoLibModelTarget.ITEM, item)

    fun owners(): Map<ResourceLocation, String> = GeckoLibModelRouteRegistry.owners()
        .filterKeys { it.target == GeckoLibModelTarget.ITEM }
        .mapKeys { it.key.identifier }
}

object GeckoLibArmorModelRegistry {
    fun register(owner: String, item: ResourceLocation, identity: SkeletalContentIdentity) =
        GeckoLibModelRouteRegistry.register(owner, GeckoLibModelTarget.ARMOR, item, identity)

    fun identity(item: ResourceLocation) =
        GeckoLibModelRouteRegistry.identity(GeckoLibModelTarget.ARMOR, item)

    fun owners(): Map<ResourceLocation, String> = GeckoLibModelRouteRegistry.owners()
        .filterKeys { it.target == GeckoLibModelTarget.ARMOR }
        .mapKeys { it.key.identifier }
}

/**
 * Declares extra full-model texture passes for one Gecko geometry identity.
 * Meshes are generation-owned; closing the registration immediately prevents
 * retained instances from invoking predicates or drawing its passes.
 */
object GeckoLibRenderLayerRegistry {
    internal class Binding internal constructor(
        val registrationId: Long,
        private val registration: QuiescentCallback<Map<String, GeckoLibRenderLayerDefinition>>,
        val definitions: Map<String, GeckoLibRenderLayerDefinition>,
    ) {
        val active get() = registration.isActive

        fun visible(name: String, state: GeckoLibAnimationState): GeckoLibRenderLayerDefinition? =
            registration.invoke { definitions ->
                definitions[name]?.takeIf { it.predicate.visible(state) }
            }
    }

    private class Registration(
        val id: Long,
        val owner: String,
        val definitions: QuiescentCallback<Map<String, GeckoLibRenderLayerDefinition>>,
    )

    internal data class Snapshot(
        val registrationId: Long,
        val definitions: List<GeckoLibRenderLayerDefinition>,
    )

    private val nextId = AtomicLong()
    private val registrations = linkedMapOf<SkeletalContentIdentity, Registration>()

    @Synchronized
    fun register(
        owner: String,
        identity: SkeletalContentIdentity,
        definitions: List<GeckoLibRenderLayerDefinition>,
    ): AutoCloseable {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "GeckoLib render-layer owner must contain 1..$MAX_OWNER_LENGTH characters."
        }
        require(identity.format == SkeletalContentFormat.GECKOLIB) {
            "GeckoLib render layers require a GeckoLib content identity."
        }
        require(definitions.isNotEmpty() && definitions.size <= MAX_LAYERS) {
            "GeckoLib render-layer registration must contain 1..$MAX_LAYERS layers."
        }
        require(definitions.map { it.name }.distinct().size == definitions.size) {
            "GeckoLib render-layer names must be unique for $identity."
        }
        require(registrations.size < MAX_REGISTRATIONS) {
            "GeckoLib render layers exceed the $MAX_REGISTRATIONS registration limit."
        }
        require(identity !in registrations) {
            "GeckoLib render layers for $identity are already registered."
        }
        val registration = Registration(
            nextId.incrementAndGet(),
            owner,
            QuiescentCallback(definitions.associateBy { it.name }),
        )
        registrations[identity] = registration
        return AutoCloseable {
            val removed = synchronized(this) { registrations.remove(identity, registration) }
            if (removed) registration.definitions.close()
        }
    }

    internal fun bind(identity: SkeletalContentIdentity): Binding? {
        val registration = synchronized(this) { registrations[identity] } ?: return null
        if (!registration.definitions.isActive) return null
        return Binding(registration.id, registration.definitions, registration.definitions.value)
    }

    internal fun snapshot(identity: SkeletalContentIdentity): Snapshot? {
        val registration = synchronized(this) { registrations[identity] } ?: return null
        return registration.definitions.invoke {
            Snapshot(registration.id, it.values.toList())
        }
    }

    @Synchronized
    fun owners(): Map<SkeletalContentIdentity, String> = registrations.mapValues { it.value.owner }

    private const val MAX_OWNER_LENGTH = 256
    private const val MAX_LAYERS = 256
    private const val MAX_REGISTRATIONS = 4_096
}
