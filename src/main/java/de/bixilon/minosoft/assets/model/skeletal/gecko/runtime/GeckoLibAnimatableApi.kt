/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.runtime.SkeletalPose
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * Typed equivalent of GeckoLib's DataTicket. Identity includes the expected
 * JVM type so two owners cannot reinterpret the same key incompatibly.
 */
data class GeckoLibDataTicket<T : Any>(
    val identifier: ResourceLocation,
    val type: Class<T>,
)

data class GeckoLibAnimatableManagerSnapshot(
    val controllers: GeckoLibControllerSetSnapshot,
    val data: Map<GeckoLibDataTicket<*>, Any>,
    val lastUpdateTimeSeconds: Double?,
) {
    init {
        require(lastUpdateTimeSeconds == null || lastUpdateTimeSeconds.isFinite() && lastUpdateTimeSeconds >= 0.0) {
            "GeckoLib animatable snapshot update time must be finite and non-negative."
        }
    }
}

/**
 * Headless manager for one generic GeckoLib animatable instance.
 *
 * When constructed by [GeckoLibControllerBindingRegistry], every operation
 * that can invoke adapted-mod callbacks crosses the registration's quiescent
 * boundary. Closing either side prevents further callback invocation.
 */
class GeckoLibAnimatableManager internal constructor(
    private val controllers: GeckoLibControllerSet,
    private val binding: GeckoLibControllerBindingRegistry.Binding? = null,
) : AutoCloseable {
    private val data = linkedMapOf<GeckoLibDataTicket<*>, Any>()
    @Volatile private var closed = false

    @Volatile var lastUpdateTimeSeconds: Double? = null
        private set

    val firstTick get() = lastUpdateTimeSeconds == null
    val active get() = !closed && (binding?.active != false)

    constructor(
        clips: Map<String, de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip>,
        definitions: List<GeckoLibControllerDefinition>,
    ) : this(GeckoLibControllerSet(clips, definitions))

    @Synchronized
    fun update(
        deltaSeconds: Float,
        state: GeckoLibAnimationState,
        random: () -> Double = { 0.0 },
    ): SkeletalPose {
        check(!closed) { "GeckoLib animatable manager is closed." }
        val pose = invokeCallbacks { controllers.update(deltaSeconds, state, random) }
            ?: return SkeletalPose(emptyMap())
        lastUpdateTimeSeconds = state.ageSeconds.toDouble()
        return pose
    }

    @Synchronized
    fun trigger(animation: String): Boolean {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return invokeCallbacks { controllers.trigger(animation) } ?: false
    }

    @Synchronized
    fun trigger(controller: String, animation: String): Boolean {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return invokeCallbacks { controllers.trigger(controller, animation) } ?: false
    }

    @Synchronized
    fun play(
        controller: String,
        animation: GeckoLibRawAnimation,
        transitionSeconds: Float? = null,
        restart: Boolean = false,
    ): Boolean {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return invokeCallbacks {
            controllers.play(controller, animation, transitionSeconds, restart)
        } ?: false
    }

    @Synchronized
    fun current(controller: String): String? {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return controllers.current(controller)
    }

    @Synchronized
    fun currentRawAnimation(controller: String): GeckoLibRawAnimation? {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return controllers.currentRawAnimation(controller)
    }

    @Synchronized
    fun isPlayingTriggeredAnimation(controller: String): Boolean {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return controllers.isPlayingTriggeredAnimation(controller)
    }

    @Synchronized
    fun hasAnimationFinished(controller: String): Boolean {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return controllers.hasAnimationFinished(controller)
    }

    @Synchronized
    fun resetCurrentAnimation(controller: String): Boolean {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return controllers.resetCurrentAnimation(controller)
    }

    @get:Synchronized
    val controllerNames: List<String>
        get() {
            check(!closed) { "GeckoLib animatable manager is closed." }
            return controllers.controllerNames
        }

    @Synchronized
    fun setEventConsumer(consumer: ((String, SkeletalAnimationEvent) -> Unit)?) {
        check(!closed) { "GeckoLib animatable manager is closed." }
        controllers.eventConsumer = consumer
    }

    @Synchronized
    fun <T : Any> setData(ticket: GeckoLibDataTicket<T>, value: T) {
        check(!closed) { "GeckoLib animatable manager is closed." }
        require(ticket.type.accepts(value)) {
            "GeckoLib data '${ticket.identifier}' requires ${ticket.type.name}, got ${value.javaClass.name}."
        }
        require(ticket in data || data.size < MAX_DATA_POINTS) {
            "GeckoLib animatable manager exceeds the $MAX_DATA_POINTS data-point limit."
        }
        data[ticket] = value
    }

    @Synchronized
    fun <T : Any> getData(ticket: GeckoLibDataTicket<T>): T? {
        check(!closed) { "GeckoLib animatable manager is closed." }
        val value = data[ticket] ?: return null
        require(ticket.type.accepts(value)) {
            "GeckoLib data '${ticket.identifier}' does not match ${ticket.type.name}."
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Synchronized
    fun removeData(ticket: GeckoLibDataTicket<*>): Any? {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return data.remove(ticket)
    }

    @Synchronized
    fun snapshot(): GeckoLibAnimatableManagerSnapshot {
        check(!closed) { "GeckoLib animatable manager is closed." }
        return GeckoLibAnimatableManagerSnapshot(
            controllers = controllers.snapshot(),
            data = data.toMap(),
            lastUpdateTimeSeconds = lastUpdateTimeSeconds,
        )
    }

    @Synchronized
    fun restore(snapshot: GeckoLibAnimatableManagerSnapshot): Int {
        check(!closed) { "GeckoLib animatable manager is closed." }
        require(snapshot.data.size <= MAX_DATA_POINTS) {
            "GeckoLib animatable snapshot exceeds the $MAX_DATA_POINTS data-point limit."
        }
        for ((ticket, value) in snapshot.data) {
            require(ticket.type.accepts(value)) {
                "GeckoLib snapshot data '${ticket.identifier}' does not match ${ticket.type.name}."
            }
        }
        val restored = controllers.restore(snapshot.controllers)
        data.clear()
        data.putAll(snapshot.data)
        lastUpdateTimeSeconds = snapshot.lastUpdateTimeSeconds
        return restored
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        controllers.eventConsumer = null
        data.clear()
        lastUpdateTimeSeconds = null
    }

    private fun <T> invokeCallbacks(action: () -> T): T? {
        val binding = binding ?: return action()
        return binding.invoke(action)
    }

    private companion object {
        const val MAX_DATA_POINTS = 4_096
    }
}

fun interface GeckoLibAnimatableManagerFactory {
    fun create(instanceId: Long): GeckoLibAnimatableManager
}

/**
 * Mirrors GeckoLib's instanced cache: every requested ID resolves to the same
 * manager because the animatable object itself owns the animation state.
 */
class GeckoLibInstancedAnimatableInstanceCache(
    factory: GeckoLibAnimatableManagerFactory,
) : AutoCloseable {
    private val manager = factory.create(0L)
    private var closed = false

    @Synchronized
    fun managerForId(@Suppress("UNUSED_PARAMETER") instanceId: Long): GeckoLibAnimatableManager {
        check(!closed) { "GeckoLib instanced animatable cache is closed." }
        return manager
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        manager.close()
    }
}

/**
 * Mirrors GeckoLib's singleton cache while imposing a bounded access-order LRU
 * so arbitrary item/object instance IDs cannot retain a generation forever.
 */
class GeckoLibSingletonAnimatableInstanceCache(
    private val factory: GeckoLibAnimatableManagerFactory,
    private val capacity: Int = DEFAULT_CAPACITY,
) : AutoCloseable {
    private val managers = LinkedHashMap<Long, GeckoLibAnimatableManager>(16, 0.75f, true)
    private var closed = false

    init {
        require(capacity > 0) { "GeckoLib singleton animatable cache capacity must be positive." }
    }

    fun managerForId(instanceId: Long): GeckoLibAnimatableManager {
        synchronized(this) {
            check(!closed) { "GeckoLib singleton animatable cache is closed." }
            managers[instanceId]?.let { return it }
        }

        val candidate = factory.create(instanceId)
        var existing: GeckoLibAnimatableManager? = null
        var evicted: GeckoLibAnimatableManager? = null
        var rejected = false
        synchronized(this) {
            if (closed) {
                rejected = true
            } else {
                existing = managers[instanceId]
                if (existing == null) {
                    if (managers.size >= capacity) {
                        val eldest = managers.entries.iterator()
                        if (eldest.hasNext()) {
                            evicted = eldest.next().value
                            eldest.remove()
                        }
                    }
                    managers[instanceId] = candidate
                }
            }
        }

        if (rejected) {
            candidate.close()
            throw IllegalStateException("GeckoLib singleton animatable cache is closed.")
        }
        existing?.let {
            candidate.close()
            return it
        }
        evicted?.close()
        return candidate
    }

    @Synchronized
    fun remove(instanceId: Long): Boolean {
        check(!closed) { "GeckoLib singleton animatable cache is closed." }
        val manager = managers.remove(instanceId) ?: return false
        manager.close()
        return true
    }

    @get:Synchronized
    val size get() = managers.size

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        managers.values.forEach(GeckoLibAnimatableManager::close)
        managers.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_048
    }
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun Class<*>.accepts(value: Any): Boolean {
    val expected = when (this) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        else -> this
    }
    return expected.isInstance(value)
}
