/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

public final class DebugPaths {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

    private final Path root;
    private final Path runtime;

    public DebugPaths(Path root, Path runtime) {
        this.root = root.toAbsolutePath().normalize();
        this.runtime = runtime.toAbsolutePath().normalize();
    }

    public static DebugPaths system() {
        String configuredRoot = System.getenv("MINOSOFT_DEBUG_HOME");
        String configuredRuntime = System.getenv("MINOSOFT_DEBUG_RUNTIME");
        Path root = configuredRoot == null || configuredRoot.isBlank() ? defaultRoot() : Path.of(configuredRoot);
        Path runtime = configuredRuntime == null || configuredRuntime.isBlank() ? defaultRuntime() : Path.of(configuredRuntime);
        return new DebugPaths(root, runtime);
    }

    private static Path defaultRuntime() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path temporary = os.contains("win") ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of("/tmp");
        return temporary.resolve("minosoft-debug-" + Integer.toHexString(System.getProperty("user.name", "user").hashCode()));
    }

    private static Path defaultRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "Minosoft", "debug", "v1");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local == null || local.isBlank() ? home : local, "Minosoft", "debug", "v1");
        }
        String state = System.getenv("XDG_STATE_HOME");
        return Path.of(state == null || state.isBlank() ? Path.of(home, ".local", "state").toString() : state, "minosoft", "debug", "v1");
    }

    public Path root() { return root; }
    public Path endpoints() { return root.resolve("endpoints"); }
    public Path credentials() { return root.resolve("credentials"); }
    public Path runtime() { return runtime; }
    public Path endpoint(String id) { return endpoints().resolve(id + ".json"); }
    public Path credential(String name) { return credentials().resolve(name); }
    public Path socket(String id) { return runtime.resolve(shortHash(id) + ".sock"); }
    public String pipe(String id) { return "\\\\.\\pipe\\minosoft-debug-" + shortHash(id); }

    public void initialize() throws IOException {
        createPrivateDirectory(root);
        createPrivateDirectory(endpoints());
        createPrivateDirectory(credentials());
        createPrivateDirectory(runtime);
    }

    public static void createPrivateDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("debug directory must not be a symbolic link: " + path);
        }
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("debug path is not a private directory: " + path);
        }
        applyPermissions(path, DIRECTORY_PERMISSIONS);
    }

    public static void makePrivateFile(Path path) throws IOException {
        applyPermissions(path, FILE_PERMISSIONS);
    }

    private static void applyPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows security is applied by its named-pipe/ACL transport.
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int index = 0; index < 12; index++) result.append(String.format("%02x", digest[index]));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
