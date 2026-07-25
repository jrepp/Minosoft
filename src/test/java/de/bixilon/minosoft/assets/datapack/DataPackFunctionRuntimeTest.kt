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

package de.bixilon.minosoft.assets.datapack

import de.bixilon.minosoft.assets.directory.DirectoryAssetsManager
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataPackFunctionRuntimeTest {

    @Test
    fun `loads tags expands macros and schedules functions deterministically`() {
        val root = Files.createTempDirectory("datapack-functions")
        val functions = root.resolve("data/test/functions").createDirectories()
        val tags = root.resolve("data/minecraft/tags/functions").createDirectories()
        functions.resolve("init.mcfunction").writeText(
            """
            function test:spawn {args:{animation:"walk",frame:2}}
            schedule function test:later 2t replace
            """.trimIndent(),
        )
        functions.resolve("spawn.mcfunction").writeText("\$summon minecraft:item_display \$(args)")
        functions.resolve("later.mcfunction").writeText("say later")
        functions.resolve("tick.mcfunction").writeText("say tick")
        tags.resolve("load.json").writeText("""{"values":["test:init"]}""")
        tags.resolve("tick.json").writeText("""{"values":["test:tick"]}""")
        val manager = DirectoryAssetsManager(root, prefix = "data")
        val commands = mutableListOf<String>()
        try {
            manager.load()
            val runtime = DataPackFunctionRuntime(
                DataPackFunctionLibrary.load(manager),
                DataPackCommandSink { command, _ ->
                    commands += command
                    1
                },
            )

            runtime.load()
            assertEquals(listOf("""summon minecraft:item_display {animation:"walk",frame:2}"""), commands)
            assertEquals(1, runtime.scheduledCount)
            runtime.tick()
            runtime.tick()
            assertEquals(
                listOf(
                    """summon minecraft:item_display {animation:"walk",frame:2}""",
                    "say tick",
                    "say tick",
                    "say later",
                ),
                commands,
            )
        } finally {
            manager.unload()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recursion and command work are bounded`() {
        val loop = de.bixilon.minosoft.data.registries.identified.ResourceLocation.of("test:loop")
        val library = DataPackFunctionLibrary(
            functions = mapOf(loop to DataPackFunction(loop, listOf("function test:loop"))),
            tags = emptyMap(),
        )
        val runtime = DataPackFunctionRuntime(
            library,
            DataPackCommandSink { _, _ -> 1 },
            DataPackFunctionRuntime.Limits(maxDepth = 4, maxCommands = 10),
        )

        assertFailsWith<IllegalArgumentException> { runtime.execute("test:loop") }
    }

    @Test
    fun `scheduled work shares a tick budget and failed scheduling is atomic`() {
        val task = de.bixilon.minosoft.data.registries.identified.ResourceLocation.of("test:task")
        val library = DataPackFunctionLibrary(
            functions = mapOf(task to DataPackFunction(task, listOf("say one", "say two"))),
            tags = emptyMap(),
        )
        val runtime = DataPackFunctionRuntime(
            library,
            DataPackCommandSink { _, _ -> 1 },
            DataPackFunctionRuntime.Limits(maxCommands = 3, maxScheduled = 2),
        )
        runtime.schedule("test:task", 1)
        runtime.schedule("test:task", 1)

        assertFailsWith<IllegalArgumentException> { runtime.schedule("test:task", 1) }
        assertEquals(2, runtime.scheduledCount)
        assertFailsWith<IllegalStateException> { runtime.tick() }
    }

    @Test
    fun `nested execute work consumes the command budget`() {
        val function = ResourceLocation.of("test:nested_execute")
        val library = DataPackFunctionLibrary(
            functions = mapOf(
                function to DataPackFunction(
                    function,
                    listOf("execute positioned ~ ~ ~ run say bounded"),
                ),
            ),
            tags = emptyMap(),
        )
        val runtime = DataPackFunctionRuntime(
            library,
            LocalDataPackCommandAuthority(),
            DataPackFunctionRuntime.Limits(maxCommands = 1),
        )

        assertFailsWith<IllegalStateException> { runtime.execute("test:nested_execute") }
    }

    @Test
    fun `schedule delay overflow does not mutate the queue`() {
        val runtime = DataPackFunctionRuntime(
            DataPackFunctionLibrary(emptyMap(), emptyMap()),
            DataPackCommandSink { _, _ -> 1 },
        )
        runtime.tick()

        assertFailsWith<IllegalArgumentException> { runtime.schedule("test:later", Long.MAX_VALUE) }
        assertEquals(0, runtime.scheduledCount)
    }

    @Test
    fun `loads macro arguments from command storage and honors return`() {
        val outer = ResourceLocation.of("test:outer")
        val inner = ResourceLocation.of("test:inner")
        val library = DataPackFunctionLibrary(
            functions = mapOf(
                outer to DataPackFunction(
                    outer,
                    listOf(
                        "return run function test:inner with storage test:runtime args",
                        "say unreachable outer",
                    ),
                ),
                inner to DataPackFunction(
                    inner,
                    listOf(
                        "\$say \$(name)",
                        "return 7",
                        "say unreachable inner",
                    ),
                ),
            ),
            tags = emptyMap(),
        )
        val messages = mutableListOf<String>()
        val authority = LocalDataPackCommandAuthority(messages::add)
        val context = DataPackCommandContext(outer, 0, 0)
        authority.execute("""data modify storage test:runtime args set value {name:"rig loaded"}""", context)

        val result = DataPackFunctionRuntime(library, authority).execute("test:outer")

        assertEquals(7, result)
        assertEquals(listOf("rig loaded"), messages)
    }

    @Test
    fun `missing storage macro source does not enter function`() {
        val outer = ResourceLocation.of("test:outer")
        val inner = ResourceLocation.of("test:inner")
        val library = DataPackFunctionLibrary(
            functions = mapOf(
                outer to DataPackFunction(
                    outer,
                    listOf("function test:inner with storage test:missing args"),
                ),
                inner to DataPackFunction(inner, listOf("\$say \$(name)")),
            ),
            tags = emptyMap(),
        )
        val messages = mutableListOf<String>()

        val result = DataPackFunctionRuntime(
            library,
            LocalDataPackCommandAuthority(messages::add),
        ).execute("test:outer")

        assertEquals(0, result)
        assertEquals(emptyList(), messages)
    }

    @Test
    fun `missing macro key rejects function before side effects`() {
        val outer = ResourceLocation.of("test:outer")
        val inner = ResourceLocation.of("test:inner")
        val library = DataPackFunctionLibrary(
            functions = mapOf(
                outer to DataPackFunction(
                    outer,
                    listOf("function test:inner with storage test:runtime args"),
                ),
                inner to DataPackFunction(
                    inner,
                    listOf("say should-not-run", "\$say \$(name)"),
                ),
            ),
            tags = emptyMap(),
        )
        val messages = mutableListOf<String>()
        val authority = LocalDataPackCommandAuthority(messages::add)
        authority.execute(
            "data modify storage test:runtime args set value {other:1}",
            DataPackCommandContext(outer, 0, 0),
        )

        val result = DataPackFunctionRuntime(library, authority).execute("test:outer")

        assertEquals(0, result)
        assertEquals(emptyList(), messages)
    }

    @Test
    fun `executes score and storage conditions with result stores`() {
        val outer = ResourceLocation.of("test:outer")
        val nested = ResourceLocation.of("test:nested")
        val library = DataPackFunctionLibrary(
            functions = mapOf(
                outer to DataPackFunction(
                    outer,
                    listOf(
                        "scoreboard objectives add test.frame dummy",
                        "scoreboard players set rig test.frame 1",
                        "execute if score rig test.frame matches 1 run scoreboard players add rig test.frame 2",
                        "execute store result storage test:runtime frame int 1 run scoreboard players get rig test.frame",
                        "execute if data storage test:runtime {frame:3} run function test:nested",
                        "execute unless score rig test.frame matches 3 run say unreachable",
                    ),
                ),
                nested to DataPackFunction(nested, listOf("say matched")),
            ),
            tags = emptyMap(),
        )
        val messages = mutableListOf<String>()
        val authority = LocalDataPackCommandAuthority(messages::add)

        DataPackFunctionRuntime(library, authority).execute("test:outer")

        assertEquals(3, authority.score("rig", "test.frame"))
        assertEquals(3, authority.storage(ResourceLocation.of("test:runtime"))!!["frame"])
        assertEquals(listOf("matched"), messages)
    }

    @Test
    fun `execute run return stops the owning function`() {
        val outer = ResourceLocation.of("test:outer")
        val library = DataPackFunctionLibrary(
            functions = mapOf(
                outer to DataPackFunction(
                    outer,
                    listOf(
                        "scoreboard objectives add aj.i dummy",
                        "scoreboard players set #success aj.i 1",
                        "execute if score #success aj.i matches 1 run return 7",
                        "scoreboard players set #success aj.i 99",
                    ),
                ),
            ),
            tags = emptyMap(),
        )
        val authority = LocalDataPackCommandAuthority()

        val result = DataPackFunctionRuntime(library, authority).execute("test:outer")

        assertEquals(7, result)
        assertEquals(1, authority.score("#success", "aj.i"))
    }

    @Test
    fun `execute if function can return a nested function result`() {
        val outer = ResourceLocation.of("test:outer")
        val predicate = ResourceLocation.of("test:is_rig_outdated")
        val value = ResourceLocation.of("test:value")
        val library = DataPackFunctionLibrary(
            functions = mapOf(
                outer to DataPackFunction(
                    outer,
                    listOf(
                        "execute if function test:is_rig_outdated run return run function test:value",
                        "return fail",
                    ),
                ),
                predicate to DataPackFunction(predicate, listOf("return 1")),
                value to DataPackFunction(value, listOf("return 9")),
            ),
            tags = emptyMap(),
        )

        val result = DataPackFunctionRuntime(library, LocalDataPackCommandAuthority()).execute("test:outer")

        assertEquals(9, result)
    }
}
