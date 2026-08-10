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

package de.bixilon.minosoft.local

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.concurrent.pool.DefaultThreadPool
import de.bixilon.kutil.observer.DataObserver.Companion.observed
import de.bixilon.minosoft.assets.datapack.LocalDataPackCommandAuthority
import de.bixilon.minosoft.assets.datapack.SessionDataPackRuntime
import de.bixilon.minosoft.data.abilities.Gamemodes
import de.bixilon.minosoft.data.chat.message.SimpleChatMessage
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.chat.type.DefaultMessageTypes
import de.bixilon.minosoft.data.entities.entities.player.additional.AdditionalDataUpdate
import de.bixilon.minosoft.data.entities.entities.InteractionEntity
import de.bixilon.minosoft.data.entities.entities.player.local.Abilities
import de.bixilon.minosoft.data.registries.dimension.DimensionProperties
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.blocks.state.BlockState
import de.bixilon.minosoft.data.text.BaseComponent
import de.bixilon.minosoft.data.text.ChatComponent
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.local.datapack.LocalDataPackEntityAccess
import de.bixilon.minosoft.local.datapack.LocalDisplayEntityFactory
import de.bixilon.minosoft.local.generator.ChunkGenerator
import de.bixilon.minosoft.local.storage.WorldStorage
import de.bixilon.minosoft.modding.event.events.TabListEntryChangeEvent
import de.bixilon.minosoft.modding.event.events.chat.ChatMessageEvent
import de.bixilon.minosoft.modding.event.events.chat.ChatMessageSendEvent
import de.bixilon.minosoft.modding.event.listener.CallbackEventListener.Companion.listen
import de.bixilon.minosoft.modding.loader.fabric.FabricWorldChangeCause
import de.bixilon.minosoft.modding.loader.fabric.FabricWorldEvents
import de.bixilon.minosoft.protocol.ServerConnection
import de.bixilon.minosoft.protocol.network.session.Session
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.protocol.network.session.play.PlaySessionStates
import de.bixilon.minosoft.protocol.packets.c2s.C2SPacket
import de.bixilon.minosoft.protocol.packets.c2s.play.entity.move.PositionC2SP
import de.bixilon.minosoft.protocol.packets.c2s.play.entity.move.PositionRotationC2SP
import de.bixilon.minosoft.protocol.packets.c2s.play.entity.interact.EntityAttackC2SP
import de.bixilon.minosoft.protocol.packets.c2s.play.entity.interact.EntityEmptyInteractC2SP
import de.bixilon.minosoft.protocol.packets.c2s.play.entity.interact.EntityInteractPositionC2SP
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

