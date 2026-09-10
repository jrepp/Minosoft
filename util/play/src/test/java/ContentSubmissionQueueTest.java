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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentSubmissionQueueTest {
    @TempDir
    Path temporary;

    @Test
    void classifiesDistinctTexturesAsGenerateAndGenericAsSelect() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-audit.json"), audit(
            "[{\"resource\":\"minecraft:blockstates/oak_door.json\",\"target\":\"assets/minecraft/blockstates/oak_door.json\"}]",
            "[{\"resource\":\"minecraft:models/block/stone.json\",\"target\":\"assets/minecraft/models/block/stone.json\"}]",
            "[{\"resource\":\"minecraft:textures/block/allay_spawn_egg\",\"target\":\"assets/minecraft/textures/item/allay_spawn_egg.png\"},"
                + "{\"resource\":\"minecraft:textures/block/white_bed\",\"target\":\"assets/minecraft/textures/item/white_bed.png\"},"
                + "{\"resource\":\"minecraft:textures/block/unknown_gadget\",\"target\":\"assets/minecraft/textures/block/unknown_gadget.png\"}]"
        ));

        ContentSubmissionQueue.Result result = ContentSubmissionQueue.prepare(audits, null, null, List.<ContentSubmissionQueue.SelectionSource>of());

        ContentSubmissionQueue.Entry spawnEgg = entry(result, "assets/minecraft/textures/item/allay_spawn_egg.png");
        assertEquals("generate", spawnEgg.disposition());
        assertEquals("spawn_egg", spawnEgg.detail());
        ContentSubmissionQueue.Entry bed = entry(result, "assets/minecraft/textures/item/white_bed.png");
        assertEquals("generate", bed.disposition());
        assertEquals("bed", bed.detail());
        ContentSubmissionQueue.Entry unknown = entry(result, "assets/minecraft/textures/block/unknown_gadget.png");
        assertEquals("select", unknown.disposition());
        assertEquals("generic_block", unknown.detail());
    }

    @Test
    void authoredComposedFilesResolveWhileGeneratedPlaceholdersStayQueued() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-audit.json"), audit(
            "[]",
            "[]",
            "[{\"resource\":\"minecraft:textures/block/allay_spawn_egg\",\"target\":\"assets/minecraft/textures/item/allay_spawn_egg.png\"},"
                + "{\"resource\":\"minecraft:textures/block/unknown_gadget\",\"target\":\"assets/minecraft/textures/item/unknown_gadget.png\"}]"
        ));
        Path generated = temporary.resolve("generated");
        Path composed = temporary.resolve("composed");
        Path generatedItem = Files.createDirectories(generated.resolve("assets/minecraft/textures/item"));
        Path composedItem = Files.createDirectories(composed.resolve("assets/minecraft/textures/item"));
        Files.writeString(generatedItem.resolve("allay_spawn_egg.png"), "placeholder");
        Files.writeString(generatedItem.resolve("unknown_gadget.png"), "placeholder");
        Files.writeString(composedItem.resolve("allay_spawn_egg.png"), "placeholder");
        Files.writeString(composedItem.resolve("unknown_gadget.png"), "REAL AUTHORS");

        ContentSubmissionQueue.Result result = ContentSubmissionQueue.prepare(audits, generated, composed, List.<ContentSubmissionQueue.SelectionSource>of());

        assertEquals("generate", entry(result, "assets/minecraft/textures/item/allay_spawn_egg.png").disposition());
        assertEquals("resolved", entry(result, "assets/minecraft/textures/item/unknown_gadget.png").disposition());
    }

    @Test
    void queueIsDeterministicAcrossSeparateStoreRoots() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-audit.json"), audit(
            "[{\"resource\":\"minecraft:blockstates/oak_door.json\",\"target\":\"assets/minecraft/blockstates/oak_door.json\"}]",
            "[{\"resource\":\"minecraft:models/block/stone.json\",\"target\":\"assets/minecraft/models/block/stone.json\"}]",
            "[{\"resource\":\"minecraft:textures/block/unknown_gadget\",\"target\":\"assets/minecraft/textures/block/unknown_gadget.png\"}]"
        ));

        ContentSubmissionQueue.Result first = ContentSubmissionQueue.prepare(audits, null, null, List.of(new ContentSubmissionQueue.SelectionSource("faithful-32x", java.nio.file.Path.of("faithful"))));
        ContentSubmissionQueue.Result second = ContentSubmissionQueue.prepare(audits, null, null, List.of(new ContentSubmissionQueue.SelectionSource("faithful-32x", java.nio.file.Path.of("faithful"))));

        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.entries(), second.entries());
    }

    @Test
    void cumulativeStagesUnionConsumersForTheSameTarget() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-first.json"), audit(
            "[]",
            "[{\"resource\":\"minecraft:models/block/stone.json\",\"target\":\"assets/minecraft/models/block/stone.json\",\"consumers\":[\"minecraft:first\"]}]",
            "[]"
        ));
        Files.writeString(audits.resolve("01-second.json"), audit(
            "[]",
            "[{\"resource\":\"minecraft:models/block/stone.json\",\"target\":\"assets/minecraft/models/block/stone.json\",\"consumers\":[\"minecraft:second\"]}]",
            "[]"
        ));

        ContentSubmissionQueue.Entry entry = ContentSubmissionQueue.prepare(
            audits, null, null, List.<ContentSubmissionQueue.SelectionSource>of()
        ).entries().getFirst();

        assertEquals(List.of("minecraft:first", "minecraft:second"), entry.consumers());
        assertFalse(entry.consumersTruncated());
    }

    @Test
    void distinctModelShapesGenerateWhileCubeAndEmptySelect() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-audit.json"), audit(
            "[]",
            "[{\"resource\":\"minecraft:models/block/oak_stairs.json\",\"target\":\"assets/minecraft/models/block/oak_stairs.json\"},"
                + "{\"resource\":\"minecraft:models/block/unknown_gadget.json\",\"target\":\"assets/minecraft/models/block/unknown_gadget.json\"}]",
            "[]"
        ));

        ContentSubmissionQueue.Result result = ContentSubmissionQueue.prepare(audits, null, null, List.<ContentSubmissionQueue.SelectionSource>of());

        assertEquals("generate", entry(result, "assets/minecraft/models/block/oak_stairs.json").disposition());
        assertEquals("stairs", entry(result, "assets/minecraft/models/block/oak_stairs.json").detail());
        assertEquals("select", entry(result, "assets/minecraft/models/block/unknown_gadget.json").disposition());
        assertEquals("cube", entry(result, "assets/minecraft/models/block/unknown_gadget.json").detail());
    }

    @Test
    void recordsExactAndNearCandidatesFromProbedSelectionSources() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-audit.json"), audit(
            "[]",
            "[]",
            "[{\"resource\":\"minecraft:textures/block/allium\",\"target\":\"assets/minecraft/textures/item/allium.png\"},"
                + "{\"resource\":\"minecraft:textures/block/unknown_gadget\",\"target\":\"assets/minecraft/textures/block/unknown_gadget.png\"}]"
        ));
        Path source = Files.createDirectories(temporary.resolve("pack/assets/minecraft/textures/block"));
        Files.writeString(source.resolve("allium.png"), "real-block-texture");

        ContentSubmissionQueue.Result result = ContentSubmissionQueue.prepare(
            audits, null, null,
            List.of(new ContentSubmissionQueue.SelectionSource("faithful-32x", temporary.resolve("pack")))
        );

        ContentSubmissionQueue.Entry allium = entry(result, "assets/minecraft/textures/item/allium.png");
        assertEquals("select", allium.disposition());
        assertEquals(1, allium.candidates().size());
        ContentSubmissionQueue.Candidate candidate = allium.candidates().getFirst();
        assertEquals("faithful-32x", candidate.source());
        assertEquals("assets/minecraft/textures/block/allium.png", candidate.target());
        assertEquals("near", candidate.match());

        ContentSubmissionQueue.Entry unknown = entry(result, "assets/minecraft/textures/block/unknown_gadget.png");
        assertTrue(unknown.candidates().isEmpty(), "No candidate should be recorded for an absent texture.");
    }

    @Test
    void topRanksByDispositionTierThenConsumersDeterministically() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-audit.json"), audit(
            "[]",
            "[{\"resource\":\"minecraft:models/block/first.json\",\"target\":\"assets/minecraft/models/block/first.json\"},"
                + "{\"resource\":\"minecraft:models/block/second.json\",\"target\":\"assets/minecraft/models/block/second.json\"}]",
            "[{\"resource\":\"minecraft:textures/block/generic\",\"target\":\"assets/minecraft/textures/block/generic.png\"}]"
        ));
        ContentSubmissionQueue.Result result = ContentSubmissionQueue.prepare(audits, null, null, List.<ContentSubmissionQueue.SelectionSource>of());

        List<ContentSubmissionQueue.RankedEntry> top = ContentSubmissionQueue.top(result.entries(), 10);

        assertTrue(!top.isEmpty(), "Authoring backlog must not be empty.");
        assertEquals(1, top.getFirst().rank());
        // Textures score 300 (select texture unavailable); cube models score 200; generate 100.
        assertEquals("select-texture-unavailable", top.getFirst().tier());
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).score() >= top.get(i).score(), "Ranks must be non-increasing by score.");
        }
        assertEquals(3, ContentSubmissionQueue.top(result.entries(), 10).size(), "Top must cap at the available actionable entries.");
    }

    @Test
    void csvIncludesEveryEntryWithPriorityColumns() throws Exception {
        Path audits = audits();
        Files.writeString(audits.resolve("00-audit.json"), audit(
            "[]",
            "[{\"resource\":\"minecraft:models/block/first.json\",\"target\":\"assets/minecraft/models/block/first.json\"}]",
            "[{\"resource\":\"minecraft:textures/block/generic\",\"target\":\"assets/minecraft/textures/block/generic.png\"}]"
        ));
        ContentSubmissionQueue.Result result = ContentSubmissionQueue.prepare(audits, null, null, List.<ContentSubmissionQueue.SelectionSource>of());

        String csv = ContentSubmissionQueue.toCsv(result);

        String[] lines = csv.trim().split("\n");
        assertTrue(lines[0].startsWith("kind,resource,target,disposition,tier,priority,detail,consumerCount,consumers,candidateSources,candidateTargets"));
        assertEquals(result.entries().size() + 1, lines.length, "Header plus one row per entry.");
        assertTrue(csv.contains("assets/minecraft/models/block/first.json"));
        assertTrue(csv.contains("assets/minecraft/textures/block/generic.png"));
        assertTrue(csv.contains("select-model-unavailable"));
        assertTrue(csv.contains("select-texture-unavailable"));
    }

    @Test
    void fullAuditCoverageRunsEveryAuditedTargetThroughTheQueue() throws Exception {
        Path finalAudit = Path.of(System.getProperty("minosoft.audit", "")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(finalAudit)) {
            System.err.println("Skipping full-audit coverage; set -Dminosoft.audit to the final audit JSON.");
            return;
        }
        Path audits = Files.createDirectories(temporary.resolve("audits"));
        Files.copy(finalAudit, audits.resolve("00-audit.json"));

        ContentSubmissionQueue.Result result = ContentSubmissionQueue.prepare(audits, null, null, List.of(new ContentSubmissionQueue.SelectionSource("faithful-32x", java.nio.file.Path.of("faithful")), new ContentSubmissionQueue.SelectionSource("vanilla-evolved", java.nio.file.Path.of("vanilla"))));

        assertTrue(result.entries().size() > 0, "Queue must not be empty for a non-complete audit.");
        assertFalse(result.entries().stream().anyMatch(entry -> entry.disposition() == null), "Every entry must be classified.");
        assertEquals(result.entries().size(), result.generate() + result.select() + result.resolved(), "Counts must sum to the entry list.");
    }

    private static ContentSubmissionQueue.Entry entry(ContentSubmissionQueue.Result result, String target) {
        return result.entries().stream()
            .filter(entry -> entry.target().equals(target))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing queue entry for target " + target));
    }

    private Path audits() throws Exception {
        return Files.createDirectories(temporary.resolve("audits"));
    }

    private static String audit(String blockstates, String models, String textures) {
        return "{\"schema\":1,\"missing\":{\"blockstates\":" + blockstates
            + ",\"models\":" + models + ",\"textures\":" + textures + "}}";
    }
}
