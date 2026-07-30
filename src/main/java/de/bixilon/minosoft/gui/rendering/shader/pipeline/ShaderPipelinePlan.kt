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
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourceId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourcePlan
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic
import de.bixilon.minosoft.gui.rendering.system.base.BlendFunctionState
import de.bixilon.minosoft.gui.rendering.shader.SceneStateAbi
import de.bixilon.minosoft.gui.rendering.shader.SceneVertexAbi
import kotlin.math.abs

enum class ShaderProgramPhase {
    SETUP,
    BEGIN,
    SHADOW,
    SHADOW_COMPOSITE,
    PREPARE,
    TERRAIN,
    BLOCK,
    ITEM,
    ENTITY,
    PARTICLE,
    SKY,
    WEATHER,
    HAND,
    BASIC,
    DISTANT_HORIZONS,
    DEFERRED,
    COMPOSITE,
    FINAL,
    UNSUPPORTED,
}

enum class IrisParticleOrdering {
    BEFORE,
    AFTER,
    MIXED,
}

data class IrisShadowDirectives(
    val enabled: Boolean = true,
    val terrain: Boolean = true,
    val translucentTerrain: Boolean = true,
    val entities: Boolean = true,
    val player: Boolean = false,
    val blockEntities: Boolean = true,
    val lightBlockEntities: Boolean = false,
    val distance: Float = 160.0f,
    val nearPlane: Float = 0.05f,
    val farPlane: Float = 256.0f,
    val mapFov: Float? = null,
    val intervalSize: Float = 2.0f,
    val distanceRenderMultiplier: Float = -1.0f,
    val entityShadowDistanceMultiplier: Float = 1.0f,
    val voxelDistance: Float = 0.0f,
    val cullingMode: IrisShadowCullingMode = IrisShadowCullingMode.DEFAULT,
    val voxelizationDetected: Boolean = false,
) {
    fun allowsEntity(isPlayer: Boolean): Boolean = entities || (isPlayer && player)

    fun allowsBlockEntity(luminance: Int): Boolean =
        blockEntities || (lightBlockEntities && luminance > 0)

    /**
     * An absent limit mirrors Iris's negative multiplier: the existing host
     * render-distance gate remains authoritative.
     */
    val terrainDistanceLimit: Float?
        get() = (distance * distanceRenderMultiplier).takeIf {
            distanceRenderMultiplier >= 0.0f
        }

    /**
     * Iris reuses the terrain frustum for 1 or negative entity multipliers.
     * A distinct nonnegative multiplier adds its own distance box only when
     * the pack also supplied a nonnegative terrain multiplier.
     */
    val entityDistanceLimit: Float?
        get() = when {
            entityShadowDistanceMultiplier == 1.0f || entityShadowDistanceMultiplier < 0.0f ->
                terrainDistanceLimit
            distanceRenderMultiplier < 0.0f -> null
            else -> distance * distanceRenderMultiplier * entityShadowDistanceMultiplier
        }

    fun allowsTerrainSection(deltaX: Int, deltaY: Int, deltaZ: Int): Boolean {
        val limit = terrainDistanceLimit ?: return true
        if (limit <= 0.0f) return false
        val extent = limit + SECTION_HALF_EXTENT
        return abs(deltaX) <= extent && abs(deltaY) <= extent && abs(deltaZ) <= extent
    }

    fun allowsEntityBounds(
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ): Boolean = allowsBounds(
        entityDistanceLimit,
        cameraX,
        cameraY,
        cameraZ,
        minX,
        minY,
        minZ,
        maxX,
        maxY,
        maxZ,
    )

    fun allowsBlockEntityBounds(
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
    ): Boolean = allowsBounds(
        entityDistanceLimit,
        cameraX,
        cameraY,
        cameraZ,
        blockX - 1.0,
        blockY - 1.0,
        blockZ - 1.0,
        blockX + 1.0,
        blockY + 1.0,
        blockZ + 1.0,
    )

    private fun allowsBounds(
        limit: Float?,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ): Boolean {
        limit ?: return true
        if (limit <= 0.0f) return false
        return maxX >= cameraX - limit && minX <= cameraX + limit &&
            maxY >= cameraY - limit && minY <= cameraY + limit &&
            maxZ >= cameraZ - limit && minZ <= cameraZ + limit
    }

    init {
        require(distance.isFinite() && distance > 0.0f)
        require(nearPlane.isFinite() && nearPlane >= 0.0f)
        require(farPlane.isFinite() && farPlane > nearPlane)
        require(mapFov == null || mapFov.isFinite() && mapFov > 0.0f && mapFov < 180.0f)
        require(intervalSize.isFinite())
        require(distanceRenderMultiplier.isFinite())
        require(entityShadowDistanceMultiplier.isFinite())
        require(voxelDistance.isFinite())
    }

    companion object {
        private const val SECTION_HALF_EXTENT = 8.0f
    }
}

