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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import de.bixilon.minosoft.debug.DebugJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPreviewTest {
    @Test
    void itemPreviewMountIsExplicitIdempotentAndRemovedForOrdinaryLaunches(@TempDir Path root) throws Exception {
        Path pack = root.resolve("content-preview"); Files.createDirectories(pack); Files.writeString(pack.resolve("pack.mcmeta"), "{}");
        ArrayNode existing = DebugJson.MAPPER.createArrayNode(); existing.addObject().put("type", "DIRECTORY").put("path", root.resolve("user-pack").toString());
        ArrayNode enabled = Play.configurePreviewDataPack(existing, pack, true);
        assertEquals(2, enabled.size());
        assertEquals(enabled, Play.configurePreviewDataPack(enabled, pack, true));
        assertEquals(existing, Play.configurePreviewDataPack(enabled, pack, false));
        assertThrows(RuntimeException.class, () -> Play.configurePreviewDataPack(existing, root.resolve("missing"), true));
    }

    @Test
    void aMissingItemFunctionCannotProduceSuccessfulPreviewEvidence() {
        assertThrows(RuntimeException.class, () -> Play.validatePreviewPlacement("content.execute-local", DebugJson.MAPPER.createObjectNode().put("executed", 0)));
        Play.validatePreviewPlacement("content.execute-local", DebugJson.MAPPER.createObjectNode().put("executed", 1));
        Play.validatePreviewPlacement("content.place-blocks", DebugJson.MAPPER.createObjectNode());
    }
    @Test
    void resourceIdsPreferBlockstatesOverEarlierItemQueueEntries() throws Exception {
        JsonNode queue = DebugJson.MAPPER.readTree("""
            {
              "entries": [
                {"target":"assets/minecraft/models/item/oak_slab.json","resource":"minecraft:oak_slab"},
                {"target":"assets/minecraft/textures/block/oak_slab.png","resource":"minecraft:oak_slab"},
                {"target":"assets/minecraft/blockstates/oak_slab.json","resource":"minecraft:oak_slab"}
              ]
            }
            """);
        Play.PreviewAsset resolved = Play.resolvePreviewAsset("minecraft:oak_slab", queue);
        assertEquals("block", resolved.kind());
        assertEquals("assets/minecraft/blockstates/oak_slab.json", resolved.target());
    }

    @Test
    void statefulFamiliesRemainBlocksWhenOnlyTheirItemGapIsQueued() throws Exception {
        JsonNode queue = DebugJson.MAPPER.readTree("""
            {
              "entries": [
                {"target":"assets/minecraft/models/item/oak_slab.json","resource":"minecraft:models/item/oak_slab.json"},
                {"target":"assets/minecraft/models/item/diamond.json","resource":"minecraft:models/item/diamond.json"}
              ]
            }
            """);
        Play.PreviewAsset slab = Play.resolvePreviewAsset("minecraft:oak_slab", queue);
        assertEquals("block", slab.kind());
        assertEquals("assets/minecraft/blockstates/oak_slab.json", slab.target());

        Play.PreviewAsset diamond = Play.resolvePreviewAsset("minecraft:diamond", queue);
        assertEquals("item", diamond.kind());
    }

    @Test
    void exactTargetsPreserveNestedResourcePaths() throws Exception {
        String target = "assets/example/models/item/tools/hammer.json";
        JsonNode queue = DebugJson.MAPPER.readTree("{\"entries\":[{\"target\":\"" + target + "\"}]}");
        Play.PreviewAsset resolved = Play.resolvePreviewAsset(target, queue);
        assertEquals("example:tools/hammer", resolved.id());
        assertEquals("item", resolved.kind());
    }

    @Test
    void equalPathsInAnotherNamespaceDoNotSelectTheWrongKind() throws Exception {
        JsonNode queue = DebugJson.MAPPER.readTree("""
            {"entries":[{"target":"assets/example/models/item/stone.json"}]}
            """);
        Play.PreviewAsset resolved = Play.resolvePreviewAsset("minecraft:stone", queue);
        assertEquals("block", resolved.kind());
        assertEquals("", resolved.target());
    }

    @Test
    void cameraUsesAThreeQuarterViewAimedAtTheTarget() {
        ObjectNode camera = Play.previewCameraJson(0.5, 20.5, 0.5, 2.75);
        assertEquals(-45.0, camera.path("yaw").asDouble(), 0.0001);
        assertTrue(camera.path("pitch").asDouble() > 0.0);
        assertEquals(camera.path("x").asDouble(), camera.path("z").asDouble(), 0.0001);
        assertTrue(camera.path("y").asDouble() > 20.5);
    }

    @Test
    void statePageControlsAreBoundedAndOneBased() {
        assertEquals("all", Play.parsePreviewStatePage("ALL"));
        assertEquals("1", Play.parsePreviewStatePage("1"));
        assertEquals("2147483647", Play.parsePreviewStatePage("2147483647"));
        assertEquals(1, Play.parsePreviewStatesPerPage("1"));
        assertEquals(64, Play.parsePreviewStatesPerPage("64"));
        assertThrows(RuntimeException.class, () -> Play.parsePreviewStatePage("0"));
        assertThrows(RuntimeException.class, () -> Play.parsePreviewStatesPerPage("0"));
        assertThrows(RuntimeException.class, () -> Play.parsePreviewStatesPerPage("65"));
    }

    @Test
    void settleControlsAreBounded() {
        assertEquals(30_000L, Play.parsePreviewSettleMillis("30000"));
        assertEquals(0L, Play.parsePreviewSettleFrames("0"));
        assertEquals(600L, Play.parsePreviewSettleFrames("600"));
        assertThrows(RuntimeException.class, () -> Play.parsePreviewSettleMillis("0"));
        assertThrows(RuntimeException.class, () -> Play.parsePreviewSettleMillis("30001"));
        assertThrows(RuntimeException.class, () -> Play.parsePreviewSettleFrames("601"));
    }
}
