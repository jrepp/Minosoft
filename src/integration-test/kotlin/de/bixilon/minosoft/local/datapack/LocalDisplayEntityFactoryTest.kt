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
import de.bixilon.minosoft.assets.directory.DirectoryAssetsManager
import de.bixilon.minosoft.data.entities.entities.InteractionEntity
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayContext
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.data.entities.entities.display.TextDisplayEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderingOptions
import de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineColor
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.modding.loader.fabric.FabricRemoteEntityDefinition
import de.bixilon.minosoft.modding.loader.fabric.FabricRemoteRegistrySync
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.protocol.packets.s2c.play.entity.passenger.EntityAttachS2CP
import de.bixilon.minosoft.protocol.protocol.buffers.play.PlayInByteBuffer
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertSame
import org.testng.Assert.assertNull
import org.testng.Assert.assertThrows
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.nio.ByteBuffer
import java.nio.file.Paths

class LocalDisplayEntityFactoryTest {

    @Test
    fun `runs the pinned Naturalist controller summon and removal fixture`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        val fixture = Paths.get(
            requireNotNull(
                javaClass.classLoader.getResource("content_fidelity/naturalist-controller-render/fixture.json"),
            ).toURI(),
        ).parent
        val data = DirectoryAssetsManager(fixture.resolve("datapacks"), prefix = "data")
        val rattlesnake = ResourceLocation.of("naturalist:rattlesnake")
        val registration = FabricRemoteRegistrySync.register(
            "naturalist-controller-fixture",
            listOf(FabricRemoteEntityDefinition(rattlesnake, 0.6f, 0.7f)),
        )
        try {
            IT.VERSION
            data.load()
            val session = createSession(version = "1.20.4")
            val origin = Vec3d(0.5, 20.0, 0.5)
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val runtime = DataPackFunctionRuntime(
                DataPackFunctionLibrary.load(data),
                LocalDataPackCommandAuthority(
                    origin = { origin },
                    spawn = { type, nbt, position -> factory.summon(type, nbt, position) },
                    entities = entities,
                ),
            )

            runtime.load()
            assertEquals(runtime.execute("minosoft_acceptance:naturalist_snake/summon"), 1)
            val entity = session.world.entities.single { it.type.identifier == rattlesnake }
            assertTrue("minosoft.acceptance.naturalist_snake" in entity.commandTags)
            assertEquals(entity.hasGravity, false)
            assertEquals(runtime.execute("minosoft_acceptance:naturalist_snake/remove"), 1)
            assertTrue(session.world.entities.none { it.type.identifier == rattlesnake })
            assertEquals(runtime.execute("minosoft_acceptance:naturalist_snake/summon_named"), 1)
            val named = session.world.entities.single { it.type.identifier == rattlesnake }
            assertEquals(named.customName?.message, "Iris Rattlesnake")
            assertTrue(named.isNameVisible)
            assertEquals(runtime.execute("minosoft_acceptance:naturalist_snake/remove"), 1)
        } finally {
            registration.close()
            if (data.loaded) data.unload()
            RenderingOptions.disabled = previous
        }
    }

    @Test
    fun `summons only owner-declared dependent mod entity definitions`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        val identifier = ResourceLocation.of("test:dependent_living")
        val registration = FabricRemoteRegistrySync.register(
            "local-dependent-fixture",
            listOf(FabricRemoteEntityDefinition(identifier, 0.6f, 0.7f)),
        )
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val entity = LocalDisplayEntityFactory(session).summon(
                identifier,
                mapOf(
                    "Tags" to listOf("test.local.dependent"),
                    "NoGravity" to true,
                    "Fire" to 100,
                ),
                Vec3d(0.5, 20.0, 0.5),
            )

            assertEquals(entity.type.identifier, identifier)
            assertEquals(entity.hasGravity, false)
            assertTrue(entity.isOnFire)
            assertTrue("test.local.dependent" in entity.commandTags)
            assertSame(session.registries.entityType[identifier], entity.type)
        } finally {
            registration.close()
            RenderingOptions.disabled = previous
        }

        val session = createSession(version = "1.20.4")
        assertThrows(IllegalArgumentException::class.java) {
            LocalDisplayEntityFactory(session).summon(identifier, emptyMap(), Vec3d.EMPTY)
        }
    }

    @Test
    fun `summons the bounded living content fidelity fixture`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val entity = LocalDisplayEntityFactory(session).summon(
                ResourceLocation.of("minecraft:zombie"),
                mapOf(
                    "Tags" to listOf("minosoft.acceptance.emf_etf"),
                    "Health" to 20.0f,
                    "NoGravity" to true,
                ),
                Vec3d(0.5, 20.0, 0.5),
            )

            assertEquals(entity.type.identifier, ResourceLocation.of("minecraft:zombie"))
            assertEquals(entity.commandNbt["Health"], 20.0f)
            assertEquals(entity.hasGravity, false)
            assertTrue("minosoft.acceptance.emf_etf" in entity.commandTags)
        } finally {
            RenderingOptions.disabled = previous
        }
    }

    @Test
    fun `display glow override selects color but does not enable outlines`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val display = LocalDisplayEntityFactory(session).summon(
                ResourceLocation.of("minecraft:item_display"),
                mapOf("glow_color_override" to 0x3366CC),
                Vec3d.EMPTY,
            ) as ItemDisplayEntity

            assertNull(EntityOutlineColor.resolve(display))
            display.data[de.bixilon.minosoft.data.entities.entities.Entity.FLAGS_DATA] = 0x40
            assertEquals(EntityOutlineColor.resolve(display), RGBAColor(0x33, 0x66, 0xCC))
        } finally {
            RenderingOptions.disabled = previous
        }
    }

    @Test
    fun `legacy attach packet keeps vehicle and leash modes distinct`() {
        IT.VERSION
        val session = createSession(version = "1.8.9")

        val vehiclePacket = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + 1)
            .putInt(7)
            .putInt(8)
            .put(0)
            .array()
        val leashPacket = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + 1)
            .putInt(7)
            .putInt(8)
            .put(1)
            .array()

        assertEquals(
            false,
            EntityAttachS2CP(PlayInByteBuffer(vehiclePacket, session)).leash,
        )
        assertEquals(
            true,
            EntityAttachS2CP(PlayInByteBuffer(leashPacket, session)).leash,
        )
    }

    @Test
    fun `modern attach packet owns leash state without creating a vehicle mount`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val factory = LocalDisplayEntityFactory(session)
            val entity = factory.summon(ResourceLocation.of("minecraft:marker"), emptyMap(), Vec3d.EMPTY)
            val holder = factory.summon(ResourceLocation.of("minecraft:marker"), emptyMap(), Vec3d(1.0, 0.0, 0.0))
            val attach = ByteBuffer.allocate(Int.SIZE_BYTES * 2)
                .putInt(requireNotNull(entity.id))
                .putInt(requireNotNull(holder.id))
                .array()

            EntityAttachS2CP(PlayInByteBuffer(attach, session)).handle(session)

            assertSame(holder, entity.attachment.leashHolder)
            assertEquals(null, entity.attachment.vehicle)

            val detach = ByteBuffer.allocate(Int.SIZE_BYTES * 2)
                .putInt(requireNotNull(entity.id))
                .putInt(-1)
                .array()
            EntityAttachS2CP(PlayInByteBuffer(detach, session)).handle(session)
            assertEquals(null, entity.attachment.leashHolder)
        } finally {
            RenderingOptions.disabled = previous
        }
    }

    @Test
    fun `summons an Animated Java root with retained display passenger state`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION // initialize the integration-test version/registry catalog
            val session = createSession(version = "1.20.4")
            val nbt = SnbtParser.compound(
                """{Tags:["aj.global.root","demo.rig.root"],Glowing:1b,billboard:"center",brightness:{sky:15,block:7},view_range:2f,width:4f,height:3f,shadow_radius:2f,shadow_strength:0.75f,teleport_duration:4,glow_color_override:16711935,transformation:{translation:[1f,2f,3f],left_rotation:[0f,0f,0f,1f],scale:[2f,2f,2f],right_rotation:[0f,0f,0f,1f]},item:{id:"minecraft:carrot_on_a_stick",Count:1b,tag:{CustomModelData:42}},item_display:"head",Passengers:[{id:"minecraft:text_display",Tags:["demo.rig.label"],text:'{"text":"Hello"}',line_width:80,shadow:1b,alignment:"left"}]}""",
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
            assertEquals(4.0f, root.dimensions.x)
            assertEquals(3.0f, root.dimensions.y)
            assertEquals(Vec3d(-2.0, 0.0, -2.0), root.defaultAABB!!.min)
            assertEquals(Vec3d(2.0, 3.0, 2.0), root.defaultAABB!!.max)
            assertTrue(root.isWithinViewRange(127.0 * 127.0))
            assertEquals(false, root.isWithinViewRange(128.0 * 128.0))
            assertEquals(2.0f, root.shadowRadius)
            assertEquals(0.75f, root.shadowStrength)
            assertEquals(4, root.positionRotationInterpolationDurationTicks)
            assertEquals(0xFF00FF, root.glowColorOverride)
            assertTrue(root.hasGlowingEffect)
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
            assertTrue(interaction.response)
            interaction.recordInteraction(session.player, attack = false, timestamp = 5L)
            interaction.recordInteraction(session.player, attack = true, timestamp = 6L)
            assertEquals(
                5L,
                (interaction.commandNbt["interaction"] as Map<*, *>)["timestamp"],
            )
            assertEquals(
                6L,
                (interaction.commandNbt["attack"] as Map<*, *>)["timestamp"],
            )
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
                                "execute as @e[type=interaction,tag=demo.hitbox] on target run tag @s add demo.interacted",
                                "execute as @e[type=interaction,tag=demo.hitbox] on attacker run tag @s add demo.attacked",
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
            assertTrue("demo.interacted" in session.player.commandTags)
            assertTrue("demo.attacked" in session.player.commandTags)
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
    fun `runs upstream shaped interaction callback through entity macro data`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        try {
            IT.VERSION
            val session = createSession(version = "1.20.4")
            val origin = session.player.physics.position
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val authority = LocalDataPackCommandAuthority(
                origin = { origin },
                spawn = { type, data, position -> factory.summon(type, data, position) },
                entities = entities,
            )
            val base = DataPackCommandContext(ResourceLocation.of("demo:interaction"), 0, 0)
            authority.execute(
                """
                summon minecraft:interaction ~ ~ ~ {
                    UUID:[I;-1985229329,-19088744,-19088744,-1985229329],
                    Tags:["aj.global.interaction"],
                    width:2f,
                    height:3f,
                    data:{animated_java:{on_interact_function:"function demo:clicked"}}
                }
                """.trimIndent().replace("\n", ""),
                base,
            )
            val interaction = entities.select("@e[type=interaction,tag=aj.global.interaction]", base)
                .single() as InteractionEntity
            interaction.recordInteraction(session.player, attack = false, timestamp = 0L)

            val on = ResourceLocation.of("animated_java:global/interactions/interaction/on")
            val check = ResourceLocation.of("animated_java:global/interactions/interaction/check")
            val dispatch = ResourceLocation.of("animated_java:global/interactions/interaction/do")
            val dynamic = ResourceLocation.of("animated_java:global/interactions/interaction/dynamic")
            val clicked = ResourceLocation.of("demo:clicked")
            val runtime = DataPackFunctionRuntime(
                DataPackFunctionLibrary(
                    functions = mapOf(
                        on to DataPackFunction(
                            on,
                            listOf(
                                "advancement revoke @s only animated_java:global/interactions/interaction/trigger",
                                "tag @s add aj.interacting_player",
                                "execute as @e[type=interaction,tag=aj.global.interaction,distance=..8] if data entity @s interaction run function animated_java:global/interactions/interaction/check",
                                "tag @s remove aj.interacting_player",
                            ),
                        ),
                        check to DataPackFunction(
                            check,
                            listOf(
                                "execute store result score #gametime aj.i run time query gametime",
                                "execute store result score #timestamp aj.i run data get entity @s interaction.timestamp",
                                "scoreboard players set #check aj.i 0",
                                "execute if score #timestamp aj.i = #gametime aj.i if data entity @s data.animated_java.on_interact_function store success score #check aj.i on target if entity @s[tag=aj.interacting_player]",
                                "execute if score #check aj.i matches 1 run function animated_java:global/interactions/interaction/do",
                            ),
                        ),
                        dispatch to DataPackFunction(
                            dispatch,
                            listOf("function animated_java:global/interactions/interaction/dynamic with entity @s data.animated_java"),
                        ),
                        dynamic to DataPackFunction(dynamic, listOf("\$$(on_interact_function)")),
                        clicked to DataPackFunction(clicked, listOf("tag @s add demo.clicked")),
                    ),
                    tags = emptyMap(),
                ),
                authority,
            )
            authority.execute("scoreboard objectives add aj.i dummy", base)

            assertEquals(1, runtime.executeAs(on.toString(), session.player))
            assertEquals(1, authority.score("#check", "aj.i"))
            assertTrue("demo.clicked" in interaction.commandTags)
            assertTrue("aj.interacting_player" !in session.player.commandTags)

            val interactionContext = base.copy(
                executor = interaction,
                position = interaction.physics.position,
            )
            authority.execute(
                "data modify storage animated_java:gu in set from entity @s UUID",
                interactionContext,
            )
            assertEquals(
                listOf(-1985229329, -19088744, -19088744, -1985229329),
                authority.storage(ResourceLocation.of("animated_java:gu"))!!["in"],
            )

            authority.execute(
                "data modify storage animated_java:gu hex_chars set value ${
                    (0..255).joinToString(prefix = "[", postfix = "]") {
                        "\"${it.toString(16).padStart(2, '0')}\""
                    }
                }",
                base,
            )
            authority.execute("scoreboard players set 256 aj.i 256", base)
            val uuidRead = ResourceLocation.of("animated_java:global/gu/get_entity_uuid_string")
            val uuidReplace = ResourceLocation.of("animated_java:global/gu/replace_uuid_bytes")
            val uuidFormat = ResourceLocation.of("animated_java:global/gu/format_uuid")
            val uuidCommands = mutableListOf(
                "data modify storage animated_java:gu temp set value {0:0,1:0,2:0,3:0,4:0,5:0,6:0,7:0,8:0,9:0,a:0,b:0,c:0,d:0,e:0,f:0}",
                "data modify storage animated_java:gu in set from entity @s UUID",
            )
            for (word in 0..3) {
                val offset = word * 4
                uuidCommands += "execute store result score 0= aj.i store result score 1= aj.i run data get storage animated_java:gu in[$word]"
                uuidCommands += "execute store result storage animated_java:gu temp.${offset.toString(16)} int 1 run scoreboard players operation 0= aj.i %= 256 aj.i"
                uuidCommands += "execute store result score 2= aj.i run scoreboard players operation 1= aj.i /= 256 aj.i"
                uuidCommands += "execute store result storage animated_java:gu temp.${(offset + 1).toString(16)} int 1 run scoreboard players operation 1= aj.i %= 256 aj.i"
                uuidCommands += "execute store result score 3= aj.i run scoreboard players operation 2= aj.i /= 256 aj.i"
                uuidCommands += "execute store result storage animated_java:gu temp.${(offset + 2).toString(16)} int 1 run scoreboard players operation 2= aj.i %= 256 aj.i"
                uuidCommands += "execute store result storage animated_java:gu temp.${(offset + 3).toString(16)} int 1 run scoreboard players operation 3= aj.i /= 256 aj.i"
            }
            uuidCommands += "function $uuidReplace with storage animated_java:gu temp"
            uuidCommands += "function $uuidFormat with storage animated_java:gu temp"
            val replaceCommands = (0..15).map {
                val key = it.toString(16)
                "\$data modify storage animated_java:gu temp.$key set from storage animated_java:gu hex_chars[\$($key)]"
            }
            val uuidRuntime = DataPackFunctionRuntime(
                DataPackFunctionLibrary(
                    functions = mapOf(
                        uuidRead to DataPackFunction(uuidRead, uuidCommands),
                        uuidReplace to DataPackFunction(uuidReplace, replaceCommands),
                        uuidFormat to DataPackFunction(
                            uuidFormat,
                            listOf(
                                "\$data modify storage animated_java:gu out set value \"\$(3)\$(2)\$(1)\$(0)-\$(7)\$(6)-\$(5)\$(4)-\$(b)\$(a)-\$(9)\$(8)\$(f)\$(e)\$(d)\$(c)\"",
                            ),
                        ),
                    ),
                    tags = emptyMap(),
                ),
                authority,
            )
            uuidRuntime.executeAs(uuidRead.toString(), interaction)
            assertEquals(
                "89abcdef-fedc-ba98-fedc-ba9889abcdef",
                authority.storage(ResourceLocation.of("animated_java:gu"))!!["out"],
            )
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
