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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

class CreativeCatalogPager<T>(
    private val items: List<T>,
    val pageSize: Int,
    private val searchableText: (T) -> String = { it.toString() },
) {
    init {
        require(pageSize > 0) { "Page size must be positive!" }
    }

    private var filteredItems: List<T> = items
    private var queryTerms: List<String> = emptyList()
    private var scrollAccumulator = 0.0f

    var page: Int = 0
        private set

    val resultCount: Int get() = filteredItems.size
    val pageCount: Int get() = if (filteredItems.isEmpty()) 1 else 1 + (filteredItems.size - 1) / pageSize

    val visible: List<T>
        get() {
            val start = page * pageSize
            return filteredItems.subList(start, minOf(start + pageSize, filteredItems.size))
        }

    fun search(query: String): Boolean {
        val terms = query.trim().lowercase(Locale.ROOT).split(WHITESPACE).filter(String::isNotEmpty)
        if (terms == queryTerms) return false

        queryTerms = terms
        filteredItems = if (terms.isEmpty()) {
            items
        } else {
            items.filter { item ->
                val text = searchableText(item).lowercase(Locale.ROOT)
                terms.all(text::contains)
            }
        }
        page = 0
        scrollAccumulator = 0.0f
        return true
    }

    fun scroll(vertical: Float): Boolean {
        if (vertical == 0.0f || !vertical.isFinite()) return false

        val direction = vertical.sign
        val nextPage = page + if (direction < 0.0f) 1 else -1
        if (nextPage !in 0 until pageCount) {
            scrollAccumulator = 0.0f
            return false
        }
        if (scrollAccumulator != 0.0f && scrollAccumulator.sign != direction) {
            scrollAccumulator = 0.0f
        }
        scrollAccumulator += vertical
        if (abs(scrollAccumulator) < SCROLL_THRESHOLD) return false

        scrollAccumulator -= direction * SCROLL_THRESHOLD
        val next = nextPage.coerceIn(0, pageCount - 1)
        page = next
        return true
    }

    companion object {
        private const val SCROLL_THRESHOLD = 2.0f
        private val WHITESPACE = Regex("\\s+")
    }
}
