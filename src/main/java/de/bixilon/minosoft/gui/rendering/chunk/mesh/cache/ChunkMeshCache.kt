/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.chunk.mesh.cache

import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelTarget
import de.bixilon.minosoft.data.entities.block.BlockEntity
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.positions.InSectionPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.chunk.entities.BlockEntityRenderer
import de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.skeletal.GeckoLibBlockEntityRenderer
import de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoader

class ChunkMeshCache(
    val context: RenderContext,
    private val contentModels: SkeletalLoader?,
) {
    private var entities: BlockEntityCacheState? = null

    val isEmpty get() = entities == null

    fun createEntity(position: InSectionPosition, entity: BlockEntity): BlockEntityRenderer? {
        var renderer: BlockEntityRenderer? = null
        var entities = this.entities
        if (entities == null) {
            // no cache yet
            renderer = createRenderer(entity) ?: return null
            entities = BlockEntityCacheState()
            this.entities = entities
            entities.store(position, renderer)
        }
        renderer = renderer ?: entities.entities[position.index]

        if (renderer == null) {
            // cached version is empty (or already created when cache was empty
            renderer = createRenderer(entity) ?: return null
            entities.store(position, renderer)
        } else {
            if (renderer.entity !== entity || !matchesContentRoute(renderer, entity)) {
                val unload = renderer
                context.queue += { unload.unload() }
                entities.store(position, null)

                renderer = createRenderer(entity) ?: return null
                entities.store(position, renderer)
            }

            entities.usage[position.index] = true
        }

        return renderer
    }

    private fun createRenderer(entity: BlockEntity): BlockEntityRenderer? {
        val model = contentModel(entity)
        return model?.let { GeckoLibBlockEntityRenderer(entity, context, it) }
            ?: entity.createRenderer(context)
    }

    private fun contentModel(entity: BlockEntity): de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel? {
        val models = contentModels ?: return null
        return models.contentModel(
            GeckoLibModelTarget.BLOCK_ENTITY,
            entity.state.block.identifier,
        )?.let(models::get)
    }

    private fun matchesContentRoute(renderer: BlockEntityRenderer, entity: BlockEntity): Boolean {
        val model = contentModel(entity)
        return if (renderer is GeckoLibBlockEntityRenderer) {
            renderer.contentModel === model
        } else {
            model == null
        }
    }

    fun unmark() {
        entities?.usage?.clear()
    }

    fun cleanup() {
        val entities = entities ?: return

        val remove = ArrayList<BlockEntityRenderer>(minOf(16, entities.count))

        for (index in 0 until ChunkSize.BLOCKS_PER_SECTION) {
            val entity = entities.entities[index] ?: continue
            if (entities.usage[index]) continue

            entities.entities[index] = null
            remove += entity

            if (--entities.count == 0) break
        }
        if (entities.count == 0) {
            this.entities = null
        }

        context.queue += { remove.forEach { it.unload() } }
    }

    fun unload() {
        entities?.unload()
        entities = null
    }

    fun drop() {
        entities?.drop()
        entities = null
    }
}
