/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.dimension.DimensionProperties
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.chunk.update.block.ChunkLocalBlockUpdate
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

data class FabricWorldIdentity(
    val dimension: DimensionProperties,
    val name: ResourceLocation?,
)

enum class FabricWorldChangeCause(val wireName: String) {
    INITIALIZE("initialize"),
    RESPAWN("respawn"),
    LOCAL_CONNECT("local-connect"),
    RECONFIGURE("reconfigure"),
    DISCONNECT("disconnect"),
}

enum class FabricWorldEventPhase(val wireName: String) {
    BEFORE_CHANGE("before-change"),
    AFTER_CHANGE("after-change"),
    JOINED("joined"),
    LEFT("left"),
}

data class FabricWorldEventContext(
    val session: PlaySession,
    val previous: FabricWorldIdentity?,
    val current: FabricWorldIdentity?,
    val cause: FabricWorldChangeCause,
    val transitionId: Long,
)

fun interface FabricWorldEventCallback {
    fun invoke(context: FabricWorldEventContext)
}

/** World identity changes and playable-world entry/exit are deliberately separate phases. */
object FabricWorldEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("world-events")
    private val phases = FabricWorldEventPhase.entries.associateWith {
        FabricOwnedEventRegistry<FabricWorldEventContext>("world-event:${it.wireName}")
    }
    private val nextTransition = AtomicLong()
    private val joined = WeakHashMap<PlaySession, FabricWorldIdentity>()
    private val pendingCause = WeakHashMap<PlaySession, FabricWorldChangeCause>()

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)
    fun register(owner: String, phase: FabricWorldEventPhase, callback: FabricWorldEventCallback): AutoCloseable =
        phases.getValue(phase).register(owner, callback::invoke)
    fun registrations(phase: FabricWorldEventPhase): List<String> = phases.getValue(phase).owners()

    fun <T> change(
        session: PlaySession,
        cause: FabricWorldChangeCause,
        nextDimension: DimensionProperties,
        nextName: ResourceLocation?,
        action: () -> T,
    ): T {
        val id = nextTransition.incrementAndGet()
        val previous = FabricWorldIdentity(session.world.dimension, session.world.name)
        val current = FabricWorldIdentity(nextDimension, nextName)
        leave(session, cause, id)
        dispatch(FabricWorldEventPhase.BEFORE_CHANGE, FabricWorldEventContext(session, previous, current, cause, id))
        val result = action()
        synchronized(pendingCause) { pendingCause[session] = cause }
        dispatch(FabricWorldEventPhase.AFTER_CHANGE, FabricWorldEventContext(session, previous, current, cause, id))
        return result
    }

    fun joined(session: PlaySession) {
        val cause = synchronized(pendingCause) { pendingCause.remove(session) } ?: FabricWorldChangeCause.INITIALIZE
        val identity = FabricWorldIdentity(session.world.dimension, session.world.name)
        synchronized(joined) {
            if (joined.put(session, identity) == identity) return
        }
        dispatch(FabricWorldEventPhase.JOINED, FabricWorldEventContext(session, null, identity, cause, nextTransition.incrementAndGet()))
    }

    fun leave(session: PlaySession, cause: FabricWorldChangeCause, transitionId: Long = nextTransition.incrementAndGet()) {
        synchronized(pendingCause) {
            if (cause == FabricWorldChangeCause.DISCONNECT || cause == FabricWorldChangeCause.RECONFIGURE) pendingCause.remove(session)
        }
        val identity = synchronized(joined) { joined.remove(session) } ?: return
        dispatch(FabricWorldEventPhase.LEFT, FabricWorldEventContext(session, identity, null, cause, transitionId))
    }

    private fun dispatch(phase: FabricWorldEventPhase, context: FabricWorldEventContext) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "world-events", System.nanoTime() - started)
        }
        phases.getValue(phase).dispatch(context)
    }
}

enum class FabricChunkEventPhase(val wireName: String) {
    CREATED("created"),
    UPDATED("updated"),
    UNLOADED("unloaded"),
    CLEARED("cleared"),
}

data class FabricChunkEventContext(
    val session: PlaySession,
    val phase: FabricChunkEventPhase,
    val position: ChunkPosition?,
    val chunk: Chunk?,
    val affectedSections: Int = 0,
    val clearedChunks: Int = 0,
)

fun interface FabricChunkEventCallback {
    fun invoke(context: FabricChunkEventContext)
}

object FabricChunkEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("chunk-events")
    private val callbacks = FabricOwnedEventRegistry<FabricChunkEventContext>("chunk-event")

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)
    fun register(owner: String, callback: FabricChunkEventCallback): AutoCloseable = callbacks.register(owner, callback::invoke)
    fun registrations(): List<String> = callbacks.owners()

    fun dispatch(context: FabricChunkEventContext) {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "chunk-events", System.nanoTime() - started)
        }
        callbacks.dispatch(context)
    }
}

data class FabricBlockMutation(
    val position: BlockPosition,
    val previous: BlockState?,
    val state: BlockState?,
)

data class FabricBlockMutationContext(
    val session: PlaySession,
    val chunk: Chunk,
    val changes: List<FabricBlockMutation>,
)

fun interface FabricBlockMutationCallback {
    fun invoke(context: FabricBlockMutationContext)
}

object FabricBlockMutationEvents {
    private val capabilityProviders = FabricHookRegistry<Unit>("block-mutation-events")
    private val callbacks = FabricOwnedEventRegistry<FabricBlockMutationContext>("block-mutation-event")

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)
    fun register(owner: String, callback: FabricBlockMutationCallback): AutoCloseable = callbacks.register(owner, callback::invoke)
    fun registrations(): List<String> = callbacks.owners()

    fun dispatch(chunk: Chunk, changes: Collection<ChunkLocalBlockUpdate.Change>) {
        if (changes.isEmpty()) return
        chunk.world.blockRevision++
        val mutations = changes.map {
            FabricBlockMutation(chunk.position.blockPosition(it.position), it.previous, it.state)
        }
        val context = FabricBlockMutationContext(chunk.world.session, chunk, mutations)
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "block-mutation-events", System.nanoTime() - started)
        }
        callbacks.dispatch(context)
    }
}
