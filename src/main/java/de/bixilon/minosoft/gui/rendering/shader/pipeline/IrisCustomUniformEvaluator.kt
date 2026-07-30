/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpression
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionException
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import kotlin.math.exp
import kotlin.math.ln

/**
 * Evaluates Iris/OptiFine custom uniforms from shaders.properties.
 *
 * Expressions are bounded by [SkeletalExpression]. Definitions are lazy so a
 * pack may carry extension-specific expressions without making those
 * expressions part of the active host contract.
 */
internal class IrisCustomUniformEvaluator(
    plan: IrisCustomUniformPlan,
    private val sunPathRotation: Float,
) {
    private data class CompiledDefinition(
        val definition: IrisCustomUniformDefinition,
        val expressions: Lazy<List<SkeletalExpression>>,
    )

    private val definitions = plan.definitions.associate { definition ->
        definition.name to CompiledDefinition(definition, lazy { compile(definition) })
    }
    private val smoothValues = linkedMapOf<String, Double>()
    private val values = linkedMapOf<String, DoubleArray>()
    private var variables = emptyMap<String, Double>()
    private var frameCounter: Int? = null
    private var frameTime = 0.0

    fun uploadTo(
        native: NativeShader,
        declaredUniforms: Set<String>,
        state: IrisFrameState,
    ): Int {
        beginFrame(state)
        var uploads = 0
        for (name in declaredUniforms) {
            val compiled = definitions[name] ?: continue
            if (!compiled.definition.uniform || !native.hasUniform(name)) continue
            val value = evaluate(name, linkedSetOf())
            when (compiled.definition.type) {
                IrisCustomUniformType.BOOL -> native.setBoolean(name, value[0] != 0.0)
                IrisCustomUniformType.INT -> native.setInt(name, value[0].toInt())
                IrisCustomUniformType.FLOAT -> native.setFloat(name, value[0].toFloat())
                IrisCustomUniformType.VEC2 -> native.setVec2f(name, Vec2f(value[0].toFloat(), value[1].toFloat()))
                IrisCustomUniformType.VEC3 -> native.setVec3f(
                    name,
                    Vec3f(value[0].toFloat(), value[1].toFloat(), value[2].toFloat()),
                )
                IrisCustomUniformType.VEC4 -> native.setVec4f(
                    name,
                    Vec4f(value[0].toFloat(), value[1].toFloat(), value[2].toFloat(), value[3].toFloat()),
                )
            }
            uploads++
        }
        return uploads
    }

    private fun beginFrame(state: IrisFrameState) {
        if (frameCounter == state.frameCounter) return
        frameCounter = state.frameCounter
        frameTime = state.frameTime.toDouble().coerceAtLeast(0.0)
        variables = state.customExpressionVariables(sunPathRotation)
        values.clear()
    }

    private fun evaluate(name: String, stack: MutableSet<String>): DoubleArray {
        values[name]?.let { return it }
        require(stack.add(name)) {
            "Iris custom-uniform dependency cycle: ${(stack + name).joinToString(" -> ")}"
        }
        try {
            val compiled = requireNotNull(definitions[name]) { "Unknown Iris custom uniform or variable $name" }
            var smoothCall = 0
            val result = compiled.expressions.value.mapIndexed { component, expression ->
                val context = SkeletalExpressionContext(
                    variableResolver = { variable -> resolveVariable(variable, stack) },
                    functionResolver = { function, arguments ->
                        if (function == "smooth") {
                            when (arguments.size) {
                                3 -> smooth(
                                    "$name/$component/${smoothCall++}",
                                    arguments[0],
                                    arguments[1],
                                    arguments[2],
                                )
                                4 -> smooth(
                                    "iris/${arguments[0].toLong()}",
                                    arguments[1],
                                    arguments[2],
                                    arguments[3],
                                )
                                else -> throw SkeletalExpressionException(
                                    "Iris smooth() requires value/fadeUp/fadeDown or id/value/fadeUp/fadeDown",
                                )
                            }
                        } else {
                            null
                        }
                    },
                )
                expression.evaluate(context).also { value ->
                    if (!value.isFinite()) {
                        throw SkeletalExpressionException(
                            "Iris custom uniform $name produced a non-finite value",
                        )
                    }
                }
            }.toDoubleArray()
            values[name] = result
            return result
        } finally {
            stack.remove(name)
        }
    }

    private fun resolveVariable(name: String, stack: MutableSet<String>): Double? {
        variables[name]?.let { return it }
        definitions[name]?.let { definition ->
            require(definition.definition.type.components == 1) {
                "Iris custom vector $name must be referenced through a component"
            }
            return evaluate(name, stack)[0]
        }
        val separator = name.lastIndexOf('.')
        if (separator <= 0) return null
        val vectorName = name.substring(0, separator)
        val component = when (name.substring(separator + 1)) {
            "x", "r" -> 0
            "y", "g" -> 1
            "z", "b" -> 2
            "w", "a" -> 3
            else -> return null
        }
        val definition = definitions[vectorName] ?: return null
        if (component >= definition.definition.type.components) return null
        return evaluate(vectorName, stack)[component]
    }

    private fun smooth(key: String, target: Double, fadeUp: Double, fadeDown: Double): Double {
        require(fadeUp.isFinite() && fadeUp >= 0.0 && fadeDown.isFinite() && fadeDown >= 0.0) {
            "Iris smooth() fade times must be finite and non-negative"
        }
        val previous = smoothValues[key]
        if (previous == null || frameTime <= 0.0) {
            smoothValues[key] = target
            return target
        }
        val halfLife = if (target >= previous) fadeUp else fadeDown
        val value = if (halfLife <= 0.0) {
            target
        } else {
            val retained = exp(-ln(2.0) * frameTime / halfLife)
            previous * retained + target * (1.0 - retained)
        }
        smoothValues[key] = value
        return value
    }

    private companion object {
        fun compile(definition: IrisCustomUniformDefinition): List<SkeletalExpression> {
            val components = definition.type.components
            if (components == 1) return listOf(SkeletalExpression.compile(definition.expression))
            val prefix = definition.type.name.lowercase()
            val source = definition.expression.trim()
            require(source.startsWith("$prefix(") && source.endsWith(')')) {
                "Iris custom vector ${definition.name} must use a $prefix(...) constructor"
            }
            val arguments = splitArguments(source.substring(prefix.length + 1, source.lastIndex))
            val expanded = when (arguments.size) {
                1 -> List(components) { arguments.single() }
                components -> arguments
                else -> throw IllegalArgumentException(
                    "Iris custom vector ${definition.name} requires one or $components components",
                )
            }
            return expanded.map(SkeletalExpression::compile)
        }

        private fun splitArguments(source: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            var start = 0
            source.forEachIndexed { index, character ->
                when (character) {
                    '(' -> depth++
                    ')' -> {
                        require(depth > 0) { "Unmatched ')' in Iris custom vector expression" }
                        depth--
                    }
                    ',' -> if (depth == 0) {
                        result += source.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
            require(depth == 0) { "Unclosed '(' in Iris custom vector expression" }
            result += source.substring(start).trim()
            require(result.all(String::isNotEmpty)) { "Empty Iris custom vector component" }
            return result
        }
    }
}
