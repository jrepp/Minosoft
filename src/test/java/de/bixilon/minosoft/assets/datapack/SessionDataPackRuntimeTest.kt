/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.datapack

import de.bixilon.minosoft.assets.model.generation.ContentFidelitySnapshot
import de.bixilon.minosoft.assets.model.generation.ContentGenerationStore
import de.bixilon.minosoft.assets.model.generation.PreparedContent
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionDataPackRuntimeTest {

    @Test
    fun `publishes only a generation whose load tag succeeds`() {
        val store = ContentGenerationStore<ContentFidelitySnapshot>()
        val commands = mutableListOf<String>()
        val cleaned = mutableListOf<String>()
        val host = SessionDataPackRuntime(store, DataPackCommandSink { command, _ ->
            if (command == "reject") error("rejected")
            commands += command
            1
        })

        val first = store.reload {
            PreparedContent(snapshot("first"), AutoCloseable { cleaned += "first" })
        }
        assertTrue(host.refresh())
        assertEquals(first, host.activeGenerationId)
        assertEquals(listOf("first"), commands)
        assertFalse(host.refresh())

        store.reload {
            PreparedContent(snapshot("reject"), AutoCloseable { cleaned += "rejected" })
        }
        assertFailsWith<IllegalStateException> { host.refresh() }
        assertEquals(first, host.activeGenerationId)
        assertEquals(emptyList(), cleaned)

        host.tick()
        assertEquals(1L, host.tick)
        host.close()
        assertEquals(listOf("first"), cleaned)
        store.close()
        assertEquals(listOf("first", "rejected"), cleaned)
    }

    private fun snapshot(loadCommand: String): ContentFidelitySnapshot {
        val load = ResourceLocation.of("test:load")
        val library = DataPackFunctionLibrary(
            functions = mapOf(load to DataPackFunction(load, listOf(loadCommand))),
            tags = mapOf(
                ResourceLocation.of("minecraft:load") to DataPackFunctionTag(
                    ResourceLocation.of("minecraft:load"),
                    listOf(DataPackFunctionReference(load.toString())),
                ),
            ),
        )
        return ContentFidelitySnapshot(dataPackFunctions = library)
    }
}
