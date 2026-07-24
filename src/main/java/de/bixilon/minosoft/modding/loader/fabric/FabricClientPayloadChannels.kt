/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.util.concurrent.atomic.AtomicBoolean

class FabricClientPayloadContext internal constructor(
    val session: PlaySession,
    val channel: ResourceLocation,
    payload: ByteArray,
) {
    private val payload = payload.copyOf()
    val size: Int get() = payload.size

    /** Every caller receives its own mutable copy; registered callbacks cannot alias network storage. */
    fun copyPayload(): ByteArray = payload.copyOf()
}

fun interface FabricClientPayloadCallback {
    fun receive(context: FabricClientPayloadContext)
}

/** Source-native equivalent of Fabric's global client play networking receiver surface. */
object FabricClientPayloadChannels {
    const val MAX_PAYLOAD_BYTES = 1 shl 20

    private val capabilityProviders = FabricHookRegistry<Unit>("client-payload-channels")
    private val channels = linkedMapOf<ResourceLocation, FabricOwnedEventRegistry<FabricClientPayloadContext>>()

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    fun register(owner: String, channel: ResourceLocation, callback: FabricClientPayloadCallback): AutoCloseable {
        val registry = synchronized(channels) {
            channels.getOrPut(channel) { FabricOwnedEventRegistry("client-payload:$channel") }
        }
        val registration = registry.register(owner, callback::receive)
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            registration.close()
            synchronized(channels) {
                if (registry.owners().isEmpty()) channels.remove(channel, registry)
            }
        }
    }

    fun registrations(): Map<ResourceLocation, List<String>> = synchronized(channels) {
        channels.mapValues { it.value.owners() }
    }

    fun dispatch(session: PlaySession, channel: ResourceLocation, payload: ByteArray): Boolean {
        if (payload.size > MAX_PAYLOAD_BYTES) return false
        val registry = synchronized(channels) { channels[channel] } ?: return false
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "client-payload-channels", System.nanoTime() - started)
        }
        registry.dispatch(FabricClientPayloadContext(session, channel, payload))
        return true
    }

    fun send(session: PlaySession, channel: ResourceLocation, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Fabric payload for $channel is ${payload.size} bytes; maximum is $MAX_PAYLOAD_BYTES."
        }
        session.channels.play.send(channel, payload.copyOf())
    }
}
