/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.skeletal.binding

import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel

/**
 * Applies CEM replacement/attachment roots to a native entity model without
 * introducing an OpenGL dependency.
 *
 * Ordinary CEM roots replace only their target part and preserve unrelated
 * native parts. `attach` roots retain the native target geometry and receive a
 * private child transform so their pivot/rotation and expressions do not move
 * the vanilla part.
 */
object SkeletalModelComposer {

    fun compose(base: SkeletalModel, binding: SkeletalModelBinding): SkeletalModel {
        val overlay = binding.model
        val elements = linkedMapOf<String, de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalElement>()
        elements.putAll(base.elements)
        val transforms = linkedMapOf<String, de.bixilon.minosoft.gui.rendering.skeletal.model.transforms.SkeletalTransform>()
        transforms.putAll(base.transforms)

        for ((name, element) in overlay.elements) {
            val target = binding.attachments[name]
            if (target == null) {
                elements[name] = element
                continue
            }
            val baseElement = requireNotNull(elements[target]) {
                "CEM attachment '$name' targets missing skeletal element '$target'."
            }
            val baseTransform = requireNotNull(transforms[target]) {
                "CEM attachment '$name' targets missing skeletal transform '$target'."
            }
            val attachmentTransform = requireNotNull(overlay.transforms[name]) {
                "CEM attachment '$name' has no bound transform."
            }
            require(name !in baseElement.children) {
                "CEM attachment '$name' collides with an existing child of '$target'."
            }
            require(name !in baseTransform.children) {
                "CEM attachment transform '$name' collides with an existing child of '$target'."
            }
            elements[target] = baseElement.copy(
                children = baseElement.children + (name to element.copy(transform = name)),
            )
            transforms[target] = baseTransform.copy(
                children = baseTransform.children + (name to attachmentTransform),
            )
        }
        for ((name, transform) in overlay.transforms) {
            if (name !in binding.attachments) transforms[name] = transform
        }

        return SkeletalModel(
            elements = elements,
            textures = base.textures + overlay.textures,
            animations = base.animations + overlay.animations,
            transforms = transforms,
            neutralAnimations = base.neutralAnimations + overlay.neutralAnimations,
            expressions = base.expressions + overlay.expressions,
            expressionAliases = base.expressionAliases + overlay.expressionAliases,
        )
    }
}
