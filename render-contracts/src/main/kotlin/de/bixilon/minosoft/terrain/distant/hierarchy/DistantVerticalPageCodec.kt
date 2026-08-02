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

import de.bixilon.minosoft.terrain.distant.DistantSourceCompleteness
import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.identity.TerrainPageKey
import de.bixilon.minosoft.terrain.model.material.TerrainSemanticMaterialId
import de.bixilon.minosoft.terrain.io.BoundedByteArrayOutputStream
import de.bixilon.minosoft.terrain.io.decodeStrictUtf8
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Canonical bounded binary representation shared by store records and protocol v2. */
object DistantVerticalPageCodec {
    const val SCHEMA_VERSION = 2
    const val MAXIMUM_ENCODED_BYTES = 4 * 1024 * 1024
    private const val MAGIC = 0x44565047
    private const val MAXIMUM_STRINGS = 65_535
    private const val MAXIMUM_STRING_BYTES = 1_024
    private const val MAXIMUM_TOTAL_RUNS = 262_144

    fun encode(page: DistantVerticalPage): ByteArray {
        val strings = linkedMapOf<String, Int>()
        fun add(value: String?) {
            if (value != null) strings.putIfAbsent(value, strings.size + 1)
        }
        for (column in page.columns) for (run in column.runs) {
            add(run.material?.value)
            add(run.fluid?.material?.value)
            add(run.fluid?.classification)
            add(run.tint?.biomeInput)
        }
        require(strings.size <= MAXIMUM_STRINGS) { "Distant page string palette is too large" }
        val totalRuns = page.columns.fold(0) { total, column -> Math.addExact(total, column.runs.size) }
        require(totalRuns <= MAXIMUM_TOTAL_RUNS) { "Distant page run count is out of bounds: $totalRuns" }
        val bytes = BoundedByteArrayOutputStream(MAXIMUM_ENCODED_BYTES)
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeShort(SCHEMA_VERSION)
            output.writeByte(page.key.detailLevel)
            output.writeLong(page.key.x)
            output.writeLong(page.key.y)
            output.writeLong(page.key.z)
            output.writeLong(page.key.worldEpoch)
            output.writeByte(page.width)
            output.writeInt(page.originY)
            output.writeLong(page.sourceRevision)
            output.writeByte(page.completeness.ordinal)
            output.writeShort(strings.size)
            strings.keys.forEach { output.writeBoundedString(it) }
            output.writeInt(totalRuns)
            for (column in page.columns) {
                output.writeByte(column.runs.size)
                for (run in column.runs) output.writeRun(run, strings)
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= MAXIMUM_ENCODED_BYTES) { "Distant page encoding exceeds $MAXIMUM_ENCODED_BYTES bytes" }
        }
    }

    fun decode(bytes: ByteArray): DistantVerticalPage {
        require(bytes.size <= MAXIMUM_ENCODED_BYTES) { "Distant page encoding exceeds $MAXIMUM_ENCODED_BYTES bytes" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid distant page magic" }
            require(input.readUnsignedShort() == SCHEMA_VERSION) { "Unsupported distant page schema" }
            val detailLevel = input.readUnsignedByte()
            val x = input.readLong()
            val y = input.readLong()
            val z = input.readLong()
            val worldEpoch = input.readLong()
            val width = input.readUnsignedByte()
            require(width in 1..64 && width.countOneBits() == 1) { "Invalid distant page width: $width" }
            val originY = input.readInt()
            val sourceRevision = input.readLong()
            val completeness = DistantSourceCompleteness.entries.getOrNull(input.readUnsignedByte())
                ?: throw IllegalArgumentException("Invalid distant page completeness")
            val paletteSize = input.readUnsignedShort()
            require(paletteSize <= MAXIMUM_STRINGS)
            val strings = arrayOfNulls<String>(paletteSize + 1)
            val uniqueStrings = HashSet<String>(paletteSize)
            for (index in 1..paletteSize) {
                val value = input.readBoundedString()
                require(uniqueStrings.add(value)) { "Distant page palette contains a duplicate string" }
                strings[index] = value
            }
            // Material and fluid identifiers are palette values. Validating the
            // same identifier for every vertical run makes a large response
            // spend most of its decode time in regular-expression matching.
            // Keep the wire palette canonical and instantiate each semantic
            // value or fluid combination only once per page.
            val materials = arrayOfNulls<TerrainSemanticMaterialId>(strings.size)
            val fluids = HashMap<DecodedFluidKey, DistantFluidSample>()
            val totalRuns = input.readInt()
            require(totalRuns in 0..MAXIMUM_TOTAL_RUNS) { "Distant page run count is out of bounds: $totalRuns" }
            var decodedRuns = 0
            val columns = ArrayList<DistantVerticalColumn>(Math.multiplyExact(width, width))
            repeat(Math.multiplyExact(width, width)) {
                val runCount = input.readUnsignedByte()
                require(runCount <= DistantVerticalColumn.MAXIMUM_RUNS)
                decodedRuns = Math.addExact(decodedRuns, runCount)
                require(decodedRuns <= totalRuns)
                columns += DistantVerticalColumn(List(runCount) { input.readRun(strings, materials, fluids) })
            }
            require(decodedRuns == totalRuns) { "Distant page run total does not match its columns" }
            require(input.read() == -1) { "Distant page encoding has trailing data" }
            return DistantVerticalPage(
                TerrainPageKey(TerrainDomain.DISTANT, detailLevel, x, y, z, worldEpoch),
                width,
                originY,
                sourceRevision,
                completeness,
                columns,
            )
        }
    }

    fun dump(page: DistantVerticalPage): String = buildString {
        append("schema=").append(SCHEMA_VERSION).append('\n')
        append("key=distant/").append(page.key.detailLevel).append('/')
            .append(page.key.x).append('/').append(page.key.y).append('/').append(page.key.z)
            .append(" epoch=").append(page.key.worldEpoch).append('\n')
        append("width=").append(page.width).append(" originY=").append(page.originY)
            .append(" sourceRevision=").append(page.sourceRevision)
            .append(" completeness=").append(page.completeness.name.lowercase()).append('\n')
        append("semanticDigest=").append(page.semanticDigest).append('\n')
        for ((columnIndex, column) in page.columns.withIndex()) {
            append("column[").append(columnIndex).append("] digest=").append(column.digest)
                .append(" runs=").append(column.runs.size).append('\n')
            for ((runIndex, run) in column.runs.withIndex()) {
                append("  run[").append(runIndex).append("] y=").append(run.minimumY)
                    .append(" height=").append(run.height)
                    .append(" material=").append(run.material?.value ?: "-")
                    .append(" fluid=").append(run.fluid?.material?.value ?: "-")
                    .append(" fluidLevel=").append(run.fluid?.level ?: -1)
                    .append(" fluidClass=").append(run.fluid?.classification ?: "-")
                    .append(" light=").append(run.blockLight).append('/').append(run.skyLight)
                    .append(" tintRgb=").append(run.tint?.resolvedRgb ?: -1)
                    .append(" tintBiome=").append(run.tint?.biomeInput ?: "-")
                    .append(" tintGeneration=").append(run.tint?.generation ?: -1L)
                    .append(" flags=").append(run.flags.sortedBy { it.ordinal }.joinToString(",") { it.name.lowercase() }.ifEmpty { "-" })
                    .append(" confidence=").append(run.confidence).append('\n')
            }
        }
    }

    private fun DataOutputStream.writeRun(run: DistantColumnRun, palette: Map<String, Int>) {
        writeInt(run.minimumY)
        writeInt(run.height)
        writeShort(run.material?.value?.let(palette::getValue) ?: 0)
        writeShort(run.fluid?.material?.value?.let(palette::getValue) ?: 0)
        writeByte(run.fluid?.level ?: 0)
        writeShort(run.fluid?.classification?.let(palette::getValue) ?: 0)
        writeByte(run.blockLight)
        writeByte(run.skyLight)
        writeInt(run.tint?.resolvedRgb ?: -1)
        writeShort(run.tint?.biomeInput?.let(palette::getValue) ?: 0)
        writeLong(run.tint?.generation ?: -1L)
        writeInt(run.flags.fold(0) { bits, flag -> bits or (1 shl flag.ordinal) })
        writeByte(run.confidence)
    }

    private fun DataInputStream.readRun(
        palette: Array<String?>,
        materials: Array<TerrainSemanticMaterialId?>,
        fluids: MutableMap<DecodedFluidKey, DistantFluidSample>,
    ): DistantColumnRun {
        val minimumY = readInt()
        val height = readInt()
        val material = readMaterial(palette, materials)
        val fluidMaterial = readMaterial(palette, materials)
        val fluidLevel = readUnsignedByte()
        val fluidClassification = readPalette(palette)
        val fluid = when {
            fluidMaterial == null && fluidClassification == null -> null
            fluidMaterial != null && fluidClassification != null -> {
                val key = DecodedFluidKey(fluidMaterial, fluidLevel, fluidClassification)
                fluids.getOrPut(key) { DistantFluidSample(fluidMaterial, fluidLevel, fluidClassification) }
            }
            else -> throw IllegalArgumentException("Incomplete distant fluid encoding")
        }
        val blockLight = readUnsignedByte()
        val skyLight = readUnsignedByte()
        val tintRgb = readInt()
        val tintBiome = readPalette(palette)
        val tintGeneration = readLong()
        val tint = when {
            tintRgb < 0 && tintBiome == null && tintGeneration == -1L -> null
            tintGeneration >= 0L && (tintRgb >= 0 || tintBiome != null) ->
                DistantTintSample(tintRgb.takeIf { it >= 0 }, tintBiome, tintGeneration)
            else -> throw IllegalArgumentException("Invalid distant tint encoding")
        }
        val encodedFlags = readInt()
        val knownFlagMask = (1 shl DistantRunFlag.entries.size) - 1
        require(encodedFlags and knownFlagMask.inv() == 0) { "Unknown distant run flag" }
        val flags = DistantRunFlag.entries.filterTo(linkedSetOf()) { encodedFlags and (1 shl it.ordinal) != 0 }
        return DistantColumnRun(
            minimumY, height, material, fluid, blockLight, skyLight, tint, flags, readUnsignedByte(),
        )
    }

    private fun DataInputStream.readMaterial(
        palette: Array<String?>,
        materials: Array<TerrainSemanticMaterialId?>,
    ): TerrainSemanticMaterialId? {
        val index = readUnsignedShort()
        require(index in palette.indices) { "Distant page palette index is out of bounds" }
        if (index == 0) return null
        materials[index]?.let { return it }
        return TerrainSemanticMaterialId(checkNotNull(palette[index])).also { materials[index] = it }
    }

    private data class DecodedFluidKey(
        val material: TerrainSemanticMaterialId,
        val level: Int,
        val classification: String,
    )

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAXIMUM_STRING_BYTES) { "Distant page string exceeds $MAXIMUM_STRING_BYTES bytes" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): String {
        val length = readUnsignedShort()
        require(length <= MAXIMUM_STRING_BYTES) { "Distant page string exceeds $MAXIMUM_STRING_BYTES bytes" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return decodeStrictUtf8(bytes)
    }

    private fun DataInputStream.readPalette(palette: Array<String?>): String? {
        val index = readUnsignedShort()
        require(index in palette.indices) { "Distant page palette index is out of bounds: $index" }
        return palette[index]
    }
}
