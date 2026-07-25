/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import com.fasterxml.jackson.databind.JsonNode
import de.bixilon.minosoft.util.json.Jackson
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import java.io.InputStream

data class FabricDependency(
    val id: String,
    val predicates: List<String>,
)

data class FabricMetadata(
    val id: String,
    val version: String,
    val name: String,
    val environment: String,
    val entrypoints: Set<String>,
    val dependencies: Map<String, List<FabricDependency>>,
    val provides: Set<String>,
    val mixins: Int,
    val accessWidener: String?,
    val nestedJarPaths: List<String>,
    val source: String,
    val description: String? = null,
    val icon: String? = null,
    val badges: Set<String> = emptySet(),
    val parent: String? = null,
) {
    val nestedJars: Int get() = nestedJarPaths.size
}

object FabricMetadataReader {
    internal const val MAX_METADATA_BYTES = 1024 * 1024
    private val ID = "[a-z][a-z0-9-_]{1,63}".toRegex()
    private val DEPENDENCY_KEYS = setOf("depends", "recommends", "suggests", "breaks", "conflicts")

    fun read(stream: InputStream, source: String): FabricMetadata {
        val bytes = stream.use { it.readNBytes(MAX_METADATA_BYTES + 1) }
        require(bytes.size <= MAX_METADATA_BYTES) { "$source exceeds the $MAX_METADATA_BYTES byte Fabric metadata limit." }
        val root = Jackson.MAPPER.readTree(bytes)
        require(root.path("schemaVersion").asInt(-1) == 1) { "$source uses an unsupported fabric.mod.json schema." }

        val id = root.requiredText("id", source)
        require(ID.matches(id)) { "$source has an invalid Fabric mod id: $id" }

        val version = root.requiredText("version", source)
        Version.parse(version)

        val dependencies = DEPENDENCY_KEYS.associateWith { key ->
            root.path(key).properties().asSequence().map { (dependencyId, value) ->
                require(ID.matches(dependencyId)) { "$source has an invalid Fabric dependency id: $dependencyId" }
                val predicates = value.stringValues(source, "$key.$dependencyId")
                predicates.forEach(VersionPredicate::parse)
                FabricDependency(dependencyId, predicates)
            }.toList()
        }

        val modMenu = root.path("custom").path("modmenu")
        val icon = root.path("icon").let { value ->
            when {
                value.isTextual -> value.asText()
                value.isObject -> value.properties().asSequence()
                    .mapNotNull { (size, path) -> size.toIntOrNull()?.let { it to path.asText() } }
                    .maxByOrNull { it.first }
                    ?.second
                else -> null
            }
        }
        val badges = modMenu.path("badges").takeIf(JsonNode::isArray)
            ?.mapNotNull { it.takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank) }
            ?.take(MAX_BADGES)
            ?.toSet()
            ?: emptySet()
        val parent = modMenu.path("parent").takeIf(JsonNode::isTextual)?.asText()?.takeIf(ID::matches)

        return FabricMetadata(
            id = id,
            version = version,
            name = root.path("name").takeIf(JsonNode::isTextual)?.asText() ?: id,
            environment = root.path("environment").takeIf(JsonNode::isTextual)?.asText() ?: "*",
            entrypoints = root.path("entrypoints").fieldNames().asSequence().toSet(),
            dependencies = dependencies,
            provides = root.path("provides").takeIf(JsonNode::isArray)?.map { it.asText() }?.toSet() ?: emptySet(),
            mixins = root.path("mixins").takeIf(JsonNode::isArray)?.size() ?: 0,
            accessWidener = root.path("accessWidener").takeIf(JsonNode::isTextual)?.asText(),
            nestedJarPaths = root.path("jars").takeIf(JsonNode::isArray)?.map { nested ->
                val path = nested.path("file")
                require(path.isTextual && path.asText().isNotBlank()) { "$source has a nested JAR without a file path." }
                val value = path.asText()
                require(!value.startsWith('/') && value.split('/').none { it == ".." }) { "$source has an unsafe nested JAR path: $value" }
                value
            } ?: emptyList(),
            source = source,
            description = root.path("description").takeIf(JsonNode::isTextual)?.asText()?.take(MAX_DESCRIPTION_LENGTH),
            icon = icon?.take(MAX_ICON_LENGTH),
            badges = badges,
            parent = parent,
        )
    }

    private fun JsonNode.requiredText(key: String, source: String): String {
        val value = path(key)
        require(value.isTextual && value.asText().isNotBlank()) { "$source is missing Fabric field '$key'." }
        return value.asText()
    }

    private fun JsonNode.stringValues(source: String, field: String): List<String> = when {
        isTextual -> listOf(asText())
        isArray -> map {
            require(it.isTextual) { "$source field '$field' must contain only strings." }
            it.asText()
        }
        else -> throw IllegalArgumentException("$source field '$field' must be a string or string array.")
    }

    private const val MAX_DESCRIPTION_LENGTH = 8_192
    private const val MAX_ICON_LENGTH = 1_024
    private const val MAX_BADGES = 32
}
