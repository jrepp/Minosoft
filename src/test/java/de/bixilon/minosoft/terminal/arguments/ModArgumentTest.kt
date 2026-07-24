/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.terminal.arguments

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModArgumentTest {
    private class TestCommand : CliktCommand() {
        val mods by ModArgument()
        override fun run() = Unit
    }

    private fun parse(vararg args: String): ModArgument {
        val command = TestCommand()
        command.parse(args.toList())
        return command.mods
    }

    @Test
    fun `accepts bounded trajectory metadata`() {
        val argument = parse("--mod-trajectory", "acceptance", "--hot-reload-generation", "2")

        argument.apply()

        assertEquals("acceptance", argument.trajectory)
        assertEquals(2, argument.hotReloadGeneration)
    }

    @Test
    fun `rejects invalid trajectory metadata`() {
        assertFailsWith<IllegalArgumentException> {
            parse("--mod-trajectory", "").apply()
        }
        assertFailsWith<IllegalArgumentException> {
            parse("--hot-reload-generation", "0").apply()
        }
    }
}
