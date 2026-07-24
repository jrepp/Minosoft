<!-- Copyright (C) 2026 Jacob Repp -->

# Debug scenario and observability protocol

## Scope

`play.sh scenario run` is the repository-owned acceptance runner for a live
Minosoft/Fabric development trajectory. It uses the shared `DebugClient` and
version-one debug operations; it does not add a second game automation
transport. Runtime artifacts belong under `.run/acceptance/` and remain
untracked. Checked-in scenario definitions live under `acceptance/scenarios/`.

## Lifecycle predicates

Use `play.sh wait PREDICATE` directly or a scenario `wait` step:

| Predicate | Evidence |
| --- | --- |
| `server.port-open` | A TCP connection can be established. This is not game readiness. |
| `server.debug-ready` | The selected server endpoint authenticates and answers `core.status`. |
| `server.game-ready` | An owned Fabric server reports `core.status.ready=true`; an external server must answer a Minecraft status request. |
| `client.debug-ready` | The selected client endpoint authenticates and answers `core.status`. |
| `client.joined` | Client `core.status.ready=true` and the session is playing. |
| `client.render-ready` | The client has an active render context in addition to a live endpoint. |
| `both.ready` | Server game-ready and client render-ready are simultaneously true. |

Managed Fabric startup requires the endpoint for the exact server PID. Managed
client startup similarly waits for the exact client PID. `status --json`
publishes every state separately and keeps `serverReady` as a compatibility
alias for `serverGameReady`.

## Scenario schema

A scenario is JSON with `name` and `steps`. Supported step types are:

- `wait`: `predicate` plus optional `timeout` (`500ms`, `30s`, `2m`, or `1h`);
- `request`: `role`, `operation`, optional `body`/`deadlineMs`, and optional
  JSON-Pointer assertions using `exists`, `equals`, `contains`, `min`, or `max`;
- `screenshot`: captures through `visual.capture`, compares a checked baseline,
  and enforces `pixelThreshold`, `maxChangedRatio`, and `maxMeanError`;
- `sleep`: a bounded interval used by soak sampling.

Top-level `matrix` expands scalar variables referenced as `${name}`. `repeat`
runs each matrix case repeatedly. `duration` repeats complete cycles until its
bounded soak deadline. Expansions are capped at 64 cases per cycle, repeat at
10,000, total retained cases at `maxCases` (10,000 by default, 100,000 maximum),
and total duration at 24 hours. A duration-based soak stops at its first failed
case so a failed step cannot bypass a later sampling interval and spin until
the deadline.

Examples:

```sh
./play.sh scenario run acceptance/scenarios/runtime-smoke.json \
  --trajectory acceptance-main --json

./play.sh scenario run acceptance/scenarios/observability-matrix.json \
  --trajectory acceptance-main --json

./play.sh scenario run acceptance/scenarios/runtime-soak.json \
  --trajectory acceptance-main --json
```

`--update-screenshots` is the only baseline-writing path. A normal run fails
when a baseline is missing. Standalone image comparison is also available:

```sh
./play.sh screenshot compare baseline.png actual.png \
  --pixel-threshold 8 --max-changed-ratio 0.01 --max-mean-error 1 --json
```

## Artifacts and JFR

Every completed scenario writes:

```text
.run/acceptance/<run-id>/
├── report.json
├── junit.xml
├── metrics/client.json
├── metrics/server.json
├── screenshots/
└── jfr/
```

`report.json` contains case variables, step results, assertions, durations,
warnings, and JFR disposition. JUnit has one test case per expanded/repeated
case. Metrics are final `metrics.snapshot` responses from both endpoints.

JFR modes are `off`, `always`, `on-failure`, and `slow`. A requested recording
starts through the Java 17 `jcmd` adjacent to the play runtime. It is always
stopped; only the selected outcome retains `.jfr` files. Attach/dump failures
are explicit report warnings rather than hidden scenario transport fallbacks.

## Metrics contract

Both roles expose `metrics.snapshot` from `debug-core`. It contains:

- endpoint identity, start/uptime, and a maximum of 256 operation series;
- success/error/timeout/cancelled counters and total/max latency;
- 15 fixed latency upper bounds plus one overflow bucket per operation;
- role-owned runtime gauges (client frames/FPS/frame and draw averages/queue
  depth/session counts; server ticks/player/world counts).

Histograms retain counters only, never raw observations. Excess operation names
are combined into `_overflow`.

## World-generation inspection

`play.sh worldgen inspect [WORLD]` reads `level.dat` and overworld Anvil region
files without loading or mutating chunks. It reports seed, enabled datapacks,
chunk/full/error counts, biome palettes, surface distribution/relief/gradient,
and a canonical SHA-256 over chunk coordinates/status, section biome/block
palettes and packed data, and heightmaps. The default scan is bounded to 4,096
chunks and accepts an explicit `--max-chunks`.

`play.sh worldgen compare BASELINE CANDIDATE` requires equal seeds, equal sampled
chunk coordinates, and equal canonical terrain hashes. `--allow-different-seed`
only relaxes the seed gate; it does not relax terrain equality. Compare stopped,
fully saved worlds with the same generated chunk set for deterministic A/B
evidence.

## Validation

1. `core.capabilities` on both roles includes `metrics.snapshot`.
2. `runtime-smoke.json` passes and writes JSON, JUnit, both metrics, and (when
   requested) readable non-empty JFR files.
3. `observability-matrix.json` produces ten passing cases.
4. A missing screenshot baseline produces a failed JUnit case, retains the
   captured actual, and triggers `on-failure` JFR.
5. World inspection reports no failed chunks; same-input comparison passes and
   different-seed/different-terrain comparison fails.
6. Clean shutdown removes both endpoint descriptors and credentials.
