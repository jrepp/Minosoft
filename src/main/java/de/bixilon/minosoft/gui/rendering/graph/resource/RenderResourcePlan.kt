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

package de.bixilon.minosoft.gui.rendering.graph.resource

private val RESOURCE_IDENTIFIER = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")

data class RenderResourceId(
    val value: String,
) : Comparable<RenderResourceId> {
    init {
        require(RESOURCE_IDENTIFIER.matches(value)) { "Invalid render resource identifier: $value" }
    }

    override fun compareTo(other: RenderResourceId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

sealed interface RenderTargetSize {
    data class Relative(
        val widthScale: Float,
        val heightScale: Float = widthScale,
    ) : RenderTargetSize {
        init {
            require(widthScale.isFinite() && widthScale > 0.0f && widthScale <= MAX_SCALE) {
                "Render target width scale must be finite and in (0, $MAX_SCALE]: $widthScale"
            }
            require(heightScale.isFinite() && heightScale > 0.0f && heightScale <= MAX_SCALE) {
                "Render target height scale must be finite and in (0, $MAX_SCALE]: $heightScale"
            }
        }

        private companion object {
            const val MAX_SCALE = 4.0f
        }
    }

    data class Fixed(
        val width: Int,
        val height: Int,
    ) : RenderTargetSize {
        init {
            require(width > 0 && height > 0) { "Render target dimensions must be positive: ${width}x$height" }
        }
    }
}

enum class RenderColorFormat {
    R8,
    RG8,
    RGB8,
    RGBA8,
    R8_SNORM,
    RG8_SNORM,
    RGB8_SNORM,
    RGBA8_SNORM,
    R16,
    RG16,
    RGB16,
    RGBA16,
    R16_SNORM,
    RG16_SNORM,
    RGB16_SNORM,
    RGBA16_SNORM,
    R16F,
    RGBA16F,
    RG16F,
    RGB16F,
    R32F,
    RG32F,
    RGB32F,
    RGBA32F,
    R8I,
    RG8I,
    RGB8I,
    RGBA8I,
    R8UI,
    RG8UI,
    RGB8UI,
    RGBA8UI,
    R16I,
    RG16I,
    RGB16I,
    RGBA16I,
    R16UI,
    RG16UI,
    RGB16UI,
    RGBA16UI,
    R32I,
    RG32I,
    RGB32I,
    RGBA32I,
    R32UI,
    RG32UI,
    RGB32UI,
    RGBA32UI,
    RGB10_A2,
    R11F_G11F_B10F,
    RGB9_E5,
}

enum class RenderDepthFormat {
    DEPTH24,
    DEPTH32F,
    DEPTH24_STENCIL8,
}

enum class RenderClearPolicy {
    LOAD,
    CLEAR,
    DISCARD,
}

data class RenderColorAttachment(
    val id: RenderResourceId,
    val format: RenderColorFormat,
    val clear: RenderClearPolicy = RenderClearPolicy.CLEAR,
)

class RenderTargetDescriptor(
    val id: RenderResourceId,
    val size: RenderTargetSize,
    val samples: Int = 1,
    colorAttachments: List<RenderColorAttachment> = emptyList(),
    val depth: RenderDepthFormat? = null,
    val depthClear: RenderClearPolicy = RenderClearPolicy.CLEAR,
) {
    val colorAttachments: List<RenderColorAttachment> = colorAttachments.toList()

    init {
        require(samples in 1..MAX_DECLARED_SAMPLES) {
            "Declared sample count must be in 1..$MAX_DECLARED_SAMPLES before driver validation: $samples"
        }
        require(this.colorAttachments.isNotEmpty() || depth != null) {
            "Render target $id must declare at least one color or depth attachment"
        }
        require(this.colorAttachments.map(RenderColorAttachment::id).toSet().size == this.colorAttachments.size) {
            "Render target $id contains duplicate color attachment identifiers"
        }
    }

    private companion object {
        const val MAX_DECLARED_SAMPLES = 64
    }
}

enum class VertexSemantic {
    POSITION,
    TEXTURE_COORDINATE,
    TEXTURE_LAYER,
    COLOR,
    PACKED_LIGHT,
    PACKED_LIGHT_COLOR,
    NORMAL,
    TANGENT,
    MATERIAL_ID,
    BLOCK_ID,
    MID_TEXTURE_COORDINATE,
    MID_BLOCK,
}

enum class VertexAttributeFormat(
    val byteSize: Int,
) {
    FLOAT2(8),
    FLOAT3(12),
    FLOAT4(16),
    HALF2(4),
    BYTE4_NORMALIZED(4),
    UBYTE4_NORMALIZED(4),
    SHORT2(4),
    USHORT2(4),
    INT(4),
    UINT(4),
}

data class VertexAttribute(
    val semantic: VertexSemantic,
    val format: VertexAttributeFormat,
    val offsetBytes: Int,
) {
    init {
        require(offsetBytes >= 0) { "Vertex attribute offset must not be negative: $offsetBytes" }
    }
}

class VertexLayoutDeclaration(
    val id: RenderResourceId,
    val strideBytes: Int,
    attributes: List<VertexAttribute>,
) {
    val attributes: List<VertexAttribute> = attributes.toList()

    init {
        require(strideBytes > 0) { "Vertex stride must be positive: $strideBytes" }
        require(this.attributes.isNotEmpty()) { "Vertex layout $id must contain at least one attribute" }
        require(this.attributes.map(VertexAttribute::semantic).toSet().size == this.attributes.size) {
            "Vertex layout $id contains duplicate semantics"
        }

        val occupied = mutableListOf<IntRange>()
        for (attribute in this.attributes) {
            val endExclusive = Math.addExact(attribute.offsetBytes, attribute.format.byteSize)
            require(endExclusive <= strideBytes) {
                "Vertex attribute ${attribute.semantic} exceeds stride $strideBytes in $id"
            }
            val range = attribute.offsetBytes until endExclusive
            require(occupied.none { existing -> range.first <= existing.last && existing.first <= range.last }) {
                "Vertex attribute ${attribute.semantic} overlaps another attribute in $id"
            }
            occupied += range
        }
    }
}

class RenderResourcePlan(
    targets: List<RenderTargetDescriptor>,
    vertexLayouts: List<VertexLayoutDeclaration>,
) {
    val targets: List<RenderTargetDescriptor> = targets.toList()
    val vertexLayouts: List<VertexLayoutDeclaration> = vertexLayouts.toList()

    init {
        require(this.targets.map(RenderTargetDescriptor::id).toSet().size == this.targets.size) {
            "Render resource plan contains duplicate target identifiers"
        }
        require(this.vertexLayouts.map(VertexLayoutDeclaration::id).toSet().size == this.vertexLayouts.size) {
            "Render resource plan contains duplicate vertex layout identifiers"
        }
    }
}