enum class ShaderBufferKind(
    val prefix: String,
    val maxIndex: Int,
) {
    COLORTEX("colortex", 15),
    DEPTHTEX("depthtex", 2),
    DHDEPTHTEX("dhDepthTex", 1),
    SHADOWTEX("shadowtex", 1),
    SHADOWCOLOR("shadowcolor", 7),
}

data class ShaderBufferId(
    val kind: ShaderBufferKind,
    val index: Int,
) : Comparable<ShaderBufferId> {
    init {
        require(index in 0..kind.maxIndex) {
            "${kind.prefix} index must be in 0..${kind.maxIndex}: $index"
        }
    }

    val sampler: String get() = "${kind.prefix}$index"
    val resource: RenderResourceId get() = RenderResourceId("iris:$sampler")

    override fun compareTo(other: ShaderBufferId): Int =
        compareValuesBy(this, other, { it.kind.ordinal }, ShaderBufferId::index)

    override fun toString(): String = sampler
}

sealed interface ShaderBufferFormat {
    data class Color(val value: RenderColorFormat) : ShaderBufferFormat
    data class Depth(val value: RenderDepthFormat) : ShaderBufferFormat
}

enum class ShaderBufferFilter {
    NEAREST,
    LINEAR,
    SHADOW_COMPARE,
}

sealed interface ShaderBufferClearColor {
    object Fog : ShaderBufferClearColor

    data class Fixed(
        val components: List<Float>,
    ) : ShaderBufferClearColor {
        init {
            require(components.size == 4 && components.all { it.isFinite() }) {
                "Fixed shader-buffer clear colors must contain four finite components"
            }
        }
    }
}

data class ShaderBufferDescriptor(
    val id: ShaderBufferId,
    val format: ShaderBufferFormat,
    val size: RenderTargetSize,
    val clear: RenderClearPolicy,
    val clearColor: ShaderBufferClearColor,
    val filter: ShaderBufferFilter,
    val mipmapped: Boolean,
    val doubleBuffered: Boolean,
) {
    init {
        require(
            (id.kind == ShaderBufferKind.COLORTEX || id.kind == ShaderBufferKind.SHADOWCOLOR) ==
                (format is ShaderBufferFormat.Color),
        ) {
            "Shader buffer $id has incompatible format $format"
        }
        require(doubleBuffered == (id.kind == ShaderBufferKind.COLORTEX || id.kind == ShaderBufferKind.SHADOWCOLOR)) {
            "Only color shader buffers are double-buffered: $id"
        }
    }
}

data class ShaderProgramResourceUsage(
    val colorWrites: List<ShaderBufferId> = emptyList(),
    val sampledBuffers: Map<String, ShaderBufferId> = emptyMap(),
    val sampledCustomTextures: Map<String, IrisTextureId> = emptyMap(),
    val sampledCustomImages: Map<String, String> = emptyMap(),
    val renderTargetImages: Map<String, ShaderBufferId> = emptyMap(),
    val customImages: Set<String> = emptySet(),
    val flipsAfter: Set<ShaderBufferId> = emptySet(),
    val mipmapsBefore: Set<ShaderBufferId> = emptySet(),
) {
    init {
        require(colorWrites.toSet().size == colorWrites.size) {
            "Shader program contains duplicate color outputs"
        }
        require(flipsAfter.all { it in colorWrites }) {
            "Shader program can only flip color buffers it writes"
        }
        require(renderTargetImages.values.all {
            it.kind == ShaderBufferKind.COLORTEX || it.kind == ShaderBufferKind.SHADOWCOLOR
        }) {
            "Only color render targets can be bound as Iris images"
        }
    }
}

