/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

@file:Suppress("UNCHECKED_CAST")

package de.bixilon.minosoft.assets.datapack

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.math.roundToInt

class UnsupportedDataPackCommandException(command: String) :
    IllegalArgumentException("Unsupported local data-pack command: $command")

/**
 * Headless local command state used before commands are bound to world/entity
 * mutation. Unknown commands fail closed so an adapted pack cannot appear to
 * work while silently dropping animation behavior.
 */
class LocalDataPackCommandAuthority(
    private val message: (String) -> Unit = {},
) : DataPackCommandSink, DataPackMacroSource {
    private val objectives = linkedMapOf<String, MutableMap<String, Int>>()
    private val storage = linkedMapOf<ResourceLocation, MutableMap<String, Any>>()

    override fun execute(command: String, context: DataPackCommandContext): Int {
        scoreboard(command)?.let { return it }
        data(command)?.let { return it }
        if (command.startsWith("say ")) {
            message(command.removePrefix("say "))
            return 1
        }
        if (command.startsWith("tellraw ")) {
            message(command.substringAfter(' ', "").substringAfter(' ', ""))
            return 1
        }
        throw UnsupportedDataPackCommandException(command)
    }

    fun score(holder: String, objective: String): Int? = objectives[objective]?.get(holder)

    fun storage(id: ResourceLocation): Map<String, Any>? = storage[id]?.deepCopyMap()

    override fun arguments(storage: ResourceLocation, path: String): Map<String, String> {
        val root = this.storage[storage] ?: throw IllegalArgumentException("Unknown command storage $storage")
        val value = NbtPath.get(root, path)
            ?: throw IllegalArgumentException("Missing command storage path $storage $path")
        require(value is Map<*, *>) { "Function macro source $storage $path must be a compound." }
        return value.entries.associate { it.key.toString() to SnbtParser.stringify(requireNotNull(it.value)) }
    }

    private fun scoreboard(command: String): Int? {
        val tokens = command.split(WHITESPACE)
        if (tokens.firstOrNull() != "scoreboard") return null
        require(tokens.size >= 3) { "Incomplete scoreboard command: $command" }
        return when (tokens[1]) {
            "objectives" -> objectives(tokens, command)
            "players" -> players(tokens, command)
            else -> throw UnsupportedDataPackCommandException(command)
        }
    }

    private fun objectives(tokens: List<String>, command: String): Int {
        return when (tokens.getOrNull(2)) {
            "add" -> {
                val objective = tokens.getOrNull(3) ?: throw IllegalArgumentException("Missing scoreboard objective in: $command")
                require(tokens.getOrNull(4) == "dummy") { "Only dummy local objectives are supported: $command" }
                if (objectives.putIfAbsent(objective, linkedMapOf()) == null) 1 else 0
            }
            "remove" -> if (objectives.remove(tokens.getOrNull(3)) != null) 1 else 0
            else -> throw UnsupportedDataPackCommandException(command)
        }
    }

    private fun players(tokens: List<String>, command: String): Int {
        val action = tokens.getOrNull(2) ?: throw IllegalArgumentException("Missing scoreboard player action: $command")
        if (action == "reset") {
            val holder = tokens.getOrNull(3) ?: throw IllegalArgumentException("Missing score holder: $command")
            val objective = tokens.getOrNull(4)
            if (objective != null) return if (objectives[objective]?.remove(holder) != null) 1 else 0
            var removed = 0
            objectives.values.forEach { if (it.remove(holder) != null) removed++ }
            return removed
        }

        val holder = tokens.getOrNull(3) ?: throw IllegalArgumentException("Missing score holder: $command")
        val objective = tokens.getOrNull(4) ?: throw IllegalArgumentException("Missing score objective: $command")
        val scores = objectives[objective] ?: throw IllegalArgumentException("Unknown scoreboard objective $objective")
        return when (action) {
            "get" -> scores[holder] ?: 0
            "set", "add", "remove" -> {
                val operand = tokens.getOrNull(5)?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid scoreboard value in: $command")
                val value = when (action) {
                    "set" -> operand
                    "add" -> (scores[holder] ?: 0) + operand
                    else -> (scores[holder] ?: 0) - operand
                }
                scores[holder] = value
                value
            }
            "operation" -> {
                val operation = tokens.getOrNull(5) ?: throw IllegalArgumentException("Missing scoreboard operation: $command")
                val sourceHolder = tokens.getOrNull(6) ?: throw IllegalArgumentException("Missing source score holder: $command")
                val sourceObjective = tokens.getOrNull(7) ?: throw IllegalArgumentException("Missing source objective: $command")
                val sourceScores = objectives[sourceObjective] ?: throw IllegalArgumentException("Unknown scoreboard objective $sourceObjective")
                val left = scores[holder] ?: 0
                val right = sourceScores[sourceHolder] ?: 0
                when (operation) {
                    "=" -> scores[holder] = right
                    "+=" -> scores[holder] = left + right
                    "-=" -> scores[holder] = left - right
                    "*=" -> scores[holder] = left * right
                    "/=" -> scores[holder] = if (right == 0) 0 else left / right
                    "%=" -> scores[holder] = if (right == 0) 0 else left % right
                    "<" -> scores[holder] = minOf(left, right)
                    ">" -> scores[holder] = maxOf(left, right)
                    "><" -> {
                        scores[holder] = right
                        sourceScores[sourceHolder] = left
                    }
                    else -> throw IllegalArgumentException("Unknown scoreboard operation $operation")
                }
                scores[holder] ?: 0
            }
            else -> throw UnsupportedDataPackCommandException(command)
        }
    }

    private fun data(command: String): Int? {
        if (!command.startsWith("data ")) return null
        DATA_REMOVE.matchEntire(command)?.let { match ->
            val root = storage[ResourceLocation.of(match.groupValues[1])] ?: return 0
            return if (NbtPath.remove(root, match.groupValues[2])) 1 else 0
        }
        DATA_GET.matchEntire(command)?.let { match ->
            val value = storage[ResourceLocation.of(match.groupValues[1])]?.let {
                NbtPath.get(it, match.groupValues[2])
            } ?: return 0
            val scale = match.groupValues[3].toDoubleOrNull() ?: 1.0
            return when (value) {
                is Number -> (value.toDouble() * scale).roundToInt()
                is Collection<*> -> value.size
                is Map<*, *> -> value.size
                is String -> value.length
                else -> 1
            }
        }
        DATA_MERGE.matchEntire(command)?.let { match ->
            val root = storage.getOrPut(ResourceLocation.of(match.groupValues[1])) { linkedMapOf() }
            merge(root, SnbtParser.compound(match.groupValues[2]))
            return 1
        }
        DATA_MODIFY.matchEntire(command)?.let { match ->
            val id = ResourceLocation.of(match.groupValues[1])
            val path = match.groupValues[2]
            val operation = match.groupValues[3]
            val sourceType = match.groupValues[4]
            val source = match.groupValues[5]
            val value = when (sourceType) {
                "value" -> SnbtParser.parse(source)
                "from storage" -> {
                    val separator = source.indexOf(' ')
                    require(separator > 0) { "Missing source storage path in: $command" }
                    val sourceRoot = storage[ResourceLocation.of(source.substring(0, separator))]
                        ?: throw IllegalArgumentException("Unknown source storage in: $command")
                    NbtPath.get(sourceRoot, source.substring(separator + 1))
                        ?: throw IllegalArgumentException("Missing source storage value in: $command")
                }
                else -> throw UnsupportedDataPackCommandException(command)
            }
            val root = storage.getOrPut(id) { linkedMapOf() }
            when (operation) {
                "set" -> NbtPath.set(root, path, value.deepMutable())
                "merge" -> {
                    val target = NbtPath.get(root, path)
                    require(target is MutableMap<*, *> && value is Map<*, *>) { "Data merge requires compounds: $command" }
                    @Suppress("UNCHECKED_CAST")
                    merge(target as MutableMap<String, Any>, value as Map<String, Any>)
                }
                "append" -> {
                    val target = NbtPath.get(root, path)
                    require(target is MutableList<*>) { "Data append requires a list target: $command" }
                    @Suppress("UNCHECKED_CAST")
                    (target as MutableList<Any>) += value.deepMutable()
                }
                else -> throw UnsupportedDataPackCommandException(command)
            }
            return 1
        }
        throw UnsupportedDataPackCommandException(command)
    }

    private fun merge(target: MutableMap<String, Any>, source: Map<String, Any>) {
        for ((key, value) in source) {
            val previous = target[key]
            if (previous is MutableMap<*, *> && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                merge(previous as MutableMap<String, Any>, value as Map<String, Any>)
            } else {
                target[key] = value.deepMutable()
            }
        }
    }

    private fun Any.deepMutable(): Any = when (this) {
        is Map<*, *> -> entries.associateTo(linkedMapOf()) { it.key.toString() to it.value!!.deepMutable() }
        is List<*> -> mapTo(mutableListOf()) { it!!.deepMutable() }
        else -> this
    }

    private fun Map<String, Any>.deepCopyMap(): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return (deepMutable() as MutableMap<String, Any>).toMap()
    }

    private object NbtPath {
        fun get(root: MutableMap<String, Any>, source: String): Any? {
            var value: Any = root
            for (segment in parse(source)) {
                value = when (segment) {
                    is Segment.Key -> (value as? Map<*, *>)?.get(segment.name) ?: return null
                    is Segment.Index -> {
                        val list = value as? List<*> ?: return null
                        list.getOrNull(segment.index.resolve(list.size)) ?: return null
                    }
                }
            }
            return value
        }

        fun set(root: MutableMap<String, Any>, source: String, newValue: Any) {
            val segments = parse(source)
            require(segments.isNotEmpty()) { "Can not replace a storage root with data modify." }
            val (parent, last) = parent(root, segments, create = true) ?: error("unreachable")
            when (last) {
                is Segment.Key -> (parent as MutableMap<String, Any>)[last.name] = newValue
                is Segment.Index -> {
                    val list = parent as MutableList<Any>
                    list[last.index.resolve(list.size)] = newValue
                }
            }
        }

        fun remove(root: MutableMap<String, Any>, source: String): Boolean {
            val segments = parse(source)
            if (segments.isEmpty()) return false
            val (parent, last) = parent(root, segments, create = false) ?: return false
            return when (last) {
                is Segment.Key -> (parent as? MutableMap<*, *>)?.remove(last.name) != null
                is Segment.Index -> {
                    val list = parent as? MutableList<*> ?: return false
                    val index = last.index.resolve(list.size)
                    if (index !in list.indices) false else {
                        list.removeAt(index)
                        true
                    }
                }
            }
        }

        private fun parent(root: MutableMap<String, Any>, segments: List<Segment>, create: Boolean): Pair<Any, Segment>? {
            var value: Any = root
            for ((index, segment) in segments.dropLast(1).withIndex()) {
                val next = segments[index + 1]
                value = when (segment) {
                    is Segment.Key -> {
                        val map = value as? MutableMap<String, Any> ?: return null
                        map[segment.name] ?: if (create) {
                            val created: Any = if (next is Segment.Index) mutableListOf<Any>() else linkedMapOf<String, Any>()
                            map[segment.name] = created
                            created
                        } else return null
                    }
                    is Segment.Index -> {
                        val list = value as? MutableList<Any> ?: return null
                        val resolved = segment.index.resolve(list.size)
                        if (resolved !in list.indices) return null
                        list[resolved]
                    }
                }
            }
            return value to segments.last()
        }

        private fun parse(source: String): List<Segment> {
            val result = mutableListOf<Segment>()
            var index = 0
            while (index < source.length) {
                if (source[index] == '.') {
                    index++
                    continue
                }
                if (source[index] == '[') {
                    val end = source.indexOf(']', index + 1)
                    require(end > index) { "Unterminated NBT path index: $source" }
                    result += Segment.Index(source.substring(index + 1, end).toInt())
                    index = end + 1
                    continue
                }
                if (source[index] == '"' || source[index] == '\'') {
                    val quote = source[index++]
                    val start = index
                    while (index < source.length && source[index] != quote) index++
                    require(index < source.length) { "Unterminated quoted NBT path key: $source" }
                    result += Segment.Key(source.substring(start, index))
                    index++
                    continue
                }
                val start = index
                while (index < source.length && source[index] != '.' && source[index] != '[') index++
                result += Segment.Key(source.substring(start, index))
            }
            return result
        }

        private fun Int.resolve(size: Int) = if (this < 0) size + this else this

        private sealed interface Segment {
            data class Key(val name: String) : Segment
            data class Index(val index: Int) : Segment
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val DATA_REMOVE = Regex("""data remove storage\s+(\S+)\s+(.+)""")
        val DATA_GET = Regex("""data get storage\s+(\S+)\s+(\S+)(?:\s+([-+]?\d+(?:\.\d+)?))?""")
        val DATA_MERGE = Regex("""data merge storage\s+(\S+)\s+(\{.*})""")
        val DATA_MODIFY = Regex("""data modify storage\s+(\S+)\s+(\S+)\s+(set|merge|append)\s+(value|from storage)\s+(.+)""")
    }
}
