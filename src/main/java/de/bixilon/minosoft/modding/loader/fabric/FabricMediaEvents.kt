/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.particle.types.Particle
import de.bixilon.minosoft.protocol.network.session.play.PlaySession

data class FabricParticleEventContext(val session: PlaySession, val particle: Particle)
fun interface FabricParticleEventCallback { fun invoke(context: FabricParticleEventContext) }

object FabricParticleEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("particle-events")
    private val callbacks = FabricOwnedEventRegistry<FabricParticleEventContext>("particle-event")
    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)
    fun register(owner: String, callback: FabricParticleEventCallback): AutoCloseable = callbacks.register(owner, callback::invoke)
    fun registrations(): List<String> = callbacks.owners()
    fun dispatch(context: FabricParticleEventContext) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "particle-events", System.nanoTime() - started)
        }
        callbacks.dispatch(context)
    }
}

data class FabricSoundEventContext(
    val session: PlaySession,
    val sound: ResourceLocation,
    val position: Vec3d?,
    val volume: Float,
    val pitch: Float,
)
fun interface FabricSoundEventCallback { fun invoke(context: FabricSoundEventContext) }

object FabricSoundEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("sound-events")
    private val callbacks = FabricOwnedEventRegistry<FabricSoundEventContext>("sound-event")
    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)
    fun register(owner: String, callback: FabricSoundEventCallback): AutoCloseable = callbacks.register(owner, callback::invoke)
    fun registrations(): List<String> = callbacks.owners()
    fun dispatch(context: FabricSoundEventContext) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "sound-events", System.nanoTime() - started)
        }
        callbacks.dispatch(context)
    }
}
