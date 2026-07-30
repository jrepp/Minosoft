/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.animal.Animal
import de.bixilon.minosoft.data.registries.entities.EntityFactory
import de.bixilon.minosoft.data.registries.entities.EntityType
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.packets.c2s.common.ChannelC2SP
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class FabricRemoteEntityKind {
    LIVING,
    ENTITY,
}

data class FabricRemoteEntityDefinition(
    val identifier: ResourceLocation,
    val width: Float,
    val height: Float,
    val kind: FabricRemoteEntityKind = FabricRemoteEntityKind.LIVING,
) {
    init {
        require(width > 0.0f && width.isFinite()) { "Remote entity width must be finite and positive." }
        require(height > 0.0f && height.isFinite()) { "Remote entity height must be finite and positive." }
    }

    internal fun materialize(): EntityType {
        val factory = FabricRemoteEntityFactory(identifier, kind)
        return EntityType(identifier, null, width, height, factory = factory)
    }
}

data class FabricRemoteRegistryState(
    val registries: Set<ResourceLocation>,
    val entityTypes: Map<ResourceLocation, Int>,
    val materializedEntityTypes: Set<ResourceLocation>,
)

/**
 * Bounded decoder for Fabric Registry Sync v0's 1.20.4 direct packet.
 *
 * Fabric splits one logical buffer across up to one-MiB custom payloads and
 * terminates it with an empty payload. Registry and entry namespaces are
 * grouped, while consecutive raw IDs are delta encoded.
 */
internal object FabricDirectRegistryCodec {
    const val MAX_TOTAL_BYTES = 8 shl 20
    private const val MAX_REGISTRIES = 4_096
    private const val MAX_ENTRIES = 1_000_000
    private const val MAX_STRING_BYTES = 32_767

    fun decode(payload: ByteArray): Map<ResourceLocation, Map<ResourceLocation, Int>> {
        val cursor = Cursor(payload)
        val registryNamespaces = cursor.count(MAX_REGISTRIES, "registry namespace groups")
        val result = linkedMapOf<ResourceLocation, Map<ResourceLocation, Int>>()
        var registryCount = 0
        var entryCount = 0

        repeat(registryNamespaces) {
            val registryNamespace = cursor.namespace()
            val registries = cursor.count(MAX_REGISTRIES - registryCount, "registries")
            registryCount += registries
            repeat(registries) {
                val registry = cursor.identifier(registryNamespace, cursor.string())
                val entryNamespaces = cursor.count(MAX_ENTRIES - entryCount, "entry namespace groups")
                val entries = linkedMapOf<ResourceLocation, Int>()
                var lastBulkLastRawId = 0
                repeat(entryNamespaces) {
                    val entryNamespace = cursor.namespace()
                    val bulks = cursor.count(MAX_ENTRIES - entryCount, "raw ID bulks")
                    repeat(bulks) {
                        val startDifference = cursor.varInt()
                        val size = cursor.count(MAX_ENTRIES - entryCount, "raw ID bulk entries")
                        var rawId = Math.addExact(lastBulkLastRawId, startDifference) - 1
                        repeat(size) {
                            rawId = Math.addExact(rawId, 1)
                            require(rawId >= 0) { "Fabric registry raw ID is negative: $rawId" }
                            val identifier = cursor.identifier(entryNamespace, cursor.string())
                            require(entries.putIfAbsent(identifier, rawId) == null) {
                                "Fabric registry $registry contains duplicate entry $identifier."
                            }
                            entryCount++
                        }
                        lastBulkLastRawId = rawId
                    }
                }
                require(result.putIfAbsent(registry, entries) == null) {
                    "Fabric registry sync contains duplicate registry $registry."
                }
            }
        }
        require(cursor.exhausted) { "Fabric registry sync has ${cursor.remaining} trailing bytes." }
        return result
    }

    private class Cursor(private val data: ByteArray) {
        private var offset = 0
        val remaining: Int get() = data.size - offset
        val exhausted: Boolean get() = offset == data.size

        fun varInt(): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                require(offset < data.size) { "Truncated Fabric registry VarInt." }
                val byte = data[offset++].toInt() and 0xFF
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("Fabric registry VarInt exceeds five bytes.")
        }

        fun count(maximum: Int, label: String): Int {
            require(maximum >= 0) { "Fabric registry exceeds the aggregate entry limit." }
            return varInt().also { require(it in 0..maximum) { "Fabric $label count $it exceeds $maximum." } }
        }

