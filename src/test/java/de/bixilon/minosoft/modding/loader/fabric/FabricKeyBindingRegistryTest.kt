/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.config.key.KeyActions
import de.bixilon.minosoft.config.key.KeyBinding
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FabricKeyBindingRegistryTest {
    @Test
    fun `definitions fan into current and future targets and close independently`() {
        val registry = FabricKeyBindingRegistry()
        val first = RecordingTarget()
        val second = RecordingTarget()
        val name = ResourceLocation("test", "map")
        var pressed = false

        val firstAttachment = registry.attach(first)
        val registration = registry.register("test:mod", name, binding(), false) { pressed = it }
        assertEquals(listOf(name), first.activeNames())
        first.active.single().callback(true)
        assertTrue(pressed)

        val secondAttachment = registry.attach(second)
        assertEquals(listOf(name), second.activeNames())
        firstAttachment.close()
        assertTrue(first.handles.single().closed)
        assertFalse(second.handles.single().closed)

        registration.close()
        assertTrue(second.handles.single().closed)
        assertTrue(registry.owners().isEmpty())
        secondAttachment.close()
    }

    @Test
    fun `duplicate names are rejected until prior owner closes`() {
        val registry = FabricKeyBindingRegistry()
        val name = ResourceLocation("test", "map")
        val first = registry.register("test:first", name, binding(), false) { }

        assertFailsWith<IllegalArgumentException> {
            registry.register("test:second", name, binding(), false) { }
        }
        first.close()
        registry.register("test:second", name, binding(), false) { }.close()
    }

    private fun binding() = KeyBinding(KeyActions.PRESS to setOf(KeyCodes.KEY_M))

    private class RecordingTarget : FabricKeyBindingTarget {
        val active = mutableListOf<FabricKeyBindingRegistration>()
        val handles = mutableListOf<RecordingHandle>()

        override fun bind(registration: FabricKeyBindingRegistration): AutoCloseable {
            active += registration
            return RecordingHandle { active.remove(registration) }.also(handles::add)
        }

        fun activeNames() = active.map(FabricKeyBindingRegistration::name)
    }

    private class RecordingHandle(private val cleanup: () -> Unit) : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            if (closed) return
            closed = true
            cleanup()
        }
    }
}
