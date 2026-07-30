/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.minosoft.config.profile.ProfileOptions
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.debug.ClientDebugChannel
import de.bixilon.minosoft.data.world.chunk.ChunkSize
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.InChunkPosition
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.IdentityHashMap
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Exact-artifact adapter for the first source-native Distant Horizons slice.
 *
 * Upstream client bytecode is not linked. The adapter captures compact explored
 * surface tiles behind owned world/chunk hooks so persistence, meshing, and the
 * graph-visible distant-terrain producer can be added without retaining live
 * chunks or leaking state across sessions.
 */
object DistantHorizonsCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:distant-horizons-2.4.4-b-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(
        FabricHostCapability.BLOCK_MUTATION_EVENTS,
        FabricHostCapability.CLIENT_PAYLOAD_CHANNELS,
        FabricHostCapability.CHUNK_EVENTS,
        FabricHostCapability.DISTANT_TERRAIN_LOD,
        FabricHostCapability.SCREENS,
        FabricHostCapability.WORLD_EVENTS,
        FabricHostCapability.WORLD_GENERATION,
    )
    override val functionality = FabricFunctionalityCatalog.DISTANT_HORIZONS

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "distanthorizons" &&
            metadata.version == "2.4.4-b" &&
            metadata.environment == "*" &&
            metadata.entrypoints == setOf("client", "modmenu", "server") &&
            metadata.provides == setOf("lod") &&
            metadata.mixins == 1 &&
            metadata.accessWidener == "distanthorizons.fabric.accesswidener" &&
            metadata.nestedJars == 10
    }

    /**
     * The pinned upstream artifact rejects Iris <= 1.7.4 because its linked
     * Minecraft renderer integration is incompatible. Minosoft links neither
     * mod's bytecode: both exact artifacts activate through source-native,
     * owner-scoped adapters. Keep this exception exact so another DH/Iris pair
     * still fails closed at preflight.
     */
    override fun acceptsDependencyIssue(issue: FabricDependencyIssue): Boolean {
        return issue.owner.id == "distanthorizons" &&
            issue.owner.version == "2.4.4-b" &&
            issue.relation == FabricDependencyRelation.BREAKS &&
            issue.dependency.id == "iris" &&
            issue.providers.map { it.id to it.version } == listOf("iris" to "1.7.2+mc1.20.4")
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) {
            "Unsupported Distant Horizons artifact: ${probe.metadata.version}"
        }
        val options = DistantHorizonsOptions.persisted()
        val persistenceRoot = ProfileOptions.path.resolve("distant-horizons")
            .takeIf { ProfileOptions.saving }
        val controller = DistantHorizonsLodController(options, persistenceRoot)
        scope.own(controller)
        scope.own(ClientDebugChannel.register(DistantHorizonsDebugProvider(controller, options)))
        scope.own(
            FabricSettings.register(
                id,
                minosoft("distant_horizons_options"),
                options.schema(),
            ),
        )
        scope.own(
            FabricRendererRegistry.register(id, DistantHorizonsRendererHookBuilder(controller, options)),
        )
        scope.own(FabricChunkEvents.register(id, controller::onChunk))
        scope.own(FabricBlockMutationEvents.register(id, controller::onBlockMutation))
        scope.own(
            FabricClientPayloadChannels.register(id, DistantLodProtocol.CHANNEL, controller::onNetworkPayload),
        )
        scope.own(
            FabricWorldEvents.register(id, FabricWorldEventPhase.JOINED, controller::onWorldJoined),
        )
        scope.own(
            FabricWorldEvents.register(id, FabricWorldEventPhase.LEFT, controller::onWorldLeft),
        )
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "DISTANT_HORIZONS_LOD_ACTIVE version=${probe.metadata.version} " +
                "source=explored+generated+network storage=versioned-persistent renderer=detached-tile-abi"
        }
    }
}

