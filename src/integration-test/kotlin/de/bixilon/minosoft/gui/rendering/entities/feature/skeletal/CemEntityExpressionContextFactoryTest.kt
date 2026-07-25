/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.observer.DataObserver
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.entities.event.events.damage.GenericDamageEvent
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.gui.rendering.RenderingStates
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.create
import de.bixilon.minosoft.util.KUtil.startInit
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

@Test(groups = ["entity_renderer", "rendering"])
class CemEntityExpressionContextFactoryTest {

    fun `live context exposes pinned EMF units and world state`() {
        val entities = EntityRendererTestUtil.create()
        entities.context.session.player.startInit()
        entities.context.session.player.forceTeleport(Vec3d(1.0, 1.0, 1.0))
        val renderer = entities.create(Pig)
        renderer.entity._id = -27_727
        renderer.entity.forceTeleport(Vec3d(5.0, 2.0, 9.0))
        renderer.entity.physics.velocity.put(Vec3d(0.0, 0.0, 1.0))
        renderer.info::partialTick.forceSet(0.25f)
        renderer.info::rotation.forceSet(EntityRotation(170.0f, 30.0f))
        renderer.info::bodyYaw.forceSet(170.0f)
        renderer.info::headYaw.forceSet(-170.0f)
        renderer.entity.onDamage(GenericDamageEvent)
        renderer.entity::deathTime.forceSet(4)
        entities.context.session.world.name = minecraft("the_nether")
        entities.context::state.forceSet(DataObserver(RenderingStates.PAUSED))
        entities.context::frameNumber.forceSet(27_722L)

        val factory = CemEntityExpressionContextFactory(renderer)
        factory.ruleIndex = 6
        factory.renderPath = CemRenderPathContext(
            firstPersonHand = true,
            inHand = true,
            inGui = true,
        )
        val context = factory.create { 0.5 }

        assertEquals(context.variable("id"), 7.0)
        assertEquals(context.variable("dimension"), -1.0)
        assertEquals(context.variable("head_pitch"), 30.0)
        assertEquals(context.variable("head_yaw"), 20.0)
        assertEquals(context.variable("frame_counter"), 2.0)
        assertEquals(context.variable("frame_time"), 0.0)
        assertEquals(context.variable("rule_index"), 6.0)
        assertEquals(context.variable("is_first_person_hand"), 1.0)
        assertEquals(context.variable("is_in_hand"), 1.0)
        assertEquals(context.variable("is_in_gui"), 1.0)
        assertEquals(context.variable("is_in_item_frame"), 0.0)
        assertEquals(context.variable("is_in_ground"), 0.0)
        assertEquals(context.variable("pos_x"), 5.0)
        assertEquals(context.variable("player_pos_x"), 1.0)
        assertEquals(context.variable("distance"), 9.0)
        assertEquals(context.variable("is_right_handed"), 1.0)
        assertEquals(context.variable("hurt_time"), 9.75)
        assertEquals(context.variable("death_time"), 4.25)
        assertEquals(context.variable("is_hurt"), 1.0)
    }
}
