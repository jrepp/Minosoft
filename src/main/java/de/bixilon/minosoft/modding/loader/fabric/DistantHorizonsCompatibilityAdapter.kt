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
import de.bixilon.kutil.concurrent.lock.LockUtil.locked
import de.bixilon.kutil.concurrent.pool.ThreadPool
import de.bixilon.kutil.concurrent.pool.io.DefaultIOPool
import de.bixilon.kutil.concurrent.pool.runnable.ThreadPoolRunnable
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
import de.bixilon.minosoft.local.LocalConnection
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantLodColumn
import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
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
import de.bixilon.minosoft.terrain.distant.store.distantTerrainWorldIdentity
import de.bixilon.minosoft.terrain.distant.network.DistantTerrainMessageV2
import de.bixilon.minosoft.terrain.distant.DistantTerrainRenderSource
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.IdentityHashMap
import java.util.ArrayDeque
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Exact-artifact adapter for the first source-native Distant Horizons slice.
 *
 * Upstream client bytecode is not linked. The adapter captures normalized
 * explored pages behind owned world/chunk hooks without retaining live chunks
 * or leaking state across sessions.
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
                "source=explored+generated+network storage=versioned-persistent " +
                "renderer=provider-neutral-page-hierarchy"
        }
    }
}

internal fun isCurrentDistantPublication(
    tile: DistantLodTile,
    page: DistantVerticalPage,
    worldEpoch: Long,
): Boolean {
    require(
        page.key.domain == TerrainDomain.DISTANT &&
            page.key.detailLevel == 0 &&
            page.key.y == 0L &&
            page.key.x == tile.position.x.toLong() &&
            page.key.z == tile.position.z.toLong(),
    ) { "Distant vertical page does not match its compatibility tile" }
    return page.key.worldEpoch == worldEpoch
}

internal data class DistantNetworkPayloadInspection(
    val pendingPayloads: Int,
    val pendingBytes: Int,
    val droppedPayloads: Long,
)