        fun string(): String {
            val size = count(MAX_STRING_BYTES, "string bytes")
            require(size <= remaining) { "Truncated Fabric registry string: $size bytes, $remaining remain." }
            val value = data.copyOfRange(offset, offset + size).toString(StandardCharsets.UTF_8)
            offset += size
            return value
        }

        fun namespace(): String = string().ifEmpty { "minecraft" }

        fun identifier(namespace: String, path: String): ResourceLocation {
            require(namespace.isNotEmpty() && path.isNotEmpty()) { "Fabric registry identifier is empty." }
            return ResourceLocation.of("$namespace:$path")
        }
    }
}

/**
 * Source-native client support for Fabric Registry Sync v0 configuration
 * payloads. Definitions remain owner scoped; synchronized numeric IDs are
 * materialized only into the receiving play session.
 */
object FabricRemoteRegistrySync {
    val REGISTER = ResourceLocation.of("minecraft:register")
    val DIRECT = ResourceLocation.of("fabric:registry/sync/direct")
    val COMPLETE = ResourceLocation.of("fabric:registry/sync/complete")
    val ENTITY_TYPE = ResourceLocation.of("minecraft:entity_type")

    private val providers = FabricHookRegistry<Unit>("remote-registry-sync")
    private val definitions = linkedMapOf<ResourceLocation, OwnedDefinition>()
    private val fragments = Collections.synchronizedMap(WeakHashMap<PlaySession, ByteArrayOutputStream>())
    private val states = Collections.synchronizedMap(WeakHashMap<PlaySession, FabricRemoteRegistryState>())