internal data class DistantLodColumn(
    val surfaceY: Int,
    val material: ResourceLocation?,
    val solidY: Int = surfaceY,
    val solidMaterial: ResourceLocation? = material,
) {
    init {
        require(surfaceY == Int.MIN_VALUE || surfaceY in -MAXIMUM_WORLD_COORDINATE..MAXIMUM_WORLD_COORDINATE) {
            "Distant LOD surface height is out of bounds: $surfaceY"
        }
        require(solidY == Int.MIN_VALUE || solidY in -MAXIMUM_WORLD_COORDINATE..MAXIMUM_WORLD_COORDINATE) {
            "Distant LOD solid height is out of bounds: $solidY"
        }
    }

    private companion object {
        const val MAXIMUM_WORLD_COORDINATE = 30_000_000
    }
}

/**
 * Immutable 16x16 explored-surface snapshot. Column index is `(z << 4) | x`.
 */
internal class DistantLodTile private constructor(
    val position: ChunkPosition,
    private val heights: IntArray,
    private val materials: Array<ResourceLocation?>,
    private val solidHeights: IntArray,
    private val solidMaterials: Array<ResourceLocation?>,
) {
    init {
        require(position.x in -MAXIMUM_CHUNK_COORDINATE..MAXIMUM_CHUNK_COORDINATE) {
            "Distant LOD tile x is out of bounds: ${position.x}"
        }
        require(position.z in -MAXIMUM_CHUNK_COORDINATE..MAXIMUM_CHUNK_COORDINATE) {
            "Distant LOD tile z is out of bounds: ${position.z}"
        }
        require(heights.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT heights, found ${heights.size}"
        }
        require(materials.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT materials, found ${materials.size}"
        }
        require(solidHeights.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT solid heights, found ${solidHeights.size}"
        }
        require(solidMaterials.size == COLUMN_COUNT) {
            "Distant LOD tile requires $COLUMN_COUNT solid materials, found ${solidMaterials.size}"
        }
    }

    operator fun get(x: Int, z: Int): DistantLodColumn {
        require(x in 0 until ChunkSize.SECTION_WIDTH_X) { "Distant LOD x is out of bounds: $x" }
        require(z in 0 until ChunkSize.SECTION_WIDTH_Z) { "Distant LOD z is out of bounds: $z" }
        val index = index(x, z)
        return DistantLodColumn(
            heights[index],
            materials[index],
            solidHeights[index],
            solidMaterials[index],
        )
    }

    fun update(
        columns: Set<Int>,
        sampler: (x: Int, z: Int) -> DistantLodColumn,
    ): DistantLodTile {
        if (columns.isEmpty()) return this
        val nextHeights = heights.copyOf()
        val nextMaterials = materials.copyOf()
        val nextSolidHeights = solidHeights.copyOf()
        val nextSolidMaterials = solidMaterials.copyOf()
        for (index in columns) {
            require(index in 0 until COLUMN_COUNT) { "Distant LOD column is out of bounds: $index" }
            val column = sampler(index and COLUMN_MASK, index ushr COLUMN_BITS)
            nextHeights[index] = column.surfaceY
            nextMaterials[index] = column.material
            nextSolidHeights[index] = column.solidY
            nextSolidMaterials[index] = column.solidMaterial
        }
        return DistantLodTile(
            position,
            nextHeights,
            nextMaterials,
            nextSolidHeights,
            nextSolidMaterials,
        )
    }

    companion object {
        const val COLUMN_COUNT = ChunkSize.SECTION_WIDTH_X * ChunkSize.SECTION_WIDTH_Z
        private const val COLUMN_BITS = 4
        private const val COLUMN_MASK = ChunkSize.SECTION_WIDTH_X - 1
        private const val MAXIMUM_CHUNK_COORDINATE = 30_000_000 / ChunkSize.SECTION_WIDTH_X

        fun capture(
            position: ChunkPosition,
            sampler: (x: Int, z: Int) -> DistantLodColumn,
        ): DistantLodTile {
            val heights = IntArray(COLUMN_COUNT)
            val materials = arrayOfNulls<ResourceLocation>(COLUMN_COUNT)
            val solidHeights = IntArray(COLUMN_COUNT)
            val solidMaterials = arrayOfNulls<ResourceLocation>(COLUMN_COUNT)
            for (z in 0 until ChunkSize.SECTION_WIDTH_Z) {
                for (x in 0 until ChunkSize.SECTION_WIDTH_X) {
                    val index = index(x, z)
                    val column = sampler(x, z)
                    heights[index] = column.surfaceY
                    materials[index] = column.material
                    solidHeights[index] = column.solidY
                    solidMaterials[index] = column.solidMaterial
                }
            }
            return DistantLodTile(
                position,
                heights,
                materials,
                solidHeights,
                solidMaterials,
            )
        }

        fun index(x: Int, z: Int): Int = (z shl COLUMN_BITS) or x
    }
}

