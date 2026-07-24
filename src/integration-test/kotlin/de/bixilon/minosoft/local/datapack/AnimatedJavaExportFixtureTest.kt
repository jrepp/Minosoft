/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.local.datapack

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.assets.datapack.DataPackFunctionLibrary
import de.bixilon.minosoft.assets.datapack.DataPackFunctionRuntime
import de.bixilon.minosoft.assets.datapack.LocalDataPackCommandAuthority
import de.bixilon.minosoft.assets.directory.DirectoryAssetsManager
import de.bixilon.minosoft.data.entities.entities.display.ItemDisplayEntity
import de.bixilon.minosoft.gui.rendering.RenderingOptions
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil.createSession
import de.bixilon.minosoft.test.IT
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.nio.file.Paths

class AnimatedJavaExportFixtureTest {

    @Test
    fun `runs pinned 1_10_2 summon tick and removal lifecycle`() {
        val previous = RenderingOptions.disabled
        RenderingOptions.disabled = true
        val fixture = Paths.get(
            requireNotNull(javaClass.classLoader.getResource("content_fidelity/animated_java-1.10.2/fixture.json")).toURI(),
        ).parent
        val data = DirectoryAssetsManager(fixture, prefix = "data")
        try {
            IT.VERSION
            data.load()
            val session = createSession(version = "1.20.4")
            val origin = Vec3d(8.0, 12.0, 4.0)
            val factory = LocalDisplayEntityFactory(session)
            val entities = LocalDataPackEntityAccess(session, factory) { origin }
            val authority = LocalDataPackCommandAuthority(
                origin = { origin },
                spawn = { type, nbt, position -> factory.summon(type, nbt, position) },
                entities = entities,
            )
            val runtime = DataPackFunctionRuntime(DataPackFunctionLibrary.load(data), authority)

            runtime.load()
            runtime.execute("demo:summon")
            val root = entities.select(
                "@e[type=item_display,tag=demo.root,limit=1,distance=..0.01]",
                de.bixilon.minosoft.assets.datapack.DataPackCommandContext(
                    de.bixilon.minosoft.data.registries.identified.ResourceLocation.of("demo:test"),
                    0,
                    0,
                    position = origin,
                ),
            ).single() as ItemDisplayEntity
            assertFalse("aj.new" in root.commandTags)
            assertEquals(authority.score(root.uuid.toString(), "aj.id"), 1)

            runtime.tick()
            val node = root.attachment.passengers.single() as ItemDisplayEntity
            assertEquals(authority.score(root.uuid.toString(), "demo.frame"), 1)
            assertEquals(node.stack!!.nbt.nbt["CustomModelData"], 27)
            assertEquals(node.interpolationStartDeltaTicks, -1)

            assertEquals(runtime.execute("demo:remove"), 2)
            assertTrue(entities.select(
                "@e[tag=demo.entity]",
                de.bixilon.minosoft.assets.datapack.DataPackCommandContext(
                    de.bixilon.minosoft.data.registries.identified.ResourceLocation.of("demo:test"),
                    0,
                    runtime.tick,
                    position = origin,
                ),
            ).isEmpty())
        } finally {
            data.unload()
            RenderingOptions.disabled = previous
        }
    }
}
