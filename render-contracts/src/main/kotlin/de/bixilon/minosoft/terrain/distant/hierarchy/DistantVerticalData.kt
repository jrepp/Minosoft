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

package de.bixilon.minosoft.terrain.distant.hierarchy

import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.HexFormat
import java.util.WeakHashMap

enum class DistantRunFlag {
    OPAQUE,
    EMISSIVE,
    CAVE,
    VOID,
    GENERATED,
    SURFACE_ONLY,
}

data class DistantFluidSample(
    val material: TerrainSemanticMaterialId,
    val level: Int,
    val classification: String,
) {
    init {
        require(level in 0..15) { "Distant fluid level must be within 0..15" }
        require(NORMALIZED_ID.matches(classification)) { "Distant fluid classification must be normalized" }
    }

    private companion object {
        val NORMALIZED_ID = Regex("[a-z0-9_.-]+(?::[a-z0-9/._-]+)?")
    }
}

data class DistantTintSample(
    val resolvedRgb: Int?,
    val biomeInput: String?,
    val generation: Long,
) {
    init {
        require(resolvedRgb == null || resolvedRgb in 0..0xFFFFFF) { "Distant tint RGB is invalid" }
        require(biomeInput == null || biomeInput.isNotBlank()) { "Distant biome tint input must not be blank" }
        require(generation >= 0L) { "Distant tint generation must not be negative" }
        require(resolvedRgb != null || biomeInput != null) { "Distant tint sample has no input" }
    }
}

class DistantColumnRun(
    val minimumY: Int,
    val height: Int,
    val material: TerrainSemanticMaterialId?,
    val fluid: DistantFluidSample?,
    val blockLight: Int,
    val skyLight: Int,
    val tint: DistantTintSample?,
    flags: Set<DistantRunFlag>,
    val confidence: Int,
) {
    internal val flagBits: Int = flags.fold(0) { bits, flag -> bits or (1 shl flag.ordinal) }
    val flags: Set<DistantRunFlag> = CANONICAL_FLAG_SETS[flagBits]
    val maximumYExclusive: Int = Math.addExact(minimumY, height)

    init {
        require(height > 0) { "Distant vertical run height must be positive" }
        require(blockLight in 0..15 && skyLight in 0..15) { "Distant run light must be within 0..15" }
        require(confidence in 0..100) { "Distant run confidence must be within 0..100" }
        require(material != null || DistantRunFlag.CAVE in this.flags || DistantRunFlag.VOID in this.flags) {
            "Distant material may be absent only for cave/void runs"
        }
        require(!(DistantRunFlag.CAVE in this.flags && DistantRunFlag.VOID in this.flags)) {
            "Distant run cannot be both cave and void"
        }
    }

    override fun equals(other: Any?): Boolean = this === other || other is DistantColumnRun &&
        minimumY == other.minimumY &&
        height == other.height &&
        material == other.material &&
        fluid == other.fluid &&
        blockLight == other.blockLight &&
        skyLight == other.skyLight &&
        tint == other.tint &&
        flagBits == other.flagBits &&
        confidence == other.confidence

    override fun hashCode(): Int {
        var result = minimumY
        result = 31 * result + height
        result = 31 * result + (material?.hashCode() ?: 0)
        result = 31 * result + (fluid?.hashCode() ?: 0)
        result = 31 * result + blockLight
        result = 31 * result + skyLight
        result = 31 * result + (tint?.hashCode() ?: 0)
        result = 31 * result + flagBits
        result = 31 * result + confidence
        return result
    }

    private companion object {
        val CANONICAL_FLAG_SETS: Array<Set<DistantRunFlag>> =
            Array(1 shl DistantRunFlag.entries.size) { bits ->
                java.util.Set.copyOf(DistantRunFlag.entries.filter { bits and (1 shl it.ordinal) != 0 })
            }
    }
}

class DistantVerticalColumn(runs: Collection<DistantColumnRun>) {
    val runs: List<DistantColumnRun> = java.util.List.copyOf(runs)
    @Volatile private var cachedDigest: String? = null
    val digest: String
        get() = cachedDigest ?: digest(this.runs).also { cachedDigest = it }

    init {
        require(this.runs.size <= MAXIMUM_RUNS) { "Distant column exceeds $MAXIMUM_RUNS runs" }
        for (index in 1 until this.runs.size) {
            val upper = this.runs[index - 1]
            val lower = this.runs[index]
            require(upper.minimumY >= lower.maximumYExclusive) {
                "Distant runs must be ordered top-to-bottom without overlap"
            }
        }
    }

