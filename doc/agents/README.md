<!-- Copyright (C) 2026 Jacob Repp -->

# Agent grounding

This directory holds layered evidence maps for agent work: what is observed,
where the evidence lives, which boundaries are intentional, and what trajectory
should guide the next change. It complements human-facing documentation in
`doc/`; it does not duplicate it.

The order below is an investigation order, not a claim that the current
single-module codebase already has a clean dependency graph. Start at the first
layer affected by a task and follow references upward.

## Evidence language

- **Observed** — directly supported by current source, build logic, or runtime
  behavior.
- **Verified** — observed and covered by a named automated or repeatable manual
  check.
- **Target** — an intended boundary or workflow; do not describe it as current.
- **Unknown** — important but not yet supported by sufficient evidence.

Every map separates evidence from trajectory. If code and a map disagree,
inspect the behavior, correct the stale statement, and keep uncertainty explicit.

## Layer maps

| Order | Layer | Primary concern |
| --- | --- | --- |
| 1 | [Base](areas/01-base.md) | Runtime primitives, boot, assets, config, utilities |
| 2 | [Object model](areas/02-object-model.md) | Registries, blocks/items, entities, world state |
| 3 | [Client](areas/03-client.md) | Application/session orchestration, UI, input, protocol client |
| 4 | [Server boundary](areas/04-server.md) | Remote-server contract and the local connection adapter |
| 5 | [Graphics](areas/05-graphics.md) | OpenGL, rendering lifecycle, resources, visual state |
| 6 | [Simulation](areas/06-simulation.md) | Ticks, physics, world transitions, local generation |
| 7 | [Development workflow](areas/07-development-workflow.md) | Build/test loop and host hot reload |
| 8 | [Mod workflow](areas/08-modding.md) | Reloadable mod loader and staged Fabric bridge |
| 9 | [Debug control plane](areas/09-debug-control-plane.md) | Local IPC, CLI automation, state/AOI sampling, and mod diagnostics |

Cross-cutting examples: a packet-driven light fix uses server, object-model,
simulation, and graphics maps; reloadable mod rendering uses base, graphics,
development, and mod maps. Debug automation and AOI inspection use the debug
control-plane map plus every sampled or mutated owner layer.

## Implementation guides

- [Java and Kotlin](guides/java-kotlin.md) turns recurring audit findings into
  source-level rules for bounds, numeric safety, concurrency, transactions,
  resource ownership, and focused verification.

Use the [map template](templates/area.md) when a layer needs a narrower map.
Split a map when it stops being quickly scannable or gains distinct evidence and
acceptance gates. Add a nested `AGENTS.md` only when a subtree needs enforceable
instructions that differ from the root contract.

## Acceptance and evidence

- [Hot-reload acceptance protocol](acceptance/hot-reload.md) defines the
  repeatable process-generation gates and failure-preservation check.
- [Canary/base reload evidence](evidence/2026-07-21-canary-reload.md) compares
  the separate mod and host compilation lanes.
- [Sodium activation evidence](evidence/2026-07-21-sodium-activation.md) proves
  adapted pack activation and graphics-hook execution.
- [Render-substrate target architecture](backlog/render-substrate.md) defines
  the single replacement pipeline, built-in/Sodium/Iris ownership contracts,
  objective completion function, delivery rungs, and small-model support queue.
  It is target guidance, not accepted runtime evidence.
- [Render-substrate R0–R7 implementation checkpoint](evidence/2026-07-24-render-substrate-r0-r7.md)
  records the canonical graph/provider cutover, headless and broad checks,
  real-OpenGL matrix/reload/performance evidence, legacy removal, and the exact
  predicates that remain unaccepted.
- [Fabric stack evidence](evidence/2026-07-21-fabric-stack.md) records the
  historical three-artifact baseline, independent host-hook invocation, owned
  cleanup, and supervised reactivation.
- [Fabric catalog and runtime-diagnostics evidence](evidence/2026-07-22-fabric-catalog-diagnostics.md)
  records per-mod mapped/partial/unmapped inventories, attributed host-hook
  telemetry, a corrected Fabric API dependency, and in-game generation status.
- [Fabric client-event evidence](evidence/2026-07-22-fabric-client-events.md)
  records the source-level lifecycle/world/HUD bridge, owned callback contract,
  focused checks, and the remaining binary-compatibility boundary.
- [Fabric client-tick evidence](evidence/2026-07-22-fabric-client-tick-events.md)
  records the ordered session safe point, start/end callback bridge, cadence,
  generation ownership, and simulation regression checks.
- [Fabric resource-reload evidence](evidence/2026-07-22-fabric-resource-reload-events.md)
  records candidate-before-apply session assets, source-level reload phases,
  render-queue shader/texture boundaries, failure cleanup, and the remaining
  last-known-good GPU swap gate.
