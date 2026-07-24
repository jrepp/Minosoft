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

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.util.PriorityQueue

fun interface DataPackCommandSink {
    fun execute(command: String, context: DataPackCommandContext): Int
}

interface DataPackMacroSource {
    fun arguments(storage: ResourceLocation, path: String): Map<String, String>
}

interface DataPackExecuteEnvironment {
    fun execute(
        command: String,
        context: DataPackCommandContext,
        continuation: (String, DataPackCommandContext) -> Int,
    ): Int
}

data class DataPackCommandContext(
    val function: ResourceLocation,
    val depth: Int,
    val tick: Long,
    val executor: Entity? = null,
    val position: Vec3d? = null,
)

class DataPackFunctionRuntime(
    val library: DataPackFunctionLibrary,
    private val sink: DataPackCommandSink,
    private val limits: Limits = Limits(),
) {
    private val scheduled = PriorityQueue<Scheduled>(compareBy(Scheduled::tick, Scheduled::sequence))
    private var sequence = 0L
    var tick: Long = 0
        private set

    fun load() = execute("#minecraft:load")

    fun tick() {
        tick = try {
            Math.addExact(tick, 1)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("Data-pack tick counter overflowed.")
        }
        val budget = Budget(limits.maxCommands)
        execute("#minecraft:tick", emptyMap(), budget)
        while (scheduled.peek()?.tick?.let { it <= tick } == true) {
            val entry = scheduled.remove()
            execute(entry.function, entry.arguments, budget)
        }
    }

    fun execute(reference: String, arguments: Map<String, String> = emptyMap()): Int {
        return execute(reference, arguments, Budget(limits.maxCommands))
    }

    private fun execute(reference: String, arguments: Map<String, String>, budget: Budget): Int {
        var result = 0
        for (function in library.resolve(reference)) {
            result = execute(function, arguments, 0, budget, null)
        }
        return result
    }

    private fun execute(
        function: DataPackFunction,
        arguments: Map<String, String>,
        depth: Int,
        budget: Budget,
        inheritedContext: DataPackCommandContext?,
    ): Int {
        require(depth <= limits.maxDepth) { "Data-pack function depth exceeded ${limits.maxDepth} at ${function.id}" }
        val context = DataPackCommandContext(
            function = function.id,
            depth = depth,
            tick = tick,
            executor = inheritedContext?.executor,
            position = inheritedContext?.position,
        )
        var result = 0
        for (source in function.commands) {
            check(--budget.remaining >= 0) { "Data-pack command budget exceeded ${limits.maxCommands} at ${function.id}" }
            val command = expand(source, arguments)
            RETURN_VALUE.matchEntire(command)?.let { return it.groupValues[1].toInt() }
            if (command == "return" || command == "return fail") return 0
            RETURN_RUN.matchEntire(command)?.let {
                return executeNestedCommand(it.groupValues[1], function, depth, budget, context)
            }
            val withStorage = FUNCTION_WITH_STORAGE.matchEntire(command)
            if (withStorage != null) {
                val macroSource = sink as? DataPackMacroSource
                    ?: throw IllegalArgumentException("${function.id} requires command-storage macro arguments.")
                val nestedArguments = macroSource.arguments(
                    ResourceLocation.of(withStorage.groupValues[2]),
                    withStorage.groupValues[3],
                )
                result = executeReference(withStorage.groupValues[1], nestedArguments, depth, budget, function.id, context)
                continue
            }
            val nested = FUNCTION.matchEntire(command)
            if (nested != null) {
                result = executeReference(
                    nested.groupValues[1],
                    parseArguments(nested.groupValues[2]),
                    depth,
                    budget,
                    function.id,
                    context,
                )
                continue
            }
            val schedule = SCHEDULE.matchEntire(command)
            if (schedule != null) {
                schedule(schedule.groupValues[1], parseDelay(schedule.groupValues[2]), schedule.groupValues[3] == "replace")
                result = 1
                continue
            }
            result = executeCommand(command, function, depth, budget, context)
        }
        return result
    }

    private fun executeNestedCommand(
        command: String,
        owner: DataPackFunction,
        depth: Int,
        budget: Budget,
        context: DataPackCommandContext,
    ): Int {
        check(--budget.remaining >= 0) { "Data-pack command budget exceeded ${limits.maxCommands} at ${owner.id}" }
        return executeCommand(command, owner, depth, budget, context)
    }

    private fun executeCommand(
        command: String,
        owner: DataPackFunction,
        depth: Int,
        budget: Budget,
        context: DataPackCommandContext,
    ): Int {
        RETURN_VALUE.matchEntire(command)?.let { return it.groupValues[1].toInt() }
        if (command == "return" || command == "return fail") return 0
        RETURN_RUN.matchEntire(command)?.let {
            return executeNestedCommand(it.groupValues[1], owner, depth, budget, context)
        }
        if (command.startsWith("execute ")) {
            val environment = sink as? DataPackExecuteEnvironment
                ?: throw IllegalArgumentException("${owner.id} requires an execute-command environment.")
            return environment.execute(command, context) { nested, nestedContext ->
                executeNestedCommand(nested, owner, depth, budget, nestedContext)
            }
        }
        FUNCTION_WITH_STORAGE.matchEntire(command)?.let {
            val macroSource = sink as? DataPackMacroSource
                ?: throw IllegalArgumentException("${owner.id} requires command-storage macro arguments.")
            return executeReference(
                it.groupValues[1],
                macroSource.arguments(ResourceLocation.of(it.groupValues[2]), it.groupValues[3]),
                depth,
                budget,
                owner.id,
                context,
            )
        }
        FUNCTION.matchEntire(command)?.let {
            return executeReference(it.groupValues[1], parseArguments(it.groupValues[2]), depth, budget, owner.id, context)
        }
        return sink.execute(command, context)
    }

    private fun executeReference(
        reference: String,
        arguments: Map<String, String>,
        depth: Int,
        budget: Budget,
        owner: ResourceLocation,
        context: DataPackCommandContext,
    ): Int {
        val targets = library.resolve(reference)
        if (targets.isEmpty()) throw IllegalArgumentException("$owner references missing function $reference")
        var result = 0
        for (target in targets) result = execute(target, arguments, depth + 1, budget, context)
        return result
    }

    fun schedule(
        reference: String,
        delayTicks: Long,
        replace: Boolean = false,
        arguments: Map<String, String> = emptyMap(),
    ) {
        require(delayTicks >= 0) { "Scheduled function delay must not be negative." }
        if (replace) scheduled.removeIf { it.function == reference }
        require(scheduled.size < limits.maxScheduled) { "Scheduled function limit exceeded ${limits.maxScheduled}." }
        val scheduledTick = try {
            Math.addExact(tick, delayTicks)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Scheduled function tick overflowed.")
        }
        require(sequence != Long.MAX_VALUE) { "Scheduled function sequence overflowed." }
        scheduled += Scheduled(scheduledTick, sequence++, reference, arguments.toMap())
    }

    val scheduledCount get() = scheduled.size

    private fun expand(source: String, arguments: Map<String, String>): String {
        if (!source.startsWith('$')) return source
        return MACRO.replace(source.removePrefix("$")) { match ->
            arguments[match.groupValues[1]]
                ?: throw IllegalArgumentException("Missing function macro argument ${match.groupValues[1]}")
        }
    }

    private fun parseArguments(input: String): Map<String, String> {
        val compound = input.trim()
        if (compound.isEmpty()) return emptyMap()
        require(compound.startsWith('{') && compound.endsWith('}')) { "Function macro arguments must be an SNBT compound: $input" }
        val body = compound.substring(1, compound.length - 1)
        val result = linkedMapOf<String, String>()
        for (entry in splitTopLevel(body)) {
            val colon = topLevelColon(entry)
            require(colon > 0) { "Invalid function macro argument: $entry" }
            result[entry.substring(0, colon).trim().trim('"', '\'')] = entry.substring(colon + 1).trim()
        }
        return result
    }

    private fun splitTopLevel(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for ((index, character) in input.withIndex()) {
            if (escaped) {
                escaped = false
                continue
            }
            if (character == '\\' && quote != null) {
                escaped = true
                continue
            }
            if (quote != null) {
                if (character == quote) quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '{', '[' -> depth++
                '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    result += input.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        require(quote == null && depth == 0) { "Unbalanced function macro compound: $input" }
        result += input.substring(start).trim()
        return result.filter(String::isNotEmpty)
    }

    private fun topLevelColon(input: String): Int {
        var depth = 0
        var quote: Char? = null
        for ((index, character) in input.withIndex()) {
            if (quote != null) {
                if (character == quote && input.getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '{', '[' -> depth++
                '}', ']' -> depth--
                ':' -> if (depth == 0) return index
            }
        }
        return -1
    }

    private fun parseDelay(input: String): Long {
        val match = DELAY.matchEntire(input) ?: throw IllegalArgumentException("Invalid schedule delay: $input")
        val value = match.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("Schedule delay is too large: $input")
        return try {
            when (match.groupValues[2]) {
                "", "t" -> value
                "s" -> Math.multiplyExact(value, 20)
                "d" -> Math.multiplyExact(value, 24_000)
                else -> error("unreachable")
            }
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Schedule delay is too large: $input")
        }
    }

    data class Limits(
        val maxDepth: Int = 64,
        val maxCommands: Int = 65_536,
        val maxScheduled: Int = 16_384,
    )

    private data class Budget(var remaining: Int)
    private data class Scheduled(
        val tick: Long,
        val sequence: Long,
        val function: String,
        val arguments: Map<String, String>,
    )

    private companion object {
        val FUNCTION = Regex("""function\s+([#a-z0-9_.-]+:[a-z0-9_./-]+)(?:\s+(\{.*\}))?""")
        val FUNCTION_WITH_STORAGE = Regex("""function\s+([#a-z0-9_.-]+:[a-z0-9_./-]+)\s+with\s+storage\s+([a-z0-9_.-]+:[a-z0-9_./-]+)\s+(\S+)""")
        val SCHEDULE = Regex("""schedule\s+function\s+([#a-z0-9_.-]+:[a-z0-9_./-]+)\s+(\d+[tsd]?)(?:\s+(append|replace))?""")
        val RETURN_VALUE = Regex("""return\s+(-?\d+)""")
        val RETURN_RUN = Regex("""return\s+run\s+(.+)""")
        val DELAY = Regex("""(\d+)([tsd]?)""")
        val MACRO = Regex("""\$\(([a-zA-Z0-9_.-]+)\)""")
    }
}
