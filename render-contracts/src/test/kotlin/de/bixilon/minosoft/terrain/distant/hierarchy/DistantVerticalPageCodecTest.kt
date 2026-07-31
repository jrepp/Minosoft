/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DistantVerticalPageCodecTest {
    @Test
    fun `page codec round trips every semantic field canonically`() {
        val page = DistantVerticalPage(
            TerrainPageKey(TerrainDomain.DISTANT, 0, -19, 0, 27, 44),
            1,
            -64,
            91,
            DistantSourceCompleteness.PARTIAL,
            listOf(
                DistantVerticalColumn(
                    listOf(
                        DistantColumnRun(
                            8,
                            4,
                            TerrainSemanticMaterialId("mod:waterlogged_lamp"),
                            DistantFluidSample(TerrainSemanticMaterialId("mod:brine"), 6, "mod:brine"),
                            13,
                            7,
                            DistantTintSample(0x123456, "mod:violet_marsh", 18),
                            setOf(DistantRunFlag.EMISSIVE, DistantRunFlag.GENERATED),
                            72,
                        ),
                        DistantColumnRun(0, 3, null, null, 0, 0, null, setOf(DistantRunFlag.CAVE), 100),
                    ),
                ),
            ),
        )

        val encoded = DistantVerticalPageCodec.encode(page)
        val decoded = DistantVerticalPageCodec.decode(encoded)

        assertEquals(page.key, decoded.key)
        assertEquals(page.sourceRevision, decoded.sourceRevision)
        assertEquals(page.completeness, decoded.completeness)
        assertEquals(page.semanticDigest, decoded.semanticDigest)
        assertContentEquals(encoded, DistantVerticalPageCodec.encode(decoded))
        assertEquals(fixture("distant-vertical-page-v2.dump"), DistantVerticalPageCodec.dump(page).trimEnd())
        assertEquals(fixture("distant-vertical-page-v2.hex"), encoded.toHexString())
    }

    @Test
    fun `page codec rejects old schemas truncation and trailing bytes`() {
        val page = DistantVerticalSampler.capture(
            TerrainPageKey(TerrainDomain.DISTANT, 0, 0, 0, 0, 1),
            1, 0, 2, 1, DistantSourceCompleteness.COMPLETE,
        ) { _, y, _ -> if (y == 0) DistantVoxelSample(TerrainSemanticMaterialId("minecraft:stone")) else DistantVoxelSample(null) }
        val encoded = DistantVerticalPageCodec.encode(page)

        assertThrows<Exception> { DistantVerticalPageCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows<IllegalArgumentException> { DistantVerticalPageCodec.decode(encoded + 0) }
        val old = encoded.copyOf().also { it[5] = 1 }
        assertThrows<IllegalArgumentException> { DistantVerticalPageCodec.decode(old) }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource(name)).readText().trimEnd()
}
