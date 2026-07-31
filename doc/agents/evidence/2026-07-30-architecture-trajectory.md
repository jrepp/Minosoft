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

### 4. Java 25 runtime and Gradle 10 readiness

Generated Minosoft launchers and all JVM test tasks receive
`--enable-native-access=ALL-UNNAMED`, covering LWJGL native calls on Java 25.
All repository-owned Gradle multi-string dependency declarations were converted
to Gradle 10-compatible notation, and the execution-time `Task.project` access
was removed. `./gradlew startScripts --warning-mode all` completes without a
deprecation warning. Commit: `6e42b35eb`.

The full JVM test run still reports a terminal `sun.misc.Unsafe` warning from
the external `de.bixilon:kutil:1.31` jar. That is an upstream dependency issue,
not an unqualified LWJGL native-access call or repository Gradle deprecation.

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
```

The opt-in compute/resource test created a hidden OpenGL 4.1 context but skipped
its 4.3 execution body on this Apple host, as required by the capability gate. A
non-Apple 4.3+ driver run remains necessary for compute/SSBO execution
acceptance; macOS fallback behavior already has recorded live 4.1 diagnostics.
