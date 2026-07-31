/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FabricServerLauncherVersionTest {
    @Test
    void java25CompatibleLauncherCoordinatesStayTogether() {
        assertEquals(
            "fabric-server-mc.1.20.4-loader.0.19.3-launcher.1.1.2.jar",
            Play.fabricServerLauncherFileName()
        );
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/loader/1.20.4/0.19.3/1.1.2/server/jar",
            Play.fabricServerLauncherUri().toString()
        );
    }
}
