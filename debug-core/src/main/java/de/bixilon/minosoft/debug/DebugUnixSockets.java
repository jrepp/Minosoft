/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

final class DebugUnixSockets {
    private DebugUnixSockets() {}

    static ServerSocketChannel bind(String path) throws IOException {
        try {
            ServerSocketChannel channel = (ServerSocketChannel) openMethod(ServerSocketChannel.class).invoke(null, unixFamily());
            try {
                channel.bind(address(path));
                return channel;
            } catch (IOException | RuntimeException error) {
                channel.close();
                throw error;
            }
        } catch (ReflectiveOperationException error) {
            throw transportFailure(error);
        }
    }

    static SocketChannel connect(String path) throws IOException {
        try {
            SocketChannel channel = (SocketChannel) openMethod(SocketChannel.class).invoke(null, unixFamily());
            try {
                channel.connect(address(path));
                return channel;
            } catch (IOException | RuntimeException error) {
                channel.close();
                throw error;
            }
        } catch (ReflectiveOperationException error) {
            throw transportFailure(error);
        }
    }

    private static Method openMethod(Class<?> channel) throws NoSuchMethodException {
        return channel.getMethod("open", ProtocolFamily.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ProtocolFamily unixFamily() throws ReflectiveOperationException {
        Class<? extends Enum> type = (Class<? extends Enum>) Class.forName("java.net.StandardProtocolFamily").asSubclass(Enum.class);
        return (ProtocolFamily) Enum.valueOf(type, "UNIX");
    }

    private static SocketAddress address(String path) throws ReflectiveOperationException {
        Class<?> type = Class.forName("java.net.UnixDomainSocketAddress");
        return (SocketAddress) type.getMethod("of", String.class).invoke(null, path);
    }

    private static IOException transportFailure(ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof IOException) return (IOException) cause;
        return new IOException("Unix-domain debug transport requires a Java 16+ runtime with Unix socket support", cause);
    }
}
