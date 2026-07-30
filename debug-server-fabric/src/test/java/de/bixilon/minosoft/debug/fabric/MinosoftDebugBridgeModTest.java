/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.debug.fabric;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugJson;
import de.bixilon.minosoft.debug.DebugOperationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinosoftDebugBridgeModTest {
    @Test
    void teleportInputRequiresAnExactDimensionAndBoundedFiniteCoordinates() {
        ObjectNode body = DebugJson.MAPPER.createObjectNode()
            .put("dimension", "minecraft:the_nether");
        ObjectNode position = body.putObject("position")
            .put("x", -12.5)
            .put("y", 80.0)
            .put("z", 29_999_999.5);

        assertEquals("minecraft:the_nether", MinosoftDebugBridgeMod.requiredText(body, "dimension"));
        assertEquals(-12.5, MinosoftDebugBridgeMod.finiteCoordinate(position, "x"));
        assertEquals(80.0, MinosoftDebugBridgeMod.finiteCoordinate(position, "y"));
        assertEquals(29_999_999.5, MinosoftDebugBridgeMod.finiteCoordinate(position, "z"));

        body.put("dimension", " ");
        assertThrows(
            DebugOperationException.class,
            () -> MinosoftDebugBridgeMod.requiredText(body, "dimension")
        );
        position.put("x", 30_000_000.5);
        assertThrows(
            DebugOperationException.class,
            () -> MinosoftDebugBridgeMod.finiteCoordinate(position, "x")
        );
    }

    @Test
    void teleportAnglesAreOptionalButMustBeFiniteNumbers() {
        ObjectNode body = DebugJson.MAPPER.createObjectNode();
        assertEquals(17.0f, MinosoftDebugBridgeMod.finiteAngle(body, "yaw", 17.0f));

        body.put("yaw", -45.25f);
        assertEquals(-45.25f, MinosoftDebugBridgeMod.finiteAngle(body, "yaw", 17.0f));

        body.put("yaw", "north");
        assertThrows(
            DebugOperationException.class,
            () -> MinosoftDebugBridgeMod.finiteAngle(body, "yaw", 17.0f)
        );
    }

}
