<!-- Copyright (C) 2026 Jacob Repp -->

# Hot-reload acceptance protocol

## Scope

This protocol accepts the Java play parent's current **process-generation**
reload boundary. It does not prove in-process classloader disposal, Fabric binary
compatibility, mod activation, GPU correctness, or state migration.

Run it after changing `play.sh`, `util/play/Play.java`, client launch arguments,
pack preparation, watcher selection, or parent/child shutdown behavior.

## Preconditions

- Use Java 17 and a disposable local server/world.
- Preserve unrelated work. Make the failure probe as one reversible edit and
  restore it before continuing.
- Use a unique `--trajectory` and an out-of-source `MINOSOFT_MODPACK_STORE`.
- If testing parallel source roots, set `MINOSOFT_HOT_RELOAD_PATHS` before launch.

## Gates

1. **Initial ownership:** status reports non-null parent, server, and client PIDs;
   `serverPortOpen`, `serverDebugReady`, `serverGameReady`,
   `clientDebugReady`, `clientJoined`, and `clientRenderReady` are true.
2. **Canary recompile:** with `--canary`, changing the canary marker emits a
   `reloadKind=canary` candidate with a new content hash; the new marker appears
   in the client log. This lane builds `canaryModJar`, not `installDist`.
3. **Base-game candidate:** a valid `src/main` edit emits a
   `reloadKind=base` candidate; the client PID changes and server/parent PIDs do
   not. The loaded canary remains present after the swap.
4. **Failure preservation:** a deliberate compile error emits `candidate_failed`;
   parent, server, and client PIDs all remain unchanged.
5. **Recovery:** restoring valid source emits a later `candidate_activated` with
   a new client PID.
6. **Parallel trigger:** an event below a configured external watch root produces
   the same successful client-only swap.
7. **Pack truthfulness:** the pinned Sodium version reports
   `activation=adapted`, an empty blocker list, and its named adapter. Runtime
   logs contain `FABRIC_PACK_ACTIVE`, `SODIUM_HOOK_INSTALLED`, and
   `SODIUM_HOOK_INVOKED`. Other unsupported Fabric surfaces remain blocked.
8. **Stack ownership:** `fabric-stack` reports seven exact adapters with no
   blockers. In addition to the Fabric API surface it reports
   `chunk-render-scheduling`, `entity-visibility`, `frame-batching`,
   `container-screen-extensions`, `shader-pipeline`,
   `resource-reload-events`, and `recipe-viewer`. Runtime diagnostics show Iris
   installing a visible world post-process, crossing before-world-render, and
   surviving shader-reload completion; disabling and restoring
   `mods.iris.presentation` produces the visual before/after gate. JEI opens
   its synchronized-recipe screen. A base candidate logs
   `FABRIC_PACK_INACTIVE` for the old client before the replacement client
   activates and exposes the seven generation-owned providers again.
9. **Technical-pack activation:** `tech-reborn` reports its three exact adapters
   in dependency order with no blockers. Runtime logs contain
   `FABRIC_API_MODULES_ACTIVE`, `REBORN_ENERGY_ACTIVE`,
   `TECH_REBORN_CONTENT_ACTIVE`, and `FABRIC_PACK_ACTIVE`; shutdown contains
   `FABRIC_PACK_INACTIVE`. This accepts the source-native capability boundary,
   not upstream gameplay execution or a modded server.
10. **Clean shutdown:** `./play.sh stop` results in null managed PIDs, all
   readiness fields false, and empty debug endpoint discovery; the session ends
   with `parent_stopped`.

## Procedure

```sh
MINOSOFT_MODPACK_STORE=/absolute/temp/store \
MINOSOFT_HOT_RELOAD_PATHS=/absolute/parallel/root \
./play.sh --canary --modpack sodium --trajectory acceptance-YYYY-MM-DD
```

From another terminal, record the baseline and event session:

```sh
./play.sh status --json
tail -n 10 .run/play-events.jsonl
```

Change `MARKER` in the canary source and wait for a canary activation and matching
`HOT_RELOAD_CANARY` log line. Then apply one harmless base source edit and wait
for a base activation. Record status and compare detection-to-ready timings.
Replace the base edit briefly with a compile error, wait for `candidate_failed`,
and prove the three PIDs are unchanged. Restore the source exactly, wait for
activation, then touch or atomically publish beneath the external watch root and
prove one more client-only swap.

Run pack preflight and shut down:

```sh
./play.sh modpack inspect sodium --trajectory acceptance-YYYY-MM-DD
rg 'FABRIC_PACK_ACTIVE|SODIUM_HOOK_(INSTALLED|INVOKED)' .run/minosoft-client.log
./play.sh modpack inspect fabric-stack --trajectory acceptance-stack-YYYY-MM-DD
rg 'FABRIC_PACK_(ACTIVE|INACTIVE)|SODIUM_HOOK_|ENTITY_CULLING_HOOK_|IMMEDIATELY_FAST_HOOK_|INVENTORY_MANAGEMENT_HOOK_|IRIS_HOOK_|JEI_HOOK_' .run/minosoft-client.log
./play.sh debug request mods.iris.presentation '{"enabled":false}' --role client --trajectory acceptance-stack-YYYY-MM-DD --json
./play.sh debug visual capture /tmp/iris-disabled.png --role client --trajectory acceptance-stack-YYYY-MM-DD --json
./play.sh debug request mods.iris.presentation '{"enabled":true}' --role client --trajectory acceptance-stack-YYYY-MM-DD --json
./play.sh debug visual capture /tmp/iris-enabled.png --role client --trajectory acceptance-stack-YYYY-MM-DD --json
./play.sh debug request mods.iris.reload-shaders '{}' --role client --trajectory acceptance-stack-YYYY-MM-DD --json
./play.sh debug request mods.iris.summary '{}' --role client --trajectory acceptance-stack-YYYY-MM-DD --json
./play.sh debug request mods.jei.summary '{}' --role client --trajectory acceptance-stack-YYYY-MM-DD --json
./play.sh modpack inspect tech-reborn --trajectory acceptance-tech-YYYY-MM-DD
rg 'FABRIC_API_MODULES_ACTIVE|REBORN_ENERGY_ACTIVE|TECH_REBORN_CONTENT_ACTIVE|FABRIC_PACK_(ACTIVE|INACTIVE)' .run/minosoft-client.log
./play.sh stop
./play.sh status --json
```

## Evidence contract

`status --json` is the assertion surface:

```json
{"target":"both","parentPid":123,"serverPid":124,"clientPid":125,"serverPortOpen":true,"serverDebugReady":true,"serverGameReady":true,"serverReady":true,"clientDebugReady":true,"clientJoined":true,"clientRenderReady":true,"externalClientPids":[]}
```

`.run/play-events.jsonl` is append-only diagnostic history. Relevant ordered
events are `parent_started`, `server_port_open`, `server_debug_ready`,
`server_game_ready`, compatibility `server_ready`, `client_started`,
`client_debug_ready`,
`candidate_detected`, `candidate_ready` or `candidate_failed`,
`candidate_activated`, `parent_stopping`, and `parent_stopped`. Candidate events
include `reloadKind`; canary-ready/activated events also include `canaryHash`.

Create a dated summary in `doc/agents/evidence/` with environment, session ID,
PID transitions, measured durations, pack result, shutdown result, and any gate
that did not pass. Do not copy the raw runtime log into source control.
