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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic content submission queue. Every cumulative audited missing
 * target is triaged into one of two work lanes:
 *
 * <ul>
 *   <li>{@code generate} — the deterministic generator has a distinct raster
 *       family (textures) or a non-trivial model shape (models), so generation
 *       is the right answer;</li>
 *   <li>{@code select} — only a generic placeholder exists today; the real
 *       asset should be selected from another package (Faithful, Vanilla
 *       Evolved, VoxeLibre, a mod, or a user overlay).</li>
 * </ul>
 *
 * A target whose authored asset already wins in the composed stack is marked
 * {@code resolved} and drained from the actionable queue. Classification is a
 * pure function of the audit inventory plus the composed and generated trees,
 * so identical inputs always produce an identical, byte-for-byte queue.
 */
final class ContentSubmissionQueue {
    static final int SCHEMA = 1;
    static final String QUEUE_VERSION = "1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KIND_NAMES = Set.of("blockstates", "models", "textures");
    private static final int MAX_AUDITS = 64;
    private static final long MAX_AUDIT_BYTES = 32L * 1024 * 1024;
    private static final int MAX_RESOURCES = 50_000;
    private static final int MAX_CONSUMERS = 256;

    record Candidate(String source, String target, String match) {}

    record SelectionSource(String label, Path path) {
        SelectionSource {
            path = path.toAbsolutePath().normalize();
        }
    }

    record Entry(
        String kind,
        String resource,
        String target,
        String disposition,
        String detail,
        List<Candidate> candidates,
        List<String> consumers,
        boolean consumersTruncated
    ) {}

    record Result(
        String fingerprint,
        int audits,
        int generate,
        int select,
        int selectCandidates,
        int selectUnavailable,
        int resolved,
        List<Entry> entries,
        List<String> selectionSources
    ) {}

    /** A queue entry with its deterministic priority score and sort keys. */
    record RankedEntry(
        int rank,
        int score,
        String tier,
        int consumers,
        Entry entry
    ) {}

    /** The documented, machine-readable scoring rule for the {@code --top K} ranking. */
    static final String RANKING_RULE = "Actionable only (resolved excluded). Tier by disposition: "
        + "select-with-candidates (score 400, adopt a real asset already in a package), "
        + "select textures without candidates (300, generic placeholder that looks the same), "
        + "select models without candidates (200, cube/empty placeholder), "
        + "generate (100, distinct family already produced). "
        + "Tie-break by consumer count descending, then target lexicographic.";

    /** The documented rule for the {@code --authoring} backlog (new authored assets only). */
    static final String AUTHORING_RULE = "New authored input only: adoption-only select-with-candidate "
        + "entries are excluded. Tier by disposition: select textures without candidates (score 300, "
        + "generic placeholder that looks the same), select models without candidates (score 200, "
        + "cube/empty placeholder), generate (score 100, distinct family already produced but "
        + "replaceable by authored work). Tie-break by consumer count descending, then target "
        + "lexicographic.";

    private ContentSubmissionQueue() {}

    /**
     * Builds the queue from the cumulative audit inventory, the generated
     * compatibility provider, and the final composed pack.
     *
     * @param auditSource       a single audit JSON file or a directory of stage JSONs
     * @param generatedPack     the generated-compatibility provider tree (optional)
     * @param composedPack      final composed resource-pack tree (optional)
     * @param selectionSources  non-generated stack layers that could supply
     *                          {@code select} targets, each with a concrete path
     */
    static Result prepare(Path auditSource, Path generatedPack, Path composedPack, List<SelectionSource> selectionSources) throws IOException {
        AuditModel audits = readAudits(auditSource);
        List<Entry> entries = new ArrayList<>();
        List<SelectionSource> selection = selectionSources == null ? List.of() : List.copyOf(selectionSources);
        Probe probe = Probe.create(selection);
        for (String kind : KIND_NAMES) {
            for (Map.Entry<String, AuditEntry> target : audits.byKind.get(kind).entrySet()) {
                AuditEntry audit = target.getValue();
                entries.add(classify(kind, target.getKey(), audit, generatedPack, composedPack, probe));
            }
        }
        entries.sort(Comparator
            .comparing(Entry::kind)
            .thenComparing(Entry::target)
            .thenComparing(Entry::resource));
        int generate = 0;
        int select = 0;
        int selectCandidates = 0;
        int selectUnavailable = 0;
        int resolved = 0;
        for (Entry entry : entries) {
            switch (entry.disposition()) {
                case "generate" -> generate++;
                case "select" -> {
                    select++;
                    if (entry.candidates().isEmpty()) selectUnavailable++;
                    else selectCandidates++;
                }
                case "resolved" -> resolved++;
            }
        }
        List<String> labels = selection.stream().map(SelectionSource::label).toList();
        String fingerprint = fingerprint(entries, labels);
        return new Result(fingerprint, audits.audits, generate, select, selectCandidates, selectUnavailable, resolved, List.copyOf(entries), labels);
    }

