/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.camera.target.targets.BlockTarget
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.registries.blocks.settings.BlockSettings
import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperty
import de.bixilon.minosoft.data.registries.blocks.properties.list.BlockPropertyList
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.registries.blocks.state.manager.PropertyStateManager
import de.bixilon.minosoft.data.registries.blocks.state.manager.SingleStateManager
import de.bixilon.minosoft.data.registries.blocks.types.Block
import de.bixilon.minosoft.data.registries.blocks.types.properties.FullBlock
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.Item
import de.bixilon.minosoft.data.registries.item.items.block.PlaceableItem
import de.bixilon.minosoft.data.registries.item.stack.StackableItem
import de.bixilon.minosoft.modding.event.events.loading.RegistriesLoadEvent
import de.bixilon.minosoft.modding.event.events.session.play.PlaySessionCreateEvent
import de.bixilon.minosoft.modding.event.listener.CallbackEventListener.Companion.listen
import de.bixilon.minosoft.modding.event.listener.EventListener
import de.bixilon.minosoft.modding.event.master.GlobalEventMaster
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.Collections
import java.util.WeakHashMap

data class FabricRegistryEntry(
    val paletteId: Int,
    val identifier: String,
    val block: Boolean,
    val item: Boolean,
)

data class FabricRegistryState(
    val paletteId: Int,
    val identifier: String,
    val properties: Map<String, String>,
)

data class FabricRegistrySnapshot(
    val namespace: String,
    val fingerprint: String,
    val entries: List<FabricRegistryEntry>,
    val states: List<FabricRegistryState>,
) {
    private val byPaletteId = entries.associateBy(FabricRegistryEntry::paletteId)
    private val byIdentifier = entries.associateBy(FabricRegistryEntry::identifier)
    private val statesByPaletteId = states.associateBy(FabricRegistryState::paletteId)
    private val statesByIdentity = states.associateBy { it.identifier to it.properties }

    fun identifier(paletteId: Int): String? = byPaletteId[paletteId]?.identifier
    fun paletteId(identifier: String): Int? = byIdentifier[identifier]?.paletteId
    fun state(paletteId: Int): FabricRegistryState? = statesByPaletteId[paletteId]
    fun statePaletteId(identifier: String, properties: Map<String, String> = emptyMap()): Int? = statesByIdentity[identifier to properties]?.paletteId

    fun validate(content: FabricWorldContent) {
        require(namespace == content.namespace) { "Registry namespace mismatch: $namespace != ${content.namespace}" }
        require(fingerprint == content.fingerprint) { "Registry fingerprint mismatch for $namespace." }
        require(entries.map { it.identifier } == content.content.map { it.id }) { "Registry entry mismatch for $namespace." }
        val expectedStates = content.blocks.flatMap { definition -> definition.stateProperties().map { definition.id to it } }
        require(states.map { it.identifier to it.properties } == expectedStates) { "Registry block-state mismatch for $namespace." }
    }

    companion object {
        fun of(content: FabricWorldContent): FabricRegistrySnapshot {
            val entries = content.content.mapIndexed { index, definition ->
                FabricRegistryEntry(index + MOD_PALETTE_BASE, definition.id, definition.hasBlockState, definition.hasItemModel)
            }
            var stateId = MOD_STATE_PALETTE_BASE
            val states = buildList {
                for (definition in content.blocks) {
                    val properties = definition.stateProperties()
                    require(size.toLong() + properties.size <= MAX_TOTAL_STATES) {
                        "Fabric namespace ${content.namespace} exceeds the $MAX_TOTAL_STATES total block-state limit."
                    }
                    properties.mapTo(this) { FabricRegistryState(stateId++, definition.id, it) }
                }
            }
            return FabricRegistrySnapshot(content.namespace, content.fingerprint, entries, states)
        }

        private const val MOD_PALETTE_BASE = 0x01000000
        private const val MOD_STATE_PALETTE_BASE = 0x02000000
        private const val MAX_TOTAL_STATES = 1_000_000
    }
}

private class FabricStringProperty(name: String, private val values: List<String>) : BlockProperty<String>(name) {
    override fun parse(value: Any): String = value.toString().lowercase().also {
        require(it in values) { "Invalid $name value: $value (expected one of $values)" }
    }

    override fun iterator(): Iterator<String> = values.iterator()
}

private class FabricPropertyList(definition: FabricContentDefinition) : BlockPropertyList {
    val entries = definition.blockProperties.mapValues { (name, values) -> FabricStringProperty(name, values) }

    override fun get(name: String): BlockProperty<*>? = entries[name]

    override fun unpack(): List<Map<BlockProperty<*>, Any>> {
        var states: List<Map<BlockProperty<*>, Any>> = listOf(emptyMap())
        for (property in entries.values) {
            states = states.flatMap { state -> property.map { value -> state + (property to value) } }
        }
        return states
    }
}

