/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

class FabricEnergyStorage(
    val capacity: Long,
    initialAmount: Long = 0L,
) {
    init {
        require(capacity >= 0L) { "Energy capacity must not be negative." }
        require(initialAmount in 0L..capacity) { "Initial energy must fit in capacity." }
    }

    var amount: Long = initialAmount
        private set

    @Synchronized
    fun insert(maxAmount: Long, simulate: Boolean = false): Long {
        require(maxAmount >= 0L) { "Inserted energy must not be negative." }
        val accepted = minOf(maxAmount, capacity - amount)
        if (!simulate) amount += accepted
        return accepted
    }

    @Synchronized
    fun extract(maxAmount: Long, simulate: Boolean = false): Long {
        require(maxAmount >= 0L) { "Extracted energy must not be negative." }
        val extracted = minOf(maxAmount, amount)
        if (!simulate) amount -= extracted
        return extracted
    }
}

data class FabricEnergyCapability(
    val apiId: String,
    val apiVersion: String,
) {
    fun createStorage(capacity: Long, initialAmount: Long = 0L) = FabricEnergyStorage(capacity, initialAmount)
}

object FabricEnergyCapabilities {
    private val registry = FabricHookRegistry<FabricEnergyCapability>("energy-storage")

    fun register(owner: String, capability: FabricEnergyCapability): AutoCloseable = registry.register(owner, capability)
    fun registrations(): List<FabricHostHook<FabricEnergyCapability>> = registry.snapshot()
}
