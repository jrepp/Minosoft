/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.hud.HUDElement
import de.bixilon.minosoft.gui.rendering.gui.hud.elements.HUDBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FabricUiHooksTest {
    @Test
    fun `client commands reject invalid and duplicate names and clean up`() {
        val command = FabricClientCommands.register("test:first", "canary") { }
        assertFailsWith<IllegalArgumentException> { FabricClientCommands.register("test:bad", "Bad Command") { } }
        assertFailsWith<IllegalArgumentException> { FabricClientCommands.register("test:second", "canary") { } }
        assertEquals(mapOf("canary" to "test:first"), FabricClientCommands.registrations())
        command.close()
        command.close()
        assertTrue(FabricClientCommands.registrations().isEmpty())
    }

    @Test
    fun `screen factories reject duplicate ids and clean up by owner handle`() {
        val id = ResourceLocation.of("test:settings")
        val first = FabricScreens.register("test:first", id, "Settings") { error("not opened") }
        assertFailsWith<IllegalArgumentException> {
            FabricScreens.register("test:second", id, "Other") { error("not opened") }
        }
        assertEquals(listOf("test:first"), FabricScreens.registrations().map { it.owner })
        first.close()
        first.close()
        assertTrue(FabricScreens.registrations().isEmpty())
    }

    @Test
    fun `hud registry fans into current targets and removes only its registration`() {
        val registry = FabricHudLayerRegistry()
        val bound = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val before = registry.register("test:before", builder("test:before"))
        val target = FabricHudLayerTarget { registration ->
            val id = registration.builder.identifier.toString()
            bound += id
            AutoCloseable { removed += id }
        }
        val attachment = registry.attach(target)
        val after = registry.register("test:after", builder("test:after"))

        assertEquals(listOf("test:before", "test:after"), bound)
        before.close()
        assertEquals(listOf("test:before"), removed)
        assertEquals(listOf("test:after"), registry.owners())
        attachment.close()
        assertEquals(listOf("test:before", "test:after"), removed)
        after.close()
        assertTrue(registry.owners().isEmpty())
    }

    private fun builder(id: String) = object : HUDBuilder<HUDElement> {
        override val identifier = ResourceLocation.of(id)
        override fun build(guiRenderer: GUIRenderer): HUDElement = error("fake target must not build the HUD layer")
    }
}
