/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.terrain.model.mesh

import de.bixilon.minosoft.terrain.model.identity.TerrainBuildIdentity
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean

data class TerrainArtifactCoordinate(val x: Int, val y: Int, val z: Int)

data class TerrainArtifactBounds(
    val minimum: TerrainArtifactCoordinate,
    val maximumInclusive: TerrainArtifactCoordinate,
) {
    init {
        require(minimum.x <= maximumInclusive.x)
        require(minimum.y <= maximumInclusive.y)
        require(minimum.z <= maximumInclusive.z)
    }
}

class TerrainArtifactBuffer(bytes: ByteArray) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var storage = bytes.copyOf()

    val size: Int
        @Synchronized get() = storage.size
    val isClosed: Boolean get() = closed.get()

    @Synchronized
    fun copyBytes(): ByteArray {
        check(!closed.get()) { "Terrain artifact buffer is closed" }
        return storage.copyOf()
    }

    @Synchronized
    internal fun updateDigest(digest: MessageDigest) {
        check(!closed.get()) { "Terrain artifact buffer is closed" }
        digest.update(storage)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        storage = ByteArray(0)
    }
}

enum class TerrainPrimitiveTopology(val indicesPerPrimitive: Int) {
    TRIANGLES(3),
    QUADS(4),
}

