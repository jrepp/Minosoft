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

package de.bixilon.minosoft.assets.model.skeletal.expression

import kotlin.math.*

class SkeletalExpressionContext(
    private val variables: Map<String, Double> = emptyMap(),
    private val variableResolver: (String) -> Double? = { null },
    private val rawFunctionResolver: (String, List<String>) -> Double? = { _, _ -> null },
    val random: () -> Double = { 0.0 },
) {
    fun variable(name: String): Double {
        return variables[name] ?: variableResolver(name)
        ?: throw SkeletalExpressionException("Unknown expression variable: $name")
    }

    fun rawFunction(name: String, arguments: List<String>): Double {
        return resolveRawFunction(name, arguments)
            ?: throw SkeletalExpressionException("Unknown raw expression function: $name")
    }

    internal fun resolveRawFunction(name: String, arguments: List<String>) =
        rawFunctionResolver(name, arguments)
}

class SkeletalExpressionException(message: String) : IllegalArgumentException(message)

/**
 * A compiled, reflection-free expression with parse-depth, token, source-size,
 * and per-evaluation operation limits.
 */
class SkeletalExpression private constructor(
    val source: String,
    private val root: Node,
) {
    fun evaluate(context: SkeletalExpressionContext, operationLimit: Int = DEFAULT_OPERATION_LIMIT): Double {
        require(operationLimit > 0) { "Expression operation limit must be positive." }
        return root.evaluate(context, Budget(operationLimit))
    }

    companion object {
        const val MAX_SOURCE_LENGTH = 4096
        const val MAX_TOKENS = 1024
        const val MAX_PARSE_DEPTH = 64
        const val DEFAULT_OPERATION_LIMIT = 4096

        fun compile(source: String): SkeletalExpression {
            if (source.length > MAX_SOURCE_LENGTH) throw SkeletalExpressionException("Expression exceeds $MAX_SOURCE_LENGTH characters.")
            return Parser(source).parse()
        }
    }

    private class Budget(var remaining: Int) {
        fun consume() {
            if (--remaining < 0) throw SkeletalExpressionException("Expression operation limit exceeded.")
        }
    }

    private sealed interface Node {
        fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double
    }

    private data class ConstantNode(val value: Double) : Node {
        override fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double {
            budget.consume()
            return value
        }
    }

    private data class VariableNode(val name: String) : Node {
        override fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double {
            budget.consume()
            return when (name.lowercase()) {
                "pi" -> PI
                "e" -> E
                "true" -> 1.0
                "false" -> 0.0
                else -> context.variable(name)
            }
        }
    }

    private data class UnaryNode(val operator: TokenType, val value: Node) : Node {
        override fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double {
            budget.consume()
            val value = value.evaluate(context, budget)
            return when (operator) {
                TokenType.PLUS -> value
                TokenType.MINUS -> -value
                TokenType.NOT -> value.not().number()
                else -> error("Invalid unary operator.")
            }
        }
    }

    private data class BinaryNode(val left: Node, val operator: TokenType, val right: Node) : Node {
        override fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double {
            budget.consume()
            val left = left.evaluate(context, budget)
            if (operator == TokenType.AND && !left.truth()) return 0.0
            if (operator == TokenType.OR && left.truth()) return 1.0
            val right = right.evaluate(context, budget)
            return when (operator) {
                TokenType.PLUS -> left + right
                TokenType.MINUS -> left - right
                TokenType.STAR -> left * right
                TokenType.SLASH -> left / right
                TokenType.PERCENT -> left % right
                TokenType.LESS -> (left < right).number()
                TokenType.LESS_EQUAL -> (left <= right).number()
                TokenType.GREATER -> (left > right).number()
                TokenType.GREATER_EQUAL -> (left >= right).number()
                TokenType.EQUAL -> (left == right).number()
                TokenType.NOT_EQUAL -> (left != right).number()
                TokenType.AND -> right.truth().number()
                TokenType.OR -> right.truth().number()
                else -> error("Invalid binary operator.")
            }
        }
    }

    private data class ConditionalNode(val condition: Node, val whenTrue: Node, val whenFalse: Node) : Node {
        override fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double {
            budget.consume()
            return if (condition.evaluate(context, budget).truth()) {
                whenTrue.evaluate(context, budget)
            } else {
                whenFalse.evaluate(context, budget)
            }
        }
    }

    private data class FunctionNode(val name: String, val arguments: List<Node>) : Node {
        override fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double {
            budget.consume()
            val name = name.lowercase()
            if (name == "if" || name == "ifb") {
                requireArguments(name, arguments.size, minimum = 3)
                if (arguments.size % 2 == 0) {
                    throw SkeletalExpressionException("Function $name expects an odd number of arguments, got ${arguments.size}.")
                }
                var index = 0
                while (index + 1 < arguments.lastIndex) {
                    if (arguments[index].evaluate(context, budget).truth()) {
                        return arguments[index + 1].evaluate(context, budget)
                    }
                    index += 2
                }
                return arguments.last().evaluate(context, budget)
            }
            if (name == "catch") {
                requireArguments(name, arguments.size, minimum = 2, maximum = 3)
                return try {
                    arguments[0].evaluate(context, budget).takeUnless(Double::isNaN)
                        ?: arguments[1].evaluate(context, budget)
                } catch (_: RuntimeException) {
                    arguments[1].evaluate(context, budget)
                }
            }
            if (name == "keyframe" || name == "keyframeloop") {
                requireArguments(name, arguments.size, minimum = 3)
                return keyframe(name == "keyframeloop", arguments, context, budget)
            }
            if (name == "print" || name == "printb") {
                if (arguments.size != 1 && arguments.size != 3) {
                    throw SkeletalExpressionException("Function $name expects 1 or 3 argument(s), got ${arguments.size}.")
                }
                val value = arguments[if (arguments.size == 1) 0 else 2].evaluate(context, budget)
                return if (name == "printb") value.truth().number() else value
            }

            val values = arguments.map { it.evaluate(context, budget) }
            return when (name) {
                "abs" -> unary(name, values, ::abs)
                "acos" -> unary(name, values, ::acos)
                "asin" -> unary(name, values, ::asin)
                "atan" -> unary(name, values, ::atan)
                "ceil" -> unary(name, values, ::ceil)
                "cos" -> unary(name, values, ::cos)
                "exp" -> unary(name, values, ::exp)
                "floor" -> unary(name, values, ::floor)
                "frac" -> unary(name, values) { it - floor(it) }
                "log" -> unary(name, values, ::ln)
                "round" -> unary(name, values) { round(it) }
                "signum", "sign" -> unary(name, values, ::sign)
                "sin" -> unary(name, values, ::sin)
                "sqrt" -> unary(name, values, ::sqrt)
                "tan" -> unary(name, values, ::tan)
                "todeg" -> unary(name, values) { Math.toDegrees(it) }
                "torad" -> unary(name, values) { Math.toRadians(it) }
                "wrapdeg" -> unary(name, values, ::wrapDegrees)
                "wraprad" -> unary(name, values) { Math.toRadians(wrapDegrees(Math.toDegrees(it))) }
                "atan2" -> binary(name, values, ::atan2)
                "degdiff" -> binary(name, values) { left, right -> abs(wrapDegrees(right - left)) }
                "raddiff" -> binary(name, values) { left, right ->
                    Math.toRadians(abs(wrapDegrees(Math.toDegrees(right) - Math.toDegrees(left))))
                }
                "pow" -> binary(name, values) { left, right -> left.pow(right) }
                "mod" -> binary(name, values) { left, right -> left % right }
                "fmod" -> binary(name, values) { left, right ->
                    Math.floorMod(left.toFloat().toInt(), right.toFloat().toInt()).toDouble()
                }
                "clamp" -> {
                    requireArguments(name, values.size, exact = 3)
                    values[0].coerceIn(values[1], values[2])
                }
                "lerp" -> {
                    requireArguments(name, values.size, exact = 3)
                    values[1] + values[0] * (values[2] - values[1])
                }
                "between" -> {
                    requireArguments(name, values.size, exact = 3)
                    (values[0] in values[1]..values[2]).number()
                }
                "equals" -> {
                    requireArguments(name, values.size, minimum = 2, maximum = 3)
                    val tolerance = values.getOrElse(2) { 0.0 }
                    (abs(values[0] - values[1]) <= tolerance).number()
                }
                "in" -> {
                    requireArguments(name, values.size, minimum = 2)
                    values.drop(1).any { it == values[0] }.number()
                }
                "catmullrom" -> {
                    requireArguments(name, values.size, exact = 5)
                    catmull(values[1], values[2], values[3], values[4], values[0])
                }
                "quadbezier" -> {
                    requireArguments(name, values.size, exact = 4)
                    quadraticBezier(values[0], values[1], values[2], values[3])
                }
                "cubicbezier" -> {
                    requireArguments(name, values.size, exact = 5)
                    cubicBezier(values[0], values[1], values[2], values[3], values[4])
                }
                "hermite" -> {
                    requireArguments(name, values.size, exact = 5)
                    hermite(values[0], values[1], values[2], values[3], values[4])
                }
                "min" -> {
                    requireArguments(name, values.size, minimum = 1)
                    values.min()
                }
                "max" -> {
                    requireArguments(name, values.size, minimum = 1)
                    values.max()
                }
                "random", "rand" -> {
                    requireArguments(name, values.size, maximum = 1)
                    values.firstOrNull()?.let(::seededRandom) ?: context.random()
                }
                "randomb" -> {
                    requireArguments(name, values.size, maximum = 1)
                    ((values.firstOrNull()?.let(::seededRandom) ?: context.random()) >= 0.5).number()
                }
                in EASING_NAMES -> easing(name, values)
                else -> throw SkeletalExpressionException("Unknown expression function: $name")
            }
        }

        private fun keyframe(
            loop: Boolean,
            arguments: List<Node>,
            context: SkeletalExpressionContext,
            budget: Budget,
        ): Double {
            val delta = arguments[0].evaluate(context, budget)
            val frames = arguments.subList(1, arguments.size)
            val floor = floor(delta).toInt()
            if (!loop) {
                val end = frames.lastIndex
                if (floor >= end) return frames[end].evaluate(context, budget)
                if (floor <= 0) return frames[0].evaluate(context, budget)
                val t = delta - floor(delta)
                return catmull(
                    frames[(floor - 1).coerceIn(0, end)].evaluate(context, budget),
                    frames[floor.coerceIn(0, end)].evaluate(context, budget),
                    frames[(floor + 1).coerceIn(0, end)].evaluate(context, budget),
                    frames[(floor + 2).coerceIn(0, end)].evaluate(context, budget),
                    t,
                )
            }
            fun frame(offset: Int): Double {
                val index = Math.floorMod(floor + offset, frames.size)
                return frames[index].evaluate(context, budget)
            }
            return catmull(frame(-1), frame(0), frame(1), frame(2), delta - floor(delta))
        }

        private fun unary(name: String, values: List<Double>, operation: (Double) -> Double): Double {
            requireArguments(name, values.size, exact = 1)
            return operation(values[0])
        }

        private fun binary(name: String, values: List<Double>, operation: (Double, Double) -> Double): Double {
            requireArguments(name, values.size, exact = 2)
            return operation(values[0], values[1])
        }

        private fun requireArguments(name: String, actual: Int, exact: Int? = null, minimum: Int = exact ?: 0, maximum: Int = exact ?: Int.MAX_VALUE) {
            if (actual !in minimum..maximum) {
                val expected = exact?.toString() ?: "$minimum..${if (maximum == Int.MAX_VALUE) "*" else maximum}"
                throw SkeletalExpressionException("Function $name expects $expected argument(s), got $actual.")
            }
        }

        private companion object {
            val EASING_NAMES = setOf(
                "easeinout", "easein", "easeout",
                "cubiceaseinout", "cubiceasein", "cubiceaseout",
                "easeinoutexpo", "easeinexpo", "easeoutexpo",
                "easeinoutcirc", "easeincirc", "easeoutcirc",
                "easeinoutelastic", "easeinelastic", "easeoutelastic",
                "easeinoutback", "easeinback", "easeoutback",
                "easeinoutbounce", "easeinbounce", "easeoutbounce",
                "easeinquad", "easeoutquad", "easeinoutquad",
                "easeincubic", "easeoutcubic", "easeinoutcubic",
                "easeinquart", "easeoutquart", "easeinoutquart",
                "easeinquint", "easeoutquint", "easeinoutquint",
                "easeinsine", "easeoutsine", "easeinoutsine",
            )
        }
    }

    private data class RawFunctionNode(val name: String, val arguments: List<String>) : Node {
        override fun evaluate(context: SkeletalExpressionContext, budget: Budget): Double {
            budget.consume()
            return context.rawFunction(name, arguments)
        }
    }

    private class Parser(private val source: String) {
        private val rawFunctions = extractRawFunctions(source)
        private val tokens = tokenize(rawFunctions.expression)
        private var index = 0
        private var depth = 0

        fun parse(): SkeletalExpression {
            val root = expression()
            consume(TokenType.END, "Unexpected trailing expression input.")
            return SkeletalExpression(source, root)
        }

        private fun expression(): Node = conditional()

        private fun conditional(): Node {
            var node = or()
            if (match(TokenType.QUESTION)) {
                node = nested {
                    val whenTrue = expression()
                    consume(TokenType.COLON, "Expected ':' in conditional expression.")
                    ConditionalNode(node, whenTrue, conditional())
                }
            }
            return node
        }

        private fun or(): Node = binary(::and, TokenType.OR)
        private fun and(): Node = binary(::equality, TokenType.AND)
        private fun equality(): Node = binary(::comparison, TokenType.EQUAL, TokenType.NOT_EQUAL)
        private fun comparison(): Node = binary(::term, TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL)
        private fun term(): Node = binary(::factor, TokenType.PLUS, TokenType.MINUS)
        private fun factor(): Node = binary(::unary, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)

        private fun binary(next: () -> Node, vararg operators: TokenType): Node {
            var node = next()
            while (peek().type in operators) {
                val operator = advance().type
                node = BinaryNode(node, operator, next())
            }
            return node
        }

        private fun unary(): Node {
            if (match(TokenType.PLUS, TokenType.MINUS, TokenType.NOT)) {
                val operator = previous().type
                return nested { UnaryNode(operator, unary()) }
            }
            return primary()
        }

        private fun primary(): Node {
            match(TokenType.NUMBER).also {
                if (it) {
                    val token = previous()
                    val value = token.text.toDoubleOrNull()
                        ?: throw SkeletalExpressionException("Invalid number at character ${token.offset}.")
                    return ConstantNode(value)
                }
            }
            if (match(TokenType.IDENTIFIER)) {
                val name = previous().text
                rawFunctions.calls[name]?.let { return RawFunctionNode(it.name, it.arguments) }
                if (!match(TokenType.LEFT_PAREN)) return VariableNode(name)
                return nested {
                    val arguments = mutableListOf<Node>()
                    if (!check(TokenType.RIGHT_PAREN)) {
                        do {
                            arguments += expression()
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after function arguments.")
                    FunctionNode(name, arguments)
                }
            }
            if (match(TokenType.LEFT_PAREN)) {
                return nested {
                    val node = expression()
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after expression.")
                    node
                }
            }
            throw error("Expected a number, variable, function, or parenthesized expression.")
        }

        private inline fun <T> nested(block: () -> T): T {
            if (++depth > MAX_PARSE_DEPTH) {
                depth--
                throw error("Expression nesting exceeds $MAX_PARSE_DEPTH.")
            }
            return try {
                block()
            } finally {
                depth--
            }
        }

        private fun match(vararg types: TokenType): Boolean {
            if (peek().type !in types) return false
            advance()
            return true
        }

        private fun check(type: TokenType) = peek().type == type
        private fun advance() = tokens[index++]
        private fun peek() = tokens[index]
        private fun previous() = tokens[index - 1]

        private fun consume(type: TokenType, message: String): Token {
            if (check(type)) return advance()
            throw error(message)
        }

        private fun error(message: String): SkeletalExpressionException {
            return SkeletalExpressionException("$message At character ${peek().offset} in '$source'.")
        }
    }

    internal data class Token(val type: TokenType, val text: String, val offset: Int)

    internal enum class TokenType {
        NUMBER,
        IDENTIFIER,
        PLUS,
        MINUS,
        STAR,
        SLASH,
        PERCENT,
        NOT,
        LESS,
        LESS_EQUAL,
        GREATER,
        GREATER_EQUAL,
        EQUAL,
        NOT_EQUAL,
        AND,
        OR,
        QUESTION,
        COLON,
        COMMA,
        LEFT_PAREN,
        RIGHT_PAREN,
        END,
    }
}

private data class RawExpressionFunction(
    val name: String,
    val arguments: List<String>,
)

private data class RawExpressionSource(
    val expression: String,
    val calls: Map<String, RawExpressionFunction>,
)

/**
 * EMF's nbt() method accepts two raw string arguments rather than mathematical
 * expressions. Extract those calls before tokenization while preserving source
 * length so diagnostics still point at the original character offsets.
 */
private fun extractRawFunctions(source: String): RawExpressionSource {
    val expression = source.toCharArray()
    val calls = linkedMapOf<String, RawExpressionFunction>()
    var index = 0
    var placeholderIndex = 0
    while (index < source.length) {
        if (!source.regionMatches(index, "nbt", 0, 3, ignoreCase = true) ||
            (index > 0 && (source[index - 1].isLetterOrDigit() || source[index - 1] == '_'))
        ) {
            index++
            continue
        }
        var open = index + 3
        while (open < source.length && source[open].isWhitespace()) open++
        if (open >= source.length || source[open] != '(') {
            index++
            continue
        }
        var cursor = open + 1
        var escaped = false
        var separator = -1
        var close = -1
        while (cursor < source.length) {
            val character = source[cursor]
            if (escaped) {
                escaped = false
            } else {
                when (character) {
                    '\\' -> escaped = true
                    ',' -> {
                        if (separator >= 0) {
                            throw SkeletalExpressionException(
                                "Function nbt expects escaped additional commas at character $cursor.",
                            )
                        }
                        separator = cursor
                    }
                    ')' -> {
                        close = cursor
                        break
                    }
                }
            }
            cursor++
        }
        if (separator < 0 || close < 0) {
            throw SkeletalExpressionException("Malformed nbt() function at character $index.")
        }
        val first = unescapeRawArgument(source.substring(open + 1, separator).trim())
        val second = unescapeRawArgument(source.substring(separator + 1, close).trim())
        if (first.isEmpty() || second.isEmpty()) {
            throw SkeletalExpressionException("Function nbt expects two non-empty raw arguments.")
        }
        var placeholder: String
        do {
            placeholder = "_r${placeholderIndex++}"
        } while (source.contains(placeholder))
        if (placeholder.length > close - index + 1) {
            throw SkeletalExpressionException("Malformed nbt() function at character $index.")
        }
        for (position in index..close) expression[position] = ' '
        placeholder.toCharArray().copyInto(expression, index)
        calls[placeholder] = RawExpressionFunction("nbt", listOf(first, second))
        index = close + 1
    }
    return RawExpressionSource(String(expression), calls)
}

private fun unescapeRawArgument(value: String): String {
    val result = StringBuilder(value.length)
    var escaped = false
    for (character in value) {
        if (escaped) {
            result.append(character)
            escaped = false
        } else if (character == '\\') {
            escaped = true
        } else {
            result.append(character)
        }
    }
    if (escaped) result.append('\\')
    return result.toString()
}

private fun tokenize(source: String): List<SkeletalExpression.Token> {
    val result = ArrayList<SkeletalExpression.Token>()
    var index = 0
    fun add(type: SkeletalExpression.TokenType, start: Int, end: Int = index) {
        result += SkeletalExpression.Token(type, source.substring(start, end), start)
        if (result.size > SkeletalExpression.MAX_TOKENS) {
            throw SkeletalExpressionException("Expression exceeds ${SkeletalExpression.MAX_TOKENS} tokens.")
        }
    }

    while (index < source.length) {
        val start = index
        when (val char = source[index++]) {
            ' ', '\t', '\r', '\n' -> Unit
            '+' -> add(SkeletalExpression.TokenType.PLUS, start)
            '-' -> add(SkeletalExpression.TokenType.MINUS, start)
            '*' -> add(SkeletalExpression.TokenType.STAR, start)
            '/' -> add(SkeletalExpression.TokenType.SLASH, start)
            '%' -> add(SkeletalExpression.TokenType.PERCENT, start)
            '?' -> add(SkeletalExpression.TokenType.QUESTION, start)
            ':' -> add(SkeletalExpression.TokenType.COLON, start)
            ',' -> add(SkeletalExpression.TokenType.COMMA, start)
            '(' -> add(SkeletalExpression.TokenType.LEFT_PAREN, start)
            ')' -> add(SkeletalExpression.TokenType.RIGHT_PAREN, start)
            '!' -> {
                if (index < source.length && source[index] == '=') {
                    index++
                    add(SkeletalExpression.TokenType.NOT_EQUAL, start)
                } else add(SkeletalExpression.TokenType.NOT, start)
            }
            '<' -> {
                if (index < source.length && source[index] == '=') {
                    index++
                    add(SkeletalExpression.TokenType.LESS_EQUAL, start)
                } else add(SkeletalExpression.TokenType.LESS, start)
            }
            '>' -> {
                if (index < source.length && source[index] == '=') {
                    index++
                    add(SkeletalExpression.TokenType.GREATER_EQUAL, start)
                } else add(SkeletalExpression.TokenType.GREATER, start)
            }
            '=' -> {
                if (index < source.length && source[index] == '=') index++
                add(SkeletalExpression.TokenType.EQUAL, start)
            }
            '&' -> {
                if (index >= source.length || source[index++] != '&') throw SkeletalExpressionException("Expected '&&' at character $start.")
                add(SkeletalExpression.TokenType.AND, start)
            }
            '|' -> {
                if (index >= source.length || source[index++] != '|') throw SkeletalExpressionException("Expected '||' at character $start.")
                add(SkeletalExpression.TokenType.OR, start)
            }
            else -> when {
                char.isDigit() || (char == '.' && index < source.length && source[index].isDigit()) -> {
                    while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
                    if (index < source.length && source[index].lowercaseChar() == 'e') {
                        index++
                        if (index < source.length && source[index] in "+-") index++
                        while (index < source.length && source[index].isDigit()) index++
                    }
                    add(SkeletalExpression.TokenType.NUMBER, start)
                }
                char.isLetter() || char == '_' -> {
                    while (index < source.length && (source[index].isLetterOrDigit() || source[index] in "_.:")) index++
                    add(SkeletalExpression.TokenType.IDENTIFIER, start)
                }
                else -> throw SkeletalExpressionException("Unexpected character '$char' at character $start.")
            }
        }
    }
    result += SkeletalExpression.Token(SkeletalExpression.TokenType.END, "", source.length)
    return result
}

private fun Double.truth() = this != 0.0 && !isNaN()
private fun Double.not() = !truth()
private fun Boolean.number() = if (this) 1.0 else 0.0

private fun wrapDegrees(value: Double): Double {
    var wrapped = value % 360.0
    if (wrapped >= 180.0) wrapped -= 360.0
    if (wrapped < -180.0) wrapped += 360.0
    return wrapped
}

private fun seededRandom(seed: Double): Double {
    var hash = java.lang.Float.floatToIntBits(seed.toFloat())
    hash = hash xor 61 xor (hash shr 16)
    hash += hash shl 3
    hash = hash xor (hash shr 4)
    hash *= 668_265_261
    hash = hash xor (hash shr 15)
    return abs(hash.toFloat()).toDouble() / 2.14748365E9
}

private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5 * (
        2.0 * p1 +
            (-p0 + p2) * t +
            (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
            (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        )
}

private fun quadraticBezier(t: Double, p0: Double, p1: Double, p2: Double): Double {
    val inverse = 1.0 - t
    return inverse * inverse * p0 + 2.0 * inverse * t * p1 + t * t * p2
}

private fun cubicBezier(t: Double, p0: Double, p1: Double, p2: Double, p3: Double): Double {
    val inverse = 1.0 - t
    val inverse2 = inverse * inverse
    val t2 = t * t
    return inverse2 * inverse * p0 + 3.0 * inverse2 * t * p1 + 3.0 * inverse * t2 * p2 + t2 * t * p3
}

private fun hermite(t: Double, p0: Double, p1: Double, m0: Double, m1: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    return (2.0 * t3 - 3.0 * t2 + 1.0) * p0 +
        (t3 - 2.0 * t2 + t) * m0 +
        (-2.0 * t3 + 3.0 * t2) * p1 +
        (t3 - t2) * m1
}

private fun easing(name: String, values: List<Double>): Double {
    if (values.size != 3) {
        throw SkeletalExpressionException("Function $name expects 3 argument(s), got ${values.size}.")
    }
    val t = values[0]
    val start = values[1]
    val delta = values[2] - start
    fun result(progress: Double) = start + delta * progress
    val progress = when (name) {
        "easein", "easeinsine" -> 1.0 - cos(t * PI / 2.0)
        "easeout", "easeoutsine" -> sin(t * PI / 2.0)
        "easeinout", "easeinoutsine" -> -(cos(PI * t) - 1.0) / 2.0
        "easeinquad" -> t * t
        "easeoutquad" -> 1.0 - (1.0 - t).pow(2)
        "easeinoutquad" -> if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0).pow(2) / 2.0
        "cubiceasein", "easeincubic" -> t * t * t
        "cubiceaseout", "easeoutcubic" -> 1.0 - (1.0 - t).pow(3)
        "cubiceaseinout", "easeinoutcubic" -> if (t < 0.5) 4.0 * t.pow(3) else 1.0 - (-2.0 * t + 2.0).pow(3) / 2.0
        "easeinquart" -> t.pow(4)
        "easeoutquart" -> 1.0 - (1.0 - t).pow(4)
        "easeinoutquart" -> if (t < 0.5) 8.0 * t.pow(4) else 1.0 - (-2.0 * t + 2.0).pow(4) / 2.0
        "easeinquint" -> t.pow(5)
        "easeoutquint" -> 1.0 - (1.0 - t).pow(5)
        "easeinoutquint" -> if (t < 0.5) 16.0 * t.pow(5) else 1.0 - (-2.0 * t + 2.0).pow(5) / 2.0
        "easeinexpo" -> 2.0.pow(10.0 * (t - 1.0))
        "easeoutexpo" -> -2.0.pow(-10.0 * t) + 1.0
        "easeinoutexpo" -> if (t < 0.5) 0.5 * 2.0.pow(10.0 * (2.0 * t - 1.0)) else 0.5 * (-2.0.pow(-10.0 * (2.0 * t - 1.0)) + 2.0)
        "easeincirc" -> -(sqrt(1.0 - t * t) - 1.0)
        "easeoutcirc" -> sqrt(1.0 - (t - 1.0).pow(2))
        "easeinoutcirc" -> if (t < 0.5) -(sqrt(1.0 - (2.0 * t).pow(2)) - 1.0) / 2.0 else (sqrt(1.0 - (-2.0 * t + 2.0).pow(2)) + 1.0) / 2.0
        "easeinelastic" -> -2.0.pow(10.0 * (t - 1.0)) * sin((t - 1.0 - 0.075) * (2.0 * PI) / 0.3)
        "easeoutelastic" -> 2.0.pow(-10.0 * t) * sin((t - 0.075) * (2.0 * PI) / 0.3) + 1.0
        "easeinoutelastic" -> {
            if (t < 0.5) {
                val shifted = 2.0 * t - 1.0
                -0.5 * 2.0.pow(10.0 * shifted) * sin((shifted - 0.05625) * (2.0 * PI) / 0.45)
            } else {
                val shifted = 2.0 * t - 1.0
                0.5 * 2.0.pow(-10.0 * shifted) * sin((shifted - 0.05625) * (2.0 * PI) / 0.45) + 1.0
            }
        }
        "easeinback" -> t * t * (2.70158 * t - 1.70158)
        "easeoutback" -> (t - 1.0).let { it * it * (2.70158 * it + 1.70158) + 1.0 }
        "easeinoutback" -> if (t < 0.5) t * t * (7.0 * t - 2.5) * 2.0 else (t - 1.0).let { (it * it * (7.0 * it + 2.5) + 2.0) * 2.0 }
        "easeinbounce" -> 1.0 - bounceOut(1.0 - t)
        "easeoutbounce" -> bounceOut(t)
        "easeinoutbounce" -> if (t < 0.5) 0.5 * (1.0 - bounceOut(1.0 - 2.0 * t)) else 0.5 * bounceOut(2.0 * t - 1.0) + 0.5
        else -> throw SkeletalExpressionException("Unknown expression function: $name")
    }
    return result(progress)
}

private fun bounceOut(value: Double): Double {
    var t = value
    return when {
        t < 1.0 / 2.75 -> 7.5625 * t * t
        t < 2.0 / 2.75 -> {
            t -= 1.5 / 2.75
            7.5625 * t * t + 0.75
        }
        t < 2.5 / 2.75 -> {
            t -= 2.25 / 2.75
            7.5625 * t * t + 0.9375
        }
        else -> {
            t -= 2.625 / 2.75
            7.5625 * t * t + 0.984375
        }
    }
}
