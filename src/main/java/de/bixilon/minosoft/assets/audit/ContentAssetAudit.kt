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

package de.bixilon.minosoft.assets.audit

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.TreeMap
import java.util.TreeSet

/**
 * Generation-owned inventory of asset lookups that reached a real missing
 * boundary. Retaining the lexicographically smallest bounded set makes the
 * snapshot independent of concurrent model-loading order, including when
 * content exceeds the diagnostic limit.
 */
class ContentAssetAudit {
    enum class Kind(val wireName: String) {
        BLOCKSTATE("blockstate"),
        MODEL("model"),
        TEXTURE("texture"),
    }

    data class Entry(
        val kind: Kind,
        val resource: String,
        val target: String,
        val consumers: List<String>,
        val consumersTruncated: Boolean,
    )

    data class Snapshot(
        val entries: List<Entry>,
        val fingerprint: String,
        val truncated: Boolean,
    ) {
        fun count(kind: Kind): Int = entries.count { it.kind == kind }
    }

    private data class MutableEntry(
        val consumers: TreeSet<String> = TreeSet(),
        var consumersTruncated: Boolean = false,
    )

    private val entries = TreeMap<String, MutableEntry>()
    private var truncated = false

    @Synchronized
    fun missing(kind: Kind, resource: ResourceLocation, consumer: ResourceLocation? = null) {
        missing(kind, resource.toString(), consumer?.toString())
    }

    @Synchronized
    fun missing(kind: Kind, resource: String, consumer: String? = null) {
        val normalizedResource = resource.trim()
        require(normalizedResource.isNotEmpty()) { "Missing asset resource must not be blank" }
        require(normalizedResource.length <= MAX_IDENTIFIER_LENGTH) {
            "Missing asset resource exceeds $MAX_IDENTIFIER_LENGTH characters"
        }
        require(RESOURCE_PATTERN.matches(normalizedResource) && normalizedResource.substringAfter(':').split('/').none { it == ".." }) {
            "Missing asset resource is not a canonical resource location: $normalizedResource"
        }
        val key = kind.wireName + "\u0000" + normalizedResource
        val entry = entries.getOrPut(key, ::MutableEntry)
        consumer?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(it.length <= MAX_IDENTIFIER_LENGTH && RESOURCE_PATTERN.matches(it)) {
                "Missing asset consumer is not a bounded canonical resource location: $it"
            }
            entry.consumers += it
            if (entry.consumers.size > MAX_CONSUMERS_PER_RESOURCE) {
                entry.consumers.pollLast()
                entry.consumersTruncated = true
            }
        }
        if (entries.size > MAX_RESOURCES) {
            entries.pollLastEntry()
            truncated = true
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val immutable = entries.map { (key, value) ->
            val split = key.indexOf('\u0000')
            val kindName = key.substring(0, split)
            val resource = key.substring(split + 1)
            val kind = Kind.entries.single { it.wireName == kindName }
            Entry(kind, resource, target(resource), value.consumers.toList(), value.consumersTruncated)
        }
        return Snapshot(immutable, fingerprint(immutable, truncated), truncated)
    }

    private fun target(resource: String): String {
        val separator = resource.indexOf(':')
        if (separator <= 0 || separator == resource.lastIndex) return resource
        return "assets/${resource.substring(0, separator)}/${resource.substring(separator + 1)}"
    }

    private fun fingerprint(entries: List<Entry>, truncated: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(AUDIT_VERSION.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(if (truncated) 1 else 0)
        for (entry in entries) {
            digest.update(entry.kind.wireName.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(entry.resource.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(if (entry.consumersTruncated) 1 else 0)
            for (consumer in entry.consumers) {
                digest.update(consumer.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    companion object {
        const val SCHEMA = 1
        const val AUDIT_VERSION = "1"
        // content.audit is returned through a debug frame whose total payload is 16 MiB.
        // These limits keep the complete worst-case JSON response below that transport bound.
        private const val MAX_RESOURCES = 2_048
        private const val MAX_CONSUMERS_PER_RESOURCE = 16
        private const val MAX_IDENTIFIER_LENGTH = 256
        private val RESOURCE_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    }
}
