/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.local.datapack

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kutil.concurrent.lock.LockUtil.acquired
import de.bixilon.minosoft.assets.datapack.DataPackCommandContext
import de.bixilon.minosoft.assets.datapack.DataPackEntityAccess
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.util.UUID

class LocalDataPackEntityAccess(
    private val session: PlaySession,
    private val factory: LocalDisplayEntityFactory,
    private val origin: () -> Vec3d,
) : DataPackEntityAccess {

    override fun select(selector: String, context: DataPackCommandContext): List<Entity> {
        UUID_PATTERN.matchEntire(selector)?.let {
            return session.world.entities[UUID.fromString(selector)]?.let(::listOf) ?: emptyList()
        }
        val match = SELECTOR.matchEntire(selector)
            ?: throw IllegalArgumentException("Unsupported local entity selector $selector")
        val kind = match.groupValues[1]
        val filters = parseFilters(match.groupValues[2])
        val entities = session.world.entities
        val snapshot = entities.lock.acquired { entities.entities.toList() }
        var selected = when (kind) {
            "s" -> context.executor?.let(::listOf) ?: emptyList()
            "a", "p" -> snapshot.filterIsInstance<PlayerEntity>()
            "e", "n" -> snapshot
            else -> throw IllegalArgumentException("Unsupported local entity selector @$kind")
        }
        filters.type?.let { (negated, type) ->
            selected = selected.filter { (it.type.identifier == type) != negated }
        }
        for ((negated, tag) in filters.tags) {
            selected = selected.filter { (tag in it.commandTags) != negated }
        }
        val center = context.position ?: context.executor?.physics?.position ?: origin()
        filters.distance?.let { range ->
            selected = selected.filter {
                val distance = kotlin.math.sqrt(distanceSquared(it.physics.position, center))
                distance >= range.first && distance <= range.second
            }
        }
        if (kind == "n" || kind == "p" || filters.sort == "nearest") {
            selected = selected.sortedBy { distanceSquared(it.physics.position, center) }
        } else if (filters.sort == "furthest") {
            selected = selected.sortedByDescending { distanceSquared(it.physics.position, center) }
        }
        val limit = filters.limit ?: if (kind == "n" || kind == "p") 1 else Int.MAX_VALUE
        return selected.take(limit)
    }

    override fun synchronize(entity: Entity) = factory.synchronize(entity)

    override fun remove(entity: Entity) = factory.remove(entity)

    private fun parseFilters(source: String): Filters {
        if (source.isEmpty()) return Filters()
        var type: Pair<Boolean, ResourceLocation>? = null
        val tags = mutableListOf<Pair<Boolean, String>>()
        var distance: Pair<Double, Double>? = null
        var limit: Int? = null
        var sort: String? = null
        for (entry in source.split(',').map(String::trim).filter(String::isNotEmpty)) {
            val separator = entry.indexOf('=')
            require(separator > 0) { "Malformed selector filter $entry" }
            val key = entry.substring(0, separator)
            val raw = entry.substring(separator + 1)
            val negated = raw.startsWith('!')
            val value = raw.removePrefix("!")
            when (key) {
                "type" -> type = negated to ResourceLocation.of(if (':' in value) value else "minecraft:$value")
                "tag" -> tags += negated to value
                "distance" -> {
                    require(!negated) { "Negated distance selectors are unsupported." }
                    val parts = value.split("..", limit = 2)
                    val parsed = if (parts.size == 1) {
                        val exact = parts[0].toDouble()
                        exact to exact
                    } else {
                        (parts[0].toDoubleOrNull() ?: 0.0) to (parts[1].toDoubleOrNull() ?: Double.POSITIVE_INFINITY)
                    }
                    require(!parsed.first.isNaN() && !parsed.second.isNaN() && parsed.first >= 0.0 && parsed.first <= parsed.second) {
                        "Invalid selector distance $value"
                    }
                    distance = parsed
                }
                "limit" -> {
                    require(!negated) { "Negated selector limits are unsupported." }
                    limit = value.toInt().also { require(it >= 0) { "Negative selector limit $value" } }
                }
                "sort" -> {
                    require(!negated && value in SORTS) { "Unsupported selector sort $raw" }
                    sort = value
                }
                else -> throw IllegalArgumentException("Unsupported local selector filter $key")
            }
        }
        return Filters(type, tags, distance, limit, sort)
    }

    private fun distanceSquared(left: Vec3d, right: Vec3d): Double {
        val x = left.x - right.x
        val y = left.y - right.y
        val z = left.z - right.z
        return x * x + y * y + z * z
    }

    private data class Filters(
        val type: Pair<Boolean, ResourceLocation>? = null,
        val tags: List<Pair<Boolean, String>> = emptyList(),
        val distance: Pair<Double, Double>? = null,
        val limit: Int? = null,
        val sort: String? = null,
    )

    private companion object {
        val SELECTOR = Regex("""@([seanp])(?:\[(.*)])?""")
        val UUID_PATTERN = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        val SORTS = setOf("nearest", "furthest", "arbitrary")
    }
}
