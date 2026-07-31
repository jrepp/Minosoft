/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertNotEquals

class TerrainRuntimeScopesTest {
    @Test
    fun `runtime identities reject negative generations`() {
        assertThrows<IllegalArgumentException> { TerrainProcessScopeId(-1L) }
        assertThrows<IllegalArgumentException> {
            TerrainDeviceRuntimeId(TerrainProcessScopeId(0L), -1L)
        }
        assertThrows<IllegalArgumentException> {
            TerrainWorldRuntimeId(TerrainProcessScopeId(0L), -1L, 0L)
        }
        assertThrows<IllegalArgumentException> {
            TerrainWorldRuntimeId(TerrainProcessScopeId(0L), 0L, -1L)
        }
    }

    @Test
    fun `device and world generations identify replacement scopes`() {
        val process = TerrainProcessScopeId(2L)

        assertNotEquals(
            TerrainDeviceRuntimeId(process, 3L),
            TerrainDeviceRuntimeId(process, 4L),
        )
        assertNotEquals(
            TerrainWorldRuntimeId(process, 5L, 6L),
            TerrainWorldRuntimeId(process, 5L, 7L),
        )
    }
}
