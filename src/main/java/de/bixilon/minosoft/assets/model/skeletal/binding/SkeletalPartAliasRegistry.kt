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

package de.bixilon.minosoft.assets.model.skeletal.binding

import de.bixilon.minosoft.data.registries.identified.ResourceLocation

data class SkeletalPartAliasSet(
    val id: String,
    val entity: ResourceLocation?,
    val minimumVersionId: Int = Int.MIN_VALUE,
    val maximumVersionId: Int = Int.MAX_VALUE,
    val aliases: Map<String, String>,
) {
    init {
        require(id.isNotBlank()) { "Part-alias set id must not be blank." }
        require(minimumVersionId <= maximumVersionId) { "Invalid part-alias version range." }
        require(aliases.keys.none(String::isBlank) && aliases.values.none(String::isBlank)) { "Part aliases must not be blank." }
    }

    fun supports(versionId: Int, entity: ResourceLocation) =
        versionId in minimumVersionId..maximumVersionId && (this.entity == null || this.entity == entity)
}

/**
 * Version-aware and generation-removable alias registry. Entity-specific sets
 * override generic sets; ambiguous sets at the same specificity are rejected.
 */
class SkeletalPartAliasRegistry(initial: Iterable<SkeletalPartAliasSet> = emptyList()) {
    private val sets = linkedMapOf<String, SkeletalPartAliasSet>()

    init {
        initial.forEach(::registerPermanent)
    }

    @Synchronized
    fun register(set: SkeletalPartAliasSet): AutoCloseable {
        registerPermanent(set)
        return AutoCloseable {
            synchronized(this) {
                sets.remove(set.id, set)
            }
        }
    }

    @Synchronized
    fun resolve(versionId: Int, entity: ResourceLocation, part: String): String {
        val candidates = sets.values.filter { it.supports(versionId, entity) && part in it.aliases }
        if (candidates.isEmpty()) return part
        val exact = candidates.filter { it.entity == entity }
        val selected = if (exact.isNotEmpty()) exact else candidates
        val values = selected.map { it.aliases.getValue(part) }.distinct()
        require(values.size == 1) {
            "Ambiguous skeletal part alias '$part' for $entity at version $versionId: ${selected.joinToString { it.id }}"
        }
        return values.single()
    }

    @Synchronized
    fun snapshot(): List<SkeletalPartAliasSet> = sets.values.toList()

    private fun registerPermanent(set: SkeletalPartAliasSet) {
        require(sets.putIfAbsent(set.id, set) == null) { "Part-alias set id is already registered: ${set.id}" }
    }

    companion object {
        val HUMANOID = SkeletalPartAliasSet(
            id = "minosoft:humanoid",
            entity = null,
            aliases = mapOf(
                "headwear" to "hat",
                "leftArm" to "left_arm",
                "rightArm" to "right_arm",
                "leftLeg" to "left_leg",
                "rightLeg" to "right_leg",
            ),
        )
    }
}

object SkeletalPartAliases {
    private val registry = SkeletalPartAliasRegistry()

    fun register(set: SkeletalPartAliasSet) = registry.register(set)
    fun resolve(versionId: Int, entity: ResourceLocation, part: String) = registry.resolve(versionId, entity, part)
    fun snapshot() = registry.snapshot()
}
