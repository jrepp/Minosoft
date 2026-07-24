/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.assets

data class ExternalAssetProvider(
    val owner: String,
    val create: () -> AssetsManager,
)

object ExternalAssetProviders {
    private val providers = linkedMapOf<String, () -> AssetsManager>()

    @Synchronized
    fun register(owner: String, create: () -> AssetsManager): AutoCloseable {
        require(owner.isNotBlank()) { "External asset owner must not be blank." }
        require(providers.putIfAbsent(owner, create) == null) { "External assets are already registered by $owner." }
        return AutoCloseable {
            synchronized(this) {
                providers.remove(owner, create)
            }
        }
    }

    @Synchronized
    fun snapshot(): List<ExternalAssetProvider> = providers.map { ExternalAssetProvider(it.key, it.value) }
}
