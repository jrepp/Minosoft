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

package de.bixilon.minosoft.assets.source

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalAssetSourceTest {

    @Test
    fun `available local content is returned unchanged`() {
        val content = byteArrayOf(1, 2, 3)

        assertEquals(content, LocalAssetSource.require("asset", "abc", content))
    }

    @Test
    fun `missing content fails instead of requesting an official source`() {
        val error = assertFailsWith<LocalAssetUnavailableException> {
            LocalAssetSource.require<ByteArray>("asset", "abc", null)
        }

        assertTrue(error.message!!.contains("local asset pack or mod"))
        assertTrue(error.message!!.contains("network retrieval"))
    }
}
