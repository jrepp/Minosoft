<!-- Copyright (C) 2026 Jacob Repp -->

# Debug control-plane grounding

## Boundary and status

Minosoft now has a version-one local debug control plane shared by the client,
the pinned Fabric 1.20.4 development server, repository CLI, and adapted mod
providers. It owns endpoint discovery, authenticated local IPC, framed messages,
state/visual/input/block operations, and generation-scoped mod diagnostics.

“Debug pipe” is the product-level name. The transport is a Unix-domain socket on
macOS/Linux and an owner-restricted Windows named pipe. The endpoint is opt-in
through `MINOSOFT_DEBUG`; `Play` enables it for managed development launches.

The control plane is not remote administration. It has no TCP listener, arbitrary
evaluation, reflection browser, unrestricted filesystem access, or server
mutation API. One-shot version-one operations are implemented; subscriptions,
advanced AOI layers, and connection-owned input state remain explicit later
stages. The CLI now composes one-shot operations into checked scenario files.

## Current evidence

| Status | Claim | Evidence |
| --- | --- | --- |
| Verified | `debug-core` is a GUI-independent Java library containing discovery, credentials, framing, transports, operation ownership, deadlines, server, and client. | `debug-core/` and `:debug-core:test`. |
| Verified | The compiled Java play utility is the only CLI protocol consumer; `play.sh` is a thin Java 17 shim. | `util/play/Play.java`, `play.sh`, and `PlayUtilityTest`. |
| Verified | The client publishes an opt-in endpoint and queues visual/input work onto the render path while state/block reads use explicit session/world seams. | `ClientDebugChannel`, live visual/input/AOI acceptance, and the dated evidence below. |
| Verified | The pinned Fabric 1.20.4 server runs an owned bridge using the same library and exposes tick-thread state, blocks/AOI, and Fabric loader diagnostics. | `debug-server-fabric/`, `Play.prepareFabricServer`, and live server acceptance. |
| Verified | Adapted Fabric mods add namespaced provider operations owned by the active pack generation. The Iris provider additionally exposes a bounded render-queue shader reload used to prove its completed-reload callback. | `FabricDiagnosticDebugProvider`, `IrisDebugProvider`, `FabricRegistrationScope`, generation 1→4 live capability checks, and [Iris/JEI behavioral acceptance](../evidence/2026-07-23-iris-jei-acceptance.md). |
| Verified | A bounded client/server block sample normalizes property ordering/case and compares through the CLI without loading chunks. | `Play.debugCompare`, both samplers, and live 125-cell equality. |
| Verified | The immutable client player sample includes model-owned sprint state, allowing normal-path input acceptance to distinguish sprint activation from ordinary displacement. | `ClientDebugChannel`, `input.inject`, and [double-tap sprint evidence](../evidence/2026-07-23-double-tap-sprint.md). |
| Verified | Both roles expose `metrics.snapshot` from shared core instrumentation: at most 256 named operation series, fixed latency buckets, outcome counters, total/max latency, and role-owned runtime gauges. | `DebugMetrics`, both status/metrics suppliers, core tests, live capabilities, and [automation evidence](../evidence/2026-07-23-automation-observability.md). |
| Verified | The CLI consumes semantic lifecycle predicates and checked JSON scenarios through `DebugClient`, including assertions, visual baselines, matrix/repeat/soak execution, JSON/JUnit artifacts, and optional JFR around a run. | `Play`, [scenario protocol](../acceptance/scenarios.md), and live smoke/matrix/failure runs. |
| Verified | macOS discovery directories are `0700`; descriptors, credentials, and sockets are `0600`; wrong credentials and stale descriptors are rejected in tests. | Live `stat` evidence plus `DebugDiscoveryTest` and `DebugChannelServerTest`. |
| Observed | Windows named-pipe code compiles and applies an owner/SYSTEM DACL, but this pass did not execute it on a Windows host. | `DebugWindowsPipes` and cross-platform build configuration. |

Durable runtime results are in
[2026-07-22 debug-control-plane evidence](../evidence/2026-07-22-debug-control-plane.md).

## Architecture

```text
play.sh -> compiled Play -> DebugClient
                             |
                 discovery + launch credential
                             |
          +------------------+------------------+
          |                                     |
 ClientDebugChannel                    Fabric server bridge
 render/input/session/world            server tick/world/loader
          |
 Fabric generation-owned ModDebugProvider operations
```