    /**
     * Returns the top {@code k} actionable authoring targets ranked by the
     * documented {@link #RANKING_RULE}. Only non-resolved entries qualify; each
     * is scored by disposition tier and tie-broken by consumer count descending
     * and target lexicographic, so the result is deterministic.
     *
     * @param authoring when true, emits the {@link #AUTHORING_RULE} backlog: only
     *                  targets that need new authored input are eligible, so
     *                  adoption-only {@code select-with-candidate} entries drop out
     */
    static List<RankedEntry> top(List<Entry> entries, int k, boolean authoring) {
        require(k >= 0, "top count must not be negative: " + k);
        Map<String, Integer> reachableConsumers = new TreeMap<>();
        Map<String, Integer> authoringLeverage = new TreeMap<>();
        for (Entry entry : entries) {
            String base = baseName(entry.target());
            reachableConsumers.merge(base, entry.consumers().size(), Integer::sum);
            if (!entry.disposition().equals("resolved")) authoringLeverage.merge(base, 1, Integer::sum);
        }
        List<Entry> actionable = entries.stream()
            .filter(entry -> !entry.disposition().equals("resolved"))
            .filter(entry -> !authoring || tier(entry).equals("select-texture-unavailable")
                || tier(entry).equals("select-model-unavailable") || tier(entry).equals("generate"))
            .toList();
        List<RankedEntry> ranked = new ArrayList<>();
        for (Entry entry : actionable) {
            String base = baseName(entry.target());
            int consumers = entry.kind().equals("textures")
                ? reachableConsumers.getOrDefault(base, 0)
                : entry.consumers().size();
            int leverage = authoringLeverage.getOrDefault(base, 0);
            ranked.add(new RankedEntry(0, score(entry) + leverage, tier(entry), consumers, entry));
        }
        ranked.sort(Comparator
            .comparingInt((RankedEntry item) -> -item.score())
            .thenComparingInt(item -> -item.consumers())
            .thenComparing(item -> item.entry().target()));
        for (int index = 0; index < ranked.size(); index++) {
            ranked.set(index, new RankedEntry(index + 1, ranked.get(index).score(), ranked.get(index).tier(),
                ranked.get(index).consumers(), ranked.get(index).entry()));
        }
        return ranked.stream().limit(k).toList();
    }

    private static String baseName(String target) {
        int length = target.length();
        if (target.endsWith(".png")) length -= ".png".length();
        else if (target.endsWith(".json")) length -= ".json".length();
        String withoutSuffix = target.substring(0, length);
        return withoutSuffix.substring(withoutSuffix.lastIndexOf('/') + 1);
    }

    static List<RankedEntry> top(List<Entry> entries, int k) {
        return top(entries, k, false);
    }

    private static int score(Entry entry) {
        return switch (entry.disposition()) {
            case "select" -> {
                boolean candidates = !entry.candidates().isEmpty();
                boolean texture = entry.kind().equals("textures");
                yield candidates ? 400 : (texture ? 300 : 200);
            }
            case "generate" -> 100;
            default -> 0;
        };
    }

    private static String tier(Entry entry) {
        return switch (entry.disposition()) {
            case "select" -> entry.candidates().isEmpty()
                ? (entry.kind().equals("textures") ? "select-texture-unavailable" : "select-model-unavailable")
                : "select-with-candidate";
            case "generate" -> "generate";
            default -> "resolved";
        };
    }

