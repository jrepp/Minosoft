/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.instance

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpressionContext
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemExpressionEvaluator
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemExpressionFrame
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemRenderProperty
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemTransformProperty
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement.Companion.BLOCK_SIZE

class CemExpressionManager(instance: SkeletalInstance) {
    private val transforms = instance.transform.expressionIndex()
    private val evaluator = instance.model.expressions
        .takeIf(List<*>::isNotEmpty)
        ?.let { CemExpressionEvaluator(it, instance.model.expressionAliases, transforms.keys) }
    var context = SkeletalExpressionContext()
    var render: Map<CemRenderProperty, Float> = emptyMap()
        private set

    val active get() = evaluator != null

    fun draw() {
        val evaluator = evaluator ?: return
        val partValues = transforms.mapValues { it.value.partProperties() }
        val frame = evaluator.evaluate(context, partValues)
        frame.apply(transforms, partValues)
        render = frame.render
    }
}

internal fun CemExpressionFrame.apply(
    transforms: Map<String, TransformInstance>,
    partValues: Map<String, Map<CemTransformProperty, Float>>,
) {
    for ((bone, values) in this.transforms) {
        val transform = transforms[bone] ?: continue
        val base = requireNotNull(partValues[bone]) { "Missing CEM part state for '$bone'." }
        val basePivot = base.vector(
            CemTransformProperty.TRANSLATE_X,
            CemTransformProperty.TRANSLATE_Y,
            CemTransformProperty.TRANSLATE_Z,
        )
        val pivot = values.vectorOr(basePivot,
            CemTransformProperty.TRANSLATE_X,
            CemTransformProperty.TRANSLATE_Y,
            CemTransformProperty.TRANSLATE_Z,
        )
        val baseRotation = base.vector(
            CemTransformProperty.ROTATE_X,
            CemTransformProperty.ROTATE_Y,
            CemTransformProperty.ROTATE_Z,
        )
        val rotation = values.vectorOr(baseRotation,
            CemTransformProperty.ROTATE_X,
            CemTransformProperty.ROTATE_Y,
            CemTransformProperty.ROTATE_Z,
        )
        val baseScale = base.vector(
            CemTransformProperty.SCALE_X,
            CemTransformProperty.SCALE_Y,
            CemTransformProperty.SCALE_Z,
        )
        val scale = values.vectorOr(baseScale,
            CemTransformProperty.SCALE_X,
            CemTransformProperty.SCALE_Y,
            CemTransformProperty.SCALE_Z,
        )
        val visible = (values[CemTransformProperty.VISIBLE] ?: base.getValue(CemTransformProperty.VISIBLE)) != 0.0f
        val hidden = (values[CemTransformProperty.VISIBLE_BOXES] ?: base.getValue(CemTransformProperty.VISIBLE_BOXES)) != 0.0f

        val translationDelta = (pivot - basePivot) / BLOCK_SIZE
        val rotationDelta = rotation - baseRotation
        val scaleRatio = Vec3f(
            ratio(scale.x, baseScale.x),
            ratio(scale.y, baseScale.y),
            ratio(scale.z, baseScale.z),
        )
        val matrixPivot = basePivot / BLOCK_SIZE
        transform.matrix.apply {
            translateAssign(translationDelta)
            translateAssign(-matrixPivot)
            rotateRadAssign(rotationDelta)
            scaleAssign(scaleRatio)
            translateAssign(matrixPivot)
        }
        transform.setPartState(pivot, rotation, scale, visible, hidden)
    }
}

private fun Map<CemTransformProperty, Float>.vector(
    x: CemTransformProperty,
    y: CemTransformProperty,
    z: CemTransformProperty,
) = Vec3f(getValue(x), getValue(y), getValue(z))

private fun Map<CemTransformProperty, Float>.vectorOr(
    fallback: Vec3f,
    x: CemTransformProperty,
    y: CemTransformProperty,
    z: CemTransformProperty,
) = Vec3f(this[x] ?: fallback.x, this[y] ?: fallback.y, this[z] ?: fallback.z)

private fun ratio(target: Float, current: Float): Float = when {
    current != 0.0f -> target / current
    target == 0.0f -> 1.0f
    else -> target
}

private fun TransformInstance.expressionIndex(): Map<String, TransformInstance> {
    val result = linkedMapOf<String, TransformInstance>()
    fun collect(transform: TransformInstance) {
        for ((name, child) in transform.children) {
            result.putIfAbsent(name, child)
            collect(child)
        }
    }
    collect(this)
    return result
}
