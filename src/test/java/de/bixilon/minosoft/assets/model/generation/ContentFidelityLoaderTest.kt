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

package de.bixilon.minosoft.assets.model.generation

import de.bixilon.minosoft.assets.directory.DirectoryAssetsManager
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentParsers
import de.bixilon.minosoft.assets.model.skeletal.cem.CEM_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.skeletal.gecko.GECKO_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureRuleParsers
import de.bixilon.minosoft.assets.model.texture.entity.OptifineEntityTexturePropertiesParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentFidelityLoaderTest {

    @Test
    fun `discovers CEM Gecko animations and ETF rules from one asset view`() {
        val root = Files.createTempDirectory("content-fidelity-loader")
        val assetsRoot = root.resolve("assets/test")
        val cem = assetsRoot.resolve("optifine/cem").createDirectories()
        val geo = assetsRoot.resolve("geo").createDirectories()
        val animations = assetsRoot.resolve("animations").createDirectories()
        val random = assetsRoot.resolve("optifine/random/entity/cow").createDirectories()
        val entityTextures = assetsRoot.resolve("textures/entity/cow").createDirectories()
        val functions = root.resolve("data/test/functions").createDirectories()
        val functionTags = root.resolve("data/minecraft/tags/functions").createDirectories()
        cem.resolve("part.jpm").writeText("""{"id":"body","boxes":[{"coordinates":[0,0,0,1,1,1],"textureOffset":[0,0]}]}""")
        cem.resolve("cow.jem").writeText("""{"models":[{"model":"part"}]}""")
        geo.resolve("bird.geo.json").writeText(
            """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.bird","texture_width":16,"texture_height":16},"bones":[{"name":"root"}]}]}""",
        )
        animations.resolve("bird.animation.json").writeText(
            """{"format_version":"1.8.0","animations":{"animation.bird.idle":{"loop":true,"bones":{"root":{"rotation":[0,0,0]}}}}}""",
        )
        random.resolve("cow.properties").writeText("skins.1=2 3")
        random.resolve("cow2.png").writeText("")
        random.resolve("cow2_e.png").writeText("")
        random.resolve("cow3.png").writeText("")
        entityTextures.resolve("cow.png").writeText("")
        functions.resolve("load.mcfunction").writeText("say loaded")
        functionTags.resolve("load.json").writeText("""{"values":["test:load"]}""")

        val manager = DirectoryAssetsManager(root)
        val dataPacks = DirectoryAssetsManager(root, prefix = "data")
        val registrations = listOf(
            SkeletalContentParsers.register(CEM_PARSER_REGISTRATION),
            SkeletalContentParsers.register(GECKO_PARSER_REGISTRATION),
            EntityTextureRuleParsers.register("test", OptifineEntityTexturePropertiesParser),
        )
        try {
            manager.load()
            dataPacks.load()
            val prepared = ContentFidelityLoader(manager, dataPacks).prepare()
            val snapshot = prepared.value

            assertEquals(2, snapshot.skeletal.size)
            assertEquals(1, snapshot.animations.size)
            assertEquals(1, snapshot.entityTextureRules.size)
            assertEquals(
                setOf(1, 2, 3),
                snapshot.entityTextureCatalog.entries.values.single().materials.keys,
            )
            assertEquals(3, snapshot.entityTextureMaterials.size)
            assertEquals(
                setOf("animation.bird.idle"),
                snapshot.skeletal.entries.single { it.key.path.endsWith("bird.geo.json") }.value.single().animations.keys,
            )
            assertEquals(listOf("say loaded"), snapshot.dataPackFunctions.resolve("#minecraft:load").single().commands)
            assertTrue(manager.list("optifine/").all { it.path.startsWith("optifine/") })
            prepared.cleanup.close()
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
            manager.unload()
            dataPacks.unload()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `one malformed candidate rejects the whole snapshot`() {
        val root = Files.createTempDirectory("content-fidelity-loader-bad")
        val geo = root.resolve("assets/test/geo").createDirectories()
        geo.resolve("broken.geo.json").writeText("{}")
        val manager = DirectoryAssetsManager(root)
        val registration = SkeletalContentParsers.register(GECKO_PARSER_REGISTRATION)
        try {
            manager.load()
            assertFailsWith<IllegalArgumentException> { ContentFidelityLoader(manager).prepare() }
        } finally {
            registration.close()
            manager.unload()
            root.toFile().deleteRecursively()
        }
    }
}
