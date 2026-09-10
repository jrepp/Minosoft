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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExistingSetupDiscoveryTest {
    @TempDir
    Path temporary;

    @Test
    void listsCompleteExistingSetupsInStableOrder() throws Exception {
        setup("zeta", "fabric-stack");
        setup("alpha", "sodium");
        Files.createDirectories(temporary.resolve("trajectories/incomplete/sodium/home"));
        Files.createDirectories(temporary.resolve("trajectories/.hidden/sodium/home"));
        Files.createDirectories(temporary.resolve("trajectories/.hidden/sodium/profiles"));

        List<Play.ExistingSetup> setups = Play.discoverExistingSetups(temporary);

        assertEquals(
            List.of("alpha/sodium", "zeta/fabric-stack"),
            setups.stream().map(setup -> setup.trajectory() + "/" + setup.modpack()).toList()
        );
    }

    @Test
    void missingStoreProducesAnEmptyList() throws Exception {
        assertEquals(List.of(), Play.discoverExistingSetups(temporary.resolve("missing")));
    }

    private void setup(String trajectory, String modpack) throws Exception {
        Path setup = temporary.resolve("trajectories").resolve(trajectory).resolve(modpack);
        Files.createDirectories(setup.resolve("home"));
        Files.createDirectories(setup.resolve("profiles"));
    }
}
