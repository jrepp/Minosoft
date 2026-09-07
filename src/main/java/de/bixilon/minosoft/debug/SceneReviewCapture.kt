/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.debug

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.data.entities.entities.item.ItemEntity
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.ContentModelInspectable
import de.bixilon.minosoft.gui.rendering.entities.renderer.living.player.PlayerRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import de.bixilon.minosoft.gui.rendering.models.block.state.baked.BakedModel
import de.bixilon.minosoft.gui.rendering.models.block.state.builder.BuiltModel
import de.bixilon.minosoft.gui.rendering.models.block.state.render.BlockRender
import de.bixilon.minosoft.gui.rendering.models.block.state.render.NeighbourBlockRender
import de.bixilon.minosoft.gui.rendering.models.block.state.render.WeightedBlockRender
import de.bixilon.minosoft.gui.rendering.models.item.FlatItemRender
import de.bixilon.minosoft.gui.rendering.models.item.ItemRender
import de.bixilon.minosoft.gui.rendering.models.item.resolve
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.file.FileTextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.modding.loader.ModOptions
import kotlin.math.floor

/**
 * A bounded, render-thread snapshot for reviewing content in the accompanying
 * final-framebuffer capture. Block visibility is deliberately described as a
 * candidate set: exact pixel ownership requires a material-id render target.
 */
internal object SceneReviewCapture {
    const val DEFAULT_HORIZONTAL_RADIUS = 24
    const val DEFAULT_VERTICAL_RADIUS = 16
    private const val MAX_BLOCK_CANDIDATES = 32_768
    private const val MAX_BLOCK_STATES = 512
    private const val MAX_ENTITY_RESULTS = 128
    private const val MAX_POSITION_SAMPLES = 4
    private const val MAX_MODEL_DEPTH = 8

    fun capture(context: RenderContext): ObjectNode {
        val session = context.session
        val player = session.player
        val cameraPosition = session.camera.entity.renderInfo.eyePosition
        val origin = BlockPosition(
            floor(cameraPosition.x).toInt(),
            floor(cameraPosition.y).toInt(),
            floor(cameraPosition.z).toInt(),
        )
        val textures = linkedMapOf<String, TextureUse>()

        return DebugJson.MAPPER.createObjectNode().apply {
            put("schema", "minosoft.scene-review/v1")
            put("frame", context.frameNumber)
            putObject("visibilitySemantics").apply {
                put("blocks", "loaded non-air blocks in frustum-intersecting sections inside the bounded camera region; candidates may be occluded")
                put("entities", "entities whose renderer reported visible for this frame")
                put("items", "main/off-hand items plus visible item and item-display entities")
                put("textures", "resolved model texture candidates for reported blocks and items; individual faces may be occluded")
            }
            putObject("camera").apply {
                put("x", cameraPosition.x); put("y", cameraPosition.y); put("z", cameraPosition.z)
                put("yaw", session.camera.entity.physics.rotation.yaw)
                put("pitch", session.camera.entity.physics.rotation.pitch)
            }
            putObject("world").apply {
                put("dimension", session.world.name?.toString())
                put("time", session.world.presentationTime.time)
                put("dayPhase", session.world.presentationTime.phase.name.lowercase())
                put("rain", session.world.presentationWeather.rain)
                put("thunder", session.world.presentationWeather.thunder)
            }
            putObject("content").apply {
                put("hotReloadGeneration", ModOptions.hotReloadGeneration)
                put("terrainFingerprint", session.world.terrainPersistenceFingerprint)
                put("missingAssetFingerprint", context.contentAssetAudit.snapshot().fingerprint)
            }
            set<ObjectNode>("blocks", captureBlocks(context, origin, textures))
            set<ArrayNode>("entities", captureEntities(context, textures))
            set<ArrayNode>("items", captureItems(context, player.equipment[EquipmentSlots.MAIN_HAND], player.equipment[EquipmentSlots.OFF_HAND], textures))
            val textureArray = putArray("textures")
            textures.values.sortedBy { it.target ?: it.resource }.forEach { use ->
                textureArray.addObject().apply {
                    put("resource", use.resource)
                    use.target?.let { put("target", it) }
                    put("uses", use.uses)
                    putArray("kinds").also { kinds -> use.kinds.sorted().forEach(kinds::add) }
                }
            }
        }
    }

