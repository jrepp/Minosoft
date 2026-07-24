/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Locale;

final class DebugTransports {
    private DebugTransports() {}

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static String name() { return windows() ? "windows-pipe" : "unix"; }

    static String address(DebugPaths paths, String id) { return windows() ? paths.pipe(id) : paths.socket(id).toString(); }

    static DebugTransportListener listen(String transport, String address) throws IOException {
        if ("windows-pipe".equals(transport)) return DebugWindowsPipes.listen(address);
        if (!"unix".equals(transport)) throw new IOException("unsupported debug transport: " + transport);
        ServerSocketChannel channel = DebugUnixSockets.bind(address);
        return new DebugTransportListener() {
            @Override
            public DebugTransportConnection accept() throws IOException {
                SocketChannel accepted = channel.accept();
                return connection(accepted);
            }

            @Override public boolean isOpen() { return channel.isOpen(); }
            @Override public void close() throws IOException { channel.close(); }
        };
    }

    static DebugTransportConnection connect(String transport, String address) throws IOException {
        if ("windows-pipe".equals(transport)) return DebugWindowsPipes.connect(address);
        if (!"unix".equals(transport)) throw new IOException("unsupported debug transport: " + transport);
        return connection(DebugUnixSockets.connect(address));
    }

    private static DebugTransportConnection connection(SocketChannel channel) {
        InputStream input = Channels.newInputStream(channel);
        OutputStream output = Channels.newOutputStream(channel);
        return new DebugTransportConnection(input, output, channel);
    }
}