class FabricContentBlock(
    identifier: ResourceLocation,
    session: PlaySession,
    val definition: FabricContentDefinition,
) : Block(identifier, BlockSettings(session.version)), FullBlock {
    override val hardness = 3.0f
    private val fabricProperties = FabricPropertyList(definition)
    override val properties: BlockPropertyList = fabricProperties

    init {
        val stateProperties = properties.unpack()
        if (stateProperties.size == 1 && stateProperties.single().isEmpty()) {
            val state = BlockState(this, emptyMap(), flags)
            this::states.forceSet(SingleStateManager(state))
        } else {
            val states = stateProperties.mapTo(linkedSetOf()) { BlockState(this, it, flags) }
            val values: Map<BlockProperty<*>, Array<Any>> = fabricProperties.entries.values.associate { property ->
                (property as BlockProperty<*>) to property.map { it as Any }.toTypedArray()
            }
            this::states.forceSet(PropertyStateManager(values, states, states.first()))
        }
    }

    fun state(properties: Map<String, String>): BlockState {
        val parsed = properties.map { (name, value) ->
            val property = requireNotNull(this.properties[name]) { "Unknown property $name for $identifier" }
            property to requireNotNull(property.parse(value))
        }.toMap()
        return states.first { it.properties == parsed }
    }
}

open class FabricContentItem(identifier: ResourceLocation) : Item(identifier), StackableItem

class FabricContentBlockItem(
    identifier: ResourceLocation,
    private val contentBlock: FabricContentBlock,
) : FabricContentItem(identifier), PlaceableItem {
    override fun getPlacementState(session: PlaySession, target: BlockTarget, stack: ItemStack): BlockState = contentBlock.states.default
}

data class FabricSessionContent(
    val snapshot: FabricRegistrySnapshot,
    val blocks: Map<String, FabricContentBlock>,
    val items: Map<String, FabricContentItem>,
)

object FabricSessionContentBridge {
    private val sessions = Collections.synchronizedMap(WeakHashMap<PlaySession, FabricSessionContent>())

    fun register(
        owner: String,
        content: FabricWorldContent,
        accepts: (PlaySession) -> Boolean = { true },
    ): AutoCloseable {
        val snapshot = FabricRegistrySnapshot.of(content)
        snapshot.validate(content)
        val sessionListeners = Collections.synchronizedMap(WeakHashMap<PlaySession, EventListener>())
        val createListener = GlobalEventMaster.listen<PlaySessionCreateEvent> { created ->
            val session = created.session
            if (!accepts(session)) return@listen
            val listener = session.events.listen<RegistriesLoadEvent> registry@{ event ->
                if (event.state != RegistriesLoadEvent.States.POST) return@registry
                install(owner, event.session, content, snapshot)
            }
            sessionListeners[session] = listener
        }
        return AutoCloseable {
            GlobalEventMaster.unregister(createListener)
            synchronized(sessionListeners) {
                for ((session, listener) in sessionListeners) {
                    session.events.unregister(listener)
                }
                sessionListeners.clear()
            }
            synchronized(sessions) {
                sessions.entries.removeIf { it.value.snapshot == snapshot }
            }
        }
    }

    fun session(session: PlaySession): FabricSessionContent? = sessions[session]

    private fun install(owner: String, session: PlaySession, content: FabricWorldContent, snapshot: FabricRegistrySnapshot) {
        check(sessions[session] == null) { "Fabric content ${content.namespace} is already installed in this session." }
        val blocks = linkedMapOf<String, FabricContentBlock>()
        val items = linkedMapOf<String, FabricContentItem>()
        for (entry in snapshot.entries) {
            val identifier = ResourceLocation.of(entry.identifier)
            val definition = content.content.single { it.id == entry.identifier }
            val block = if (definition.hasBlockState) FabricContentBlock(identifier, session, definition).also {
                session.registries.block.add(entry.paletteId, it)
                blocks[entry.identifier] = it
            } else null
            if (definition.hasItemModel) {
                val item = if (block == null) FabricContentItem(identifier) else FabricContentBlockItem(identifier, block)
                session.registries.item.add(entry.paletteId, item)
                items[entry.identifier] = item
            }
        }
        for (state in snapshot.states) {
            val block = requireNotNull(blocks[state.identifier])
            session.registries.blockState[state.paletteId] = block.state(state.properties)
        }
        sessions[session] = FabricSessionContent(snapshot, blocks, items)
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "FABRIC_REGISTRY_SYNCED namespace=${content.namespace} fingerprint=${content.fingerprint} blocks=${blocks.size} items=${items.size} registry=${snapshot.entries.size} states=${snapshot.states.size} owner=$owner"
        }
    }
}

private fun FabricContentDefinition.stateProperties(): List<Map<String, String>> {
    var states: List<Map<String, String>> = listOf(emptyMap())
    for ((name, values) in blockProperties) {
        require(values.isNotEmpty()) { "Fabric block property $name has no values." }
        require(states.size.toLong() * values.size <= MAX_STATES_PER_BLOCK) {
            "Fabric block $id exceeds the $MAX_STATES_PER_BLOCK expanded-state limit."
        }
        states = states.flatMap { state -> values.map { value -> state + (name to value) } }
    }
    return states
}

private const val MAX_STATES_PER_BLOCK = 65_536
