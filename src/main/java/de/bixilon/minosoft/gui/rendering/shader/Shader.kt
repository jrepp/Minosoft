/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.shader

import de.bixilon.minosoft.gui.rendering.shader.pipeline.ShaderPipelineRegistry
import de.bixilon.minosoft.gui.rendering.shader.uniform.ShaderUniform
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import java.util.Collections

enum class ShaderPipelineScope {
    SCENE_GEOMETRY,
    INTERNAL_COMPOSITE,
}

abstract class Shader(override val native: NativeShader) : AbstractShader {
    open val pipelineScope: ShaderPipelineScope = ShaderPipelineScope.SCENE_GEOMETRY
    open val sceneContract: SceneShaderContract? = null
    private val sceneProgramFamily = ThreadLocal<SceneProgramFamily?>()
    private val uniforms: MutableMap<String, ShaderUniform> = mutableMapOf()
    internal var uniformRevision: Long = 0L
        private set
    internal var uniformUploadInProgress: Boolean = false
        private set
    val declaredUniforms: Set<String> = Collections.unmodifiableSet(uniforms.keys)

    fun unload() {
        if (native.context.system.shader.shader === this) {
            native.context.system.shader.shader = null
        }
        native.unload()
        native.context.system.shader -= this
    }

    fun load() {
        native.load()
        native.context.system.shader += this
        activate()
        syncUniformsTo(native)
    }

    override fun use() {
        // Some headless integration fixtures allocate RenderContext without
        // running its constructor. Preserve the exact built-in activation path
        // for those contexts while production contexts always own a registry.
        val pipeline: ShaderPipelineRegistry? = native.context.shaderPipeline
        if (pipeline == null) {
            activate()
        } else {
            pipeline.bindShader(this)
        }
    }

    /**
     * Selects a more specific shader-pack program for one complete retained
     * draw without changing the physical vertex/state ABI of this host shader.
     *
     * The family must remain active through uniform setters and mesh helpers:
     * both legitimately call [use] again. The lexical scope keeps those
     * re-entrant binds on the same specialization; a one-shot bind would be
     * replaced by the host shader's default family before the draw reached GL.
     */
    fun <T> withProgramFamily(family: SceneProgramFamily, action: () -> T): T {
        check(sceneProgramFamily.get() == null) {
            "A scene program family override is already active for ${this::class.java.name}"
        }
        sceneProgramFamily.set(family)
        try {
            use()
            return action()
        } finally {
            sceneProgramFamily.remove()
        }
    }

    internal fun effectiveSceneContract(): SceneShaderContract? {
        val contract = sceneContract ?: return null
        val family = sceneProgramFamily.get() ?: return contract
        return contract.copy(family = family)
    }

    override fun uniformTarget(): NativeShader {
        val pipeline: ShaderPipelineRegistry? = native.context.shaderPipeline
        return pipeline?.uniformTarget(this) ?: native
    }

    override fun acceptsUniform(name: String): Boolean {
        val pipeline: ShaderPipelineRegistry? = native.context.shaderPipeline
        return pipeline?.acceptsUniform(this, name) ?: true
    }

    /**
     * Activates this exact native program without consulting the selected world
     * pipeline. Pipeline implementations use this after they have selected the
     * program for the current semantic draw.
     */
    internal fun activate() {
        native.context.system.shader.shader = this
    }

    /**
     * Copies the complete host-side uniform snapshot to a newly selected scene
     * program. This is required even for values that did not change this frame.
     */
    internal fun syncUniformsTo(target: NativeShader, names: Set<String>? = null) {
        for ((name, uniform) in uniforms) {
            if (names != null && name !in names) continue
            if (!target.hasUniform(name)) continue
            uniform.uploadTo(target)
        }
    }

    internal fun beginUniformUpload(): Long {
        check(!uniformUploadInProgress) { "A shader uniform upload is already active" }
        uniformRevision++
        uniformUploadInProgress = true
        return uniformRevision
    }

    internal fun finishUniformUpload(target: NativeShader?, revision: Long) {
        check(uniformUploadInProgress && revision == uniformRevision) { "Mismatched shader uniform revision" }
        uniformUploadInProgress = false
        val pipeline: ShaderPipelineRegistry? = native.context.shaderPipeline
        if (target != null) pipeline?.recordUniformUpload(this, target, revision)
    }

    fun reload() {
        native.reload()
        activate()
        syncUniformsTo(native)
    }

    private fun <T : ShaderUniform> T.register(): T {
        val previous = uniforms.put(name, this)

        if (previous != null) {
            throw IllegalStateException("Duplicated uniform: $name")
        }

        return this
    }

    override fun <T : ShaderUniform> uniform(uniform: T) = uniform.register()
}
