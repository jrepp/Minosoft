/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;

public final class DebugResponse {
    private final JsonNode result;
    private final byte[] attachment;
    private final String attachmentType;
    private final String attachmentName;

    public DebugResponse(JsonNode result, byte[] attachment, String attachmentType, String attachmentName) {
        this.result = result;
        this.attachment = attachment == null ? null : Arrays.copyOf(attachment, attachment.length);
        this.attachmentType = attachmentType;
        this.attachmentName = attachmentName;
    }

    public JsonNode result() { return result; }
    public boolean hasAttachment() { return attachment != null; }
    public byte[] attachment() { return attachment == null ? null : Arrays.copyOf(attachment, attachment.length); }
    public String attachmentType() { return attachmentType; }
    public String attachmentName() { return attachmentName; }
}
