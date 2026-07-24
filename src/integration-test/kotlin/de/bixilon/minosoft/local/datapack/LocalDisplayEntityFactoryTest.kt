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
import de.bixilon.minosoft.assets.datapack.DataPackFunction
import de.bixilon.minosoft.assets.datapack.DataPackFunctionLibrary
import de.bixilon.minosoft.assets.datapack.DataPackFunctionRuntime
import de.bixilon.minosoft.assets.datapack.LocalDataPackCommandAuthority
import de.bixilon.minosoft.assets.datapack.SnbtParser
import de.bixilon.minosoft.data.entities.entities.InteractionEntity
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

    @Test
    fun `mutates and removes selected Animated Java entities`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val origin = Vec3d(4.0, 5.0, 6.0)
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val authority = LocalDataPackCommandAuthority(
                origin = { origin },
                spawn = { type, data, position -> factory.summon(type, data, position) },
                entities = entities,
            )
            val base = DataPackCommandContext(ResourceLocation.of("demo:animation"), 0, 0)
            authority.execute(
                """summon minecraft:item_display ~ ~ ~ {Tags:["demo.root"],item:{id:"minecraft:carrot_on_a_stick",Count:1b,tag:{CustomModelData:1}},Passengers:[{id:"minecraft:text_display",Tags:["demo.node"],text:'{"text":"Node"}'}]}""",
                base,
            )
            authority.execute(
                """summon minecraft:interaction ^1 ^ ^ {Tags:["demo.hitbox"],width:2f,height:3f,response:1b}""",
                base,
            )
            authority.execute(
                """summon minecraft:marker ^ ^ ^2 {Tags:["demo.locator"]}""",
                base,
            )
            authority.execute(
                """summon minecraft:marker ^ ^ ^3 {Tags:["demo.locator"]}""",
                base,
            )

            val root = entities.select("@e[type=minecraft:item_display,tag=demo.root,limit=1,distance=..1]", base).single() as ItemDisplayEntity
            val context = base.copy(executor = root, position = root.physics.position)
            authority.execute("tag @s add demo.playing", context)
            authority.execute("scoreboard objectives add demo.frame dummy", context)
            authority.execute("scoreboard players set @s demo.frame 4", context)
            authority.execute("data modify entity @s item.tag.CustomModelData set value 27", context)
            authority.execute("data merge entity @s {interpolation_duration:6}", context)
            authority.execute("tp @s ~1 ~2 ~3 ~10 ~-5", context)

            assertTrue("demo.playing" in root.commandTags)
            assertEquals(authority.score(root.uuid.toString(), "demo.frame"), 4)
            assertEquals(root.stack!!.nbt.nbt["CustomModelData"], 27)
            assertEquals(root.interpolationDurationTicks, 6)
            assertEquals(root.physics.position, Vec3d(5.0, 7.0, 9.0))
            assertEquals(root.physics.rotation.yaw, 10.0f)
            assertEquals(root.physics.rotation.pitch, -5.0f)

            val interaction = entities.select(
                "@e[type=interaction,tag=demo.hitbox,sort=nearest,limit=1,distance=..2]",
                base,
            ).single() as InteractionEntity
            assertEquals(interaction.width, 2.0f)
            assertEquals(interaction.height, 3.0f)
            var anchored: DataPackCommandContext? = null
            authority.execute(
                "execute anchored eyes positioned ^ ^ ^1 run say anchored",
                base.copy(executor = interaction, position = interaction.physics.position),
            ) { _, current ->
                anchored = current
                1
            }
            val anchoredContext = requireNotNull(anchored)
            assertEquals(
                anchoredContext.position,
                interaction.physics.position.plus(y = interaction.eyeHeight.toDouble(), z = 1.0),
            )
            assertEquals(anchoredContext.anchor, de.bixilon.minosoft.assets.datapack.DataPackCommandAnchor.FEET)
            assertEquals(
                entities.select("@e[type=marker,tag=demo.locator,distance=..2]", base).single().physics.position,
                Vec3d(4.0, 5.0, 8.0),
            )

            val node = root.attachment.passengers.single()
            val tickFunction = ResourceLocation.of("demo:tick")
            DataPackFunctionRuntime(
                DataPackFunctionLibrary(
                    functions = mapOf(
                        tickFunction to DataPackFunction(
                            tickFunction,
                            listOf(
                                "execute as @e[type=item_display,tag=demo.root] at @s run tag @s add demo.executed",
                                "execute as @e[type=item_display,tag=demo.root] on passengers if entity @s[tag=demo.node] run data merge entity @s {interpolation_duration:9}",
                                "execute store result score #count demo.frame if entity @e[type=marker,tag=demo.locator]",
                                """execute rotated 90 0 positioned 4 5 6 run summon minecraft:marker ^ ^ ^1 {Tags:["demo.rotated"]}""",
                            ),
                        ),
                    ),
                    tags = emptyMap(),
                ),
                authority,
            ).execute("demo:tick")
            assertTrue("demo.executed" in root.commandTags)
            assertEquals((node as TextDisplayEntity).interpolationDurationTicks, 9)
            assertEquals(authority.score("#count", "demo.frame"), 2)
            val rotated = entities.select("@e[tag=demo.rotated]", base).single().physics.position
            assertEquals(rotated.x, 3.0, 0.000001)
            assertEquals(rotated.y, 5.0, 0.000001)
            assertEquals(rotated.z, 6.0, 0.000001)

            authority.execute("rotate @s ~5 ~-2", context)
            assertEquals(root.physics.rotation.yaw, 15.0f)
            assertEquals(root.physics.rotation.pitch, -7.0f)

            authority.execute("ride @s mount ${root.uuid}", base.copy(executor = node))
            assertSame(node.attachment.vehicle, root)
            assertEquals(authority.execute("kill @e[tag=demo.hitbox]", base), 1)
            assertTrue(entities.select("@e[tag=demo.hitbox]", base).isEmpty())
        } finally {
            RenderingOptions.disabled = previous
        }
    }

    @Test
    fun `rolls entity mutations and lifecycle back as one transaction`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val origin = Vec3d(1.0, 2.0, 3.0)
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val authority = LocalDataPackCommandAuthority(
                origin = { origin },
                spawn = { type, data, position -> factory.summon(type, data, position) },
                entities = entities,
            )
            val context = DataPackCommandContext(ResourceLocation.of("demo:load"), 0, 0)
            authority.execute(
                """summon minecraft:item_display ~ ~ ~ {Tags:["stable"],item:{id:"minecraft:carrot_on_a_stick",Count:1b,tag:{CustomModelData:4}},Passengers:[{id:"minecraft:text_display",Tags:["stable.node"],text:'{"text":"Stable"}'}]}""",
                context,
            )
            val root = entities.select("@e[tag=stable]", context).single() as ItemDisplayEntity
            val passenger = root.attachment.passengers.single()

            val transaction = authority.beginTransaction()
            authority.execute("tag @e[tag=stable] add candidate", context)
            authority.execute("data merge entity @e[tag=stable] {interpolation_duration:40}", context)
            authority.execute("tp @e[tag=stable] ~10 ~ ~", context)
            authority.execute("""summon minecraft:marker ~ ~ ~ {Tags:["candidate.created"]}""", context)
            authority.execute("kill @e[tag=stable]", context)
            transaction.rollback()

            val restored = entities.select("@e[tag=stable]", context).single()
            assertSame(root, restored)
            assertEquals(root.physics.position, origin)
            assertEquals(root.interpolationDurationTicks, 0)
            assertTrue("candidate" !in root.commandTags)
            assertSame(root, passenger.attachment.vehicle)
            assertTrue(entities.select("@e[tag=candidate.created]", context).isEmpty())
        } finally {
            RenderingOptions.disabled = previous
        }
    }
}
