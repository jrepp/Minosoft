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
                            put("material", cell.material.name.lowercase())
                            put("surface", cell.surface.wireName)
                            put("source", cell.source.wireName)
                            put("skirtSegments", cell.skirtSegments)
                            put("maximumSkirtDrop", cell.maximumSkirtDrop)
                        }
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
