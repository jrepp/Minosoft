/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets

import de.bixilon.minosoft.assets.datapack.DataPackFunctionLibrary
import de.bixilon.minosoft.config.profile.profiles.resources.ResourcesProfile
import de.bixilon.minosoft.config.profile.profiles.resources.assets.packs.ResourcePack
import de.bixilon.minosoft.config.profile.profiles.resources.assets.packs.ResourcePackType
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetsLoaderDataPackTest {

    @Test
    fun `mounts only the data namespace from configured data packs`() {
        val root = Files.createTempDirectory("session-data-pack")
        root.resolve("data/test/functions").createDirectories().resolve("load.mcfunction").writeText("say loaded")
        root.resolve("assets/test/models").createDirectories().resolve("ignored.json").writeText("{}")
        val profile = ResourcesProfile()
        @Suppress("UNCHECKED_CAST")
        (profile.assets.dataPacks as MutableList<ResourcePack>) += ResourcePack(ResourcePackType.DIRECTORY, root.toString())

        val manager = AssetsLoader.createDataPacks(profile)
        try {
            manager.load()
            assertEquals(listOf("say loaded"), DataPackFunctionLibrary.load(manager).functions.values.single().commands)
            assertEquals(emptySet(), manager.list("models/"))
        } finally {
            manager.unload()
            root.toFile().deleteRecursively()
        }
    }
}
