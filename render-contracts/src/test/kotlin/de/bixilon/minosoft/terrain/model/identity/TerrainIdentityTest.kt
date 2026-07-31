/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.model.identity

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerrainIdentityTest {
    @Test
    fun `world identity rejects invalid durable fields and ranges`() {
        assertThrows<IllegalArgumentException> { world(normalizedWorldKey = " ") }
        assertThrows<IllegalArgumentException> { world(minimumHeight = 64, maximumHeightExclusive = 64) }
        assertThrows<IllegalArgumentException> { world(persistenceIdentity = "") }
    }

    @Test
    fun `page offsets use checked absolute coordinate arithmetic`() {
        val page = page(x = Long.MAX_VALUE)

        assertThrows<ArithmeticException> { page.offset(1L, 0L, 0L) }
        assertEquals(page(x = -2L, y = 4L, z = 8L), page(x = -3L, y = 2L, z = 5L).offset(1L, 2L, 3L))
    }

    @Test
    fun `build comparison checks world before page and ignores scheduling priority`() {
        val expected = identity()

        assertEquals(
            TerrainBuildIdentityMismatch.WORLD_EPOCH,
            expected.copy(page = expected.page.copy(worldEpoch = 4L)).mismatch(expected),
        )
        assertEquals(
            TerrainBuildIdentityMismatch.PAGE,
            expected.copy(page = expected.page.copy(x = 4L)).mismatch(expected),
        )
        assertEquals(
            TerrainBuildIdentityMismatch.MATERIAL_GENERATION,
            expected.copy(materialGeneration = 8L).mismatch(expected),
        )
        assertNull(expected.copy(prioritySequence = 99L).mismatch(expected))
    }

    private fun world(
        normalizedWorldKey: String = "minecraft:overworld",
        minimumHeight: Int = -64,
        maximumHeightExclusive: Int = 320,
        persistenceIdentity: String? = "server/world",
    ) = TerrainWorldIdentity(
        sessionGeneration = 1L,
        normalizedWorldKey = normalizedWorldKey,
        worldEpoch = 2L,
        minimumHeight = minimumHeight,
        maximumHeightExclusive = maximumHeightExclusive,
        contentGeneration = 3L,
        persistenceIdentity = persistenceIdentity,
    )

    private fun page(
        x: Long = -3L,
        y: Long = 2L,
        z: Long = 5L,
    ) = TerrainPageKey(TerrainDomain.NEAR, 0, x, y, z, 3L)

    private fun identity() = TerrainBuildIdentity(
        page = page(),
        requestRevision = 1L,
        capturedModelRevision = 2L,
        providerGeneration = 3L,
        layoutGeneration = 4L,
        materialGeneration = 5L,
        coverageGeneration = 6L,
        prioritySequence = 7L,
        sourceDataRevision = 8L,
    )
}
