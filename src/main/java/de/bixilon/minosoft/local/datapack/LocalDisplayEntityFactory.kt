/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.local.datapack

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.container.stack.properties.NbtProperty
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.display.BlockDisplayEntity
import de.bixilon.minosoft.data.entities.entities.display.DisplayEntity
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayContext
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.data.entities.entities.display.TextDisplayAlignment
import de.bixilon.minosoft.data.entities.entities.display.TextDisplayEntity
import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperty
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.KUtil.startInit
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * Converts command-side entity SNBT into ordinary session-owned display
 * entities. It does not touch renderer state and is therefore valid headlessly.
 */
class LocalDisplayEntityFactory(private val session: PlaySession) {
    private var nextEntityId = Int.MAX_VALUE
    private val owned = Collections.newSetFromMap(WeakHashMap<Entity, Boolean>())

    @Synchronized
    fun summon(
        type: ResourceLocation,
        nbt: Map<String, Any>,
        position: Vec3d,
        rotation: EntityRotation = EntityRotation.EMPTY,
    ): Entity {
        require(type.namespace == "minecraft" && type.path in LOCAL_ENTITY_TYPES) {
            "Unsupported local datapack entity type $type."
        }
        require(owned.size < MAX_OWNED_ENTITIES) {
            "Local datapack entity count exceeds the $MAX_OWNED_ENTITIES session limit."
        }
        val created = mutableListOf<Entity>()
        try {
            return createAndAdd(type, nbt, position, rotation, vehicle = null, depth = 0, created)
        } catch (error: Throwable) {
            for (entity in created.asReversed()) {
                try {
                    entity.attachment.vehicle = null
                    session.world.entities.remove(entity)
                    owned.remove(entity)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    fun synchronize(entity: Entity) {
        applyKnownData(entity.type.identifier, entity.data, entity.commandNbt)
    }

    private fun createAndAdd(
        type: ResourceLocation,
        nbt: Map<String, Any>,
        position: Vec3d,
        rotation: EntityRotation,
        vehicle: Entity?,
        depth: Int,
        created: MutableList<Entity>,
    ): Entity {
        require(depth <= MAX_PASSENGER_DEPTH) {
            "Local datapack entity tree exceeds the $MAX_PASSENGER_DEPTH passenger depth limit."
        }
        require(created.size < MAX_ENTITIES_PER_SUMMON) {
            "Local datapack summon exceeds the $MAX_ENTITIES_PER_SUMMON entity limit."
        }
        require(owned.size < MAX_OWNED_ENTITIES) {
            "Local datapack entity count exceeds the $MAX_OWNED_ENTITIES session limit."
        }
        val effectivePosition = nbt.vec3d("Pos") ?: position
        val effectiveRotation = nbt.rotation("Rotation") ?: rotation
        require(effectivePosition.x.isFinite() && effectivePosition.y.isFinite() && effectivePosition.z.isFinite()) {
            "Local datapack entity position must be finite."
        }
        require(effectiveRotation.yaw.isFinite() && effectiveRotation.pitch.isFinite()) {
            "Local datapack entity rotation must be finite."
        }
        val uuid = nbt.uuid() ?: UUID.randomUUID()
        require(session.world.entities[uuid] == null) { "Duplicate local datapack entity UUID $uuid." }
        val entityType = session.registries.entityType[type]
            ?: throw IllegalArgumentException("Unknown local entity type $type for ${session.version}")
        val data = EntityData(session)
        applyKnownData(type, data, nbt)
        val entity = entityType.build(session, effectivePosition, effectiveRotation, data, uuid, session.version.versionId)
            ?: throw IllegalArgumentException("Entity type $type can not be built by the local authority.")

        entity.commandNbt.putAll(nbt.deepMutableMap())
        (nbt["Tags"] as? List<*>)?.mapTo(entity.commandTags) { it.toString() }
        entity.attachment.vehicle = vehicle
        entity.startInit()
        session.world.entities.add(allocateEntityId(), uuid, entity)
        owned += entity
        created += entity

        for (passenger in nbt["Passengers"] as? List<*> ?: emptyList<Any>()) {
            val passengerData = passenger.stringMap()
            val passengerType = passengerData["id"]?.toString()?.let(ResourceLocation::of)
                ?: throw IllegalArgumentException("Display passenger is missing an entity id.")
            require(passengerType.namespace == "minecraft" && passengerType.path in LOCAL_ENTITY_TYPES) {
                "Unsupported local datapack passenger type $passengerType."
            }
            createAndAdd(passengerType, passengerData, effectivePosition, effectiveRotation, entity, depth + 1, created)
        }
        return entity
    }

    @Synchronized
    fun remove(entity: Entity) {
        session.world.entities.remove(entity)
        owned.remove(entity)
    }

    @Synchronized
    internal fun owns(entity: Entity): Boolean = entity in owned

    @Synchronized
    internal fun restore(entity: Entity, id: Int?, uuid: UUID?, wasOwned: Boolean) {
        require(id == null || session.world.entities[id] == null) {
            "Can not restore local datapack entity id $id because it is already occupied."
        }
        require(uuid == null || session.world.entities[uuid] == null) {
            "Can not restore local datapack entity UUID $uuid because it is already occupied."
        }
        session.world.entities.add(id, uuid, entity)
        if (wasOwned) owned += entity
    }

    private fun applyKnownData(type: ResourceLocation, data: EntityData, nbt: Map<String, Any>) {
        if (type.path in DISPLAY_TYPES) {
            data[DisplayEntity.INTERPOLATION_START] = nbt.number("start_interpolation")?.toInt()
            data[DisplayEntity.INTERPOLATION_DURATION] = nbt.number("interpolation_duration")?.toInt()
            data[DisplayEntity.POSITION_ROTATION_INTERPOLATION_DURATION] = nbt.number("teleport_duration")?.toInt()
            data[DisplayEntity.BILLBOARD] = nbt["billboard"]?.toString()?.let(::billboard)
            data[DisplayEntity.BRIGHTNESS] = brightness(nbt["brightness"])
            data[DisplayEntity.VIEW_RANGE] = nbt.number("view_range")?.finiteFloat("view_range")
            data[DisplayEntity.SHADOW_RADIUS] = nbt.number("shadow_radius")?.finiteFloat("shadow_radius")
            data[DisplayEntity.SHADOW_STRENGTH] = nbt.number("shadow_strength")?.finiteFloat("shadow_strength")
            data[DisplayEntity.WIDTH] = nbt.number("width")?.finiteFloat("width")
            data[DisplayEntity.HEIGHT] = nbt.number("height")?.finiteFloat("height")
            data[DisplayEntity.GLOW_COLOR_OVERRIDE] = nbt.number("glow_color_override")?.toInt()
            applyTransformation(data, nbt["transformation"])
        }

        when (type.path) {
            "item_display" -> {
                data[ItemDisplayEntity.ITEM] = itemStack(nbt["item"])
                data[ItemDisplayEntity.ITEM_DISPLAY] = nbt["item_display"]?.toString()
                    ?.uppercase()
                    ?.let { name -> ItemDisplayContext.entries.indexOfFirst { it.name == name } }
                    ?.takeIf { it >= 0 }
            }
            "block_display" -> data[BlockDisplayEntity.BLOCK_STATE] = blockState(nbt["block_state"])
            "text_display" -> applyTextData(data, nbt)
            "interaction" -> {
                data[de.bixilon.minosoft.data.entities.entities.InteractionEntity.WIDTH] = nbt.number("width")?.finiteFloat("width")
                data[de.bixilon.minosoft.data.entities.entities.InteractionEntity.HEIGHT] = nbt.number("height")?.finiteFloat("height")
                data[de.bixilon.minosoft.data.entities.entities.InteractionEntity.RESPONSE] = nbt.boolean("response")
            }
        }
    }

    private fun applyTransformation(data: EntityData, raw: Any?) {
        when (raw) {
            is Map<*, *> -> {
                val transformation = raw.stringMap()
                data[DisplayEntity.TRANSLATION] = transformation.vec3f("translation")
                data[DisplayEntity.SCALE] = transformation.vec3f("scale")
                data[DisplayEntity.LEFT_ROTATION] = transformation.quaternion("left_rotation")
                data[DisplayEntity.RIGHT_ROTATION] = transformation.quaternion("right_rotation")
            }
            is List<*> -> {
                val decomposed = decompose(raw.map { (it as Number).finiteFloat("transformation") })
                data[DisplayEntity.TRANSLATION] = decomposed.translation
                data[DisplayEntity.SCALE] = decomposed.scale
                data[DisplayEntity.LEFT_ROTATION] = decomposed.rotation
                data[DisplayEntity.RIGHT_ROTATION] = DisplayEntity.IDENTITY_ROTATION
            }
        }
    }

    private fun applyTextData(data: EntityData, nbt: Map<String, Any>) {
        data[TextDisplayEntity.TEXT] = nbt["text"]
        data[TextDisplayEntity.LINE_WIDTH] = nbt.number("line_width")?.toInt()
        data[TextDisplayEntity.BACKGROUND] = nbt.number("background")?.toInt()
        data[TextDisplayEntity.TEXT_OPACITY] = nbt.number("text_opacity")?.toByte()
        var flags = 0
        if (nbt.boolean("shadow")) flags = flags or 0x01
        if (nbt.boolean("see_through")) flags = flags or 0x02
        if (nbt.boolean("default_background")) flags = flags or 0x04
        flags = flags or when (nbt["alignment"]?.toString()) {
            TextDisplayAlignment.LEFT.name.lowercase() -> 0x08
            TextDisplayAlignment.RIGHT.name.lowercase() -> 0x10
            else -> 0
        }
        data[TextDisplayEntity.TEXT_DISPLAY_FLAGS] = flags.toByte()
    }

    private fun itemStack(raw: Any?): ItemStack? {
        val itemData = (raw as? Map<*, *>)?.stringMap() ?: return null
        val item = itemData["id"]?.toString()?.let { session.registries.item[ResourceLocation.of(it)] } ?: return null
        val count = itemData.number("Count")?.toInt() ?: itemData.number("count")?.toInt() ?: 1
        require(count > 0) { "Display item count must be positive." }
        val nbt = itemData["tag"]?.stringMap()?.toMutableMap() ?: linkedMapOf()
        val components = itemData["components"]?.stringMap()
        components?.get("minecraft:custom_model_data")?.let { nbt["custom_model_data"] = it }
        components?.get("minecraft:item_model")?.let { nbt["item_model"] = it }
        return ItemStack(item, count, nbt = NbtProperty(nbt))
    }

    private fun blockState(raw: Any?): BlockState? {
        val state = (raw as? Map<*, *>)?.stringMap() ?: return null
        val block = state["Name"]?.toString()?.let { session.registries.block[ResourceLocation.of(it)] } ?: return null
        val properties: Map<BlockProperty<*>, Any> = state["Properties"]?.stringMap()?.map { (name, value) ->
            val property = block.properties[name]
                ?: throw IllegalArgumentException("Unknown property $name for ${block.identifier}")
            property to requireNotNull(property.parse(value))
        }?.toMap() ?: emptyMap()
        return if (properties.isEmpty()) block.states.default else block.states.withProperties(properties)
    }

    private fun brightness(raw: Any?): Int? {
        if (raw is Number) return raw.toInt()
        val value = (raw as? Map<*, *>)?.stringMap() ?: return null
        val block = value.number("block")?.toInt()?.coerceIn(0, 15) ?: 0
        val sky = value.number("sky")?.toInt()?.coerceIn(0, 15) ?: 0
        return block shl 4 or (sky shl 20)
    }

    private fun billboard(value: String): Byte = when (value.lowercase()) {
        "fixed" -> 0
        "vertical" -> 1
        "horizontal" -> 2
        "center" -> 3
        else -> throw IllegalArgumentException("Unknown display billboard $value")
    }

    private fun Map<String, Any>.uuid(): UUID? {
        val values = this["UUID"] as? List<*> ?: return null
        if (values.size != 4) return null
        val ints = values.map { (it as Number).toInt() }
        val most = ints[0].toLong() shl 32 or (ints[1].toLong() and 0xffffffffL)
        val least = ints[2].toLong() shl 32 or (ints[3].toLong() and 0xffffffffL)
        return UUID(most, least)
    }

    private fun Map<String, Any>.vec3d(key: String): Vec3d? {
        val values = this[key] as? List<*> ?: return null
        if (values.size != 3) return null
        return Vec3d(
            values.number(0).finiteDouble(key),
            values.number(1).finiteDouble(key),
            values.number(2).finiteDouble(key),
        )
    }

    private fun Map<String, Any>.rotation(key: String): EntityRotation? {
        val values = this[key] as? List<*> ?: return null
        if (values.size != 2) return null
        return EntityRotation(values.number(0).finiteFloat(key), values.number(1).finiteFloat(key))
    }

    private fun Map<String, Any>.vec3f(key: String): Vec3f? {
        val values = this[key] as? List<*> ?: return null
        if (values.size != 3) return null
        return Vec3f(
            values.number(0).finiteFloat(key),
            values.number(1).finiteFloat(key),
            values.number(2).finiteFloat(key),
        )
    }

    private fun Map<String, Any>.quaternion(key: String): Vec4f? {
        val values = this[key] as? List<*> ?: return null
        if (values.size != 4) return null
        return Vec4f(
            values.number(0).finiteFloat(key),
            values.number(1).finiteFloat(key),
            values.number(2).finiteFloat(key),
            values.number(3).finiteFloat(key),
        )
    }

    private fun Map<String, Any>.number(key: String) = this[key] as? Number
    private fun Map<String, Any>.boolean(key: String) = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }
    private fun List<*>.number(index: Int) = this[index] as Number
    private fun Number.finiteFloat(field: String) = toFloat().also {
        require(it.isFinite()) { "Display entity field $field must be finite." }
    }
    private fun Number.finiteDouble(field: String) = toDouble().also {
        require(it.isFinite()) { "Display entity field $field must be finite." }
    }

    private fun Any?.stringMap(): Map<String, Any> {
        require(this is Map<*, *>) { "Expected an SNBT compound, got $this" }
        return entries.associate { it.key.toString() to requireNotNull(it.value) }
    }

    private fun Map<String, Any>.deepMutableMap(): MutableMap<String, Any> {
        return entries.associateTo(linkedMapOf()) { it.key to it.value.deepMutable() }
    }

    private fun Any.deepMutable(): Any = when (this) {
        is Map<*, *> -> entries.associateTo(linkedMapOf()) { it.key.toString() to requireNotNull(it.value).deepMutable() }
        is List<*> -> mapTo(mutableListOf()) { requireNotNull(it).deepMutable() }
        else -> this
    }

    private fun decompose(matrix: List<Float>): MatrixTransform {
        require(matrix.size == 16) { "Display transformation matrix must contain 16 values." }
        val sx = length(matrix[0], matrix[1], matrix[2])
        val sy = length(matrix[4], matrix[5], matrix[6])
        val sz = length(matrix[8], matrix[9], matrix[10])
        val m00 = matrix[0] / sx
        val m01 = matrix[4] / sy
        val m02 = matrix[8] / sz
        val m10 = matrix[1] / sx
        val m11 = matrix[5] / sy
        val m12 = matrix[9] / sz
        val m20 = matrix[2] / sx
        val m21 = matrix[6] / sy
        val m22 = matrix[10] / sz
        val quaternion = quaternion(m00, m01, m02, m10, m11, m12, m20, m21, m22)
        return MatrixTransform(
            Vec3f(matrix[12], matrix[13], matrix[14]),
            Vec3f(sx, sy, sz),
            quaternion,
        )
    }

    private fun quaternion(
        m00: Float, m01: Float, m02: Float,
        m10: Float, m11: Float, m12: Float,
        m20: Float, m21: Float, m22: Float,
    ): Vec4f {
        val trace = m00 + m11 + m22
        return if (trace > 0.0f) {
            val s = kotlin.math.sqrt(trace + 1.0f) * 2.0f
            Vec4f((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = kotlin.math.sqrt(1.0f + m00 - m11 - m22) * 2.0f
            Vec4f(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = kotlin.math.sqrt(1.0f + m11 - m00 - m22) * 2.0f
            Vec4f((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = kotlin.math.sqrt(1.0f + m22 - m00 - m11) * 2.0f
            Vec4f((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
        }
    }

    private fun length(x: Float, y: Float, z: Float): Float {
        val value = kotlin.math.sqrt(x * x + y * y + z * z)
        return if (value <= 0.000001f) 1.0f else value
    }

    @Synchronized
    private fun allocateEntityId(): Int {
        while (session.world.entities[nextEntityId] != null) nextEntityId--
        return nextEntityId--
    }

    private data class MatrixTransform(
        val translation: Vec3f,
        val scale: Vec3f,
        val rotation: Vec4f,
    )

    private companion object {
        const val MAX_PASSENGER_DEPTH = 32
        const val MAX_ENTITIES_PER_SUMMON = 1024
        const val MAX_OWNED_ENTITIES = 16_384
        val DISPLAY_TYPES = setOf("item_display", "block_display", "text_display")
        val LOCAL_ENTITY_TYPES = DISPLAY_TYPES + setOf("interaction", "marker")
    }
}
