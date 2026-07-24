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
    val random: () -> Double = { 0.0 },
) {
    fun variable(name: String): Double {
        return variables[name] ?: variableResolver(name)
        ?: throw SkeletalExpressionException("Unknown expression variable: $name")
    }
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
                requireArguments(name, arguments.size, exact = 2)
                return try {
                    arguments[0].evaluate(context, budget)
                } catch (_: RuntimeException) {
                    arguments[1].evaluate(context, budget)
                }
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
                "atan2" -> binary(name, values, ::atan2)
                "pow" -> binary(name, values) { left, right -> left.pow(right) }
                "mod" -> binary(name, values) { left, right -> left % right }
                "clamp" -> {
                    requireArguments(name, values.size, exact = 3)
                    values[0].coerceIn(values[1], values[2])
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
                "min" -> {
                    requireArguments(name, values.size, minimum = 1)
                    values.min()
                }
                "max" -> {
                    requireArguments(name, values.size, minimum = 1)
                    values.max()
                }
                "random", "rand" -> {
                    requireArguments(name, values.size, exact = 0)
                    context.random()
                }
                else -> throw SkeletalExpressionException("Unknown expression function: $name")
            }
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
    }

    private class Parser(private val source: String) {
        private val tokens = tokenize(source)
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