sealed interface IrisBlendMode {
    data object Off : IrisBlendMode

    data class Enabled(
        val function: BlendFunctionState,
    ) : IrisBlendMode
}

data class IrisProgramBlendOverride(
    val program: IrisBlendMode? = null,
    val buffers: Map<ShaderBufferId, IrisBlendMode> = emptyMap(),
) {
    init {
        require(buffers.keys.all {
            it.kind == ShaderBufferKind.COLORTEX || it.kind == ShaderBufferKind.SHADOWCOLOR
        }) {
            "Iris blend overrides may only target color buffers"
        }
    }

    val isEmpty: Boolean get() = program == null && buffers.isEmpty()
    val requiresPerBufferBlending: Boolean get() = buffers.isNotEmpty()

    fun resolveProgram(
        hostEnabled: Boolean,
        hostFunction: BlendFunctionState,
    ): IrisResolvedBlendMode =
        program?.resolved() ?: IrisResolvedBlendMode(hostEnabled, hostFunction)

    fun resolve(
        outputs: List<ShaderBufferId>,
        hostEnabled: Boolean,
        hostFunction: BlendFunctionState,
    ): List<IrisResolvedBlendMode> {
        val inherited = resolveProgram(hostEnabled, hostFunction)
        return outputs.map { output -> buffers[output]?.resolved() ?: inherited }
    }

    private fun IrisBlendMode.resolved(): IrisResolvedBlendMode = when (this) {
        IrisBlendMode.Off -> IrisResolvedBlendMode(false, BlendFunctionState.DEFAULT)
        is IrisBlendMode.Enabled -> IrisResolvedBlendMode(true, function)
    }
}

data class IrisResolvedBlendMode(
    val enabled: Boolean,
    val function: BlendFunctionState,
)

enum class IrisTextureStage {
    GLOBAL,
    SETUP,
    BEGIN,
    SHADOW_COMPOSITE,
    PREPARE,
    GBUFFERS_AND_SHADOW,
    DEFERRED,
    COMPOSITE_AND_FINAL,
}

@JvmInline
value class IrisTextureId(val value: String) {
    init {
        require(value.isNotBlank()) { "Iris texture ID must not be blank" }
    }

    override fun toString(): String = value
}

class IrisTextureBytes(bytes: ByteArray) {
    private val content = bytes.copyOf()

    val size: Int get() = content.size

    fun copy(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is IrisTextureBytes && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()
}

data class IrisPngTextureDescriptor(
    val id: IrisTextureId,
    val path: String,
    val content: IrisTextureBytes,
    val width: Int,
    val height: Int,
    val blur: Boolean = false,
    val clamp: Boolean = false,
)

enum class IrisRawTextureTarget {
    TEXTURE_1D,
    TEXTURE_2D,
    TEXTURE_3D,
    TEXTURE_RECTANGLE,
}

enum class IrisRawTextureFormat(val components: Int) {
    RED(1),
    RG(2),
    RGB(3),
    RGBA(4),
}

enum class IrisRawTextureType(val bytes: Int) {
    UNSIGNED_BYTE(1),
    HALF_FLOAT(2),
    FLOAT(4),
}

data class IrisRawTextureDescriptor(
    val id: IrisTextureId,
    val path: String,
    val content: IrisTextureBytes,
    val target: IrisRawTextureTarget,
    val internalFormat: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val format: IrisRawTextureFormat,
    val type: IrisRawTextureType,
    val blur: Boolean = true,
    val clamp: Boolean = true,
)

/**
 * A texture owned by the active Minecraft resource generation.
 *
 * Unlike pack-local PNG/raw descriptors this is only an immutable compatibility
 * key. The renderer must resolve it through a source-native bridge rather than
 * uploading or pretending that Minosoft's texture arrays are a vanilla atlas.
 */
data class IrisResourceTextureDescriptor(
    val id: IrisTextureId,
    val location: String,
)

sealed interface IrisCustomTextureDescriptor {
    val id: IrisTextureId
    val path: String

