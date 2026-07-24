/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import java.nio.file.Path
import java.util.jar.JarFile

data class FabricContentCatalog(
    val modId: String,
    val assetNamespaces: Set<String>,
    val dataNamespaces: Set<String>,
    val blockStates: Set<String>,
    val models: Set<String>,
    val recipes: Set<String>,
    val lootTables: Set<String>,
    val worldGeneration: Set<String>,
) {
    val resources: Int
        get() = blockStates.size + models.size + recipes.size + lootTables.size + worldGeneration.size
}

object FabricContentCatalogReader {
    fun read(metadata: FabricMetadata): FabricContentCatalog {
        val path = Path.of(metadata.source)
        require(path.toFile().isFile) { "Fabric content source is not a top-level JAR: ${metadata.source}" }
        val entries = JarFile(path.toFile()).use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }
                .take(MAX_CATALOG_ENTRIES + 1).toList().also {
                    require(it.size <= MAX_CATALOG_ENTRIES) {
                        "${metadata.source} exceeds the $MAX_CATALOG_ENTRIES entry content-catalog limit."
                    }
                }
        }
        return FabricContentCatalog(
            modId = metadata.id,
            assetNamespaces = entries.namespaces("assets/"),
            dataNamespaces = entries.namespaces("data/"),
            blockStates = entries.resources("assets/${metadata.id}/blockstates/"),
            models = entries.resources("assets/${metadata.id}/models/"),
            recipes = entries.resources("data/${metadata.id}/recipes/"),
            lootTables = entries.resources("data/${metadata.id}/loot_tables/"),
            worldGeneration = entries.resources("data/${metadata.id}/worldgen/"),
        )
    }

    private fun List<String>.namespaces(prefix: String): Set<String> = asSequence()
        .filter { it.startsWith(prefix) }
        .mapNotNull { it.removePrefix(prefix).substringBefore('/').takeIf(String::isNotBlank) }
        .toSortedSet()

    private fun List<String>.resources(prefix: String): Set<String> = asSequence()
        .filter { it.startsWith(prefix) && it.endsWith(".json") }
        .map { it.removePrefix(prefix).removeSuffix(".json") }
        .toSortedSet()

    private const val MAX_CATALOG_ENTRIES = 100_000
}

object FabricContentCatalogs {
    private val registry = FabricHookRegistry<FabricContentCatalog>("content-catalog")

    fun register(owner: String, catalog: FabricContentCatalog): AutoCloseable = registry.register(owner, catalog)
    fun registrations(): List<FabricHostHook<FabricContentCatalog>> = registry.snapshot()
}

data class FabricApiModules(
    val version: String,
    val modules: Map<String, String>,
)

object FabricApiModuleRegistry {
    private val registry = FabricHookRegistry<FabricApiModules>("fabric-api-modules")

    fun register(owner: String, modules: FabricApiModules): AutoCloseable = registry.register(owner, modules)
    fun registrations(): List<FabricHostHook<FabricApiModules>> = registry.snapshot()
}
