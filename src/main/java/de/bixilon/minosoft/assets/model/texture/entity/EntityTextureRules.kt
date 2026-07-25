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
            "biomeTag" to "biome_tag",
            "biomeTags" to "biome_tag",
            "heights" to "height",
            "professions" to "profession",
            "item" to "items",
            "jumpStrength" to "jump_strength",
            "jumpHeight" to "jump_strength",
            "llamaInventory" to "llama_inventory",
            "hiddenGene" to "hidden_gene",
            "gene" to "hidden_gene",
            "maxSpeed" to "speed",
            "speeds" to "speed",
            "moonPhase" to "moon_phase",
            "dayTime" to "day_time",
            "timeOfDay" to "day_time",
            "isAngry" to "angry",
            "is_angry" to "angry",
            "aggressive" to "angry",
            "is_aggressive" to "angry",
            "isBaby" to "baby",
            "is_baby" to "baby",
            "creeperCharged" to "charged",
            "creeper_charged" to "charged",
            "isClientPlayer" to "client_player",
            "clientPlayer" to "client_player",
            "isCreative" to "creative",
            "distanceFromPlayer" to "distance",
            "maxHealth" to "max_health",
            "is_moving" to "moving",
            "playerCreated" to "player_created",
            "screamingGoat" to "screaming_goat",
            "isSpawner" to "spawner",
            "isTeammate" to "teammate",
            "clientGameMode" to "client_game_mode",
            "minecraftVersion" to "minecraft_version",
            "modLoaded" to "mod_loaded",
            "modsLoaded" to "mod_loaded",
            "nbtClient" to "nbt_client",
            "nbtVehicle" to "nbt_vehicle",
            "block" to "blocks",
            "blockAbove" to "block_above",
            "blockAboveSolid" to "block_above_solid",
            "blockBelow" to "block_below",
            "blockBelowSolid" to "block_below_solid",
            "blockSpawned" to "block_spawned",
            "monthDay" to "month_day",
            "dayMonth" to "month_day",
            "weekDay" to "week_day",
            "dayWeek" to "week_day",
            "yearDay" to "year_day",
            "dayYear" to "year_day",
            "regionalDifficulty" to "regional_difficulty",
            "Difficulty" to "difficulty",
            "textureRule" to "texture_rule",
            "textureSuffix" to "texture_suffix",
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
    private val orderedRules = rules.sortedBy(EntityTextureRule::index)

    init {
        require(rules.map { it.index }.distinct().size == rules.size) { "Duplicate entity texture rule index." }
    }

    fun select(context: EntityTextureContext): Int {
        return selectResult(context).suffix
    }

    fun selectResult(context: EntityTextureContext): EntityTextureSelection {
        val rule = orderedRules.firstOrNull { it.matches(context) }
            ?: return EntityTextureSelection(ruleIndex = 0, suffix = 1)
        return EntityTextureSelection(rule.index, rule.select(context))
    }
}

