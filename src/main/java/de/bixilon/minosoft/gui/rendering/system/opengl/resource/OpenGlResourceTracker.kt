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

package de.bixilon.minosoft.gui.rendering.system.opengl.resource

import java.util.EnumMap

enum class OpenGlResourceType {
    BUFFER,
    VERTEX_ARRAY,
    TEXTURE,
    RENDERBUFFER,
    FRAMEBUFFER,
    SHADER,
    PROGRAM,
    QUERY,
}

data class OpenGlResourceTypeSnapshot(
    val type: OpenGlResourceType,
    val created: Long,
    val deleted: Long,
    val live: Int,
)

data class OpenGlResourceSnapshot(
    val types: List<OpenGlResourceTypeSnapshot>,
) {
    val created: Long get() = types.sumOf { it.created }
    val deleted: Long get() = types.sumOf { it.deleted }
    val live: Int get() = types.sumOf { it.live }

    operator fun get(type: OpenGlResourceType): OpenGlResourceTypeSnapshot {
        return types.first { it.type == type }
    }
}

/**
 * Accounts for context-owned OpenGL names. Namespaces are independent by
 * resource type, so the same numeric handle may be live in multiple types.
 */
class OpenGlResourceTracker {
    private val live = EnumMap<OpenGlResourceType, MutableSet<Int>>(OpenGlResourceType::class.java)
    private val created = EnumMap<OpenGlResourceType, Long>(OpenGlResourceType::class.java)
    private val deleted = EnumMap<OpenGlResourceType, Long>(OpenGlResourceType::class.java)

    init {
        for (type in OpenGlResourceType.entries) {
            live[type] = linkedSetOf()
            created[type] = 0L
            deleted[type] = 0L
        }
    }

    @Synchronized
    fun created(type: OpenGlResourceType, handle: Int) {
        require(handle > 0) { "OpenGL created invalid ${type.name.lowercase()} handle $handle." }
        check(live.getValue(type).add(handle)) {
            "OpenGL ${type.name.lowercase()} handle $handle is already live."
        }
        created[type] = created.getValue(type) + 1L
    }

    @Synchronized
    fun deleted(type: OpenGlResourceType, handle: Int) {
        require(handle > 0) { "OpenGL deleted invalid ${type.name.lowercase()} handle $handle." }
        check(live.getValue(type).remove(handle)) {
            "OpenGL ${type.name.lowercase()} handle $handle was not live."
        }
        deleted[type] = deleted.getValue(type) + 1L
    }

    @Synchronized
    fun snapshot(): OpenGlResourceSnapshot {
        return OpenGlResourceSnapshot(OpenGlResourceType.entries.map { type ->
            OpenGlResourceTypeSnapshot(
                type = type,
                created = created.getValue(type),
                deleted = deleted.getValue(type),
                live = live.getValue(type).size,
            )
        })
    }
}
