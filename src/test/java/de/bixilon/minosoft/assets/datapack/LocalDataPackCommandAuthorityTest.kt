/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.datapack

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LocalDataPackCommandAuthorityTest {
    private val context = DataPackCommandContext(ResourceLocation.of("test:load"), 0, 0)

    @Test
    fun `executes scoreboard state and operations`() {
        val authority = LocalDataPackCommandAuthority()
        authority.execute("scoreboard objectives add aj.id dummy", context)
        authority.execute("scoreboard players set root aj.id 4", context)
        authority.execute("scoreboard players add root aj.id 3", context)
        authority.execute("scoreboard players set other aj.id 2", context)
        authority.execute("scoreboard players operation root aj.id *= other aj.id", context)

        assertEquals(14, authority.score("root", "aj.id"))
        authority.execute("scoreboard players reset root aj.id", context)
        assertEquals(null, authority.score("root", "aj.id"))
    }

    @Test
    fun `scoreboard division and remainder match signed command semantics`() {
        val authority = LocalDataPackCommandAuthority()
        authority.execute("scoreboard objectives add aj.i dummy", context)
        authority.execute("scoreboard players set value aj.i -1", context)
        authority.execute("scoreboard players set divisor aj.i 256", context)
        authority.execute("scoreboard players operation quotient aj.i = value aj.i", context)
        authority.execute("scoreboard players operation remainder aj.i = value aj.i", context)
        authority.execute("scoreboard players operation quotient aj.i /= divisor aj.i", context)
        authority.execute("scoreboard players operation remainder aj.i %= divisor aj.i", context)

        assertEquals(-1, authority.score("quotient", "aj.i"))
        assertEquals(255, authority.score("remainder", "aj.i"))
    }

    @Test
    fun `execute compares two scores with vanilla operators`() {
        val messages = mutableListOf<String>()
        val authority = LocalDataPackCommandAuthority(messages::add)
        authority.execute("scoreboard objectives add aj.i dummy", context)
        authority.execute("scoreboard players set left aj.i 4", context)
        authority.execute("scoreboard players set right aj.i 4", context)

        authority.execute("execute if score left aj.i = right aj.i run say equal", context) { nested, current ->
            authority.execute(nested, current)
        }
        authority.execute("execute unless score left aj.i > right aj.i run say not-greater", context) { nested, current ->
            authority.execute(nested, current)
        }

        assertEquals(listOf("equal", "not-greater"), messages)
    }

    @Test
    fun `executes nested storage set merge append copy and remove`() {
        val authority = LocalDataPackCommandAuthority()
        authority.execute("""data modify storage demo:runtime rigs."1" set value {uuid:"one",frames:[{x:1}]}""", context)
        authority.execute("""data modify storage demo:runtime rigs."1" merge value {playing:true}""", context)
        authority.execute("""data modify storage demo:runtime rigs."1".frames append value {x:2}""", context)
        authority.execute("""data modify storage demo:temp selected set from storage demo:runtime rigs."1".frames[-1]""", context)
        authority.execute("""data remove storage demo:runtime rigs."1".uuid""", context)

        val rig = ((authority.storage(ResourceLocation.of("demo:runtime"))!!["rigs"] as Map<*, *>)["1"] as Map<*, *>)
        assertEquals(true, rig["playing"])
        assertEquals(listOf(mapOf("x" to 1), mapOf("x" to 2)), rig["frames"])
        assertEquals(mapOf("x" to 2), authority.storage(ResourceLocation.of("demo:temp"))!!["selected"])
        assertEquals(null, rig["uuid"])
    }

    @Test
    fun `unknown commands fail closed`() {
        assertFailsWith<UnsupportedDataPackCommandException> {
            LocalDataPackCommandAuthority().execute("summon minecraft:item_display ~ ~ ~ {}", context)
        }
    }

    @Test
    fun `rejects non-finite entity coordinates`() {
        val authority = LocalDataPackCommandAuthority(spawn = { _, _, _ -> })

        assertFailsWith<IllegalArgumentException> {
            authority.execute("summon minecraft:item_display NaN 0 0 {}", context)
        }
        assertFailsWith<IllegalArgumentException> {
            authority.execute("summon minecraft:item_display ^Infinity ^ ^ {}", context)
        }
    }

    @Test
    fun `execute facing and anchored retain bounded command context`() {
        val authority = LocalDataPackCommandAuthority()
        var nested: DataPackCommandContext? = null

        assertEquals(
            1,
            authority.execute(
                "execute anchored eyes facing 10 10 0 run say oriented",
                context.copy(position = Vec3d.EMPTY),
            ) { _, current ->
                nested = current
                1
            },
        )

        val captured = assertNotNull(nested)
        val rotation = assertNotNull(captured.rotation)
        assertEquals(DataPackCommandAnchor.EYES, captured.anchor)
        assertEquals(-90.0f, rotation.yaw, 0.0001f)
        assertEquals(-45.0f, rotation.pitch, 0.0001f)
    }

    @Test
    fun `positioned resets an anchored context to feet`() {
        val authority = LocalDataPackCommandAuthority()
        var nested: DataPackCommandContext? = null

        authority.execute(
            "execute anchored eyes positioned 1 2 3 run say moved",
            context.copy(position = Vec3d.EMPTY),
        ) { _, current ->
            nested = current
            1
        }

        val captured = assertNotNull(nested)
        assertEquals(Vec3d(1.0, 2.0, 3.0), captured.position)
        assertEquals(DataPackCommandAnchor.FEET, captured.anchor)
    }
}
