/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureManager
import de.bixilon.minosoft.gui.rendering.system.opengl.texture.OpenGlTextureManager

/**
 * Maps sparse physical Minosoft texture-array units to a compact sampler array
 * in one linked Iris program. Meshes keep their global packed array index; the
 * transformed GLSL switch maps that index to the compact declaration.
 */
internal data class IrisTextureArrayLayout(
    val physicalSlots: List<Int>,
    val companionSlots: List<Int> = emptyList(),
) {
    init {
        require(physicalSlots.isNotEmpty()) { "Iris texture-array layout must not be empty" }
        require(physicalSlots == physicalSlots.distinct().sorted()) {
            "Iris texture-array slots must be sorted and unique: $physicalSlots"
        }
        require(physicalSlots.all { it in 0 until TextureManager.SHADER_TEXTURE_ARRAY_SIZE }) {
            "Iris texture-array slot is outside the host range: $physicalSlots"
        }
        require(companionSlots == companionSlots.distinct().sorted()) {
            "Iris companion texture-array slots must be sorted and unique: $companionSlots"
        }
        require(companionSlots.all { it in physicalSlots }) {
            "Iris companion texture-array slots must be a subset of host slots: $companionSlots"
        }
    }

    fun bind(native: NativeShader): Int {
        var uploads = 0
        physicalSlots.forEachIndexed { compact, physical ->
            val uniform = "uTextures[$compact]"
            if (!native.hasUniform(uniform)) return@forEachIndexed
            native.setTexture(uniform, physical)
            uploads++
        }
        return uploads
    }
}

internal data class IrisTextureArrayLayouts(
    val all: IrisTextureArrayLayout,
    val terrain: IrisTextureArrayLayout,
    val scene: IrisTextureArrayLayout,
    val font: IrisTextureArrayLayout,
) {
    fun scene(state: SceneStateAbi): IrisTextureArrayLayout = when (state) {
        SceneStateAbi.BILLBOARD_TEXT -> font
        else -> scene
    }

    companion object {
        fun capture(textures: TextureManager): IrisTextureArrayLayouts {
            val active = textures.shaderTextureSizes()
                .mapIndexedNotNull { index, size -> index.takeIf { size.x > 0 && size.y > 0 } }
                .ifEmpty { listOf(0) }
            val openGl = textures as? OpenGlTextureManager
            if (openGl == null) {
                val conservative = IrisTextureArrayLayout(active)
                return IrisTextureArrayLayouts(conservative, conservative, conservative, conservative)
            }

            val static = openGl.staticShaderTextureIndices().ifEmpty { active }
            val dynamic = openGl.dynamicShaderTextureIndex()
            val font = openGl.fontShaderTextureIndex()
            return IrisTextureArrayLayouts(
                all = IrisTextureArrayLayout(active, static.filter { it in active }),
                terrain = IrisTextureArrayLayout(static, static),
                scene = IrisTextureArrayLayout((static + dynamic).distinct().sorted(), static),
                font = IrisTextureArrayLayout(listOf(font)),
            )
        }
    }
}
