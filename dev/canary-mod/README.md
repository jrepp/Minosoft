# Hot-reload canary mod

This is a source-controlled native Minosoft mod used to prove the mod-only
recompile lane. Built JARs are not stored here: `./play.sh dev --canary`
publishes immutable, content-addressed artifacts under `MINOSOFT_MODPACK_STORE`.

Change `MARKER` in `HotReloadCanary.kt` while the parent is running. A successful
candidate rebuild should log the new marker, replace only the client PID, retain
the server/parent PIDs, and record `reloadKind=canary` in
`.run/play-events.jsonl`.
