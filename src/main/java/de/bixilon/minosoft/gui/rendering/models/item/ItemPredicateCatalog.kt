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

package de.bixilon.minosoft.gui.rendering.models.item

import de.bixilon.minosoft.protocol.protocol.ProtocolVersions

private val BASE_ITEM_PREDICATE_PROVIDERS = setOf(
    "angle",
    "blocking",
    "broken",
    "cast",
    "charged",
    "cooldown",
    "custom_model_data",
    "damage",
    "damaged",
    "filled",
    "firework",
    "lefthanded",
    "level",
    "pull",
    "pulling",
    "throwing",
    "time",
    "tooting",
)

/**
 * Native legacy item-property providers admitted for an audited client release.
 *
 * Item-specific applicability remains in [ItemPredicate]; this catalog prevents
 * a provider introduced by a later release from leaking into an older session.
 */
enum class ItemPredicateCatalog(
    private val providers: Set<String>,
) {
    LEGACY_COMPATIBILITY(BASE_ITEM_PREDICATE_PROVIDERS),
    V1_19_4(BASE_ITEM_PREDICATE_PROVIDERS),
    V1_20_4(BASE_ITEM_PREDICATE_PROVIDERS + setOf("brushing", "trim_type")),
    ;

    fun supports(path: String): Boolean = path in providers

    companion object {
        fun forVersion(versionId: Int): ItemPredicateCatalog = when {
            versionId >= ProtocolVersions.V_1_20_RC1 -> V1_20_4
            versionId >= ProtocolVersions.V_1_19_4 -> V1_19_4
            else -> LEGACY_COMPATIBILITY
        }
    }
}
