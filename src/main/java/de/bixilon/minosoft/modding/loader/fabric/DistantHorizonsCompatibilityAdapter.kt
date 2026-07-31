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
import de.bixilon.minosoft.data.registries.blocks.state.BlockStateFlags
import de.bixilon.minosoft.data.registries.blocks.types.fluid.FluidHolder
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.debug.ClientDebugChannel
import de.bixilon.minosoft.data.world.chunk.chunk.Chunk
import de.bixilon.minosoft.data.world.positions.ChunkPosition
import de.bixilon.minosoft.data.world.positions.InChunkPosition
import de.bixilon.minosoft.gui.rendering.terrain.distant.DistantTerrainRendererBuilder
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantLodColumn
import de.bixilon.minosoft.terrain.distant.DistantLodChanges
import de.bixilon.minosoft.terrain.distant.DistantLodChangeJournal
import de.bixilon.minosoft.terrain.distant.DistantLodRenderDiagnostics
import de.bixilon.minosoft.terrain.distant.DistantLodSnapshot
import de.bixilon.minosoft.terrain.distant.DistantLodTile
import de.bixilon.minosoft.terrain.distant.DistantLodTileSource
import de.bixilon.minosoft.terrain.distant.DistantLodTileStore
import de.bixilon.minosoft.terrain.distant.DistantLodUpdate
import de.bixilon.minosoft.terrain.distant.DistantCompatibilityMaterial
import de.bixilon.minosoft.terrain.distant.DistantCompatibilityMaterialResolver
import de.bixilon.minosoft.terrain.distant.DistantWorldVerticalSampler
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantFluidSample
import de.bixilon.minosoft.terrain.distant.hierarchy.DistantVerticalPage
import de.bixilon.minosoft.terrain.distant.migrateTopOnlyTiles
import de.bixilon.minosoft.terrain.distant.toTopOnlyCompatibilityTile
import de.bixilon.minosoft.terrain.distant.store.DistantDirectoryTerrainStore
import de.bixilon.minosoft.terrain.distant.store.DistantTerrainStoreIdentity
import de.bixilon.minosoft.terrain.distant.store.DistantTerrainStoreWriter
import de.bixilon.minosoft.terrain.distant.store.DistantTerrainStoreInspection
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderSource
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
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
            FabricRendererRegistry.register(
                id,
                DistantTerrainRendererBuilder(options, controller::renderSource),
            ),
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

