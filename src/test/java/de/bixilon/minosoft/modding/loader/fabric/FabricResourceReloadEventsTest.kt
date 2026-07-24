/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FabricResourceReloadEventsTest {
    @Test
    fun `successful transaction prepares before apply and completes`() {
        val order = mutableListOf<String>()

        val result = FabricReloadTransaction.run(
            prepare = { order += "work:prepare"; "candidate" },
            apply = { order += "work:apply:$it" },
            phase = { phase, _ -> order += "phase:${phase.wireName}" },
        )

        assertEquals("candidate", result)
        assertEquals(
            listOf("phase:prepare", "work:prepare", "phase:apply", "work:apply:candidate", "phase:complete"),
            order,
        )
    }

    @Test
    fun `prepare failure never applies and emits failed with cause`() {
        val phases = mutableListOf<Pair<FabricResourceReloadPhase, String?>>()

        val error = assertFailsWith<IllegalStateException> {
            FabricReloadTransaction.run(
                prepare = { error("candidate failed") },
                apply = { error("must not apply") },
                phase = { phase, failure -> phases += phase to failure?.message },
            )
        }

        assertEquals("candidate failed", error.message)
        assertEquals(
            listOf(
                FabricResourceReloadPhase.PREPARE to null,
                FabricResourceReloadPhase.FAILED to "candidate failed",
            ),
            phases,
        )
    }

    @Test
    fun `apply failure emits failed after apply phase`() {
        val phases = mutableListOf<FabricResourceReloadPhase>()

        assertFailsWith<IllegalArgumentException> {
            FabricReloadTransaction.run(
                prepare = { "candidate" },
                apply = { throw IllegalArgumentException(it) },
                phase = { phase, _ -> phases += phase },
            )
        }

        assertEquals(
            listOf(FabricResourceReloadPhase.PREPARE, FabricResourceReloadPhase.APPLY, FabricResourceReloadPhase.FAILED),
            phases,
        )
    }
}
