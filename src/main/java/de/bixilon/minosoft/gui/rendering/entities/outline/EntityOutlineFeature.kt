/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.outline

import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureDrawable
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels

/**
 * Marks only features that contribute entity geometry to the outline mask.
 * Names, hitboxes, shadows, and leashes intentionally do not implement it.
 */
interface EntityOutlineFeature : FeatureDrawable {

    fun drawOutline(color: RGBAColor)

    fun prepareOutline() {
        prepare()
    }

    fun outlineColor(): RGBAColor? {
        val feature = this as EntityRenderFeature
        if (!feature.enabled || feature.visibility < EntityVisibilityLevels.OCCLUDED) return null
        return EntityOutlineColor.resolve(feature.renderer.entity)
    }
}