/**
 * Bounded access-ordered store. Persistence and coarser aggregation are later
 * integration rungs; eviction prevents explored multiplayer worlds from
 * becoming an unbounded client allocation meanwhile.
 */
internal class DistantLodTileStore(
    private val maximumTiles: Int = DEFAULT_MAXIMUM_TILES,
) {
    init {
        require(maximumTiles > 0) { "Distant LOD tile limit must be positive" }
    }

    private val tiles = LinkedHashMap<ChunkPosition, DistantLodTile>(16, 0.75f, true)

    @Synchronized
    fun put(tile: DistantLodTile): List<ChunkPosition> {
        val evicted = ArrayList<ChunkPosition>(1)
        tiles[tile.position] = tile
        while (tiles.size > maximumTiles) {
            val eldest = tiles.entries.firstOrNull() ?: break
            tiles.remove(eldest.key)
            evicted += eldest.key
        }
        return evicted
    }

    @Synchronized
    operator fun get(position: ChunkPosition): DistantLodTile? = tiles[position]

    @Synchronized
    fun size(): Int = tiles.size

    @Synchronized
    fun snapshot(): List<DistantLodTile> = tiles.values.toList()

    @Synchronized
    fun clear() = tiles.clear()

    private companion object {
        const val DEFAULT_MAXIMUM_TILES = 4_096
    }
}