internal class DistantHorizonsLodController(
    private val options: DistantHorizonsOptions = DistantHorizonsOptions.inMemory(),
    private val persistenceRoot: Path? = null,
    private val persistenceHydrationDispatcher: ((() -> Unit) -> Unit) = { task ->
        DefaultIOPool += ThreadPoolRunnable(
            forcePool = true,
            priority = ThreadPool.Priorities.LOW,
            runnable = task,
        )
    },
    private val networkPayloadDispatcher: ((() -> Unit) -> Unit) = { task ->
        DefaultIOPool += ThreadPoolRunnable(
            forcePool = true,
            priority = ThreadPool.Priorities.HIGH,
            runnable = task,
        )
    },
) : AutoCloseable {
    private sealed interface NegotiatedHello {
        data class V1(val message: DistantLodMessage.Hello) : NegotiatedHello
        data class V2(val message: DistantTerrainMessageV2.Hello) : NegotiatedHello
    }

    private data class PendingNetworkPayload(
        val session: PlaySession,
        val payload: ByteArray,
    )

    private inner class SessionState(
        val session: PlaySession,
    ) : AutoCloseable {
        private val residentPageLimit = minOf(options.maximumTiles, MAX_RESIDENT_PAGES)
        val store = DistantLodTileStore(residentPageLimit)
        val sources = HashMap<ChunkPosition, DistantLodTileSource>()
        val verticalPages = HashMap<ChunkPosition, DistantVerticalPage>()
        private val changeJournal = DistantLodChangeJournal(MAX_CHANGE_POSITIONS)
        // A server address and dimension do not identify a remote level after an operator replaces
        // its world. Reusing that store produces convincing but false detached terrain. Persist only
        // when the connection supplied a stable terrain fingerprint; unfingerprinted sessions still
        // retain and render pages for their current lifetime.
        private val persistence = persistenceRoot?.takeIf {
            options.persistenceEnabled && session.connection is LocalConnection &&
                session.world.terrainPersistenceFingerprint != null
        }?.let {
            DistantLodPersistence(DistantLodPersistence.path(it, session), options.maximumTiles)
        }
        private var persistenceHydrating = persistence != null
        private var residentCenter: ChunkPosition? = null
        private var requestedResidentCenter: ChunkPosition? = null
        private var residentRefreshScheduled = false
        @Volatile private var pageStore: DistantDirectoryTerrainStore? = null
        @Volatile private var pageWriter: DistantTerrainStoreWriter? = null
        private val pendingPersistencePages = linkedMapOf<ChunkPosition, DistantVerticalPage>()
        private val residentPersistence = Runnable(::refreshResidentPersistenceIfMoved)
        private val generator = DistantUnexploredGenerator(
            session = session,
            options = options,
            maximumResidentTiles = residentPageLimit,
            contains = ::containsCanonicalPage,
            publish = { tile, page -> publish(tile, DistantLodTileSource.LOCAL_GENERATION, page) },
        )
        private val network = DistantLodNetworkClient(
            session = session,
            options = options,
            maximumResidentTiles = residentPageLimit,
            contains = ::containsCanonicalPage,
            localSourceRevision = ::completeSourceRevision,
            publishTile = { publish(it, DistantLodTileSource.NETWORK) },
            publishPage = { tile, page -> publish(tile, DistantLodTileSource.NETWORK, page) },
        )
        @Volatile private var closed = false

        init {
            var residentPersistenceRegistered = false
            var generatorRegistered = false
            var networkRegistered = false
            try {
                session.ticker += residentPersistence
                residentPersistenceRegistered = true
                session.ticker += generator
                generatorRegistered = true
                session.ticker += network
                networkRegistered = true
                if (persistence != null) {
                    persistenceHydrationDispatcher(::hydratePersistence)
                }
            } catch (error: Throwable) {
                closed = true
                if (networkRegistered) {
                    try {
                        session.ticker -= network
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                if (generatorRegistered) {
                    try {
                        session.ticker -= generator
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                if (residentPersistenceRegistered) {
                    try {
                        session.ticker -= residentPersistence
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

        /**
         * Persistence is deliberately hydrated away from the world/join and
         * payload threads. Large explored worlds contain thousands of records;
         * decoding them inline starves keepalives and can disconnect the client.
         */
        private fun hydratePersistence() {
            val legacyPersistence = persistence ?: return
            var localPageStore: DistantDirectoryTerrainStore? = null
            var localWriter: DistantTerrainStoreWriter? = null
            try {
                if (isClosed()) return
                val loadedTiles = legacyPersistence.load()
                val installedTiles = installPersistentTiles(loadedTiles)
                if (isClosed()) return

                val initializedPageStore = DistantDirectoryTerrainStore(
                    legacyPersistence.path.resolveSibling("${legacyPersistence.path.fileName}.pages"),
                    DistantTerrainStoreIdentity(
                        worldIdentity = distantTerrainWorldIdentity(
                            session.version.name,
                            session.connection.identifier,
                            session.world.name?.toString() ?: "minosoft:unknown",
                            session.world.terrainPersistenceFingerprint,
                        ),
                        normalizedLevelKey = session.world.name?.toString() ?: "minosoft:unknown",
                    ),
                    maximumPages = options.maximumTiles,
                )
                localPageStore = initializedPageStore
                val center = session.player.physics.positionInfo.chunkPosition
                var installedPages = 0
                val loadedPages = initializedPageStore.loadNearestInBatches(
                    worldEpoch = session.world.terrainEpoch,
                    centerX = center.x.toLong(),
                    centerZ = center.z.toLong(),
                    maximumLoadedPages = residentPageLimit,
                    maximumEncodedBytes = MAX_RESIDENT_ENCODED_BYTES,
                    maximumBatchPages = PERSISTENCE_INSTALL_BATCH,
                ) consume@{ pages ->
                    if (isClosed()) return@consume false
                    pages.maxOfOrNull(DistantVerticalPage::sourceRevision)?.let { loadedRevision ->
                        revision.accumulateAndGet(loadedRevision, ::maxOf)
                    }
                    installedPages = Math.addExact(installedPages, installPersistentPages(pages))
                    !isClosed()
                }
                if (isClosed()) return

                val migrationCandidates = synchronized(this) {
                    store.snapshot().filter { tile ->
                        sources[tile.position] == DistantLodTileSource.PERSISTENCE &&
                            tile.position !in verticalPages
                    }
                }
                val migratedPages = migrateTopOnlyTiles(
                    tiles = migrationCandidates,
                    existingPositions = emptySet(),
                    resolver = compatibilityResolver(),
                    worldEpoch = session.world.terrainEpoch,
                    originY = session.world.dimension.minY,
                    nextSourceRevision = revision::incrementExact,
                    persist = { page ->
                        if (!isClosed() && shouldPersistMigration(page)) initializedPageStore.write(page)
                    },
                )
                val installedMigrations = installPersistentPages(migratedPages)
                if (isClosed()) return

                localWriter = DistantTerrainStoreWriter(initializedPageStore, options.maximumTiles)
                synchronized(this) {
                    if (closed) return
                    for (page in pendingPersistencePages.values) {
                        check(localWriter.markDirty(page)) {
                            "Distant page writer rejected a page queued during persistence hydration"
                        }
                    }
                    pendingPersistencePages.clear()
                    pageStore = localPageStore
                    pageWriter = localWriter
                    residentCenter = center
                }
                // Ownership moved into the session state. Clear the local
                // cleanup handles before diagnostics, which must not be able
                // to close the active writer if logging itself fails.
                localPageStore = null
                localWriter = null
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "DISTANT_HORIZONS_DATABASE_LOADED tiles=${loadedTiles.size} installedTiles=$installedTiles " +
                        "records=${initializedPageStore.inspect().recordCount} residentPages=$loadedPages " +
                        "installedPages=$installedPages " +
                        "migratedPages=$installedMigrations path=${legacyPersistence.path}"
                }
            } catch (error: Throwable) {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
            } finally {
                synchronized(this) { persistenceHydrating = false }
                try {
                    localWriter?.close() ?: localPageStore?.close()
                } catch (error: Throwable) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
                }
            }
        }

        private fun installPersistentTiles(tiles: List<DistantLodTile>): Int {
            var installed = 0
            for (batch in tiles.chunked(PERSISTENCE_INSTALL_BATCH)) {
                synchronized(this) {
                    if (closed) return installed
                    var publicationRevision: Long? = null
                    for (tile in batch) {
                        if (tile.position in sources || store.size() >= residentPageLimit) continue
                        val changeRevision = publicationRevision
                            ?: revision.incrementExact().also { publicationRevision = it }
                        store.put(tile).forEach { evicted ->
                            sources.remove(evicted)
                            verticalPages.remove(evicted)
                            pendingPersistencePages.remove(evicted)
                            recordChange(evicted, changeRevision)
                        }
                        sources[tile.position] = DistantLodTileSource.PERSISTENCE
                        recordChange(tile.position, changeRevision)
                        installed++
                    }
                }
            }
            return installed
        }

        private fun installPersistentPages(pages: List<DistantVerticalPage>): Int {
            var installed = 0
            for (batch in pages.chunked(PERSISTENCE_INSTALL_BATCH)) {
                val prepared = batch.map { page ->
                    Triple(
                        page,
                        ChunkPosition(page.key.x.toInt(), page.key.z.toInt()),
                        page.toTopOnlyCompatibilityTile(),
                    )
                }
                synchronized(this) {
                    if (closed) return installed
                    var publicationRevision: Long? = null
                    for ((page, position, tile) in prepared) {
                        val source = sources[position]
                        val canInstall = (source == null && store.size() < residentPageLimit) ||
                            (source == DistantLodTileSource.PERSISTENCE && position !in verticalPages)
                        if (!canInstall) continue
                        val changeRevision = publicationRevision
                            ?: revision.incrementExact().also { publicationRevision = it }
                        store.put(tile).forEach { evicted ->
                            sources.remove(evicted)
                            verticalPages.remove(evicted)
                            pendingPersistencePages.remove(evicted)
                            recordChange(evicted, changeRevision)
                        }
                        verticalPages[position] = page
                        sources[position] = DistantLodTileSource.PERSISTENCE
                        recordChange(position, changeRevision)
                        installed++
                    }
                }
            }
            return installed
        }

        @Synchronized
        private fun shouldPersistMigration(page: DistantVerticalPage): Boolean {
            if (closed) return false
            val position = ChunkPosition(page.key.x.toInt(), page.key.z.toInt())
            return sources[position] == DistantLodTileSource.PERSISTENCE && position !in verticalPages
        }

        @Synchronized
        private fun isClosed(): Boolean = closed

        @Synchronized
        private fun containsCanonicalPage(position: ChunkPosition): Boolean {
            if (persistenceHydrating) return true
            val page = verticalPages[position]
                ?: return pageStore?.contains(
                    TerrainPageKey(
                        TerrainDomain.DISTANT,
                        detailLevel = 0,
                        x = position.x.toLong(),
                        y = 0L,
                        z = position.z.toLong(),
                        worldEpoch = session.world.terrainEpoch,
                    ),
                ) == true
            return page.completeness == DistantSourceCompleteness.COMPLETE ||
                sources[position] != DistantLodTileSource.PERSISTENCE
        }

        @Synchronized
        private fun refreshResidentPersistenceIfMoved() {
            if (closed || persistenceHydrating || pageStore == null) return
            val center = session.player.physics.positionInfo.chunkPosition
            if (center == residentCenter && requestedResidentCenter == null) return
            requestedResidentCenter = center
            if (residentRefreshScheduled) return
            residentRefreshScheduled = true
            persistenceHydrationDispatcher(::refreshResidentPersistence)
        }

        /** Replaces only persistence-owned residency; native and network pages retain precedence. */
        private fun refreshResidentPersistence() {
            while (true) {
                val request = synchronized(this) {
                    if (closed) {
                        residentRefreshScheduled = false
                        return
                    }
                    val center = requestedResidentCenter
                    val store = pageStore
                    if (center == null || store == null) {
                        residentRefreshScheduled = false
                        return
                    }
                    requestedResidentCenter = null
                    store to center
                }
                val (persistentStore, center) = request
                try {
                    val residentKeys = persistentStore.nearestKeys(
                        centerX = center.x.toLong(),
                        centerZ = center.z.toLong(),
                        maximumLoadedPages = residentPageLimit,
                        maximumEncodedBytes = MAX_RESIDENT_ENCODED_BYTES,
                    )
                    val residentPositions = residentKeys.mapTo(hashSetOf()) { key ->
                        ChunkPosition(Math.toIntExact(key.x), Math.toIntExact(key.z))
                    }
                    synchronized(this) {
                        if (closed) return
                        val removals = sources.entries.asSequence()
                            .filter { (position, source) ->
                                source == DistantLodTileSource.PERSISTENCE && position !in residentPositions
                            }
                            .map { it.key }
                            .toList()
                        if (removals.isNotEmpty()) {
                            val changeRevision = revision.incrementExact()
                            removals.forEach { position ->
                                store.remove(position)
                                sources.remove(position)
                                verticalPages.remove(position)
                                recordChange(position, changeRevision)
                            }
                        }
                    }
                    persistentStore.loadNearestInBatches(
                        worldEpoch = session.world.terrainEpoch,
                        centerX = center.x.toLong(),
                        centerZ = center.z.toLong(),
                        maximumLoadedPages = residentPageLimit,
                        maximumEncodedBytes = MAX_RESIDENT_ENCODED_BYTES,
                        maximumBatchPages = PERSISTENCE_INSTALL_BATCH,
                    ) consume@{ pages ->
                        if (isClosed()) return@consume false
                        pages.maxOfOrNull(DistantVerticalPage::sourceRevision)?.let { loadedRevision ->
                            revision.accumulateAndGet(loadedRevision, ::maxOf)
                        }
                        installPersistentPages(pages)
                        !isClosed()
                    }
                    synchronized(this) {
                        if (!closed) residentCenter = center
                    }
                } catch (error: Throwable) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
                }
                synchronized(this) {
                    if (closed || requestedResidentCenter == null) {
                        residentRefreshScheduled = false
                        return
                    }
                }
            }
        }

        @Synchronized
        private fun completeSourceRevision(position: ChunkPosition): Long? =
            verticalPages[position]?.takeIf {
                it.completeness == DistantSourceCompleteness.COMPLETE
            }?.sourceRevision

        @Synchronized
        fun mutationSource(position: ChunkPosition): Pair<DistantLodTile?, DistantVerticalPage?> =
            store[position] to verticalPages[position]

        @Synchronized
        fun publish(
            tile: DistantLodTile,
            source: DistantLodTileSource,
            verticalPage: DistantVerticalPage? = null,
        ) {
            if (closed) return
            if (verticalPage != null && !isCurrentDistantPublication(
                    tile,
                    verticalPage,
                    session.world.terrainEpoch,
                )
            ) return
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
                pendingPersistencePages.remove(evicted)
                recordChange(evicted, publicationRevision)
            }
            sources[tile.position] = source
            if (publishedPage == null) {
                verticalPages.remove(tile.position)
                pendingPersistencePages.remove(tile.position)
            } else {
                verticalPages[tile.position] = publishedPage
                if (persistence != null) {
                    val writer = pageWriter
                    if (writer == null) {
                        pendingPersistencePages[tile.position] = publishedPage
                    } else if (!writer.markDirty(publishedPage)) {
                        Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                            "Distant page writer queue is saturated; retaining the page in memory"
                        }
                    }
                }
            }
            recordChange(tile.position, publicationRevision)
        }

        fun receive(message: DistantLodMessage) {
            if (closed) return
            network.receive(message)
        }

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

        fun networkInspection(): DistantLodNetworkClient.Inspection = network.inspect()

        fun close(sendNetworkCancellation: Boolean) {
            val writer = synchronized(this) {
                if (closed) return
                closed = true
                pageWriter
            }
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
            cleanup { session.ticker -= residentPersistence }
            cleanup { session.ticker -= generator }
            cleanup { session.ticker -= network }
            if (persistence != null) cleanup { writer?.close() }
            synchronized(this) {
                cleanup(store::clear)
                sources.clear()
                verticalPages.clear()
                requestedResidentCenter = null
                residentRefreshScheduled = false
                pendingPersistencePages.clear()
                changeJournal.clear()
            }
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
    // A configuration payload can precede the playable-world JOINED event.
    // Retain the latest negotiated hello across that same-session replacement;
    // both readable v1 and production v2 hellos use this ordering boundary.
    private val networkHello = IdentityHashMap<PlaySession, NegotiatedHello>()
    private val renderDiagnostics = IdentityHashMap<PlaySession, DistantLodRenderDiagnostics>()
    private val pendingNetworkPayloads = ArrayDeque<PendingNetworkPayload>()
    private var pendingNetworkPayloadBytes = 0
    private var networkPayloadDrainScheduled = false
    private val droppedNetworkPayloads = AtomicLong()
    private val revision = AtomicLong()
    @Volatile private var presentationOverride: Boolean? = null
    @Volatile private var closed = false

    private companion object {
        const val MAX_CHANGE_POSITIONS = 4_096
        const val PERSISTENCE_INSTALL_BATCH = 64
        const val MAX_RESIDENT_PAGES = 2_048
        const val MAX_RESIDENT_ENCODED_BYTES = 512L * 1_024L * 1_024L
        const val MAX_PENDING_NETWORK_PAYLOADS = 128
        const val MAX_PENDING_NETWORK_PAYLOAD_BYTES = 64 * FabricClientPayloadChannels.MAX_PAYLOAD_BYTES
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
        try {
            when (hello) {
                is NegotiatedHello.V1 -> replacement.receive(hello.message)
                is NegotiatedHello.V2 -> replacement.receive(hello.message)
                null -> Unit
            }
        } catch (failure: Throwable) {
            try {
                replacement.close(sendNetworkCancellation = false)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
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
        val (previous, previousPage) = state.mutationSource(context.chunk.position)
        if (previous == null) {
            val (tile, page) = captureNative(context.chunk)
            state.publish(tile, DistantLodTileSource.NATIVE, page)
            return
        }
        val columns = context.changes.mapTo(linkedSetOf()) {
            DistantLodTile.index(it.position.x and 0x0F, it.position.z and 0x0F)
        }
        val (tile, page) = updateNative(context.chunk, previous, previousPage, columns)
        state.publish(tile, DistantLodTileSource.NATIVE, page)
    }

    fun onNetworkPayload(context: FabricClientPayloadContext) {
        if (closed || !options.networkTransferEnabled) return
        val payload = context.copyPayload()
        val shouldSchedule = synchronized(pendingNetworkPayloads) {
            if (closed) return
            if (
                pendingNetworkPayloads.size >= MAX_PENDING_NETWORK_PAYLOADS ||
                pendingNetworkPayloadBytes > MAX_PENDING_NETWORK_PAYLOAD_BYTES - payload.size
            ) {
                val dropped = droppedNetworkPayloads.incrementAndGet()
                if (dropped == 1L || dropped % 128L == 0L) {
                    Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                        "Distant network decode queue is saturated; dropped=$dropped"
                    }
                }
                return
            }
            pendingNetworkPayloads.addLast(PendingNetworkPayload(context.session, payload))
            pendingNetworkPayloadBytes = Math.addExact(pendingNetworkPayloadBytes, payload.size)
            if (networkPayloadDrainScheduled) {
                false
            } else {
                networkPayloadDrainScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        try {
            networkPayloadDispatcher(::drainNetworkPayloads)
        } catch (error: Throwable) {
            synchronized(pendingNetworkPayloads) {
                pendingNetworkPayloads.clear()
                pendingNetworkPayloadBytes = 0
                networkPayloadDrainScheduled = false
            }
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN, error)
        }
    }

    /**
     * Protocol-v2 pages can contain thousands of vertical runs. Decoding and
     * publishing them on Netty's event loop starves ordinary packets and, in
     * particular, keepalive replies. One bounded serial drain preserves wire
     * order while keeping the transport thread available.
     */
    private fun drainNetworkPayloads() {
        while (true) {
            val work = synchronized(pendingNetworkPayloads) {
                val next = pendingNetworkPayloads.pollFirst()
                if (next == null) {
                    networkPayloadDrainScheduled = false
                    return
                }
                pendingNetworkPayloadBytes = Math.subtractExact(pendingNetworkPayloadBytes, next.payload.size)
                next
            }
            if (closed) continue
            processNetworkPayload(work.session, work.payload)
        }
    }

    private fun processNetworkPayload(session: PlaySession, payload: ByteArray) {
        try {
            val message = DistantLodProtocol.decodeNegotiated(payload)
            val current = synchronized(states) {
                if (closed) return
                // A server can advertise immediately after login, before a
                // registry-driven world reconfiguration finishes. Retain the
                // negotiated bounds across that same-session world transition.
                when (message) {
                    is DistantLodMessage.Hello -> networkHello[session] = NegotiatedHello.V1(message)
                    is DistantTerrainMessageV2.Hello -> networkHello[session] = NegotiatedHello.V2(message)
                    else -> Unit
                }
                states[session]
            }
            when (message) {
                is DistantLodMessage -> {
                    if (current != null) current.receive(message)
                }
                is DistantTerrainMessageV2 -> {
                    if (current != null) current.receive(message)
                }
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

    internal fun networkPayloadInspection(): DistantNetworkPayloadInspection =
        synchronized(pendingNetworkPayloads) {
            DistantNetworkPayloadInspection(
                pendingPayloads = pendingNetworkPayloads.size,
                pendingBytes = pendingNetworkPayloadBytes,
                droppedPayloads = droppedNetworkPayloads.get(),
            )
        }

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

    private fun captureNative(chunk: Chunk): Pair<DistantLodTile, DistantVerticalPage> = chunk.lock.locked {
        DistantLodTile.capture(chunk.position) { x, z -> sample(chunk, x, z) } to
            DistantWorldVerticalSampler.captureObservedLocked(chunk)
    }

    private fun updateNative(
        chunk: Chunk,
        previous: DistantLodTile,
        previousPage: DistantVerticalPage?,
        columns: Set<Int>,
    ): Pair<DistantLodTile, DistantVerticalPage> = chunk.lock.locked {
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
        synchronized(pendingNetworkPayloads) {
            pendingNetworkPayloads.clear()
            pendingNetworkPayloadBytes = 0
            networkPayloadDrainScheduled = false
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
