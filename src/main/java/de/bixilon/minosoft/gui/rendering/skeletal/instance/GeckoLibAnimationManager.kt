/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.instance

import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEvent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationState
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerSet
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerSetSnapshot
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerRegistry
import kotlin.time.Duration

data class GeckoLibAnimationManagerSnapshot(
    val identity: SkeletalContentIdentity,
    val controllers: GeckoLibControllerSetSnapshot,
)

class GeckoLibAnimationManager(private val instance: SkeletalInstance) {
    private val binding = instance.model.contentIdentity
        ?.takeIf { it.format == SkeletalContentFormat.GECKOLIB }
        ?.let { GeckoLibControllerBindingRegistry.bind(it, instance.model.neutralAnimations) }
    private val renderLayerBinding = instance.model.contentIdentity
        ?.takeIf { it.format == SkeletalContentFormat.GECKOLIB }
        ?.let(GeckoLibRenderLayerRegistry::bind)
    val controllers = binding?.let { GeckoLibControllerSet(instance.model.neutralAnimations, it.definitions) }
    private val transforms = instance.transform.index()
    private val pendingEvents = mutableListOf<PendingEvent>()
    private var state = GeckoLibAnimationState(0.0f)
    var eventConsumer: ((String, SkeletalAnimationEvent) -> Unit)? = null

    val active get() = binding?.active == true && controllers != null

    init {
        controllers?.eventConsumer = collect@{ animation, event ->
            if (eventConsumer == null) return@collect
            require(pendingEvents.size < MAX_PENDING_EVENTS) {
                "Retained GeckoLib event queue exceeds the $MAX_PENDING_EVENTS event limit."
            }
            pendingEvents += PendingEvent(animation, event)
        }
    }

    fun draw(delta: Duration, state: GeckoLibAnimationState) {
        this.state = state
        val controllers = controllers
        if (!active || controllers == null) {
            pendingEvents.clear()
            return
        }
        val pose = binding?.invoke {
            controllers.update(
                deltaSeconds = delta.inWholeNanoseconds / 1_000_000_000.0f,
                state = state,
            )
        } ?: run {
            pendingEvents.clear()
            return
        }
        pose.apply(transforms)
    }

    fun updateState(state: GeckoLibAnimationState) {
        this.state = state
    }

    fun snapshot(): GeckoLibAnimationManagerSnapshot? {
        val identity = instance.model.contentIdentity ?: return null
        val controllers = controllers ?: return null
        if (!active) return null
        return GeckoLibAnimationManagerSnapshot(identity, controllers.snapshot())
    }

    fun restore(snapshot: GeckoLibAnimationManagerSnapshot): Boolean {
        if (!active || instance.model.contentIdentity != snapshot.identity) return false
        val controllers = controllers ?: return false
        return controllers.restore(snapshot.controllers) > 0
    }

    fun renderLayer(name: String): GeckoLibRenderLayerDefinition? {
        val binding = renderLayerBinding
        if (binding?.active != true) return null
        return binding.visible(name, state)
    }

    internal fun renderLayer(name: String, registrationId: Long): GeckoLibRenderLayerDefinition? {
        val binding = renderLayerBinding
        if (binding?.active != true || binding.registrationId != registrationId) return null
        return renderLayer(name)
    }

    fun dispatchEvents() {
        if (!active || pendingEvents.isEmpty()) {
            pendingEvents.clear()
            return
        }
        val consumer = eventConsumer
        try {
            if (consumer != null) {
                for ((animation, event) in pendingEvents) consumer(animation, event)
            }
        } finally {
            pendingEvents.clear()
        }
    }

    fun clearEvents() {
        pendingEvents.clear()
        eventConsumer = null
        controllers?.eventConsumer = null
    }

    private data class PendingEvent(
        val animation: String,
        val event: SkeletalAnimationEvent,
    )

    private companion object {
        const val MAX_PENDING_EVENTS = 16_384
    }
}
