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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bixilon.minosoft.debug.DebugJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class WorldSnapshot {
    private static final int MAX_FILES = 1_000_000;
    private static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;

    ObjectNode create(Path source, Path output, String trajectory) throws IOException {
        Path sourceRoot = source.toAbsolutePath().normalize();
        Path outputTarget = output.toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) throw new IllegalArgumentException("world directory not found: " + sourceRoot);
        if (!Files.isRegularFile(sourceRoot.resolve("level.dat"))) {
            throw new IllegalArgumentException("world level.dat not found: " + sourceRoot.resolve("level.dat"));
        }
        if (Files.exists(outputTarget)) throw new IllegalArgumentException("snapshot output already exists: " + outputTarget);
        if (outputTarget.startsWith(sourceRoot)) throw new IllegalArgumentException("snapshot output must not be inside the source world");
        if (outputTarget.getParent() == null) throw new IllegalArgumentException("snapshot output requires a parent directory");

        Files.createDirectories(outputTarget.getParent());
        Path candidate = outputTarget.resolveSibling(outputTarget.getFileName() + ".partial-" + UUID.randomUUID());
        try {
            Files.createDirectory(candidate);
            List<Path> files;
            try (var paths = Files.walk(sourceRoot)) {
                files = paths
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> portable(sourceRoot.relativize(path))))
                    .toList();
            }
            if (files.size() > MAX_FILES) throw new IllegalArgumentException("world snapshot exceeds " + MAX_FILES + " files");

            MessageDigest tree = digest();
            ArrayNode entries = DebugJson.MAPPER.createArrayNode();
            long totalBytes = 0L;
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("world snapshot rejects symbolic links: " + file);
                BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
                totalBytes = Math.addExact(totalBytes, before.size());
                if (totalBytes > MAX_BYTES) throw new IllegalArgumentException("world snapshot exceeds 64 GiB");

                Path relative = sourceRoot.relativize(file);
                Path target = candidate.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
                if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                    throw new IOException("world changed during snapshot: " + file);
                }

                String hash = sha256(target);
                String name = portable(relative);
                tree.update((hash + "  " + name + "\n").getBytes(StandardCharsets.UTF_8));
                entries.addObject().put("path", name).put("bytes", before.size()).put("sha256", hash);
            }

            ObjectNode manifest = DebugJson.MAPPER.createObjectNode()
                .put("schemaVersion", 1)
                .put("createdAt", Instant.now().toString())
                .put("trajectory", trajectory)
                .put("source", sourceRoot.toString())
                .put("output", outputTarget.toString())
                .put("files", files.size())
                .put("bytes", totalBytes)
                .put("treeSha256", HexFormat.of().formatHex(tree.digest()));
            manifest.set("entries", entries);
            Files.writeString(
                candidate.resolve("snapshot-manifest.json"),
                DebugJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + System.lineSeparator(),
                StandardCharsets.UTF_8
            );
            Files.move(candidate, outputTarget, StandardCopyOption.ATOMIC_MOVE);
            return manifest;
        } catch (Throwable error) {
            deleteTree(candidate);
            if (error instanceof IOException io) throw io;
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IOException("world snapshot failed", error);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
