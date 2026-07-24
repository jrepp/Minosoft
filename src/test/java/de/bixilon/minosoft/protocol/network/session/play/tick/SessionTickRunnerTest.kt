/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.protocol.network.session.play.tick

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTickRunnerTest {
    @Test
    fun `cycle is ordered isolates tasks and always reaches end`() {
        val order = mutableListOf<String>()
        val failures = mutableListOf<String?>()
        val runner = SessionTickRunner { failures += it.message }

        runner.run(
            before = { order += "start" },
            tasks = listOf(
                Runnable { order += "entities" },
                Runnable { order += "world"; error("world failure") },
                Runnable { order += "particles" },
            ),
            after = { order += "end" },
        )

        assertEquals(listOf("start", "entities", "world", "particles", "end"), order)
        assertEquals(listOf<String?>("world failure"), failures)
    }

    @Test
    fun `end runs when start completes and task iteration fails unexpectedly`() {
        val order = mutableListOf<String>()
        val runner = SessionTickRunner { throw it }

        try {
            runner.run(
                before = { order += "start" },
                tasks = listOf(Runnable { error("handler failure") }),
                after = { order += "end" },
            )
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf("start", "end"), order)
    }

    @Test
    fun `end runs when start boundary fails`() {
        val order = mutableListOf<String>()
        val runner = SessionTickRunner()

        try {
            runner.run(
                before = { order += "start"; error("start failure") },
                tasks = listOf(Runnable { order += "task" }),
                after = { order += "end" },
            )
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf("start", "end"), order)
    }
}
