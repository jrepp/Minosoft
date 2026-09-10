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

package de.bixilon.minosoft.debug.content

import de.bixilon.kutil.enums.AliasableEnum
import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperty
import de.bixilon.minosoft.data.registries.blocks.properties.Halves
import de.bixilon.minosoft.data.registries.blocks.properties.MultipartDirections
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.types.Block
import de.bixilon.minosoft.data.registries.blocks.types.properties.size.DoubleSizeBlock
import de.bixilon.minosoft.data.world.positions.BlockPosition
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/**
 * Produces stable, bounded pages containing every legal registry state of one block.
 * Connected states use two-block spacing so their authored properties, rather than
 * incidental neighbours, determine the rendered model. Vertical double-size blocks
 * use three-block row spacing and stage the matching other half as visual context.
 */
object BlockStateSculptureCatalog {
    const val MIN_PAGE_SIZE = 1
    const val MAX_PAGE_SIZE = 64
    const val DEFAULT_PAGE_SIZE = MAX_PAGE_SIZE
    const val SPACING = 2
    const val DOUBLE_SIZE_SPACING = 3

    data class Context(
        val position: BlockPosition,
        val state: BlockState,
    )

    data class Entry(
        val catalogIndex: Int,
        val key: String,
        val state: BlockState,
        val position: BlockPosition,
        val context: List<Context>,
    )

    data class Page(
        val block: Block,
        val pageIndex: Int,
        val pageSize: Int,
        val totalStates: Int,
        val totalPages: Int,
        val columns: Int,
        val rows: Int,
        val spacing: Int,
        val fingerprint: String,
        val slots: List<BlockPosition>,
        val entries: List<Entry>,
    )

    fun page(block: Block, pageIndex: Int, pageSize: Int = DEFAULT_PAGE_SIZE, y: Int = 20): Page {
        require(pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
            "pageSize must be within $MIN_PAGE_SIZE..$MAX_PAGE_SIZE"
        }
        val states = block.states.sortedWith(compareBy<BlockState>(::environmentOrder).thenBy(::canonicalKey))
        check(states.isNotEmpty()) { "${block.identifier} has no registry states" }
        val totalPages = Math.addExact(states.size, pageSize - 1) / pageSize
        require(pageIndex in 0 until totalPages) {
            "page must be within 0..${totalPages - 1} for ${block.identifier}"
        }

        val slotCount = minOf(pageSize, states.size)
        val columns = ceilSquareRoot(slotCount)
        val rows = Math.addExact(slotCount, columns - 1) / columns
        val spacing = if (block is DoubleSizeBlock) DOUBLE_SIZE_SPACING else SPACING
        val startX = -(columns - 1)
        val anchors = List(slotCount) { index ->
            val column = index % columns
            val row = index / columns
            BlockPosition(startX + column * SPACING, y + row * spacing, 0)
        }
        val from = Math.multiplyExact(pageIndex, pageSize)
        val to = minOf(Math.addExact(from, pageSize), states.size)
        val entries = states.subList(from, to).mapIndexed { offset, state ->
            val catalogIndex = Math.addExact(from, offset)
            val position = anchors[offset]
            Entry(catalogIndex, canonicalKey(state), state, position, context(block, state, position))
        }
        // Include unused cells on the last page and both possible context halves,
        // so switching pages cannot retain geometry from an earlier state.
        val slots = anchors.flatMap { position ->
            if (block is DoubleSizeBlock) {
                listOf(position.with(y = position.y - 1), position, position.with(y = position.y + 1))
            } else {
                listOf(position)
            }
        }
        return Page(
            block = block,
            pageIndex = pageIndex,
            pageSize = pageSize,
            totalStates = states.size,
            totalPages = totalPages,
            columns = columns,
            rows = rows,
            spacing = spacing,
            fingerprint = fingerprint(block, states),
            slots = slots,
            entries = entries,
        )
    }

    private fun context(block: Block, state: BlockState, position: BlockPosition): List<Context> {
        if (block !is DoubleSizeBlock) return emptyList()
        val half = state[DoubleSizeBlock.HALF]
        val contextHalf = if (half == Halves.UPPER) Halves.LOWER else Halves.UPPER
        val contextPosition = position.with(y = position.y + if (half == Halves.UPPER) -1 else 1)
        return listOf(Context(contextPosition, state.withProperties(DoubleSizeBlock.HALF to contextHalf)))
    }

    fun canonicalKey(state: BlockState): String = buildString {
        append(state.block.identifier)
        if (state.properties.isNotEmpty()) {
            append(state.properties.entries.sortedBy { it.key.name }.joinToString(",", "[", "]") { (property, value) ->
                "${property.name}=${canonicalValue(state, property, value)}"
            })
        }
    }

    fun canonicalValue(state: BlockState, property: BlockProperty<*>, value: Any): String {
        if (value is MultipartDirections) {
            val booleanDomain = state.block.states.all { candidate ->
                when (candidate.properties[property]) {
                    MultipartDirections.NONE, MultipartDirections.SIDE -> true
                    else -> false
                }
            }
            if (booleanDomain) return if (value == MultipartDirections.SIDE) "true" else "false"
        }
        if (value is Halves) {
            val path = state.block.identifier.path
            val usesTopBottom = property.name == "type" || path.endsWith("_stairs") || path.endsWith("_trapdoor")
            if (!usesTopBottom) return value.toString().lowercase(Locale.ROOT)
        }
        if (value is AliasableEnum && value.names.isNotEmpty()) return value.names.first()
        return value.toString().lowercase(Locale.ROOT)
    }

    private fun environmentOrder(state: BlockState): Int {
        val waterlogged = state.properties.entries.firstOrNull { it.key.name == "waterlogged" }?.value
        return if (waterlogged == true) 1 else 0
    }

    private fun fingerprint(block: Block, states: List<BlockState>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(block.identifier.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        for (state in states) {
            digest.update(canonicalKey(state).toByteArray(StandardCharsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun ceilSquareRoot(value: Int): Int {
        var root = 1
        while (root < value / root || root * root < value) root++
        return root
    }
}
