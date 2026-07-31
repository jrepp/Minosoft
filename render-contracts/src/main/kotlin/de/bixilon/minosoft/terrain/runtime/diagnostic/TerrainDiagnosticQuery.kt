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

package de.bixilon.minosoft.terrain.runtime.diagnostic

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

sealed interface TerrainPageSelector {
    val domain: TerrainDomain

    fun matches(page: TerrainPageKey): Boolean

    fun canonicalKey(): String
}

data class TerrainPageAreaSelector(
    override val domain: TerrainDomain,
    val minimumDetailLevel: Int,
    val maximumDetailLevel: Int,
    val minimumX: Long,
    val maximumX: Long,
    val minimumY: Long,
    val maximumY: Long,
    val minimumZ: Long,
    val maximumZ: Long,
) : TerrainPageSelector {
    init {
        require(minimumDetailLevel >= 0) {
            "Terrain page-area minimum detail level must not be negative"
        }
        require(maximumDetailLevel >= minimumDetailLevel) {
            "Terrain page-area detail range must not be empty"
        }
        require(maximumX >= minimumX) { "Terrain page-area x range must not be empty" }
        require(maximumY >= minimumY) { "Terrain page-area y range must not be empty" }
        require(maximumZ >= minimumZ) { "Terrain page-area z range must not be empty" }
    }

    override fun matches(page: TerrainPageKey): Boolean =
        page.domain == domain &&
            page.detailLevel in minimumDetailLevel..maximumDetailLevel &&
            page.x in minimumX..maximumX &&
            page.y in minimumY..maximumY &&
            page.z in minimumZ..maximumZ

    override fun canonicalKey(): String = listOf(
        "area",
        domain.name,
        minimumDetailLevel,
        maximumDetailLevel,
        minimumX,
        maximumX,
        minimumY,
        maximumY,
        minimumZ,
        maximumZ,
    ).joinToString(":")
}

/**
 * Prefix fields follow the canonical page order: domain, detail, z, y, x.
 * Optional coordinates may only be supplied after every preceding field.
 */
data class TerrainPagePrefixSelector(
    override val domain: TerrainDomain,
    val detailLevel: Int,
    val z: Long? = null,
    val y: Long? = null,
    val x: Long? = null,
) : TerrainPageSelector {
    init {
        require(detailLevel >= 0) { "Terrain page-prefix detail level must not be negative" }
        require(y == null || z != null) {
            "Terrain page-prefix y requires a z prefix"
        }
        require(x == null || y != null) {
            "Terrain page-prefix x requires z and y prefixes"
        }
    }

    override fun matches(page: TerrainPageKey): Boolean =
        page.domain == domain &&
            page.detailLevel == detailLevel &&
            (z == null || page.z == z) &&
            (y == null || page.y == y) &&
            (x == null || page.x == x)

    override fun canonicalKey(): String = listOf(
        "prefix",
        domain.name,
        detailLevel.toString(),
        z?.toString().orEmpty(),
        y?.toString().orEmpty(),
        x?.toString().orEmpty(),
    ).joinToString(":")
}

@JvmInline
value class TerrainPageCursor(val opaqueValue: String) {
    init {
        require(opaqueValue.isNotBlank()) { "Terrain page cursor must not be blank" }
        require(opaqueValue.length <= TerrainDiagnosticSchema.MAXIMUM_CURSOR_LENGTH) {
            "Terrain page cursor exceeds the schema bound"
        }
        require(
            opaqueValue.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
            },
        ) {
            "Terrain page cursor must use URL-safe opaque characters"
        }
    }
}

data class TerrainPageQuery(
    val worldEpoch: Long,
    val selector: TerrainPageSelector,
    val maximumCount: Int,
    val cursor: TerrainPageCursor? = null,
) {
    init {
        require(worldEpoch >= 0L) { "Terrain page query world epoch must not be negative" }
        require(maximumCount in 1..TerrainDiagnosticSchema.MAXIMUM_PAGE_COUNT) {
            "Terrain page query maximum count is outside the schema bound"
        }
    }

    internal fun cursorBinding(): String =
        "$worldEpoch:${selector.canonicalKey()}:$maximumCount"
}

sealed interface TerrainPageCursorResolution {
    data class Accepted(val lastPage: TerrainPageKey) : TerrainPageCursorResolution
    data class Rejected(val rejection: TerrainDiagnosticRejection) : TerrainPageCursorResolution
}