class LocalConnection(
    val generator: (PlaySession) -> ChunkGenerator,
    val storage: (PlaySession) -> WorldStorage,
    private val terrainPersistenceFingerprint: String? = null,
) : ServerConnection {
    override val identifier = "<local>"
    override var active by observed(false)
    private var detached = false
    private lateinit var session: PlaySession
    private var dataPackRuntime: SessionDataPackRuntime? = null
    private var dataPackTickTask: Runnable? = null
    private var displayFactory: LocalDisplayEntityFactory? = null
    lateinit var chunks: LocalChunkManager

    fun sendMessage(message: Any, type: ResourceLocation = DefaultMessageTypes.CHAT) {
        val type = session.registries.messageType[type]!!
        session.events.fire(ChatMessageEvent(session, SimpleChatMessage(ChatComponent.of(message), type)))
    }


    override fun connect(session: Session) {
        if (session !is PlaySession) throw IllegalStateException("Not a play session?")
        Log.log(LogMessageType.NETWORK, LogLevels.INFO) { "Establishing local connection" }
        active = true
        this.session = session
        val generator = generator.invoke(session)

        session.util.prepareSpawn()


        val dimension = generator.dimension ?: DimensionProperties()
        FabricWorldEvents.change(session, FabricWorldChangeCause.LOCAL_CONNECT, dimension, session.world.name) {
            session.util.resetWorld()
            session.world.dimension = dimension
            session.world.updateTerrainPersistenceFingerprint(terrainPersistenceFingerprint)
        }
        this.chunks = LocalChunkManager(session, storage.invoke(session), generator)
        session.player.additional.gamemode = Gamemodes.CREATIVE


        session.world.entities.clear(session, local = true)
        session.world.entities.add(1, null, session.player)

        session.player.abilities = Abilities(flying = true, allowFly = true)

        session.state = PlaySessionStates.SPAWNING

        val additional = session.player.additional

        session.tabList.uuid[session.player.uuid] = additional
        session.tabList.name[session.player.additional.name] = additional

        session.events.fire(TabListEntryChangeEvent(session, mapOf(session.player.uuid to AdditionalDataUpdate())))


        Log.log(LogMessageType.NETWORK, LogLevels.INFO) { "Loading local world" }
        chunks.storage.load(session.world)
        DefaultThreadPool += { chunks.update() }
        Log.log(LogMessageType.NETWORK, LogLevels.INFO) { "Loaded local world!" }

        session.player.physics.forceTeleport(Vec3d(0.5, 20.0, 0.5)) // TODO: teleport on ground (after world is loaded)

        val displayFactory = LocalDisplayEntityFactory(session)
        this.displayFactory = displayFactory
        val dataPackEntities = LocalDataPackEntityAccess(session, displayFactory) { session.player.physics.position }
        val commandAuthority = LocalDataPackCommandAuthority(
            message = { sendMessage(it) },
            origin = { session.player.physics.position },
            rotation = { session.player.physics.rotation },
            spawn = { type, nbt, position -> displayFactory.summon(type, nbt, position) },
            entities = dataPackEntities,
        )
        val dataPackRuntime = SessionDataPackRuntime(session.contentFidelity, commandAuthority)
        this.dataPackRuntime = dataPackRuntime
        try {
            dataPackRuntime.refresh()
        } catch (error: Throwable) {
            Log.log(LogMessageType.LOADING, LogLevels.WARN, error)
        }
        val dataPackTickTask = Runnable { dataPackRuntime.tick() }
        this.dataPackTickTask = dataPackTickTask
        session.ticker += dataPackTickTask

        session.events.listen<ChatMessageSendEvent> { sendMessage(BaseComponent(session.player.name, "> ", it.message.replace('&', '§'))) }

        session.state = PlaySessionStates.PLAYING

        sendMessage("§e${session.player.name} §ejoined the game!")
    }

    override fun disconnect() {
        dataPackTickTask?.let { session.ticker -= it }
        dataPackTickTask = null
        displayFactory = null
        val dataPackRuntime = dataPackRuntime
        this.dataPackRuntime = null
        try {
            dataPackRuntime?.close()
        } finally {
            active = false
        }
    }

    override fun detach() {
        detached = true
    }

    override fun send(packet: C2SPacket) {
        if (detached) return
        packet.log(true)
        when (packet) {
            is PositionRotationC2SP -> chunks.update()
            is PositionC2SP -> chunks.update()
            is EntityAttackC2SP -> recordInteraction(packet.entityId, attack = true)
            is EntityEmptyInteractC2SP -> recordInteraction(packet.entityId, attack = false)
            is EntityInteractPositionC2SP -> recordInteraction(packet.entityId, attack = false)
        }
    }

    /**
     * Executes one mounted data-pack function through the same session-owned
     * runtime used by local ticks. The origin is installed only for command
     * execution; a successful call then moves the local camera to its requested
     * deterministic acceptance pose. Failures restore the previous player pose.
     */
    @Synchronized
    fun executeDataPackFunction(
        reference: String,
        arguments: Map<String, String>,
        origin: Vec3d,
        originRotation: EntityRotation,
        camera: Vec3d,
        cameraRotation: EntityRotation,
    ): LocalDataPackExecution {
        check(active && ::session.isInitialized) { "Local connection is not active." }
        val runtime = dataPackRuntime ?: throw IllegalStateException("Local data-pack runtime is not active.")
        require(origin.isFinite() && camera.isFinite()) { "Local data-pack acceptance positions must be finite." }
        require(originRotation.isFinite() && cameraRotation.isFinite()) { "Local data-pack acceptance rotations must be finite." }

        val player = session.player
        val previousPosition = player.physics.position
        val previousRotation = player.physics.rotation
        player.physics.forceTeleport(origin)
        player.physics.forceSetRotation(originRotation)
        player.physics.forceSetHeadYaw(originRotation.yaw)
        val executed = try {
            runtime.execute(reference, arguments)
        } catch (error: Throwable) {
            player.physics.forceTeleport(previousPosition)
            player.physics.forceSetRotation(previousRotation)
            player.physics.forceSetHeadYaw(previousRotation.yaw)
            throw error
        }
        player.physics.forceTeleport(camera)
        player.physics.forceSetRotation(cameraRotation)
        player.physics.forceSetHeadYaw(cameraRotation.yaw)
        return LocalDataPackExecution(
            executed = executed,
            generation = runtime.activeGenerationId,
            tick = runtime.tick,
        )
    }

    /**
     * Places a bounded set of blocks through the normal client-world mutation
     * path. Any chunk the placements touch is generated and registered first, and
     * each write fires the ordinary single-block update, so neighbours are re-lit
     * and re-meshed exactly like a server-pushed block change.
     */
    @Synchronized
    fun placeBlocks(placements: List<LocalBlockPlacement>): Int {
        check(active && ::session.isInitialized) { "Local connection is not active." }
        val world = session.world
        val loadedChunks = hashSetOf<ChunkPosition>()
        for (placement in placements) {
            val position = placement.position
            require(world.isValidPosition(position)) { "Block position $position is outside the world." }
            if (loadedChunks.add(position.chunkPosition)) {
                chunks.ensureLoaded(position.chunkPosition)
            }
            world[position] = placement.state
        }
        return placements.size
    }

    private fun recordInteraction(entityId: Int, attack: Boolean) {
        val entity = session.world.entities[entityId] as? InteractionEntity ?: return
        if (displayFactory?.owns(entity) != true) return
        val runtime = dataPackRuntime
        val handler = if (attack) {
            "animated_java:global/interactions/attack/on"
        } else {
            "animated_java:global/interactions/interaction/on"
        }
        try {
            if (runtime == null) {
                entity.recordInteraction(session.player, attack, 0L)
            } else {
                runtime.executeInteraction(handler, entity, session.player, attack)
            }
        } catch (error: Throwable) {
            Log.log(LogMessageType.LOADING, LogLevels.WARN, error)
        }
    }

    private fun Vec3d.isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    private fun EntityRotation.isFinite() = yaw.isFinite() && pitch.isFinite()

    data class LocalBlockPlacement(
        val position: BlockPosition,
        val state: BlockState,
    )

    data class LocalDataPackExecution(
        val executed: Int,
        val generation: Long?,
        val tick: Long,
    )
}
