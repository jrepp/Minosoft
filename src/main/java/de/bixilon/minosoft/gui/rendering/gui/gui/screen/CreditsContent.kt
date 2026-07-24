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

package de.bixilon.minosoft.gui.rendering.gui.gui.screen

import com.fasterxml.jackson.databind.JsonNode
import de.bixilon.minosoft.assets.AssetsManager
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.util.json.Jackson

enum class CreditsLineStyle {
    TEXT,
    SECTION,
    TITLE,
    NAME,
    SPACER,
}

data class CreditsLine(
    val text: String,
    val style: CreditsLineStyle,
)

object CreditsContent {
    val RESOURCE: ResourceLocation = minosoft("texts/credits.json")

    fun load(assets: AssetsManager): List<CreditsLine> {
        val documents = assets.getAllOrNull(RESOURCE).orEmpty().take(MAX_DOCUMENTS).mapNotNull { stream ->
            runCatching {
                val bytes = stream.use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
                require(bytes.size <= MAX_DOCUMENT_BYTES) { "Credits document exceeds $MAX_DOCUMENT_BYTES bytes." }
                Jackson.MAPPER.readTree(bytes)
            }.getOrNull()
        }
        return parse(documents)
    }

    internal fun parse(documents: Iterable<JsonNode>): List<CreditsLine> {
        val lines = mutableListOf<CreditsLine>()

        fun add(text: String, style: CreditsLineStyle) {
            if (lines.size >= MAX_LINES) return
            lines += CreditsLine(text.take(MAX_TEXT_LENGTH), style)
        }

        for (credits in documents.take(MAX_DOCUMENTS)) {
            if (!credits.isArray) {
                continue
            }
            for (section in credits.take(MAX_SECTIONS_PER_DOCUMENT)) {
                val sectionName = section.path("section").asText()
                if (sectionName.isNotEmpty()) {
                    add(sectionName, CreditsLineStyle.SECTION)
                }
                add("", CreditsLineStyle.SPACER)

                for (title in section.path("titles").take(MAX_TITLES_PER_SECTION)) {
                    val titleName = title.path("title").asText()
                    if (titleName.isNotEmpty()) {
                        add(titleName, CreditsLineStyle.TITLE)
                    }
                    for (name in title.path("names").take(MAX_NAMES_PER_TITLE)) {
                        val value = name.asText()
                        if (value.isNotEmpty()) {
                            add(value, CreditsLineStyle.NAME)
                        }
                    }
                    add("", CreditsLineStyle.SPACER)
                }
                add("", CreditsLineStyle.SPACER)
            }
        }

        if (lines.isEmpty()) {
            lines += CreditsLine("Minosoft", CreditsLineStyle.SECTION)
            lines += CreditsLine("No local or mod-supplied credits were found.", CreditsLineStyle.TEXT)
        }
        return lines
    }

    private const val MAX_DOCUMENTS = 64
    private const val MAX_DOCUMENT_BYTES = 1024 * 1024
    private const val MAX_SECTIONS_PER_DOCUMENT = 256
    private const val MAX_TITLES_PER_SECTION = 256
    private const val MAX_NAMES_PER_TITLE = 4_096
    private const val MAX_LINES = 65_536
    private const val MAX_TEXT_LENGTH = 1_024
}

class CreditsScrollState {
    var pixels: Float = 0.0f
        private set

    fun advance(spaceDown: Boolean, controlDown: Boolean): Float {
        val multiplier = when {
            controlDown -> CONTROL_MULTIPLIER
            spaceDown -> SPACE_MULTIPLIER
            else -> 1.0f
        }
        pixels += PIXELS_PER_TICK * multiplier
        return pixels
    }

    fun complete(viewportHeight: Float, contentHeight: Float): Boolean {
        return pixels > viewportHeight + contentHeight + viewportHeight / 2.0f
    }

    companion object {
        const val PIXELS_PER_TICK = 1.5f
        const val SPACE_MULTIPLIER = 5.0f
        const val CONTROL_MULTIPLIER = 20.0f
    }
}
