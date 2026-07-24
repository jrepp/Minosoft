/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;

public final class DebugOperationResult {
    private final JsonNode result;
    private final byte[] attachment;
    private final String attachmentType;
    private final String attachmentName;

    private DebugOperationResult(JsonNode result, byte[] attachment, String attachmentType, String attachmentName) {
        this.result = result;
        this.attachment = attachment == null ? null : Arrays.copyOf(attachment, attachment.length);
        this.attachmentType = attachmentType;
        this.attachmentName = attachmentName;
    }

    public static DebugOperationResult json(JsonNode result) {
        return new DebugOperationResult(result, null, null, null);
    }

    public static DebugOperationResult attachment(JsonNode result, byte[] bytes, String mediaType, String name) {
        if (bytes == null) throw new NullPointerException("bytes");
        if (mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("mediaType must not be blank");
        return new DebugOperationResult(result, bytes, mediaType, name);
    }

    public JsonNode result() { return result; }
    public boolean hasAttachment() { return attachment != null; }
    public byte[] attachment() { return attachment == null ? null : Arrays.copyOf(attachment, attachment.length); }
    public String attachmentType() { return attachmentType; }
    public String attachmentName() { return attachmentName; }
}
