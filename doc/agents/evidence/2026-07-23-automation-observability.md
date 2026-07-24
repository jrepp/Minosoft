<!-- Copyright (C) 2026 Jacob Repp -->

# Automation and observability evidence

Date: 2026-07-23 (HST)

## Implemented boundary

The Java play utility now distinguishes server port-open, debug-ready, and
game-ready states plus client debug-ready, joined, and render-ready states.
Managed Fabric readiness is selected by exact PID and requires its
`core.status.ready` result. External servers fall back to a real Minecraft
status handshake rather than accepting a TCP connect as completed startup.

`play.sh wait` exposes those states as deadline-bounded predicates.
`status --json` exposes all six booleans; the legacy `serverReady` field now
means game-ready.

The same utility runs JSON scenarios through `DebugClient`. It emits one
`report.json` and one JUnit suite, captures final endpoint metrics, supports
visual baselines, matrix/repeat/soak execution, and optionally starts/dumps/stops
JFR with `jcmd`. No new socket, credential, operation framing, or OS input path
was introduced.

## Live lifecycle evidence

The old supervised session was stopped cleanly. Immediately afterward,
`status --json` returned null managed PIDs and all readiness fields false, while
`debug endpoints --json` returned an empty array.

Session `2026-07-24T05:43:52.606871Z-66555` then started:

- parent PID `66555`;
- Fabric server PID `66593`;
- initial client PID `66761`.

Its event order was:

```text
server_port_open
server_debug_ready
server_game_ready
server_ready semantic=game_ready
client_started
client_debug_ready
```

The server took about 5.17 seconds from port-open to debug/game readiness.
Following a normal base hot reload, client generation 2/PID `67954` replaced
generation 1 while parent/server stayed stable. `client.render-ready` passed,
and final status reported every readiness field true.

After the final build, a fresh supervised session started with parent/server/
client PIDs `97275`/`97311`/`97469`. `both.ready` passed, `status --json`
reported all seven readiness booleans true, both capabilities advertised
`metrics.snapshot`, and both snapshots returned all 15 fixed latency bounds
plus live role-specific gauges.

## Metrics and scenario evidence

Both live `core.capabilities` responses advertised `metrics.snapshot`.
Snapshots returned the same fixed 15 upper bounds plus overflow bucket,
operation outcome/latency counters, and role-specific runtime gauges. The
server showed ready/tick/player/world values; the client showed active frame,
FPS, average frame/draw time, queue depth, and playing-session counts.

`runtime-smoke` run
`runtime-smoke-2026-07-24T05-44-28-725567Z-66995` passed all six steps. Its
artifact set contained:

- passing JSON and JUnit reports;
- client and server metrics snapshots;
- client JFR (722,989 bytes);
- server JFR (449,997 bytes).

The final-source rerun
`runtime-smoke-2026-07-24T06-10-52-976061Z-82991` also passed. Its report
explicitly recorded `jfrMode=always`, two started and retained recordings, and
no warnings. The client/server JFR files were 634,714/446,032 bytes; `jfr
summary` read both as version 2.1 one-second recordings.

`observability-matrix` run
`observability-matrix-2026-07-24T05-44-45-643243Z-67189` expanded client/server
roles across five repetitions and produced ten passing JUnit cases.
The final-source rerun
`observability-matrix-2026-07-24T06-10-54-782077Z-82990` likewise passed all
ten cases.

The deliberate missing-baseline run
`screenshot-example-2026-07-24T05-45-02-325090Z-67328` failed truthfully. It
retained the 4,092,768-byte actual framebuffer, emitted a failed JUnit case with
the missing baseline path, wrote both metrics snapshots, and retained both
on-failure JFR recordings. The focused play utility test independently proved
zero-error PNG comparison for identical input.

An intentionally disruptive soak exposed a runner-boundary issue: a client hot
reload could fail a case before its final sleep step, after which the old
installed runner cycled rapidly until the duration deadline. Duration-based
soaks now stop at their first failed case, and every run also has a bounded
`maxCases`. A focused one-minute failure probe exited after one 74 ms case,
wrote the failed JSON/JUnit artifacts, and reported
`Soak stopped after the first failed case.` instead of spinning.

The subsequent 15-minute run
`runtime-soak-2026-07-24T06-11-10-036519Z-83155` passed 175 cases in 901,642
ms while unrelated source edits exercised several real client hot reloads.
It retained final client/server metrics, JSON and JUnit, and a valid
31,917,117-byte server JFR covering 907 seconds. The report explicitly warned
that the original client PID had been replaced and could no longer be attached
for dump/stop; the run did not hide or fabricate a client recording.

## World-generation evidence

The new read-only Java Anvil inspector scanned the active Terratonic world with
zero chunk parse failures. A 1,500-chunk bounded pass found the enabled
`tectonic/terratonic` and `terralith` datapacks, both vanilla and Terralith biome
palettes, 268,032 decoded surface columns, Y=62..198 in that bounded selection,
58-block maximum within-chunk relief, and 43-block maximum adjacent gradient.
The canonical terrain hash was
`897d8794f52a9cf3e956ff6ffa47db3ca34e5eb5cbbf18499ce0231e51e8bf54`.

A stopped-input-equivalent 256-chunk self comparison passed seed, coordinate,
and terrain-hash equality. Comparing the pre-Terratonic backup to the active
world failed all three gates and exited nonzero: their seeds, sampled chunk
coordinates, and canonical terrain hashes differed. This validates both pass
and fail behavior; a causal terrain A/B claim still requires two separately
generated, stopped worlds with the same seed and chunk set.

## Automated checks

The final Java 17 broad pass, rerun after concurrent unrelated inventory-source
edits settled, completed successfully:

- root `test`: 1,497 tests, zero failures/errors;
- `integrationTest`: 2,031 tests, zero failures/errors;
- `assemble`;
- `:debug-core:test`;
- `:play-util:installDist`;
- `:debug-server-fabric:remapJar`.

An earlier integration pass under simultaneous game/JFR/soak load saw the
timing-sensitive `KeyHandlerTest.tick twice` assertion once; its focused rerun
passed, and the unloaded full integration pass above then passed all 2,031
tests. `LogArgumentTest` now resets process-global log options and honors the
standard inherited `NO_COLOR` environment setting, so the full unit suite is
repeatable in this shell.
