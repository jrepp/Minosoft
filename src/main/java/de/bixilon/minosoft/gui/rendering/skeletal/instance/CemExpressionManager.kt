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
import de.bixilon.minosoft.assets.model.skeletal.runtime.CemTransformProperty
import de.bixilon.minosoft.gui.rendering.models.block.element.ModelElement.Companion.BLOCK_SIZE

class CemExpressionManager(instance: SkeletalInstance) {
    private val evaluator = instance.model.expressions
        .takeIf(List<*>::isNotEmpty)
        ?.let { CemExpressionEvaluator(it, instance.model.expressionAliases) }
    private val transforms = instance.transform.expressionIndex()
    var context = SkeletalExpressionContext()

    val active get() = evaluator != null

    fun draw() {
        val evaluator = evaluator ?: return
        evaluator.evaluate(context).apply(transforms)
    }
}

internal fun CemExpressionFrame.apply(transforms: Map<String, TransformInstance>) {
    for ((bone, values) in this.transforms) {
        val transform = transforms[bone] ?: continue
        val visible = values[CemTransformProperty.VISIBLE] ?: values[CemTransformProperty.VISIBLE_BOXES]
        val translation = Vec3f(
            values[CemTransformProperty.TRANSLATE_X] ?: 0.0f,
            values[CemTransformProperty.TRANSLATE_Y] ?: 0.0f,
            values[CemTransformProperty.TRANSLATE_Z] ?: 0.0f,
        ) / BLOCK_SIZE
        val rotation = Vec3f(
            values[CemTransformProperty.ROTATE_X] ?: 0.0f,
            values[CemTransformProperty.ROTATE_Y] ?: 0.0f,
            values[CemTransformProperty.ROTATE_Z] ?: 0.0f,
        )
        val scale = if (visible != null && visible == 0.0f) {
            Vec3f.EMPTY
        } else {
            Vec3f(
                values[CemTransformProperty.SCALE_X] ?: 1.0f,
                values[CemTransformProperty.SCALE_Y] ?: 1.0f,
                values[CemTransformProperty.SCALE_Z] ?: 1.0f,
            )
        }
        transform.matrix.apply {
            translateAssign(translation)
            translateAssign(transform.nPivot)
            rotateRadAssign(rotation)
            scaleAssign(scale)
            translateAssign(transform.pivot)
        }
    }
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
