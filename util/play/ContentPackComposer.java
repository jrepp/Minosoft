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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Composes ordered local asset sources into one immutable resource-pack layout. */
final class ContentPackComposer {
    private static final String COMPOSER_VERSION = "7";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FILES = 100_000;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Set<String> NOTICE_FILES = Set.of(
        "LICENSE", "LICENSE.TXT", "LICENSE.MD",
        "LEGAL", "LEGAL.TXT", "LEGAL.MD",
        "NOTICE", "NOTICE.TXT", "NOTICE.MD",
        "CREDITS", "CREDITS.TXT", "CREDITS.MD",
        "COPYING", "COPYING.TXT", "COPYING.MD",
        "THIRD_PARTY", "THIRD_PARTY.TXT", "THIRD_PARTY.MD"
    );

    enum SourceType {
        DIRECTORY,
        ARCHIVE,
        MODS,
        NOTICE;

        static SourceType parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "directory", "dir", "loose", "resource_pack" -> DIRECTORY;
                case "archive", "zip", "jar" -> ARCHIVE;
                case "mods", "mod_collection" -> MODS;
                case "notice", "attribution" -> NOTICE;
                default -> throw new IllegalArgumentException("Unknown content source type '" + value + "'. Use directory, archive, mods, or notice.");
            };
        }
    }

    record Source(SourceType type, Path path, String label) {
        Source {
            path = path.toAbsolutePath().normalize();
            if (label == null || label.isBlank()) label = type.name().toLowerCase(Locale.ROOT) + ":" + path.getFileName();
        }
    }

    record Result(Path resourcePack, String fingerprint, int files, long bytes, List<Source> sources) {}

    private ContentPackComposer() {}

    static Result compose(List<Source> orderedSources, Path storeRoot, String stackName) throws IOException {
        require(!orderedSources.isEmpty(), "A content stack requires at least one source.");
        require(stackName != null && stackName.matches("[A-Za-z0-9][A-Za-z0-9._-]*"), "Invalid content stack name: " + stackName);
        Path parent = storeRoot.toAbsolutePath().normalize().resolve("content-stacks").resolve(stackName);
        Files.createDirectories(parent);
        Path candidate = parent.resolve(".candidate-" + ProcessHandle.current().pid());
        deleteTree(candidate);
        Budget budget = new Budget();
        List<Source> expanded = new ArrayList<>();
        try {
            Files.createDirectories(candidate.resolve("assets"));
            for (Source source : orderedSources) apply(source, candidate, budget, expanded);
            sanitizeAnimationMetadata(candidate.resolve("assets"));
            Files.writeString(candidate.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":22,\"description\":\"Minosoft managed content stack: " + jsonEscape(stackName) + "\"}}\n", StandardCharsets.UTF_8);
            String fingerprint = fingerprint(candidate, expanded);
            Path output = parent.resolve(fingerprint);
            if (Files.isRegularFile(output.resolve("provenance.json"))) {
                deleteTree(candidate);
                return readResult(output, fingerprint, expanded, budget);
            }
            writeProvenance(candidate, fingerprint, stackName, expanded, budget);
            publish(candidate, output, output.resolve("provenance.json"));
            return new Result(output, fingerprint, budget.files, budget.bytes, List.copyOf(expanded));
        } catch (Throwable error) {
            deleteTree(candidate);
            throw error;
        }
    }

    private static void sanitizeAnimationMetadata(Path assets) throws IOException {
        if (!Files.isDirectory(assets)) return;
        List<Path> metadata;
        try (var files = Files.walk(assets)) {
            metadata = files.filter(path -> path.getFileName().toString().endsWith(".png.mcmeta")).sorted().toList();
        }
        for (Path meta : metadata) {
            Path png = meta.resolveSibling(meta.getFileName().toString().substring(0, meta.getFileName().toString().length() - ".mcmeta".length()));
            if (!Files.isRegularFile(png) || Files.size(meta) > MAX_FILE_BYTES) continue;
            JsonNode root;
            try {
                root = JSON.readTree(meta.toFile());
            } catch (IOException invalidJson) {
                continue;
            }
            JsonNode animation = root.path("animation");
            JsonNode frames = animation.path("frames");
            if (!frames.isArray()) continue;
            ImageDimensions image = readImageDimensions(png);
            if (image == null) continue;
            int frameWidth = animation.path("width").asInt(image.width());
            int frameHeight = animation.path("height").asInt(frameWidth);
            if (frameWidth <= 0 || frameHeight <= 0) continue;
            long frameCount = (long) (image.width() / frameWidth) * (image.height() / frameHeight);
            boolean invalid = frameCount <= 0;
            for (JsonNode frame : frames) {
                int index = frame.isObject() ? frame.path("index").asInt(-1) : frame.asInt(-1);
                if (index < 0 || index >= frameCount) invalid = true;
            }
            if (invalid) Files.delete(meta);
        }
    }

    private static ImageDimensions readImageDimensions(Path image) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private static void publish(Path candidate, Path output, Path marker) throws IOException {
        try {
            Files.move(candidate, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            publishNonAtomic(candidate, output, marker);
        } catch (IOException collision) {
            if (!Files.isRegularFile(marker)) throw collision;
            deleteTree(candidate);
        }
    }

    private static void publishNonAtomic(Path candidate, Path output, Path marker) throws IOException {
        try {
            Files.move(candidate, output);
        } catch (IOException collision) {
            if (!Files.isRegularFile(marker)) throw collision;
            deleteTree(candidate);
        }
    }

    private static void apply(Source source, Path output, Budget budget, List<Source> expanded) throws IOException {
        switch (source.type) {
            case DIRECTORY -> copyDirectory(source, output, budget, expanded);
            case ARCHIVE -> copyArchive(source, output, budget, expanded);
            case MODS -> copyMods(source, output, budget, expanded);
            case NOTICE -> copyNotice(source, output, budget, expanded);
        }
    }

    private static void copyNotice(Source source, Path output, Budget budget, List<Source> expanded) throws IOException {
        require(Files.isRegularFile(source.path), "Content attribution notice is missing: " + source.path);
        require(isNoticeFile(source.path.getFileName().toString()),
            "Content attribution source must use a recognized notice filename: " + source.path);
        copyFile(source.path, noticeTarget(output, source, expanded.size(), source.path.getFileName().toString()), budget);
        expanded.add(source);
    }

    private static void copyDirectory(Source source, Path output, Budget budget, List<Source> expanded) throws IOException {
        Path root = source.path;
        require(Files.isDirectory(root), "Content directory is missing: " + root);
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) {
            require(root.getFileName() != null && root.getFileName().toString().equals("assets"),
                "Loose content directory must contain assets/ or be the assets directory: " + root);
            assets = root;
        }
        Path realAssets = assets.toRealPath();
        Path finalAssets = assets;
        Files.walkFileTree(assets, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (isUnwantedMetadata(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                Path real = file.toRealPath();
                require(real.startsWith(realAssets), "Content file escapes its source root: " + file);
                Path relative = finalAssets.relativize(file).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) throw new IllegalArgumentException("Unsafe content path: " + relative);
                copyFile(real, output.resolve("assets").resolve(relative), budget);
                return FileVisitResult.CONTINUE;
            }
        });
        copyDirectoryNotices(source, root, output, budget, expanded.size());
        expanded.add(source);
    }

    private static void copyDirectoryNotices(Source source, Path root, Path output, Budget budget, int priority) throws IOException {
        if (root.getFileName() != null && root.getFileName().toString().equals("assets")) return;
        List<Path> notices;
        try (var entries = Files.list(root)) {
            notices = entries.filter(Files::isRegularFile)
                .filter(path -> isNoticeFile(path.getFileName().toString()))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
        for (Path notice : notices) {
            copyFile(notice, noticeTarget(output, source, priority, notice.getFileName().toString()), budget);
        }
    }

    private static void copyArchive(Source source, Path output, Budget budget, List<Source> expanded) throws IOException {
        require(Files.isRegularFile(source.path), "Content archive is missing: " + source.path);
        try (ZipFile zip = new ZipFile(source.path.toFile())) {
            var entries = zip.stream().filter(entry -> !entry.isDirectory()).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            require(entries.size() <= MAX_FILES, "Content archive exceeds " + MAX_FILES + " entries: " + source.path);
            for (ZipEntry entry : entries) {
                String name = entry.getName().replace('\\', '/');
                if (isUnwantedMetadata(name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name)) continue;
                if (!name.contains("/") && isNoticeFile(name)) {
                    copyArchiveEntry(zip, entry, noticeTarget(output, source, expanded.size(), name), budget);
                    continue;
                }
                if (!name.startsWith("assets/")) continue;
                Path relative = safeArchivePath(name);
                copyArchiveEntry(zip, entry, contained(output, relative), budget);
            }
        }
        expanded.add(source);
    }

    private static void copyArchiveEntry(ZipFile zip, ZipEntry entry, Path target, Budget budget) throws IOException {
        String name = entry.getName().replace('\\', '/');
        long declared = entry.getSize();
        require(declared < 0 || declared <= MAX_FILE_BYTES, "Content archive entry exceeds the per-file limit: " + name);
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        try (InputStream input = zip.getInputStream(entry); var stream = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                written += read;
                require(written <= MAX_FILE_BYTES, "Content archive entry expands beyond the per-file limit: " + name);
                budget.reserve(read);
                stream.write(buffer, 0, read);
            }
        }
        budget.file();
    }

    private static boolean isNoticeFile(String name) {
        return NOTICE_FILES.contains(name.toUpperCase(Locale.ROOT));
    }

    private static boolean isUnwantedMetadata(String name) {
        if (name.equals(".DS_Store") || name.equals("Thumbs.db")) return true;
        if (name.endsWith("~") || name.startsWith("._")) return true;
        return false;
    }

    private static Path noticeTarget(Path output, Source source, int priority, String filename) {
        String label = source.label.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (label.isBlank()) label = "source";
        if (label.length() > 80) label = label.substring(0, 80);
        Path directory = output.resolve("third-party-notices").resolve("%03d-%s".formatted(priority, label));
        Path target = contained(directory, Path.of(filename).getFileName());
        for (int duplicate = 2; Files.exists(target); duplicate++) {
            require(duplicate <= 100, "Content source contains too many duplicate notice files: " + source.path);
            target = contained(directory, Path.of(duplicate + "-" + filename).getFileName());
        }
        return target;
    }

    private static void copyMods(Source source, Path output, Budget budget, List<Source> expanded) throws IOException {
        if (Files.isRegularFile(source.path)) {
            copyArchive(new Source(SourceType.ARCHIVE, source.path, source.label), output, budget, expanded);
            return;
        }
        require(Files.isDirectory(source.path), "Mod collection is missing: " + source.path);
        List<Path> archives;
        try (var entries = Files.list(source.path)) {
            archives = entries.filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".jar") || name.endsWith(".zip");
                })
                .sorted()
                .toList();
        }
        require(archives.size() <= MAX_FILES, "Mod collection contains too many archives: " + source.path);
        for (Path archive : archives) {
            copyArchive(new Source(SourceType.ARCHIVE, archive, source.label + "/" + archive.getFileName()), output, budget, expanded);
        }
    }

    private static void copyFile(Path source, Path target, Budget budget) throws IOException {
        long size = Files.size(source);
        require(size <= MAX_FILE_BYTES, "Content file exceeds the per-file limit: " + source);
        budget.reserve(size);
        budget.file();
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static String fingerprint(Path root, List<Source> sources) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        digest.update(COMPOSER_VERSION.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            digest.update(Integer.toString(index).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(source.type.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(source.label.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths
                .filter(Files::isRegularFile)
                .filter(path -> !isUnwantedMetadata(path.getFileName().toString()))
                .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                .toList();
        }
        for (Path file : files) {
            digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeProvenance(Path output, String fingerprint, String stackName, List<Source> sources, Budget budget) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("stack", stackName);
        root.put("composer_version", COMPOSER_VERSION);
        root.put("fingerprint", fingerprint);
        root.put("priority", "sources are applied from lowest to highest; later files replace earlier files");
        root.put("files_processed", budget.files);
        root.put("bytes_processed", budget.bytes);
        ArrayNode array = root.putArray("sources");
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            array.addObject().put("priority", index).put("type", source.type.name().toLowerCase(Locale.ROOT))
                .put("label", source.label).put("path", source.path.toString());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("provenance.json").toFile(), root);
    }

    private static Result readResult(Path output, String fingerprint, List<Source> sources, Budget budget) {
        return new Result(output, fingerprint, budget.files, budget.bytes, List.copyOf(sources));
    }

    private static Path safeArchivePath(String name) {
        Path relative = Path.of(name).normalize();
        require(!relative.isAbsolute() && !relative.startsWith("..") && relative.toString().replace('\\', '/').startsWith("assets/"),
            "Unsafe content archive path: " + name);
        return relative;
    }

    private static Path contained(Path root, Path relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        require(target.startsWith(normalizedRoot), "Content path escapes the composed pack: " + relative);
        return target;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private record ImageDimensions(int width, int height) {}

    private static final class Budget {
        private int files;
        private long bytes;

        private void file() {
            files++;
            require(files <= MAX_FILES, "Composed content exceeds " + MAX_FILES + " files.");
        }

        private void reserve(long amount) {
            require(amount >= 0 && bytes <= MAX_TOTAL_BYTES - amount, "Composed content exceeds the " + MAX_TOTAL_BYTES + " byte limit.");
            bytes += amount;
        }
    }
}
