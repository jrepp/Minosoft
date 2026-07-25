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

import java.util.PriorityQueue

private val RENDER_IDENTIFIER = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")

data class RenderPassId(
    val value: String,
) : Comparable<RenderPassId> {
    init {
        require(RENDER_IDENTIFIER.matches(value)) { "Invalid render pass identifier: $value" }
    }

    override fun compareTo(other: RenderPassId): Int = value.compareTo(other.value)

    override fun toString(): String = value

}

data class RenderOwnerId(
    val value: String,
) {
    init {
        require(RENDER_IDENTIFIER.matches(value)) { "Invalid render owner identifier: $value" }
    }

    override fun toString(): String = value

}

data class RenderViewId(
    val value: String,
) : Comparable<RenderViewId> {
    init {
        require(RENDER_IDENTIFIER.matches(value)) { "Invalid render view identifier: $value" }
    }

    override fun compareTo(other: RenderViewId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        val MAIN = RenderViewId("minosoft:main")
    }
}

enum class RenderPhase {
    FRAME_SETUP,
    SHADOW,
    SKY,
    WORLD_OPAQUE,
    ENTITIES,
    BLOCK_ENTITIES,
    PARTICLES,
    WEATHER,
    WORLD_TRANSLUCENT,
    WORLD_OVERLAY,
    HUD,
    PRESENTATION,
    COMPOSITE,
    FRAME_FINALIZE,
}

class RenderPass<C>(
    val id: RenderPassId,
    val owner: RenderOwnerId,
    val view: RenderViewId = RenderViewId.MAIN,
    val phase: RenderPhase,
    val semantic: String? = null,
    val order: Int = 0,
    after: Set<RenderPassId> = emptySet(),
    val enabled: (C) -> Boolean = { true },
    val draw: (C) -> Unit,
) {
    val after: Set<RenderPassId> = after.toSet()

    init {
        require(id !in this.after) { "Render pass $id can not depend on itself" }
    }
}

fun interface RenderGraphExecutor<C> {
    fun execute(pass: RenderPass<C>, context: C)

    companion object {
        fun <C> direct(): RenderGraphExecutor<C> = RenderGraphExecutor { pass, context -> pass.draw(context) }
    }
}

class RecordingRenderGraphExecutor<C>(
    private val delegate: RenderGraphExecutor<C>? = null,
) : RenderGraphExecutor<C> {
    private val mutablePasses = mutableListOf<RenderPassId>()
    val passes: List<RenderPassId> get() = mutablePasses.toList()

    override fun execute(pass: RenderPass<C>, context: C) {
        mutablePasses += pass.id
        delegate?.execute(pass, context)
    }
}

class RenderGraphGeneration<C> internal constructor(
    val number: Long,
    passes: List<RenderPass<C>>,
) {
    val passes: List<RenderPass<C>> = passes.toList()

    fun execute(
        context: C,
        executor: RenderGraphExecutor<C> = RenderGraphExecutor.direct(),
    ) {
        for (pass in passes) {
            if (!pass.enabled(context)) continue
            executor.execute(pass, context)
        }
    }

    companion object {
        fun <C> empty(number: Long = 0L): RenderGraphGeneration<C> = RenderGraphGeneration(number, emptyList())
    }
}

class RenderGraphBuilder<C>(
    private val number: Long,
) {
    private val passes = mutableListOf<RenderPass<C>>()

    fun add(pass: RenderPass<C>): RenderGraphBuilder<C> = apply {
        passes += pass
    }

    fun addAll(passes: Iterable<RenderPass<C>>): RenderGraphBuilder<C> = apply {
        this.passes += passes
    }

    fun build(): RenderGraphGeneration<C> {
        val byId = linkedMapOf<RenderPassId, RenderPass<C>>()
        for (pass in passes) {
            require(byId.putIfAbsent(pass.id, pass) == null) { "Duplicate render pass identifier: ${pass.id}" }
        }

        for (pass in passes) {
            for (dependencyId in pass.after) {
                val dependency = requireNotNull(byId[dependencyId]) {
                    "Render pass ${pass.id} depends on unknown pass $dependencyId"
                }
                require(dependency.view == pass.view) {
                    "Render pass ${pass.id} in ${pass.view} can not depend on $dependencyId in ${dependency.view}"
                }
                require(dependency.phase.ordinal <= pass.phase.ordinal) {
                    "Render pass ${pass.id} in ${pass.phase} can not run after later phase ${dependency.phase}"
                }
            }
        }

        val ordered = mutableListOf<RenderPass<C>>()
        val views = passes.map(RenderPass<C>::view).distinct().sorted()
        for (phase in RenderPhase.entries) {
            for (view in views) {
                val viewPasses = passes.filter { it.view == view }
                ordered += orderPhase(viewPasses.filter { it.phase == phase }, byId)
            }
        }
        return RenderGraphGeneration(number, ordered)
    }

    private fun orderPhase(
        phasePasses: List<RenderPass<C>>,
        allPasses: Map<RenderPassId, RenderPass<C>>,
    ): List<RenderPass<C>> {
        if (phasePasses.size < 2) return phasePasses

        val phaseIds = phasePasses.mapTo(hashSetOf(), RenderPass<C>::id)
        val incoming = phasePasses.associate { pass ->
            pass.id to pass.after.count { dependency -> dependency in phaseIds }
        }.toMutableMap()
        val outgoing = hashMapOf<RenderPassId, MutableList<RenderPass<C>>>()
        for (pass in phasePasses) {
            for (dependencyId in pass.after) {
                if (dependencyId !in phaseIds || dependencyId !in allPasses) continue
                outgoing.getOrPut(dependencyId, ::mutableListOf) += pass
            }
        }

        val ready = PriorityQueue(PASS_ORDER<C>())
        phasePasses.filterTo(ready) { incoming.getValue(it.id) == 0 }
        val ordered = ArrayList<RenderPass<C>>(phasePasses.size)
        while (ready.isNotEmpty()) {
            val pass = ready.remove()
            ordered += pass
            for (dependent in outgoing[pass.id].orEmpty()) {
                val remaining = incoming.getValue(dependent.id) - 1
                incoming[dependent.id] = remaining
                if (remaining == 0) ready += dependent
            }
        }

        require(ordered.size == phasePasses.size) {
            val blocked = incoming.filterValues { it > 0 }.keys.sorted().joinToString()
            "Render graph contains a cycle involving: $blocked"
        }
        return ordered
    }

    private companion object {
        fun <C> PASS_ORDER(): Comparator<RenderPass<C>> = compareBy(RenderPass<C>::order, RenderPass<C>::id)
    }
}