    data class Png(val value: IrisPngTextureDescriptor) : IrisCustomTextureDescriptor {
        override val id get() = value.id
        override val path get() = value.path
    }

    data class Raw(val value: IrisRawTextureDescriptor) : IrisCustomTextureDescriptor {
        override val id get() = value.id
        override val path get() = value.path
    }

    data class Resource(val value: IrisResourceTextureDescriptor) : IrisCustomTextureDescriptor {
        override val id get() = value.id
        override val path get() = value.location
    }
}

sealed interface IrisNoiseTextureDescriptor {
    data class Generated(val resolution: Int = 256) : IrisNoiseTextureDescriptor
    data class Custom(val texture: IrisPngTextureDescriptor) : IrisNoiseTextureDescriptor
}

data class IrisCustomTextureBinding(
    val stage: IrisTextureStage,
    val sampler: String,
    val texture: IrisCustomTextureDescriptor,
)

data class IrisTexturePlan(
    val custom: List<IrisCustomTextureBinding> = emptyList(),
    val noise: IrisNoiseTextureDescriptor = IrisNoiseTextureDescriptor.Generated(),
) {
    init {
        require(custom.map { it.stage to it.sampler }.toSet().size == custom.size) {
            "Iris texture plan contains duplicate stage sampler bindings"
        }
    }

    val physicalTextureCount: Int get() =
        custom.asSequence()
            .filterNot { it.texture is IrisCustomTextureDescriptor.Resource }
            .map { it.texture.id }
            .toSet()
            .size + 1
}

enum class IrisCustomImageTarget(val dimensions: Int) {
    TEXTURE_1D(1),
    TEXTURE_2D(2),
    TEXTURE_3D(3),
}

enum class IrisCustomImageFormat {
    RED,
    RG,
    RGB,
    RGBA,
    RED_INTEGER,
    RG_INTEGER,
    RGB_INTEGER,
    RGBA_INTEGER,
}

enum class IrisCustomImageInternalFormat(val bytesPerTexel: Int) {
    R8(1),
    RG8(2),
    RGB8(3),
    RGBA8(4),
    R16F(2),
    RG16F(4),
    RGB16F(6),
    RGBA16F(8),
    R32F(4),
    RG32F(8),
    RGB32F(12),
    RGBA32F(16),
    R8I(1),
    RG8I(2),
    RGB8I(3),
    RGBA8I(4),
    R8UI(1),
    RG8UI(2),
    RGB8UI(3),
    RGBA8UI(4),
    R16I(2),
    RG16I(4),
    RGB16I(6),
    RGBA16I(8),
    R16UI(2),
    RG16UI(4),
    RGB16UI(6),
    RGBA16UI(8),
    R32I(4),
    RG32I(8),
    RGB32I(12),
    RGBA32I(16),
    R32UI(4),
    RG32UI(8),
    RGB32UI(12),
    RGBA32UI(16),
}

enum class IrisCustomImageType {
    BYTE,
    SHORT,
    INT,
    HALF_FLOAT,
    FLOAT,
    UNSIGNED_BYTE,
    UNSIGNED_SHORT,
    UNSIGNED_INT,
}

sealed interface IrisCustomImageSize {
    data class Absolute(
        val width: Int,
        val height: Int = 1,
        val depth: Int = 1,
    ) : IrisCustomImageSize {
        init {
            require(width > 0 && height > 0 && depth > 0) {
                "Iris custom-image dimensions must be positive: ${width}x${height}x$depth"
            }
        }
    }