class TerrainArtifactStream(
    val partitionId: String,
    val material: TerrainSemanticMaterialId,
    val vertexStrideBytes: Int,
    vertexBytes: ByteArray,
    val indexElementBytes: Int,
    indexBytes: ByteArray,
    val topology: TerrainPrimitiveTopology = TerrainPrimitiveTopology.TRIANGLES,
) : AutoCloseable {
    init {
        require(partitionId.matches(NORMALIZED_ID)) { "Terrain artifact partition ID must be normalized" }
        validateVertexBytes(vertexBytes, vertexStrideBytes)
        validateIndexBytes(indexBytes, indexElementBytes, topology)
    }

    val vertices = TerrainArtifactBuffer(vertexBytes)
    val indices = TerrainArtifactBuffer(indexBytes)
    val byteCount: Long = Math.addExact(vertices.size.toLong(), indices.size.toLong())

    override fun close() {
        var failure: Throwable? = null
        try {
            indices.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            vertices.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }

    private companion object {
        val NORMALIZED_ID = Regex("[a-z0-9_.-]+(?::[a-z0-9/._-]+)?")
        val INDEX_ELEMENT_BYTES = setOf(1, 2, 4)

        fun validateVertexBytes(bytes: ByteArray, stride: Int) {
            require(stride > 0) { "Terrain artifact vertex stride must be positive" }
            require(bytes.size % stride == 0) { "Terrain vertex stream is not stride aligned" }
        }

        fun validateIndexBytes(
            bytes: ByteArray,
            elementBytes: Int,
            topology: TerrainPrimitiveTopology,
        ) {
            require(elementBytes in INDEX_ELEMENT_BYTES) { "Unsupported terrain index element size" }
            require(bytes.size % elementBytes == 0) { "Terrain index stream is not element aligned" }
            require(bytes.size / elementBytes % topology.indicesPerPrimitive == 0) {
                "Terrain index stream does not contain complete ${topology.name.lowercase()} primitives"
            }
        }
    }
}

data class TerrainArtifactDigest(
    val schemaVersion: Int,
    val algorithm: String,
    val encodedValue: String,
)

class TerrainMeshArtifact(
    val identity: TerrainBuildIdentity,
    streams: Collection<TerrainArtifactStream>,
    val bounds: TerrainArtifactBounds?,
    val connectivityBits: Long,
    coverageContribution: Collection<TerrainPageKey> = emptyList(),
    entityPositions: Collection<TerrainArtifactCoordinate> = emptyList(),
) : AutoCloseable {
    val streams = validatedStreams(streams)
    val coverageContribution = validatedCoverage(identity, coverageContribution)
    val entityPositions = java.util.List.copyOf(entityPositions.sortedWith(COORDINATE_ORDER))
    val byteCount = this.streams.fold(0L) { total, stream -> Math.addExact(total, stream.byteCount) }
    val digest: TerrainArtifactDigest = calculateDigest()
    private val closed = AtomicBoolean()

    init {
    }

    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        for (stream in streams.asReversed()) {
            try {
                stream.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    private fun calculateDigest(): TerrainArtifactDigest {
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        digest.putInt(DIGEST_SCHEMA_VERSION)
        identity.digestInto(digest)
        digest.putLong(connectivityBits)
        digest.putNullableCoordinate(bounds?.minimum)
        digest.putNullableCoordinate(bounds?.maximumInclusive)
        digest.putInt(coverageContribution.size)
        coverageContribution.forEach { it.digestInto(digest) }
        digest.putInt(entityPositions.size)
        entityPositions.forEach { digest.putCoordinate(it) }
        digest.putInt(streams.size)
        streams.forEach { stream ->
            digest.putText(stream.partitionId)
            digest.putText(stream.material.value)
            digest.putInt(stream.vertexStrideBytes)
            digest.putInt(stream.vertices.size)
            stream.vertices.updateDigest(digest)
            digest.putInt(stream.indexElementBytes)
            digest.putInt(stream.topology.ordinal)
            digest.putInt(stream.indices.size)
            stream.indices.updateDigest(digest)
        }
        return TerrainArtifactDigest(
            DIGEST_SCHEMA_VERSION,
            DIGEST_ALGORITHM,
            HexFormat.of().formatHex(digest.digest()),
        )
    }

    companion object {
        const val DIGEST_SCHEMA_VERSION = 2
        const val DIGEST_ALGORITHM = "SHA-256"

        private val STREAM_ORDER = compareBy(TerrainArtifactStream::material, TerrainArtifactStream::partitionId)
        private val COORDINATE_ORDER = compareBy(TerrainArtifactCoordinate::x)
            .thenBy(TerrainArtifactCoordinate::y)
            .thenBy(TerrainArtifactCoordinate::z)
        private val PAGE_ORDER = compareBy<TerrainPageKey>(
            { it.domain.ordinal },
            { it.detailLevel },
            { it.x },
            { it.y },
            { it.z },
            { it.worldEpoch },
        )

        private fun validatedStreams(streams: Collection<TerrainArtifactStream>): List<TerrainArtifactStream> {
            val result = java.util.List.copyOf(streams.sortedWith(STREAM_ORDER))
            require(result.map(TerrainArtifactStream::partitionId).toSet().size == result.size) {
                "Terrain artifact contains duplicate partitions"
            }
            return result
        }

        private fun validatedCoverage(
            identity: TerrainBuildIdentity,
            coverage: Collection<TerrainPageKey>,
        ): List<TerrainPageKey> {
            val result = java.util.List.copyOf(coverage.sortedWith(PAGE_ORDER))
            require(result.all { it.worldEpoch == identity.page.worldEpoch }) {
                "Terrain artifact coverage crosses a world epoch"
            }
            require(result.distinct().size == result.size) {
                "Terrain artifact contains duplicate coverage pages"
            }
            return result
        }
    }
}

enum class TerrainArtifactDifference {
    IDENTITY,
    BOUNDS,
    CONNECTIVITY,
    COVERAGE,
    ENTITIES,
    STREAM_LAYOUT,
    CONTENT,
}

class TerrainArtifactComparison(
    val equivalent: Boolean,
    differences: Set<TerrainArtifactDifference>,
    val expectedDigest: TerrainArtifactDigest,
    val actualDigest: TerrainArtifactDigest,
) {
    val differences: Set<TerrainArtifactDifference> = java.util.Set.copyOf(differences)
}

fun TerrainMeshArtifact.compareSemantic(actual: TerrainMeshArtifact): TerrainArtifactComparison {
    val differences = linkedSetOf<TerrainArtifactDifference>()
    if (identity != actual.identity) differences += TerrainArtifactDifference.IDENTITY
    if (bounds != actual.bounds) differences += TerrainArtifactDifference.BOUNDS
    if (connectivityBits != actual.connectivityBits) differences += TerrainArtifactDifference.CONNECTIVITY
    if (coverageContribution != actual.coverageContribution) differences += TerrainArtifactDifference.COVERAGE
    if (entityPositions != actual.entityPositions) differences += TerrainArtifactDifference.ENTITIES
    val layout = streams.map {
        listOf(it.partitionId, it.material.value, it.vertexStrideBytes, it.indexElementBytes, it.topology)
    }
    val actualLayout = actual.streams.map {
        listOf(it.partitionId, it.material.value, it.vertexStrideBytes, it.indexElementBytes, it.topology)
    }
    if (layout != actualLayout) differences += TerrainArtifactDifference.STREAM_LAYOUT
    if (digest != actual.digest && differences.isEmpty()) differences += TerrainArtifactDifference.CONTENT
    return TerrainArtifactComparison(
        differences.isEmpty(),
        java.util.Set.copyOf(differences),
        digest,
        actual.digest,
    )
}

private fun TerrainBuildIdentity.digestInto(digest: MessageDigest) {
    page.digestInto(digest)
    digest.putLong(requestRevision)
    digest.putLong(capturedModelRevision)
    digest.putLong(providerGeneration)
    digest.putLong(layoutGeneration)
    digest.putLong(materialGeneration)
    digest.putLong(coverageGeneration)
    digest.putLong(prioritySequence)
    digest.putLong(sourceDataRevision ?: -1L)
}

private fun TerrainPageKey.digestInto(digest: MessageDigest) {
    digest.putInt(domain.ordinal)
    digest.putInt(detailLevel)
    digest.putLong(x)
    digest.putLong(y)
    digest.putLong(z)
    digest.putLong(worldEpoch)
}

private fun MessageDigest.putNullableCoordinate(value: TerrainArtifactCoordinate?) {
    update((if (value == null) 0 else 1).toByte())
    value?.let(::putCoordinate)
}

private fun MessageDigest.putCoordinate(value: TerrainArtifactCoordinate) {
    putInt(value.x)
    putInt(value.y)
    putInt(value.z)
}

private fun MessageDigest.putText(value: String) {
    val bytes = value.encodeToByteArray()
    putInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.putInt(value: Int) {
    update((value ushr 24).toByte())
    update((value ushr 16).toByte())
    update((value ushr 8).toByte())
    update(value.toByte())
}

private fun MessageDigest.putLong(value: Long) {
    putInt((value ushr 32).toInt())
    putInt(value.toInt())
}
