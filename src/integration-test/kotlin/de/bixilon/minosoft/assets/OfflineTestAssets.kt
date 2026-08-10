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

import de.bixilon.minosoft.assets.directory.DirectoryAssetsManager
import de.bixilon.minosoft.assets.session.SessionAssetsManager
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.nio.file.Files
import java.nio.file.Path

object OfflineTestAssets {
    const val CONTENT_FORGE_ROOT_ENV = "MINOSOFT_CONTENT_FORGE_ROOT"

    fun create(session: PlaySession): SessionAssetsManager {
        val assets = session.profiles.resources.assets
        assets.disableIndexAssets = true
        assets.disableJarAssets = true

        val priorityAssets = buildList {
            add(DirectoryAssetsManager(standInRoot()))
            contentForgeRoot()?.let { add(DirectoryAssetsManager(it)) }
            add(OfflineFallbackAssetsManager())
        }
        return AssetsLoader.create(session.profiles.resources, session.version, priorityAssets = priorityAssets)
    }

    fun contentForgeRoot(): Path? {
        val configured = System.getenv(CONTENT_FORGE_ROOT_ENV)?.trim().orEmpty()
        if (configured.isEmpty()) return null
        return requireContentRoot(Path.of(configured).toAbsolutePath().normalize())
    }

    private fun requireContentRoot(root: Path): Path {
        require(Files.isDirectory(root.resolve("assets"))) {
            "$CONTENT_FORGE_ROOT_ENV must contain an assets directory: $root"
        }
        return root
    }

    private fun standInRoot(): Path {
        val marker = requireNotNull(javaClass.classLoader.getResource("offline_assets/pack.mcmeta")) {
            "Offline integration asset stand-in is missing."
        }
        return Path.of(marker.toURI()).parent
    }
}
