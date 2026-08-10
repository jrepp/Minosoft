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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict source-controlled definition for an ordered local content stack. */
final class ContentStackManifest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 1024 * 1024;
    private static final int MAX_SOURCES = 64;
    /** Suffix for the machine-local, git-ignored overlay that sits beside a tracked manifest. */
    private static final String LOCAL_OVERLAY_SUFFIX = ".local.json";
    private static final Set<String> TYPES = Set.of(
        "generated", "voxelibre", "managed_mods", "managed_resource_packs", "managed_resource_pack",
        "directory", "archive", "mods", "notice"
    );
    private static final Set<String> ROOT_FIELDS = Set.of("schema", "name", "standalone", "managed_modpack", "sources");
    private static final Set<String> SOURCE_FIELDS = Set.of("type", "label", "environment", "default", "artifact", "optional", "multiple");
    private static final Set<String> OVERLAY_ROOT_FIELDS = Set.of("_readme", "schema", "overrides");
    private static final Set<String> OVERRIDE_FIELDS = Set.of("default", "environment", "optional");

    record Source(
        String type,
        String label,
        String environment,
        String defaultPath,
        String artifact,
        boolean optional,
        boolean multiple
    ) {}
    record Definition(String name, boolean standalone, String managedModpack, List<Source> sources, Path manifest) {}

    private ContentStackManifest() {}

    static Definition read(Path manifest) throws IOException {
        manifest = manifest.toAbsolutePath().normalize();
        require(Files.isRegularFile(manifest), "Content stack manifest is missing: " + manifest);
        require(Files.size(manifest) <= MAX_BYTES, "Content stack manifest exceeds the 1 MiB limit: " + manifest);
        JsonNode root = JSON.readTree(manifest.toFile());
        require(root.isObject(), "Content stack manifest must be a JSON object: " + manifest);
        requireFields(root, ROOT_FIELDS, "content stack manifest", manifest);
        require(root.path("schema").isIntegralNumber() && root.path("schema").intValue() == 1,
            "Content stack manifest requires numeric schema 1: " + manifest);
        requireBoolean(root, "standalone", manifest);
        String name = requiredText(root, "name", manifest);
        require(name.matches("[A-Za-z0-9][A-Za-z0-9._-]*"), "Invalid content stack name in " + manifest + ": " + name);
        String managedModpack = optionalText(root, "managed_modpack", manifest);
        if (!managedModpack.isEmpty()) require(managedModpack.matches("[A-Za-z0-9][A-Za-z0-9._-]*"), "Invalid managed_modpack in " + manifest);
        JsonNode rawSources = root.path("sources");
        require(rawSources.isArray() && !rawSources.isEmpty(), "Content stack manifest requires a non-empty sources array: " + manifest);
        require(rawSources.size() <= MAX_SOURCES, "Content stack manifest exceeds " + MAX_SOURCES + " sources: " + manifest);
        List<Source> sources = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (int index = 0; index < rawSources.size(); index++) {
            JsonNode source = rawSources.get(index);
            require(source.isObject(), "Content source " + index + " must be an object in " + manifest);
            requireFields(source, SOURCE_FIELDS, "content source " + index, manifest);
            String type = requiredText(source, "type", manifest).trim().toLowerCase().replace('-', '_');
            require(TYPES.contains(type), "Unknown content source type '" + type + "' in " + manifest);
            String label = source.has("label") ? optionalText(source, "label", manifest) : type + "-" + index;
            require(label.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"), "Invalid content source label '" + label + "' in " + manifest);
            require(labels.add(label), "Duplicate content source label '" + label + "' in " + manifest);
            String environment = optionalText(source, "environment", manifest);
            String defaultPath = optionalText(source, "default", manifest);
            String artifact = optionalText(source, "artifact", manifest);
            requireBoolean(source, "optional", manifest);
            requireBoolean(source, "multiple", manifest);
            boolean virtual = type.startsWith("managed_") || type.equals("generated");
            require(virtual || !environment.isEmpty() || !defaultPath.isEmpty(), "Content source '" + label + "' requires environment or default in " + manifest);
            require(environment.isEmpty() || environment.matches("[A-Z][A-Z0-9_]*"), "Invalid environment key for source '" + label + "' in " + manifest);
            require(!type.equals("managed_resource_pack") || !artifact.isEmpty(), "Content source '" + label + "' requires an artifact filename in " + manifest);
            require(type.equals("managed_resource_pack") || artifact.isEmpty(), "Only managed_resource_pack sources may select an artifact in " + manifest);
            require(artifact.isEmpty() || (!artifact.contains("/") && !artifact.contains("\\") && !artifact.equals(".") && !artifact.equals("..")),
                "Invalid artifact filename for source '" + label + "' in " + manifest);
            sources.add(new Source(
                type,
                label,
                environment,
                defaultPath,
                artifact,
                source.path("optional").asBoolean(false),
                source.path("multiple").asBoolean(false)
            ));
        }
        List<Source> resolved = applyLocalOverlay(sources, manifest);
        return new Definition(name, root.path("standalone").asBoolean(false), managedModpack, List.copyOf(resolved), manifest);
    }

    /**
     * Apply a machine-local, git-ignored overlay ({@code <name>.local.json}) that sits beside the
     * tracked manifest. The overlay is the producer/consumer rendezvous point: it may retarget a
     * source's resolvable fields (its {@code default} path, {@code environment} key, or
     * {@code optional} flag) so a machine can point an input queue at a local handoff directory
     * without committing that path. Structure (source order, {@code type}, {@code label},
     * {@code artifact}, {@code multiple}) stays source-controlled and cannot be overridden.
     */
    private static List<Source> applyLocalOverlay(List<Source> sources, Path manifest) throws IOException {
        String fileName = manifest.getFileName().toString();
        if (fileName.endsWith(LOCAL_OVERLAY_SUFFIX) || !fileName.endsWith(".json")) return sources;
        Path overlay = manifest.resolveSibling(fileName.substring(0, fileName.length() - ".json".length()) + LOCAL_OVERLAY_SUFFIX);
        if (!Files.isRegularFile(overlay)) return sources;
        require(Files.size(overlay) <= MAX_BYTES, "Local content stack overlay exceeds the 1 MiB limit: " + overlay);
        JsonNode root = JSON.readTree(overlay.toFile());
        require(root.isObject(), "Local content stack overlay must be a JSON object: " + overlay);
        requireFields(root, OVERLAY_ROOT_FIELDS, "local content stack overlay", overlay);
        require(root.path("schema").isIntegralNumber() && root.path("schema").intValue() == 1,
            "Local content stack overlay requires numeric schema 1: " + overlay);
        JsonNode overrides = root.path("overrides");
        require(overrides.isObject(), "Local content stack overlay requires an 'overrides' object: " + overlay);

        Map<String, Source> byLabel = new LinkedHashMap<>();
        for (Source source : sources) byLabel.put(source.label(), source);
        for (Map.Entry<String, JsonNode> entry : overrides.properties()) {
            String label = entry.getKey();
            Source base = byLabel.get(label);
            require(base != null, "Local content stack overlay references unknown source label '" + label + "' in " + overlay);
            JsonNode override = entry.getValue();
            require(override.isObject(), "Local content stack overlay override for '" + label + "' must be an object in " + overlay);
            requireFields(override, OVERRIDE_FIELDS, "local content stack overlay for '" + label + "'", overlay);
            requireBoolean(override, "optional", overlay);
            String environment = override.has("environment") ? optionalText(override, "environment", overlay) : base.environment();
            String defaultPath = override.has("default") ? optionalText(override, "default", overlay) : base.defaultPath();
            boolean optional = override.path("optional").asBoolean(base.optional());
            require(environment.isEmpty() || environment.matches("[A-Z][A-Z0-9_]*"),
                "Invalid environment key for overlay source '" + label + "' in " + overlay);
            boolean virtual = base.type().startsWith("managed_") || base.type().equals("generated");
            require(virtual || !environment.isEmpty() || !defaultPath.isEmpty(),
                "Overlay source '" + label + "' requires environment or default in " + overlay);
            byLabel.put(label, new Source(base.type(), base.label(), environment, defaultPath, base.artifact(), optional, base.multiple()));
        }

        List<Source> resolved = new ArrayList<>(sources.size());
        for (Source source : sources) resolved.add(byLabel.get(source.label()));
        return resolved;
    }

    private static String requiredText(JsonNode root, String field, Path manifest) {
        require(root.path(field).isTextual(), "Content stack field '" + field + "' must be text: " + manifest);
        String value = root.path(field).textValue().trim();
        require(!value.isEmpty(), "Content stack manifest requires '" + field + "': " + manifest);
        return value;
    }

    private static String optionalText(JsonNode root, String field, Path source) {
        if (!root.has(field)) return "";
        require(root.path(field).isTextual(), "Content stack field '" + field + "' must be text: " + source);
        return root.path(field).textValue().trim();
    }

    private static void requireBoolean(JsonNode root, String field, Path source) {
        require(!root.has(field) || root.path(field).isBoolean(), "Content stack field '" + field + "' must be boolean: " + source);
    }

    private static void requireFields(JsonNode node, Set<String> allowed, String description, Path source) {
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            require(allowed.contains(field), "Unknown " + description + " field '" + field + "' in " + source);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