    data class Relative(
        val widthScale: Float,
        val heightScale: Float,
    ) : IrisCustomImageSize {
        init {
            require(widthScale.isFinite() && widthScale > 0.0f) {
                "Iris relative custom-image width must be finite and positive"
            }
            require(heightScale.isFinite() && heightScale > 0.0f) {
                "Iris relative custom-image height must be finite and positive"
            }
        }
    }
}

data class IrisCustomImageDescriptor(
    val name: String,
    val sampler: String?,
    val target: IrisCustomImageTarget,
    val format: IrisCustomImageFormat,
    val internalFormat: IrisCustomImageInternalFormat,
    val type: IrisCustomImageType,
    val clear: Boolean,
    val size: IrisCustomImageSize,
) {
    init {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Invalid Iris custom-image name '$name'"
        }
        require(sampler == null || sampler.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Invalid Iris custom-image sampler '$sampler'"
        }
        require(size !is IrisCustomImageSize.Relative || target == IrisCustomImageTarget.TEXTURE_2D) {
            "Relative Iris custom images must be two-dimensional"
        }
    }
}

data class IrisShaderStorageBufferDescriptor(
    val index: Int,
    val bytesPerElement: Long,
    val relative: Boolean = false,
    val widthScale: Float = 0.0f,
    val heightScale: Float = 0.0f,
) {
    init {
        require(index in 0..8) { "Iris shader-storage buffer index must be in 0..8: $index" }
        require(bytesPerElement > 0L) { "Iris shader-storage buffer size must be positive" }
        if (relative) {
            require(widthScale.isFinite() && widthScale > 0.0f) {
                "Iris relative shader-storage width scale must be finite and positive"
            }
            require(heightScale.isFinite() && heightScale > 0.0f) {
                "Iris relative shader-storage height scale must be finite and positive"
            }
        } else {
            require(widthScale == 0.0f && heightScale == 0.0f) {
                "Absolute Iris shader-storage buffers cannot declare relative scales"
            }
        }
    }
}

data class IrisCustomResourcePlan(
    val images: List<IrisCustomImageDescriptor> = emptyList(),
    val shaderStorageBuffers: List<IrisShaderStorageBufferDescriptor> = emptyList(),
) {
    init {
        require(images.map(IrisCustomImageDescriptor::name).toSet().size == images.size) {
            "Iris custom-resource plan contains duplicate image names"
        }
        require(images.mapNotNull(IrisCustomImageDescriptor::sampler).toSet().size ==
            images.mapNotNull(IrisCustomImageDescriptor::sampler).size) {
            "Iris custom-resource plan contains duplicate image samplers"
        }
        require(shaderStorageBuffers.map(IrisShaderStorageBufferDescriptor::index).toSet().size ==
            shaderStorageBuffers.size) {
            "Iris custom-resource plan contains duplicate shader-storage bindings"
        }
    }
}

data class ShaderBufferPlan(
    val buffers: List<ShaderBufferDescriptor> = emptyList(),
) {
    init {
        require(buffers.map(ShaderBufferDescriptor::id).toSet().size == buffers.size) {
            "Shader buffer plan contains duplicate logical buffers"
        }
    }

    operator fun get(id: ShaderBufferId): ShaderBufferDescriptor? = buffers.firstOrNull { it.id == id }
}

data class ShaderProgramSource(
    val name: String,
    val phase: ShaderProgramPhase,
    val vertex: String,
    val fragment: String,
    val geometry: String? = null,
    val uniforms: Set<String>,
    val samplers: Set<String>,
    val sceneBridges: Set<SceneProgramBridge> = emptySet(),
    val resourceUsage: ShaderProgramResourceUsage = ShaderProgramResourceUsage(),
    val alphaTest: IrisAlphaTest? = null,
    val blendOverride: IrisProgramBlendOverride = IrisProgramBlendOverride(),
    val inspectionVertex: String? = null,
    val inspectionFragment: String? = null,
    val inspectionGeometry: String? = null,
    val tessellationControl: String? = null,
    val tessellationEvaluation: String? = null,
    val inspectionTessellationControl: String? = null,
    val inspectionTessellationEvaluation: String? = null,
    val tessellationPatchVertices: Int? = null,
) {
    init {
        require((tessellationControl == null) == (tessellationEvaluation == null)) {
            "Shader program $name must provide tessellation control and evaluation stages together"
        }
        require(
            (inspectionTessellationControl == null) ==
                (inspectionTessellationEvaluation == null),
        ) {
            "Shader program $name must provide inspected tessellation stages together"
        }
        require((tessellationControl == null) == (tessellationPatchVertices == null)) {
            "Shader program $name tessellation stages require an explicit patch vertex count"
        }
        require(tessellationPatchVertices == null || tessellationPatchVertices > 0) {
            "Shader program $name tessellation patch vertex count must be positive"
        }
    }
}