    fun install(owner: String): AutoCloseable {
        val registration = providers.register(owner, Unit)
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            registration.close()
            if (providers.snapshot().isEmpty()) {
                synchronized(fragments) { fragments.clear() }
                synchronized(states) { states.clear() }
            }
        }
    }
    fun providers(): List<String> = providers.snapshot().map(FabricHostHook<Unit>::owner)

    @Synchronized
    fun register(owner: String, values: Collection<FabricRemoteEntityDefinition>): AutoCloseable {
        require(owner.isNotBlank()) { "Fabric remote entity owner is blank." }
        require(values.map { it.identifier }.toSet().size == values.size) { "Remote entity definitions contain duplicate identifiers." }
        val owned = values.map { definition ->
            require(definitions[definition.identifier] == null) {
                "Remote entity ${definition.identifier} is already registered by ${definitions[definition.identifier]?.owner}."
            }
            OwnedDefinition(owner, definition)
        }
        owned.forEach { definitions[it.definition.identifier] = it }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            synchronized(this) {
                owned.forEach { definitions.remove(it.definition.identifier, it) }
            }
        }
    }

    @Synchronized
    fun definitions(): Map<ResourceLocation, String> = definitions.mapValues { it.value.owner }

    @Synchronized
    fun supportsLocal(identifier: ResourceLocation): Boolean = identifier in definitions

    /**
     * Materializes one owner-declared dependent-mod type for the source-native
     * local authority. This shares the exact DTO/factory used by remote
     * registry sync without inventing a numeric wire ID.
     */
    @Synchronized
    fun materializeLocal(session: PlaySession, identifier: ResourceLocation): EntityType? {
        val definition = definitions[identifier]?.definition ?: return null
        session.registries.entityType[identifier]?.let { return it }
        return definition.materialize().also { session.registries.entityType.add(null, it) }
    }

    fun state(session: PlaySession): FabricRemoteRegistryState? = states[session]

    /**
     * Returns true when the payload belongs to the supported Fabric registry
     * handshake, even when no response is required for this fragment.
     */
    fun handleConfiguration(session: PlaySession, channel: ResourceLocation, payload: ByteArray): Boolean {
        if (providers.snapshot().isEmpty()) return false
        return when (channel) {
            REGISTER -> handleRegistration(session, payload)
            DIRECT -> handleDirect(session, payload)
            else -> false
        }
    }

    private fun handleRegistration(session: PlaySession, payload: ByteArray): Boolean {
        require(payload.size <= FabricClientPayloadChannels.MAX_PAYLOAD_BYTES) {
            "Fabric channel registration exceeds ${FabricClientPayloadChannels.MAX_PAYLOAD_BYTES} bytes."
        }
        val channels = payload.toString(StandardCharsets.US_ASCII)
            .split('\u0000')
            .filter(String::isNotEmpty)
            .map(ResourceLocation::of)
            .toSet()
        if (COMPLETE !in channels) return false
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "FABRIC_REMOTE_REGISTRY_ADVERTISED channels=${channels.size} direct=$DIRECT"
        }
        val registration = listOf(DIRECT, ResourceLocation.of("minosoft:registry_sync"))
            .joinToString("\u0000")
            .toByteArray(StandardCharsets.US_ASCII)
        session.connection.send(ChannelC2SP(REGISTER, registration, rawData = true))
        return true
    }

    private fun handleDirect(session: PlaySession, payload: ByteArray): Boolean {
        require(payload.size <= FabricClientPayloadChannels.MAX_PAYLOAD_BYTES) {
            "Fabric registry fragment exceeds ${FabricClientPayloadChannels.MAX_PAYLOAD_BYTES} bytes."
        }
        if (payload.isNotEmpty()) {
            val total = synchronized(fragments) {
                val stream = fragments.getOrPut(session) { ByteArrayOutputStream() }
                require(stream.size() + payload.size <= FabricDirectRegistryCodec.MAX_TOTAL_BYTES) {
                    "Fabric registry sync exceeds ${FabricDirectRegistryCodec.MAX_TOTAL_BYTES} bytes."
                }
                stream.write(payload)
                stream.size()
            }
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "FABRIC_REMOTE_REGISTRY_FRAGMENT bytes=${payload.size} total=$total"
            }
            return true
        }

        val complete = synchronized(fragments) {
            fragments.remove(session)?.toByteArray() ?: ByteArray(0)
        }
        val decoded = FabricDirectRegistryCodec.decode(complete)
        val state = apply(session, decoded)
        states[session] = state
        session.connection.send(ChannelC2SP(COMPLETE, byteArrayOf(), rawData = true))
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "FABRIC_REMOTE_REGISTRY_SYNCED registries=${state.registries.size} entityTypes=${state.entityTypes.size} materialized=${state.materializedEntityTypes.size}"
        }
        return true
    }

    private fun apply(
        session: PlaySession,
        decoded: Map<ResourceLocation, Map<ResourceLocation, Int>>,
    ): FabricRemoteRegistryState {
        val remote = decoded[ENTITY_TYPE].orEmpty()
        val numeric = linkedMapOf<Int, EntityType>()
        val additions = linkedMapOf<ResourceLocation, EntityType>()
        val missing = linkedSetOf<ResourceLocation>()
        for ((identifier, rawId) in remote) {
            val type = session.registries.entityType[identifier]
                ?: synchronized(this) { definitions[identifier]?.definition }?.materialize()?.also {
                    additions[identifier] = it
                }
            if (type == null) {
                missing += identifier
                continue
            }
            require(numeric.putIfAbsent(rawId, type) == null) {
                "Fabric entity registry raw ID $rawId is duplicated."
            }
        }
        require(missing.isEmpty()) {
            "Fabric server has unsupported entity types: ${missing.joinToString()}."
        }
        session.registries.entityType.replaceIds(numeric, additions.values)
        return FabricRemoteRegistryState(decoded.keys, remote, additions.keys)
    }

    private data class OwnedDefinition(
        val owner: String,
        val definition: FabricRemoteEntityDefinition,
    )
}

private class FabricRemoteEntityFactory(
    override val identifier: ResourceLocation,
    private val kind: FabricRemoteEntityKind,
) : EntityFactory<Entity> {
    override fun build(
        session: PlaySession,
        entityType: EntityType,
        data: EntityData,
        position: Vec3d,
        rotation: EntityRotation,
    ): Entity = when (kind) {
        FabricRemoteEntityKind.LIVING -> FabricRemoteLivingEntity(session, entityType, data, position, rotation)
        FabricRemoteEntityKind.ENTITY -> FabricRemoteEntity(session, entityType, data, position, rotation)
    }
}

private class FabricRemoteLivingEntity(
    session: PlaySession,
    entityType: EntityType,
    data: EntityData,
    position: Vec3d,
    rotation: EntityRotation,
) : Animal(session, entityType, data, position, rotation)

private class FabricRemoteEntity(
    session: PlaySession,
    entityType: EntityType,
    data: EntityData,
    position: Vec3d,
    rotation: EntityRotation,
) : Entity(session, entityType, data, position, rotation)
