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
import java.util.concurrent.atomic.AtomicBoolean;

final class DebugTransportConnection implements AutoCloseable {
    final InputStream input;
    final OutputStream output;
    private final AutoCloseable closeable;
    private final AtomicBoolean open = new AtomicBoolean(true);

    DebugTransportConnection(InputStream input, OutputStream output, AutoCloseable closeable) {
        this.input = input;
        this.output = output;
        this.closeable = closeable;
    }

    @Override
    public void close() throws IOException {
        if (!open.compareAndSet(true, false)) return;
        try {
            closeable.close();
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("could not close debug transport", error);
        }
    }
}
