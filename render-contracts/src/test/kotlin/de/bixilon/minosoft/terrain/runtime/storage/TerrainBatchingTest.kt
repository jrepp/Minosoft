/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.runtime.storage

import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.model.mesh.TerrainArtifactStream
import de.bixilon.minosoft.terrain.model.mesh.TerrainMeshArtifact
import de.bixilon.minosoft.terrain.runtime.TerrainDeviceRuntimeId
import de.bixilon.minosoft.terrain.runtime.TerrainProcessScopeId
import de.bixilon.minosoft.terrain.runtime.TerrainSubmission
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionCompletion
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionSerial
import de.bixilon.minosoft.terrain.runtime.TerrainSubmissionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

class TerrainBatchingTest {
    private val deviceId = TerrainDeviceRuntimeId(TerrainProcessScopeId(1L), 1L)
    private val material = TerrainSemanticMaterialId("minosoft:opaque")

    @Test
    fun `cache reuses templates while every frame gets a fresh retirement lease`() {
        val completion = Completion(deviceId)
        val device = Device(deviceId)
        val firstPage = page(0L)
        val secondPage = page(1L)
        val storage = TerrainRegionStorage(
            TerrainRegionKey.containing(firstPage, 8),
            8,
            device,
            completion,
        )
        artifact(firstPage, 1L).use { storage.publish(it) }
        artifact(secondPage, 2L).use { storage.publish(it) }
        val cache = TerrainBatchCache(maximumEntries = 2)
        val view = TerrainViewKey("main")

        val first = assertNotNull(cache.batch(storage, material, view, listOf(secondPage, firstPage), 3L, 4L))
        assertEquals(listOf(secondPage, firstPage), first.commands.map { it.page })
        val drawn = ArrayList<TerrainPageKey>()
        assertEquals(2, TerrainConventionalDrawLoop.submit(first) { drawn += it.page })
        assertEquals(listOf(secondPage, firstPage), drawn)
        first.submit(TerrainSubmission(deviceId, TerrainSubmissionSerial(1L)))
        first.close()

        val second = assertNotNull(cache.batch(storage, material, view, listOf(secondPage, firstPage), 3L, 4L))
        second.close()
        assertEquals(1L, cache.metrics().builds)
        assertEquals(1L, cache.metrics().hits)

        storage.remove(firstPage)
        assertEquals(1, storage.metrics().retiredPages)
        completion.states[TerrainSubmissionSerial(1L)] = TerrainSubmissionState.COMPLETE
        assertEquals(1, storage.collectRetired())
    }

    @Test
    fun `cache isolates storage shards with the same logical region and publication ids`() {
        val completion = Completion(deviceId)
        val regionKey = TerrainRegionKey.containing(page(0L), 8)
        val firstStorage = TerrainRegionStorage(regionKey, 8, Device(deviceId), completion)
        val secondStorage = TerrainRegionStorage(regionKey, 8, Device(deviceId), completion)
        val filler = page(1L)
        val target = page(0L)
        artifact(filler, 1L).use { firstStorage.publish(it) }
        artifact(target, 2L).use { firstStorage.publish(it) }
        artifact(filler, 1L).use { secondStorage.publish(it) }
        secondStorage.remove(filler)
        artifact(target, 2L).use { secondStorage.publish(it) }
        val cache = TerrainBatchCache()
        val view = TerrainViewKey("main")

        val first = assertNotNull(cache.batch(firstStorage, material, view, listOf(target), 3L, 4L))
        val second = assertNotNull(cache.batch(secondStorage, material, view, listOf(target), 3L, 4L))
        assertNotEquals(first.commands.single().vertexRange.offset, second.commands.single().vertexRange.offset)
        first.close()
        second.close()
        assertEquals(2L, cache.metrics().builds)
        assertEquals(0L, cache.metrics().hits)
    }

    @Test
    fun `budget scheduler keeps urgency order and independent caps`() {
        val items = listOf(
            TerrainUploadBudgetItem("deferred", TerrainUploadUrgency.DEFERRED, 1L, 1L, 1L),
            TerrainUploadBudgetItem("next", TerrainUploadUrgency.NEXT_FRAME, 2L, 4L, 4L),
            TerrainUploadBudgetItem("same", TerrainUploadUrgency.SAME_FRAME, 3L, 3L, 3L),
        )

        val selected = TerrainUploadBudgetScheduler.select(items, cpuBudgetNanos = 4L, uploadBudgetBytes = 4L)

        assertEquals(listOf("same", "deferred"), selected.selected.map { it.value })
        assertEquals(listOf("next"), selected.deferred.map { it.value })
        assertEquals(4L, selected.selectedCpuNanos)
        assertEquals(4L, selected.selectedUploadBytes)

        val oversized = TerrainUploadBudgetScheduler.select(
            listOf(TerrainUploadBudgetItem("oversized", TerrainUploadUrgency.SAME_FRAME, 4L, 9L, 11L)),
            cpuBudgetNanos = 4L,
            uploadBudgetBytes = 4L,
        )
        assertEquals(listOf("oversized"), oversized.selected.map { it.value })
        assertEquals(9L, oversized.selectedCpuNanos)
        assertEquals(11L, oversized.selectedUploadBytes)
    }

    private fun page(x: Long) = TerrainPageKey(TerrainDomain.NEAR, 0, x, 0L, 0L, 9L)

    private fun artifact(page: TerrainPageKey, revision: Long) = TerrainMeshArtifact(
        TerrainBuildIdentity(page, revision, revision, 1L, 3L, 4L, 1L, revision),
        listOf(TerrainArtifactStream("opaque:unassigned", material, 4, ByteArray(8), 2, ByteArray(6))),
        null,
        0L,
    )

    private class Completion(override val deviceRuntime: TerrainDeviceRuntimeId) : TerrainSubmissionCompletion {
        val states = HashMap<TerrainSubmissionSerial, TerrainSubmissionState>()
        override fun state(serial: TerrainSubmissionSerial) = states[serial] ?: TerrainSubmissionState.PENDING
    }

    private class Device(override val deviceRuntime: TerrainDeviceRuntimeId) : TerrainRegionUploadDevice {
        override val layoutGeneration = 3L
        override val vertexCapacityBytes = 64
        override val indexCapacityBytes = 32
        override fun upload(plan: TerrainUploadPlan) = Unit
    }
}
