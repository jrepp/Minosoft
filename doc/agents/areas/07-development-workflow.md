<!-- Copyright (C) 2026 Jacob Repp -->

# Development-workflow evidence map

## Boundary

The development layer owns repeatable build/test/run feedback, diagnostics, and
three iteration loops: resource reload, classloader-generation reload, and
debugger class redefinition. Mod generation details are specified in the next
map.

## Evidence map

| Status | Claim | Evidence |
| --- | --- | --- |
| Verified | Gradle provides compile, unit, integration, run, assemble, and fat-jar workflows. | `build.gradle.kts` and both CI definitions. |
| Verified | CI runs Java 17 across Linux, Windows, and macOS; output targets JVM 11. | CI setup and Gradle Java/Kotlin targets. |
| Observed | Main/test Kotlin paths are unconventional. | Main is under `src/main/java`; unit tests are under `src/test/java`; integration tests are under `src/integration-test/kotlin`. |
| Observed | Standard JVM redefinition preserves existing instances/static values and does not rerun initializers. | Java Instrumentation API contract. |
| Target | JetBrains Runtime 17 enhanced class redefinition is the preferred open-source structural hot-swap runtime for the first spike. | JBR documents DCEVM-based redefinition enabled by `-XX:+AllowEnhancedClassRedefinition`. |
| Target | Clean reload is based on disposable classloader generations, not accumulated in-place mutation. | Required by the client/mod lifecycle maps; not implemented today. |
| Verified | The Java play utility resolves a named Packwiz/Fabric pack into an out-of-source content store and isolates mutable state by trajectory. | `util/play/Play.java`, `modpacks/`, and `./play.sh modpack inspect sodium`. |
| Verified | The same manifest can carry non-executable resource-pack ZIPs: the parent verifies/stages them, writes trajectory profile entries immediately before launch, and treats manifest changes as a client-generation reload. | `Play.resolveClientArtifact`, `materializeResourcePackProfile`, and [resource-pack evidence](../evidence/2026-07-22-resource-pack-stack.md). |
| Verified | `play.sh` is a thin shim over the incrementally compiled Java 17 utility; the utility remains the parent, keeps the server stable, builds a candidate, and replaces its client child after relevant changes. | `play.sh`, `:play-util:installDist`, `util/play/Play.java`, and the default `./play.sh` development command. |
| Verified | A failed candidate build does not stop the active client or server; restoring valid source permits a later swap. | [2026-07-21 hot-reload evidence](../evidence/2026-07-21-hot-reload.md), session `2026-07-22T07:00:06.678569Z-15381`. |
| Verified | Launcher state and lifecycle transitions have machine-readable contracts. | `./play.sh status --json`, `.run/play-events.jsonl`, and `PlayUtilityTest`. |
| Verified | `--canary` separates native-mod recompilation from base-game recompilation: canary edits build only `canaryModJar`, while base edits build `installDist`; both currently replace the client process generation and retain the server. | `dev/canary-mod/`, `util/play/Play.java`, and [canary reload evidence](../evidence/2026-07-21-canary-reload.md). |
| Verified | A supervised base candidate can activate the pinned Sodium adapter and invoke its graphics hook without replacing the parent or server. | [Sodium activation evidence](../evidence/2026-07-21-sodium-activation.md), session `2026-07-22T07:54:37.118559Z-53584`. |
| Verified | A four-artifact adapted Fabric stack survives a supervised base reload: the old pack scope closes, the client PID changes, parent/server PIDs remain stable, and all three performance hooks execute again. The launcher passes trajectory and monotonically increasing generation data into each client for in-game diagnostics. | [Catalog/diagnostics evidence](../evidence/2026-07-22-fabric-catalog-diagnostics.md), session `2026-07-22T23:48:28.229181Z-17812`. |
| Verified | The Java parent can launch a deterministic memory-backed Tech Reborn local world without requiring the external server; repeated seed launches regenerate the same first logged chunk and `stop` leaves both process roles stopped. | `--local-world`, `--world-seed`, and [Tech Reborn world-generation evidence](../evidence/2026-07-21-tech-reborn-worldgen.md). |
| Verified | The Java play utility exposes `debug` commands through one shared typed client library, replacing AppleScript visual/input automation and adding client/server state, bounded AOI comparison, and generation-owned mod tooling. | [Debug control-plane design](09-debug-control-plane.md) and [acceptance evidence](../evidence/2026-07-22-debug-control-plane.md). |
| Verified | Dedicated-server lifecycle now publishes port-open, debug-ready, and game-ready separately. Owned Fabric readiness requires the exact PID's `core.status.ready`; external servers use a Minecraft status handshake. Client debug/join/render predicates and all states are machine-readable. | `Play`, session `2026-07-24T05:43:52.606871Z-66555`, and [automation evidence](../evidence/2026-07-23-automation-observability.md). |
| Verified | `play.sh scenario run` executes checked JSON steps through `DebugClient`, expands matrix/repeat/soak cases, evaluates JSON-Pointer assertions and framebuffer baselines, and always emits JSON/JUnit plus final endpoint metrics. Optional outcome-driven JFR uses the selected Java 17 runtime's `jcmd`. | `Play`, `acceptance/scenarios/`, [scenario protocol](../acceptance/scenarios.md), and live smoke/matrix/failure evidence. |
| Verified | A Blockbench plugin producer can be tested locally through an isolated exact desktop host, official plugin artifact, normal project codec, and public export action. Generated bytes are pinned separately from Minosoft's automated headless ingestion gate; neither is presented as real-render acceptance. | [Blockbench producer-integration protocol](../acceptance/blockbench.md), [Animated Java export evidence](../evidence/2026-07-24-animated-java-blockbench-export.md), and `AnimatedJavaExportFixtureTest`. |

