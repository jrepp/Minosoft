<!-- Copyright (C) 2026 Jacob Repp -->

# Client evidence map

## Boundary

The client layer composes boot, profiles/accounts, status/play sessions, outbound
input and commands, the Eros launcher, terminal/headless interaction, and the
network protocol client. Graphics has its own map even though it is currently
constructed from a play session.

## Evidence map

| Status | Claim | Evidence |
| --- | --- | --- |
| Observed | One process can create multiple play/status sessions. | Session construction is instance-based; `PlaySession` tracks session-owned registries/world/settings and companion collections of active connections. |
| Observed | Eros and CLI are two application surfaces over shared runtime/session behavior. | `gui/eros/`, `terminal/`, boot options in `Minosoft.kt`. |
| Observed | The network client is Netty-backed and stateful. | `protocol/network/network/client/netty/`, `NetworkConnection`, and protocol states. |
| Observed | Packet classes perform explicit version-aware decoding/handling. | `protocol/packets/` and protocol integration fixtures. |
| Observed | `PlaySession` directly constructs `Rendering` today. | `PlaySession.rendering` is marked deprecated pending a module split. |
| Verified | CI exercises unit and integration suites across Linux, Windows, and macOS. | `.github/workflows/build.yml`. |
| Verified | The pause menu exposes active Fabric pack functionality and process-local load/hook/performance diagnostics without requiring Eros or JavaFX. | `FabricModSettingsMenu`, `FabricModDiagnosticsMenu`, and [2026-07-22 catalog/diagnostics evidence](../evidence/2026-07-22-fabric-catalog-diagnostics.md). |
| Verified | Source-native configuration screens provide categories/tabs, focused search, hover descriptions, virtualized clipped rows/grids, dialogs, banners, map interaction, and synchronized machine presentation models. The Mod settings catalog decodes descriptions, icon paths, badges, parents, and dependencies and routes configurable owners directly to their schemas. | `SettingsFormMenu`, `ClippedVirtualGridElement`, `MapCanvasElement`, `FabricMachineScreens`, `FabricModSettingsMenu`, focused tests, and [Fabric UI evidence](../evidence/2026-07-24-fabric-ui-framework.md). |
| Verified | The pause menu exposes profile-backed Audio and Advanced Audio pages with a live engine state, bounded volume changes, sound-family policies, test output, stop-all, and an explicit restart boundary for engine startup. | `AudioMenu`, `AudioAdvancedMenu`, `AudioControls`, and [audio-menu evidence](../evidence/2026-07-22-audio-menu.md). |
| Verified | Client debug automation exposes framebuffer capture/sampling, normal-path key/text/mouse/scroll injection, immutable session/player/world DTOs, loaded-only block/AOI sampling, and mod providers through an opt-in user-owned endpoint. | `ClientDebugChannel` and [debug control-plane evidence](../evidence/2026-07-22-debug-control-plane.md). |
| Verified | The Fabric source bridge exposes owned start/end client-tick callbacks around one ordered play-session cycle at 20 Hz. | `FabricClientTickEvents`, `SessionTicker`, and [tick evidence](../evidence/2026-07-22-fabric-client-tick-events.md). |
| Verified | Fabric API can observe session-asset, shader, and texture reload transactions through owned prepare/apply/complete/failed callbacks; initial session assets are prepared before publication. | `FabricResourceReloadEvents`, `PlaySession.load`, `ReloadCommand`, and [resource-reload evidence](../evidence/2026-07-22-fabric-resource-reload-events.md). |
| Verified | Normalized key, character, mouse-move, and scroll observations share the normal GLFW/debug-injection path; source-native configurable bindings attach to every render session and remove individual callbacks on close. | `FabricInputEvents`, `FabricKeyBindings`, `InputManager`, and [input/connection evidence](../evidence/2026-07-22-fabric-input-connection-events.md). |
| Verified | Each play session publishes owned created, state-changed, joined, and disconnected callbacks directly from its observable state machine. | `FabricClientConnectionEvents`, `PlaySession`, and [input/connection evidence](../evidence/2026-07-22-fabric-input-connection-events.md). |
| Verified | Local survival mining emits the target block sound group's positional `hit` event at a monotonic 200 ms cadence, while remote block-break animation packets emit the same event from the packet's world position and exclude the local player. Completion remains the separate world `destroy` event. | `SurvivalDigger`, `BlockHitAudio`, `BlockHitCadence`, `BlockBreakAnimationS2CP`, and [harvesting evidence](../evidence/2026-07-22-resource-pack-opengl-audio.md). |
| Verified | Chat execution expands the exact `/gamemode c` and `/gamemode s` aliases to vanilla command arguments before server-command-tree parsing, then uses the normal version-aware command transport; unrelated commands and chat remain unchanged. | `CommandAliases`, `ChatNode`, focused unit/integration tests, and [gamemode alias evidence](../evidence/2026-07-23-creative-catalog-gamemode-aliases.md). |
| Verified | Eros opens on a Minecraft-style title activity whose Multiplayer, Profiles & Options, Mods, and Quit actions reuse existing launcher flows. Direct-connect, `--no-eros`, and headless launch remain outside this JavaFX boundary. | `TitleController`, `ErosMainActivities`, `MainErosController`, focused resource tests, and [mob/title evidence](../evidence/2026-07-23-mob-rendering-title-menu.md). |
| Verified | Double-tapping forward within the shared 300 ms window publishes one accumulated start-sprint action; typing consumers and input clear boundaries do not feed the movement binding, and held Control remains unchanged. | `CameraInput`, `KeyActionFilter.DoublePress`, `PersonView`, focused tests, and [double-tap sprint evidence](../evidence/2026-07-23-double-tap-sprint.md). |
| Verified | A win-game event opens scrolling Minosoft credits assembled only from the project-local `minosoft:texts/credits.json` resource and same-key local pack/mod contributions; closing or completing it uses the ordinary respawn action. | `CreditsContent`, `CreditsScreen`, focused tests, and [credits parity evidence](../evidence/2026-07-23-credits-parity.md). |