internal class DistantHorizonsLodController(
    private val options: DistantHorizonsOptions = DistantHorizonsOptions.inMemory(),
    private val persistenceRoot: Path? = null,
) : AutoCloseable {
    private inner class SessionState(
        val session: PlaySession,
    ) : AutoCloseable {
        val store = DistantLodTileStore(options.maximumTiles)
        val sources = HashMap<ChunkPosition, DistantLodTileSource>()
        private val persistence = persistenceRoot?.let {
            DistantLodPersistence(DistantLodPersistence.path(it, session), options.maximumTiles)
        }
        private val writer = persistence?.let(::DistantLodPersistenceWriter)
        private val generator = DistantUnexploredGenerator(
            session = session,
            options = options,
            contains = { store[it] != null },
            publish = { publish(it, DistantLodTileSource.LOCAL_GENERATION) },
        )
        private val network = DistantLodNetworkClient(
            session = session,
            options = options,
            contains = { store[it] != null },
            publish = { publish(it, DistantLodTileSource.NETWORK) },
        )
        private var closed = false

        init {
            if (options.persistenceEnabled) {
                try {
                    val loaded = persistence?.load().orEmpty()
                    loaded.forEach {
                        store.put(it).forEach(sources::remove)
                        sources[it.position] = DistantLodTileSource.PERSISTENCE
                    }
                    if (persistence != null) {
                        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                            "DISTANT_HORIZONS_DATABASE_LOADED tiles=${loaded.size} path=${persistence.path}"
                        }
                    }
                } catch (error: Throwable) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
                }
            }
            var generatorRegistered = false
            try {
                session.ticker += generator
                generatorRegistered = true
                session.ticker += network
            } catch (error: Throwable) {
                closed = true
                if (generatorRegistered) {
                    try {
                        session.ticker -= generator
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                try {
                    writer?.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        @Synchronized
        fun publish(tile: DistantLodTile, source: DistantLodTileSource) {
            if (closed) return
            store.put(tile).forEach(sources::remove)
            sources[tile.position] = source
            changed()
        }

        @Synchronized
        fun receive(message: DistantLodMessage) {
            if (closed) return
            network.receive(message)
        }

        private fun changed() {
            revision.incrementAndGet()
            if (options.persistenceEnabled) writer?.markDirty(store.snapshot())
        }

        @Synchronized
        fun snapshot(): DistantLodSnapshot = DistantLodSnapshot(
            revision = revision.get(),
            tiles = if (options.enabled) store.snapshot() else emptyList(),
            sources = sources.toMap(),
        )

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    val current = failure
                    if (current == null) {
                        failure = error
                    } else {
                        current.addSuppressed(error)
                    }
                }
            }

            cleanup { session.ticker -= generator }
            cleanup { session.ticker -= network }
            if (options.persistenceEnabled) cleanup { writer?.markDirty(store.snapshot()) }
            cleanup { writer?.close() }
            cleanup(store::clear)
            sources.clear()
            failure?.let { throw it }
        }
    }

    private val states = IdentityHashMap<PlaySession, SessionState>()
    private val networkHello = IdentityHashMap<PlaySession, DistantLodMessage.Hello>()
    private val renderDiagnostics = IdentityHashMap<PlaySession, DistantLodRenderDiagnostics>()
    private val revision = AtomicLong()
    @Volatile private var presentationOverride: Boolean? = null
    @Volatile private var closed = false

    fun onWorldJoined(context: FabricWorldEventContext) {
        if (closed) return
        synchronized(states) {
            if (closed) return
            states.remove(context.session)?.close()
            states[context.session] = SessionState(context.session).also { state ->
                networkHello[context.session]?.let(state::receive)
            }
        }
        revision.incrementAndGet()
    }

    fun onChunk(context: FabricChunkEventContext) {
        if (closed) return
        when (context.phase) {
            FabricChunkEventPhase.CREATED,
            FabricChunkEventPhase.UPDATED,
            -> context.chunk?.let { chunk ->
                state(context.session).publish(capture(chunk), DistantLodTileSource.NATIVE)
            }

            // Retaining an immutable explored tile after native chunk unload is
            // the purpose of this first LOD boundary.
            FabricChunkEventPhase.UNLOADED,
            FabricChunkEventPhase.CLEARED,
            -> Unit
        }
    }

    fun onBlockMutation(context: FabricBlockMutationContext) {
        if (closed || context.changes.isEmpty()) return
        val state = state(context.session)
        val store = state.store
        val previous = store[context.chunk.position]
        if (previous == null) {
            state.publish(capture(context.chunk), DistantLodTileSource.NATIVE)
            return
        }
        val columns = context.changes.mapTo(linkedSetOf()) {
            DistantLodTile.index(it.position.x and 0x0F, it.position.z and 0x0F)
        }
        state.publish(update(context.chunk, previous, columns), DistantLodTileSource.NATIVE)
    }

    fun onNetworkPayload(context: FabricClientPayloadContext) {
        if (closed || !options.networkTransferEnabled) return
        try {
            val message = DistantLodProtocol.decode(context.copyPayload())
            synchronized(states) {
                if (closed) return
                if (message is DistantLodMessage.Hello) {
                    // A server can advertise immediately after login, before a
                    // registry-driven world reconfiguration finishes. Retain
                    // the negotiated bounds across that same-session world
                    // transition so the final world state can begin requests.
                    networkHello[context.session] = message
                }
                val current = states[context.session]
                if (current != null) {
                    current.receive(message)
                } else if (message !is DistantLodMessage.Hello) {
                    states.getOrPut(context.session) { SessionState(context.session) }.receive(message)
                }
            }
        } catch (error: Throwable) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
        }
    }

    fun onWorldLeft(context: FabricWorldEventContext) {
        synchronized(states) {
            states.remove(context.session)?.close()
            renderDiagnostics.remove(context.session)
            if (context.cause == FabricWorldChangeCause.DISCONNECT) {
                networkHello.remove(context.session)
            }
        }
        revision.incrementAndGet()
    }

    internal fun tile(session: PlaySession, position: ChunkPosition): DistantLodTile? =
        synchronized(states) { states[session]?.store?.get(position) }

    internal fun tileCount(session: PlaySession): Int =
        synchronized(states) { states[session]?.store?.size() ?: 0 }

    internal fun revision(): Long = revision.get()

    internal fun presentationEnabled(): Boolean = presentationOverride ?: options.enabled

    internal fun presentationOverride(): Boolean? = presentationOverride

    internal fun setPresentationOverride(value: Boolean?): Boolean? {
        val previous = presentationOverride
        presentationOverride = value
        if (previous != value) revision.incrementAndGet()
        return previous
    }

    internal fun publishRenderDiagnostics(session: PlaySession, diagnostics: DistantLodRenderDiagnostics) {
        synchronized(states) {
            if (!closed && states.containsKey(session)) {
                renderDiagnostics[session] = diagnostics
            }
        }
    }

    internal fun renderDiagnostics(session: PlaySession): DistantLodRenderDiagnostics? =
        synchronized(states) { renderDiagnostics[session] }

    internal fun snapshot(session: PlaySession): DistantLodSnapshot = synchronized(states) {
        states[session]?.snapshot() ?: DistantLodSnapshot(
            revision = revision.get(),
            tiles = emptyList(),
            sources = emptyMap(),
        )
    }

    private fun state(session: PlaySession): SessionState = synchronized(states) {
        check(!closed) { "Distant Horizons LOD controller is closed" }
        states.getOrPut(session) { SessionState(session) }
    }

    private fun capture(chunk: Chunk): DistantLodTile = chunk.lock.acquired {
        DistantLodTile.capture(chunk.position) { x, z -> sample(chunk, x, z) }
    }

    private fun update(
        chunk: Chunk,
        previous: DistantLodTile,
        columns: Set<Int>,
    ): DistantLodTile = chunk.lock.acquired {
        previous.update(columns) { x, z -> sample(chunk, x, z) }
    }

    private fun sample(chunk: Chunk, x: Int, z: Int): DistantLodColumn {
        val height = chunk.light.heightmap[x, z]
        if (height == Int.MIN_VALUE) return DistantLodColumn(Int.MIN_VALUE, null)
        val dimension = chunk.world.dimension
        val top = height.coerceIn(dimension.minY, dimension.maxY)
        var surfaceY = top
        var state = chunk[InChunkPosition(x, surfaceY, z)]
        while (state == null && surfaceY > dimension.minY) {
            surfaceY--
            state = chunk[InChunkPosition(x, surfaceY, z)]
        }
        val material = state?.block?.identifier
            ?: return DistantLodColumn(Int.MIN_VALUE, null)
        if (!material.isDistantWaterMaterial()) {
            return DistantLodColumn(surfaceY, material)
        }

        var solidY = surfaceY - 1
        var solidMaterial: ResourceLocation? = null
        while (solidY >= dimension.minY) {
            val candidate = chunk[InChunkPosition(x, solidY, z)]?.block?.identifier
            if (candidate != null && !candidate.isDistantWaterMaterial()) {
                solidMaterial = candidate
                break
            }
            solidY--
        }
        if (solidMaterial == null) solidY = Int.MIN_VALUE
        return DistantLodColumn(surfaceY, material, solidY, solidMaterial)
    }

    override fun close() {
        var failure: Throwable? = null
        synchronized(states) {
            if (closed) return
            closed = true
            for (state in states.values) {
                try {
                    state.close()
                } catch (error: Throwable) {
                    val current = failure
                    if (current == null) {
                        failure = error
                    } else {
                        current.addSuppressed(error)
                    }
                }
            }
            states.clear()
            networkHello.clear()
            renderDiagnostics.clear()
        }
        revision.incrementAndGet()
        failure?.let { throw it }
    }
}

internal data class DistantLodSnapshot(
    val revision: Long,
    val tiles: List<DistantLodTile>,
    val sources: Map<ChunkPosition, DistantLodTileSource>,
)

internal enum class DistantLodTileSource(val wireName: String) {
    NATIVE("native"),
    PERSISTENCE("persistence"),
    LOCAL_GENERATION("local-generation"),
    NETWORK("network"),
}