    companion object {
        const val MAXIMUM_RUNS = 64

        internal fun semanticDigest(runs: List<DistantColumnRun>): String = digest(runs)

        private fun digest(runs: List<DistantColumnRun>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.putInt(DIGEST_SCHEMA)
            digest.putInt(runs.size)
            runs.forEach { run ->
                digest.putInt(run.minimumY)
                digest.putInt(run.height)
                digest.putText(run.material?.value)
                digest.putText(run.fluid?.material?.value)
                digest.putInt(run.fluid?.level ?: -1)
                digest.putText(run.fluid?.classification)
                digest.putInt(run.blockLight)
                digest.putInt(run.skyLight)
                digest.putInt(run.tint?.resolvedRgb ?: -1)
                digest.putText(run.tint?.biomeInput)
                digest.putLong(run.tint?.generation ?: -1L)
                digest.putInt(run.flags.fold(0) { bits, flag -> bits or (1 shl flag.ordinal) })
                digest.putInt(run.confidence)
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private const val DIGEST_SCHEMA = 1
    }
}

/**
 * Shares immutable vertical values across pages without making the canonical table an owner.
 * Both keys and values are weak: once pages release a value, ordinary GC can discard it and the
 * next access drains the stale weak-map entry. Stripes keep parallel hierarchy builds independent.
 */
internal class DistantWeakCanonicalizer<T : Any>(stripeCount: Int = 32) {
    private class Stripe<T : Any> {
        val values = WeakHashMap<T, WeakReference<T>>()
    }

    private val stripes = Array(stripeCount) { Stripe<T>() }

    init {
        require(stripeCount > 0 && stripeCount.countOneBits() == 1) {
            "Distant canonicalizer stripe count must be a positive power of two"
        }
    }

    fun canonicalize(value: T): T {
        val stripe = stripes[value.hashCode() and (stripes.size - 1)]
        synchronized(stripe) {
            stripe.values[value]?.get()?.let { return it }
            stripe.values[value] = WeakReference(value)
            return value
        }
    }
}

object DistantColumnReducer {
    /**
     * Selects a deterministic bounded subset without inventing heights. The
     * top/bottom extrema and first opaque/fluid runs are mandatory; remaining
     * capacity favors emissive, fluid, material, light, and large-height
     * transitions in that order.
     */
    fun reduce(column: DistantVerticalColumn, maximumRuns: Int): DistantVerticalColumn {
        require(maximumRuns > 0 && maximumRuns <= DistantVerticalColumn.MAXIMUM_RUNS)
        if (column.runs.size <= maximumRuns) return column
        return reduce(column.runs, maximumRuns)
    }

    fun reduce(runs: Collection<DistantColumnRun>, maximumRuns: Int): DistantVerticalColumn {
        require(maximumRuns > 0 && maximumRuns <= DistantVerticalColumn.MAXIMUM_RUNS)
        val ordered = java.util.List.copyOf(runs)
        if (ordered.size <= maximumRuns) return DistantVerticalColumn(ordered)
        val mandatory = linkedSetOf(0, ordered.lastIndex)
        ordered.indexOfFirst { DistantRunFlag.OPAQUE in it.flags }
            .takeIf { it >= 0 }?.let(mandatory::add)
        ordered.indexOfFirst { it.fluid != null }
            .takeIf { it >= 0 }?.let(mandatory::add)
        require(maximumRuns >= mandatory.size) {
            "Distant reducer limit $maximumRuns cannot preserve $mandatory mandatory extrema/surfaces"
        }

        val selected = mandatory.toMutableSet()
        val candidates = ordered.indices
            .filterNot(selected::contains)
            .sortedWith(compareByDescending<Int> { score(ordered, it) }.thenBy { it })
        selected += candidates.take(maximumRuns - selected.size)
        return DistantVerticalColumn(selected.sorted().map(ordered::get))
    }

    private fun score(runs: List<DistantColumnRun>, index: Int): Long {
        val run = runs[index]
        var score = run.height.toLong() * 1_000L + run.confidence
        if (DistantRunFlag.EMISSIVE in run.flags) score += 1_000_000_000_000L
        if (run.fluid != null) score += 500_000_000_000L
        val above = runs.getOrNull(index - 1)
        val below = runs.getOrNull(index + 1)
        if (above?.material != run.material || below?.material != run.material) score += 100_000_000_000L
        val lightDelta = maxOf(
            lightDelta(run, above),
            lightDelta(run, below),
        )
        score += lightDelta * 1_000_000_000L
        if (DistantRunFlag.CAVE in run.flags || DistantRunFlag.VOID in run.flags) score += 10_000_000_000L
        return score
    }

    private fun lightDelta(run: DistantColumnRun, neighbour: DistantColumnRun?): Long {
        if (neighbour == null) return 0L
        return (kotlin.math.abs(run.blockLight - neighbour.blockLight) +
            kotlin.math.abs(run.skyLight - neighbour.skyLight)).toLong()
    }
}

private fun MessageDigest.putText(value: String?) {
    if (value == null) {
        putInt(-1)
        return
    }
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