sealed interface IrisComputeDispatch {
    data class Absolute(
        val x: Int,
        val y: Int,
        val z: Int,
    ) : IrisComputeDispatch {
        init {
            require(x > 0 && y > 0 && z > 0) { "Iris compute work groups must be positive" }
        }
    }

    data class Relative(
        val widthScale: Float,
        val heightScale: Float,
        val localSizeX: Int,
        val localSizeY: Int,
    ) : IrisComputeDispatch {
        init {
            require(widthScale.isFinite() && widthScale > 0.0f)
            require(heightScale.isFinite() && heightScale > 0.0f)
            require(localSizeX > 0 && localSizeY > 0)
        }
    }

    data class Indirect(
        val bufferIndex: Int,
        val offset: Long,
    ) : IrisComputeDispatch {
        init {
            require(bufferIndex in 0..8) {
                "Iris indirect compute buffer index must be in 0..8: $bufferIndex"
            }
            require(offset >= 0L && offset % Int.SIZE_BYTES == 0L) {
                "Iris indirect compute offset must be a non-negative multiple of ${Int.SIZE_BYTES}: $offset"
            }
        }
    }
}

data class IrisComputeProgramSource(
    val name: String,
    val phase: ShaderProgramPhase,
    val source: String,
    val uniforms: Set<String>,
    val samplers: Set<String>,
    val dispatch: IrisComputeDispatch,
    val resourceUsage: ShaderProgramResourceUsage,
)

enum class IrisAlphaTestFunction(val operator: String?) {
    NEVER(null),
    LESS("<"),
    EQUAL("=="),
    LEQUAL("<="),
    GREATER(">"),
    NOTEQUAL("!="),
    GEQUAL(">="),
    ALWAYS(null),
}

data class IrisAlphaTest(
    val function: IrisAlphaTestFunction,
    val reference: Float,
) {
    init {
        require(reference.isFinite()) { "Iris alpha-test reference must be finite" }
    }

    companion object {
        val ALWAYS = IrisAlphaTest(IrisAlphaTestFunction.ALWAYS, 0.0f)
        val NON_ZERO = IrisAlphaTest(IrisAlphaTestFunction.GREATER, 0.0001f)
        val ONE_TENTH = IrisAlphaTest(IrisAlphaTestFunction.GREATER, 0.1f)
    }
}

/**
 * Iris ShaderKey alpha tests that are fixed by an exact retained host ABI.
 *
 * An authored alphaTest.<program> directive always wins, including `off`.
 * Other ShaderKey defaults still need a render-layer discriminator before they
 * can be applied without conflating opaque and cutout variants of one ABI.
 */
internal object IrisAlphaTestDefaults {
    fun scene(program: ShaderProgramSource, bridge: SceneProgramBridge): IrisAlphaTest? {
        program.alphaTest?.let { return it }
        return when (bridge.stateAbi) {
            SceneStateAbi.ARM,
            SceneStateAbi.HELD_ITEM,
            -> IrisAlphaTest.ONE_TENTH

            SceneStateAbi.GENERIC_TEXTURE_2D,
            SceneStateAbi.WORLD_BORDER,
            -> IrisAlphaTest.NON_ZERO

            else -> null
        }
    }
}

data class SceneProgramBridge(
    val vertexAbi: SceneVertexAbi,
    val stateAbi: SceneStateAbi,
    val uniforms: Set<String>,
)

