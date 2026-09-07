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

package de.bixilon.minosoft.assets

import de.bixilon.kutil.latch.SimpleLatch
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.assets.audit.ContentAssetAudit
import de.bixilon.minosoft.data.registries.blocks.types.Block
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.models.ModelTestUtil.prepareDummyTextures
import de.bixilon.minosoft.gui.rendering.models.block.BlockModelPrototype
import de.bixilon.minosoft.gui.rendering.models.block.state.DirectBlockModel
import de.bixilon.minosoft.gui.rendering.models.block.state.apply.BlockStateApply
import de.bixilon.minosoft.gui.rendering.models.block.state.builder.BuilderBlockModel
import de.bixilon.minosoft.gui.rendering.models.block.state.variant.PropertyVariantBlockModel
import de.bixilon.minosoft.gui.rendering.models.block.state.variant.SingleVariantBlockModel
import de.bixilon.minosoft.gui.rendering.models.loader.ModelLoader
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureManager
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.SkipException
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path

@Test(groups = ["assets", "external-assets"])
class ContentForgeBlockLoadIT {

    fun `all content forge blockstates load and bake headlessly`() {
        val root = OfflineTestAssets.contentForgeRoot()
            ?: throw SkipException("Set ${OfflineTestAssets.CONTENT_FORGE_ROOT_ENV} to validate external authored block models.")
        val blockstates = blockstates(root)
        assertEquals(blockstates.size, EXPECTED_BLOCKSTATES, "Unexpected content-forge blockstate surface.")

        assertTrue(IT.VERSION.flattened, "Integration bootstrap did not load the supported version catalog.")
        val session = createSession(version = MINECRAFT_VERSION)
        val assets = OfflineTestAssets.createContentForge(session, root)
        session::assets.forceSet(assets)
        assets.load(SimpleLatch(0))

        val context = RenderContext::class.java.allocate()
        context::session.forceSet(session)
        context::contentAssetAudit.forceSet(ContentAssetAudit())
        context::textures.forceSet(DummyTextureManager(context))
        val loader = ModelLoader(context).block
        val failures = mutableListOf<String>()
        val loaded = mutableListOf<LoadedBlockstate>()

        try {
            for (target in blockstates) {
                try {
                    val block = session.registries.block[target.block]
                        ?: throw IllegalArgumentException("block is absent from the $MINECRAFT_VERSION registry")
                    val prototype = loader.loadState(block, target.file)
                        ?: throw IllegalArgumentException("BlockLoader returned no model prototype")
                    loaded += LoadedBlockstate(target, block, prototype)
                } catch (error: Exception) {
                    failures += "${target.block} -> load: ${reason(error)}"
                }
            }

            context.textures.prepareDummyTextures()
            for ((target, block, prototype) in loaded) {
                for ((index, apply) in allApplies(prototype.model, block).withIndex()) {
                    try {
                        if (apply.bake() == null && target.block !in NON_RENDERED_BLOCKS) {
                            throw IllegalArgumentException("referenced model apply $index baked to null")
                        }
                    } catch (error: Exception) {
                        failures += "${target.block} -> bake apply $index: ${reason(error)}"
                    }
                }
                for (state in block.states) {
                    try {
                        val apply = prototype.model.choose(state.properties)
                            ?: throw IllegalArgumentException("no model matches properties ${state.properties}")
                        if (apply.bake() == null && target.block !in NON_RENDERED_BLOCKS) {
                            throw IllegalArgumentException("model baked to null for properties ${state.properties}")
                        }
                    } catch (error: Exception) {
                        failures += "${target.block} -> bake ${state.properties}: ${reason(error)}"
                    }
                }
            }

            val missing = context.contentAssetAudit.snapshot().entries.filter {
                it.kind == ContentAssetAudit.Kind.BLOCKSTATE || it.kind == ContentAssetAudit.Kind.MODEL
            }
            failures += missing.map { entry ->
                val consumers = entry.consumers.takeIf(List<String>::isNotEmpty)?.joinToString(prefix = " (used by ", postfix = ")").orEmpty()
                "${entry.resource} -> missing ${entry.kind.wireName}$consumers"
            }

            assertEquals(loaded.size, blockstates.size, failureSummary(blockstates.size, failures))
            assertTrue(failures.isEmpty(), failureSummary(blockstates.size, failures))
            println("ContentForgeBlockLoadIT: loaded and baked ${blockstates.size} blockstates with 0 failures.")
        } finally {
            assets.unload()
        }
    }

    private fun blockstates(root: Path): List<BlockstateTarget> {
        val assetsRoot = root.resolve("assets")
        val targets = mutableListOf<BlockstateTarget>()
        Files.walk(assetsRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.forEach { file ->
                val relative = assetsRoot.relativize(file)
                if (relative.nameCount < 3 || relative.getName(1).toString() != "blockstates") return@forEach
                require(!Files.isSymbolicLink(file)) { "Content blockstate is a symbolic link: $file" }
                require(targets.size < MAX_BLOCKSTATES) { "Content output exceeds $MAX_BLOCKSTATES blockstates." }
                val namespace = relative.getName(0).toString()
                val name = relative.subpath(2, relative.nameCount).toString().replace('\\', '/').removeSuffix(".json")
                val block = ResourceLocation(namespace, name)
                targets += BlockstateTarget(block, ResourceLocation(namespace, "blockstates/$name.json"))
            }
        }
        return targets.sortedBy { it.block.toString() }
    }

    private fun allApplies(model: DirectBlockModel, block: Block): List<BlockStateApply> {
        val applies = when (model) {
            is BuilderBlockModel -> model.parts.map { it.apply }
            is PropertyVariantBlockModel -> model.variants.values.toList()
            is SingleVariantBlockModel -> listOf(model.apply)
            else -> block.states.mapNotNull { model.choose(it.properties) }
        }
        return applies.distinct()
    }

    private fun reason(error: Exception): String {
        val message = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return if (message.isEmpty()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    private fun failureSummary(total: Int, failures: List<String>): String {
        return "${failures.size} failures while loading/baking $total blockstates:\n${failures.joinToString("\n")}"
    }

    private data class BlockstateTarget(val block: ResourceLocation, val file: ResourceLocation)
    private data class LoadedBlockstate(val target: BlockstateTarget, val block: Block, val prototype: BlockModelPrototype)

    private companion object {
        const val MINECRAFT_VERSION = "1.20.4"
        const val EXPECTED_BLOCKSTATES = 966
        const val MAX_BLOCKSTATES = 10_000
        val NON_RENDERED_BLOCKS = setOf(ResourceLocation("minecraft", "air"))
    }
}
