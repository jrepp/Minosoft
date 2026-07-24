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

package de.bixilon.minosoft.assets.datapack

import de.bixilon.kutil.json.JsonUtil.toJsonList
import de.bixilon.kutil.json.JsonUtil.toJsonObject
import de.bixilon.minosoft.assets.AssetsManager
import de.bixilon.minosoft.assets.util.InputStreamUtil.readJsonObject
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

data class DataPackFunction(
    val id: ResourceLocation,
    val commands: List<String>,
)

data class DataPackFunctionTag(
    val id: ResourceLocation,
    val values: List<DataPackFunctionReference>,
    val replace: Boolean = false,
)

data class DataPackFunctionReference(
    val id: String,
    val required: Boolean = true,
)

data class DataPackFunctionLibrary(
    val functions: Map<ResourceLocation, DataPackFunction>,
    val tags: Map<ResourceLocation, DataPackFunctionTag>,
) {
    fun resolve(reference: String): List<DataPackFunction> {
        if (!reference.startsWith('#')) {
            return listOfNotNull(functions[ResourceLocation.of(reference)])
        }
        val result = mutableListOf<DataPackFunction>()
        resolveTag(ResourceLocation.of(reference.removePrefix("#")), linkedSetOf(), result)
        return result
    }

    private fun resolveTag(
        id: ResourceLocation,
        resolving: MutableSet<ResourceLocation>,
        result: MutableList<DataPackFunction>,
    ) {
        require(resolving.add(id)) { "Cyclic data-pack function tag: $id" }
        try {
            val tag = tags[id] ?: return
            for (reference in tag.values) {
                if (reference.id.startsWith('#')) {
                    val target = ResourceLocation.of(reference.id.removePrefix("#"))
                    if (target !in tags && reference.required) throw IllegalArgumentException("$id references missing function tag $target")
                    resolveTag(target, resolving, result)
                } else {
                    val target = ResourceLocation.of(reference.id)
                    val function = functions[target]
                    if (function == null && reference.required) throw IllegalArgumentException("$id references missing function $target")
                    if (function != null) result += function
                }
            }
        } finally {
            resolving.remove(id)
        }
    }

    companion object {
        val EMPTY = DataPackFunctionLibrary(emptyMap(), emptyMap())

        fun load(data: AssetsManager): DataPackFunctionLibrary {
            check(data.loaded) { "Data assets must be loaded before function discovery." }
            val functions = linkedMapOf<ResourceLocation, DataPackFunction>()
            val tags = linkedMapOf<ResourceLocation, DataPackFunctionTag>()
            for (source in data.list().sortedWith(compareBy(ResourceLocation::namespace, ResourceLocation::path))) {
                functionId(source)?.let { id ->
                    val bytes = data[source].use { input -> input.readNBytes(MAX_FUNCTION_BYTES + 1) }
                    require(bytes.size <= MAX_FUNCTION_BYTES) {
                        "$source exceeds the $MAX_FUNCTION_BYTES byte data-pack function limit."
                    }
                    val commands = bytes.inputStream().bufferedReader().useLines { lines ->
                        lines.map(String::trim)
                            .filter { it.isNotEmpty() && !it.startsWith('#') }
                            .toList()
                    }
                    require(commands.size <= MAX_FUNCTION_COMMANDS) {
                        "$source exceeds the $MAX_FUNCTION_COMMANDS command data-pack function limit."
                    }
                    functions[id] = DataPackFunction(id, commands)
                    continue
                }
                tagId(source)?.let { id ->
                    val bytes = data[source].use { input -> input.readNBytes(MAX_TAG_BYTES + 1) }
                    require(bytes.size <= MAX_TAG_BYTES) {
                        "$source exceeds the $MAX_TAG_BYTES byte data-pack function tag limit."
                    }
                    val json = bytes.inputStream().readJsonObject()
                    val replace = json["replace"] as? Boolean ?: false
                    val values = json["values"].toJsonList()?.map { value ->
                        val objectValue = value.toJsonObject()
                        if (objectValue == null) {
                            DataPackFunctionReference(value.toString())
                        } else {
                            DataPackFunctionReference(
                                id = objectValue["id"]?.toString()
                                    ?: throw IllegalArgumentException("$source contains a function tag entry without an id."),
                                required = objectValue["required"] as? Boolean ?: true,
                            )
                        }
                    } ?: emptyList()
                    tags[id] = DataPackFunctionTag(id, values, replace)
                }
            }
            return DataPackFunctionLibrary(functions, tags)
        }

        private fun functionId(source: ResourceLocation): ResourceLocation? {
            val prefix = when {
                source.path.startsWith("functions/") -> "functions/"
                source.path.startsWith("function/") -> "function/"
                else -> return null
            }
            if (!source.path.endsWith(".mcfunction")) return null
            return ResourceLocation(source.namespace, source.path.removePrefix(prefix).removeSuffix(".mcfunction"))
        }

        private fun tagId(source: ResourceLocation): ResourceLocation? {
            val prefix = when {
                source.path.startsWith("tags/functions/") -> "tags/functions/"
                source.path.startsWith("tags/function/") -> "tags/function/"
                else -> return null
            }
            if (!source.path.endsWith(".json")) return null
            return ResourceLocation(source.namespace, source.path.removePrefix(prefix).removeSuffix(".json"))
        }

        private const val MAX_FUNCTION_BYTES = 4 * 1024 * 1024
        private const val MAX_FUNCTION_COMMANDS = 65_536
        private const val MAX_TAG_BYTES = 1024 * 1024
    }
}
