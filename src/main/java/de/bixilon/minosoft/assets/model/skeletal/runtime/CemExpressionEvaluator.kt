/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.runtime

import de.bixilon.minosoft.assets.model.skeletal.SkeletalExpressionBinding
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionException

enum class CemTransformProperty(val key: String) {
    TRANSLATE_X("tx"),
    TRANSLATE_Y("ty"),
    TRANSLATE_Z("tz"),
    ROTATE_X("rx"),
    ROTATE_Y("ry"),
    ROTATE_Z("rz"),
    SCALE_X("sx"),
    SCALE_Y("sy"),
    SCALE_Z("sz"),
    VISIBLE("visible"),
    VISIBLE_BOXES("visible_boxes");

    companion object {
        fun of(key: String): CemTransformProperty? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

enum class CemRenderProperty(val key: String, val default: Float) {
    SHADOW_SIZE("render.shadow_size", Float.NaN),
    SHADOW_OPACITY("render.shadow_opacity", Float.NaN),
    SHADOW_OFFSET_X("render.shadow_offset_x", 0.0f),
    SHADOW_OFFSET_Z("render.shadow_offset_z", 0.0f),
    LEASH_OFFSET_X("render.leash_offset_x", 0.0f),
    LEASH_OFFSET_Y("render.leash_offset_y", 0.0f),
    LEASH_OFFSET_Z("render.leash_offset_z", 0.0f);

    companion object {
        fun of(key: String): CemRenderProperty? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

data class CemExpressionFrame(
    val transforms: Map<String, Map<CemTransformProperty, Float>>,
    val variables: Map<String, Double>,
    val render: Map<CemRenderProperty, Float>,
)

/**
 * Ordered, bounded OptiFine-style model-expression evaluation.
 *
 * Model variables persist for the lifetime of this evaluator (one evaluator
 * per rendered model instance). Transform assignments are rebuilt each frame.
 * `this.*`, named-part lookups, `var.*`, and `varb.*` are resolved without
 * reflection; when the retained part set is supplied, unknown targets fail
 * during construction.
 */
class CemExpressionEvaluator(
    bindings: List<SkeletalExpressionBinding>,
    aliases: Map<String, String> = emptyMap(),
    knownParts: Set<String>? = null,
) {
    private val aliases = aliases.toMap()
    private val bindings = bindings.also {
        require(it.size <= MAX_BINDINGS) {
            "CEM expression model exceeds the $MAX_BINDINGS binding limit."
        }
    }.map { binding ->
        CompiledBinding(
            owner = aliases[binding.owner] ?: binding.owner,
            target = parseTarget(binding, this.aliases, knownParts),
            expression = binding,
        )
    }
    private val variables = linkedMapOf<String, Double>()

    @Synchronized
    fun evaluate(
        input: SkeletalExpressionContext = SkeletalExpressionContext(),
        partValues: Map<String, Map<CemTransformProperty, Float>> = emptyMap(),
    ): CemExpressionFrame {
        val transforms = linkedMapOf<String, MutableMap<CemTransformProperty, Float>>()
        val render = linkedMapOf<CemRenderProperty, Float>()
        for (binding in bindings) {
            val context = SkeletalExpressionContext(
                variableResolver = { name ->
                    resolve(name, binding.owner, transforms, render, partValues, input)
                },
                rawFunctionResolver = input::resolveRawFunction,
                random = input.random,
            )
            val value = binding.expression.compiled.evaluate(context)
            if (!value.isFinite()) {
                throw SkeletalExpressionException(
                    "CEM expression '${binding.expression.expression}' produced a non-finite value.",
                )
            }
            when (val target = binding.target) {
                is Target.Variable -> variables[target.name] = if (target.boolean) {
                    if (value != 0.0) 1.0 else 0.0
                } else {
                    value
                }
                is Target.Render -> render[target.property] = value.toFloat()
                is Target.Transform -> transforms
                    .getOrPut(target.bone, ::linkedMapOf)[target.property] = value.toFloat()
            }
        }
        return CemExpressionFrame(
            transforms = transforms.mapValues { it.value.toMap() },
            variables = variables.toMap(),
            render = render.toMap(),
        )
    }

    @Synchronized
    fun clearVariables() = variables.clear()

    private fun resolve(
        raw: String,
        owner: String,
        transforms: Map<String, Map<CemTransformProperty, Float>>,
        render: Map<CemRenderProperty, Float>,
        partValues: Map<String, Map<CemTransformProperty, Float>>,
        input: SkeletalExpressionContext,
    ): Double? {
        variables[raw]?.let { return it }
        CemRenderProperty.of(raw)?.let { return (render[it] ?: it.default).toDouble() }
        val target = parseLookup(raw, owner)
        if (target != null) {
            val assigned = transforms[target.first]?.get(target.second)
            if (assigned != null) return assigned.toDouble()
            return (partValues[target.first]?.get(target.second) ?: 0.0f).toDouble()
        }
        return try {
            input.variable(raw)
        } catch (_: SkeletalExpressionException) {
            null
        }
    }

    private fun parseLookup(raw: String, owner: String): Pair<String, CemTransformProperty>? {
        val separator = raw.lastIndexOf('.')
        if (separator <= 0) return null
        val property = CemTransformProperty.of(raw.substring(separator + 1)) ?: return null
        val sourceBone = raw.substring(0, separator)
        val bone = if (sourceBone == "this") owner else aliases[sourceBone] ?: sourceBone
        return bone to property
    }

    private fun parseTarget(
        binding: SkeletalExpressionBinding,
        aliases: Map<String, String>,
        knownParts: Set<String>?,
    ): Target {
        val raw = binding.target
        if (raw.startsWith("var.")) return Target.Variable(raw, boolean = false)
        if (raw.startsWith("varb.")) return Target.Variable(raw, boolean = true)
        CemRenderProperty.of(raw)?.let { return Target.Render(it) }
        val separator = raw.lastIndexOf('.')
        require(separator > 0) { "Invalid CEM expression target '$raw'." }
        val rawBone = raw.substring(0, separator)
        val bone = if (rawBone == "this") {
            aliases[binding.owner] ?: binding.owner
        } else {
            aliases[rawBone] ?: rawBone
        }
        val property = CemTransformProperty.of(raw.substring(separator + 1))
            ?: throw IllegalArgumentException("Unsupported CEM expression target '$raw'.")
        require(knownParts == null || bone in knownParts) {
            "CEM expression target '$raw' resolves to missing part '$bone'."
        }
        return Target.Transform(bone, property)
    }

    private data class CompiledBinding(
        val owner: String,
        val target: Target,
        val expression: SkeletalExpressionBinding,
    )

    private sealed interface Target {
        data class Variable(val name: String, val boolean: Boolean) : Target
        data class Render(val property: CemRenderProperty) : Target
        data class Transform(val bone: String, val property: CemTransformProperty) : Target
    }

    private companion object {
        const val MAX_BINDINGS = 16_384
    }
}