object TerrainPageCursorCodec {
    private const val MAGIC: Int = 0x54444331
    private const val SELECTOR_DIGEST_BYTES: Int = 32
    private const val ENCODED_BYTES: Int =
        Int.SIZE_BYTES +
            Long.SIZE_BYTES +
            SELECTOR_DIGEST_BYTES +
            Byte.SIZE_BYTES +
            Int.SIZE_BYTES +
            Long.SIZE_BYTES * 4

    fun issue(
        diagnosticGeneration: Long,
        query: TerrainPageQuery,
        lastPage: TerrainPageKey,
    ): TerrainPageCursor {
        require(diagnosticGeneration >= 0L) {
            "Terrain diagnostic generation must not be negative"
        }
        require(lastPage.worldEpoch == query.worldEpoch) {
            "Terrain page cursor must belong to the query world epoch"
        }
        require(query.selector.matches(lastPage)) {
            "Terrain page cursor must belong to the query selector"
        }
        val bytes = ByteBuffer.allocate(ENCODED_BYTES)
            .putInt(MAGIC)
            .putLong(diagnosticGeneration)
            .put(selectorDigest(query))
            .put(lastPage.domain.ordinal.toByte())
            .putInt(lastPage.detailLevel)
            .putLong(lastPage.x)
            .putLong(lastPage.y)
            .putLong(lastPage.z)
            .putLong(lastPage.worldEpoch)
            .array()
        return TerrainPageCursor(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }

    fun resolve(
        cursor: TerrainPageCursor,
        diagnosticGeneration: Long,
        query: TerrainPageQuery,
    ): TerrainPageCursorResolution {
        require(diagnosticGeneration >= 0L) {
            "Terrain diagnostic generation must not be negative"
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(cursor.opaqueValue)
        } catch (_: IllegalArgumentException) {
            return malformed(diagnosticGeneration)
        }
        if (bytes.size != ENCODED_BYTES) return malformed(diagnosticGeneration)

        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) return malformed(diagnosticGeneration)
        val cursorGeneration = buffer.long
        if (cursorGeneration < 0L) return malformed(diagnosticGeneration)
        val cursorSelectorDigest = ByteArray(SELECTOR_DIGEST_BYTES)
        buffer.get(cursorSelectorDigest)
        val domainOrdinal = buffer.get().toInt()
        if (domainOrdinal !in TerrainDomain.entries.indices) return malformed(diagnosticGeneration)
        val detailLevel = buffer.int
        if (detailLevel < 0) return malformed(diagnosticGeneration)
        val x = buffer.long
        val y = buffer.long
        val z = buffer.long
        val worldEpoch = buffer.long
        if (worldEpoch < 0L) return malformed(diagnosticGeneration)
        val lastPage = TerrainPageKey(
            domain = TerrainDomain.entries[domainOrdinal],
            detailLevel = detailLevel,
            x = x,
            y = y,
            z = z,
            worldEpoch = worldEpoch,
        )

        if (cursorGeneration != diagnosticGeneration) {
            return TerrainPageCursorResolution.Rejected(
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.STALE_CURSOR,
                    diagnosticGeneration = diagnosticGeneration,
                    expectedGeneration = diagnosticGeneration,
                    actualGeneration = cursorGeneration,
                ),
            )
        }
        if (!MessageDigest.isEqual(cursorSelectorDigest, selectorDigest(query))) {
            return TerrainPageCursorResolution.Rejected(
                TerrainDiagnosticRejection(
                    code = TerrainDiagnosticRejectionCode.CURSOR_SELECTOR_MISMATCH,
                    diagnosticGeneration = diagnosticGeneration,
                ),
            )
        }
        if (lastPage.worldEpoch != query.worldEpoch || !query.selector.matches(lastPage)) {
            return malformed(diagnosticGeneration)
        }
        return TerrainPageCursorResolution.Accepted(lastPage)
    }

    private fun selectorDigest(query: TerrainPageQuery): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(query.cursorBinding().encodeToByteArray())

    private fun malformed(diagnosticGeneration: Long): TerrainPageCursorResolution =
        TerrainPageCursorResolution.Rejected(
            TerrainDiagnosticRejection(
                code = TerrainDiagnosticRejectionCode.MALFORMED_CURSOR,
                diagnosticGeneration = diagnosticGeneration,
            ),
        )
}