    private fun captureBlocks(
        context: RenderContext,
        origin: BlockPosition,
        textures: MutableMap<String, TextureUse>,
    ): ObjectNode {
        val states = linkedMapOf<String, BlockUse>()
        val visibleSections = mutableMapOf<Long, Boolean>()
        var candidates = 0
        var truncated = false
        val minY = maxOf(context.session.world.dimension.minY, origin.y - DEFAULT_VERTICAL_RADIUS)
        val maxY = minOf(context.session.world.dimension.maxY - 1, origin.y + DEFAULT_VERTICAL_RADIUS)

        context.session.world.lock.acquired {
            scan@ for (y in minY..maxY) for (z in origin.z - DEFAULT_HORIZONTAL_RADIUS..origin.z + DEFAULT_HORIZONTAL_RADIUS) {
                for (x in origin.x - DEFAULT_HORIZONTAL_RADIUS..origin.x + DEFAULT_HORIZONTAL_RADIUS) {
                    val dx = x - origin.x
                    val dz = z - origin.z
                    if (dx * dx + dz * dz > DEFAULT_HORIZONTAL_RADIUS * DEFAULT_HORIZONTAL_RADIUS) continue
                    val position = BlockPosition(x, y, z)
                    val section = position.sectionPosition
                    if (!visibleSections.getOrPut(section.raw) { section in context.camera.frustum }) continue
                    val chunk = context.session.world.chunks[position.chunkPosition] ?: continue
                    val state = chunk[position.inChunkPosition] ?: continue
                    if (state.block.identifier.toString() in AIR_BLOCKS) continue
                    if (candidates >= MAX_BLOCK_CANDIDATES) {
                        truncated = true
                        break@scan
                    }
                    candidates++
                    val stateName = stateName(state)
                    val use = states.getOrPut(stateName) {
                        BlockUse(state.block.identifier.toString(), stateName).also { blockUse ->
                            collectBlockTextures(state.model, position).forEach { texture ->
                                val reference = recordTexture(textures, texture, "block")
                                blockUse.textures += reference.target ?: reference.resource
                            }
                        }
                    }
                    use.count++
                    if (use.positions.size < MAX_POSITION_SAMPLES) use.positions += position
                    if (states.size >= MAX_BLOCK_STATES) {
                        truncated = true
                        break@scan
                    }
                }
            }
        }

        return DebugJson.MAPPER.createObjectNode().apply {
            put("classification", "frustum-candidate")
            put("horizontalRadius", DEFAULT_HORIZONTAL_RADIUS)
            put("verticalRadius", DEFAULT_VERTICAL_RADIUS)
            put("candidateCount", candidates)
            put("stateCount", states.size)
            put("truncated", truncated)
            val values = putArray("states")
            states.values.sortedByDescending(BlockUse::count).forEach { use ->
                values.addObject().apply {
                    put("block", use.block)
                    put("state", use.state)
                    put("count", use.count)
                    putArray("textures").also { array -> use.textures.sorted().forEach(array::add) }
                    putArray("samplePositions").also { positions ->
                        use.positions.forEach { position -> positions.add(positionJson(position)) }
                    }
                }
            }
        }
    }

    private fun captureEntities(context: RenderContext, textures: MutableMap<String, TextureUse>): ArrayNode {
        val result = DebugJson.MAPPER.createArrayNode()
        val session = context.session
        session.world.entities.lock.acquired {
            session.world.entities.entities.asSequence()
                .filter { it.renderer?.visibility == EntityVisibilityLevels.VISIBLE }
                .sortedBy { it.renderer?.distance2 ?: Double.MAX_VALUE }
                .take(MAX_ENTITY_RESULTS)
                .forEach { entity ->
                    val renderer = entity.renderer
                    result.addObject().apply {
                        put("id", entity.id)
                        put("uuid", entity.uuid?.toString())
                        put("type", entity.type.identifier.toString())
                        put("renderer", renderer?.javaClass?.simpleName)
                        put("visibility", "visible")
                        put("x", entity.physics.position.x); put("y", entity.physics.position.y); put("z", entity.physics.position.z)
                        when (entity) {
                            is ItemEntity -> entity.stack?.let { put("item", it.item.identifier.toString()) }
                            is ItemDisplayEntity -> entity.stack?.let { put("item", it.item.identifier.toString()) }
                        }
                        (renderer as? PlayerRenderer<*>)?.skin?.let { put("texture", it.toString()) }
                        val inspectable = renderer as? ContentModelInspectable
                        inspectable?.retainedContentModel?.let { model ->
                            model.contentIdentity?.let { identity ->
                                put("contentGeometry", identity.identifier)
                                put("contentSource", identity.source.toString())
                            }
                            context.models.skeletal.entityTextures(entity, model).values.forEach { material ->
                                recordTexture(textures, material.base.toString(), null, "entity")
                            }
                        }
                    }
                }
        }
        return result
    }

