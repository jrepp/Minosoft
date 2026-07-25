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

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderResourcePlanTest {
    @Test
    fun `accepts typed target and terrain vertex layout headlessly`() {
        val plan = RenderResourcePlan(
            targets = listOf(
                RenderTargetDescriptor(
                    id = RenderResourceId("minosoft:world"),
                    size = RenderTargetSize.Relative(1.0f),
                    colorAttachments = listOf(
                        RenderColorAttachment(RenderResourceId("minosoft:world/color"), RenderColorFormat.RGBA8),
                    ),
                    depth = RenderDepthFormat.DEPTH24,
                ),
            ),
            vertexLayouts = listOf(
                VertexLayoutDeclaration(
                    id = RenderResourceId("minosoft:terrain"),
                    strideBytes = 24,
                    attributes = listOf(
                        VertexAttribute(VertexSemantic.POSITION, VertexAttributeFormat.FLOAT3, 0),
                        VertexAttribute(VertexSemantic.TEXTURE_COORDINATE, VertexAttributeFormat.FLOAT2, 12),
                        VertexAttribute(VertexSemantic.PACKED_LIGHT, VertexAttributeFormat.USHORT2, 20),
                    ),
                ),
            ),
        )

        assertEquals(plan.targets.single().depth, RenderDepthFormat.DEPTH24)
        assertEquals(plan.vertexLayouts.single().attributes.size, 3)
    }

    @Test
    fun `rejects non-finite target scale`() {
        assertThrows<IllegalArgumentException> { RenderTargetSize.Relative(Float.NaN) }
        assertThrows<IllegalArgumentException> { RenderTargetSize.Relative(Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `rejects empty target`() {
        assertThrows<IllegalArgumentException> {
            RenderTargetDescriptor(
                id = RenderResourceId("minosoft:empty"),
                size = RenderTargetSize.Fixed(1, 1),
            )
        }
    }

    @Test
    fun `rejects duplicate and overlapping vertex semantics`() {
        assertThrows<IllegalArgumentException> {
            VertexLayoutDeclaration(
                id = RenderResourceId("minosoft:duplicate"),
                strideBytes = 16,
                attributes = listOf(
                    VertexAttribute(VertexSemantic.POSITION, VertexAttributeFormat.FLOAT3, 0),
                    VertexAttribute(VertexSemantic.POSITION, VertexAttributeFormat.UINT, 12),
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            VertexLayoutDeclaration(
                id = RenderResourceId("minosoft:overlap"),
                strideBytes = 16,
                attributes = listOf(
                    VertexAttribute(VertexSemantic.POSITION, VertexAttributeFormat.FLOAT3, 0),
                    VertexAttribute(VertexSemantic.COLOR, VertexAttributeFormat.UINT, 8),
                ),
            )
        }
    }

    @Test
    fun `rejects checked offset overflow`() {
        assertThrows<ArithmeticException> {
            VertexLayoutDeclaration(
                id = RenderResourceId("minosoft:overflow"),
                strideBytes = Int.MAX_VALUE,
                attributes = listOf(
                    VertexAttribute(VertexSemantic.POSITION, VertexAttributeFormat.FLOAT4, Int.MAX_VALUE - 4),
                ),
            )
        }
    }
}
