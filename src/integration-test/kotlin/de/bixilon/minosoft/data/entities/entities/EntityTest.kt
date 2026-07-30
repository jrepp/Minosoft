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

package de.bixilon.minosoft.data.entities.entities

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.exception.Broken
import de.bixilon.minosoft.data.entities.EntityAnimations
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.registries.entities.EntityFactory
import de.bixilon.minosoft.data.registries.entities.EntityType
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.text.TextComponent
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import org.testng.Assert.assertEquals
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.Assert.expectThrows
import org.testng.annotations.Test

@Test(groups = ["entities"])
class EntityTest {

    private fun create() = TestEntity().apply { init() }

    // < 1.13
    fun `set string as custom name`() {
        val player = create()
        player.data[Entity.CUSTOM_NAME_DATA] = "Test"
        assertEquals(player.customName, TextComponent("Test"))
    }

    fun `set text component as custom name`() {
        val player = create()
        val name = TextComponent("Test2").color(ChatColors.RED)
        player.data[Entity.CUSTOM_NAME_DATA] = name
        assertSame(player.customName, name)
    }

    fun `invisible and glowing flags are independent`() {
        val entity = create()

        entity.data[Entity.FLAGS_DATA] = 0x20
        assertEquals(entity.isInvisible, true)
        assertEquals(entity.hasGlowingEffect, false)

        entity.data[Entity.FLAGS_DATA] = 0x40
        assertEquals(entity.isInvisible, false)
        assertEquals(entity.hasGlowingEffect, true)

        entity.data[Entity.FLAGS_DATA] = 0x60
        assertEquals(entity.isInvisible, true)
        assertEquals(entity.hasGlowingEffect, true)
    }

    fun `fire canary toggle preserves unrelated synchronized flags`() {
        val entity = create()
        entity.data[Entity.FLAGS_DATA] = 0x40

        entity.isOnFire = true
        assertEquals(entity.isOnFire, true)
        assertEquals(entity.hasGlowingEffect, true)

        entity.isOnFire = false
        assertEquals(entity.isOnFire, false)
        assertEquals(entity.hasGlowingEffect, true)
    }

    fun `outline canary toggle preserves unrelated synchronized flags`() {
        val entity = create()
        entity.data[Entity.FLAGS_DATA] = 0x21

        entity.hasGlowingEffect = true
        assertEquals(entity.hasGlowingEffect, true)
        assertEquals(entity.isInvisible, true)
        assertEquals(entity.isOnFire, true)

        entity.hasGlowingEffect = false
        assertEquals(entity.hasGlowingEffect, false)
        assertEquals(entity.isInvisible, true)
        assertEquals(entity.isOnFire, true)
    }

    fun `raw tracked data is bounded and preserves protocol values`() {
        val entity = create()
        entity.data[19] = 2
        assertEquals(entity.data.raw(19), 2)
        entity.data[19] = null
        assertEquals(entity.data.raw(19), null)
        expectThrows(IllegalArgumentException::class.java) { entity.data.raw(-1) }
        expectThrows(IllegalArgumentException::class.java) { entity.data.raw(255) }
    }

    fun `protocol animations retain a bounded independently readable journal`() {
        val entity = create()
        entity.handleAnimation(EntityAnimations.SWING_MAIN_ARM)
        entity.handleAnimation(EntityAnimations.TAKE_DAMAGE)

        val first = entity.animationEvents.readAfter(0)
        assertEquals(first.events.map { it.animation }, listOf(EntityAnimations.SWING_MAIN_ARM, EntityAnimations.TAKE_DAMAGE))
        assertEquals(first.latestSequence, 2L)
        assertEquals(first.overflowed, false)
        assertEquals(entity.animationEvents.readAfter(first.latestSequence).events, emptyList<Any>())

        repeat(de.bixilon.minosoft.data.entities.EntityAnimationJournal.DEFAULT_CAPACITY + 1) {
            entity.handleAnimation(EntityAnimations.EAT_FOOD)
        }
        val overflow = entity.animationEvents.readAfter(0)
        assertTrue(overflow.overflowed)
        assertEquals(overflow.events.size, de.bixilon.minosoft.data.entities.EntityAnimationJournal.DEFAULT_CAPACITY)
        assertEquals(overflow.events.last().sequence, overflow.latestSequence)
    }

    private class TestEntity(session: PlaySession = createSession()) : Entity(session, EntityType(Companion.identifier, null, 1.0f, 1.0f, factory = Companion), EntityData(session), Vec3d.EMPTY, EntityRotation.EMPTY) {


        companion object : EntityFactory<TestEntity> {
            override fun build(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation) = Broken()
            override val identifier = minosoft("test")
        }
    }
}
