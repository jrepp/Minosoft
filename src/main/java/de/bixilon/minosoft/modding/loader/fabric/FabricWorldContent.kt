/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import com.fasterxml.jackson.databind.JsonNode
import de.bixilon.minosoft.util.json.Jackson
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarFile

data class FabricContentDefinition(
    val id: String,
    val hasBlockState: Boolean,
    val hasItemModel: Boolean,
    val blockProperties: Map<String, List<String>> = emptyMap(),
)

enum class FabricHeightAnchorType {
    ABSOLUTE,
    ABOVE_BOTTOM,
    BELOW_TOP,
}

data class FabricHeightAnchor(
    val type: FabricHeightAnchorType,
    val value: Int,
) {
    fun resolve(minY: Int, maxY: Int): Int = when (type) {
        FabricHeightAnchorType.ABSOLUTE -> value.toLong()
        FabricHeightAnchorType.ABOVE_BOTTOM -> minY.toLong() + value
        FabricHeightAnchorType.BELOW_TOP -> maxY.toLong() - 1 - value
    }.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

data class FabricOreTarget(
    val state: String,
    val block: String?,
    val tag: String?,
)

data class FabricOreFeature(
    val id: String,
    val size: Int,
    val count: Int,
    val minHeight: FabricHeightAnchor,
    val maxHeight: FabricHeightAnchor,
    val targets: List<FabricOreTarget>,
)

data class FabricWorldContent(
    val namespace: String,
    val content: List<FabricContentDefinition>,
    val ores: List<FabricOreFeature>,
    val fingerprint: String,
) {
    val blocks get() = content.filter(FabricContentDefinition::hasBlockState)
    val items get() = content.filter(FabricContentDefinition::hasItemModel)
}

object FabricWorldContentReader {
    fun read(metadata: FabricMetadata): FabricWorldContent {
        val path = Path.of(metadata.source)
        require(path.toFile().isFile) { "Fabric world-content source is not a top-level JAR: ${metadata.source}" }
        val namespace = metadata.id
        JarFile(path.toFile()).use { jar ->
            val entries = jar.entries().asSequence().filterNot { it.isDirectory }.map(JarEntry::getName)
                .take(MAX_JAR_ENTRIES + 1).toList()
            require(entries.size <= MAX_JAR_ENTRIES) {
                "${metadata.source} exceeds the $MAX_JAR_ENTRIES entry Fabric content limit."
            }
            val blockPrefix = "assets/$namespace/blockstates/"
            val itemPrefix = "assets/$namespace/models/item/"
            val blocks = entries.asSequence().filter { it.startsWith(blockPrefix) && it.endsWith(".json") }
                .map { it.removePrefix(blockPrefix).removeSuffix(".json") }
                .take(MAX_CONTENT_DEFINITIONS + 1).toSet()
            val items = entries.asSequence().filter { it.startsWith(itemPrefix) && it.endsWith(".json") }
                .map { it.removePrefix(itemPrefix).removeSuffix(".json") }
                .take(MAX_CONTENT_DEFINITIONS + 1).toSet()
            val definitions = blocks + items
            require(definitions.size <= MAX_CONTENT_DEFINITIONS) {
                "${metadata.source} exceeds the $MAX_CONTENT_DEFINITIONS Fabric content definition limit."
            }
            val content = definitions.sorted().map { id ->
                val properties = if (id in blocks) {
                    val entry = requireNotNull(jar.getJarEntry("$blockPrefix$id.json"))
                    readJson(jar, entry, "${metadata.source}!/${entry.name}").readBlockProperties()
                } else emptyMap()
                FabricContentDefinition("$namespace:$id", id in blocks, id in items, properties)
            }
            val ores = readOres(jar, namespace, entries)
            val canonical = buildString {
                fun appendPart(value: Any?) {
                    val text = value.toString()
                    append(text.length).append('#').append(text)
                }
                content.forEach { definition ->
                    appendPart(definition.id)
                    appendPart(definition.hasBlockState)
                    appendPart(definition.hasItemModel)
                    definition.blockProperties.forEach { (name, values) ->
                        appendPart(name)
                        values.forEach(::appendPart)
                    }
                    append('\n')
                }
                ores.forEach { ore ->
                    appendPart(ore.id)
                    appendPart(ore.size)
                    appendPart(ore.count)
                    appendPart(ore.minHeight)
                    appendPart(ore.maxHeight)
                    append('\n')
                    ore.targets.forEach {
                        appendPart(it.state)
                        appendPart(it.block)
                        appendPart(it.tag)
                        append('\n')
                    }
                }
            }
            val fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)))
            return FabricWorldContent(namespace, content, ores, fingerprint)
        }
    }

    private fun JsonNode.readBlockProperties(): Map<String, List<String>> {
        val values = linkedMapOf<String, MutableSet<String>>()

        fun collectExpression(expression: String) {
            if (expression.isBlank()) return
            for (part in expression.split(',')) {
                val separator = part.indexOf('=')
                if (separator <= 0 || separator == part.lastIndex) continue
                val name = part.substring(0, separator)
                val propertyValues = part.substring(separator + 1).split('|')
                values.getOrPut(name) { linkedSetOf() }.addAll(propertyValues)
            }
        }

        path("variants").fieldNames().forEachRemaining(::collectExpression)

        fun collectConditions(node: JsonNode) {
            when {
                node.isArray -> node.forEach(::collectConditions)
                node.isObject -> node.properties().forEach { (name, value) ->
                    if (name == "OR" || name == "AND") {
                        collectConditions(value)
                    } else if (value.isValueNode) {
                        values.getOrPut(name) { linkedSetOf() }.addAll(value.asText().split('|'))
                    }
                }
            }
        }
        path("multipart").forEach { part -> collectConditions(part.path("when")) }

        require(values.size <= MAX_BLOCK_PROPERTIES) {
            "Block state exceeds the $MAX_BLOCK_PROPERTIES property limit."
        }
        var states = 1L
        return values.entries.sortedBy(Map.Entry<String, *>::key).associate { (name, rawValues) ->
            val completed = rawValues.toMutableSet()
            if (completed.any { it == "true" || it == "false" }) completed += setOf("false", "true")
            require(completed.isNotEmpty() && completed.size <= MAX_PROPERTY_VALUES) {
                "Block property $name must have 1..$MAX_PROPERTY_VALUES values."
            }
            states *= completed.size
            require(states <= MAX_BLOCK_STATES) {
                "Block state exceeds the $MAX_BLOCK_STATES expanded-state limit."
            }
            name to completed.sorted()
        }
    }

    private fun readOres(jar: JarFile, namespace: String, entries: List<String>): List<FabricOreFeature> {
        val configuredPrefix = "data/$namespace/worldgen/configured_feature/"
        val placedPrefix = "data/$namespace/worldgen/placed_feature/"
        val configuredEntries = entries.asSequence()
            .filter { it.startsWith(configuredPrefix) && it.endsWith(".json") }
            .take(MAX_CONFIGURED_FEATURES + 1).toList()
        require(configuredEntries.size <= MAX_CONFIGURED_FEATURES) {
            "Fabric namespace $namespace exceeds the $MAX_CONFIGURED_FEATURES configured feature limit."
        }
        return configuredEntries.asSequence()
            .mapNotNull { configuredName ->
                val configuredEntry = requireNotNull(jar.getJarEntry(configuredName))
                val configured = readJson(jar, configuredEntry, configuredEntry.name)
                if (configured.path("type").asText() != "minecraft:ore") return@mapNotNull null
                val name = configuredEntry.name.removePrefix(configuredPrefix).removeSuffix(".json")
                val placedEntry = jar.getJarEntry("$placedPrefix$name.json") ?: return@mapNotNull null
                val placed = readJson(jar, placedEntry, placedEntry.name)
                decodeOre("$namespace:$name", configured, placed)
            }.sortedBy(FabricOreFeature::id).toList().also {
                require(it.size <= MAX_ORE_FEATURES) {
                    "Fabric namespace $namespace exceeds the $MAX_ORE_FEATURES ore feature limit."
                }
            }
    }

    private fun decodeOre(id: String, configured: JsonNode, placed: JsonNode): FabricOreFeature {
        val config = configured.path("config")
        val count = placed.path("placement").firstOrNull { it.path("type").asText() == "minecraft:count" }
            ?.path("count")?.asInt() ?: 1
        val height = requireNotNull(placed.path("placement").firstOrNull { it.path("type").asText() == "minecraft:height_range" }) {
            "$id has no height_range placement."
        }.path("height")
        require(height.path("type").asText() == "minecraft:uniform") { "$id uses an unsupported height distribution." }
        val targets = config.path("targets").map { target ->
            FabricOreTarget(
                state = target.path("state").path("Name").asText().also { require(it.isNotBlank()) { "$id has a target without a state." } },
                block = target.path("target").path("block").takeIf(JsonNode::isTextual)?.asText(),
                tag = target.path("target").path("tag").takeIf(JsonNode::isTextual)?.asText(),
            )
        }
        require(targets.isNotEmpty() && targets.size <= MAX_ORE_TARGETS) {
            "$id must have 1..$MAX_ORE_TARGETS targets."
        }
        return FabricOreFeature(
            id = id,
            size = config.path("size").asInt().also {
                require(it in 1..MAX_ORE_SIZE) { "$id has an invalid vein size." }
            },
            count = count.also {
                require(it in 0..MAX_ORE_COUNT) { "$id has an invalid placement count." }
            },
            minHeight = height.path("min_inclusive").heightAnchor(id),
            maxHeight = height.path("max_inclusive").heightAnchor(id),
            targets = targets,
        )
    }

    private fun JsonNode.heightAnchor(id: String): FabricHeightAnchor {
        val fields = listOf(
            "absolute" to FabricHeightAnchorType.ABSOLUTE,
            "above_bottom" to FabricHeightAnchorType.ABOVE_BOTTOM,
            "below_top" to FabricHeightAnchorType.BELOW_TOP,
        ).filter { path(it.first).isIntegralNumber }
        require(fields.size == 1) { "$id uses an unsupported height anchor: $this" }
        return FabricHeightAnchor(fields.single().second, path(fields.single().first).asInt())
    }

    private fun readJson(jar: JarFile, entry: JarEntry, source: String): JsonNode {
        val bytes = jar.getInputStream(entry).use { it.readNBytes(MAX_JSON_BYTES + 1) }
        require(bytes.size <= MAX_JSON_BYTES) { "$source exceeds the $MAX_JSON_BYTES byte JSON limit." }
        return Jackson.MAPPER.readTree(bytes)
    }

    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_JAR_ENTRIES = 100_000
    private const val MAX_CONTENT_DEFINITIONS = 16_384
    private const val MAX_CONFIGURED_FEATURES = 4096
    private const val MAX_BLOCK_PROPERTIES = 32
    private const val MAX_PROPERTY_VALUES = 64
    private const val MAX_BLOCK_STATES = 65_536
    private const val MAX_ORE_FEATURES = 256
    private const val MAX_ORE_TARGETS = 64
    private const val MAX_ORE_SIZE = 64
    private const val MAX_ORE_COUNT = 64
}

object FabricWorldContents {
    private val registry = FabricHookRegistry<FabricWorldContent>("world-content")

    fun register(owner: String, content: FabricWorldContent): AutoCloseable = registry.register(owner, content)
    fun registrations(): List<FabricHostHook<FabricWorldContent>> = registry.snapshot()
    fun byNamespace(namespace: String): FabricWorldContent? = registrations().firstNotNullOfOrNull { it.hook.takeIf { content -> content.namespace == namespace } }
}
