/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.terrain.scene

import de.bixilon.minosoft.terrain.model.identity.TerrainDomain
import de.bixilon.minosoft.terrain.model.interop.DistantTerrainProvider
import de.bixilon.minosoft.terrain.model.interop.NearTerrainProvider
import de.bixilon.minosoft.terrain.model.interop.TerrainInteropDescriptor
import de.bixilon.minosoft.terrain.model.interop.TerrainLightingSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainMaterialClass
import de.bixilon.minosoft.terrain.model.interop.TerrainTintSemantics
import de.bixilon.minosoft.terrain.model.interop.TerrainUploadCapability
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainPipelineGenerationTest {
    @Test
    fun `one lease pins the complete previous pipeline generation`() {
        val closed = mutableListOf<String>()
        val initial = candidate("old", closed)
        val registry = TerrainPipelineRegistry(initial)
        val old = registry.acquire()

        assertEquals(1L, registry.publish { candidate("new", closed) })
        assertTrue(closed.isEmpty())
        assertEquals("old-near", old.value.nearProvider.descriptor.providerId)
        assertEquals("old-distant", old.value.distantProvider?.descriptor?.providerId)
        assertEquals(1, registry.stats().retiredAwaitingLeases)

        old.close()
        assertEquals(listOf("old-shader", "old-material", "old-distant", "old-near"), closed)

        registry.acquire().use {
            assertEquals(1L, it.value.generation)
            assertEquals("new-near", it.value.nearProvider.descriptor.providerId)
        }
        registry.close()
        assertEquals(
            listOf(
                "old-shader",
                "old-material",
                "old-distant",
                "old-near",
                "new-shader",
                "new-material",
                "new-distant",
                "new-near",
            ),
            closed,
        )
    }

    @Test
    fun `failed construction preserves the active pipeline`() {
        val closed = mutableListOf<String>()
        val registry = TerrainPipelineRegistry(candidate("active", closed))

        assertThrows<IllegalStateException> {
            registry.publish { throw IllegalStateException("candidate failed") }
        }

        registry.acquire().use { assertEquals(0L, it.value.generation) }
        assertTrue(closed.isEmpty())
        registry.close()
    }

    @Test
    fun `invalid candidate closes all constructed resources in reverse order`() {
        val closed = mutableListOf<String>()
        val near = Provider("invalid-near", TerrainDomain.DISTANT, closed)
        val material = Material("invalid-material", closed)
        val shader = Shader("invalid-shader", closed)

        assertThrows<IllegalArgumentException> {
            TerrainPipelineCandidate.create(
                mode = TerrainRuntimeMode.UNIFIED_COMPARE,
                nearProvider = near,
                distantProvider = null,
                materialTable = material,
                shaderPipeline = shader,
                nearLayout = TerrainPhysicalLayout("minosoft:near", 1L, TerrainDomain.NEAR),
                distantLayout = null,
                declaredViews = setOf("minosoft:main"),
                declaredMaterialPasses = setOf("minosoft:opaque"),
                renderGraphGeneration = 1L,
            )
        }

        assertEquals(listOf("invalid-shader", "invalid-material", "invalid-near"), closed)
    }

    @Test
    fun `candidate published after close is rejected and cleaned`() {
        val closed = mutableListOf<String>()
        val registry = TerrainPipelineRegistry(candidate("active", closed))
        registry.close()
        val rejected = candidate("rejected", closed)

        assertThrows<IllegalStateException> { registry.publish { rejected } }

        assertTrue("rejected-near" in closed)
        assertTrue("rejected-distant" in closed)
    }

    @Test
    fun `candidate rejects aliased near distant layouts and unsupported views`() {
        val closed = mutableListOf<String>()
        assertThrows<IllegalArgumentException> {
            TerrainPipelineCandidate.create(
                mode = TerrainRuntimeMode.UNIFIED,
                nearProvider = Provider(
                    "near", TerrainDomain.NEAR, closed, physicalLayoutIds = setOf("minosoft:shared"),
                ),
                distantProvider = Provider(
                    "distant", TerrainDomain.DISTANT, closed, physicalLayoutIds = setOf("minosoft:shared"),
                ),
                materialTable = Material("material", closed),
                shaderPipeline = Shader("shader", closed),
                nearLayout = TerrainPhysicalLayout("minosoft:shared", 1L, TerrainDomain.NEAR),
                distantLayout = TerrainPhysicalLayout("minosoft:shared", 1L, TerrainDomain.DISTANT),
                declaredViews = setOf("minosoft:main"),
                declaredMaterialPasses = setOf("minosoft:opaque"),
                renderGraphGeneration = 1L,
            )
        }
        assertEquals(listOf("shader", "material", "distant", "near"), closed)

        val unsupported = mutableListOf<String>()
        assertThrows<IllegalArgumentException> {
            TerrainPipelineCandidate.create(
                mode = TerrainRuntimeMode.UNIFIED,
                nearProvider = Provider("unsupported-near", TerrainDomain.NEAR, unsupported, setOf("minosoft:main")),
                distantProvider = null,
                materialTable = Material("unsupported-material", unsupported),
                shaderPipeline = Shader("unsupported-shader", unsupported),
                nearLayout = TerrainPhysicalLayout("minosoft:near", 1L, TerrainDomain.NEAR),
                distantLayout = null,
                declaredViews = setOf("minosoft:main", "minosoft:shadow"),
                declaredMaterialPasses = setOf("minosoft:opaque"),
                renderGraphGeneration = 1L,
            )
        }
        assertEquals(listOf("unsupported-shader", "unsupported-material", "unsupported-near"), unsupported)
    }

    @Test
    fun `invalid aliased ownership closes the shared resource once`() {
        val closed = mutableListOf<String>()
        val aliased = Provider("aliased", TerrainDomain.NEAR, closed)

        assertThrows<IllegalArgumentException> {
            TerrainPipelineCandidate.create(
                mode = TerrainRuntimeMode.UNIFIED,
                nearProvider = aliased,
                distantProvider = aliased,
                materialTable = Material("material", closed),
                shaderPipeline = Shader("shader", closed),
                nearLayout = TerrainPhysicalLayout("minosoft:near", 1L, TerrainDomain.NEAR),
                distantLayout = TerrainPhysicalLayout("minosoft:distant", 1L, TerrainDomain.DISTANT),
                declaredViews = setOf("minosoft:main"),
                declaredMaterialPasses = setOf("minosoft:opaque"),
                renderGraphGeneration = 1L,
            )
        }

        assertEquals(1, closed.count { it == "aliased" })
    }

    private fun candidate(prefix: String, closed: MutableList<String>) = TerrainPipelineCandidate.create(
        mode = TerrainRuntimeMode.UNIFIED_COMPARE,
        nearProvider = Provider("$prefix-near", TerrainDomain.NEAR, closed),
        distantProvider = Provider("$prefix-distant", TerrainDomain.DISTANT, closed),
        materialTable = Material("$prefix-material", closed),
        shaderPipeline = Shader("$prefix-shader", closed),
        nearLayout = TerrainPhysicalLayout("minosoft:near", 1L, TerrainDomain.NEAR),
        distantLayout = TerrainPhysicalLayout("minosoft:distant", 1L, TerrainDomain.DISTANT),
        declaredViews = setOf("minosoft:main", "minosoft:shadow"),
        declaredMaterialPasses = setOf("minosoft:opaque", "minosoft:distant-water"),
        renderGraphGeneration = 1L,
    )

    private class Provider(
        id: String,
        domain: TerrainDomain,
        private val closed: MutableList<String>,
        supportedViews: Set<String> = setOf("minosoft:main", "minosoft:shadow"),
        physicalLayoutIds: Set<String> = setOf(
            if (domain == TerrainDomain.NEAR) "minosoft:near" else "minosoft:distant",
        ),
    ) : NearTerrainProvider, DistantTerrainProvider {
        private val name = id
        override val generation = 1L
        override val descriptor = TerrainInteropDescriptor(
            providerId = id,
            domains = setOf(domain),
            capabilities = emptySet(),
            materials = setOf(TerrainMaterialClass.OPAQUE),
            semanticVertexLayoutId = "minosoft:terrain",
            physicalLayoutIds = physicalLayoutIds,
            supportedViews = supportedViews,
            uploadCapabilities = setOf(TerrainUploadCapability.BUFFER_UPDATE),
            shaderInputs = setOf("position"),
            lightingSemantics = TerrainLightingSemantics.BLOCK_AND_SKY,
            tintSemantics = TerrainTintSemantics.BIOME_INPUT,
        )

        override fun close() {
            assertFalse(closed.contains(name))
            closed += name
        }
    }

    private class Material(
        private val name: String,
        private val closed: MutableList<String>,
    ) : TerrainMaterialTableGeneration {
        override val generation = 1L
        override fun close() {
            closed += name
        }
    }

    private class Shader(
        private val name: String,
        private val closed: MutableList<String>,
    ) : TerrainShaderPipelineGeneration {
        override val generation = 1L
        override fun close() {
            closed += name
        }
    }
}
