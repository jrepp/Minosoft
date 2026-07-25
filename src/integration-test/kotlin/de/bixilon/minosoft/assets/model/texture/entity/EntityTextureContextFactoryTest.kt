/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.texture.entity

import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.entities.animal.Panda
import de.bixilon.minosoft.data.entities.entities.animal.Pig
import de.bixilon.minosoft.data.entities.entities.animal.horse.Llama
import de.bixilon.minosoft.data.registries.biomes.Biome
import de.bixilon.minosoft.data.registries.blocks.state.TestBlockStates
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.registries.registry.RegistryItem
import de.bixilon.minosoft.data.world.WorldTestUtil.fill
import de.bixilon.minosoft.data.world.WorldTestUtil.initialize
import de.bixilon.minosoft.data.world.biome.source.DummyBiomeSource
import de.bixilon.minosoft.data.world.difficulty.Difficulties
import de.bixilon.minosoft.data.world.difficulty.WorldDifficulty
import de.bixilon.minosoft.data.world.time.WorldTime
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.tags.MinecraftTagTypes.BIOME
import de.bixilon.minosoft.tags.Tag
import de.bixilon.minosoft.tags.TagList
import de.bixilon.minosoft.tags.TagManager
import de.bixilon.minosoft.util.KUtil.startInit
import de.bixilon.kmath.vec.vec3.d.Vec3d
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["assets", "entities"])
class EntityTextureContextFactoryTest {

    fun `live entity context exposes ETF equipment genes attributes and alternate NBT`() {
        val session = createSession()
        val pandaType = requireNotNull(session.registries.entityType[Panda.identifier])
        val pigType = requireNotNull(session.registries.entityType[Pig.identifier])
        val panda = Panda(session, pandaType, EntityData(session), Vec3d.EMPTY, EntityRotation.EMPTY).apply { startInit() }
        val vehicle = Pig(session, pigType, EntityData(session), Vec3d.EMPTY, EntityRotation.EMPTY).apply { startInit() }
        panda.attachment.vehicle = vehicle
        session.player.commandNbt["ClientMarker"] = "ready"
        vehicle.commandNbt["VehicleMarker"] = "mounted"

        val environment = EntityTextureRuntimeEnvironment.installLoadedMods(listOf("entity_texture_features", "geckolib"))
        val context = try {
            EntityTextureContextFactory.create(panda)
        } finally {
            environment.close()
        }

        assertEquals(context.strings("hiddenGene"), listOf("normal"))
        assertEquals(context.strings("variant"), listOf("panda"))
        assertTrue("geckolib" in context.strings("modLoaded").orEmpty())
        assertEquals(context.strings["nbt_client.ClientMarker"], listOf("ready"))
        assertEquals(context.strings["nbt_vehicle.VehicleMarker"], listOf("mounted"))
        assertFalse(context.boolean("spawner")!!)
        assertTrue(EntityTextureRuntimeEnvironment.loadedMods().isEmpty())
    }

    fun `live llama context exposes equipped items movement speed jump and inventory strength`() {
        val session = createSession()
        val llamaType = requireNotNull(session.registries.entityType[Llama.identifier])
        val llama = Llama(session, llamaType, EntityData(session), Vec3d.EMPTY, EntityRotation.EMPTY).apply { startInit() }
        val item = requireNotNull(session.registries.item[ResourceLocation.of("minecraft:diamond_sword")])
        llama.equipment[EquipmentSlots.MAIN_HAND] = ItemStack(item)

        val context = EntityTextureContextFactory.create(llama)

        assertTrue("diamond_sword" in context.strings("items").orEmpty())
        assertTrue(context.boolean("items_any")!!)
        assertTrue(context.boolean("items_holding")!!)
        assertFalse(context.boolean("items_wearing")!!)
        assertEquals(context.number("llamaInventory"), llama.strength.toDouble())
        assertEquals(context.number("jumpStrength"), llama.attributes[de.bixilon.minosoft.data.registries.effects.attributes.MinecraftAttributes.HORSE_JUMP_STRENGTH])
        assertEquals(context.number("maxSpeed"), llama.attributes[de.bixilon.minosoft.data.registries.effects.attributes.MinecraftAttributes.MOVEMENT_SPEED])
    }

    fun `live world context exposes biome tags and vertical block searches`() {
        val session = createSession()
        val plains = Biome(ResourceLocation.of("minecraft:plains"), 0.8f, 0.4f)
        session.world.initialize(1) { DummyBiomeSource(plains) }
        session.world.difficulty = WorldDifficulty(Difficulties.HARD, false)
        session.world.time = WorldTime(age = 1_536_000L)
        session.tags = TagManager(
            mapOf(
                BIOME to TagList(
                    mapOf(
                        ResourceLocation.of("minecraft:is_overworld") to Tag(setOf<RegistryItem>(plains)),
                    ),
                ),
            ),
        )
        session.world.fill(0, 66, 0, 0, 66, 0, TestBlockStates.TEST1)
        session.world.fill(0, 68, 0, 0, 68, 0, TestBlockStates.OPAQUE1)
        session.world.fill(0, 63, 0, 0, 63, 0, TestBlockStates.TEST3)
        session.world.fill(0, 62, 0, 0, 62, 0, TestBlockStates.TEST2)
        session.world.fill(0, 60, 0, 0, 60, 0, TestBlockStates.OPAQUE2)
        val pandaType = requireNotNull(session.registries.entityType[Panda.identifier])
        val panda = Panda(session, pandaType, EntityData(session), Vec3d(0.0, 64.0, 0.0), EntityRotation.EMPTY).apply { startInit() }

        val context = EntityTextureContextFactory.create(
            panda,
            setOf("blocks", "blockSpawned", "blockAbove", "blockAboveSolid", "blockBelow", "blockBelowSolid"),
        )

        assertTrue("minecraft:is_overworld" in context.strings("biomeTag").orEmpty())
        assertTrue("minecraft:test3" in context.strings("blocks").orEmpty())
        assertTrue("minecraft:air" in context.strings("blocks").orEmpty())
        assertEquals(context.strings("blocks"), context.strings("blockSpawned"))
        assertEquals(context.number("regionalDifficulty"), 3.75)
        assertTrue("minecraft:test1" in context.strings("blockAbove").orEmpty())
        assertTrue("minecraft:opaque1" in context.strings("blockAboveSolid").orEmpty())
        assertTrue("minecraft:test3" in context.strings("blockBelow").orEmpty())
        assertTrue("minecraft:opaque2" in context.strings("blockBelowSolid").orEmpty())
    }
}
