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
import java.io.InputStream
import java.util.Properties

fun interface EntityTextureRuleParser {
    fun parse(source: ResourceLocation, input: InputStream): EntityTextureRuleSet
}

object OptifineEntityTexturePropertiesParser : EntityTextureRuleParser {
    override fun parse(source: ResourceLocation, input: InputStream): EntityTextureRuleSet {
        val properties = Properties()
        val bytes = input.use { it.readNBytes(MAX_PROPERTIES_BYTES + 1) }
        require(bytes.size <= MAX_PROPERTIES_BYTES) { "$source exceeds the $MAX_PROPERTIES_BYTES byte properties limit." }
        bytes.inputStream().use(properties::load)
        require(properties.size <= MAX_PROPERTIES) { "$source exceeds the $MAX_PROPERTIES property limit." }
        val rules = linkedMapOf<Int, MutableRule>()

        for ((rawKey, rawValue) in properties) {
            val key = rawKey.toString()
            val value = rawValue.toString().trim()
            val match = RULE_KEY.matchEntire(key) ?: continue
            val prefix = match.groupValues[1]
            val index = match.groupValues[2].toInt()
            val suffix = match.groupValues[3].removePrefix(".")
            val rule = rules.getOrPut(index) { MutableRule(index) }
            when (prefix) {
                "skins", "textures" -> rule.suffixes = positiveIntegers(source, key, value)
                "weights" -> rule.weights = nonNegativeIntegers(source, key, value)
                else -> {
                    val conditionKey = if (suffix.isBlank()) prefix else "$prefix.$suffix"
                    rule.conditions += EntityTextureCondition(conditionKey, value)
                }
            }
        }

        val parsed = rules.values.sortedBy { it.index }.map { rule ->
            val suffixes = rule.suffixes ?: throw IllegalArgumentException("$source: rule ${rule.index} has conditions but no skins/textures entry.")
            EntityTextureRule(rule.index, suffixes, rule.weights ?: emptyList(), rule.conditions.toList())
        }
        return EntityTextureRuleSet(source, parsed)
    }

    private fun positiveIntegers(source: ResourceLocation, key: String, value: String): List<Int> {
        val values = integers(source, key, value)
        require(values.all { it > 0 }) { "$source: $key values must be positive." }
        return values
    }

    private fun nonNegativeIntegers(source: ResourceLocation, key: String, value: String): List<Int> {
        val values = integers(source, key, value)
        require(values.all { it >= 0 }) { "$source: $key values must not be negative." }
        return values
    }

    private fun integers(source: ResourceLocation, key: String, value: String): List<Int> {
        val values = value.split(WHITESPACE).filter(String::isNotBlank).map {
            it.toIntOrNull() ?: throw IllegalArgumentException("$source: $key contains non-integer '$it'.")
        }
        require(values.isNotEmpty()) { "$source: $key must not be empty." }
        return values
    }

    private data class MutableRule(
        val index: Int,
        var suffixes: List<Int>? = null,
        var weights: List<Int>? = null,
        val conditions: MutableList<EntityTextureCondition> = mutableListOf(),
    )

    private val RULE_KEY = Regex("(.+?)\\.(\\d+)(.*)")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_PROPERTIES_BYTES = 1024 * 1024
    private const val MAX_PROPERTIES = 16_384
}

object EntityTextureRuleParsers {
    private val parsers = linkedMapOf<String, EntityTextureRuleParser>()

    @Synchronized
    fun register(id: String, parser: EntityTextureRuleParser): AutoCloseable {
        require(id.isNotBlank()) { "Entity texture parser id must not be blank." }
        require(parsers.putIfAbsent(id, parser) == null) { "Entity texture parser is already registered: $id" }
        return AutoCloseable {
            synchronized(this) {
                parsers.remove(id, parser)
            }
        }
    }

    @Synchronized
    fun parser(): EntityTextureRuleParser? {
        val values = parsers.values.toList()
        require(values.size <= 1) { "Multiple entity texture properties parsers are active." }
        return values.singleOrNull()
    }

    @Synchronized
    fun snapshot() = parsers.keys.toList()
}
