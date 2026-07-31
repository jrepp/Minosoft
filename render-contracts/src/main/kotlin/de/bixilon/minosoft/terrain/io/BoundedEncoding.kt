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

package de.bixilon.minosoft.terrain.io

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

internal class BoundedByteArrayOutputStream(private val maximumBytes: Int) :
    ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_INITIAL_CAPACITY)) {
    init {
        require(maximumBytes > 0) { "Encoded byte limit must be positive" }
    }

    override fun write(value: Int) {
        require(count < maximumBytes) { "Encoding exceeds $maximumBytes bytes" }
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid encoded byte range" }
        require(length <= maximumBytes - count) { "Encoding exceeds $maximumBytes bytes" }
        super.write(bytes, offset, length)
    }

    private companion object {
        const val DEFAULT_INITIAL_CAPACITY = 8 * 1024
    }
}

internal fun decodeStrictUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    throw IllegalArgumentException("Encoded text is not valid UTF-8", error)
}