The launcher and tests use `DebugClient`; they do not implement a second wire
protocol. Transport workers authenticate, validate, and dispatch. Operations
that touch render or server state schedule work onto the appropriate owner
thread. Responses contain DTO/JSON values or one bounded binary attachment,
never live game objects.

## Discovery, identity, and permissions

Version-one descriptor roots are:

| Platform | Default root |
| --- | --- |
| macOS | `~/Library/Application Support/Minosoft/debug/v1/` |
| Linux | `${XDG_STATE_HOME:-~/.local/state}/minosoft/debug/v1/` |
| Windows | `%LOCALAPPDATA%\Minosoft\debug\v1\` |

`MINOSOFT_DEBUG_HOME` and `MINOSOFT_DEBUG_RUNTIME` isolate tests and parallel
automation. A root contains `endpoints/<id>.json` plus an unprinted
`credentials/<id>.token`. Unix sockets use a short SHA-256-derived name beneath
the private runtime directory; Windows descriptors carry a named-pipe address.
Callers discover addresses and never construct them.

Descriptors include role, PID, process start, trajectory, generation, transport,
and protocol range. Discovery validates PID plus process-start identity, deletes
stale descriptors without signaling a process, and publishes/removes descriptor
and credential state atomically around endpoint lifetime. The random 256-bit
launch token is sent only across the local owner-restricted transport and never
appears in normal status or endpoint output.

Windows pipes use a protected DACL granting the owner and SYSTEM. Windows
descriptor-file ACL behavior still needs runtime CI evidence; compilation alone
is not permission evidence.

## Protocol and shared API

`DebugFrameCodec` uses a fixed 12-byte header with magic, protocol version, kind,
flags, and payload length. Payloads are capped at 16 MiB. JSON frames carry
HELLO, REQUEST, RESPONSE, and ERROR values; a RESPONSE may declare exactly one
following BINARY frame, used by PNG capture.

Handshake:

```text
connect
-> HELLO {endpointId, token, protocolMin, protocolMax}
<- HELLO {selected protocol, endpoint identity, role, generation}
-> REQUEST {id, operation, deadlineMs, body}
<- RESPONSE {id, result, optional attachment} | ERROR {id, error}
```

Stable error codes include `unsupported_operation`, `invalid_request`,
`unauthenticated`, `not_ready`, `limit_exceeded`, `deadline_exceeded`, and
`internal_error`. Server execution deadlines include owner-thread queue time.
The client adds a transport watchdog and closes a wedged connection after the
request deadline plus a bounded response grace period.

Primary shared types:

- `DebugPaths`, `DebugDiscovery`, `DebugEndpointDescriptor`
- `DebugFrame`, `DebugFrameCodec`, `DebugProtocolException`
- `DebugChannelServer`, `DebugOperationRegistry`, `DebugOperationHandler`
- `DebugClient`, `DebugResponse`, `DebugClientException`
- `ModDebugProvider`, `ModDebugRegistrar`

The emitted core API targets Java 11 and depends on no Minosoft GUI, OpenGL,
session, world, Netty, or Fabric implementation type. JNA is transport-only for
Windows named pipes.

## Version-one operations

| Operation | Role | Owner/thread | Limit/result |
| --- | --- | --- | --- |
| `core.ping` | both | transport-safe | endpoint identity and role |
| `core.capabilities` | both | transport-safe | operation names and owners, payload/deadline limits |
| `core.status` | both | immutable snapshot | process, trajectory, generation, readiness summary |
| `state.sample` | both | client session or server tick snapshot | named client/server view |
| `visual.capture` | client | render queue | final framebuffer PNG plus dimensions/frame/time |
| `visual.sample` | client | render queue | ≤4096 points and ≤65536-pixel region hash/luminance |
| `input.inject` | client | render/input path | ≤256 key, text, mouse-move, or scroll events |
| `world.blocks.sample` | both | loaded world state | inclusive box, ≤32768 cells, palette/RLE |
| `world.aoi` | both | loaded world state | version-one alias for bounded box sampling |
| `mods.debug` | both | process-local snapshot | adapted-client or Fabric-server mod lifecycle |
| `mods.<id>.summary` | client | mod generation | provider-specific adapter/hook summary |
| `mods.iris.reload-shaders` | client | render queue | one host shader reload plus bounded JSON completion |
| `metrics.snapshot` | both | transport-safe plus immutable role gauges | capped operation series, fixed latency buckets, counters/totals/max, runtime gauges |

### Visual and input invariants

Framebuffer reads use Minosoft's renderer readback and return the composited
game framebuffer. The CLI chooses and writes the output file; the game process
does not accept arbitrary output paths. Sampling uses top-left CLI coordinates
and returns RGBA, region SHA-256, and average luminance.

Input enters the normal Minosoft event path rather than host OS automation. A
mouse button is currently expressed as a key code such as
`MOUSE_BUTTON_LEFT`; actions are `PRESS`/`RELEASE`. The caller is responsible for
balanced actions. Connection-owned held-state cleanup, coordinate-space
conversion, and semantic GUI-element actions remain future API work. Scenario
files can sequence current one-shot operations and wait on lifecycle/state/frame
evidence, but they do not change those input ownership limits.

### State and AOI invariants

Implemented client views are `client.summary`, `client.player`, and
`client.world`. The Fabric server supplies `server.summary` including bounded
player and world records. Client-observed and server-authoritative samples remain
visibly separate.

Version-one AOI is an inclusive axis-aligned block box in `y,z,x` order. It is
read-only and loaded-only: it never generates or requests a missing chunk.
`minecraft:air` and `minosoft:not_loaded` are distinct palette entries. Block
states are canonicalized, palette-compressed, and run-length encoded. The CLI
expands two bounded samples, normalizes state property ordering/case, aligns
coordinates, and reports at most 64 detailed differences plus the total.

Spheres, cylinders, chunk selectors, light/biome/entity layers, binary
partitions, hashes, and delta subscriptions remain target work after the stable
one-shot box contract.

## Mod provider ownership

`ClientDebugChannel.register(ModDebugProvider)` validates the mod/provider ID,
names operations `mods.<id>.<local-name>`, records the provider version in the
operation owner, and returns one reverse-order cleanup handle. Partial
registration failure closes every operation already added. Closing a Fabric
pack registration scope removes its provider operations before the old
generation exits.

The current provider surface is operation-only. First-party adapted Fabric mods
register `summary`; Iris also registers `reload-shaders`, which selects exactly
one active render session and executes the same
`FabricResourceReloadEvents`/native shader reload transaction as the CLI command.
`mods.debug` supplies baseline lifecycle and attributed hook counters/timing
even if a mod has no provider. Core operation metrics now use fixed bounded
series/buckets. Future mod-owned metrics and AOI layers should build on the same
cleanup discipline rather than introducing a second registry.

## Implemented CLI

Selectors may appear with each command:

```text
--role client|server
--trajectory NAME
--endpoint ID
--json
```

Commands:

```sh
./play.sh debug endpoints --json
./play.sh debug status --role client --trajectory NAME --json
./play.sh debug capabilities --role server --trajectory NAME --json
./play.sh debug state client.player --role client --json
./play.sh debug mods --role client --json
./play.sh debug request mods.sodium.summary '{}' --role client --json
./play.sh debug request mods.iris.reload-shaders '{}' --role client --json