## Baseline commands

```sh
./gradlew compileKotlin
./gradlew test
./gradlew integrationTest
./gradlew assemble
```

Use a Java 17 runtime. A newer machine default may not configure this Gradle/
Kotlin build successfully even though emitted code targets Java 11.

## Reload domains

| Change | Mechanism | State policy |
| --- | --- | --- |
| Shader/texture/data resource | Owner validates and swaps the resource | Preserve running client and last-known-good resource |
| One mod and affected dependents | Replace mod classloader generations at a safe point | Preserve only explicitly exported/versioned mod state |
| Object model/client/graphics/simulation implementation | Dispose and recreate the client generation | Initially reconnect/recreate sessions; no arbitrary live-object migration |
| Loader kernel, stable API/SPI, native bootstrap, incompatible schema | Process restart | Persist only normal user configuration/launch descriptor |
| Small method edit during debugging | JBR/debugger class redefinition | Best-effort developer convenience; existing instances remain |

Target classloader shape:

```text
process / bootstrap
└── stable kernel + loader API/SPI
    └── client generation N
        ├── mod generation A.n
        └── mod generation B.n
```

Nothing typed by a child generation may be retained in a parent after disposal.
A client reload quiesces and disposes all child mods before discarding the client
classloader. This model is the architectural hot-reload workflow; debugger
redefinition is an accelerator inside a generation.

## Parallel pack trajectories

Tracked manifests under `modpacks/` are the portable source of truth. The
launcher downloads each declared artifact once, verifies its declared hash, and
publishes a read-only content-addressed artifact plus an immutable pack view.
When `MINOSOFT_MODPACK_CACHE` is set, resolution checks the same
`<hash-format>/<hash>/<filename>` layout before using the network.
`./play.sh modpack cache add FILE` verifies and publishes a local artifact into
that portable cache. A `minosoft-cache:` manifest URL requires an exact cache
hit and has no network fallback, which gives unreleased local builds a portable
contract without adding binaries to the repository.
Mutable home and profile state is isolated under a named trajectory. Parallel
branches and experiments can therefore share artifact bytes without sharing
world/session/configuration state.

```sh
./play.sh modpack prepare sodium --trajectory graphics-a
./play.sh modpack inspect sodium --trajectory graphics-a
./play.sh dev --modpack sodium --trajectory graphics-a
./play.sh modpack inspect fabric-stack --trajectory compatibility-a
./play.sh dev --modpack fabric-stack --trajectory compatibility-a
./play.sh modpack inspect tech-reborn --trajectory industrial-a
./play.sh dev --modpack tech-reborn --trajectory industrial-a
./play.sh start client --local-world --world-seed 6072333650475958863 \
  --modpack tech-reborn --trajectory industrial-worldgen-a
```

Set `MINOSOFT_MODPACK_STORE` to move the runtime store to another absolute path,
or `MINOSOFT_MODPACKS_DIR` to use the same launcher with manifests from a parallel
source checkout. Set `MINOSOFT_MODPACK_CACHE` to a portable, read-only artifact
source independent of that runtime store. None of these overrides changes the
checked-in manifest contract.

