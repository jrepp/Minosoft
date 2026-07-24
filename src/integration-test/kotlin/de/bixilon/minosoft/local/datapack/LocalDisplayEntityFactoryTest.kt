/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.local.datapack

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.assets.datapack.DataPackCommandContext
import de.bixilon.minosoft.assets.datapack.LocalDataPackCommandAuthority
import de.bixilon.minosoft.assets.datapack.SnbtParser
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayContext
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.data.entities.entities.display.TextDisplayEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderingOptions
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class LocalDisplayEntityFactoryTest {

    @Test
    fun `summons an Animated Java root with retained display passenger state`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION // initialize the integration-test version/registry catalog
            val session = createSession(version = "1.20.4")
            val nbt = SnbtParser.compound(
                """{Tags:["aj.global.root","demo.rig.root"],billboard:"center",brightness:{sky:15,block:7},transformation:{translation:[1f,2f,3f],left_rotation:[0f,0f,0f,1f],scale:[2f,2f,2f],right_rotation:[0f,0f,0f,1f]},item:{id:"minecraft:carrot_on_a_stick",Count:1b,tag:{CustomModelData:42}},item_display:"head",Passengers:[{id:"minecraft:text_display",Tags:["demo.rig.label"],text:'{"text":"Hello"}',line_width:80,shadow:1b,alignment:"left"}]}""",
            )

            val factory = LocalDisplayEntityFactory(session)
            val authority = LocalDataPackCommandAuthority(
                origin = { Vec3d(4.0, 5.0, 6.0) },
                spawn = { type, data, position -> factory.summon(type, data, position) },
            )
            assertEquals(
                1,
                authority.execute(
                    "summon minecraft:item_display ~ ~ ~ ${SnbtParser.stringify(nbt)}",
                    DataPackCommandContext(ResourceLocation.of("demo:load"), 0, 0),
                ),
            )
            val root = session.world.entities
                .filterIsInstance<ItemDisplayEntity>()
                .single()

            assertEquals(setOf("aj.global.root", "demo.rig.root"), root.commandTags)
            assertEquals(Vec3d(4.0, 5.0, 6.0), root.physics.position)
            assertEquals(ItemDisplayContext.HEAD, root.displayContext)
            assertEquals(42, root.stack!!.nbt.nbt["CustomModelData"])
            assertEquals(3, root.billboard.toInt())
            assertEquals(15, root.brightness!!.sky)
            assertEquals(7, root.brightness!!.block)
            assertEquals(1.0f, root.translation.x)
            val passenger = root.attachment.passengers.single() as TextDisplayEntity
            assertSame(root, passenger.attachment.vehicle)
            assertTrue(passenger.shadow)
            assertEquals(80, passenger.lineWidth)
            assertEquals(setOf("demo.rig.label"), passenger.commandTags)
        } finally {
            RenderingOptions.disabled = previous
        }
    }
}
