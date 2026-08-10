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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentStackManifestTest {

    private static final String BASE_MANIFEST = """
        {
          "schema": 1,
          "name": "standalone",
          "standalone": true,
          "sources": [
            { "type": "generated", "label": "generated-compatibility",
              "environment": "MINOSOFT_CONTENT_AUDIT_STAGES",
              "default": ".run/content-audits/terrain-local-dojo-stages", "optional": true },
            { "type": "directory", "label": "loose-content",
              "environment": "MINOSOFT_CONTENT_DIRECTORIES", "optional": true, "multiple": true },
            { "type": "notice", "label": "standalone-content-notices",
              "default": "content-stacks/notices/standalone/NOTICE.md" }
          ]
        }
        """;

    private static Path writeBase(Path dir) throws Exception {
        Path manifest = dir.resolve("standalone.json");
        Files.writeString(manifest, BASE_MANIFEST);
        return manifest;
    }

    private static ContentStackManifest.Source source(ContentStackManifest.Definition definition, String label) {
        return definition.sources().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void localOverlayRetargetsAResolvableFieldAndPreservesStructure(@TempDir Path dir) throws Exception {
        Path manifest = writeBase(dir);
        Files.writeString(dir.resolve("standalone.local.json"), """
            {
              "schema": 1,
              "overrides": {
                "loose-content": { "default": "/tmp/handoff/producer-out", "optional": false }
              }
            }
            """);

        ContentStackManifest.Definition definition = ContentStackManifest.read(manifest);

        // structure (order, type, label, count) is unchanged
        assertEquals(
            java.util.List.of("generated-compatibility", "loose-content", "standalone-content-notices"),
            definition.sources().stream().map(ContentStackManifest.Source::label).toList());
        ContentStackManifest.Source loose = source(definition, "loose-content");
        assertEquals("/tmp/handoff/producer-out", loose.defaultPath());
        assertFalse(loose.optional());
        // untouched fields survive the overlay
        assertEquals("MINOSOFT_CONTENT_DIRECTORIES", loose.environment());
        assertTrue(loose.multiple());
        // an unreferenced source is left exactly as authored
        assertEquals(".run/content-audits/terrain-local-dojo-stages",
            source(definition, "generated-compatibility").defaultPath());
    }

    @Test
    void manifestWithoutOverlayIsUnchanged(@TempDir Path dir) throws Exception {
        ContentStackManifest.Definition definition = ContentStackManifest.read(writeBase(dir));
        // no sibling standalone.local.json -> sources are exactly as authored
        assertTrue(source(definition, "loose-content").optional());
        assertEquals("", source(definition, "loose-content").defaultPath());
    }

    @Test
    void overlayReferencingUnknownLabelIsRejected(@TempDir Path dir) throws Exception {
        Path manifest = writeBase(dir);
        Files.writeString(dir.resolve("standalone.local.json"),
            "{ \"schema\": 1, \"overrides\": { \"does-not-exist\": { \"default\": \"/x\" } } }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ContentStackManifest.read(manifest));
        assertTrue(error.getMessage().contains("unknown source label 'does-not-exist'"), error.getMessage());
    }

    @Test
    void overlayCannotChangeStructuralFields(@TempDir Path dir) throws Exception {
        Path manifest = writeBase(dir);
        Files.writeString(dir.resolve("standalone.local.json"),
            "{ \"schema\": 1, \"overrides\": { \"loose-content\": { \"type\": \"archive\" } } }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ContentStackManifest.read(manifest));
        assertTrue(error.getMessage().contains("Unknown local content stack overlay"), error.getMessage());
    }

    @Test
    void manifestAndOverlayRejectUnknownOrWronglyTypedFields(@TempDir Path dir) throws Exception {
        Path manifest = writeBase(dir);
        Files.writeString(dir.resolve("standalone.local.json"),
            "{ \"schema\": 1, \"overrides\": { \"loose-content\": { \"optional\": \"yes\" } } }");
        assertThrows(IllegalArgumentException.class, () -> ContentStackManifest.read(manifest));

        Files.delete(dir.resolve("standalone.local.json"));
        Files.writeString(manifest, BASE_MANIFEST.replace("\"standalone\": true", "\"standalone\": true, \"unexpected\": 1"));
        assertThrows(IllegalArgumentException.class, () -> ContentStackManifest.read(manifest));
    }

    @Test
    void defaultStandaloneManifestHasStablePriorityContract() throws Exception {
        Path project = Path.of("").toAbsolutePath().normalize().getParent().getParent();
        ContentStackManifest.Definition manifest = ContentStackManifest.read(project.resolve("content-stacks/standalone.json"));

        assertEquals("standalone", manifest.name());
        assertTrue(manifest.standalone());
        assertEquals("distant-horizons-bliss", manifest.managedModpack());
        assertEquals(
            java.util.List.of(
                "generated", "voxelibre", "managed_mods", "mods", "directory",
                "notice",
                "managed_resource_pack", "managed_resource_pack", "managed_resource_pack", "managed_resource_pack",
                "archive", "archive"
            ),
            manifest.sources().stream().map(ContentStackManifest.Source::type).toList()
        );
        assertEquals(
            java.util.List.of("faithful-32x", "vanilla-evolved", "open-assets-lib", "gui-revision"),
            manifest.sources().stream()
                .filter(source -> source.type().equals("managed_resource_pack"))
                .map(ContentStackManifest.Source::label)
                .toList()
        );
        assertEquals("VanillaEvolved_1.9.0.zip", manifest.sources().get(7).artifact());
        assertEquals("faithful-overlays", manifest.sources().getLast().label());
        assertEquals("MINOSOFT_FAITHFUL_PACKS", manifest.sources().getLast().environment());
    }
}
