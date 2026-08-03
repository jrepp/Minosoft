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

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.Framebuffer
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.FramebufferState
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.depth.DepthAttachment
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.stencil.StencilAttachment
import de.bixilon.minosoft.gui.rendering.system.base.buffer.frame.attachment.texture.TextureAttachment
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlRenderSystem.Companion.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.OpenGlUtil.gl
import de.bixilon.minosoft.gui.rendering.system.opengl.irisPerBufferBlending
import de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceType
import org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendFuncSeparateiARB
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL
import org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24
import org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32
import org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL31.*
import org.lwjgl.opengl.GL40.glBlendFuncSeparatei
import org.lwjgl.opengl.GL42.GL_MAX_IMAGE_UNITS
import org.lwjgl.opengl.GL42.GL_READ_WRITE
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * Physical realization of the logical Iris buffer plan.
 *
 * One framebuffer name is retained per view while its attachments are rebound
 * for each program's ordered output list. Logical color buffers own a main and
 * alternate texture; depth buffers are single-sided. This keeps attachment
 * identity, sampler identity, and future composite flips in one generation.
 */
internal class IrisOpenGlRenderTargets(
    private val context: RenderContext,
    private val plan: ShaderBufferPlan,
) : AutoCloseable {
    private val system = context.system as? OpenGlRenderSystem
        ?: throw IllegalArgumentException("Iris render targets require the OpenGL backend")
    private val buffers = linkedMapOf<ShaderBufferId, Buffer>()
    private val main = ViewFramebuffer(RenderViewId.MAIN)
    private val shadow = ViewFramebuffer(IrisShaderPackPlanner.SHADOW_VIEW)
    private val hasShadow = plan.buffers.any {
        it.id.kind == ShaderBufferKind.SHADOWTEX || it.id.kind == ShaderBufferKind.SHADOWCOLOR
    }
    private var baseSize = Vec2i.EMPTY
    private var maxDrawBuffers = 0
    private var copyReadFramebuffer = -1
    private var copyDrawFramebuffer = -1
    private var initialized = false
    private var closed = false
    private var blendOverrideActive = false
    private var blendBinding: BlendBinding? = null
    private val blendOverrideApplications = linkedMapOf<String, Long>()
    private val samplerUniforms = IdentityHashMap<NativeShader, MutableMap<String, Int>>()
    private val samplerPlans = IdentityHashMap<NativeShader, SamplerBindingPlan>()
    private val imagePlans = IdentityHashMap<NativeShader, ImageBindingPlan>()
    private val comparisonModes = mutableMapOf<Int, Int>()

    val logicalBufferCount: Int get() = buffers.size
    val physicalTextureCount: Int get() = buffers.values.sumOf { if (it.alternate < 0) 1 else 2 }
    val appliedBlendOverrides: Map<String, Long> get() = blendOverrideApplications.toMap()

    fun prepare() {
        check(!closed) { "Iris render targets are closed" }
        maxDrawBuffers = minOf(
            gl { glGetInteger(GL_MAX_DRAW_BUFFERS) },
            gl { glGetInteger(GL_MAX_COLOR_ATTACHMENTS) },
        )
        require(maxDrawBuffers > 0) { "OpenGL reports no color attachments" }
        plan.buffers.filter {
            it.id.kind == ShaderBufferKind.COLORTEX || it.id.kind == ShaderBufferKind.SHADOWCOLOR
        }.groupBy { it.id.kind }.forEach { (kind, buffers) ->
            require(buffers.size <= kind.maxIndex + 1) { "Invalid logical buffer count for $kind" }
        }
        resize(hostSize())
    }

    fun beginFrame(state: IrisFrameState) {
        check(!closed) { "Iris render targets are closed" }
        val size = hostSize()
        if (!initialized || size != baseSize) {
            resize(size)
        }
        // Keep each logical buffer's current side across frames. Iris copies
        // the last flipped side back to its main texture after final; this
        // implementation instead alternates the physical roles directly.
        // Resetting here would make non-cleared history buffers (for example
        // Complementary's TAA colortex2) read the stale primary every frame.
        clearView(RenderViewId.MAIN)
        clearDistantDepth()
    }

    private fun hostSize(): Vec2i {
        val host = context.framebuffer.main
        return Vec2i(
            (host.size.x * host.scale).roundToInt().coerceAtLeast(1),
            (host.size.y * host.scale).roundToInt().coerceAtLeast(1),
        )
    }

    fun bindView(view: RenderViewId) {
        check(initialized && !closed) { "Iris render targets are unavailable" }
        require(view != IrisShaderPackPlanner.SHADOW_VIEW || hasShadow) {
            "Shader pack has no shadow buffers"
        }
        val outputs = when (view) {
            IrisShaderPackPlanner.SHADOW_VIEW -> defaultOutputs(ShaderBufferKind.SHADOWCOLOR)
            else -> defaultOutputs(ShaderBufferKind.COLORTEX)
        }
        bindOutputs(view, outputs)
        restoreHostBlend()
    }

    fun beginShadow() {
        require(hasShadow) { "Shader pack has no shadow buffers" }
        clearView(IrisShaderPackPlanner.SHADOW_VIEW, colors = false, clearDepth = true)
    }

    fun clearShadowColors() {
        require(hasShadow) { "Shader pack has no shadow buffers" }
        clearView(IrisShaderPackPlanner.SHADOW_VIEW, colors = true, clearDepth = false)
    }

    fun size(view: RenderViewId): Vec2i {
        check(initialized && !closed) { "Iris render targets are unavailable" }
        return if (view == IrisShaderPackPlanner.SHADOW_VIEW) shadow.size else main.size
    }

    fun snapshotDepth(snapshot: ShaderDepthSnapshot): Boolean {
        val (sourceId, targetId) = depthSnapshotBuffers(snapshot)
        val source = buffers[sourceId] ?: return false
        val target = buffers[targetId] ?: return false
        copyDepth(source, target)
        return true
    }

    fun bindProgram(view: RenderViewId, program: ShaderProgramSource) {
        val kind = if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
            ShaderBufferKind.SHADOWCOLOR
        } else {
            ShaderBufferKind.COLORTEX
        }
        val outputs = program.resourceUsage.colorWrites.takeIf(List<ShaderBufferId>::isNotEmpty) ?: defaultOutputs(kind)
        require(outputs.all { it.kind == kind }) {
            "Program outputs $outputs do not belong to view $view"
        }
        bindOutputs(
            view,
            outputs,
            program.phase in FULLSCREEN_BUFFER_PHASES,
            depthKind = if (program.phase == ShaderProgramPhase.DISTANT_HORIZONS) {
                ShaderBufferKind.DHDEPTHTEX
            } else {
                null
            },
        )
        applyBlend(program, outputs)
    }

    fun restoreHostBlend() {
        if (!blendOverrideActive) return
        val enabled = system[de.bixilon.minosoft.gui.rendering.system.base.RenderingCapabilities.BLENDING]
        val function = system.blendFunction
        gl {
            if (enabled) glEnable(GL_BLEND) else glDisable(GL_BLEND)
            glBlendFuncSeparate(
                function.sourceRGB.gl,
                function.destinationRGB.gl,
                function.sourceAlpha.gl,
                function.destinationAlpha.gl,
            )
        }
        blendOverrideActive = false
        blendBinding = null
    }

    private fun applyBlend(program: ShaderProgramSource, outputs: List<ShaderBufferId>) {
        val override = program.blendOverride
        if (override.isEmpty || outputs.isEmpty()) {
            restoreHostBlend()
            return
        }
        val hostEnabled =
            system[de.bixilon.minosoft.gui.rendering.system.base.RenderingCapabilities.BLENDING]
        val hostFunction = system.blendFunction
        if (blendOverrideActive && blendBinding?.matches(program, outputs, hostEnabled, hostFunction) == true) return
        val modes = override.resolve(outputs, hostEnabled, system.blendFunction)
        if (override.program != null) {
            recordBlendOverride("${program.name}/program")
        }
        outputs.forEach { output ->
            if (output in override.buffers) {
                recordBlendOverride("${program.name}/$output")
            }
        }
        if (override.requiresPerBufferBlending) {
            modes.forEachIndexed { index, mode ->
                gl {
                    if (mode.enabled) glEnablei(GL_BLEND, index) else glDisablei(GL_BLEND, index)
                    val capabilities = GL.getCapabilities()
                    if (capabilities.OpenGL40) {
                        glBlendFuncSeparatei(
                            index,
                            mode.function.sourceRGB.gl,
                            mode.function.destinationRGB.gl,
                            mode.function.sourceAlpha.gl,
                            mode.function.destinationAlpha.gl,
                        )
                    } else {
                        require(capabilities.irisPerBufferBlending)
                        glBlendFuncSeparateiARB(
                            index,
                            mode.function.sourceRGB.gl,
                            mode.function.destinationRGB.gl,
                            mode.function.sourceAlpha.gl,
                            mode.function.destinationAlpha.gl,
                        )
                    }
                }
            }
        } else {
            val mode = modes.singleOrNull() ?: modes.first()
            gl {
                if (mode.enabled) glEnable(GL_BLEND) else glDisable(GL_BLEND)
                glBlendFuncSeparate(
                    mode.function.sourceRGB.gl,
                    mode.function.destinationRGB.gl,
                    mode.function.sourceAlpha.gl,
                    mode.function.destinationAlpha.gl,
                )
            }
        }
        blendOverrideActive = true
        blendBinding = BlendBinding(program, outputs, hostEnabled, hostFunction)
    }

    private fun recordBlendOverride(key: String) {
        blendOverrideApplications[key] = (blendOverrideApplications[key] ?: 0L) + 1L
    }

    fun bindSamplers(
        program: ShaderProgramSource,
        native: NativeShader,
        customTextures: IrisOpenGlCustomTextures,
        customResources: IrisOpenGlCustomResources,
        hostTextureUnits: Set<Int>? = null,
    ) {
        program.resourceUsage.mipmapsBefore.forEach { id ->
            val buffer = requireNotNull(buffers[id]) {
                "${program.name} generates mipmaps for undeclared Iris buffer $id"
            }
            system.bindTexture(system.framebufferTextureIndex, GL_TEXTURE_2D, buffer.readTexture())
            gl { glGenerateMipmap(GL_TEXTURE_2D) }
        }
        bindImages(program, native, customResources)
        val binding = samplerPlans[native] ?: buildSamplerPlan(program, native, hostTextureUnits).also {
            samplerPlans[native] = it
        }
        check(binding.program === program) { "One linked Iris shader was reused for multiple program plans" }
        check(binding.hostTextureUnits == hostTextureUnits) {
            "${program.name} changed its host texture-unit reservation"
        }
        for (entry in binding.buffers) {
            val buffer = requireNotNull(buffers[entry.id]) { "${program.name} samples undeclared Iris buffer ${entry.id}" }
            val texture = buffer.readTexture()
            system.bindTexture(entry.unit, GL_TEXTURE_2D, texture)
            if (comparisonModes[texture] != entry.comparisonMode) {
                system.textureParameter { glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, entry.comparisonMode) }
                comparisonModes[texture] = entry.comparisonMode
            }
        }
        binding.customTextures.forEach { customTextures.bind(it.id, it.unit) }
        binding.customImages.forEach { customResources.bindSampler(it.sampler, it.unit) }
        binding.uniforms.forEach { (name, unit) -> bindSamplerUniform(native, name, unit) }
    }

    private fun buildSamplerPlan(
        program: ShaderProgramSource,
        native: NativeShader,
        hostTextureUnits: Set<Int>?,
    ): SamplerBindingPlan {
        // GLSL drivers may optimize a source-referenced sampler out after
        // constant folding. Iris allocates and binds against the linked
        // program, so inactive declarations consume neither a texture unit nor
        // a uniform upload.
        val activeBuffers = program.resourceUsage.sampledBuffers.filterKeys(native::hasUniform)
        val activeCustom = program.resourceUsage.sampledCustomTextures.filterKeys(native::hasUniform)
        val activeImageSamplers = program.resourceUsage.sampledCustomImages.filterKeys(native::hasUniform)
        val distinctBuffers = activeBuffers.values.distinct()
        val distinctCustom = activeCustom.values.distinct()
        val distinctImageSamplers = activeImageSamplers.values.distinct()
        val maxUnits = gl { glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS) }
        val required = distinctBuffers.size + distinctCustom.size + distinctImageSamplers.size
        // Fullscreen programs have no active host scene samplers and may use
        // every unit. Scene programs reserve only the physical texture-array
        // units retained by their compact linked layout; 2D Iris samplers can
        // safely occupy the remaining sparse units.
        val reserved = hostTextureUnits ?: if (
            program.phase in FULLSCREEN_BUFFER_PHASES || program.phase == ShaderProgramPhase.FINAL
        ) {
            emptySet()
        } else {
            (0 until system.nextTextureIndex).toSet()
        }
        val samplerUnits = allocateSamplerUnits(maxUnits, required, reserved)
        require(samplerUnits.size == required) {
            "${program.name} requires $required Iris samplers with host units ${reserved.sorted()} reserved, " +
                "but the fragment stage exposes $maxUnits"
        }
        val bufferUnits = distinctBuffers.withIndex().associate { (offset, id) ->
            requireNotNull(buffers[id]) { "${program.name} samples undeclared Iris buffer $id" }
            val comparisonModes = activeBuffers.asSequence()
                .filter { it.value == id }
                .map { it.key in program.resourceUsage.shadowComparisonSamplers }
                .toSet()
            require(comparisonModes.size == 1) {
                "${program.name} samples $id as both raw depth and shadow comparison"
            }
            val unit = samplerUnits[offset]
            val comparisonMode = if (comparisonModes.single()) GL_COMPARE_REF_TO_TEXTURE else GL_NONE
            id to BufferSamplerBinding(id, unit, comparisonMode)
        }
        val customUnits = distinctCustom.withIndex().associate { (offset, id) ->
            val unit = samplerUnits[distinctBuffers.size + offset]
            id to unit
        }
        val imageUnits = distinctImageSamplers.withIndex().associate { (offset, name) ->
            val unit = samplerUnits[distinctBuffers.size + distinctCustom.size + offset]
            name to unit
        }
        val uniforms = buildMap {
            activeBuffers.forEach { (sampler, id) -> put(sampler, bufferUnits.getValue(id).unit) }
            activeCustom.forEach { (sampler, id) -> put(sampler, customUnits.getValue(id)) }
            activeImageSamplers.forEach { (sampler, name) -> put(sampler, imageUnits.getValue(name)) }
        }
        return SamplerBindingPlan(
            program = program,
            hostTextureUnits = hostTextureUnits?.toSet(),
            buffers = bufferUnits.values.toList(),
            customTextures = customUnits.map { (id, unit) -> CustomTextureSamplerBinding(id, unit) },
            customImages = activeImageSamplers.map { (sampler, name) ->
                CustomImageSamplerBinding(sampler, imageUnits.getValue(name))
            }.distinct(),
            uniforms = uniforms,
        )
    }

    private fun bindSamplerUniform(native: NativeShader, name: String, unit: Int) {
        val bindings = samplerUniforms.getOrPut(native) { mutableMapOf() }
        if (bindings[name] == unit) return
        native.setTexture(name, unit)
        bindings[name] = unit
    }

    private fun bindImages(
        program: ShaderProgramSource,
        native: NativeShader,
        customResources: IrisOpenGlCustomResources,
    ) {
        if (
            program.resourceUsage.renderTargetImages.isEmpty() &&
            program.resourceUsage.customImages.isEmpty()
        ) return
        val binding = imagePlans[native] ?: buildImagePlan(program, native).also { imagePlans[native] = it }
        check(binding.program === program) { "One linked Iris shader was reused for multiple image plans" }
        binding.renderTargets.forEach { entry ->
            val buffer = requireNotNull(buffers[entry.id]) {
                "${program.name} binds undeclared Iris render-target image ${entry.name}=${entry.id}"
            }
            system.bindImageTexture(
                entry.unit,
                buffer.readTexture(),
                0,
                false,
                0,
                GL_READ_WRITE,
                buffer.descriptor.format.gl.internal,
            )
            bindSamplerUniform(native, entry.name, entry.unit)
        }
        customResources.bindImages(native, binding.customImages, binding.renderTargets.size)
    }

    private fun buildImagePlan(program: ShaderProgramSource, native: NativeShader): ImageBindingPlan {
        val activeTargets = program.resourceUsage.renderTargetImages.entries
            .filter { native.hasUniform(it.key) }
        val activeCustom = program.resourceUsage.customImages.filter(native::hasUniform)
        val maximum = gl { glGetInteger(GL_MAX_IMAGE_UNITS) }
        require(activeTargets.size + activeCustom.size <= maximum) {
            "${program.name} requires ${activeTargets.size + activeCustom.size} Iris images " +
                "but the driver exposes $maximum image units"
        }
        return ImageBindingPlan(
            program = program,
            renderTargets = activeTargets.mapIndexed { unit, entry ->
                RenderTargetImageBinding(entry.key, entry.value, unit)
            },
            customImages = activeCustom,
        )
    }

    fun finish(program: ShaderProgramSource) {
        program.resourceUsage.flipsAfter.forEach { id ->
            requireNotNull(buffers[id]).flipState.flip()
        }
    }

    private fun defaultOutputs(kind: ShaderBufferKind): List<ShaderBufferId> {
        val zero = ShaderBufferId(kind, 0)
        return if (zero in buffers) listOf(zero) else emptyList()
    }

    private fun bindOutputs(
        view: RenderViewId,
        outputs: List<ShaderBufferId>,
        alternateWrites: Boolean = false,
        depthKind: ShaderBufferKind? = null,
    ) {
        val framebuffer = if (view == IrisShaderPackPlanner.SHADOW_VIEW) shadow else main
        system.framebuffer = framebuffer
        framebuffer.configure(
            outputs,
            alternateWrites = alternateWrites,
            depthKind = depthKind,
        )
    }

    companion object {
        internal fun depthSnapshotBuffers(
            snapshot: ShaderDepthSnapshot,
        ): Pair<ShaderBufferId, ShaderBufferId> = when (snapshot) {
            ShaderDepthSnapshot.DISTANT_BEFORE_TRANSLUCENT ->
                ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 0) to
                    ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 1)
            ShaderDepthSnapshot.BEFORE_TRANSLUCENT ->
                ShaderBufferId(ShaderBufferKind.DEPTHTEX, 0) to
                    ShaderBufferId(ShaderBufferKind.DEPTHTEX, 1)
            ShaderDepthSnapshot.BEFORE_HAND ->
                ShaderBufferId(ShaderBufferKind.DEPTHTEX, 0) to
                    ShaderBufferId(ShaderBufferKind.DEPTHTEX, 2)
            ShaderDepthSnapshot.SHADOW_BEFORE_TRANSLUCENT ->
                ShaderBufferId(ShaderBufferKind.SHADOWTEX, 0) to
                    ShaderBufferId(ShaderBufferKind.SHADOWTEX, 1)
        }

        internal fun firstIrisSamplerUnit(phase: ShaderProgramPhase, hostUnits: Int): Int {
            require(hostUnits >= 0) { "Host texture-unit boundary must be non-negative" }
            return if (phase in FULLSCREEN_BUFFER_PHASES || phase == ShaderProgramPhase.FINAL) 0 else hostUnits
        }

        internal fun allocateSamplerUnits(
            maximum: Int,
            required: Int,
            reserved: Set<Int>,
        ): List<Int> {
            require(maximum >= 0) { "Maximum texture-unit count must be non-negative" }
            require(required >= 0) { "Required texture-unit count must be non-negative" }
            require(reserved.all { it in 0 until maximum }) {
                "Reserved texture unit is outside 0 until $maximum: ${reserved.sorted()}"
            }
            return (0 until maximum).filterNot(reserved::contains).take(required)
        }

        internal fun shouldClearTexture(policy: RenderClearPolicy, initializing: Boolean): Boolean =
            initializing || policy == RenderClearPolicy.CLEAR

        private val FULLSCREEN_BUFFER_PHASES = setOf(
            ShaderProgramPhase.BEGIN,
            ShaderProgramPhase.SHADOW_COMPOSITE,
            ShaderProgramPhase.PREPARE,
            ShaderProgramPhase.DEFERRED,
            ShaderProgramPhase.COMPOSITE,
        )

    }

    private fun clearView(
        view: RenderViewId,
        colors: Boolean = true,
        clearDepth: Boolean = true,
        initializing: Boolean = false,
    ) {
        val colorKind = if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
            ShaderBufferKind.SHADOWCOLOR
        } else {
            ShaderBufferKind.COLORTEX
        }
        val framebuffer = if (view == IrisShaderPackPlanner.SHADOW_VIEW) shadow else main
        system.framebuffer = framebuffer
        if (colors) {
            for (buffer in buffers.values.filter { it.descriptor.id.kind == colorKind }) {
                if (!shouldClearTexture(buffer.descriptor.clear, initializing)) continue
                for (texture in buffer.textures()) {
                    framebuffer.configure(listOf(buffer.descriptor.id), mapOf(buffer.descriptor.id to texture))
                    val clear = when (val source = buffer.descriptor.clearColor) {
                        ShaderBufferClearColor.Fog -> context.system.clearColor.let {
                            floatArrayOf(it.redf, it.greenf, it.bluef, it.alphaf)
                        }

                        is ShaderBufferClearColor.Fixed -> source.components.toFloatArray()
                    }
                    when (buffer.descriptor.format.gl.integerKind) {
                        IntegerKind.NONE -> gl { glClearBufferfv(GL_COLOR, 0, clear) }
                        IntegerKind.SIGNED -> gl {
                            glClearBufferiv(GL_COLOR, 0, clear.map(Float::toInt).toIntArray())
                        }
                        IntegerKind.UNSIGNED -> gl {
                            glClearBufferuiv(
                                GL_COLOR,
                                0,
                                clear.map {
                                    it.toLong().coerceAtLeast(0L).coerceAtMost(UInt.MAX_VALUE.toLong()).toInt()
                                }.toIntArray(),
                            )
                        }
                    }
                }
            }
        }
        val depthKind = if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
            ShaderBufferKind.SHADOWTEX
        } else {
            ShaderBufferKind.DEPTHTEX
        }
        val depth = buffers[ShaderBufferId(depthKind, 0)]
        if (clearDepth && depth != null && shouldClearTexture(depth.descriptor.clear, initializing)) {
            framebuffer.configure(defaultOutputs(colorKind))
            gl { glClearDepth(1.0) }
            gl { glClear(GL_DEPTH_BUFFER_BIT) }
        }
    }

    private fun clearDistantDepth(initializing: Boolean = false) {
        val depth = buffers[ShaderBufferId(ShaderBufferKind.DHDEPTHTEX, 0)] ?: return
        if (!shouldClearTexture(depth.descriptor.clear, initializing)) return
        system.framebuffer = main
        main.configure(
            defaultOutputs(ShaderBufferKind.COLORTEX),
            depthKind = ShaderBufferKind.DHDEPTHTEX,
        )
        gl { glClearDepth(1.0) }
        gl { glClear(GL_DEPTH_BUFFER_BIT) }
        main.configure(defaultOutputs(ShaderBufferKind.COLORTEX))
    }

    private fun resize(size: Vec2i) {
        release()
        baseSize = size
        try {
            plan.buffers.forEach { descriptor ->
                buffers[descriptor.id] = createBuffer(descriptor)
            }
            copyReadFramebuffer = createFramebufferName()
            copyDrawFramebuffer = createFramebufferName()
            main.init()
            if (hasShadow) shadow.init()
            initialized = true
            // LOAD is a per-frame persistence policy, not permission to sample
            // undefined driver memory on the generation's first frame.
            clearView(RenderViewId.MAIN, initializing = true)
            clearDistantDepth(initializing = true)
            if (hasShadow) clearView(IrisShaderPackPlanner.SHADOW_VIEW, initializing = true)
        } catch (failure: Throwable) {
            try {
                release()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun createBuffer(descriptor: ShaderBufferDescriptor): Buffer {
        val size = descriptor.size.resolve(baseSize)
        val primary = createTexture(descriptor, size)
        return try {
            val alternate = if (descriptor.doubleBuffered) createTexture(descriptor, size) else -1
            Buffer(descriptor, size, primary, alternate)
        } catch (failure: Throwable) {
            deleteTexture(primary, failure)
            throw failure
        }
    }

    private fun createTexture(descriptor: ShaderBufferDescriptor, size: Vec2i): Int {
        val texture = gl { glGenTextures() }
        system.resources.created(OpenGlResourceType.TEXTURE, texture)
        try {
            system.bindTexture(system.framebufferTextureIndex, GL_TEXTURE_2D, texture)
            val format = descriptor.format.gl
            val levels = if (descriptor.mipmapped) {
                32 - Integer.numberOfLeadingZeros(maxOf(size.x, size.y))
            } else {
                1
            }
            for (level in 0 until levels) {
                gl {
                    glTexImage2D(
                        GL_TEXTURE_2D,
                        level,
                        format.internal,
                        (size.x shr level).coerceAtLeast(1),
                        (size.y shr level).coerceAtLeast(1),
                        0,
                        format.external,
                        format.type,
                        null as ByteBuffer?,
                    )
                }
            }
            val linear = descriptor.filter != ShaderBufferFilter.NEAREST
            system.textureParameter {
                glTexParameteri(
                    GL_TEXTURE_2D,
                    GL_TEXTURE_MIN_FILTER,
                    when {
                        descriptor.mipmapped && linear -> GL_LINEAR_MIPMAP_LINEAR
                        descriptor.mipmapped -> GL_NEAREST_MIPMAP_NEAREST
                        linear -> GL_LINEAR
                        else -> GL_NEAREST
                    },
                )
            }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, if (linear) GL_LINEAR else GL_NEAREST) }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE) }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE) }
            system.textureParameter { glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1) }
            if (descriptor.filter == ShaderBufferFilter.SHADOW_COMPARE) {
                system.textureParameter { glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE) }
            }
            return texture
        } catch (failure: Throwable) {
            deleteTexture(texture, failure)
            throw failure
        }
    }

    private fun copyDepth(source: Buffer, target: Buffer) {
        require(source.size == target.size) {
            "Depth snapshot sizes differ: ${source.descriptor.id}=${source.size}, ${target.descriptor.id}=${target.size}"
        }
        val previousFramebuffer = system.framebuffer
        try {
            system.bindFramebuffer(GL_READ_FRAMEBUFFER, copyReadFramebuffer)
            system.work.framebufferAttachmentChange()
            gl {
                glFramebufferTexture2D(
                    GL_READ_FRAMEBUFFER,
                    GL_DEPTH_ATTACHMENT,
                    GL_TEXTURE_2D,
                    source.readTexture(),
                    0,
                )
            }
            system.work.readBufferChange()
            gl { glReadBuffer(GL_NONE) }
            system.bindFramebuffer(GL_DRAW_FRAMEBUFFER, copyDrawFramebuffer)
            system.work.framebufferAttachmentChange()
            gl {
                glFramebufferTexture2D(
                    GL_DRAW_FRAMEBUFFER,
                    GL_DEPTH_ATTACHMENT,
                    GL_TEXTURE_2D,
                    target.primary,
                    0,
                )
            }
            system.work.drawBufferChange()
            gl { glDrawBuffer(GL_NONE) }
            system.work.framebufferCompletenessCheck()
            require(gl { glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) } == GL_FRAMEBUFFER_COMPLETE) {
                "Iris depth snapshot read framebuffer is incomplete"
            }
            system.work.framebufferCompletenessCheck()
            require(gl { glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) } == GL_FRAMEBUFFER_COMPLETE) {
                "Iris depth snapshot draw framebuffer is incomplete"
            }
            gl {
                glBlitFramebuffer(
                    0,
                    0,
                    source.size.x,
                    source.size.y,
                    0,
                    0,
                    target.size.x,
                    target.size.y,
                    GL_DEPTH_BUFFER_BIT,
                    GL_NEAREST,
                )
            }
        } finally {
            // READ/DRAW binds bypass OpenGlRenderSystem's cached logical
            // framebuffer. Restore the actual binding even when the blit or a
            // completeness check fails.
            if (previousFramebuffer == null) {
                system.bindFramebuffer(GL_FRAMEBUFFER, 0)
            } else {
                previousFramebuffer.bind()
            }
        }
    }

    private fun createFramebufferName(): Int {
        val framebuffer = gl { glGenFramebuffers() }
        system.resources.created(OpenGlResourceType.FRAMEBUFFER, framebuffer)
        return framebuffer
    }

    private fun release() {
        var failure: Throwable? = null
        for (framebuffer in listOf(main, shadow)) {
            try {
                framebuffer.release()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        for (framebuffer in intArrayOf(copyReadFramebuffer, copyDrawFramebuffer)) {
            if (framebuffer < 0) continue
            try {
                gl { glDeleteFramebuffers(framebuffer) }
                system.resources.deleted(OpenGlResourceType.FRAMEBUFFER, framebuffer)
                system.invalidateFramebuffer(framebuffer)
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        copyReadFramebuffer = -1
        copyDrawFramebuffer = -1
        for (buffer in buffers.values) {
            for (texture in buffer.textures()) {
                try {
                    deleteTexture(texture)
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
        }
        buffers.clear()
        initialized = false
        failure?.let { throw it }
    }

    private fun deleteTexture(texture: Int, original: Throwable? = null) {
        try {
            gl { glDeleteTextures(texture) }
            system.resources.deleted(OpenGlResourceType.TEXTURE, texture)
            system.invalidateTexture(texture)
            comparisonModes.remove(texture)
        } catch (cleanup: Throwable) {
            if (original == null) throw cleanup
            original.addSuppressed(cleanup)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            restoreHostBlend()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            release()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }

    private inner class ViewFramebuffer(
        private val view: RenderViewId,
    ) : Framebuffer {
        private var id = -1
        private val bindingState = IrisFramebufferBindingState()
        override var state: FramebufferState = FramebufferState.PREPARING
            private set
        override val depth: DepthAttachment? = null
        override val stencil: StencilAttachment? = null
        override val texture: TextureAttachment? = null
        override val size: Vec2i
            get() = if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
                buffers.values.firstOrNull {
                    it.descriptor.id.kind == ShaderBufferKind.SHADOWTEX ||
                        it.descriptor.id.kind == ShaderBufferKind.SHADOWCOLOR
                }?.size ?: Vec2i(1, 1)
            } else {
                baseSize
            }
        override val scale: Float = 1.0f

        override fun init() {
            check(state != FramebufferState.COMPLETE)
            id = gl { glGenFramebuffers() }
            system.resources.created(OpenGlResourceType.FRAMEBUFFER, id)
            state = FramebufferState.COMPLETE
            configure(
                if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
                    defaultOutputs(ShaderBufferKind.SHADOWCOLOR)
                } else {
                    defaultOutputs(ShaderBufferKind.COLORTEX)
                },
            )
        }

        override fun bind() {
            check(state == FramebufferState.COMPLETE)
            system.bindFramebuffer(GL_FRAMEBUFFER, id)
            system.viewport = size
        }

        fun configure(
            outputs: List<ShaderBufferId>,
            overrides: Map<ShaderBufferId, Int> = emptyMap(),
            alternateWrites: Boolean = false,
            depthKind: ShaderBufferKind? = null,
        ) {
            require(outputs.size <= maxDrawBuffers) {
                "Iris pass requires ${outputs.size} draw buffers but OpenGL exposes $maxDrawBuffers"
            }
            bind()
            val selectedDepthKind = depthKind ?: if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
                ShaderBufferKind.SHADOWTEX
            } else {
                ShaderBufferKind.DEPTHTEX
            }
            val depth = buffers[ShaderBufferId(selectedDepthKind, 0)]
            val desiredDepth = depth?.primary ?: 0
            fun desiredColor(index: Int): Int {
                val output = outputs[index]
                val buffer = requireNotNull(buffers[output]) { "Missing Iris output buffer $output" }
                return overrides[output] ?: buffer.writeTexture(alternateWrites)
            }
            var colorsMatch = bindingState.colorCount == outputs.size
            if (colorsMatch) {
                for (index in outputs.indices) {
                    if (bindingState.color(index) == desiredColor(index)) continue
                    colorsMatch = false
                    break
                }
            }
            val desiredReadBuffer = if (outputs.isEmpty()) GL_NONE else GL_COLOR_ATTACHMENT0
            val drawBuffersMatch = bindingState.matchesSequentialDrawBuffers(outputs.size, GL_COLOR_ATTACHMENT0)
            if (
                colorsMatch && bindingState.matchesDepth(desiredDepth) && drawBuffersMatch &&
                bindingState.matchesReadBuffer(desiredReadBuffer)
            ) {
                system.viewport = outputs.firstOrNull()?.let { requireNotNull(buffers[it]).size } ?: size
                return
            }
            val desiredColors = IntArray(outputs.size, ::desiredColor)
            var changed = false
            val attachmentCount = maxOf(bindingState.colorCount, desiredColors.size)
            for (index in 0 until attachmentCount) {
                val current = bindingState.color(index)
                val desired = desiredColors.getOrElse(index) { 0 }
                if (current == desired) continue
                system.work.framebufferAttachmentChange()
                gl { glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + index, GL_TEXTURE_2D, desired, 0) }
                changed = true
            }
            if (!bindingState.matchesDepth(desiredDepth)) {
                system.work.framebufferAttachmentChange()
                gl { glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, desiredDepth, 0) }
                changed = true
            }
            val desiredDrawBuffers = IntArray(outputs.size) { GL_COLOR_ATTACHMENT0 + it }
            if (!bindingState.matchesDrawBuffers(desiredDrawBuffers)) {
                if (outputs.isEmpty()) {
                    system.work.drawBufferChange()
                    gl { glDrawBuffer(GL_NONE) }
                } else {
                    system.work.drawBufferChange()
                    gl { glDrawBuffers(desiredDrawBuffers) }
                }
                changed = true
            }
            if (!bindingState.matchesReadBuffer(desiredReadBuffer)) {
                system.work.readBufferChange()
                gl { glReadBuffer(desiredReadBuffer) }
                changed = true
            }
            if (changed) {
                system.work.framebufferCompletenessCheck()
                val status = gl { glCheckFramebufferStatus(GL_FRAMEBUFFER) }
                check(status == GL_FRAMEBUFFER_COMPLETE) {
                    "Iris $view framebuffer is incomplete for $outputs: $status"
                }
            }
            bindingState.record(desiredColors, desiredDepth, desiredDrawBuffers, desiredReadBuffer)
            system.viewport = outputs.firstOrNull()?.let { requireNotNull(buffers[it]).size } ?: size
        }

        override fun bindTexture() {
            val kind = if (view == IrisShaderPackPlanner.SHADOW_VIEW) {
                ShaderBufferKind.SHADOWCOLOR
            } else {
                ShaderBufferKind.COLORTEX
            }
            val buffer = buffers[ShaderBufferId(kind, 0)] ?: return
            system.bindTexture(system.framebufferTextureIndex, GL_TEXTURE_2D, buffer.readTexture())
        }

        override fun delete() = release()

        fun release() {
            if (id >= 0) {
                gl { glDeleteFramebuffers(id) }
                system.resources.deleted(OpenGlResourceType.FRAMEBUFFER, id)
                system.invalidateFramebuffer(id)
                id = -1
            }
            bindingState.clear()
            state = FramebufferState.PREPARING
        }
    }

    private data class Buffer(
        val descriptor: ShaderBufferDescriptor,
        val size: Vec2i,
        val primary: Int,
        val alternate: Int,
        val flipState: IrisBufferFlipState = IrisBufferFlipState(),
    ) {
        fun readTexture(): Int = flipState.read(primary, alternate)
        fun writeTexture(alternateWrite: Boolean): Int =
            flipState.write(primary, alternate, alternateWrite)
        fun textures(): IntArray = if (alternate >= 0) intArrayOf(primary, alternate) else intArrayOf(primary)
    }

    private data class GlFormat(
        val internal: Int,
        val external: Int,
        val type: Int,
    )

    private class BlendBinding(
        val program: ShaderProgramSource,
        val outputs: List<ShaderBufferId>,
        val hostEnabled: Boolean,
        val hostFunction: de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState,
    ) {
        fun matches(
            program: ShaderProgramSource,
            outputs: List<ShaderBufferId>,
            hostEnabled: Boolean,
            hostFunction: de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState,
        ): Boolean = this.program === program && this.outputs == outputs &&
            this.hostEnabled == hostEnabled && this.hostFunction == hostFunction
    }

    private data class SamplerBindingPlan(
        val program: ShaderProgramSource,
        val hostTextureUnits: Set<Int>?,
        val buffers: List<BufferSamplerBinding>,
        val customTextures: List<CustomTextureSamplerBinding>,
        val customImages: List<CustomImageSamplerBinding>,
        val uniforms: Map<String, Int>,
    )

    private data class BufferSamplerBinding(val id: ShaderBufferId, val unit: Int, val comparisonMode: Int)
    private data class CustomTextureSamplerBinding(val id: IrisTextureId, val unit: Int)
    private data class CustomImageSamplerBinding(val sampler: String, val unit: Int)
    private data class ImageBindingPlan(
        val program: ShaderProgramSource,
        val renderTargets: List<RenderTargetImageBinding>,
        val customImages: List<String>,
    )
    private data class RenderTargetImageBinding(val name: String, val id: ShaderBufferId, val unit: Int)

    private enum class IntegerKind {
        NONE,
        SIGNED,
        UNSIGNED,
    }

    private val GlFormat.integerKind: IntegerKind
        get() {
            if (external != GL_RED_INTEGER && external != GL_RG_INTEGER &&
                external != GL_RGB_INTEGER && external != GL_RGBA_INTEGER
            ) {
                return IntegerKind.NONE
            }
            return when (type) {
                GL_BYTE, GL_SHORT, GL_INT -> IntegerKind.SIGNED
                else -> IntegerKind.UNSIGNED
            }
        }

    private val ShaderBufferFormat.gl: GlFormat
        get() = when (this) {
            is ShaderBufferFormat.Depth -> when (value) {
                RenderDepthFormat.DEPTH24 -> GlFormat(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT)
                RenderDepthFormat.DEPTH32F -> GlFormat(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT)
                RenderDepthFormat.DEPTH24_STENCIL8 ->
                    GlFormat(GL_DEPTH24_STENCIL8, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8)
            }

            is ShaderBufferFormat.Color -> value.gl
        }

    private val RenderColorFormat.gl: GlFormat
        get() = when (this) {
            RenderColorFormat.R8 -> GlFormat(GL_R8, GL_RED, GL_UNSIGNED_BYTE)
            RenderColorFormat.RG8 -> GlFormat(GL_RG8, GL_RG, GL_UNSIGNED_BYTE)
            RenderColorFormat.RGB8 -> GlFormat(GL_RGB8, GL_RGB, GL_UNSIGNED_BYTE)
            RenderColorFormat.RGBA8 -> GlFormat(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE)
            RenderColorFormat.R8_SNORM -> GlFormat(GL_R8_SNORM, GL_RED, GL_BYTE)
            RenderColorFormat.RG8_SNORM -> GlFormat(GL_RG8_SNORM, GL_RG, GL_BYTE)
            RenderColorFormat.RGB8_SNORM -> GlFormat(GL_RGB8_SNORM, GL_RGB, GL_BYTE)
            RenderColorFormat.RGBA8_SNORM -> GlFormat(GL_RGBA8_SNORM, GL_RGBA, GL_BYTE)
            RenderColorFormat.R16 -> GlFormat(GL_R16, GL_RED, GL_UNSIGNED_SHORT)
            RenderColorFormat.RG16 -> GlFormat(GL_RG16, GL_RG, GL_UNSIGNED_SHORT)
            RenderColorFormat.RGB16 -> GlFormat(GL_RGB16, GL_RGB, GL_UNSIGNED_SHORT)
            RenderColorFormat.RGBA16 -> GlFormat(GL_RGBA16, GL_RGBA, GL_UNSIGNED_SHORT)
            RenderColorFormat.R16_SNORM -> GlFormat(GL_R16_SNORM, GL_RED, GL_SHORT)
            RenderColorFormat.RG16_SNORM -> GlFormat(GL_RG16_SNORM, GL_RG, GL_SHORT)
            RenderColorFormat.RGB16_SNORM -> GlFormat(GL_RGB16_SNORM, GL_RGB, GL_SHORT)
            RenderColorFormat.RGBA16_SNORM -> GlFormat(GL_RGBA16_SNORM, GL_RGBA, GL_SHORT)
            RenderColorFormat.R16F -> GlFormat(GL_R16F, GL_RED, GL_HALF_FLOAT)
            RenderColorFormat.RG16F -> GlFormat(GL_RG16F, GL_RG, GL_HALF_FLOAT)
            RenderColorFormat.RGB16F -> GlFormat(GL_RGB16F, GL_RGB, GL_HALF_FLOAT)
            RenderColorFormat.RGBA16F -> GlFormat(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT)
            RenderColorFormat.R32F -> GlFormat(GL_R32F, GL_RED, GL_FLOAT)
            RenderColorFormat.RG32F -> GlFormat(GL_RG32F, GL_RG, GL_FLOAT)
            RenderColorFormat.RGB32F -> GlFormat(GL_RGB32F, GL_RGB, GL_FLOAT)
            RenderColorFormat.RGBA32F -> GlFormat(GL_RGBA32F, GL_RGBA, GL_FLOAT)
            RenderColorFormat.R8I -> GlFormat(GL_R8I, GL_RED_INTEGER, GL_BYTE)
            RenderColorFormat.RG8I -> GlFormat(GL_RG8I, GL_RG_INTEGER, GL_BYTE)
            RenderColorFormat.RGB8I -> GlFormat(GL_RGB8I, GL_RGB_INTEGER, GL_BYTE)
            RenderColorFormat.RGBA8I -> GlFormat(GL_RGBA8I, GL_RGBA_INTEGER, GL_BYTE)
            RenderColorFormat.R8UI -> GlFormat(GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE)
            RenderColorFormat.RG8UI -> GlFormat(GL_RG8UI, GL_RG_INTEGER, GL_UNSIGNED_BYTE)
            RenderColorFormat.RGB8UI -> GlFormat(GL_RGB8UI, GL_RGB_INTEGER, GL_UNSIGNED_BYTE)
            RenderColorFormat.RGBA8UI -> GlFormat(GL_RGBA8UI, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE)
            RenderColorFormat.R16I -> GlFormat(GL_R16I, GL_RED_INTEGER, GL_SHORT)
            RenderColorFormat.RG16I -> GlFormat(GL_RG16I, GL_RG_INTEGER, GL_SHORT)
            RenderColorFormat.RGB16I -> GlFormat(GL_RGB16I, GL_RGB_INTEGER, GL_SHORT)
            RenderColorFormat.RGBA16I -> GlFormat(GL_RGBA16I, GL_RGBA_INTEGER, GL_SHORT)
            RenderColorFormat.R16UI -> GlFormat(GL_R16UI, GL_RED_INTEGER, GL_UNSIGNED_SHORT)
            RenderColorFormat.RG16UI -> GlFormat(GL_RG16UI, GL_RG_INTEGER, GL_UNSIGNED_SHORT)
            RenderColorFormat.RGB16UI -> GlFormat(GL_RGB16UI, GL_RGB_INTEGER, GL_UNSIGNED_SHORT)
            RenderColorFormat.RGBA16UI -> GlFormat(GL_RGBA16UI, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT)
            RenderColorFormat.R32I -> GlFormat(GL_R32I, GL_RED_INTEGER, GL_INT)
            RenderColorFormat.RG32I -> GlFormat(GL_RG32I, GL_RG_INTEGER, GL_INT)
            RenderColorFormat.RGB32I -> GlFormat(GL_RGB32I, GL_RGB_INTEGER, GL_INT)
            RenderColorFormat.RGBA32I -> GlFormat(GL_RGBA32I, GL_RGBA_INTEGER, GL_INT)
            RenderColorFormat.R32UI -> GlFormat(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT)
            RenderColorFormat.RG32UI -> GlFormat(GL_RG32UI, GL_RG_INTEGER, GL_UNSIGNED_INT)
            RenderColorFormat.RGB32UI -> GlFormat(GL_RGB32UI, GL_RGB_INTEGER, GL_UNSIGNED_INT)
            RenderColorFormat.RGBA32UI -> GlFormat(GL_RGBA32UI, GL_RGBA_INTEGER, GL_UNSIGNED_INT)
            RenderColorFormat.RGB10_A2 ->
                GlFormat(GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV)

            RenderColorFormat.R11F_G11F_B10F ->
                GlFormat(GL_R11F_G11F_B10F, GL_RGB, GL_UNSIGNED_INT_10F_11F_11F_REV)

            RenderColorFormat.RGB9_E5 ->
                GlFormat(GL_RGB9_E5, GL_RGB, GL_UNSIGNED_INT_5_9_9_9_REV)
        }

    private fun RenderTargetSize.resolve(base: Vec2i): Vec2i = when (this) {
        is RenderTargetSize.Fixed -> Vec2i(width, height)
        is RenderTargetSize.Relative -> Vec2i(
            (base.x * widthScale).roundToInt().coerceAtLeast(1),
            (base.y * heightScale).roundToInt().coerceAtLeast(1),
        )
    }

}

/** Committed physical framebuffer state; failed GL realization is never cached. */
internal class IrisFramebufferBindingState {
    private var colors = IntArray(0)
    private var depth = -1
    private var drawBuffers: IntArray? = null
    private var readBuffer: Int? = null

    val colorCount: Int get() = colors.size

    fun color(index: Int): Int = colors.getOrElse(index) { 0 }

    fun matchesColors(size: Int, desired: (Int) -> Int): Boolean =
        colors.size == size && (0 until size).all { colors[it] == desired(it) }

    fun matchesDepth(desired: Int): Boolean = depth == desired

    fun matchesSequentialDrawBuffers(size: Int, first: Int): Boolean = drawBuffers?.let { configured ->
        configured.size == size && configured.indices.all { configured[it] == first + it }
    } == true

    fun matchesDrawBuffers(desired: IntArray): Boolean = drawBuffers?.contentEquals(desired) == true

    fun matchesReadBuffer(desired: Int): Boolean = readBuffer == desired

    fun record(colors: IntArray, depth: Int, drawBuffers: IntArray, readBuffer: Int) {
        this.colors = colors.copyOf()
        this.depth = depth
        this.drawBuffers = drawBuffers.copyOf()
        this.readBuffer = readBuffer
    }

    fun clear() {
        colors = IntArray(0)
        depth = -1
        drawBuffers = null
        readBuffer = null
    }
}

internal class IrisBufferFlipState {
    private var flipped = false

    fun read(primary: Int, alternate: Int): Int =
        if (flipped && alternate >= 0) alternate else primary

    fun write(primary: Int, alternate: Int, alternateWrite: Boolean): Int {
        if (!alternateWrite || alternate < 0) return read(primary, alternate)
        return if (flipped) primary else alternate
    }

    fun flip() {
        flipped = !flipped
    }
}
