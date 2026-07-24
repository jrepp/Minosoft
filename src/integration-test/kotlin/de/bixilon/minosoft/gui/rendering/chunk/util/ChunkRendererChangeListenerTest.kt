/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.gui.rendering.chunk.util

import de.bixilon.minosoft.data.registries.blocks.state.TestBlockStates
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@Test(groups = ["chunk_rendering"])
class ChunkRendererChangeListenerTest {

    fun `entity block placement and removal require a remesh`() {
        assertFalse(ChunkRendererChangeListener.canIgnore(TestBlockStates.ENTITY1, null))
        assertFalse(ChunkRendererChangeListener.canIgnore(null, TestBlockStates.ENTITY1))
    }

    fun `entity block state replacement requires a remesh`() {
        assertFalse(ChunkRendererChangeListener.canIgnore(TestBlockStates.ENTITY2, TestBlockStates.ENTITY1))
    }

    fun `unchanged entity block can reuse its mesh`() {
        assertTrue(ChunkRendererChangeListener.canIgnore(TestBlockStates.ENTITY1, TestBlockStates.ENTITY1))
    }
}
