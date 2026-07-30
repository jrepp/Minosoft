/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature

import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.createEntity
import de.bixilon.minosoft.gui.rendering.entities.draw.EntityDrawer
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration

@Test(groups = ["entities", "rendering"])
class EntityRenderFeatureVisibilityTest {

    fun `first person main-view suppression preserves auxiliary shadow visibility`() {
        val entities = EntityRendererTestUtil.create()
        val renderer = TestRenderer(entities, entities.createEntity(Pig))
        val feature = TestFeature(renderer)
        feature.updateVisibility(EntityVisibilityLevels.VISIBLE)

        feature.mainViewEnabled = false

        assertFalse(feature.isVisible())
        assertTrue(feature.isShadowVisible())

        renderer.features.clear()
        renderer.features += feature
        val drawer = EntityDrawer(entities)
        renderer.features.update(Duration.ZERO, auxiliaryVisible = true)
        renderer.features.collectShadow(drawer)
        drawer.prepare()
        assertEquals(feature.updates, 1)
        assertEquals(drawer.size, 1)

        feature.enabled = false
        renderer.features.update(Duration.ZERO, auxiliaryVisible = true)

        assertFalse(feature.isShadowVisible())
        assertEquals(feature.updates, 1)
    }

    private class TestRenderer(
        entities: EntitiesRenderer,
        entity: Pig,
    ) : EntityRenderer<Pig>(entities, entity)

    private class TestFeature(
        renderer: EntityRenderer<*>,
    ) : DrawableEntityRenderFeature(renderer) {
        override val castsShadow = true
        var updates = 0

        override fun update(delta: Duration) {
            updates++
        }

        override fun draw() = Unit
    }
}
