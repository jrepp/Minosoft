/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.chunk.queue.meshing.tasks

import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.kutil.concurrent.lock.locks.reentrant.ReentrantRWLock
import de.bixilon.kutil.concurrent.pool.DefaultThreadPool
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.SectionPosition
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import java.util.concurrent.atomic.AtomicInteger

class MesherTaskManager(
    val renderer: ChunkRenderer,
    val max: Int = maxOf(minOf(Runtime.getRuntime().availableProcessors() - 1, DefaultThreadPool.threadCount - 1), 1)
) {
    private val tasks: MutableSet<MeshPrepareTask> = HashSet(max)
    private val lock = ReentrantRWLock()
    private val taskCount = AtomicInteger()

    val size get() = taskCount.get()

    operator fun plusAssign(task: MeshPrepareTask) = lock.locked {
        if (tasks.add(task)) taskCount.incrementAndGet()
    }
    operator fun minusAssign(task: MeshPrepareTask) = lock.locked {
        if (tasks.remove(task)) taskCount.decrementAndGet()
    }

    fun interrupt(requeue: Boolean) = lock.acquired {
        for (task in tasks) {
            task.cancel()
            if (requeue) {
                renderer.invalidate(task.section, de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause.TASK_RETRY)
            }
        }
    }

    fun interruptIf(requeue: Boolean, predicate: (SectionPosition) -> Boolean) = lock.acquired {
        for (task in tasks) {
            if (!predicate.invoke(task.position)) continue

            task.cancel()
            if (requeue) {
                renderer.invalidate(task.section, de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildCause.TASK_RETRY)
            }
        }
    }

    fun interrupt(position: ChunkPosition) = interruptIf(false) { it.chunkPosition == position }
    fun interrupt(position: SectionPosition) = interruptIf(false) { it == position }
}