    private fun captureItems(
        context: RenderContext,
        mainHand: ItemStack?,
        offHand: ItemStack?,
        textures: MutableMap<String, TextureUse>,
    ): ArrayNode = DebugJson.MAPPER.createArrayNode().apply {
        fun addStack(source: String, stack: ItemStack) {
            addObject().apply {
                put("source", source)
                put("item", stack.item.identifier.toString())
                put("count", stack.count)
                val itemTextures = linkedSetOf<String>()
                collectItemTextures(stack.item.model, stack).forEach { texture ->
                    val reference = recordTexture(textures, texture, "item")
                    itemTextures += reference.target ?: reference.resource
                }
                putArray("textures").also { array -> itemTextures.sorted().forEach(array::add) }
            }
        }
        mainHand?.let { addStack("main-hand", it) }
        offHand?.let { addStack("off-hand", it) }
        context.session.world.entities.lock.acquired {
            context.session.world.entities.entities.asSequence()
                .filter { it.renderer?.visibility == EntityVisibilityLevels.VISIBLE }
                .take(MAX_ENTITY_RESULTS)
                .forEach { entity ->
                    when (entity) {
                        is ItemEntity -> entity.stack?.let { addStack("entity:${entity.id}", it) }
                        is ItemDisplayEntity -> entity.stack?.let { addStack("item-display:${entity.id}", it) }
                    }
                }
        }
    }

    private fun collectBlockTextures(render: BlockRender?, position: BlockPosition, depth: Int = 0): Sequence<Texture> = sequence {
        if (render == null || depth >= MAX_MODEL_DEPTH) return@sequence
        when (render) {
            is BakedModel -> render.faces.forEach { side -> side.forEach { yield(it.texture) } }
            is BuiltModel -> {
                yieldAll(collectBlockTextures(render.model, position, depth + 1))
                render.dynamic.forEach { yieldAll(collectBlockTextures(it, position, depth + 1)) }
            }
            is WeightedBlockRender -> render.models.forEach { yieldAll(collectBlockTextures(it.model, position, depth + 1)) }
            is NeighbourBlockRender -> yieldAll(collectBlockTextures(render.default, position, depth + 1))
            else -> render.getParticleTexture(null, position)?.let { yield(it) }
        }
    }

    private fun collectItemTextures(render: ItemRender?, stack: ItemStack): Sequence<Texture> = sequence {
        val resolved = render?.resolve(stack) ?: return@sequence
        when (resolved) {
            is FlatItemRender -> resolved.layers.forEach { yield(it) }
            is BlockRender -> yieldAll(collectBlockTextures(resolved, BlockPosition()))
            else -> resolved.particle?.let { yield(it) }
        }
    }

    private fun recordTexture(textures: MutableMap<String, TextureUse>, texture: Texture, kind: String): TextureUse {
        val file = (texture.loader as? FileTextureLoader)?.file
        return recordTexture(textures, file?.toString() ?: texture.toString(), file?.let(::assetTarget), kind)
    }

    private fun recordTexture(
        textures: MutableMap<String, TextureUse>,
        resource: String,
        target: String?,
        kind: String,
    ): TextureUse = textures.getOrPut(resource) { TextureUse(resource, target) }.also {
        it.uses++
        it.kinds += kind
    }

    internal fun assetTarget(resource: ResourceLocation): String? {
        if (!resource.path.startsWith("textures/") || !resource.path.endsWith(".png")) return null
        return "${resource.namespace}:${resource.path.removePrefix("textures/").removeSuffix(".png")}"
    }

    private fun stateName(state: BlockState) = buildString {
        append(state.block.identifier)
        if (state.properties.isNotEmpty()) append(state.properties.entries.sortedBy { it.key.toString() }.joinToString(",", "[", "]") {
            "${it.key.toString().lowercase()}=${it.value.toString().lowercase()}"
        })
    }

    private fun positionJson(position: BlockPosition) = DebugJson.MAPPER.createObjectNode().apply {
        put("x", position.x); put("y", position.y); put("z", position.z)
    }

    private data class BlockUse(
        val block: String,
        val state: String,
        var count: Int = 0,
        val textures: MutableSet<String> = linkedSetOf(),
        val positions: MutableList<BlockPosition> = mutableListOf(),
    )

    private data class TextureUse(
        val resource: String,
        val target: String?,
        var uses: Int = 0,
        val kinds: MutableSet<String> = linkedSetOf(),
    )

    private val AIR_BLOCKS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
}
