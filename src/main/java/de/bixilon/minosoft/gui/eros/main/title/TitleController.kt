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

package de.bixilon.minosoft.gui.eros.main.title

import de.bixilon.kutil.shutdown.AbstractShutdownReason
import de.bixilon.kutil.shutdown.ShutdownManager
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.eros.Eros
import de.bixilon.minosoft.gui.eros.controller.EmbeddedJavaFXController
import de.bixilon.minosoft.gui.eros.main.ErosMainActivities
import de.bixilon.minosoft.gui.eros.util.JavaFXUtil.ctext
import de.bixilon.minosoft.terminal.RunConfiguration
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.Pane

class TitleController : EmbeddedJavaFXController<Pane>() {
    @FXML private lateinit var splashFX: Label
    @FXML private lateinit var multiplayerFX: Button
    @FXML private lateinit var profilesFX: Button
    @FXML private lateinit var modsFX: Button
    @FXML private lateinit var quitFX: Button
    @FXML private lateinit var versionFX: Label

    override fun init() {
        splashFX.ctext = minosoft("main.title.splash")
        multiplayerFX.ctext = minosoft("main.title.multiplayer")
        profilesFX.ctext = minosoft("main.title.profiles")
        modsFX.ctext = minosoft("main.title.mods")
        quitFX.ctext = minosoft("main.title.quit")
        versionFX.text = RunConfiguration.APPLICATION_NAME
    }

    @FXML
    fun openMultiplayer() {
        Eros.mainErosController.showActivity(ErosMainActivities.PLAY)
    }

    @FXML
    fun openProfiles() {
        Eros.mainErosController.showActivity(ErosMainActivities.PROFILES)
    }

    @FXML
    fun openMods() {
        Eros.mainErosController.showActivity(ErosMainActivities.MODS)
    }

    @FXML
    fun quit() {
        Eros.mainErosController.stage.close()
        ShutdownManager.shutdown(reason = AbstractShutdownReason.DEFAULT)
    }

    companion object {
        val LAYOUT = minosoft("eros/main/title/title.fxml")
    }
}
