/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicBoolean

data class FabricClientCommandContext(
    val session: PlaySession,
    val name: String,
    val arguments: String,
)

fun interface FabricClientCommandCallback {
    fun execute(context: FabricClientCommandContext)
}

object FabricClientCommands {
    private data class Registration(val owner: String, val callback: FabricClientCommandCallback)

    private val capabilityProviders = FabricHookRegistry<Unit>("client-commands")
    private val commands = linkedMapOf<String, Registration>()

    fun install(owner: String): AutoCloseable = capabilityProviders.register(owner, Unit)
    fun providers(): List<String> = capabilityProviders.snapshot().map(FabricHostHook<Unit>::owner)

    @Synchronized
    fun register(owner: String, name: String, callback: FabricClientCommandCallback): AutoCloseable {
        require(owner.isNotBlank()) { "Fabric client command owner must not be blank." }
        require(COMMAND_NAME.matches(name)) { "Invalid Fabric client command name: $name" }
        val registration = Registration(owner, callback)
        require(commands.putIfAbsent(name, registration) == null) { "Fabric client command is already registered: $name" }
        FabricModDiagnostics.hookInstalled(owner, "client-command:$name")
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            synchronized(this) { commands.remove(name, registration) }
            FabricModDiagnostics.hookUninstalled(owner, "client-command:$name")
        }
    }

    @Synchronized
    fun registrations(): Map<String, String> = commands.mapValues { it.value.owner }

    fun execute(session: PlaySession, command: String): Boolean {
        val normalized = command.trimStart()
        val split = normalized.indexOfFirst(Character::isWhitespace)
        val name = if (split < 0) normalized else normalized.substring(0, split)
        val arguments = if (split < 0) "" else normalized.substring(split).trimStart()
        val registration = synchronized(this) { commands[name] } ?: return false
        for (provider in capabilityProviders.snapshot()) {
            val started = System.nanoTime()
            FabricModDiagnostics.hookInvoked(provider.owner, "client-commands", System.nanoTime() - started)
        }
        val started = System.nanoTime()
        try {
            registration.callback.execute(FabricClientCommandContext(session, name, arguments))
        } catch (error: Throwable) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                "FABRIC_CLIENT_COMMAND_FAILED owner=${registration.owner} command=$name error=${error::class.java.simpleName}: ${error.message}"
            }
        } finally {
            FabricModDiagnostics.hookInvoked(registration.owner, "client-command:$name", System.nanoTime() - started)
        }
        return true
    }

    private val COMMAND_NAME = "[a-z0-9_.-]+".toRegex()
}