- [Portable resource-pack evidence](evidence/2026-07-22-resource-pack-stack.md)
  records verified non-executable artifacts, trajectory-profile materialization,
  priority ordering, supervised reload, and live Faithful/Enhanced Audio mounts.
- [In-game audio-menu evidence](evidence/2026-07-22-audio-menu.md) records
  profile-backed live controls, actual engine readiness, test output, visual
  layout validation, and base hot-reload acceptance.
- [Entity mesh unload evidence](evidence/2026-07-22-entity-mesh-unload.md)
  records the duplicate GPU-buffer cleanup failure, corrected lifecycle
  invariant, supervised activation, and post-fix live log gate.
- [Resource-pack OpenGL and positional-audio evidence](evidence/2026-07-22-resource-pack-opengl-audio.md)
  records the Apple sampler contract, adaptive dynamic/font texture arrays,
  clean Faithful rendering, and source-start/world-origin harvesting proof.
- [Fabric input and connection evidence](evidence/2026-07-22-fabric-input-connection-events.md)
  records normalized input observation, session-wide configurable key bindings,
  callback-specific cleanup, connection phases, debug injection, and the
  remaining binary-translation boundary.
- [Fabric world, UI, networking, and gameplay evidence](evidence/2026-07-22-fabric-world-ui-gameplay-hooks.md)
  records source-native world/chunk/block transitions, bounded payloads,
  screens/HUD, commands, entities, interactions, particles, sound, and their
  ownership/thread contracts. The [Fabric hook backlog](backlog/fabric-hooks.md)
  names the host refactors required by every deferred family.
- [Inventory Management evidence](evidence/2026-07-22-inventory-management.md)
  records the pinned 1.20.4-compatible artifact, corrected player preview,
  reusable container-screen extensions, native sort/transfer/stack controls,
  managed server handler, operation telemetry, live end-to-end sort, and
  crafting-table/player-hotbar reconciliation.
- [Iris and JEI activation evidence](evidence/2026-07-23-iris-jei-activation.md)
  records their exact pinned artifacts, blocker-free adapted activation,
  generation-owned render/reload and recipe-viewer hooks, live invocation and
  cleanup evidence, and the GUI quad-index invariant exposed by the recipe view.
- [Iris and JEI behavioral acceptance](evidence/2026-07-23-iris-jei-acceptance.md)
  records a clean four-generation supervised run, a real render-queue shader
  reload, JEI server presence and complete recipe-view navigation, stable
  parent/server ownership, and the launcher/navigation defects found by the
  stricter gates.
- [Chest-rendering evidence](evidence/2026-07-23-chest-rendering.md) records the
  `builtin/entity` item fallback gap, entity-backed section invalidation
  contract, the 61-block-item vanilla special-render audit, crafted-shield
  fallback, namespace-agnostic mod diagnostics, focused and broad regressions,
  and a live harvest/pickup/place sequence that remained on one client
  generation.
- [Generated block-item rendering evidence](evidence/2026-07-23-generated-block-item-rendering.md)
  records the dedicated item-model precedence rule, flat ladder versus 3D block
  regression coverage, and live crafting-result validation after hot reload.
- [Content-fidelity foundation evidence](evidence/2026-07-24-content-fidelity-foundation.md)
  records the OptiFine native-adapter decision, explicit model-culling and AO
  contracts, tiled animation support, and the ordered gates for EMF, ETF,
  GeckoLib, and Animated Java.
- [Content-fidelity native adapter evidence](evidence/2026-07-24-content-fidelity-native-adapters.md)
  records exact ETF/EMF/GeckoLib activation, headless ingestion, the neutral
  renderer bridge, transactional generation leases, existing-texture live
  retained-model swaps, focused tests, and the remaining runtime/visual gates.
- [Animated Java Blockbench export evidence](evidence/2026-07-24-animated-java-blockbench-export.md)
  pins the exact Blockbench/plugin/blueprint identities, proves an unmodified
  1.20.4 export, records its output manifest, and defines the Minosoft runtime,
  render, reload, and remote-server acceptance boundary.
- [Animated Java display-entity semantics evidence](evidence/2026-07-24-animated-java-display-semantics.md)
  records the mapped 1.20.4 display reference, shared visibility bounds/range,
  transform/shadow/text/teleport interpolation, exact-export regression gate,
  and the remaining outline, real-render, GPU, and remote-server boundary.
- [OpenGL resource-accounting evidence](evidence/2026-07-24-opengl-resource-accounting.md)
  records typed context-owned GPU name accounting, allocation rollback,
  teardown ownership, render diagnostics, and the remaining repeated-live
  baseline gate.
