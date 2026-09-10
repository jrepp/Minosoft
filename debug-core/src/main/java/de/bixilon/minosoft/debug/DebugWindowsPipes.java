/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.sun.jna.Pointer;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class DebugWindowsPipes {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int SDDL_REVISION_1 = 1;

    private DebugWindowsPipes() {}

    private interface SecurityApi extends StdCallLibrary {
        SecurityApi INSTANCE = Native.load("Advapi32", SecurityApi.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ConvertStringSecurityDescriptorToSecurityDescriptor(
            String descriptor, int revision, PointerByReference securityDescriptor, IntByReference descriptorSize);
    }

    static DebugTransportListener listen(String address) throws IOException {
        return new Listener(address);
    }

    static DebugTransportConnection connect(String address) throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(address,
                WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null);
            if (!WinBase.INVALID_HANDLE_VALUE.equals(handle)) return connection(handle);
            int error = Kernel32.INSTANCE.GetLastError();
            if (error != WinError.ERROR_PIPE_BUSY || !Kernel32.INSTANCE.WaitNamedPipe(address, 250)) {
                if (error != WinError.ERROR_PIPE_BUSY) throw winError("could not connect to named debug pipe", error);
            }
        }
        throw new IOException("timed out waiting for named debug pipe: " + address);
    }

    private static final class Listener implements DebugTransportListener {
        private final String address;
        private boolean open = true;
        private boolean accepting;
        private WinNT.HANDLE pending;
        private WinNT.HANDLE acceptingHandle;
        private CompletableFuture<Void> acceptFinished;

        private Listener(String address) throws IOException {
            this.address = address;
            pending = createPrivatePipe(address);
        }

        @Override
        public DebugTransportConnection accept() throws IOException {
            WinNT.HANDLE handle;
            synchronized (this) {
                if (!open) throw new IOException("named debug pipe is closed");
                if (accepting) throw new IOException("named debug pipe already has an accept in progress");
                accepting = true;
                acceptFinished = new CompletableFuture<>();
                handle = pending;
                acceptingHandle = handle;
            }
            try {
                boolean connected = Kernel32.INSTANCE.ConnectNamedPipe(handle, null);
                int error = connected ? WinError.ERROR_SUCCESS : Kernel32.INSTANCE.GetLastError();
                synchronized (this) {
                    // The accepting thread retains ownership until the native call returns.
                    if (!open) throw new IOException("named debug pipe is closed");
                    pending = null;
                    try {
                        if (!connected && error != WinError.ERROR_PIPE_CONNECTED) {
                            throw winError("could not accept named debug pipe", error);
                        }
                        // Keep the next instance ready before handing this connection off.
                        pending = createPrivatePipe(address);
                        return connection(handle);
                    } catch (IOException | RuntimeException errorDuringAccept) {
                        open = false;
                        Kernel32.INSTANCE.CloseHandle(handle);
                        throw errorDuringAccept;
                    }
                }
            } finally {
                synchronized (this) {
                    if (!open && pending == handle) {
                        pending = null;
                        Kernel32.INSTANCE.CloseHandle(handle);
                    }
                    accepting = false;
                    acceptingHandle = null;
                    acceptFinished.complete(null);
                }
            }
        }

        @Override public synchronized boolean isOpen() { return open; }

        @Override
        public void close() {
            WinNT.HANDLE handle;
            CompletableFuture<Void> finished;
            synchronized (this) {
                if (!open) return;
                open = false;
                handle = pending;
                finished = accepting && handle == acceptingHandle ? acceptFinished : null;
                if (finished == null) pending = null;
            }
            if (handle == null) return;
            if (finished == null) {
                Kernel32.INSTANCE.CloseHandle(handle);
                return;
            }
            // CloseHandle waits for a synchronous ConnectNamedPipe. A local connection
            // releases that call, including the race where accept has not entered it yet.
            WinNT.HANDLE wake = Kernel32.INSTANCE.CreateFile(address,
                WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null);
            try {
                // The accepting thread closes its handle before completing this future.
                finished.join();
            } finally {
                if (!WinBase.INVALID_HANDLE_VALUE.equals(wake)) Kernel32.INSTANCE.CloseHandle(wake);
            }
        }
    }

    private static WinNT.HANDLE createPrivatePipe(String address) throws IOException {
        PointerByReference descriptor = new PointerByReference();
        boolean converted = SecurityApi.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptor(
            "D:P(A;;GA;;;OW)(A;;GA;;;SY)", SDDL_REVISION_1, descriptor, null);
        if (!converted) throw winError("could not create owner-only named-pipe security descriptor", Kernel32.INSTANCE.GetLastError());
        Pointer pointer = descriptor.getValue();
        try {
            WinBase.SECURITY_ATTRIBUTES security = new WinBase.SECURITY_ATTRIBUTES();
            security.dwLength = new WinNT.DWORD(security.size());
            security.lpSecurityDescriptor = pointer;
            security.bInheritHandle = false;
            security.write();
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateNamedPipe(address,
                WinBase.PIPE_ACCESS_DUPLEX,
                WinBase.PIPE_TYPE_BYTE | WinBase.PIPE_READMODE_BYTE | WinBase.PIPE_WAIT,
                255, BUFFER_SIZE, BUFFER_SIZE, 0, security);
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                throw winError("could not create named debug pipe", Kernel32.INSTANCE.GetLastError());
            }
            return handle;
        } finally {
            Kernel32.INSTANCE.LocalFree(pointer);
        }
    }

    private static DebugTransportConnection connection(WinNT.HANDLE handle) {
        AtomicBoolean open = new AtomicBoolean(true);
        InputStream input = new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (length == 0) return 0;
                byte[] buffer = offset == 0 && length == target.length ? target : new byte[length];
                IntByReference read = new IntByReference();
                if (!Kernel32.INSTANCE.ReadFile(handle, buffer, length, read, null)) {
                    int error = Kernel32.INSTANCE.GetLastError();
                    if (error == WinError.ERROR_BROKEN_PIPE || error == WinError.ERROR_NO_DATA) return -1;
                    throw winError("could not read named debug pipe", error);
                }
                int count = read.getValue();
                if (buffer != target && count > 0) System.arraycopy(buffer, 0, target, offset, count);
                return count == 0 ? -1 : count;
            }
        };
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }

            @Override
            public void write(byte[] source, int offset, int length) throws IOException {
                byte[] buffer = offset == 0 && length == source.length ? source : Arrays.copyOfRange(source, offset, offset + length);
                int writtenTotal = 0;
                while (writtenTotal < buffer.length) {
                    byte[] remaining = writtenTotal == 0 ? buffer : Arrays.copyOfRange(buffer, writtenTotal, buffer.length);
                    IntByReference written = new IntByReference();
                    if (!Kernel32.INSTANCE.WriteFile(handle, remaining, remaining.length, written, null)) {
                        throw winError("could not write named debug pipe", Kernel32.INSTANCE.GetLastError());
                    }
                    if (written.getValue() < 1) throw new IOException("named debug pipe wrote zero bytes");
                    writtenTotal += written.getValue();
                }
            }
        };
        return new DebugTransportConnection(input, output, () -> {
            if (!open.compareAndSet(true, false)) return;
            // This instance is never reused. DisconnectNamedPipe would discard an
            // unread final response; closing our handle lets the peer drain it.
            Kernel32.INSTANCE.CloseHandle(handle);
        });
    }

    private static IOException winError(String message, int code) {
        return new IOException(message + " (Windows error " + code + ")");
    }
}
