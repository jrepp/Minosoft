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

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.entities.Entity
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
    private val origin: () -> Vec3d = { Vec3d.EMPTY },
    private val rotation: () -> EntityRotation = { EntityRotation.EMPTY },
    private val spawn: ((ResourceLocation, Map<String, Any>, Vec3d) -> Unit)? = null,
    private val entities: DataPackEntityAccess? = null,
) : DataPackCommandSink, DataPackMacroSource, DataPackExecuteEnvironment {
    private enum class StoreMode { RESULT, SUCCESS }

    private sealed class ExecuteStore(val mode: StoreMode) {
        class Score(mode: StoreMode, val holder: String, val objective: String) : ExecuteStore(mode)
        class Storage(
            mode: StoreMode,
            val id: ResourceLocation,
            val path: String,
            val numberType: String,
            val scale: Double,
        ) : ExecuteStore(mode)

        class EntityData(
            mode: StoreMode,
            val selector: String,
            val path: String,
            val numberType: String,
            val scale: Double,
        ) : ExecuteStore(mode)
    }

    private val objectives = linkedMapOf<String, MutableMap<String, Int>>()
    private val storage = linkedMapOf<ResourceLocation, MutableMap<String, Any>>()

    override fun execute(command: String, context: DataPackCommandContext): Int {
        scoreboard(command, context)?.let { return it }
        entity(command, context)?.let { return it }
        data(command)?.let { return it }
        summon(command, context)?.let { return it }
        if (command.startsWith("say ")) {
            message(command.removePrefix("say "))
            return 1
        }
        if (command.startsWith("tellraw ")) {
            message(command.substringAfter(' ', "").substringAfter(' ', ""))
            return 1
        }
        if (command == "time query gametime") return context.tick.toInt()
        throw UnsupportedDataPackCommandException(command)
    }

    override fun execute(
        command: String,
        context: DataPackCommandContext,
        continuation: (String, DataPackCommandContext) -> Int,
    ): Int {
        require(command.startsWith("execute ")) { "Not an execute command: $command" }
        val tokens = tokenize(command.removePrefix("execute "))
        val runIndex = tokens.indexOf("run")
        val clauses = if (runIndex < 0) tokens else tokens.take(runIndex)
        val nested = if (runIndex < 0) null else tokens.drop(runIndex + 1).joinToString(" ")
            .takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Execute command is missing its run command: $command")
        var contexts = listOf(context)
        val stores = mutableListOf<ExecuteStore>()
        var index = 0
        while (index < clauses.size) {
            when (clauses[index]) {
                "as" -> {
                    val selector = clauses.required(index + 1, command)
                    val access = entities ?: throw UnsupportedDataPackCommandException(command)
                    contexts = contexts.flatMap { current ->
                        access.select(selector, current).map {
                            current.copy(executor = it)
                        }
                    }
                    index += 2
                }
                "at" -> {
                    val selector = clauses.required(index + 1, command)
                    val access = entities ?: throw UnsupportedDataPackCommandException(command)
                    contexts = contexts.flatMap { current ->
                        access.select(selector, current).map {
                            current.copy(position = it.physics.position)
                        }
                    }
                    index += 2
                }
                "on" -> {
                    require(clauses.required(index + 1, command) == "passengers") {
                        "Only execute on passengers is supported: $command"
                    }
                    contexts = contexts.flatMap { current ->
                        current.executor?.attachment?.passengers?.map {
                            current.copy(executor = it, position = it.physics.position)
                        } ?: emptyList()
                    }
                    index += 2
                }
                "positioned" -> {
                    val x = clauses.required(index + 1, command)
                    val y = clauses.required(index + 2, command)
                    val z = clauses.required(index + 3, command)
                    contexts = contexts.map { current ->
                        val base = current.position ?: current.executor?.physics?.position ?: origin()
                        val facing = current.executor?.physics?.rotation ?: rotation()
                        current.copy(position = position(x, y, z, base, facing))
                    }
                    index += 4
                }
                "if", "unless" -> {
                    val positive = clauses[index] == "if"
                    when (clauses.required(index + 1, command)) {
                        "entity" -> {
                            val selector = clauses.required(index + 2, command)
                            val access = entities ?: throw UnsupportedDataPackCommandException(command)
                            contexts = contexts.filter { (access.select(selector, it).isNotEmpty()) == positive }
                            index += 3
                        }
                        "score" -> {
                            val holder = clauses.required(index + 2, command)
                            val objective = clauses.required(index + 3, command)
                            require(clauses.required(index + 4, command) == "matches") {
                                "Only execute score matches is supported: $command"
                            }
                            val range = scoreRange(clauses.required(index + 5, command))
                            contexts = contexts.filter {
                                val value = objectives[objective]?.get(scoreHolder(holder, it))
                                ((value != null && value in range)) == positive
                            }
                            index += 6
                        }
                        "data" -> {
                            require(clauses.required(index + 2, command) == "storage") {
                                "Only execute data storage is supported: $command"
                            }
                            val id = ResourceLocation.of(clauses.required(index + 3, command))
                            val predicate = clauses.required(index + 4, command)
                            contexts = contexts.filter {
                                storageMatches(id, predicate) == positive
                            }
                            index += 5
                        }
                        "function" -> {
                            val function = clauses.required(index + 2, command)
                            contexts = contexts.filter {
                                (continuation("function $function", it) != 0) == positive
                            }
                            index += 3
                        }
                        else -> throw UnsupportedDataPackCommandException(command)
                    }
                }
                "store" -> {
                    val mode = when (clauses.required(index + 1, command)) {
                        "result" -> StoreMode.RESULT
                        "success" -> StoreMode.SUCCESS
                        else -> throw UnsupportedDataPackCommandException(command)
                    }
                    when (clauses.required(index + 2, command)) {
                        "score" -> {
                            stores += ExecuteStore.Score(
                                mode,
                                clauses.required(index + 3, command),
                                clauses.required(index + 4, command),
                            )
                            index += 5
                        }
                        "storage" -> {
                            stores += ExecuteStore.Storage(
                                mode,
                                ResourceLocation.of(clauses.required(index + 3, command)),
                                clauses.required(index + 4, command),
                                clauses.required(index + 5, command),
                                clauses.required(index + 6, command).toDouble(),
                            )
                            index += 7
                        }
                        "entity" -> {
                            stores += ExecuteStore.EntityData(
                                mode,
                                clauses.required(index + 3, command),
                                clauses.required(index + 4, command),
                                clauses.required(index + 5, command),
                                clauses.required(index + 6, command).toDouble(),
                            )
                            index += 7
                        }
                        else -> throw UnsupportedDataPackCommandException(command)
                    }
                }
                else -> throw UnsupportedDataPackCommandException(command)
            }
        }

        if (contexts.isEmpty()) {
            stores.forEach { applyStore(it, 0, context) }
            return 0
        }
        var result = if (nested == null) 1 else 0
        for (current in contexts) {
            val currentResult = nested?.let { continuation(it, current) } ?: 1
            stores.forEach { applyStore(it, currentResult, current) }
            result += if (nested == null) 0 else currentResult
        }
        return result
    }

    private fun applyStore(store: ExecuteStore, result: Int, context: DataPackCommandContext) {
        val value = if (store.mode == StoreMode.SUCCESS) {
            if (result != 0) 1 else 0
        } else result
        when (store) {
            is ExecuteStore.Score -> {
                val scores = objectives[store.objective]
                    ?: throw IllegalArgumentException("Unknown scoreboard objective ${store.objective}")
                scores.putScore(scoreHolder(store.holder, context), value)
            }
            is ExecuteStore.Storage -> {
                val root = storageRoot(store.id)
                NbtPath.set(root, store.path, numericValue(value, store.numberType, store.scale))
            }
            is ExecuteStore.EntityData -> {
                val access = entities ?: throw IllegalArgumentException("Entity store requires a local entity authority.")
                for (entity in access.select(store.selector, context)) {
                    NbtPath.set(
                        entity.commandNbt,
                        store.path,
                        numericValue(value, store.numberType, store.scale),
                    )
                    access.synchronize(entity)
                }
            }
        }
    }

    private fun numericValue(value: Int, type: String, scale: Double): Number {
        require(scale.isFinite()) { "Execute store scale must be finite." }
        val scaled = value * scale
        return when (type) {
            "byte" -> scaled.toInt().toByte()
            "short" -> scaled.toInt().toShort()
            "int" -> scaled.toInt()
            "long" -> scaled.toLong()
            "float" -> scaled.toFloat()
            "double" -> scaled
            else -> throw IllegalArgumentException("Unsupported execute store number type $type")
        }
    }

    private fun storageMatches(id: ResourceLocation, predicate: String): Boolean {
        val root = storage[id] ?: return false
        if (!predicate.startsWith('{')) return NbtPath.get(root, predicate) != null
        val expected = SnbtParser.compound(predicate)
        return containsNbt(root, expected)
    }

    private fun containsNbt(actual: Any?, expected: Any?): Boolean {
        if (expected is Map<*, *>) {
            if (actual !is Map<*, *>) return false
            return expected.all { (key, value) -> containsNbt(actual[key], value) }
        }
        if (expected is List<*>) {
            if (actual !is List<*> || actual.size < expected.size) return false
            return expected.indices.all { containsNbt(actual[it], expected[it]) }
        }
        return actual == expected
    }

    private fun scoreRange(source: String): IntRange {
        val parts = source.split("..", limit = 2)
        if (parts.size == 1) {
            val exact = parts[0].toInt()
            return exact..exact
        }
        return (parts[0].toIntOrNull() ?: Int.MIN_VALUE)..(parts[1].toIntOrNull() ?: Int.MAX_VALUE)
    }

    private fun tokenize(source: String): List<String> {
        val tokens = mutableListOf<String>()
        var start = -1
        var square = 0
        var curly = 0
        var quote: Char? = null
        var escaped = false
        for ((index, character) in source.withIndex()) {
            if (start < 0 && !character.isWhitespace()) start = index
            if (start < 0) continue
            if (escaped) {
                escaped = false
                continue
            }
            if (quote != null) {
                if (character == '\\') escaped = true
                else if (character == quote) quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '[' -> square++
                ']' -> square--
                '{' -> curly++
                '}' -> curly--
                else -> if (character.isWhitespace() && square == 0 && curly == 0) {
                    tokens += source.substring(start, index)
                    start = -1
                }
            }
        }
        require(square == 0 && curly == 0 && quote == null) { "Unbalanced execute command: $source" }
        if (start >= 0) tokens += source.substring(start)
        return tokens
    }

    private fun List<String>.required(index: Int, command: String): String {
        return getOrNull(index) ?: throw IllegalArgumentException("Incomplete execute command: $command")
    }

    private fun summon(command: String, context: DataPackCommandContext): Int? {
        if (!command.startsWith("summon ")) return null
        val target = spawn ?: throw UnsupportedDataPackCommandException(command)
        val match = SUMMON.matchEntire(command) ?: throw IllegalArgumentException("Malformed summon command: $command")
        val base = context.position ?: context.executor?.physics?.position ?: origin()
        val position = position(
            match.groupValues[2],
            match.groupValues[3],
            match.groupValues[4],
            base,
            context.executor?.physics?.rotation ?: rotation(),
        )
        val nbt = match.groupValues[5].takeIf(String::isNotBlank)?.let(SnbtParser::compound) ?: emptyMap()
        target(ResourceLocation.of(match.groupValues[1]), nbt, position)
        return 1
    }

    private fun coordinate(source: String, base: Double): Double {
        val result = if (!source.startsWith('~')) {
            source.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid entity coordinate $source")
        } else {
            val offset = source.substring(1).takeIf(String::isNotEmpty)?.toDoubleOrNull()
                ?: if (source.length == 1) 0.0 else throw IllegalArgumentException("Invalid entity coordinate $source")
            base + offset
        }
        require(result.isFinite()) { "Entity coordinate must be finite: $source" }
        return result
    }

    private fun position(
        x: String,
        y: String,
        z: String,
        base: Vec3d,
        facing: EntityRotation,
    ): Vec3d {
        val local = x.startsWith('^') || y.startsWith('^') || z.startsWith('^')
        if (!local) return Vec3d(coordinate(x, base.x), coordinate(y, base.y), coordinate(z, base.z))
        require(x.startsWith('^') && y.startsWith('^') && z.startsWith('^')) {
            "Local coordinates can not be mixed with world coordinates: $x $y $z"
        }
        val leftAmount = localCoordinate(x)
        val upAmount = localCoordinate(y)
        val forwardAmount = localCoordinate(z)
        val forward = facing.front
        val yaw = Math.toRadians(-facing.yaw.toDouble())
        val leftX = kotlin.math.cos(yaw)
        val leftZ = -kotlin.math.sin(yaw)
        val upX = forward.y * leftZ
        val upY = forward.z * leftX - forward.x * leftZ
        val upZ = -forward.y * leftX
        val result = Vec3d(
            base.x + leftX * leftAmount + upX * upAmount + forward.x * forwardAmount,
            base.y + upY * upAmount + forward.y * forwardAmount,
            base.z + leftZ * leftAmount + upZ * upAmount + forward.z * forwardAmount,
        )
        require(result.x.isFinite() && result.y.isFinite() && result.z.isFinite()) {
            "Local entity position must be finite."
        }
        return result
    }

    private fun localCoordinate(source: String): Double {
        val offset = source.substring(1)
        val result = if (offset.isEmpty()) 0.0 else offset.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid local coordinate $source")
        require(result.isFinite()) { "Local coordinate must be finite: $source" }
        return result
    }

    fun score(holder: String, objective: String): Int? = objectives[objective]?.get(holder)

    fun storage(id: ResourceLocation): Map<String, Any>? = storage[id]?.deepCopyMap()

    private fun storageRoot(id: ResourceLocation): MutableMap<String, Any> {
        storage[id]?.let { return it }
        require(storage.size < MAX_STORAGE_ROOTS) {
            "Local command storage exceeds the $MAX_STORAGE_ROOTS root limit."
        }
        return linkedMapOf<String, Any>().also { storage[id] = it }
    }

    override fun arguments(storage: ResourceLocation, path: String): Map<String, String> {
        val root = this.storage[storage] ?: throw IllegalArgumentException("Unknown command storage $storage")
        val value = NbtPath.get(root, path)
            ?: throw IllegalArgumentException("Missing command storage path $storage $path")
        require(value is Map<*, *>) { "Function macro source $storage $path must be a compound." }
        return value.entries.associate { it.key.toString() to SnbtParser.stringify(requireNotNull(it.value)) }
    }

    private fun scoreboard(command: String, context: DataPackCommandContext): Int? {
        val tokens = command.split(WHITESPACE)
        if (tokens.firstOrNull() != "scoreboard") return null
        require(tokens.size >= 3) { "Incomplete scoreboard command: $command" }
        return when (tokens[1]) {
            "objectives" -> objectives(tokens, command)
            "players" -> players(tokens, command, context)
            else -> throw UnsupportedDataPackCommandException(command)
        }
    }

    private fun objectives(tokens: List<String>, command: String): Int {
        return when (tokens.getOrNull(2)) {
            "add" -> {
                val objective = tokens.getOrNull(3) ?: throw IllegalArgumentException("Missing scoreboard objective in: $command")
                require(tokens.getOrNull(4) == "dummy") { "Only dummy local objectives are supported: $command" }
                require(objective in objectives || objectives.size < MAX_OBJECTIVES) {
                    "Local scoreboard exceeds the $MAX_OBJECTIVES objective limit."
                }
                if (objectives.putIfAbsent(objective, linkedMapOf()) == null) 1 else 0
            }
            "remove" -> if (objectives.remove(tokens.getOrNull(3)) != null) 1 else 0
            else -> throw UnsupportedDataPackCommandException(command)
        }
    }

    private fun players(tokens: List<String>, command: String, context: DataPackCommandContext): Int {
        val action = tokens.getOrNull(2) ?: throw IllegalArgumentException("Missing scoreboard player action: $command")
        if (action == "reset") {
            val holder = scoreHolder(tokens.getOrNull(3) ?: throw IllegalArgumentException("Missing score holder: $command"), context)
            val objective = tokens.getOrNull(4)
            if (holder == "*") {
                if (objective != null) {
                    val removed = objectives[objective]?.size ?: 0
                    objectives[objective]?.clear()
                    return removed
                }
                val removed = objectives.values.sumOf { it.size }
                objectives.values.forEach(MutableMap<String, Int>::clear)
                return removed
            }
            if (objective != null) return if (objectives[objective]?.remove(holder) != null) 1 else 0
            var removed = 0
            objectives.values.forEach { if (it.remove(holder) != null) removed++ }
            return removed
        }

        val holder = scoreHolder(tokens.getOrNull(3) ?: throw IllegalArgumentException("Missing score holder: $command"), context)
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
                scores.putScore(holder, value)
                value
            }
            "operation" -> {
                val operation = tokens.getOrNull(5) ?: throw IllegalArgumentException("Missing scoreboard operation: $command")
                val sourceHolder = scoreHolder(tokens.getOrNull(6) ?: throw IllegalArgumentException("Missing source score holder: $command"), context)
                val sourceObjective = tokens.getOrNull(7) ?: throw IllegalArgumentException("Missing source objective: $command")
                val sourceScores = objectives[sourceObjective] ?: throw IllegalArgumentException("Unknown scoreboard objective $sourceObjective")
                val left = scores[holder] ?: 0
                val right = sourceScores[sourceHolder] ?: 0
                when (operation) {
                    "=" -> scores.putScore(holder, right)
                    "+=" -> scores.putScore(holder, left + right)
                    "-=" -> scores.putScore(holder, left - right)
                    "*=" -> scores.putScore(holder, left * right)
                    "/=" -> scores.putScore(holder, if (right == 0) 0 else left / right)
                    "%=" -> scores.putScore(holder, if (right == 0) 0 else left % right)
                    "<" -> scores.putScore(holder, minOf(left, right))
                    ">" -> scores.putScore(holder, maxOf(left, right))
                    "><" -> {
                        scores.putScore(holder, right)
                        sourceScores.putScore(sourceHolder, left)
                    }
                    else -> throw IllegalArgumentException("Unknown scoreboard operation $operation")
                }
                scores[holder] ?: 0
            }
            else -> throw UnsupportedDataPackCommandException(command)
        }
    }

    private fun scoreHolder(source: String, context: DataPackCommandContext): String {
        if (source != "@s") return source
        return context.executor?.uuid?.toString()
            ?: throw IllegalArgumentException("Score holder @s requires an executing entity.")
    }

    private fun MutableMap<String, Int>.putScore(holder: String, value: Int) {
        require(holder in this || size < MAX_SCORE_HOLDERS_PER_OBJECTIVE) {
            "Local scoreboard objective exceeds the $MAX_SCORE_HOLDERS_PER_OBJECTIVE holder limit."
        }
        this[holder] = value
    }

    private fun entity(command: String, context: DataPackCommandContext): Int? {
        if (
            !command.startsWith("tag ") &&
            !command.startsWith("kill ") &&
            !command.startsWith("tp ") &&
            !command.startsWith("teleport ") &&
            !command.startsWith("ride ") &&
            !command.startsWith("data ")
        ) return null
        val access = entities ?: return if (
            command.startsWith("data ") && !command.contains(" entity ")
        ) null else throw UnsupportedDataPackCommandException(command)

        TAG.matchEntire(command)?.let { match ->
            val selected = access.select(match.groupValues[1], context)
            val tag = match.groupValues[3]
            return selected.count {
                if (match.groupValues[2] == "add") {
                    require(tag in it.commandTags || it.commandTags.size < MAX_ENTITY_TAGS) {
                        "Local entity exceeds the $MAX_ENTITY_TAGS command-tag limit."
                    }
                    it.commandTags.add(tag)
                } else {
                    it.commandTags.remove(tag)
                }
            }
        }
        KILL.matchEntire(command)?.let { match ->
            val selected = access.select(match.groupValues[1], context)
            selected.forEach(access::remove)
            return selected.size
        }
        TELEPORT.matchEntire(command)?.let { match ->
            val selected = access.select(match.groupValues[1], context)
            for (entity in selected) {
                val base = entity.physics.position
                entity.forceTeleport(position(
                    match.groupValues[2],
                    match.groupValues[3],
                    match.groupValues[4],
                    base,
                    entity.physics.rotation,
                ))
                if (match.groupValues[5].isNotBlank()) {
                    val rotation = entity.physics.rotation
                    entity.forceRotate(EntityRotation(
                        angle(match.groupValues[5], rotation.yaw),
                        angle(match.groupValues[6], rotation.pitch),
                    ))
                }
            }
            return selected.size
        }
        RIDE.matchEntire(command)?.let { match ->
            val selected = access.select(match.groupValues[1], context)
            val vehicle = access.select(match.groupValues[2], context).singleOrNull()
                ?: return 0
            require(selected.none { it === vehicle }) { "An entity can not ride itself." }
            selected.forEach { it.attachment.vehicle = vehicle }
            return selected.size
        }
        DATA_ENTITY_MERGE.matchEntire(command)?.let { match ->
            val value = SnbtParser.compound(match.groupValues[2])
            val selected = access.select(match.groupValues[1], context)
            selected.forEach {
                merge(it.commandNbt, value)
                access.synchronize(it)
            }
            return selected.size
        }
        DATA_ENTITY_REMOVE.matchEntire(command)?.let { match ->
            val selected = access.select(match.groupValues[1], context)
            var changed = 0
            selected.forEach {
                if (NbtPath.remove(it.commandNbt, match.groupValues[2])) {
                    access.synchronize(it)
                    changed++
                }
            }
            return changed
        }
        DATA_ENTITY_GET.matchEntire(command)?.let { match ->
            val selected = access.select(match.groupValues[1], context)
            val entity = selected.singleOrNull() ?: return 0
            val value = NbtPath.get(entitySnapshot(entity), match.groupValues[2]) ?: return 0
            return commandResult(value, match.groupValues[3])
        }
        DATA_ENTITY_MODIFY.matchEntire(command)?.let { match ->
            val selected = access.select(match.groupValues[1], context)
            val path = match.groupValues[2]
            val operation = match.groupValues[3]
            val sourceType = match.groupValues[4]
            val source = match.groupValues[5]
            val value = dataSource(sourceType, source, command)
            selected.forEach {
                modifyEntity(it, path, operation, value, command)
                access.synchronize(it)
            }
            return selected.size
        }
        if (command.contains(" entity ")) throw UnsupportedDataPackCommandException(command)
        return null
    }

    private fun modifyEntity(entity: Entity, path: String, operation: String, value: Any, command: String) {
        if (path == "{}") {
            require(value is Map<*, *>) { "Entity root operation requires a compound: $command" }
            @Suppress("UNCHECKED_CAST")
            val compound = value as Map<String, Any>
            when (operation) {
                "set" -> {
                    entity.commandNbt.clear()
                    entity.commandNbt.putAll(compound.mapValues { it.value.deepMutable() })
                }
                "merge" -> merge(entity.commandNbt, compound)
                else -> throw UnsupportedDataPackCommandException(command)
            }
            return
        }
        when (operation) {
            "set" -> NbtPath.set(entity.commandNbt, path, value.deepMutable())
            "merge" -> {
                val target = NbtPath.get(entity.commandNbt, path)
                require(target is MutableMap<*, *> && value is Map<*, *>) { "Data merge requires compounds: $command" }
                @Suppress("UNCHECKED_CAST")
                merge(target as MutableMap<String, Any>, value as Map<String, Any>)
            }
            "append" -> {
                val target = NbtPath.get(entity.commandNbt, path)
                require(target is MutableList<*>) { "Data append requires a list target: $command" }
                require(target.size < MAX_NBT_COLLECTION_SIZE) {
                    "Entity NBT list exceeds the $MAX_NBT_COLLECTION_SIZE element limit."
                }
                @Suppress("UNCHECKED_CAST")
                (target as MutableList<Any>) += value.deepMutable()
            }
            else -> throw UnsupportedDataPackCommandException(command)
        }
    }

    private fun dataSource(sourceType: String, source: String, command: String): Any {
        return when (sourceType) {
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
    }

    private fun entitySnapshot(entity: Entity): MutableMap<String, Any> {
        val snapshot = entity.commandNbt.deepCopyMap().mapValuesTo(linkedMapOf()) { it.value.deepMutable() }
        snapshot["Pos"] = mutableListOf(entity.physics.position.x, entity.physics.position.y, entity.physics.position.z)
        snapshot["Rotation"] = mutableListOf(entity.physics.rotation.yaw, entity.physics.rotation.pitch)
        entity.uuid?.let { snapshot["UUID"] = it.toString() }
        snapshot["Tags"] = entity.commandTags.toMutableList()
        return snapshot
    }

    private fun commandResult(value: Any, scaleSource: String): Int {
        val scale = scaleSource.toDoubleOrNull() ?: 1.0
        require(scale.isFinite()) { "Command result scale must be finite." }
        return when (value) {
            is Number -> (value.toDouble() * scale).roundToInt()
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
            is String -> value.length
            else -> 1
        }
    }

    private fun angle(source: String, base: Float): Float {
        val result = if (!source.startsWith('~')) {
            source.toFloatOrNull() ?: throw IllegalArgumentException("Invalid teleport angle $source")
        } else {
            val offset = source.substring(1).takeIf(String::isNotEmpty)?.toFloatOrNull()
                ?: if (source.length == 1) 0.0f else throw IllegalArgumentException("Invalid teleport angle $source")
            base + offset
        }
        require(result.isFinite()) { "Teleport angle must be finite: $source" }
        return result
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
            val root = storageRoot(ResourceLocation.of(match.groupValues[1]))
            merge(root, SnbtParser.compound(match.groupValues[2]))
            return 1
        }
        DATA_MODIFY.matchEntire(command)?.let { match ->
            val id = ResourceLocation.of(match.groupValues[1])
            val path = match.groupValues[2]
            val operation = match.groupValues[3]
            val sourceType = match.groupValues[4]
            val source = match.groupValues[5]
            val value = dataSource(sourceType, source, command)
            val root = storageRoot(id)
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
                    require(target.size < MAX_NBT_COLLECTION_SIZE) {
                        "Storage NBT list exceeds the $MAX_NBT_COLLECTION_SIZE element limit."
                    }
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
                require(key in target || target.size < MAX_NBT_COLLECTION_SIZE) {
                    "NBT compound exceeds the $MAX_NBT_COLLECTION_SIZE entry limit."
                }
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
                is Segment.Key -> {
                    val map = parent as MutableMap<String, Any>
                    require(last.name in map || map.size < MAX_NBT_COLLECTION_SIZE) {
                        "NBT compound exceeds the $MAX_NBT_COLLECTION_SIZE entry limit."
                    }
                    map[last.name] = newValue
                }
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
                            require(map.size < MAX_NBT_COLLECTION_SIZE) {
                                "NBT compound exceeds the $MAX_NBT_COLLECTION_SIZE entry limit."
                            }
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
        const val MAX_OBJECTIVES = 256
        const val MAX_SCORE_HOLDERS_PER_OBJECTIVE = 4096
        const val MAX_STORAGE_ROOTS = 1024
        const val MAX_NBT_COLLECTION_SIZE = 4096
        const val MAX_ENTITY_TAGS = 1024
        val WHITESPACE = Regex("\\s+")
        val DATA_REMOVE = Regex("""data remove storage\s+(\S+)\s+(.+)""")
        val DATA_GET = Regex("""data get storage\s+(\S+)\s+(\S+)(?:\s+([-+]?\d+(?:\.\d+)?))?""")
        val DATA_MERGE = Regex("""data merge storage\s+(\S+)\s+(\{.*})""")
        val DATA_MODIFY = Regex("""data modify storage\s+(\S+)\s+(\S+)\s+(set|merge|append)\s+(value|from storage)\s+(.+)""")
        val SUMMON = Regex("""summon\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)(?:\s+(\{.*}))?""")
        val TAG = Regex("""tag\s+(\S+)\s+(add|remove)\s+(\S+)""")
        val KILL = Regex("""kill\s+(\S+)""")
        val TELEPORT = Regex("""(?:tp|teleport)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)(?:\s+(\S+)\s+(\S+))?""")
        val RIDE = Regex("""ride\s+(\S+)\s+mount\s+(\S+)""")
        val DATA_ENTITY_MERGE = Regex("""data merge entity\s+(\S+)\s+(\{.*})""")
        val DATA_ENTITY_REMOVE = Regex("""data remove entity\s+(\S+)\s+(\S+)""")
        val DATA_ENTITY_GET = Regex("""data get entity\s+(\S+)\s+(\S+)(?:\s+([-+]?\d+(?:\.\d+)?))?""")
        val DATA_ENTITY_MODIFY = Regex("""data modify entity\s+(\S+)\s+(\S+)\s+(set|merge|append)\s+(value|from storage)\s+(.+)""")
    }
}