Local generators are memory-backed. `--local-world` selects the base-safe flat
generator unless `--world-generator` overrides it; the `tech-reborn` pack
selects `tech_reborn` by default. Each launch is a clean world regeneration; use
the same `--world-seed` for repeatability or a different seed for a new
deterministic trajectory.

## Parent-supervised hot reload

Running `./play.sh` with no action starts the Java utility in development mode.
It remains the parent process, starts or reuses the server, launches the client as
a child, and watches `src/main`, `debug-core/src/main`, the Gradle build
configuration, and the selected pack manifests. A change is debounced and built with `installDist` while the
current client remains active. Only a successful candidate causes the client
child to stop and reconnect; the server stays stable.

```sh
./play.sh
./play.sh --canary
./play.sh --modpack sodium --trajectory sodium-main
./play.sh dev client  # reuse a server started elsewhere
```

Use `MINOSOFT_HOT_RELOAD_PATHS` with the platform path separator to add parallel
source or immutable staging roots. This is currently a clean **process-generation
reload**. Disposable in-process client/mod classloaders remain the architectural
target for finer-grained reload after lifecycle ownership exists.

`--canary` adds a narrower build lane. The tracked native mod fixture is compiled
as a reproducible JAR, published read-only by SHA-256 into the same out-of-source
artifact store, and supplied through `--mod-source`. A canary-only edit skips
`installDist`; a base edit rebuilds both the distribution and canary against the
new host API. The client still restarts in either case because a safe mod-only
dispose/swap lifecycle has not yet been established.

Evidence commands:

```sh
./play.sh status --json
tail -n 20 .run/play-events.jsonl
```

Treat the JSON status as the live assertion surface and JSONL as diagnostic
history. Persist only a dated summary under `doc/agents/evidence/`; runtime logs
may contain machine-specific paths and grow across sessions.

## Debugger fast path

Use this convenience loop while the generation boundary is being built:

1. Install a JBRSDK 17 distribution and use it as both the Gradle and project SDK.
2. Start the existing Gradle `run` task under a debugger with
   `-XX:+AllowEnhancedClassRedefinition`. With Gradle CLI, `--debug-jvm` exposes
   the `JavaExec` debug port; configure the IDE to attach and compile changed
   classes.
3. Rebuild changed Kotlin/Java in the IDE. The debugger submits new class bytes;
   JBR handles supported structural redefinition.
4. Reload changed shaders/assets through graphics-owned reload hooks, not class
   redefinition.
5. Restart the session/process when a change alters boot ordering, native/OpenGL
   initialization, persistent schema, or another unsupported lifecycle boundary.

Example launch shape (replace the JBR path; this suspends for a debugger):

```sh
JAVA_HOME=/path/to/jbrsdk-17 \
JAVA_TOOL_OPTIONS=-XX:+AllowEnhancedClassRedefinition \
./gradlew run --debug-jvm
```

This fast path is currently **Target**, not yet a CI guarantee. Do not add
HotswapAgent by default: Minosoft is not using one of its framework plugins, and
JBR plus debugger redefinition is the smaller first experiment.

## Acceptance gates

1. Resource, mod, client-generation, and restart-required changes are classified
   explicitly in diagnostics.
2. A client generation can be recreated three times; old classloaders become
   collectible and native/thread/resource counts return to baseline.
3. An invalid candidate leaves the current generation active, or performs a
   documented clean reconnect when rollback is impossible.
4. A debugger method-body edit is visible without process restart; unsupported
   edits fail with a clear generation-reload/restart instruction.
5. The setup is documented for IntelliJ and reproduced on at least two developer
   operating systems before being called established.

## Trajectory

First establish the stable-kernel/client-generation seam and a repository-owned
reload coordinator. Then add `devRun`/IDE configuration that watches staged
artifacts, reports the selected reload domain, and checks optional debugger/JBR
capability. Keep ordinary `run` and CI independent of JBR.

## References

- [Development guide](../../contributing/Development.md)
- [JetBrains Runtime enhanced class redefinition](https://github.com/JetBrains/JetBrainsRuntime#why-use-jetbrains-runtime)
- [Java Instrumentation redefinition contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.instrument/java/lang/instrument/Instrumentation.html)
- [HotswapAgent](https://github.com/HotswapProjects/HotswapAgent)
- [Hot-reload acceptance protocol](../acceptance/hot-reload.md)
- [Latest recorded hot-reload evidence](../evidence/2026-07-21-hot-reload.md)
