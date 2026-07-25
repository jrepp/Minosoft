/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.texture.entity

import de.bixilon.minosoft.data.world.difficulty.Difficulties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntityTextureRegionalDifficultyTest {

    @Test
    fun `regional difficulty follows vanilla 1_20_4 boundaries`() {
        assertEquals(0.0, EntityTextureRegionalDifficulty.calculate(Difficulties.PEACEFUL, 10_000_000L, 10_000_000L, 1.0f))
        assertEquals(1.5, EntityTextureRegionalDifficulty.calculate(Difficulties.NORMAL, 0L, 0L, 1.0f))
        assertEquals(3.75, EntityTextureRegionalDifficulty.calculate(Difficulties.HARD, 1_536_000L, 0L, 1.0f))
        assertEquals(6.75, EntityTextureRegionalDifficulty.calculate(Difficulties.HARD, 1_536_000L, 3_600_000L, 1.0f))
        assertFailsWith<IllegalArgumentException> {
            EntityTextureRegionalDifficulty.calculate(Difficulties.NORMAL, 0L, 0L, Float.NaN)
        }
    }
}