internal class DistantHorizonsLodController(
    private val options: DistantHorizonsOptions = DistantHorizonsOptions.inMemory(),
    private val persistenceRoot: Path? = null,
) : AutoCloseable {
    private inner class SessionState(
        val session: PlaySession,
    ) : AutoCloseable {
        val store = DistantLodTileStore(options.maximumTiles)
        val sources = HashMap<ChunkPosition, DistantLodTileSource>()
        val verticalPages = HashMap<ChunkPosition, DistantVerticalPage>()
        private val changeJournal = DistantLodChangeJournal(MAX_CHANGE_POSITIONS)
        private val persistence = persistenceRoot?.takeIf { options.persistenceEnabled }?.let {
            DistantLodPersistence(DistantLodPersistence.path(it, session), options.maximumTiles)
        }
        private val pageStore = persistence?.let {
            DistantDirectoryTerrainStore(
                it.path.resolveSibling("${it.path.fileName}.pages"),
                DistantTerrainStoreIdentity(
                    worldIdentity = "${session.version.name}\u0000${session.connection.identifier}\u0000${session.world.name}",
                    normalizedLevelKey = session.world.name?.toString() ?: "minosoft:unknown",
                ),
                maximumPages = options.maximumTiles,
            )
        }
        private val pageWriter = pageStore?.let(::DistantTerrainStoreWriter)
        private val generator = DistantUnexploredGenerator(
            session = session,
            options = options,
            contains = { store[it] != null },
            publish = { tile, page -> publish(tile, DistantLodTileSource.LOCAL_GENERATION, page) },
        )
        private val network = DistantLodNetworkClient(
            session = session,
            options = options,
            contains = { store[it] != null },
            localSourceRevision = { verticalPages[it]?.sourceRevision },
            publishTile = { publish(it, DistantLodTileSource.NETWORK) },
            publishPage = { tile, page -> publish(tile, DistantLodTileSource.NETWORK, page) },
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
                    pageStore?.load(session.world.terrainEpoch).orEmpty().forEach { page ->
                        revision.accumulateAndGet(page.sourceRevision) { current, loadedRevision ->
                            maxOf(current, loadedRevision)
                        }
                        val position = ChunkPosition(page.key.x.toInt(), page.key.z.toInt())
                        store.put(page.toTopOnlyCompatibilityTile()).forEach { evicted ->
                            sources.remove(evicted)
                            verticalPages.remove(evicted)
                        }
                        verticalPages[position] = page
                        sources[position] = DistantLodTileSource.PERSISTENCE
                    }
                    val migratedPages = migrateTopOnlyTiles(
                        tiles = store.snapshot(),
                        existingPositions = verticalPages.keys,
                        resolver = compatibilityResolver(),
                        worldEpoch = session.world.terrainEpoch,
                        originY = session.world.dimension.minY,
                        nextSourceRevision = revision::incrementAndGet,
                        persist = { page -> pageStore?.write(page) },
                    )
                    for (page in migratedPages) {
                        val position = ChunkPosition(page.key.x.toInt(), page.key.z.toInt())
                        verticalPages[position] = page
                    }
                    if (persistence != null) {
                        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                            "DISTANT_HORIZONS_DATABASE_LOADED tiles=${loaded.size} migratedPages=${migratedPages.size} path=${persistence.path}"
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
                    pageWriter?.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        @Synchronized
        fun publish(
            tile: DistantLodTile,
            source: DistantLodTileSource,
            verticalPage: DistantVerticalPage? = null,
        ) {
            if (closed) return
            require(verticalPage == null ||
                verticalPage.key.domain == TerrainDomain.DISTANT &&
                verticalPage.key.detailLevel == 0 &&
                verticalPage.key.y == 0L &&
                verticalPage.key.x == tile.position.x.toLong() &&
                verticalPage.key.z == tile.position.z.toLong() &&
                verticalPage.key.worldEpoch == session.world.terrainEpoch
            ) { "Distant vertical page does not match its compatibility tile and active world" }
            val publicationRevision = revision.incrementExact()
            val publishedPage = verticalPage?.let {
                DistantVerticalPage(
                    key = it.key,
                    width = it.width,
                    originY = it.originY,
                    sourceRevision = publicationRevision,
                    completeness = it.completeness,
                    columns = it.columns,
                )
            }
            session.player.physics.positionInfo.chunkPosition.let { center ->
                pageStore?.updateRetentionCenter(center.x.toLong(), center.z.toLong())
            }
            store.put(tile).forEach { evicted ->
                sources.remove(evicted)
                verticalPages.remove(evicted)
                recordChange(evicted, publicationRevision)
            }
            sources[tile.position] = source
            if (publishedPage == null) {
                verticalPages.remove(tile.position)
            } else {
                verticalPages[tile.position] = publishedPage
                if (options.persistenceEnabled) pageWriter?.let { writer ->
                    if (!writer.markDirty(publishedPage)) {
                        Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                            "Distant page writer queue is saturated; retaining the page in memory"
                        }
                    }
                }
            }
            recordChange(tile.position, publicationRevision)
        }

        @Synchronized
        fun receive(message: DistantLodMessage) {
            if (closed) return
            network.receive(message)
        }

        @Synchronized
        fun receive(message: DistantTerrainMessageV2) {
            if (closed) return
            network.receive(message)
        }

        @Synchronized
        fun snapshot(): DistantLodSnapshot = DistantLodSnapshot(
            revision = revision.get(),
            tiles = if (options.enabled) store.snapshot() else emptyList(),
            sources = sources.toMap(),
            verticalPages = if (options.enabled) verticalPages.toMap() else emptyMap(),
        )

        @Synchronized
        fun changesSince(previousRevision: Long): DistantLodChanges {
            val currentRevision = revision.get()
            val window = changeJournal.changesSince(previousRevision, currentRevision)
            if (!options.enabled || window.reset) {
                return DistantLodChanges.reset(snapshot())
            }
            val updates = ArrayList<DistantLodUpdate>()
            val removals = linkedSetOf<ChunkPosition>()
            window.positions.forEach { position ->
                val tile = store[position]
                if (tile == null) {
                    removals += position
                } else {
                    updates += DistantLodUpdate(
                        tile,
                        sources[position] ?: DistantLodTileSource.NATIVE,
                        verticalPages[position],
                    )
                }
            }
            return DistantLodChanges(currentRevision, reset = false, updates, removals)
        }

        @Synchronized
        fun storeInspection(): DistantTerrainStoreInspection? = pageStore?.inspect()

        @Synchronized
        fun networkInspection(): DistantLodNetworkClient.Inspection = network.inspect()

        @Synchronized
        fun close(sendNetworkCancellation: Boolean) {
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

            cleanup { network.close(sendNetworkCancellation) }
            cleanup { session.ticker -= generator }
            cleanup { session.ticker -= network }
            if (options.persistenceEnabled) cleanup { pageWriter?.close() }
            cleanup(store::clear)
            sources.clear()
            verticalPages.clear()
            changeJournal.clear()
            failure?.let { throw it }
        }

        override fun close() = close(sendNetworkCancellation = false)

        private fun recordChange(position: ChunkPosition, publicationRevision: Long) {
            changeJournal.record(position, publicationRevision)
        }

        private fun compatibilityResolver() = DistantCompatibilityMaterialResolver { identifier ->
            if (identifier in AIR_IDENTIFIERS) return@DistantCompatibilityMaterialResolver null
            val semantic = TerrainSemanticMaterialId(identifier.toString())
            val block = session.registries.block[identifier]
                ?: return@DistantCompatibilityMaterialResolver DistantCompatibilityMaterial(semantic, opaque = false)
            val state = block.states.default
            val fluid = (block as? FluidHolder)?.fluid?.let { source ->
                DistantFluidSample(
                    material = TerrainSemanticMaterialId(source.identifier.toString()),
                    level = 0,
                    classification = source.identifier.toString(),
                )
            }
            DistantCompatibilityMaterial(
                semanticMaterial = semantic,
                opaque = BlockStateFlags.FULL_OPAQUE in state.flags,
                fluid = fluid,
            )
        }
    }

    private val states = IdentityHashMap<PlaySession, SessionState>()
    private val networkHello = IdentityHashMap<PlaySession, DistantLodMessage.Hello>()
    private val renderDiagnostics = IdentityHashMap<PlaySession, DistantLodRenderDiagnostics>()
    private val revision = AtomicLong()
    @Volatile private var presentationOverride: Boolean? = null
    @Volatile private var closed = false

    private companion object {
        const val MAX_CHANGE_POSITIONS = 4_096
        val AIR_IDENTIFIERS = setOf(
            ResourceLocation.of("minecraft:air"),
            ResourceLocation.of("minecraft:cave_air"),
            ResourceLocation.of("minecraft:void_air"),
        )
    }

    fun onWorldJoined(context: FabricWorldEventContext) {
        if (closed) return
        val replacement = SessionState(context.session)
        val hello = synchronized(states) { networkHello[context.session] }
        hello?.let(replacement::receive)
        val previous = synchronized(states) {
            if (closed) null else states.put(context.session, replacement)
        }
        if (closed && existingState(context.session) !== replacement) {
            replacement.close(sendNetworkCancellation = false)
            return
        }
        previous?.close(sendNetworkCancellation = true)
        revision.incrementExact()
    }

    fun onChunk(context: FabricChunkEventContext) {
        if (closed) return
        when (context.phase) {
            FabricChunkEventPhase.CREATED,
            FabricChunkEventPhase.UPDATED,
            -> context.chunk?.let { chunk ->
                val (tile, page) = captureNative(chunk)
                state(context.session).publish(tile, DistantLodTileSource.NATIVE, page)
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
            val (tile, page) = captureNative(context.chunk)
            state.publish(tile, DistantLodTileSource.NATIVE, page)
            return
        }
        val columns = context.changes.mapTo(linkedSetOf()) {
            DistantLodTile.index(it.position.x and 0x0F, it.position.z and 0x0F)
        }
        val previousPage = state.verticalPages[context.chunk.position]
        val (tile, page) = updateNative(context.chunk, previous, previousPage, columns)
        state.publish(tile, DistantLodTileSource.NATIVE, page)
    }

    fun onNetworkPayload(context: FabricClientPayloadContext) {
        if (closed || !options.networkTransferEnabled) return
        try {
            val message = DistantLodProtocol.decodeNegotiated(context.copyPayload())
            val current = synchronized(states) {
                if (closed) return
                if (message is DistantLodMessage.Hello) {
                    // A server can advertise immediately after login, before a
                    // registry-driven world reconfiguration finishes. Retain
                    // the negotiated bounds across that same-session world
                    // transition so the final world state can begin requests.
                    networkHello[context.session] = message
                }
                states[context.session]
            }
            when (message) {
                is DistantLodMessage -> {
                    if (current != null) current.receive(message)
                    else if (message !is DistantLodMessage.Hello) state(context.session).receive(message)
                }
                is DistantTerrainMessageV2 -> (current ?: state(context.session)).receive(message)
                else -> error("Unknown distant negotiated message")
            }
        } catch (error: Throwable) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
        }
    }

    fun onWorldLeft(context: FabricWorldEventContext) {
        val removed = synchronized(states) {
            val removed = states.remove(context.session)
            renderDiagnostics.remove(context.session)
            if (context.cause == FabricWorldChangeCause.DISCONNECT) {
                networkHello.remove(context.session)
            }
            removed
        }
        removed?.close(sendNetworkCancellation = context.cause != FabricWorldChangeCause.DISCONNECT)
        revision.incrementExact()
    }

    internal fun tile(session: PlaySession, position: ChunkPosition): DistantLodTile? =
        existingState(session)?.store?.get(position)

    internal fun tileCount(session: PlaySession): Int =
        existingState(session)?.store?.size() ?: 0

    internal fun revision(): Long = revision.get()

    internal fun presentationEnabled(): Boolean = presentationOverride ?: options.enabled

    internal fun presentationOverride(): Boolean? = presentationOverride

    internal fun setPresentationOverride(value: Boolean?): Boolean? {
        val previous = presentationOverride
        presentationOverride = value
        if (previous != value) revision.incrementExact()
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

    internal fun snapshot(session: PlaySession): DistantLodSnapshot =
        existingState(session)?.snapshot() ?: DistantLodSnapshot(
            revision = revision.get(),
            tiles = emptyList(),
            sources = emptyMap(),
        )

    internal fun storeInspection(session: PlaySession): DistantTerrainStoreInspection? =
        existingState(session)?.storeInspection()

    internal fun networkInspection(session: PlaySession): DistantLodNetworkClient.Inspection? =
        existingState(session)?.networkInspection()

    internal fun renderSource(session: PlaySession): DistantTerrainRenderSource =
        object : DistantTerrainRenderSource {
            override val revision: Long
                get() = this@DistantHorizonsLodController.revision()

            override val presentationEnabled: Boolean
                get() = this@DistantHorizonsLodController.presentationEnabled()

            override fun snapshot(): DistantLodSnapshot =
                this@DistantHorizonsLodController.snapshot(session)

            override fun changesSince(revision: Long): DistantLodChanges =
                existingState(session)?.changesSince(revision)
                    ?: DistantLodChanges.reset(this@DistantHorizonsLodController.snapshot(session))

            override fun publishDiagnostics(diagnostics: DistantLodRenderDiagnostics) {
                this@DistantHorizonsLodController.publishRenderDiagnostics(session, diagnostics)
            }
        }

    private fun existingState(session: PlaySession): SessionState? = synchronized(states) { states[session] }

    private fun state(session: PlaySession): SessionState {
        synchronized(states) {
            check(!closed) { "Distant Horizons LOD controller is closed" }
            states[session]?.let { return it }
        }
        val created = SessionState(session)
        val selected = synchronized(states) {
            if (closed) null else states[session] ?: created.also { states[session] = it }
        }
        if (selected !== created) created.close(sendNetworkCancellation = false)
        return selected ?: throw IllegalStateException("Distant Horizons LOD controller is closed")
    }

    private fun capture(chunk: Chunk): DistantLodTile = chunk.lock.acquired {
        DistantLodTile.capture(chunk.position) { x, z -> sample(chunk, x, z) }
    }

    private fun captureNative(chunk: Chunk): Pair<DistantLodTile, DistantVerticalPage> = chunk.lock.acquired {
        DistantLodTile.capture(chunk.position) { x, z -> sample(chunk, x, z) } to
            DistantWorldVerticalSampler.captureObservedLocked(chunk)
    }

    private fun updateNative(
        chunk: Chunk,
        previous: DistantLodTile,
        previousPage: DistantVerticalPage?,
        columns: Set<Int>,
    ): Pair<DistantLodTile, DistantVerticalPage> = chunk.lock.acquired {
        previous.update(columns) { x, z -> sample(chunk, x, z) } to
            if (previousPage == null) {
                DistantWorldVerticalSampler.captureObservedLocked(chunk)
            } else {
                DistantWorldVerticalSampler.updateObservedLocked(chunk, previousPage, columns)
            }
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
        if (!state.isDistantFluidState()) {
            return DistantLodColumn(surfaceY, material)
        }

        var solidY = surfaceY - 1
        var solidMaterial: ResourceLocation? = null
        while (solidY >= dimension.minY) {
            val candidateState = chunk[InChunkPosition(x, solidY, z)]
            val candidate = candidateState?.block?.identifier
            if (candidate != null && !candidateState.isDistantFluidState()) {
                solidMaterial = candidate
                break
            }
            solidY--
        }
        if (solidMaterial == null) solidY = Int.MIN_VALUE
        return DistantLodColumn(surfaceY, material, solidY, solidMaterial)
    }

    private fun de.bixilon.minosoft.data.registries.blocks.state.BlockState?.isDistantFluidState(): Boolean =
        this != null && (block is FluidHolder || BlockStateFlags.WATERLOGGED in flags)

    override fun close() {
        val removed = synchronized(states) {
            if (closed) return
            closed = true
            val removed = states.values.toList()
            states.clear()
            networkHello.clear()
            renderDiagnostics.clear()
            removed
        }
        var failure: Throwable? = null
        for (state in removed) {
            try {
                state.close()
            } catch (error: Throwable) {
                val current = failure
                if (current == null) failure = error else current.addSuppressed(error)
            }
        }
        revision.incrementExact()
        failure?.let { throw it }
    }
}

private fun AtomicLong.incrementExact(): Long = updateAndGet { Math.incrementExact(it) }
