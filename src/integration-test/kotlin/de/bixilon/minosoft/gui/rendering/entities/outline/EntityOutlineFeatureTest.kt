/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.outline

import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.entities.entities.player.RemotePlayerEntity
import de.bixilon.minosoft.data.scoreboard.team.Team
import de.bixilon.minosoft.data.scoreboard.team.TeamFormatting
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.draw.EntityDrawer
import de.bixilon.minosoft.gui.rendering.entities.feature.DrawableEntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import kotlin.time.Duration

@Test(groups = ["entities", "rendering"])
class EntityOutlineFeatureTest {

    fun `glowing non-player entity uses scoreboard team color`() {
        val entities = EntityRendererTestUtil.create()
        val entity = EntityRendererTestUtil.run { entities.createEntity(Pig) }
        entity.data[Entity.FLAGS_DATA] = 0x40
        val color = ChatColors.LIGHT_PURPLE.rgb()

        assertEquals(EntityOutlineColor.resolve(entity), ChatColors.WHITE)

        entities.session.scoreboard.teams["outline"] = Team(
            "outline",
            TeamFormatting(ChatComponent.EMPTY, color = color),
            members = mutableSetOf(entity.uuid.toString()),
        )

        assertEquals(EntityOutlineColor.resolve(entity), color.rgba())
    }

    fun `glowing player uses attached team color`() {
        val entities = EntityRendererTestUtil.create()
        val entity = EntityRendererTestUtil.run { entities.createEntity(RemotePlayerEntity) }
        entity.data[Entity.FLAGS_DATA] = 0x40
        val color = ChatColors.BLUE.rgb()
        entity.additional.team = Team(
            "outline",
            TeamFormatting(ChatComponent.EMPTY, color = color),
        )

        assertEquals(EntityOutlineColor.resolve(entity), color.rgba())
    }

    fun `occluded glowing geometry updates and queues only an outline`() {
        val entities = EntityRendererTestUtil.create()
        val entity = EntityRendererTestUtil.run { entities.createEntity(Pig) }
        entity.data[Entity.FLAGS_DATA] = 0x40
        val renderer = TestRenderer(entities, entity)
        val feature = TestFeature(renderer)
        renderer.features += feature
        renderer.features.updateVisibility(EntityVisibilityLevels.OCCLUDED)
        val drawer = EntityDrawer(entities)

        renderer.features.update(Duration.ZERO)
        renderer.features.collect(drawer)
        drawer.prepare()

        assertEquals(feature.updates, 1)
        assertEquals(feature.normalCollections, 0)
        assertEquals(drawer.outline.size, 1)
    }

    fun `invisible glowing geometry does not enter the normal entity layer`() {
        val entities = EntityRendererTestUtil.create()
        val entity = EntityRendererTestUtil.run { entities.createEntity(Pig) }
        entity.data[Entity.FLAGS_DATA] = 0x60
        val renderer = TestRenderer(entities, entity)
        val feature = TestFeature(renderer)
        renderer.features += feature
        renderer.features.updateVisibility(EntityVisibilityLevels.VISIBLE)
        val drawer = EntityDrawer(entities)

        renderer.features.update(Duration.ZERO)
        renderer.features.collect(drawer)
        drawer.prepare()

        assertEquals(feature.updates, 1)
        assertEquals(feature.normalCollections, 0)
        assertEquals(drawer.outline.size, 1)
    }

    private class TestRenderer(
        entities: de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer,
        entity: Pig,
    ) : EntityRenderer<Pig>(entities, entity)

    private class TestFeature(
        renderer: EntityRenderer<*>,
    ) : DrawableEntityRenderFeature(renderer), EntityOutlineFeature {
        var updates = 0
        var normalCollections = 0

        override fun update(delta: Duration) {
            updates++
        }

        override fun collect(drawer: EntityDrawer) {
            normalCollections++
        }

        override fun draw() = Unit

        override fun drawOutline(color: RGBAColor) = Unit
    }
}
