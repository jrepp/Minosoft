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

package de.bixilon.minosoft.modding.loader.fabric

import java.nio.file.Path

object FabricPackPreflightCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: FabricPackPreflightCli PACK_VIEW" }
        val report = FabricPackPreflight.inspect(Path.of(args.single()))
        println("pack=${report.pack.id} version=${report.pack.version} mods=${report.mods.size} activation=${report.activation.name.lowercase()}")
        for (probe in report.mods) {
            val capabilities = probe.adapter?.capabilities?.joinToString(",") { it.wireName }.orEmpty()
            val functionality = probe.functionality
            val summary = FabricFunctionalitySummary.of(functionality)
            println("mod=${probe.metadata.id} version=${probe.metadata.version} environment=${probe.metadata.environment} activation=${probe.activation.name.lowercase()} adapter=${probe.adapter?.id ?: "none"} capabilities=$capabilities mapped=${summary.mapped} partial=${summary.partial} unmapped=${summary.unmapped} nested=${probe.nestedMods.size} blockers=${probe.blockers.joinToString(",")} dependencyIssues=${probe.dependencyIssues.joinToString("|")}")
            for (entry in functionality) {
                println("function=${entry.id} status=${entry.status.wireName} area=${entry.area.wireName} menu=${entry.menu?.wireName ?: "none"} restart=${entry.restartRequired} mapping=${entry.hostMapping ?: "none"} detail=${entry.detail}")
            }
        }
    }
}
