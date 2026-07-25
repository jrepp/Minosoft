/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.graph

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderGraphTest {
    private val owner = RenderOwnerId("minosoft:test")

    @Test
    fun `orders phases then dependencies then stable order`() {
        val first = pass("first", RenderPhase.WORLD_OPAQUE, order = 10)
        val second = pass("second", RenderPhase.WORLD_OPAQUE, order = -10, after = setOf(first.id))
        val sky = pass("sky", RenderPhase.SKY)
        val translucent = pass("translucent", RenderPhase.WORLD_TRANSLUCENT)

        val generation = RenderGraphBuilder<Unit>(1L)
            .add(second)
            .add(translucent)
            .add(first)
            .add(sky)
            .build()

        assertEquals(
            generation.passes.map { it.id.value },
            listOf("minosoft:sky", "minosoft:first", "minosoft:second", "minosoft:translucent"),
        )
    }

    @Test
    fun `rejects duplicate identifiers`() {
        val builder = RenderGraphBuilder<Unit>(1L)
            .add(pass("duplicate", RenderPhase.SKY))
            .add(pass("duplicate", RenderPhase.WORLD_OPAQUE))

        assertThrows<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `rejects cycles`() {
        val firstId = RenderPassId("minosoft:first")
        val secondId = RenderPassId("minosoft:second")
        val builder = RenderGraphBuilder<Unit>(1L)
            .add(pass(firstId, RenderPhase.WORLD_OPAQUE, after = setOf(secondId)))
            .add(pass(secondId, RenderPhase.WORLD_OPAQUE, after = setOf(firstId)))

        assertThrows<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `rejects unknown dependencies`() {
        val builder = RenderGraphBuilder<Unit>(1L)
            .add(
                pass(
                    "dependent",
                    RenderPhase.WORLD_OPAQUE,
                    after = setOf(RenderPassId("minosoft:missing")),
                ),
            )

        assertThrows<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `rejects dependency on a later phase`() {
        val later = pass("later", RenderPhase.COMPOSITE)
        val builder = RenderGraphBuilder<Unit>(1L)
            .add(later)
            .add(pass("earlier", RenderPhase.SKY, after = setOf(later.id)))

        assertThrows<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `recording executor is headless and honors enabled predicate`() {
        var drawn = false
        val generation = RenderGraphBuilder<Unit>(1L)
            .add(pass("disabled", RenderPhase.SKY, enabled = { false }) { drawn = true })
            .add(pass("recorded", RenderPhase.WORLD_OPAQUE) { drawn = true })
            .build()
        val recording = RecordingRenderGraphExecutor<Unit>()

        generation.execute(Unit, recording)

        assertFalse(drawn)
        assertEquals(recording.passes.map(RenderPassId::value), listOf("minosoft:recorded"))
    }

    @Test
    fun `phase ordering spans all views`() {
        val shadow = RenderViewId("minosoft:shadow")
        val generation = RenderGraphBuilder<Unit>(1L)
            .add(pass("main-composite", RenderPhase.COMPOSITE))
            .add(
                RenderPass(
                    id = RenderPassId("minosoft:shadow-pass"),
                    owner = owner,
                    view = shadow,
                    phase = RenderPhase.SHADOW,
                    draw = {},
                ),
            )
            .build()

        assertEquals(
            listOf("minosoft:shadow-pass", "minosoft:main-composite"),
            generation.passes.map { it.id.value },
        )
    }

    @Test
    fun `registry preserves generation when candidate is invalid`() {
        val registry = RenderGraphRegistry<Unit>()
        val first = registry.install(owner, listOf(pass("active", RenderPhase.SKY)))
        val accepted = registry.generation

        assertThrows<IllegalArgumentException> {
            registry.install(
                RenderOwnerId("minosoft:invalid"),
                listOf(
                    RenderPass(
                        id = RenderPassId("minosoft:active"),
                        owner = RenderOwnerId("minosoft:invalid"),
                        phase = RenderPhase.SKY,
                        draw = {},
                    ),
                ),
            )
        }

        assertTrue(owner in registry.owners())
        assertEquals(registry.generation.number, accepted.number)
        assertEquals(registry.generation.passes.map { it.id.value }, listOf("minosoft:active"))
        first.close()
    }

    @Test
    fun `owner close is idempotent and removes only the matching generation`() {
        val registry = RenderGraphRegistry<Unit>()
        val old = registry.install(owner, listOf(pass("old", RenderPhase.SKY)))
        val replacement = registry.replace(owner, listOf(pass("new", RenderPhase.SKY)))

        old.close()
        assertEquals(registry.generation.passes.map { it.id.value }, listOf("minosoft:new"))

        replacement.close()
        replacement.close()
        assertTrue(registry.generation.passes.isEmpty())
        assertTrue(registry.owners().isEmpty())
    }

    private fun pass(
        name: String,
        phase: RenderPhase,
        order: Int = 0,
        after: Set<RenderPassId> = emptySet(),
        enabled: (Unit) -> Boolean = { true },
        draw: (Unit) -> Unit = {},
    ): RenderPass<Unit> = pass(RenderPassId("minosoft:$name"), phase, order, after, enabled, draw)

    private fun pass(
        id: RenderPassId,
        phase: RenderPhase,
        order: Int = 0,
        after: Set<RenderPassId> = emptySet(),
        enabled: (Unit) -> Boolean = { true },
        draw: (Unit) -> Unit = {},
    ) = RenderPass(
        id = id,
        owner = owner,
        phase = phase,
        order = order,
        after = after,
        enabled = enabled,
        draw = draw,
    )
}
