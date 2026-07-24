/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import java.io.IOException;

public class DebugClientException extends IOException {
    private final String code;

    public DebugClientException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
