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

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourcePlan
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic

enum class ShaderProgramPhase {
    SHADOW,
    TERRAIN,
    ENTITY,
    SKY,
    WEATHER,
    COMPOSITE,
    FINAL,
}

data class ShaderProgramSource(
    val name: String,
    val phase: ShaderProgramPhase,
    val vertex: String,
    val fragment: String,
    val uniforms: Set<String>,
    val samplers: Set<String>,
)

data class ShaderPipelinePlan(
    val owner: RenderOwnerId,
    val packName: String,
    val fingerprint: String,
    val views: Set<RenderViewId>,
    val resources: RenderResourcePlan,
    val programs: List<ShaderProgramSource>,
    val requiredTerrainSemantics: Set<VertexSemantic>,
) {
    init {
        require(packName.isNotBlank()) { "Shader-pack name must not be blank" }
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Shader-pack fingerprint must be SHA-256" }
        require(RenderViewId.MAIN in views) { "Shader pipeline must declare the main view" }
        require(programs.isNotEmpty()) { "Shader pipeline must contain at least one program" }
        require(programs.map(ShaderProgramSource::name).toSet().size == programs.size) {
            "Shader pipeline contains duplicate program names"
        }
    }
}