    private static Entry classify(String kind, String target, AuditEntry audit, Path generatedPack, Path composedPack, Probe probe) {
        boolean authored = composedPack != null && generatedPack != null
            && Files.isRegularFile(composedPack.resolve(target))
            && Files.isRegularFile(generatedPack.resolve(target))
            && differs(composedPack.resolve(target), generatedPack.resolve(target));
        if (authored) {
            return new Entry(kind, audit.resource, target, "resolved", "authored", List.of(), audit.consumers, audit.consumersTruncated);
        }
        Entry entry = switch (kind) {
            case "textures" -> textureEntry(target, audit);
            case "models" -> modelEntry(target, audit);
            default -> new Entry(kind, audit.resource, target, "generate", "blockstate", List.of(), audit.consumers, audit.consumersTruncated);
        };
        if (!entry.disposition().equals("select")) return entry;
        List<Candidate> candidates = probe.candidates(target);
        return new Entry(kind, audit.resource, target, "select", entry.detail(), candidates, audit.consumers, audit.consumersTruncated);
    }

    private static boolean differs(Path first, Path second) {
        try {
            long mismatch = Files.mismatch(first, second);
            return mismatch != -1L;
        } catch (IOException error) {
            return false;
        }
    }

    private static Entry textureEntry(String target, AuditEntry audit) {
        String family = GeneratedTextureLibrary.family(target);
        String disposition = GeneratedTextureLibrary.distinct(family) ? "generate" : "select";
        return new Entry("textures", audit.resource, target, disposition, family, List.of(), audit.consumers, audit.consumersTruncated);
    }

    private static Entry modelEntry(String target, AuditEntry audit) {
        String id = modelId(target);
        GeneratedContentPack.Shape shape = GeneratedContentPack.shape(id);
        String disposition = shape == GeneratedContentPack.Shape.CUBE || shape == GeneratedContentPack.Shape.EMPTY
            ? "select"
            : "generate";
        return new Entry("models", audit.resource, target, disposition, shape.name().toLowerCase(Locale.ROOT), List.of(), audit.consumers, audit.consumersTruncated);
    }

    private static String modelId(String target) {
        String withoutSuffix = target.substring(0, target.length() - ".json".length());
        return withoutSuffix.substring(withoutSuffix.lastIndexOf('/') + 1);
    }

    /**
     * In-memory index of the candidate package files. Directories are walked
     * once; archives are listed once. A candidate match is {@code exact} when a
     * source contains the target path itself, or {@code near} when a source
     * carries the same basename under a sibling texture/model directory (for
     * example an item texture that only exists as its block counterpart).
     */
    private record Probe(List<SelectionSource> sources, List<TreeSet<String>> files) {
        static Probe create(List<SelectionSource> sources) {
            List<TreeSet<String>> files = new ArrayList<>();
            for (SelectionSource source : sources) files.add(list(source.path()));
            return new Probe(sources, files);
        }

        List<Candidate> candidates(String target) {
            List<Candidate> result = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                TreeSet<String> tree = files.get(index);
                if (tree.contains(target)) {
                    result.add(new Candidate(sources.get(index).label(), target, "exact"));
                    continue;
                }
                String near = nearMatch(tree, target);
                if (near != null) result.add(new Candidate(sources.get(index).label(), near, "near"));
            }
            result.sort(Comparator.comparing(Candidate::source).thenComparing(Candidate::target));
            return result;
        }

