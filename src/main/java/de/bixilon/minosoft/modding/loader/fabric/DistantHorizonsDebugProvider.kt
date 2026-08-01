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

import com.fasterxml.jackson.databind.node.ObjectNode
import de.bixilon.minosoft.debug.DebugJson
import de.bixilon.minosoft.debug.DebugOperationException
import de.bixilon.minosoft.debug.DebugOperationResult
import de.bixilon.minosoft.debug.ModDebugProvider
import de.bixilon.minosoft.debug.ModDebugRegistrar
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.terrain.distant.DistantLodTileSource
import java.util.concurrent.CompletableFuture

internal class DistantHorizonsDebugProvider(
    private val controller: DistantHorizonsLodController,
    private val options: DistantHorizonsOptions,
) : ModDebugProvider {
    override fun modId() = "distanthorizons"
    override fun providerVersion() = "fabric-adapter-v2"

    override fun register(registrar: ModDebugRegistrar) {
        registrar.operation("presentation") { _, body ->
            val restore = body.path("restore").asBoolean(false)
            val enabled = body["enabled"]
            if (restore && enabled != null) {
                throw DebugOperationException("invalid_request", "restore and enabled are mutually exclusive")
            }
            if (!restore && enabled != null && !enabled.isBoolean) {
                throw DebugOperationException("invalid_request", "enabled must be a boolean")
            }
            val previous = controller.presentationOverride()
            if (restore) {
                controller.setPresentationOverride(null)
            } else if (enabled != null) {
                controller.setPresentationOverride(enabled.booleanValue())
            }
            completed(DebugJson.MAPPER.createObjectNode().apply {
                putNullableBoolean("previousOverride", previous)
                putNullableBoolean("override", controller.presentationOverride())
                put("configuredEnabled", options.enabled)
                put("enabled", controller.presentationEnabled())
                put("nonPersistent", true)
            })
        }
        registrar.operation("render-diagnostics") { _, _ ->
            val session = activeSession()
            val diagnostics = controller.renderDiagnostics(session)
                ?: throw DebugOperationException("not_ready", "distant terrain has not published a mesh plan")
            completed(DebugJson.MAPPER.createObjectNode().apply {
                put("revision", diagnostics.revision)
                put("nativeOwnershipRevision", diagnostics.nativeOwnershipRevision)
                put("configuredEnabled", options.enabled)
                put("enabled", controller.presentationEnabled())
                put("tileCount", diagnostics.tileCount)
                put("renderReadyNativeChunks", diagnostics.renderReadyNativeChunks)
                put("excludedTiles", diagnostics.excludedTiles)
                put("cellCount", diagnostics.cellCount)
                put("terrainCells", diagnostics.terrainCells)
                put("waterCells", diagnostics.waterCells)
                put("waterBedCells", diagnostics.waterBedCells)
                put("adaptiveCells", diagnostics.adaptiveCells)
                put("skirtSegments", diagnostics.skirtSegments)
                put("maximumSkirtDrop", diagnostics.maximumSkirtDrop)
                putObject("cellSizes").also { sizes ->
                    diagnostics.cellSizes.toSortedMap().forEach { (size, count) ->
                        sizes.put(size.toString(), count)
                    }
                }
                putObject("sources").also { sources ->
                    diagnostics.sources.toSortedMap(compareBy(DistantLodTileSource::wireName))
                        .forEach { (source, count) -> sources.put(source.wireName, count) }
                }
                putArray("cells").also { cells ->
                    diagnostics.cells.forEach { cell ->
                        cells.addObject().apply {
                            put("chunkX", cell.chunk.x); put("chunkZ", cell.chunk.z)
                            put("x", cell.x); put("z", cell.z)
                            put("size", cell.size); put("y", cell.y)
                            put("minimumY", cell.minimumY); put("maximumY", cell.maximumY)
                            put("heightRange", cell.maximumY - cell.minimumY)
                            put("material", cell.material)
                            put("surface", cell.surface)
                            put("source", cell.source.wireName)
                            put("skirtSegments", cell.skirtSegments)
                            put("maximumSkirtDrop", cell.maximumSkirtDrop)
                        }
                    }
                }
                diagnostics.hierarchy?.let { hierarchy ->
                    putObject("hierarchy").apply {
                        put("deviceCapacityBytes", hierarchy.deviceCapacityBytes)
                        put("storageHighWaterBytes", hierarchy.storageHighWaterBytes)
                        put("stagingCapacityBytes", hierarchy.stagingCapacityBytes)
                        put("indexRevision", hierarchy.indexRevision)
                        put("sourcePublicationRevision", hierarchy.sourcePublicationRevision)
                        put("dirtyRevision", hierarchy.dirtyRevision)
                        put("indexedPages", hierarchy.indexedPages)
                        put("dirtyPages", hierarchy.dirtyPages)
                        put("cpuPages", hierarchy.cpuPages)
                        put("gpuPages", hierarchy.gpuPages)
                        put("pendingPages", hierarchy.pendingPages)
                        put("queuedPages", hierarchy.queuedPages)
                        put("queuedBuildPages", hierarchy.queuedBuildPages)
                        put("pendingUploadPages", hierarchy.pendingUploadPages)
                        put("mainSelectedPages", hierarchy.mainSelectedPages)
                        put("shadowSelectedPages", hierarchy.shadowSelectedPages)
                        put("mainMaskedPages", hierarchy.mainMaskedPages)
                        put("shadowMaskedPages", hierarchy.shadowMaskedPages)
                        put("coverageRevision", hierarchy.coverageRevision)
                        put("coverageLifecycleRevision", hierarchy.coverageLifecycleRevision)
                        put("coverageTransitionFrames", hierarchy.coverageTransitionFrames)
                        put("regions", hierarchy.regions)
                        put("residentBytes", hierarchy.residentBytes)
                        put("retiredBytes", hierarchy.retiredBytes)
                        put("allocationFailures", hierarchy.allocationFailures)
                        put("uploadFailures", hierarchy.uploadFailures)
                        put("cpuLeaseCount", hierarchy.cpuLeaseCount)
                        put("pendingSubmissionCount", hierarchy.pendingSubmissionCount)
                        put("failedSubmissionCount", hierarchy.failedSubmissionCount)
                        put("invalidatedSubmissionCount", hierarchy.invalidatedSubmissionCount)
                        put("retiredCpuLeasedPages", hierarchy.retiredCpuLeasedPages)
                        put("retiredPendingSubmissionPages", hierarchy.retiredPendingSubmissionPages)
                        put("retiredFailedSubmissionPages", hierarchy.retiredFailedSubmissionPages)
                        put("retiredDeviceInvalidatedPages", hierarchy.retiredDeviceInvalidatedPages)
                        put("deviceInvalidations", hierarchy.deviceInvalidations)
                        put("drawBatches", hierarchy.drawBatches)
                        put("drawCommands", hierarchy.drawCommands)
                        put("drawVertices", hierarchy.drawVertices)
                        put("pendingSubmissionFences", hierarchy.pendingSubmissionFences)
                        putObject("detailCounts").also { counts ->
                            hierarchy.detailCounts.toSortedMap().forEach { (detail, count) ->
                                counts.put(detail.toString(), count)
                            }
                        }
                        putArray("regionStates").also { states ->
                            hierarchy.regionStates.forEach { region ->
                                states.addObject().apply {
                                    put("detailLevel", region.detailLevel)
                                    put("x", region.x); put("z", region.z)
                                    put("shard", region.shard)
                                    put("activePages", region.activePages)
                                    put("retiredPages", region.retiredPages)
                                    put("residentBytes", region.residentBytes)
                                    put("vertexAllocatedBytes", region.vertexAllocatedBytes)
                                    put("indexAllocatedBytes", region.indexAllocatedBytes)
                                    put("vertexHighWaterBytes", region.vertexHighWaterBytes)
                                    put("indexHighWaterBytes", region.indexHighWaterBytes)
                                    put("vertexFragmentation", region.vertexFragmentation)
                                    put("indexFragmentation", region.indexFragmentation)
                                    put("allocationFailures", region.allocationFailures)
                                    put("uploadFailures", region.uploadFailures)
                                }
                            }
                        }
                        putArray("pages").also { pages ->
                            hierarchy.pages.forEach { page ->
                                pages.addObject().apply {
                                    put("detailLevel", page.detailLevel)
                                    put("x", page.x); put("z", page.z)
                                    put("sourceRevision", page.sourceRevision)
                                    put("dirtyRevision", page.dirtyRevision)
                                    put("renderRevision", page.renderRevision)
                                    put("dirty", page.dirty)
                                    put("derived", page.derived)
                                    put("buildState", page.buildState.name.lowercase())
                                    put("mergedFaces", page.mergedFaces)
                                    put("fallbackFaces", page.fallbackFaces)
                                }
                            }
                        }
                    }
                }
            })
        }
        registrar.operation("store-network-inspection") { _, _ ->
            val session = activeSession()
            val store = controller.storeInspection(session)
            val network = controller.networkInspection(session)
                ?: throw DebugOperationException("not_ready", "distant network state is not initialized")
            completed(DebugJson.MAPPER.createObjectNode().apply {
                put("schemaVersion", 1)
                putObject("store").apply {
                    if (store == null) {
                        put("enabled", false)
                    } else {
                        put("enabled", true)
                        put("pageSchemaVersion", store.schemaVersion)
                        put("recordCount", store.recordCount)
                        put("totalBytes", store.totalBytes)
                        put("ignoredTemporaryRecords", store.ignoredTemporaryRecords)
                        put("pinnedRecords", store.pinnedRecords)
                        put("evictionCount", store.evictionCount)
                    }
                }
                putObject("network").apply {
                    put("protocolVersion", network.protocolVersion)
                    put("serverMaximumPages", network.serverMaximumPages)
                    put("serverMaximumRadius", network.serverMaximumRadius)
                    put("pendingPages", network.pendingPages)
                    put("requestsSent", network.requestsSent)
                    put("receivedPages", network.receivedPages)
                    put("cancellationsSent", network.cancellationsSent)
                    put("cancellationFailures", network.cancellationFailures)
                    put("worldResets", network.worldResets)
                    putObject("responseRejections").also { rejections ->
                        network.responseRejections.forEach { (reason, count) ->
                            rejections.put(reason.name, count)
                        }
                    }
                    putArray("outstandingRequestIds").also { ids ->
                        network.outstandingRequestIds.forEach(ids::add)
                    }
                }
            })
        }
    }

    private fun activeSession(): PlaySession {
        val sessions = PlaySession.collectSessions()
        return sessions.singleOrNull()
            ?: throw DebugOperationException(
                "not_ready",
                "render diagnostics require exactly one active play session; found ${sessions.size}",
            )
    }

    private fun completed(result: ObjectNode): CompletableFuture<DebugOperationResult> =
        CompletableFuture.completedFuture(DebugOperationResult.json(result))

    private fun ObjectNode.putNullableBoolean(name: String, value: Boolean?) {
        if (value == null) putNull(name) else put(name, value)
    }
}
