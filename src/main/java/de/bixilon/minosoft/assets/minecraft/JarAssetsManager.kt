/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.minecraft

import com.fasterxml.jackson.databind.JsonNode
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.assets.AssetsManager
import de.bixilon.minosoft.assets.InvalidAssetException
import de.bixilon.minosoft.assets.minecraft.MinecraftPackFormat.packFormat
import de.bixilon.minosoft.assets.properties.manager.AssetsManagerProperties
import de.bixilon.minosoft.assets.properties.manager.pack.PackProperties
import de.bixilon.minosoft.assets.source.LocalAssetSource
import de.bixilon.minosoft.assets.util.FileAssetsTypes
import de.bixilon.minosoft.assets.util.FileAssetsUtil
import de.bixilon.minosoft.assets.util.InputStreamUtil.readTarArchive
import de.bixilon.minosoft.assets.util.InputStreamUtil.readZipArchive
import de.bixilon.minosoft.assets.util.PathUtil
import de.bixilon.minosoft.config.profile.profiles.resources.ResourcesProfile
import de.bixilon.minosoft.data.registries.identified.Namespaces
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.versions.Version
import de.bixilon.minosoft.util.KUtil.toResourceLocation
import de.bixilon.minosoft.util.json.Jackson
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarHeader
import org.kamranzafar.jtar.TarOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream


/**
 * Local assets-manager that imports compatible assets from an existing local
 * client archive and stores the filtered result in Minosoft's local cache.
 */
class JarAssetsManager(
    val jarAssetsHash: String,
    val clientJarHash: String,
    val profile: ResourcesProfile,
    val version: Version,
    val expectedTarBytes: Int = DEFAULT_TAR_BYTES,
) : MinecraftAssetsManager {
    override var loaded: Boolean = false
        private set
    private var assets: MutableMap<String, ByteArray> = mutableMapOf()
    override val properties = AssetsManagerProperties(PackProperties(format = version.packFormat))


    private fun tryLoadAssets(): Boolean {
        val assets = FileAssetsUtil.readOrNull(jarAssetsHash, FileAssetsTypes.GAME, verify = profile.verify)?.let { ByteArrayInputStream(it).readTarArchive() } ?: return false

        for ((path, data) in assets) {
            this.assets[path.removePrefix("assets/" + Namespaces.MINECRAFT + "/")] = data
        }
        loaded = true

        return true
    }

    private fun readClientJar(): Map<String, ByteArray> {
        val local = FileAssetsUtil.readOrNull(clientJarHash, FileAssetsTypes.GAME, verify = profile.verify)
        return ByteArrayInputStream(LocalAssetSource.require("client asset archive", clientJarHash, local)).readZipArchive()
    }

    override fun load(latch: AbstractLatch?) {
        check(!loaded) { "Already loaded!" }

        if (tryLoadAssets()) {
            return
        }

        val clientJar = readClientJar()

        val assets: MutableMap<String, ByteArray> = mutableMapOf()

        val output = ByteArrayOutputStream(expectedTarBytes)
        val tar = TarOutputStream(output)

        for ((filename, data) in clientJar) {
            if (!filename.startsWith("assets/")) {
                continue
            }
            var cutFilename = filename.removePrefix("assets/")
            val splitFilename = cutFilename.split("/", limit = 2)
            if (splitFilename[0] != Namespaces.MINECRAFT) {
                continue
            }
            cutFilename = splitFilename.getOrNull(1) ?: continue

            var outData = data
            if (cutFilename.endsWith(".json")) {
                outData = minifyJson(data)
            }

            if (!isRequired(cutFilename)) {
                continue
            }

            assets[cutFilename] = outData
            tar.putNextEntry(TarEntry(TarHeader.createHeader(filename, outData.size.toLong(), 0L, false, 777).apply { generalize() }))
            tar.write(outData)
            tar.flush()
        }

        tar.close()
        val tarBytes = output.toByteArray()
        val savedHash = FileAssetsUtil.save(ByteArrayInputStream(tarBytes), FileAssetsTypes.GAME)
        PathUtil.getAssetsPath(hash = clientJarHash, type = FileAssetsTypes.GAME).toFile().delete()

        if (savedHash != jarAssetsHash) {
            throw InvalidAssetException("jar_assets".toResourceLocation(), savedHash, jarAssetsHash, tarBytes.size)
        }

        this.assets = assets
        loaded = true
    }

    override fun get(path: ResourceLocation): InputStream {
        check(path.namespace == Namespaces.MINECRAFT) { "Jar Assets manager does only provides minecraft assets!" }
        return ByteArrayInputStream(assets[path.path] ?: throw FileNotFoundException("Can not find asset: $path"))
    }

    override fun getOrNull(path: ResourceLocation): InputStream? {
        if (path.namespace != Namespaces.MINECRAFT) {
            return null
        }
        return ByteArrayInputStream(assets[path.path] ?: return null)
    }

    override fun list(pathPrefix: String): Set<ResourceLocation> = assets.keys.asSequence()
        .filter { it.startsWith(pathPrefix) }
        .mapTo(linkedSetOf()) { ResourceLocation(Namespaces.MINECRAFT, it) }

    override fun unload() {
        assets = mutableMapOf()
        loaded = false
    }

    override fun getAssetsManager(path: ResourceLocation): AssetsManager? {
        if (path.namespace != Namespaces.MINECRAFT) {
            return null
        }
        return if (path.path in assets) this else null
    }

    companion object {
        const val DEFAULT_TAR_BYTES = 10_000_000
        private val REQUIRED_FILE_PREFIXES = arrayOf(
            "blockstates/",
            "font/",
            "lang/",
            "models/",
            "particles/",
            "texts/",
            "textures/",
            "recipes/",
        )

        private fun isRequired(name: String): Boolean {
            for (prefix in REQUIRED_FILE_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return true
                }
            }
            return false
        }

        private fun minifyJson(data: ByteArray): ByteArray {
            val node = Jackson.MAPPER.readValue(data, JsonNode::class.java)
            return node.toString().toByteArray()
        }

        private fun TarHeader.generalize() {
            userId = 0
            groupId = 0
            modTime = 0L
            userName = StringBuffer("nobody")
            groupName = StringBuffer("nobody")
        }
    }
}