data class EntityTextureSelection(
    val ruleIndex: Int,
    val suffix: Int,
)

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
        nbtCondition(condition.key)?.let { (prefix, key) ->
            return matchesNbt(prefix, key, condition.value, context)
        }
        if (condition.key == "items" || condition.key == "item") {
            return matchesItems(condition.value, context)
        }
        if (condition.key in BLOCK_KEYS) {
            return matchesBlocks(condition.key, condition.value, context)
        }
        context.boolean(condition.key)?.let { actual ->
            return condition.value.split(WHITESPACE).any { it.equals(actual.toString(), ignoreCase = true) }
        }
        if (condition.key == "health" && '%' in condition.value) {
            context.number("health_percent")?.let { actual ->
                return numberMatches(actual, condition.value.replace("%", ""))
            }
        }
        context.number(condition.key)?.let { actual -> return numberMatches(actual, condition.value) }
        val actual = context.strings(condition.key) ?: return false
        val expected = condition.value.split(WHITESPACE).filter(String::isNotBlank)
        if (condition.key == "minecraft_version" || condition.key == "minecraftVersion") {
            return actual.any { candidate -> expected.any { semanticVersionMatches(candidate, it) } }
        }
        return stringMatches(actual, expected)
    }

    private fun stringMatches(actual: List<String>, expected: List<String>): Boolean {
        val excluded = expected.filter { it.startsWith('!') && it.length > 1 }.map { it.drop(1) }
        if (actual.any { candidate -> excluded.any { patternMatches(candidate, it) } }) return false
        val included = expected.filterNot { it.startsWith('!') }
        return included.isEmpty() || actual.any { candidate -> included.any { patternMatches(candidate, it) } }
    }

    private fun matchesItems(input: String, context: EntityTextureContext): Boolean {
        val expected = input.split(WHITESPACE).filter(String::isNotBlank)
        if (expected.size == 1) {
            val special = when (expected.single().lowercase()) {
                "none" -> "items_none"
                "any" -> "items_any"
                "holding" -> "items_holding"
                "wearing" -> "items_wearing"
                else -> null
            }
            if (special != null) return context.boolean(special) == true
        }
        return stringMatches(context.strings("items").orEmpty(), expected)
    }

    /**
     * ETF 7.0.13 first compares the block identifier, then—for non-regex
     * values containing state separators—accepts a state when every colon
     * segment occurs in its `block:property=value` representation. Preserve
     * that unusual subset behavior without materializing every property
     * combination in the per-entity context.
     */
    private fun matchesBlocks(key: String, input: String, context: EntityTextureContext): Boolean {
        val actual = context.strings(key).orEmpty()
        val expected = input.split(WHITESPACE).filter(String::isNotBlank)
        val excluded = expected.filter { it.startsWith('!') && it.length > 1 }.map { it.drop(1) }
        if (excluded.any { blockTokenMatches(actual, it) }) return false
        val included = expected.filterNot { it.startsWith('!') }
        return included.isEmpty() || included.any { blockTokenMatches(actual, it) }
    }

    private fun blockTokenMatches(actual: List<String>, expected: String): Boolean {
        if (actual.any { patternMatches(it, expected) }) return true
        if (expected.startsWith("regex:") || expected.startsWith("iregex:") ||
            expected.startsWith("pattern:") || expected.startsWith("ipattern:")
        ) {
            return false
        }
        val segments = expected.split(':').filter(String::isNotEmpty)
        if (segments.size < 2) return false
        return actual.any { candidate ->
            candidate.count { it == ':' } >= 2 && segments.all(candidate::contains)
        }
    }

    private fun nbtCondition(key: String): Pair<String, String>? {
        val normalized = when {
            key.startsWith("nbt.") -> "nbt" to key.removePrefix("nbt.")
            key.startsWith("nbtClient.") -> "nbt_client" to key.removePrefix("nbtClient.")
            key.startsWith("nbt_client.") -> "nbt_client" to key.removePrefix("nbt_client.")
            key.startsWith("nbtVehicle.") -> "nbt_vehicle" to key.removePrefix("nbtVehicle.")
            key.startsWith("nbt_vehicle.") -> "nbt_vehicle" to key.removePrefix("nbt_vehicle.")
            else -> return null
        }
        return normalized.takeIf { it.second.isNotBlank() }
    }

    /**
     * Evaluates the OptiFine/ETF NBT query forms shared by ETF properties and
     * EMF's raw nbt(key, query) expression method.
     */
    fun matchesNbt(key: String, rawQuery: String, context: EntityTextureContext): Boolean {
        return matchesNbt("nbt", key, rawQuery, context)
    }

    private fun matchesNbt(prefix: String, key: String, rawQuery: String, context: EntityTextureContext): Boolean {
        if (key.isBlank() || key.length > MAX_PATTERN_LENGTH || rawQuery.length > MAX_MATCH_INPUT) return false
        var query = rawQuery.trim()
        if (query.startsWith("print_all:")) query = query.removePrefix("print_all:")
        if (query.startsWith("print:")) query = query.removePrefix("print:")
        val inverted = query.startsWith('!')
        if (inverted) query = query.drop(1)

        val path = "$prefix.$key"
        val keys = (context.strings.keys + context.numbers.keys + context.booleans.keys)
            .asSequence()
            .filter { nbtPathMatches(path, it) }
            .take(MAX_NBT_MATCHES + 1)
            .toList()
        if (keys.size > MAX_NBT_MATCHES) return false
        val present = keys.isNotEmpty()
        val result = when {
            query == "exists:true" -> present
            query == "exists:false" -> !present
            query.startsWith("range:") -> keys.any { candidate ->
                context.number(candidate)?.let { numberMatches(it, query.removePrefix("range:")) } == true
            }
            else -> {
                val raw = query.startsWith("raw:")
                val expected = query.removePrefix("raw:")
                keys.any { candidate ->
                    if (!raw && context.number(candidate)?.let { numberMatches(it, expected) } == true) {
                        return@any true
                    }
                    val values = context.strings(candidate)
                        ?: context.number(candidate)?.let { listOf(it.toString()) }
                        ?: context.boolean(candidate)?.let { listOf(it.toString()) }
                        ?: emptyList()
                    values.any { patternMatches(it, expected) }
                }
            }
        }
        return if (inverted) !result else result
    }

    fun snapshot() = custom.keys.sorted()

    private fun nbtPathMatches(pattern: String, actual: String): Boolean {
        if ('*' !in pattern) return pattern == actual
        val expected = pattern.split('.')
        val candidate = actual.split('.')
        if (expected.size != candidate.size) return false
        return expected.indices.all { expected[it] == "*" || expected[it] == candidate[it] }
    }

    private fun numberMatches(actual: Double, input: String): Boolean {
        return input.split(WHITESPACE).filter(String::isNotBlank).any { rawToken ->
            val token = rawToken.replace("%", "")
            RANGE.matchEntire(token)?.let { match ->
                val start = match.groupValues[1].toDouble()
                val end = match.groupValues[2].toDouble()
                return@any actual in minOf(start, end)..maxOf(start, end)
            }
            val expected = token.toDoubleOrNull() ?: return@any false
            actual == expected
        }
    }

    /**
     * ETF 7.0.13 compares Minecraft versions as inclusive dotted semantic
     * version ranges (for example `1.19.4-1.20.4`) rather than as decimal
     * numbers. Snapshot/pre-release suffixes remain ordered after the numeric
     * core so exact strings continue to work without widening a release range.
     */
    private fun semanticVersionMatches(actual: String, input: String): Boolean {
        val range = input.split(SEMVER_RANGE_SEPARATOR, limit = 2)
        val value = SemanticVersion.parse(actual) ?: return false
        val first = SemanticVersion.parse(range[0]) ?: return false
        if (range.size == 1) return value == first
        val second = SemanticVersion.parse(range[1]) ?: return false
        return value >= minOf(first, second) && value <= maxOf(first, second)
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
    private val SEMVER_RANGE_SEPARATOR = Regex("(?<!^)-(?=\\d)")
    private val BLOCK_KEYS = setOf(
        "block",
        "blocks",
        "blockSpawned",
        "block_spawned",
        "blockAbove",
        "block_above",
        "blockAboveSolid",
        "block_above_solid",
        "blockBelow",
        "block_below",
        "blockBelowSolid",
        "block_below_solid",
    )
    private const val MAX_PATTERN_LENGTH = 256
    private const val MAX_MATCH_INPUT = 1024
    private const val MAX_NBT_MATCHES = 4096
    private const val MAX_REGEX_CHARACTER_READS = 100_000

    private class RegexWorkLimitExceeded : RuntimeException()

    private data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val suffix: String,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
            minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
            patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
            if (suffix == other.suffix) return 0
            if (suffix.isEmpty()) return 1
            if (other.suffix.isEmpty()) return -1
            return suffix.compareTo(other.suffix)
        }

        companion object {
            fun parse(input: String): SemanticVersion? {
                val match = SEMVER.matchEntire(input.trim()) ?: return null
                return SemanticVersion(
                    major = match.groupValues[1].toIntOrNull() ?: return null,
                    minor = match.groupValues[2].toIntOrNull() ?: 0,
                    patch = match.groupValues[3].toIntOrNull() ?: 0,
                    suffix = match.groupValues[4],
                )
            }

            private val SEMVER = Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(.*)")
        }
    }

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

class EntityTextureSelectionCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) : AutoCloseable {
    private val selected = linkedMapOf<ResourceLocation, LinkedHashMap<String, EntityTextureSelection>>()
    private val lastSelection = LinkedHashMap<String, EntityTextureSelection>(16, 0.75f, true)
    @Volatile private var closed = false

    init {
        require(capacity > 0) { "Entity texture cache capacity must be positive." }
    }

    fun select(key: EntityTextureCacheKey, rules: EntityTextureRuleSet, context: EntityTextureContext): Int {
        return selectResult(key, rules, context).suffix
    }

    fun selectResult(
        key: EntityTextureCacheKey,
        rules: EntityTextureRuleSet,
        context: EntityTextureContext,
    ): EntityTextureSelection {
        while (true) {
            val previous = synchronized(this) {
                check(!closed) { "Entity texture cache is closed." }
                selected[key.texture]?.get(key.entity)?.let { result ->
                    lastSelection.putBounded(key.entity, result)
                    return result
                }
                lastSelection[key.entity] ?: EMPTY_SELECTION
            }
            val contextual = context.copy(
                numbers = context.numbers + mapOf(
                    "texture_rule" to previous.ruleIndex.toDouble(),
                    "texture_suffix" to previous.suffix.toDouble(),
                ),
            )
            val candidate = rules.selectResult(contextual)
            var retry = false
            synchronized(this) {
                check(!closed) { "Entity texture cache is closed." }
                val textureSelections = selected.getOrPut(key.texture) {
                    LinkedHashMap(16, 0.75f, true)
                }
                textureSelections[key.entity]?.let { result ->
                    lastSelection.putBounded(key.entity, result)
                    return result
                }
                if ((lastSelection[key.entity] ?: EMPTY_SELECTION) != previous) {
                    retry = true
                } else {
                    textureSelections.putBounded(key.entity, candidate)
                    lastSelection.putBounded(key.entity, candidate)
                }
            }
            if (retry) continue
            return candidate
        }
    }

    @Synchronized
    fun invalidate(key: EntityTextureCacheKey) {
        selected[key.texture]?.let {
            it.remove(key.entity)
            if (it.isEmpty()) selected.remove(key.texture)
        }
        lastSelection.remove(key.entity)
    }

    @get:Synchronized
    val size get() = selected.values.sumOf { it.size }

    @Synchronized
    override fun close() {
        closed = true
        selected.clear()
        lastSelection.clear()
    }

    private fun <K, V> LinkedHashMap<K, V>.putBounded(key: K, value: V) {
        if (size >= capacity && key !in this) {
            val eldest = entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        this[key] = value
    }

    companion object {
        const val DEFAULT_CAPACITY = 2048
        private val EMPTY_SELECTION = EntityTextureSelection(ruleIndex = 0, suffix = 0)
    }
}
