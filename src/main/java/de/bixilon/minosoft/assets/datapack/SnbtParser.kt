/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.datapack

/**
 * Renderer- and protocol-independent parser for the SNBT values used by
 * generated data-pack commands. It intentionally returns ordinary immutable
 * Kotlin collections and scalar values so local/headless command execution does
 * not depend on network NBT codecs.
 */
object SnbtParser {
    fun parse(source: String): Any {
        require(source.length <= MAX_SOURCE_LENGTH) { "SNBT input exceeds $MAX_SOURCE_LENGTH characters." }
        val parser = Parser(source)
        val value = parser.value()
        parser.whitespace()
        require(parser.end) { "Unexpected SNBT input at ${parser.index}: ${source.substring(parser.index)}" }
        return value
    }

    fun compound(source: String): Map<String, Any> {
        val value = parse(source)
        require(value is Map<*, *>) { "Expected an SNBT compound." }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any>
    }

    fun stringify(value: Any): String = when (value) {
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
            key(it.key.toString()) + ":" + stringify(requireNotNull(it.value))
        }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(requireNotNull(it)) }
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Byte -> "${value}b"
        is Short -> "${value}s"
        is Long -> "${value}L"
        is Float -> "${value}f"
        is Double -> "${value}d"
        else -> value.toString()
    }

    private fun key(value: String): String {
        if (value.matches(UNQUOTED_KEY)) return value
        return stringify(value)
    }

    private val UNQUOTED_KEY = Regex("[A-Za-z0-9_.+-]+")

    private class Parser(private val source: String) {
        var index = 0
            private set
        private var depth = 0
        private var elements = 0
        val end get() = index >= source.length

        fun whitespace() {
            while (!end && source[index].isWhitespace()) index++
        }

        fun value(): Any {
            whitespace()
            require(!end) { "Expected an SNBT value at end of input." }
            return when (source[index]) {
                '{' -> nested(::compound)
                '[' -> nested(::list)
                '"', '\'' -> quoted()
                else -> scalar(token())
            }
        }

        private fun compound(): Map<String, Any> {
            expect('{')
            val result = linkedMapOf<String, Any>()
            whitespace()
            if (consume('}')) return result
            while (true) {
                whitespace()
                val key = when (source.getOrNull(index)) {
                    '"', '\'' -> quoted()
                    else -> token(stopAtColon = true)
                }
                whitespace()
                expect(':')
                result[key] = value()
                recordElement()
                whitespace()
                if (consume('}')) return result
                expect(',')
            }
        }

        private fun list(): List<Any> {
            expect('[')
            whitespace()
            if (consume(']')) return emptyList()

            // Typed arrays use [B;...], [I;...], and [L;...]. The scalar
            // parser already retains the corresponding numeric widths.
            if (source.getOrNull(index)?.uppercaseChar() in setOf('B', 'I', 'L') && source.getOrNull(index + 1) == ';') {
                index += 2
            }

            val result = mutableListOf<Any>()
            while (true) {
                result += value()
                recordElement()
                whitespace()
                if (consume(']')) return result
                expect(',')
            }
        }

        private fun quoted(): String {
            val quote = source[index++]
            val result = StringBuilder()
            var escaped = false
            while (!end) {
                val character = source[index++]
                if (escaped) {
                    result.append(
                        when (character) {
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> character
                        },
                    )
                    escaped = false
                    continue
                }
                if (character == '\\') {
                    escaped = true
                    continue
                }
                if (character == quote) return result.toString()
                result.append(character)
            }
            throw IllegalArgumentException("Unterminated SNBT string.")
        }

        private fun token(stopAtColon: Boolean = false): String {
            val start = index
            while (!end) {
                val character = source[index]
                if (character.isWhitespace() || character == ',' || character == '}' || character == ']' || (stopAtColon && character == ':')) break
                index++
            }
            require(index > start) { "Expected an SNBT token at $index." }
            return source.substring(start, index)
        }

        private fun scalar(token: String): Any {
            if (token.equals("true", ignoreCase = true)) return true
            if (token.equals("false", ignoreCase = true)) return false
            val suffix = token.lastOrNull()?.lowercaseChar()
            val number = if (suffix in setOf('b', 's', 'l', 'f', 'd')) token.dropLast(1) else token
            return try {
                when (suffix) {
                    'b' -> number.toByte()
                    's' -> number.toShort()
                    'l' -> number.toLong()
                    'f' -> number.toFloat()
                    'd' -> number.toDouble()
                    else -> if (number.contains('.') || number.contains('e', ignoreCase = true)) number.toDouble() else number.toInt()
                }
            } catch (_: NumberFormatException) {
                token
            }
        }

        private fun expect(character: Char) {
            whitespace()
            require(consume(character)) { "Expected '$character' at SNBT offset $index." }
        }

        private fun consume(character: Char): Boolean {
            if (source.getOrNull(index) != character) return false
            index++
            return true
        }

        private inline fun <T> nested(block: () -> T): T {
            require(++depth <= MAX_DEPTH) { "SNBT nesting exceeds $MAX_DEPTH." }
            return try {
                block()
            } finally {
                depth--
            }
        }

        private fun recordElement() {
            require(++elements <= MAX_ELEMENTS) { "SNBT value exceeds $MAX_ELEMENTS collection elements." }
        }
    }

    private const val MAX_SOURCE_LENGTH = 1024 * 1024
    private const val MAX_DEPTH = 64
    private const val MAX_ELEMENTS = 65_536
}
