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

import java.util.concurrent.atomic.AtomicBoolean

class RenderGraphRegistry<C> {
    private data class Contribution<C>(
        val token: Long,
        val passes: List<RenderPass<C>>,
    )

    private val lock = Any()
    private val contributions = linkedMapOf<RenderOwnerId, Contribution<C>>()
    private var nextToken = 1L

    @Volatile
    var generation: RenderGraphGeneration<C> = RenderGraphGeneration.empty()
        private set

    fun install(
        owner: RenderOwnerId,
        passes: List<RenderPass<C>>,
    ): AutoCloseable {
        val immutablePasses = passes.toList()
        require(immutablePasses.all { it.owner == owner }) {
            "Every render pass installed by $owner must name that owner"
        }

        val token: Long
        synchronized(lock) {
            require(owner !in contributions) { "Render owner is already installed: $owner" }
            token = nextToken++
            val candidateContributions = LinkedHashMap(contributions)
            candidateContributions[owner] = Contribution(token, immutablePasses)
            val candidate = buildCandidate(candidateContributions, generation.number + 1L)
            contributions[owner] = Contribution(token, immutablePasses)
            generation = candidate
        }
        return Registration(owner, token)
    }

    fun replace(
        owner: RenderOwnerId,
        passes: List<RenderPass<C>>,
    ): AutoCloseable {
        val immutablePasses = passes.toList()
        require(immutablePasses.all { it.owner == owner }) {
            "Every render pass installed by $owner must name that owner"
        }

        val token: Long
        synchronized(lock) {
            token = nextToken++
            val candidateContributions = LinkedHashMap(contributions)
            candidateContributions[owner] = Contribution(token, immutablePasses)
            val candidate = buildCandidate(candidateContributions, generation.number + 1L)
            contributions[owner] = Contribution(token, immutablePasses)
            generation = candidate
        }
        return Registration(owner, token)
    }

    fun owners(): Set<RenderOwnerId> = synchronized(lock) { contributions.keys.toSet() }

    private fun buildCandidate(
        contributions: Map<RenderOwnerId, Contribution<C>>,
        number: Long,
    ): RenderGraphGeneration<C> {
        val builder = RenderGraphBuilder<C>(number)
        contributions.values.forEach { builder.addAll(it.passes) }
        return builder.build()
    }

    private fun remove(owner: RenderOwnerId, token: Long) {
        synchronized(lock) {
            val contribution = contributions[owner] ?: return
            if (contribution.token != token) return

            val candidateContributions = LinkedHashMap(contributions)
            candidateContributions.remove(owner)
            val candidate = buildCandidate(candidateContributions, generation.number + 1L)
            contributions.remove(owner)
            generation = candidate
        }
    }

    private inner class Registration(
        private val owner: RenderOwnerId,
        private val token: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            remove(owner, token)
        }
    }
}
