/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.io.InputStream

fun interface SkeletalResourceResolver {
    fun open(reference: String): InputStream?
}
data class SkeletalParseContext(
    val source: ResourceLocation,
    val resources: SkeletalResourceResolver = SkeletalResourceResolver { null },
)

interface SkeletalGeometryParser {
    val format: SkeletalContentFormat
    val suffixes: Set<String>
    fun parse(context: SkeletalParseContext, input: InputStream): SkeletalContentDocument
}

interface SkeletalAnimationParser {
    val format: SkeletalContentFormat
    val suffixes: Set<String>
    fun parse(context: SkeletalParseContext, input: InputStream): Map<String, SkeletalAnimationClip>
}

data class SkeletalParserRegistration(
    val id: String,
    val geometry: SkeletalGeometryParser? = null,
    val animation: SkeletalAnimationParser? = null,
) {
    init {
        require(id.isNotBlank()) { "Skeletal parser registration id must not be blank." }
        require(geometry != null || animation != null) { "Skeletal parser registration must expose geometry or animation parsing." }
    }
}

/**
 * Process-stable parser catalog. Registrations are removable and are expected
 * to be owned by the activating compatibility generation.
 */
object SkeletalContentParsers {
    private val registrations = linkedMapOf<String, SkeletalParserRegistration>()

    @Synchronized
    fun register(registration: SkeletalParserRegistration): AutoCloseable {
        require(registrations.putIfAbsent(registration.id, registration) == null) {
            "Skeletal parser registration id is already registered: ${registration.id}"
        }
        return AutoCloseable {
            synchronized(this) {
                registrations.remove(registration.id, registration)
            }
        }
    }

    @Synchronized
    fun geometry(source: ResourceLocation): SkeletalGeometryParser? {
        return select(source.path, registrations.values.mapNotNull { it.geometry }) { it.suffixes }
    }

    @Synchronized
    fun animation(source: ResourceLocation): SkeletalAnimationParser? {
        return select(source.path, registrations.values.mapNotNull { it.animation }) { it.suffixes }
    }

    @Synchronized
    fun snapshot(): List<SkeletalParserRegistration> = registrations.values.toList()

    private fun <T> select(path: String, values: List<T>, suffixes: (T) -> Set<String>): T? {
        val matches = values.mapNotNull { parser ->
            suffixes(parser).filter { path.endsWith(it, ignoreCase = true) }.maxByOrNull(String::length)?.let { parser to it.length }
        }
        if (matches.isEmpty()) return null
        val longest = matches.maxOf { it.second }
        val selected = matches.filter { it.second == longest }.map { it.first }
        require(selected.size == 1) { "Multiple skeletal parsers match $path." }
        return selected.single()
    }
}
