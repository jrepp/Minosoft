<!-- Copyright (C) 2026 Jacob Repp -->

# Architecture and trajectory checkpoint — 2026-07-30

## Scope

This checkpoint records five architecture follow-ups after the Java 25
migration, plus the OpenGL 4.3 assessment. It is implementation evidence, not a
claim that every remaining trajectory-tooling or render-substrate target is
complete.

## Accepted decisions

### 1. Central JVM conventions

Root build conventions configure Java toolchains, source/target/release, UTF-8,
and Kotlin JVM target 25 for every JVM subproject. Individual modules no longer
repeat that policy. Commit: `d0e000cae`.

### 2. Recoverable trajectory primitives

The Java play utility now provides:

- expiring, cross-process mutation leases (`5ee07b504`);
- bounded, hashed diagnosis bundles (`5d9d368c9`);
- stopped/saved-world immutable snapshots (`651848628`); and
- player-pose capture/mark/compare-and-restore checkpoints (`6e5db29ff`).

The implementation deliberately stops short of claiming live server save/flush,
all-command lease enforcement, prepared-reference diagnosis, or multi-field
checkpoints. Those remain explicit targets in the tooling backlog.

### 3. Dependency-clean headless render contracts

`render-contracts` owns immutable render graphs, typed resource and vertex
layout declarations, leased generation stores, single-override generation
ownership, and fixed-storage terrain telemetry. It has no application,
Minecraft, window, LWJGL, or OpenGL dependency. The application depends on the
module, not the reverse. Commits: `dad040ade`, `99602ac06`.

Terrain and shader registries now share `TransactionalOverrideStore`. The common
state machine makes current-registration removal, stale-handle no-op behavior,
fallback republication, frame leases, and deferred retirement one tested
contract. Candidate validation and GPU cleanup stay with their concrete owners.
The application and integration-test suites declare the module explicitly so
the application dependency does not become a transitive public API. Integration
suite wiring follow-up: `4c3c32c6b`.

### 4. Java 25 runtime and Gradle 10 readiness

Generated Minosoft launchers and all JVM test tasks receive
`--enable-native-access=ALL-UNNAMED`, covering LWJGL native calls on Java 25,
and explicitly allow the legacy `sun.misc.Unsafe` access still required by the
pinned external `de.bixilon:kutil:1.31` dependency. Test runtime classpaths use
SLF4J's no-op provider because the suites do not consume an SLF4J backend.
All repository-owned Gradle multi-string dependency declarations were converted
to Gradle 10-compatible notation, and the execution-time `Task.project` access
was removed. `./gradlew startScripts --warning-mode all` completes without a
deprecation warning. Commit: `6e42b35eb`.

The explicit Unsafe compatibility option is a pinned-dependency boundary, not
approval for new repository-owned Unsafe access. Remove it when kutil no longer
requires the unsupported API.

### 5. OpenGL 4.3 is preferred, not universal

Non-Apple GLFW negotiation now requests a 4.3 core context, then falls back to
the OpenGL 3.3 compatibility baseline. GLFW documents that context-version
hints are minimum compatibility requests and creation fails only when the
returned context is older, so a 4.3 request may return a newer context.
macOS keeps a 4.1 then 3.3 sequence because Apple documents OpenGL support only
through 4.1. The OpenGL 4.3 specification includes compute shaders and
shader-storage buffers; Minosoft continues to check actual capabilities and
implementation limits before realizing those resources.

Primary references:

- [GLFW context-version hints](https://www.glfw.org/docs/latest/window_guide.html#window_hints_ctx)
- [Apple Silicon Mac OpenGL 4.1 ceiling](https://developer.apple.com/videos/play/wwdc2020/10631/)
- [OpenGL 4.3 core specification](https://registry.khronos.org/OpenGL/specs/gl/glspec43.core.pdf)

Commit: `994a2cb41`.

## Follow-up audit

A line-by-line follow-up of the new stateful boundaries established these
additional durable conclusions:

- World snapshot containment must compare canonical source and output-parent
  identity, not lexical paths. Existing entries, including dangling symbolic
  links, are never replaced (`d73b6d1ef`, `64d90ca9f`).
- Lease creation rejects blank durable fields and sub-millisecond TTLs, and
  persisted tokens must match their filenames (`e67bd07ca`).
- An override fallback is retained across every republication and retired once
  only after registry closure and its final lease. This removed the terrain
  registry's eager built-in close (`3401a4c85`).
- Fixed terrain histograms derive sample counts from captured buckets, bounded
  gauges saturate, and invalid worker completion cannot corrupt the active
  count (`12d88faff`, `b59c20adf`).
- Render timing status takes one median/p95 snapshot per window. Experimental
  FPS presentation delegates the real timing snapshot instead of reporting
  zero samples, and shader cleanup runs outside the registry monitor
  (`eecf55d2e`, `0c23164b2`, `24ff9db26`).

## Verification

Passed checks:

```sh
./gradlew :play-util:test
./gradlew :render-contracts:test compileKotlin
./gradlew test
./gradlew help --warning-mode all
./gradlew startScripts --warning-mode all
./gradlew :test --tests '*OpenGlContextRequestTest'
MINOSOFT_OPENGL_IRIS_COMPUTE_TEST=true \
  ./gradlew :test --tests '*OpenGlIrisCustomResourceComputeTest'
./gradlew compileKotlin :render-contracts:test test integrationTest assemble \
  :debug-core:test :play-util:installDist :debug-server-fabric:remapJar
```

The opt-in compute/resource test created a hidden OpenGL 4.1 context but skipped
its 4.3 execution body on this Apple host, as required by the capability gate. A
non-Apple 4.3+ driver run remains necessary for compute/SSBO execution
acceptance; macOS fallback behavior already has recorded live 4.1 diagnostics.
