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

package de.bixilon.minosoft.assets.model.texture.entity

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class EntityTextureContext(
    val seed: Long,
    val strings: Map<String, List<String>> = emptyMap(),
    val numbers: Map<String, Double> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
) {
    fun strings(key: String) = strings[key] ?: strings[ALIASES[key]]
    fun number(key: String) = numbers[key] ?: numbers[ALIASES[key]]
    fun boolean(key: String) = booleans[key] ?: booleans[ALIASES[key]]

    companion object {
        private val ALIASES = mapOf(
            "biomes" to "biome",
            "heights" to "height",
            "professions" to "profession",
            "moonPhase" to "moon_phase",
            "dayTime" to "day_time",
            "weather" to "weather",
        )
    }
}

data class EntityTextureCondition(
    val key: String,
    val value: String,
)

data class EntityTextureRule(
    val index: Int,
    val suffixes: List<Int>,
    val weights: List<Int> = emptyList(),
    val conditions: List<EntityTextureCondition> = emptyList(),
) {
    init {
        require(index > 0) { "Entity texture rule index must be positive." }
        require(suffixes.isNotEmpty() && suffixes.all { it > 0 }) { "Entity texture suffixes must be positive." }
        require(weights.isEmpty() || weights.size == suffixes.size) { "Entity texture weights must match suffix count." }
        require(weights.all { it >= 0 }) { "Entity texture weights must not be negative." }
    }

    fun matches(context: EntityTextureContext) = conditions.all { EntityTextureConditions.matches(it, context) }

    fun select(context: EntityTextureContext): Int {
        if (suffixes.size == 1) return suffixes.single()
        val configuredTotal = weights.sumOf(Int::toLong)
        val useConfiguredWeights = weights.isNotEmpty() && configuredTotal > 0
        val selectedWeights = if (useConfiguredWeights) weights else List(suffixes.size) { 1 }
        val total = if (useConfiguredWeights) configuredTotal else suffixes.size.toLong()
        var ticket = floorMod(mix(context.seed xor index.toLong()), total)
        for (index in suffixes.indices) {
            ticket -= selectedWeights[index]
            if (ticket < 0) return suffixes[index]
        }
        return suffixes.last()
    }

    private fun mix(input: Long): Long {
        var value = input
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val remainder = value % divisor
        return if (remainder < 0) remainder + divisor else remainder
    }
}

data class EntityTextureRuleSet(
    val source: ResourceLocation,
    val rules: List<EntityTextureRule>,
) {
    init {
        require(rules.map { it.index }.distinct().size == rules.size) { "Duplicate entity texture rule index." }
    }

    fun select(context: EntityTextureContext): Int {
        val rule = rules.sortedBy { it.index }.firstOrNull { it.matches(context) } ?: return 1
        return rule.select(context)
    }
}

fun interface EntityTextureConditionTester {
    fun matches(condition: EntityTextureCondition, context: EntityTextureContext): Boolean
}

object EntityTextureConditions {
    private val custom = ConcurrentHashMap<String, EntityTextureConditionTester>()

    fun register(key: String, tester: EntityTextureConditionTester): AutoCloseable {
        require(key.isNotBlank()) { "Entity texture condition key must not be blank." }
        require(custom.putIfAbsent(key, tester) == null) { "Entity texture condition tester is already registered: $key" }
        return AutoCloseable { custom.remove(key, tester) }
    }

    fun matches(condition: EntityTextureCondition, context: EntityTextureContext): Boolean {
        custom[condition.key]?.let { return it.matches(condition, context) }
        context.boolean(condition.key)?.let { actual ->
            return condition.value.split(WHITESPACE).any { it.equals(actual.toString(), ignoreCase = true) }
        }
        context.number(condition.key)?.let { actual -> return numberMatches(actual, condition.value) }
        val actual = context.strings(condition.key) ?: return false
        val expected = condition.value.split(WHITESPACE).filter(String::isNotBlank)
        return actual.any { candidate -> expected.any { patternMatches(candidate, it) } }
    }