        private static String nearMatch(TreeSet<String> tree, String target) {
            int slash = target.lastIndexOf('/');
            if (slash < 0) return null;
            String base = target.substring(slash + 1);
            String prefix = target.substring(0, slash);
            int marker = prefix.lastIndexOf('/');
            if (marker < 0) return null;
            String head = prefix.substring(0, marker);
            for (String sibling : SIBLING_DIRECTORIES) {
                String probe = head + "/" + sibling + "/" + base;
                if (!probe.equals(target) && tree.contains(probe)) return probe;
            }
            return null;
        }
    }

    private static final List<String> SIBLING_DIRECTORIES = List.of(
        "block", "item", "entity", "misc", "environment", "particle", "gui", "font",
        "models/block", "models/item"
    );

    private static TreeSet<String> list(Path path) {
        TreeSet<String> tree = new TreeSet<>();
        if (!Files.exists(path)) return tree;
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    String relative = path.relativize(file).toString().replace('\\', '/');
                    if (!relative.equals("provenance.json") && !relative.equals("pack.mcmeta")) tree.add(relative);
                });
            } catch (IOException ignored) {
            }
        } else {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
                zip.stream().forEach(entry -> {
                    if (!entry.isDirectory()) tree.add(entry.getName().replace('\\', '/'));
                });
            } catch (IOException ignored) {
            }
        }
        return tree;
    }

    private static AuditModel readAudits(Path auditSource) throws IOException {
        TreeMap<String, TreeMap<String, AuditEntry>> byKind = new TreeMap<>();
        for (String kind : KIND_NAMES) byKind.put(kind, new TreeMap<>());
        if (auditSource == null || !Files.exists(auditSource)) return new AuditModel(byKind, 0);

        List<Path> audits;
        if (Files.isRegularFile(auditSource)) {
            audits = List.of(auditSource);
        } else {
            require(Files.isDirectory(auditSource), "Content queue audit source is neither a file nor directory: " + auditSource);
            try (var entries = Files.list(auditSource)) {
                audits = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            }
        }
        require(audits.size() <= MAX_AUDITS, "Content queue audit source exceeds " + MAX_AUDITS + " audit files: " + auditSource);
        for (Path audit : audits) {
            require(Files.size(audit) <= MAX_AUDIT_BYTES, "Content queue audit exceeds the 32 MiB parser limit: " + audit);
            JsonNode root = JSON.readTree(audit.toFile());
            require(root.isObject() && root.path("schema").asInt(-1) == 1, "Content queue audit requires schema 1: " + audit);
            JsonNode missing = root.path("missing");
            require(missing.isObject(), "Content queue audit is missing its resource inventory: " + audit);
            for (String kind : KIND_NAMES) {
                JsonNode entries = missing.path(kind);
                require(entries.isArray(), "Content queue audit is missing array '" + kind + "': " + audit);
                for (JsonNode entry : entries) {
                    String target = entry.path("target").asText("").trim().replace('\\', '/');
                    require(isSafeTarget(target), "Content queue audit has an unsafe target: " + target);
                    String resource = entry.path("resource").asText("").trim();
                    TreeSet<String> consumers = new TreeSet<>();
                    JsonNode rawConsumers = entry.path("consumers");
                    if (rawConsumers.isArray()) {
                        for (JsonNode consumer : rawConsumers) {
                            if (consumers.size() >= MAX_CONSUMERS) break;
                            String value = consumer.asText("").trim();
                            if (!value.isEmpty()) consumers.add(value);
                        }
                    }
                    boolean consumersTruncated = entry.path("consumersTruncated").asBoolean(false);
                    TreeMap<String, AuditEntry> inventory = byKind.get(kind);
                    AuditEntry previous = inventory.get(target);
                    if (previous == null) {
                        inventory.put(target, new AuditEntry(resource, List.copyOf(consumers), consumersTruncated));
                    } else {
                        TreeSet<String> merged = new TreeSet<>(previous.consumers());
                        merged.addAll(consumers);
                        boolean truncated = previous.consumersTruncated() || consumersTruncated || merged.size() > MAX_CONSUMERS;
                        while (merged.size() > MAX_CONSUMERS) merged.pollLast();
                        String mergedResource = previous.resource().compareTo(resource) <= 0 ? previous.resource() : resource;
                        inventory.put(target, new AuditEntry(mergedResource, List.copyOf(merged), truncated));
                    }
                }
            }
        }
        for (Map.Entry<String, TreeMap<String, AuditEntry>> kind : byKind.entrySet()) {
            require(kind.getValue().size() <= MAX_RESOURCES, "Content queue inventory exceeds " + MAX_RESOURCES + " targets per kind.");
        }
        return new AuditModel(byKind, audits.size());
    }

    private static boolean isSafeTarget(String target) {
        return target.matches("assets/[a-z0-9_.-]+/[a-z0-9/._-]+")
            && !target.contains("/../") && !target.endsWith("/..");
    }

    private static String fingerprint(List<Entry> entries, List<String> selectionSources) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        digest.update(QUEUE_VERSION.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (String source : selectionSources) {
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        for (Entry entry : entries) {
            digest.update(entry.kind().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.resource().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.target().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.disposition().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.detail().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.consumersTruncated() ? (byte) 1 : (byte) 0);
            digest.update((byte) 0);
            for (Candidate candidate : entry.candidates()) {
                digest.update(candidate.source().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(candidate.target().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(candidate.match().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            for (String consumer : entry.consumers()) {
                digest.update(consumer.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static ObjectNode toJson(Result result) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("queueVersion", QUEUE_VERSION);
        root.put("fingerprint", result.fingerprint());
        root.put("audits", result.audits());
        root.putObject("counts").put("generate", result.generate()).put("select", result.select())
            .put("selectWithCandidates", result.selectCandidates()).put("selectUnavailable", result.selectUnavailable())
            .put("resolved", result.resolved()).put("total", result.entries().size());
        ArrayNode sources = root.putArray("selectionSources");
        result.selectionSources().forEach(sources::add);
        ArrayNode entries = root.putArray("entries");
        for (Entry entry : result.entries()) {
            ObjectNode entryNode = entries.addObject();
            entryNode.put("kind", entry.kind()).put("resource", entry.resource())
                .put("target", entry.target()).put("disposition", entry.disposition())
                .put("detail", entry.detail()).put("consumersTruncated", entry.consumersTruncated());
            if (!entry.candidates().isEmpty()) {
                ArrayNode candidates = entryNode.putArray("candidates");
                for (Candidate candidate : entry.candidates()) {
                    candidates.addObject().put("source", candidate.source())
                        .put("target", candidate.target()).put("match", candidate.match());
                }
            }
            ArrayNode consumers = entryNode.putArray("consumers");
            for (String consumer : entry.consumers()) consumers.add(consumer);
        }
        return root;
    }

    static ObjectNode topJson(List<RankedEntry> top, String rankingRule) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("queueVersion", QUEUE_VERSION);
        root.put("rankingRule", rankingRule);
        ArrayNode entries = root.putArray("top");
        for (RankedEntry ranked : top) {
            Entry entry = ranked.entry();
            ObjectNode entryNode = entries.addObject();
            entryNode.put("rank", ranked.rank()).put("score", ranked.score())
                .put("tier", ranked.tier()).put("consumerCount", ranked.consumers())
                .put("kind", entry.kind()).put("resource", entry.resource())
                .put("target", entry.target()).put("disposition", entry.disposition())
                .put("detail", entry.detail());
            if (!entry.candidates().isEmpty()) {
                ArrayNode candidates = entryNode.putArray("candidates");
                for (Candidate candidate : entry.candidates()) {
                    candidates.addObject().put("source", candidate.source())
                        .put("target", candidate.target()).put("match", candidate.match());
                }
            }
            ArrayNode consumers = entryNode.putArray("consumers");
            for (String consumer : entry.consumers()) consumers.add(consumer);
        }
        return root;
    }

    /**
     * Emits the entire queue as a UTF-8 CSV for a production handoff. Every
     * entry becomes one row with kind, resource, target, disposition, priority
     * tier, priority score, family/detail, consumer count, consumers, candidate
     * sources, and candidate targets. Rows are ordered by priority tier then
     * target so the team can filter on {@code tier} or {@code priority}.
     */
    static String toCsv(Result result) {
        StringBuilder csv = new StringBuilder();
        String header = String.join(",", List.of(
            "kind", "resource", "target", "disposition", "tier", "priority", "detail",
            "consumerCount", "consumers", "candidateSources", "candidateTargets"
        ));
        csv.append(header).append(System.lineSeparator());
        List<RankedEntry> ranked = top(result.entries(), Integer.MAX_VALUE);
        for (RankedEntry rankedEntry : ranked) {
            csv.append(csvRow(rankedEntry)).append(System.lineSeparator());
        }
        for (Entry entry : result.entries()) {
            if (entry.disposition().equals("resolved")) csv.append(csvRow(new RankedEntry(0, 0, "resolved", 0, entry))).append(System.lineSeparator());
        }
        return csv.toString();
    }

    private static String csvRow(RankedEntry ranked) {
        Entry entry = ranked.entry();
        List<String> candidateSources = new ArrayList<>();
        List<String> candidateTargets = new ArrayList<>();
        for (Candidate candidate : entry.candidates()) {
            candidateSources.add(candidate.source());
            candidateTargets.add(candidate.target());
        }
        return String.join(",", List.of(
            csv(entry.kind()),
            csv(entry.resource()),
            csv(entry.target()),
            csv(entry.disposition()),
            csv(ranked.tier()),
            Integer.toString(ranked.score()),
            csv(entry.detail()),
            Integer.toString(ranked.consumers()),
            csv(String.join("|", entry.consumers())),
            csv(String.join("|", candidateSources)),
            csv(String.join("|", candidateTargets))
        ));
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private record AuditEntry(String resource, List<String> consumers, boolean consumersTruncated) {}
    private record AuditModel(TreeMap<String, TreeMap<String, AuditEntry>> byKind, int audits) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
