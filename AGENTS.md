# Agent guidance

This file is deliberately small. Use it as the working contract, then read the
relevant evidence maps in [`doc/agents/`](doc/agents/README.md) before changing
code. Repository-wide contribution, review, and commit expectations are in
[`Contributing.md`](Contributing.md).

## Start here

1. Read the layer index and select every map the task crosses. Start at the
   earliest affected layer when a boundary is unclear.
2. Inspect the referenced source and tests. Grounding documents are maps, not a
   substitute for current code.
3. For Java or Kotlin changes, follow the
   [JVM implementation guidance](doc/agents/guides/java-kotlin.md).
4. Keep the change scoped and preserve unrelated work already in the tree.

## Working contract

- Follow the task, the nearest applicable `AGENTS.md`, and the selected evidence
  maps. If documentation and code disagree, verify behavior and update or flag
  the stale grounding.
- Preserve Minosoft's multi-version and headless behavior unless the task
  explicitly changes it.
- Preserve valid copyright and license notices. For each new, nontrivial file
  whose format supports a header, copy the nearest applicable project GPL
  license notice and credit its actual author; use `Copyright (C) 2026 Jacob
  Repp` for Jacob Repp's 2026 work. Cover comment-hostile files through the
  nearest directory-level or project-level license notice instead of inserting
  invalid syntax. In an existing file, add a separate `Copyright (C) 2026 Jacob
  Repp` line only for a substantial, original contribution such as a new
  feature, coherent implementation, or major test/documentation body. Do not
  add or extend a copyright line for a small patch, mechanical change, or minor
  refactor, and never replace another holder's valid notice.
- Do not hand-edit generated output or local runtime state (`build/`, `.gradle/`,
  `.run/`, `it/`, or `server/`) unless the task explicitly targets it.
- Keep pack definitions under `modpacks/`; keep third-party artifacts and
  trajectory state in the configured out-of-source modpack store.
- For play-parent or reload changes, use `./play.sh status --json` and the
  hot-reload acceptance protocol. Copy durable conclusions into `doc/agents/`;
  do not commit raw `.run/` lifecycle logs.
- For debug-channel changes, read `doc/agents/areas/09-debug-control-plane.md`.
  Keep wire/transport behavior in `debug-core`, consume it through the shared
  `DebugClient`, and verify both endpoint cleanup and `core.capabilities`.
- Put focused tests beside the behavior they cover. Use
  `src/integration-test/kotlin` when real assets, packet fixtures, or subsystem
  integration are required.
- Update the relevant evidence map when a change moves an entry point, changes
  an invariant, establishes a decision, or alters the recommended validation.
  Do not record temporary implementation detail.

## Live-runtime contract

- Before mutating a running client or server, resolve the exact trajectory and
  roles with `./play.sh status --json`, then record the endpoint generation,
  player dimension/position/yaw/pitch, and any task-relevant world,
  presentation, shader-pack, and resource state needed for restoration.
- A trajectory isolates client home/profile/mod state. It does **not** isolate
  the default dedicated-server process, port, `server/` directory,
  `server.properties`, or selected level. Treat dedicated-server mutations as
  shared unless the launch explicitly uses a separate server instance.
- Diagnose visual failures in this order: clear transient GUI state with
  `visual.prepare-reference`, capture the framebuffer, sample player/world
  state, inspect loaded blocks around the camera, inspect `render.substrate`,
  then run reversible shader/entity/particle A/B checks. Do not change renderer
  code until pose, embedding/submersion, GUI, and acceptance-fixture causes are
  ruled out.
- Diagnose performance only with a named, repeatable workload and matched
  trajectory, generation, pose, world, presentation/shader, fixture, window,
  warm-up, and work-count or duration state. Capture counters before and after
  each interval and compare deltas; do not compare cumulative totals from
  processes with different uptime or use FPS, one frame, or one screenshot as
  sufficient performance evidence.
- Use `metrics.snapshot` for low-cost polling and request detailed
  `render.substrate` state only at measurement boundaries. For terrain, report
  phase sample counts and median/p95 together with
  queue/outstanding/upload depth, current and cumulative worker utilization,
  cancellation/staleness/rejection, visible sections, output/upload bytes, and
  upload time. Treat histogram percentiles as bucket bounds, not exact
  observations. Follow the
  [scenario performance-measurement guidance](doc/agents/acceptance/scenarios.md#performance-measurement-guidance)
  for sample sufficiency and artifact requirements.
- Keep instrumentation overhead separate from candidate performance. At an
  idle terrain boundary, interleave identical
  `render.terrain-telemetry`-disabled/enabled workloads and reject the
  instrumentation if p95 exceeds the disabled result by more than 10 percent
  plus 100 microseconds. Then compare baseline/candidate builds with the same
  instrumentation state. Prefer fixed primitive counters/histograms, numeric
  keys, or explicitly reported sampling; do not add unsampled per-frame/draw
  strings, maps, allocation-heavy traces, or logging to diagnose a hot path.
- Re-resolve endpoints after every client/server generation change. If another
  session changes live state, use compare-and-restore behavior where available;
  do not overwrite state whose current owner or value is unknown.
- Do not treat a live Anvil read as deterministic evidence. Gracefully stop and
  save the server, or use an accepted snapshot boundary, before
  `worldgen inspect`/`compare`; an `EOFException` from an active region is a
  raced read, not terrain corruption.
- Keep animated/checked-pixel fixtures out of ordinary play packs. Mount them
  only for an explicitly named acceptance trajectory and verify the active
  fixture list before diagnosing time-varying materials.
- At handoff, either restore the recorded live invariants or clearly identify
  the intentional new state. Confirm readiness with `status --json`, leave no
  transient reference/canary override active, and link durable artifacts rather
  than raw lifecycle logs.

## Build and verification

CI runs on Java 17 while emitted bytecode targets Java 11. Prefer a Java 17
runtime for Gradle.

```sh
./gradlew compileKotlin
./gradlew test
./gradlew integrationTest
./gradlew assemble
./gradlew :debug-core:test :play-util:installDist :debug-server-fabric:remapJar
```

Run the smallest relevant check first, then broaden in proportion to risk.
Document checks you could not run and why.
