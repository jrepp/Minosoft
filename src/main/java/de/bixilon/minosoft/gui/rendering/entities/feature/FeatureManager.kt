/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature

import de.bixilon.minosoft.gui.rendering.entities.draw.EntityDrawer
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineFeature
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import kotlin.time.Duration

class FeatureManager(val renderer: EntityRenderer<*>) : Iterable<EntityRenderFeature> {
    private val features: ArrayList<EntityRenderFeature> = ArrayList(10)


    operator fun plusAssign(feature: EntityRenderFeature) = register(feature)
    fun register(feature: EntityRenderFeature) {
        val index = features.indexOfFirst { it.updatePriority > feature.updatePriority }
        if (index < 0) {
            features += feature
        } else {
            features.add(index, feature)
        }
    }

    operator fun minusAssign(feature: EntityRenderFeature) = remove(feature)
    fun remove(feature: EntityRenderFeature) {
        this.features -= feature
    }

    fun update(
        delta: Duration,
        auxiliaryVisible: Boolean = false,
        cameraVisible: Boolean = renderer.isVisibleTo(renderer.renderer.context.session.camera.entity),
    ) {
        for (feature in features) {
            val outline = (feature as? EntityOutlineFeature)?.outlineColor()
            val shadow = feature as? FeatureDrawable
            val shadowVisible = auxiliaryVisible &&
                shadow?.castsShadow == true &&
                feature.isShadowVisible()
            if (!feature.isNormallyVisible(cameraVisible) && outline == null && !shadowVisible) continue
            feature.update(delta)
        }
    }

    fun unload() {
        var failure: Throwable? = null
        for (feature in features) {
            try {
                feature.unload()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        features.clear()
        failure?.let { throw it }
    }

    fun enqueueUnload() {
        features.forEach { it.enqueueUnload() }
    }

    fun invalidate() = features.forEach { it.invalidate() }
    fun updateVisibility(level: EntityVisibilityLevels) = features.forEach { it.updateVisibility(level) }
    fun collect(
        drawer: EntityDrawer,
        cameraVisible: Boolean = renderer.isVisibleTo(renderer.renderer.context.session.camera.entity),
    ) {
        for (feature in features) {
            val outline = (feature as? EntityOutlineFeature)?.outlineColor()
            if (outline != null) drawer.addOutline(feature, outline)
            if (feature.isNormallyVisible(cameraVisible)) feature.collect(drawer)
        }
    }

    fun collectShadow(drawer: EntityDrawer) {
        for (feature in features) {
            val drawable = feature as? FeatureDrawable ?: continue
            if (!feature.isShadowVisible() || !drawable.castsShadow) continue
            drawer.addShadow(drawable)
        }
    }

    private fun EntityRenderFeature.isNormallyVisible(cameraVisible: Boolean): Boolean {
        if (!isVisible()) return false
        return cameraVisible
    }

    fun clear() {
        features.clear()
    }

    override fun iterator(): Iterator<EntityRenderFeature> {
        return features.iterator()
    }
}