./play.sh debug visual capture /tmp/frame.png --role client --json
./play.sh debug visual sample --point 640,360 \
  --region 0,0,320,180 --role client --json

./play.sh debug input key KEY_ESCAPE PRESS --role client --json
./play.sh debug input key KEY_ESCAPE RELEASE --role client --json
./play.sh debug input mouse 640 360 --role client --json
./play.sh debug input text hello --role client --json
./play.sh debug input scroll 0 -1 --role client --json

./play.sh debug blocks 0 60 0 15 79 15 --role server --json
./play.sh debug aoi 0 60 0 15 79 15 --role client --json
./play.sh debug compare blocks 0 60 0 15 79 15 --trajectory NAME --json
```

Selection chooses the highest generation for a role/trajectory and rejects
ambiguous peers at the same generation. Tokens are never printed. Binary bytes
are written only by `visual capture`. Expected remote failures have concise
human output or a stable JSON error object rather than a Java stack trace.

## Fabric server decision

The development server lane is pinned to Minecraft 1.20.4, Fabric Loader
0.15.11, Fabric API 0.97.3, and the repository-owned
`minosoft_debug_bridge`. `Play` builds/stages the bridge and Fabric API into the
server's mutable runtime directory, then launches the official Fabric server
launcher. The bridge starts after `SERVER_STARTED`, samples server-owned state
through `MinecraftServer.execute`, and closes before server shutdown.

Paper/Bukkit is a different plugin ecosystem and may be added as a separate
server lane. NeoForge is a future separate lane for mods such as Mekanism.
Hybrid Fabric/Bukkit servers are not part of this architecture because they
weaken loader/API ownership and compatibility evidence.

## Lifecycle and hot reload

- Endpoint publication follows operation/authentication setup; close removes the
  socket/pipe, descriptor, and credential.
- A client replacement increments generation and publishes a new endpoint ID;
  the old endpoint disappears rather than redirecting an in-flight request.
- The development parent watches `src/main`, `debug-core/src/main`, build files,
  the selected manifest, canary source when enabled, and configured external
  roots.
- Candidate compile/preflight happens while the current client remains active.
  A successful base/shared-core candidate replaces only the client; the parent
  and server stay stable.
- Fabric pack scope cleanup removes old provider operations and replacement
  activation re-registers them with the new generation.

## Acceptance status

| Gate | Status |
| --- | --- |
| Discovery/authentication/stale cleanup/private Unix permissions | Verified in unit and live macOS checks |
| Frame validation, errors, deadlines, binary attachment, operation cleanup | Verified in `debug-core` tests |
| Client final-frame capture and point/region sampling without AppleScript | Verified live |
| Normal-path mouse/key input causing death-screen Respawn | Verified live |
| Client/server state on one protocol | Verified live |
| Fabric server headless endpoint startup and clean shutdown discovery removal | Verified live |
| Loaded-only client/server bounded block equality | Verified live after canonicalization fix |
| Generation-owned Sodium/stack provider replacement | Verified across generations 1→3 |
| Base and shared-debug source hot reload with stable parent/server | Verified live |
| Scenario lifecycle/metrics JSON+JUnit, matrix, screenshot failure, and JFR disposition | Verified live |
| Full repository unit and integration suites | Verified on Java 17: 1,497 unit tests and 2,031 integration tests, zero failures/errors |
| Linux and Windows runtime transport/ACL behavior | Not run in this macOS pass; Windows compiles |
| Streaming/backpressure and advanced AOI/provider layers | Deferred; not advertised in version one |

## Next trajectory

1. Add Linux and Windows runtime transport/ACL CI before calling portability
   fully verified.
2. Add connection-owned pressed input state and resolved coordinate spaces.
   Semantic sequence files and lifecycle/frame wait predicates now exist at the
   CLI scenario layer; continue with GUI-element predicates where state/frame
   evidence cannot express the target.
3. Version state DTO schemas and add render identity plus richer tick/render
   histograms beyond the current averages and operation histograms.
4. Add partitioned AOI layers and content hashes, then subscriptions with bounded
   queue depth and explicit `resync_required` behavior.
5. Extend `ModDebugRegistrar` with owned metrics and AOI-layer registrations and
   prove canary cleanup under reload.

## Stable contracts

- One protocol implementation and shared client serve the CLI and tests.
- Discovery is per-user, versioned, atomic, trajectory-aware, and token-safe.
- Operations are explicit, namespaced, deadline-bounded, and owner-thread aware.
- No operation exposes arbitrary evaluation/reflection or silently falls back to
  AppleScript/OS input.
- Sampling is read-only and loaded-only by default.
- Client-observed and server-authoritative state remain distinct.
- Mod operations cannot outlive their registration/generation.
- Unsupported future capability is absent from `core.capabilities`.

## References

- [Base/kernel evidence map](01-base.md)
- [Client evidence map](03-client.md)
- [Server boundary](04-server.md)
- [Graphics evidence map](05-graphics.md)
- [Development workflow](07-development-workflow.md)
- [Mod workflow](08-modding.md)
- [Hot-reload acceptance protocol](../acceptance/hot-reload.md)
- [Scenario and observability protocol](../acceptance/scenarios.md)
- [Debug control-plane acceptance evidence](../evidence/2026-07-22-debug-control-plane.md)
- [Automation and observability evidence](../evidence/2026-07-23-automation-observability.md)
