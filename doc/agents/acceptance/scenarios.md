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
- `screenshot`: captures through `visual.capture`, optionally requires exact
  `sourceWidth`/`sourceHeight`, optionally crops a top-left
  `region: [x,y,width,height]`, compares a checked baseline, and enforces
  `pixelThreshold`, `maxChangedRatio`, and `maxMeanError`;
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

./play.sh scenario run acceptance/scenarios/animated-java-rejected-reload.json \
  --trajectory animated-java-rejected-reload --json

./play.sh scenario run acceptance/scenarios/emf-etf-zombie-render-reference.json \
  --trajectory emf-etf-render-reference --json

./play.sh scenario run acceptance/scenarios/naturalist-controller-state.json \
  --trajectory naturalist-controller-state --json
```

The Animated Java rejection scenario is platform-qualified to the checked
macOS Retina reference. It uses bounded `rejectAt` body checkpoints on
`render.reload-content` rather than editing mounted fixture files, so candidate allocation,
publication rollback, exact pixels, data-pack availability, and live OpenGL
balance are asserted in one reproducible lane.

The EMF/ETF zombie scenario applies the same transaction checkpoints to one
fixed living-entity scene. ETF selects rule 1 from health NBT, EMF consumes the
pinned public `rule_index` value, and a targeted CEM part composes with the
native zombie model. The scene waits for spawn-light interpolation before
using zero screenshot tolerance; immediate lighting interpolation is not
treated as z-fighting.

The Naturalist controller scenario uses a local-authority dependent-mod entity
definition to make the source-native Gecko consumer deterministic without
inventing a remote wire ID. It asserts the retained geometry/texture, one
252-vertex body pass, four independent controller summaries, the exact nearby-
player rattle loop, timeline continuity across production content reload, and
fixture removal. It is a real-GL controller/topology gate, not a screenshot
baseline and not evidence for remote packet timing, AI sound, or seam pixels.

`--update-screenshots` is the only baseline-writing path. A normal run fails
when a baseline is missing. Standalone image comparison is also available:

```sh
./play.sh screenshot crop full-frame.png reference-region.png \
  --region 1100,900,550,550 --json

./play.sh screenshot compare baseline.png actual.png \
  --pixel-threshold 8 --max-changed-ratio 0.01 --max-mean-error 1 --json
```

`visual.prepare-reference` is a bounded client render operation for checked
captures. It clears transient GUI overlays and can disable the HUD,
non-persistent entity-hitbox rendering, and moving clouds without opening a
screen or mutating gameplay. `hideHud`, `hideHitboxes`, and `hideClouds`
default to true. Reference scenarios should call it explicitly instead of
assuming a newly launched or focused window. Multi-capture scenarios call it
again immediately before each checked screenshot: on hosts that pause when the
window loses focus, a prior capture/debug round trip can reopen a pause or
settings screen even though the initial reference preparation succeeded.

Before accepting or updating a visual baseline, record `client.player`,
`client.world`, `mods.iris.presentation`, and `render.substrate`. Require the
intended dimension/pose, then sample loaded blocks around the camera when a
capture is blank, occluded, submerged, or surrounded by unexpected faces.
Baseline updates are invalid when the player moved, the camera is embedded, a
GUI overlay reopened, or an acceptance-only animated fixture is mounted.
Time-varying failures require a fixed-pose burst or repeated `visual.sample`
region observations; one frame cannot distinguish shader history, material
animation, and world-time changes.

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
starts through the Java 25 `jcmd` adjacent to the play runtime. It is always
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

## Performance measurement guidance

Performance evidence must identify the exact workload and preserve every input
that can affect it: trajectory and endpoint generation, Minecraft version,
dimension and player/camera pose, loaded world area, presentation and shader
generation, resource/fixture set, window state, and work count or interval
duration. Warm the candidate until generations, queues, and resource counts are
stable before starting a measured interval. Use a fixed work count when
possible; otherwise use equal fixed durations.

Capture `metrics.snapshot` immediately before and after each interval and
compare counter deltas. Its counters are cumulative and are not comparable
across processes with different uptime. Use it for repeated low-cost polling;
request `render.substrate` only at interval boundaries because that operation
materializes human-readable maps, sorted diagnostics, and detailed histogram
arrays. Do not infer a performance result from FPS alone, a single frame,
maximum latency, or a screenshot.

For percentile claims:

1. Record warm-up separately from measured samples.
2. Require at least 100 samples for a p95 claim. If the production phase cannot
   produce 100 comparable samples, report the result as underpowered rather
   than claiming no regression.
3. Report sample count, median, p95, maximum, and the histogram bounds used.
   Fixed-histogram percentiles are upper bucket bounds, not exact raw values.
4. Interleave baseline and candidate intervals to limit thermal, JIT, world,
   and background-load drift. Preserve the same instrumentation state for that
   comparison.
5. Retain the JSON/JUnit artifacts and report both absolute values and relative
   change. A faster median does not excuse a material p95 regression.

Terrain performance is available as a compact `terrain` object in the client
`metrics.snapshot`; full fixed buckets are in
`render.substrate.terrain.productionRuntime`. Record:

- queue depth, outstanding builds, pending uploads, and their high-water marks;
- configured/current workers plus current and cumulative utilization;
- requested, started, successful, cancelled, stale, rejected, and failed work;
- requested, started, and duplicate-suppressed counts for every fixed terrain
  build cause;
- visible section count and produced/uploaded bytes; and
- queue-wait, snapshot-capture, mesh-build, worker-busy, visibility, and real
  upload sample counts and median/p95.

Measure monitoring overhead independently of a renderer change. Wait for zero
queued/outstanding builds and uploads, then alternate identical workloads with
`render.terrain-telemetry` disabled and enabled. The repository guard rejects
enabled p95 above disabled p95 by more than 10 percent plus 100 microseconds.
After that gate passes, compare baseline and candidate builds with telemetry in
the same state. The toggle deliberately rejects non-idle boundaries so a phase
cannot straddle the A/B interval.

Hot-path diagnostics must remain bounded. Prefer fixed primitive
counters/histograms and precomputed numeric route IDs. High-cardinality detail
must use an explicit sample rate and expose that rate beside the result. Do not
introduce unsampled per-frame or per-draw strings, maps, stack traces, or log
records and then use the instrumented run as performance evidence.

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

Do not inspect an actively written world as acceptance evidence. Region
allocation/header writes can race the read and produce `EOFException` or an
internally inconsistent chunk set. Gracefully stop/save first until the
repository provides the bounded snapshot command proposed in the
[agent trajectory tooling backlog](../backlog/agent-trajectory-tooling.md).

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
