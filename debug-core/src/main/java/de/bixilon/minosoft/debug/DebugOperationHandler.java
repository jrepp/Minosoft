/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.debug;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface DebugOperationHandler {
    CompletionStage<DebugOperationResult> handle(DebugRequestContext context, JsonNode body);
}