data class IrisSmoothingDirectives(
    val wetnessHalfLife: Float = 600.0f,
    val drynessHalfLife: Float = 200.0f,
    val eyeBrightnessHalfLife: Float = 10.0f,
) {
    init {
        require(wetnessHalfLife.isFinite() && wetnessHalfLife >= 0.0f) {
            "Shader-pack wetness half-life must be finite and non-negative"
        }
        require(drynessHalfLife.isFinite() && drynessHalfLife >= 0.0f) {
            "Shader-pack dryness half-life must be finite and non-negative"
        }
        require(eyeBrightnessHalfLife.isFinite() && eyeBrightnessHalfLife >= 0.0f) {
            "Shader-pack eye-brightness half-life must be finite and non-negative"
        }
    }
}

enum class IrisCustomUniformType(val components: Int) {
    BOOL(1),
    INT(1),
    FLOAT(1),
    VEC2(2),
    VEC3(3),
    VEC4(4),
}

data class IrisCustomUniformDefinition(
    val name: String,
    val type: IrisCustomUniformType,
    val expression: String,
    val uniform: Boolean,
)

data class IrisCustomUniformPlan(
    val definitions: List<IrisCustomUniformDefinition> = emptyList(),
) {
    init {
        require(definitions.map(IrisCustomUniformDefinition::name).toSet().size == definitions.size) {
            "Iris custom-uniform plan contains duplicate names"
        }
    }

    val uniforms: Set<String> = definitions.filter(IrisCustomUniformDefinition::uniform)
        .mapTo(linkedSetOf(), IrisCustomUniformDefinition::name)
}

data class ShaderPipelinePlan(
    val owner: RenderOwnerId,
    val packName: String,
    val fingerprint: String,
    val programDirectory: String? = null,
    val preprocessorDefines: Map<String, String> = emptyMap(),
    val views: Set<RenderViewId>,
    val resources: RenderResourcePlan,
    val programs: List<ShaderProgramSource>,
    val computePrograms: List<IrisComputeProgramSource> = emptyList(),
    val requiredTerrainSemantics: Set<VertexSemantic>,
    val buffers: ShaderBufferPlan = ShaderBufferPlan(),
    val textures: IrisTexturePlan = IrisTexturePlan(),
    val customResources: IrisCustomResourcePlan = IrisCustomResourcePlan(),
    val customUniforms: IrisCustomUniformPlan = IrisCustomUniformPlan(),
    val selectedProfile: String? = null,
    val sunPathRotation: Float = 0.0f,
    val smoothingDirectives: IrisSmoothingDirectives = IrisSmoothingDirectives(),
    val idMaps: IrisIdMaps = IrisIdMaps.EMPTY,
    val oldHandLight: Boolean = true,
    val underwaterOverlay: Boolean = true,
    val particlesOrdering: IrisParticleOrdering = IrisParticleOrdering.AFTER,
    val separateEntityDraws: Boolean = false,
    val skipAllRendering: Boolean = false,
    val shadowDirectives: IrisShadowDirectives = IrisShadowDirectives(),
) {
    init {
        require(packName.isNotBlank()) { "Shader-pack name must not be blank" }
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Shader-pack fingerprint must be SHA-256" }
        require(preprocessorDefines.size <= 64) { "Shader pipeline contains too many preprocessor defines" }
        require(preprocessorDefines.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
            "Shader pipeline contains an invalid preprocessor define name"
        }
        require(preprocessorDefines.values.all { it.length <= 64 && '\n' !in it && '\r' !in it }) {
            "Shader pipeline contains an invalid preprocessor define value"
        }
        require(RenderViewId.MAIN in views) { "Shader pipeline must declare the main view" }
        require(sunPathRotation.isFinite()) { "Shader-pack sun path rotation must be finite" }
        require(programs.isNotEmpty()) { "Shader pipeline must contain at least one program" }
        require(programs.map(ShaderProgramSource::name).toSet().size == programs.size) {
            "Shader pipeline contains duplicate program names"
        }
        require(computePrograms.map(IrisComputeProgramSource::name).toSet().size == computePrograms.size) {
            "Shader pipeline contains duplicate compute program names"
        }
    }
}
