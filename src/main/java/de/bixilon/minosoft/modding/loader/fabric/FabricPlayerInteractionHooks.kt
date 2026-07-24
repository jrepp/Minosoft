/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.camera.target.targets.GenericTarget
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.entities.player.Hands
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

enum class FabricPlayerInteractionType(val wireName: String) {
    ATTACK_ENTITY("attack-entity"),
    ATTACK_BLOCK("attack-block"),
    USE_ENTITY("use-entity"),
    USE_BLOCK("use-block"),
    USE_ITEM("use-item"),
}

enum class FabricInteractionDecision { PASS, DENY }

data class FabricPlayerInteractionContext(
    val session: PlaySession,
    val type: FabricPlayerInteractionType,
    val target: GenericTarget? = null,
    val hand: Hands? = null,
    val stack: ItemStack? = null,
)

fun interface FabricPlayerInteractionHook {
    fun decide(context: FabricPlayerInteractionContext): FabricInteractionDecision
}

object FabricPlayerInteractionHooks {
    private val capabilityProviders = FabricHookRegistry<Unit>("player-interactions")
    private val hooks = FabricHookRegistry<FabricPlayerInteractionHook>("player-interaction")

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)
    fun register(owner: String, hook: FabricPlayerInteractionHook): AutoCloseable = hooks.register(owner, hook)
    fun registrations(): List<String> = hooks.snapshot().map { it.owner }

    fun decide(context: FabricPlayerInteractionContext): FabricInteractionDecision {
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "player-interactions", System.nanoTime() - started)
        }
        for (registration in hooks.snapshot()) {
            val started = System.nanoTime()
            val decision = try {
                registration.hook.decide(context)
            } catch (error: Throwable) {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                    "FABRIC_INTERACTION_FAILED owner=${registration.owner} type=${context.type.wireName} error=${error::class.java.simpleName}: ${error.message}"
                }
                FabricInteractionDecision.PASS
            } finally {
                FabricModDiagnostics.hookInvoked(registration.owner, "player-interaction", System.nanoTime() - started)
            }
            if (decision == FabricInteractionDecision.DENY) return decision
        }
        return FabricInteractionDecision.PASS
    }
}
