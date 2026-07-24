/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.protocol.network.session.play.tick

/** Runs one deterministic tick cycle while preserving per-task failure isolation. */
internal class SessionTickRunner(
    private val failureHandler: (Throwable) -> Unit = { it.printStackTrace() },
) {
    fun run(before: () -> Unit, tasks: List<Runnable>, after: () -> Unit) {
        try {
            before()
            for (task in tasks) {
                try {
                    task.run()
                } catch (error: Throwable) {
                    failureHandler(error)
                }
            }
        } finally {
            after()
        }
    }
}
