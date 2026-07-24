/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
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

import de.bixilon.minosoft.assets.minecraft.JarAssetsManager
import de.bixilon.minosoft.assets.minecraft.MinecraftPackFormat.packFormat
import de.bixilon.minosoft.assets.minecraft.index.IndexAssetsManager
import de.bixilon.minosoft.assets.properties.manager.AssetsManagerProperties
import de.bixilon.minosoft.assets.properties.manager.pack.PackProperties
import de.bixilon.minosoft.assets.properties.version.AssetsVersionProperties
import de.bixilon.minosoft.assets.properties.version.AssetsVersionProperty
import de.bixilon.minosoft.assets.session.SessionAssetsManager
import de.bixilon.minosoft.assets.session.SessionDataPackManager
import de.bixilon.minosoft.config.profile.profiles.resources.ResourcesProfile
import de.bixilon.minosoft.protocol.versions.Version
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

object AssetsLoader {

    private fun ResourcesProfile.createPackProperties(version: Version): AssetsManagerProperties {
        var packFormat = assets.packFormat
        if (packFormat < 0) {
            packFormat = version.packFormat
        }

        return AssetsManagerProperties(PackProperties(packFormat))
    }

    private fun SessionAssetsManager.addResourcePacks(profile: ResourcesProfile) {
        for (pack in profile.assets.resourcePacks.reversed()) {
            Log.log(LogMessageType.ASSETS, LogLevels.INFO) { "Mounting ${pack.type.name.lowercase()} resource pack: ${pack.path}" }
            val manager = pack.type.create(pack)
            this += manager
        }
    }

    fun createDataPacks(profile: ResourcesProfile): SessionDataPackManager {
        val manager = SessionDataPackManager()
        for (pack in profile.assets.dataPacks.reversed()) {
            Log.log(LogMessageType.ASSETS, LogLevels.INFO) { "Mounting ${pack.type.name.lowercase()} data pack: ${pack.path}" }
            manager += pack.type.create(pack, prefix = "data")
        }
        return manager
    }

    fun create(profile: ResourcesProfile, version: Version, property: AssetsVersionProperty = AssetsVersionProperties[version] ?: throw IllegalAccessException("$version has no assets!")): SessionAssetsManager {
        val properties = profile.createPackProperties(version)

        val manager = SessionAssetsManager(properties)

        manager += IntegratedAssets.OVERRIDE

        manager.addResourcePacks(profile)

        for (format in properties.pack.format downTo 1) {
            manager += IntegratedAssets.VERSIONED.getOrNull(format - 1) ?: continue
        }

        if (!profile.assets.disableIndexAssets) {
            manager += IndexAssetsManager(profile, property.indexHash, profile.assets.indexAssetsTypes.toSet(), version.packFormat)
        }
        if (!profile.assets.disableJarAssets) {
            manager += JarAssetsManager(property.jarAssetsHash, property.clientJarHash, profile, version, property.jarAssetsTarBytes ?: JarAssetsManager.DEFAULT_TAR_BYTES)
        }
        for (provider in ExternalAssetProviders.snapshot()) {
            manager += provider.create()
        }
        manager += IntegratedAssets.DEFAULT

        return manager
    }
}
