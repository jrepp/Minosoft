<!-- Copyright (C) 2026 Jacob Repp -->

# Server-boundary evidence map

## Boundary

Minosoft is a client. This layer maps what it treats as “the server”: the
`ServerConnection` port, the remote Netty-backed connection, protocol state and
packets, plus the in-process `LocalConnection` used for a local world. The
repository also contains a development-only Fabric bridge for an external
pinned server; that bridge is not a Minosoft server implementation.

## Evidence map

| Status | Claim | Evidence |
| --- | --- | --- |
| Observed | `ServerConnection` is the common boundary for connect, disconnect, detach, and clientbound sending. | `protocol/ServerConnection.kt`. |
| Observed | `NetworkConnection` adapts that boundary to the Netty client. | `protocol/network/NetworkConnection.kt`. |
| Observed | `LocalConnection` implements the same port without a socket and populates a play session directly. | `local/LocalConnection.kt`. |
| Observed | Local generation/storage is small and client-hosted. | `local/generator/`, `local/storage/`, and `LocalChunkManager.kt`. |
| Observed | `server/` at the repository root is local runtime state, not product source. | It is outside Gradle source sets and is excluded by the root agent contract. |
| Verified | `LocalConnection` can act as the authority for an adapted Tech Reborn registry snapshot and deterministic ore-generated chunks without a socket. | `LocalConnection`, `TechRebornGenerator`, `FabricSessionContentBridge`, and [live evidence](../evidence/2026-07-21-tech-reborn-worldgen.md). |
| Verified | The launcher can run a pinned Minecraft 1.20.4/Fabric 0.15.11 server with an owned bridge that exposes status, player/world state, loaded-only blocks/AOI, and Fabric loader diagnostics through `debug-core`. | `debug-server-fabric/`, `Play.prepareFabricServer`, and [debug control-plane evidence](../evidence/2026-07-22-debug-control-plane.md). |
| Verified | Managed server-mod cleanup accepts the `+` character used by valid Fabric artifact versions while still rejecting separators and other unsafe filename characters. A clean acceptance launch installed Fabric API, Inventory Management, and JEI and reported JEI 17.3.1.5 through server Fabric Loader diagnostics. | `Play.SAFE_MANAGED_FILENAME`, `Play.prepareFabricServer`, focused utility tests, and [Iris/JEI behavioral acceptance](../evidence/2026-07-23-iris-jei-acceptance.md). |
| Verified | The attach-entity protocol boundary preserves version semantics: pre-15w41a packets honor their explicit vehicle/leash flag, while modern packets update leash-holder state and do not create a vehicle mount. | `EntityAttachS2CP`, `EntityAttachment`, `LocalDisplayEntityFactoryTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | The owned Fabric 1.20.4 server installs the pack's server-only Terralith 2.4.11 artifact, exposes it through live Fabric Loader diagnostics, and loads its datapack before new-world creation. The Terralith artifact remains outside the Minosoft client classpath. | `Play.prepareFabricServer`, `mods.debug`, and [Terralith world-generation evidence](../evidence/2026-07-23-terralith-worldgen.md). |
| Verified | The managed server lane distinguishes port-open, debug-ready, and game-ready; exact-PID Fabric readiness requires `core.status.ready`, while external readiness uses a Minecraft status handshake. JSON scenarios can gate server state, collect metrics/JUnit/JFR, and guarantee deadline-bounded observations. | `Play.startServer`, `Play.waitForPredicate`, checked scenarios, and [automation evidence](../evidence/2026-07-23-automation-observability.md). |
| Observed | The repeatable harness currently targets the pinned managed Fabric server and an already-running external server. It does not yet provision arbitrary server implementations/protocol versions in isolated directories. | `Play`, checked scenarios, and current CLI options. |

## Stable contracts

- Keep remote transport and local connection interchangeable at the
  `ServerConnection` boundary where their semantics overlap.
- Never make local behavior evidence for all remote-server behavior; protocol
  ordering, authentication, compression, encryption, and disconnects differ.
- Treat recorded packet fixtures as test inputs, not permission to embed
  copyrighted server code or assets.
- Preserve clear ownership of server processes and test data. Tooling may start a
  disposable server only when the task explicitly scopes and cleans it up.
- Do not describe the local authority as a Fabric dedicated server. External
  multiplayer requires an explicit registry/network synchronization protocol
  and matching authoritative gameplay implementation.

## Trajectory

The managed Fabric lane now has semantic readiness, client scenarios, captured
diagnostics, and normal parent-owned shutdown. **Target:** generalize it into a
disposable compatibility matrix with isolated working directories, explicit
online/offline policy, representative server implementations/versions, and
guaranteed cleanup. Keep it optional for fast unit/mod reload loops.

The local adapter should remain useful as the zero-network simulation host; it
should not grow into a second full protocol implementation.

## Next evidence

1. Map `ServerConnection` lifecycle and disconnect/error cleanup in both adapters.
2. Inventory supported protocol boundary versions and representative fixtures.
3. Define disposable server-harness inputs without depending on the developer's
   root `server/` directory.
4. Add smoke scenarios for login, spawn, movement, chunk/light update, and clean
   disconnect before using the harness as a compatibility gate.

## Validation

- Protocol integration tests and packet fixtures
- Local generator/storage integration tests
- Future external-server checks must report server type/version, auth mode,
  client protocol version, ports, and retained logs

## References

- [Architecture: Networking](../../Architecture.md#networking)
- [Minecraft versions](../../MinecraftVersions.md)
- [Bannability and server caution](../../Bannability.md)