- [Minecraft 1.20.4 item-model predicate evidence](evidence/2026-07-24-item-model-predicates.md)
  audits the vanilla provider catalog, records complete audited-1.20.4 stack
  and live-context support plus dynamic world/display-item mesh replacement,
  protects unknown-provider behavior, records session-selected
  1.19.4/1.20.4 catalogs, and names the remaining older-catalog,
  component-dispatch, and rendered-reference gates.
- [Player-light evidence](evidence/2026-07-23-player-light.md) records the
  player-centered six-block falloff, profile/menu controls, per-fragment
  rendering correction, focused tests, and generation-19 live acceptance.
- [Stepped-slider evidence](evidence/2026-07-23-stepped-slider.md) records the
  reusable discrete slider contract, complete bounded-setting migration,
  focused/broad tests, and generation-23 visual and interaction acceptance.
- [Source-native settings-form evidence](evidence/2026-07-24-settings-forms.md)
  records typed staged entries, validation and rollback, dependency and restart
  state, cycle selection, clipped visible-row scrolling, owned Fabric screen
  adaptation, and the focused JVM gate.
- [Fabric UI framework and adapter evidence](evidence/2026-07-24-fabric-ui-framework.md)
  records categorized/searchable forms, virtual grids, dialogs, map and machine
  primitives, atomic adapter options, current-stack configuration mappings,
  richer JEI/Mod settings surfaces, and the remaining non-UI gameplay/data
  boundaries.
- [Right-drag crafting evidence](evidence/2026-07-23-right-drag-crafting.md)
  records the standard quick-craft packet sequence, once-only slot traversal,
  local prediction and eligibility rules, the transaction-queue eviction
  correction, and focused/broad regression gates.
- [Debug control-plane evidence](evidence/2026-07-22-debug-control-plane.md)
  records authenticated client/server IPC, framebuffer/input automation,
  state/AOI comparison, Fabric server diagnostics, permissions, and provider
  replacement across base/shared-core hot reloads.
- [Mob-rendering and launcher-title evidence](evidence/2026-07-23-mob-rendering-title-menu.md)
  records the model-less living-entity diagnosis, dedicated zombie limb/UV
  contract, diagnostic living-mob fallback, and Eros title-screen boundary.
- [Terralith world-generation evidence](evidence/2026-07-23-terralith-worldgen.md)
  records the pinned server-only artifact, recoverable world regeneration,
  Fabric Loader/datapack activation, and generated-chunk biome palettes.
- [Double-tap sprint evidence](evidence/2026-07-23-double-tap-sprint.md) records
  the render-to-tick action mailbox, double-press startup correction, focused
  and broad checks, and live sprint state acceptance.
- [Terratonic world-generation evidence](evidence/2026-07-23-terratonic-worldgen.md)
  records the pinned Tectonic mod, embedded compatibility-pack selection,
  recoverable regeneration, density-function join, and measured terrain.
- [Automation and observability evidence](evidence/2026-07-23-automation-observability.md)
  records semantic lifecycle predicates, JSON/JUnit scenarios, bounded endpoint
  metrics, world inspection/A-B hashing, visual failure artifacts, matrix runs,
  and outcome-driven JFR capture.
- [Minecraft 1.20.4 credits parity evidence](evidence/2026-07-23-credits-parity.md)
  records the original Minosoft/local-mod credit pipeline, viewport-bounded
  scrolling, speed tiers, respawn transition, and focused checks. The
  [local asset provenance evidence](evidence/2026-07-24-local-asset-provenance.md)
  records the removal of official network fallbacks. The
  [1.20.4 parity backlog](backlog/minecraft-1.20.4-parity.md) defines the wider
  behavioral gates without claiming global parity.
- [Crafty public API evidence](evidence/2026-07-24-crafty-api.md) records the
  opt-in bearer-authenticated endpoint coverage, caller-owned remote content,
  error/timeout contract, and crash-environment secret redaction.
- [Tech Reborn activation evidence](evidence/2026-07-21-tech-reborn-discovery.md)
  records the first Fabric-native industrial ladder, exact adapted activation,
  and the remaining gameplay, networking, and server boundaries.
- [Tech Reborn world-generation evidence](evidence/2026-07-21-tech-reborn-worldgen.md)
  proves source-native block/item/state registration, local-authority ore
  generation, deterministic regeneration, asset mounting, and the remaining
  graphics/server limits.
- `evidence/` contains dated, durable observations distilled from runtime JSONL.
  Raw `.run/play-events.jsonl` remains local and untracked.

## Maintenance rules

- Prefer stable paths and named entry points over exhaustive file lists.
- Link each architectural claim to source, a test, or accepted documentation.
- Keep present evidence, target direction, and unknowns visibly separate.
- Record acceptance gates for a trajectory, not an unbounded wish list.
- Date runtime evidence, name the session ID, and distinguish measured behavior
  from architectural targets.
- Remove stale guidance in the same change that invalidates it.
