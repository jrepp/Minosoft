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
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostStateInput
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostStateQuery
import de.bixilon.minosoft.data.abilities.Gamemodes
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.entities.entities.player.RemotePlayerEntity
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil
import de.bixilon.minosoft.gui.rendering.entities.EntityRendererTestUtil.createEntity
import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import kotlin.random.Random

@Test(groups = ["entities", "skeletal"])
class GeckoLibEntityHostStateResolverTest {
    fun `entity host queries resolve random type and exact nearby player bounds`() {
        val entities = EntityRendererTestUtil.create()
        val subject = entities.createEntity(Pig)
        val player = entities.createEntity(RemotePlayerEntity)
        subject.forceTeleport(Vec3d(0.0, 0.0, 0.0))
        player.forceTeleport(Vec3d(3.0, 0.0, 0.0))
        entities.session.world.entities.add(1, null, subject)
        entities.session.world.entities.add(2, null, player)

        val randomInput = GeckoLibHostStateInput(
            "test.random",
            GeckoLibHostStateQuery.RandomInteger(1_000),
        )
        val typeInput = GeckoLibHostStateInput(
            "test.type",
            GeckoLibHostStateQuery.EntityType(Pig.identifier),
        )
        val nearbyInput = GeckoLibHostStateInput(
            "test.nearby",
            GeckoLibHostStateQuery.NearbyPlayer(
                range = 4.0,
                horizontalExpansion = 4.0,
                verticalExpansion = 2.0,
            ),
        )
        val expectedRandom = Random(7).nextInt(1_000).toDouble()

        assertEquals(
            GeckoLibEntityHostStateResolver.resolve(subject, randomInput, Random(7)),
            expectedRandom,
        )
        assertEquals(GeckoLibEntityHostStateResolver.resolve(subject, typeInput, Random(0)), 1.0)
        assertEquals(GeckoLibEntityHostStateResolver.resolve(subject, nearbyInput, Random(0)), 1.0)

        player.additional.gamemode = Gamemodes.SPECTATOR
        assertEquals(GeckoLibEntityHostStateResolver.resolve(subject, nearbyInput, Random(0)), 0.0)
        player.additional.gamemode = Gamemodes.SURVIVAL
        player.forceTeleport(Vec3d(4.5, 0.0, 0.0))
        assertEquals(GeckoLibEntityHostStateResolver.resolve(subject, nearbyInput, Random(0)), 0.0)
    }
}