    fun snapshot() = custom.keys.sorted()

    private fun numberMatches(actual: Double, input: String): Boolean {
        return input.split(WHITESPACE).filter(String::isNotBlank).any { token ->
            RANGE.matchEntire(token)?.let { match ->
                val start = match.groupValues[1].toDouble()
                val end = match.groupValues[2].toDouble()
                return@any actual in minOf(start, end)..maxOf(start, end)
            }
            val expected = token.removeSuffix("%").toDoubleOrNull() ?: return@any false
            if (token.endsWith('%')) abs(actual - expected / 100.0) < 0.000001 else actual == expected
        }
    }

    private fun patternMatches(actual: String, expected: String): Boolean {
        if (actual.length > MAX_MATCH_INPUT || expected.length > MAX_PATTERN_LENGTH) return false
        return when {
            expected.startsWith("regex:") -> boundedRegex(expected.removePrefix("regex:"), actual, false)
            expected.startsWith("iregex:") -> boundedRegex(expected.removePrefix("iregex:"), actual, true)
            expected.startsWith("pattern:") -> wildcard(expected.removePrefix("pattern:"), false).matches(actual)
            expected.startsWith("ipattern:") -> wildcard(expected.removePrefix("ipattern:"), true).matches(actual)
            '*' in expected || '?' in expected -> wildcard(expected, false).matches(actual)
            else -> actual == expected
        }
    }

    private fun boundedRegex(pattern: String, actual: String, ignoreCase: Boolean): Boolean {
        return try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                .matches(BoundedRegexInput(actual))
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: RegexWorkLimitExceeded) {
            false
        }
    }

    private fun wildcard(pattern: String, ignoreCase: Boolean): Regex {
        val expression = buildString {
            append('^')
            for (character in pattern) {
                when (character) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(character.toString()))
                }
            }
            append('$')
        }
        return Regex(expression, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
    }

    private val WHITESPACE = Regex("\\s+")
    private val RANGE = Regex("(-?\\d+(?:\\.\\d+)?)-(-?\\d+(?:\\.\\d+)?)")
    private const val MAX_PATTERN_LENGTH = 256
    private const val MAX_MATCH_INPUT = 1024
    private const val MAX_REGEX_CHARACTER_READS = 100_000

    private class RegexWorkLimitExceeded : RuntimeException()

    private class BoundedRegexInput private constructor(
        private val value: String,
        private val start: Int,
        private val end: Int,
        private val budget: IntArray,
    ) : CharSequence {
        constructor(value: String) : this(value, 0, value.length, intArrayOf(MAX_REGEX_CHARACTER_READS))

        override val length: Int get() = end - start

        override fun get(index: Int): Char {
            if (index !in indices) throw IndexOutOfBoundsException(index)
            if (--budget[0] < 0) throw RegexWorkLimitExceeded()
            return value[start + index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex in 0..length && endIndex in startIndex..length)
            return BoundedRegexInput(value, start + startIndex, start + endIndex, budget)
        }

        override fun toString(): String = value.substring(start, end)
    }
}

data class EntityTextureCacheKey(
    val entity: String,
    val texture: ResourceLocation,
)

class EntityTextureSelectionCache : AutoCloseable {
    private val selected = ConcurrentHashMap<EntityTextureCacheKey, Int>()
    @Volatile private var closed = false

    fun select(key: EntityTextureCacheKey, rules: EntityTextureRuleSet, context: EntityTextureContext): Int {
        check(!closed) { "Entity texture cache is closed." }
        return selected.computeIfAbsent(key) { rules.select(context) }
    }

    fun invalidate(key: EntityTextureCacheKey) {
        selected.remove(key)
    }

    val size get() = selected.size

    override fun close() {
        closed = true
        selected.clear()
    }
}
