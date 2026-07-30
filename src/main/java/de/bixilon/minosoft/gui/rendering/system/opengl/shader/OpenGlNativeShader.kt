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

package de.bixilon.minosoft.gui.rendering.system.opengl.shader

import de.bixilon.kmath.mat.mat4.f.Mat4f
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec3.i.Vec3i
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.kutil.exception.ExceptionUtil.catchAll
import de.bixilon.kutil.stream.InputStreamUtil.readAsString
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.exceptions.ShaderLinkingException
import de.bixilon.minosoft.gui.rendering.exceptions.ShaderLoadingException
import de.bixilon.minosoft.gui.rendering.system.base.buffer.uniform.UniformBuffer
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.base.shader.code.glsl.GLSLShaderCode
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.buffer.uniform.OpenGlUniformBuffer
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL40.GL_MAX_PATCH_VERTICES
import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil
import java.io.FileNotFoundException

class OpenGlNativeShader(
    val system: OpenGlRenderSystem,
    private val vertex: ResourceLocation?,
    private val tessellationControl: ResourceLocation? = null,
    private val tessellationEvaluation: ResourceLocation? = null,
    private val geometry: ResourceLocation?,
    private val fragment: ResourceLocation?,
    private val vertexSource: String? = null,
    private val tessellationControlSource: String? = null,
    private val tessellationEvaluationSource: String? = null,
    private val geometrySource: String? = null,
    private val fragmentSource: String? = null,
    private val compute: ResourceLocation? = null,
    private val computeSource: String? = null,
    internal val patchVertices: Int? = null,
) : NativeShader {
    override val context get() = system.context
    override var loaded: Boolean = false
        private set
    override val defines: MutableMap<String, Any> = mutableMapOf()
    private var handler = -1
    private val uniformLocations: Object2IntOpenHashMap<String> = Object2IntOpenHashMap()
    private val uniformBlockIndices: Object2IntOpenHashMap<String> = Object2IntOpenHashMap()
    private val absentUniforms = mutableSetOf<String>()

    private inline fun cleanup(failure: Throwable?, action: () -> Unit) {
        try {
            action()
        } catch (cleanupError: Throwable) {
            if (failure == null) throw cleanupError
            failure.addSuppressed(cleanupError)
        }
    }

    private fun compile(
        file: ResourceLocation,
        type: ShaderType,
        source: String?,
        programDefines: Map<String, Any>,
    ): Int {
        val code = GLSLShaderCode(context, source ?: context.session.assets[file].readAsString(), file)
        system.log { "Compiling shader $file" }

        code.defines += programDefines
        code.defines["SHADER_TYPE_${type.name}"] = ""
        for (hack in system.vendor.hacks) {
            code.defines[hack.name] = ""
        }

        val shader = gl { glCreateShader(type.native) }
        if (shader.toLong() == MemoryUtil.NULL) {
            throw ShaderLoadingException()
        }
        system.resources.created(OpenGlResourceType.SHADER, shader)

        try {
            val glsl = code.code
            gl { glShaderSource(shader, glsl) }

            gl { glCompileShader(shader) }

            if (gl { glGetShaderi(shader, GL_COMPILE_STATUS) } == GL_FALSE) {
                throw ShaderLoadingException("Can not load shader: $file:\n" + gl { glGetShaderInfoLog(shader) }, glsl)
            }

            return shader
        } catch (error: Throwable) {
            cleanup(error) { deleteShader(shader) }
            throw error
        }
    }

    private fun prepareProgram(): Int {
        require((compute == null) == (computeSource == null)) {
            "Compute shader ID and source must be provided together"
        }
        require((tessellationControl == null) == (tessellationEvaluation == null)) {
            "Tessellation control and evaluation stages must be provided together"
        }
        require((tessellationControl == null) == (patchVertices == null)) {
            "Tessellation stages require an explicit patch vertex count"
        }
        require(patchVertices == null || patchVertices > 0) {
            "Tessellation patch vertex count must be positive"
        }
        if (patchVertices != null) {
            require(GL.getCapabilities().OpenGL40) {
                "Tessellation shaders require OpenGL 4.0"
            }
            val maximumPatchVertices = gl { glGetInteger(GL_MAX_PATCH_VERTICES) }
            require(patchVertices <= maximumPatchVertices) {
                "Tessellation patch vertex count $patchVertices exceeds driver limit $maximumPatchVertices"
            }
        }
        require((compute != null) xor (vertex != null && fragment != null)) {
            "OpenGL program must contain either compute or paired vertex/fragment stages"
        }
        val geometryCode = geometrySource ?: geometry?.let { catchAll { context.session.assets[it].readAsString() } }
        val programDefines = defines.toMutableMap()
        if (tessellationControl != null) {
            programDefines["HAS_TESSELLATION_SHADER"] = " "
        }
        if (geometryCode != null) {
            programDefines["HAS_GEOMETRY_SHADER"] = " "
        }
        val candidate = gl { glCreateProgram() }

        if (candidate.toLong() == MemoryUtil.NULL) {
            throw ShaderLoadingException()
        }
        system.resources.created(OpenGlResourceType.PROGRAM, candidate)

        val programs = IntArrayList(if (compute != null) 1 else 5)
        var failure: Throwable? = null
        try {
            if (compute != null) {
                programs += compile(compute, ShaderType.COMPUTE, computeSource, programDefines)
            } else {
                programs += compile(requireNotNull(vertex), ShaderType.VERTEX, vertexSource, programDefines)
                tessellationControl?.let {
                    programs += compile(
                        it,
                        ShaderType.TESSELLATION_CONTROL,
                        tessellationControlSource,
                        programDefines,
                    )
                }
                tessellationEvaluation?.let {
                    programs += compile(
                        it,
                        ShaderType.TESSELLATION_EVALUATION,
                        tessellationEvaluationSource,
                        programDefines,
                    )
                }
                try {
                    geometry?.let { programs += compile(it, ShaderType.GEOMETRY, geometryCode, programDefines) }
                } catch (_: FileNotFoundException) {
                }
                programs += compile(requireNotNull(fragment), ShaderType.FRAGMENT, fragmentSource, programDefines)
            }

            for (index in 0 until programs.size) {
                val program = programs.getInt(index)
                gl { glAttachShader(candidate, program) }
            }

            gl { glLinkProgram(candidate) }
            if (gl { glGetProgrami(candidate, GL_LINK_STATUS) } == GL_FALSE) {
                throw ShaderLinkingException(
                    "Can not link shaders: ${compute ?: vertex} with $tessellationControl with " +
                        "$tessellationEvaluation with $geometry with $fragment: \n " +
                        glGetProgramInfoLog(candidate),
                )
            }
            gl { glValidateProgram(candidate) }
            return candidate
        } catch (error: Throwable) {
            failure = error
            cleanup(error) { deleteProgram(candidate) }
            throw error
        } finally {
            for (index in 0 until programs.size) {
                val program = programs.getInt(index)
                cleanup(failure) { deleteShader(program) }
            }
        }
    }

    override fun load() {
        check(!loaded) { "Already loaded!" }
        handler = prepareProgram()
        uniformLocations.clear()
        uniformBlockIndices.clear()
        absentUniforms.clear()
        loaded = true
    }

    override fun unload() {
        check(loaded) { "Not loaded!" }
        deleteProgram(this.handler)
        loaded = false
        this.handler = -1
        uniformLocations.clear()
        uniformBlockIndices.clear()
        absentUniforms.clear()
    }

    override fun reload() {
        check(loaded) { "Not loaded!" }
        val candidate = prepareProgram()
        val previous = handler
        val selected = system.shader.shader?.native === this
        handler = candidate
        uniformLocations.clear()
        uniformBlockIndices.clear()
        absentUniforms.clear()
        if (selected) unsafeUse()
        deleteProgram(previous)
    }

    private fun deleteShader(shader: Int) {
        gl { glDeleteShader(shader) }
        system.resources.deleted(OpenGlResourceType.SHADER, shader)
    }

    private fun deleteProgram(program: Int) {
        gl { glDeleteProgram(program) }
        system.resources.deleted(OpenGlResourceType.PROGRAM, program)
    }


    private fun getUniformLocation(uniform: String) = uniformLocations.getOrPut(uniform) {
        val location = gl { glGetUniformLocation(handler, uniform) }
        if (location < 0) {
            val error = "No uniform named $uniform in $this, maybe you use something that has been optimized out? Check your shader code!"
            if (!context.profile.advanced.allowUniformErrors) {
                throw IllegalArgumentException(error)
            }
            Log.log(LogMessageType.RENDERING, LogLevels.WARN, error)
        }
        return@getOrPut location
    }

    override fun hasUniform(uniform: String): Boolean {
        check(loaded) { "Not loaded!" }
        if (uniformLocations.containsKey(uniform)) return uniformLocations.getInt(uniform) >= 0
        if (uniformBlockIndices.containsKey(uniform)) return uniformBlockIndices.getInt(uniform) >= 0
        if (uniform in absentUniforms) return false

        val location = gl { glGetUniformLocation(handler, uniform) }
        if (location >= 0) {
            uniformLocations[uniform] = location
            return true
        }
        // Uniform blocks are program inputs too, but OpenGL deliberately does
        // not expose them through glGetUniformLocation. Scene-state
        // synchronization uses this predicate for every declared value, so a
        // block must be discovered through the block-index API or its binding
        // (notably uSkeletalBuffer) is silently skipped.
        val blockIndex = gl { glGetUniformBlockIndex(handler, uniform) }
        if (blockIndex >= 0) {
            uniformBlockIndices[uniform] = blockIndex
            return true
        }
        absentUniforms += uniform
        return false
    }

    override fun setFloat(uniform: String, value: Float) {
        gl { glUniform1f(getUniformLocation(uniform), value) }
    }

    override fun setInt(uniform: String, value: Int) {
        gl { glUniform1i(getUniformLocation(uniform), value) }
    }

    override fun setUInt(uniform: String, value: Int) {
        gl { glUniform1ui(getUniformLocation(uniform), value) }
    }

    override fun setBoolean(uniform: String, boolean: Boolean) {
        setInt(uniform, if (boolean) 1 else 0)
    }

    override fun setMat4f(uniform: String, mat4: Mat4f) {
        gl { glUniformMatrix4fv(getUniformLocation(uniform), false, mat4._0.array) }
    }

    override fun setVec2f(uniform: String, vec2: Vec2f) {
        gl { glUniform2f(getUniformLocation(uniform), vec2.x, vec2.y) }
    }

    override fun setVec2i(uniform: String, vec2: Vec2i) {
        gl { glUniform2i(getUniformLocation(uniform), vec2.x, vec2.y) }
    }

    override fun setVec3f(uniform: String, vec3: Vec3f) {
        gl { glUniform3f(getUniformLocation(uniform), vec3.x, vec3.y, vec3.z) }
    }

    override fun setVec3i(uniform: String, vec3: Vec3i) {
        gl { glUniform3i(getUniformLocation(uniform), vec3.x, vec3.y, vec3.z) }
    }

    override fun setVec4f(uniform: String, vec4: Vec4f) {
        gl { glUniform4f(getUniformLocation(uniform), vec4.x, vec4.y, vec4.z, vec4.w) }
    }

    override fun setVec4i(uniform: String, x: Int, y: Int, z: Int, w: Int) {
        gl { glUniform4i(getUniformLocation(uniform), x, y, z, w) }
    }

    override fun setRGBColor(uniform: String, color: RGBColor) {
        setRGBAColor(uniform, color.rgba())
    }

    override fun setRGBAColor(uniform: String, color: RGBAColor) {
        gl { glUniform4f(getUniformLocation(uniform), color.redf, color.greenf, color.bluef, color.alphaf) }
    }

    override fun setTexture(uniform: String, textureId: Int) {
        gl { glUniform1i(getUniformLocation(uniform), textureId) }
    }

    override fun setUniformBuffer(uniform: String, buffer: UniformBuffer) {
        if (buffer !is OpenGlUniformBuffer) throw IllegalArgumentException("Not an opengl buffer: $buffer")
        val location = uniformBlockIndices.getOrPut(uniform) {
            val index = gl { glGetUniformBlockIndex(handler, uniform) }
            if (index < 0) {
                throw IllegalArgumentException("No uniform buffer called $uniform")
            }
            return@getOrPut index
        }
        gl { glUniformBlockBinding(handler, location, buffer.bindingIndex) }
    }

    fun unsafeUse() {
        gl { glUseProgram(handler) }
        patchVertices?.let { vertices ->
            gl { glPatchParameteri(GL_PATCH_VERTICES, vertices) }
        }
    }

    override fun toString(): String {
        return if (compute != null) {
            "OpenGLComputeShader: $compute"
        } else {
            "OpenGLShader: $vertex:$tessellationControl:$tessellationEvaluation:$geometry:$fragment"
        }
    }

    private enum class ShaderType(
        val native: Int,
    ) {
        GEOMETRY(GL_GEOMETRY_SHADER),
        VERTEX(GL_VERTEX_SHADER),
        TESSELLATION_CONTROL(GL_TESS_CONTROL_SHADER),
        TESSELLATION_EVALUATION(GL_TESS_EVALUATION_SHADER),
        FRAGMENT(GL_FRAGMENT_SHADER),
        COMPUTE(GL_COMPUTE_SHADER),
    }
}