## Stable contracts

- Preserve multi-session isolation and protocol-version compatibility.
- Headless and `--no-eros` paths must not construct JavaFX controls.
- Keep transport framing, packet decoding, domain mutation, and presentation
  distinguishable so failures retain packet/state context.
- Online authentication/encryption and offline accounts are distinct modes; do
  not silently downgrade when a server requests encryption.
- Long-running network/asset work stays off the JavaFX application thread.

## Trajectory

**Target:** make client orchestration a replaceable client generation behind
explicit server, model, simulation, and graphics ports. Replace the deprecated
`PlaySession` → concrete `Rendering` link with a lifecycle boundary that supports
headless testing, reloadable mod extensions, and whole-client generation restart.

The first clean client reload may disconnect and recreate sessions rather than
migrating a live object graph. Persist only a stable launch/reconnect descriptor
across the boundary; add state migration later only for explicitly versioned
data. A client-generation reload also disposes its child mod generations.

## Next evidence

1. Trace boot to status/play session creation for Eros and CLI independently.
2. Map session lifecycle states, owners, cleanup, and retry behavior.
3. Map packet decode → model update → event → presentation for representative
   login, chunk, entity, chat, and light paths.
4. Identify client extension points that can become stable mod API.
5. Separate the minimum stable launch descriptor from live session/model state.

## Validation

- Protocol unit/integration packages and packet fixtures
- Config and Eros integration packages for launcher/profile changes
- Normal graphical launch plus headless/no-Eros launch for boundary changes
- At least the versions adjacent to any changed packet boundary

## References

- [Architecture: Core and Eros](../../Architecture.md)
- [Headless mode](../../Headless.md)
- [Version support](../../VersionSupport.md)
