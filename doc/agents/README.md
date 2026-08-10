<!-- Copyright (C) 2026 Jacob Repp -->

# Agent grounding

This directory holds layered evidence maps for agent work: what is observed,
where the evidence lives, which boundaries are intentional, and what trajectory
should guide the next change. It complements human-facing documentation in
`doc/`; it does not duplicate it.

The order below is an investigation order, not a claim that the
application-heavy codebase already has a fully clean dependency graph. Start at
the first layer affected by a task and follow references upward.

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
- [Java 25 build baseline](evidence/2026-07-30-java-25-baseline.md) records the
  repository-wide runtime/bytecode decision, compatible build stack, class-file
  inspection, complete test gate, and CI packaging checks.
- [Architecture and trajectory checkpoint](evidence/2026-07-30-architecture-trajectory.md)
  records centralized JVM conventions, the first four trajectory primitives,
  the headless render-contract boundary, shared generation ownership, Java 25
  native-access/Gradle 10 cleanup, and the preferred OpenGL 4.3 tier.
- [Terrain consolidation implementation start](evidence/2026-07-30-terrain-consolidation-start.md)
  records the initial Phase 0/Phase 1 headless contracts, diagnostic-schema
  freeze plus provider-only live adoption, package-direction ratchets, neutral
  near-provider, distant-data, and distant-renderer ownership moves, focused
  gates, plus the first Phase 2 world-epoch, consolidated-identity, bounded
  scheduler/mailbox, and near-production adoption boundary.
- [Agent trajectory tooling backlog](backlog/agent-trajectory-tooling.md)
  prioritizes runtime leases, atomic diagnosis bundles, safe world snapshots,
  compare-and-restore checkpoints, visual A/B automation, and isolated
  world/server creation. It labels implemented slices and remaining targets.
- [Recent terrain and visual trajectory summary](evidence/2026-08-01-recent-trajectory-summary.md)
  separates the accepted July 31/August 1 terrain gates from the independent
  open DH foreground-ownership and Iris/Complementary corruption defects. The
  [root-cause test plan](backlog/recent-terrain-visual-root-cause-test-plan.md)
  maps both defects onto the repository's headless, exact-pack, real-OpenGL,
  scenario, ownership, and restoration scaffolds.
- [Medium diverse-biomes visual remediation](evidence/2026-08-03-medium-diverse-visual-remediation.md)
  closes the independent distant-lighting, seam, remote-persistence, and
  Complementary motion-transient diagnoses with focused and live evidence.
- [Distant-terrain meshing and cardinal-view baselines](evidence/2026-08-09-distant-terrain-meshing-view-baselines.md)
  records the independent 2,048-case heightfield oracle plus structured and
  multi-run shelf/overhang coverage, exact region-encoder packing baselines,
  and four-direction real-OpenGL selection and pixel gate.
- [Render-performance and OpenGL submission audit](evidence/2026-08-01-render-performance-opengl-audit.md)
  grounds the current region multi-draw, Iris binding, particle/entity churn,
  OpenGL state-cache, measurement gaps, and ranked optimization trajectory in
  current source plus retained Apple OpenGL evidence.
- [Render-performance and OpenGL optimization implementation](evidence/2026-08-02-render-performance-opengl-optimization.md)
  records the six implemented source contracts: complete physical-work
  counters, unit/target texture state, Iris binding revisions, retained particle
  capacities, cached terrain packets, and explicit entity state keys. Matched
  real-OpenGL performance acceptance remains separate.
- [Blockbench producer-integration protocol](acceptance/blockbench.md) defines
  the isolated desktop export boundary, captured-output contract, automated
  Minosoft fixture gate, and the separate real-render and remote-server gates.
- [Canary/base reload evidence](evidence/2026-07-21-canary-reload.md) compares
  the separate mod and host compilation lanes.
- [Sodium activation evidence](evidence/2026-07-21-sodium-activation.md) proves
  adapted pack activation and graphics-hook execution.
- [Sodium terrain visual-fidelity evidence](evidence/2026-07-27-sodium-visual-fidelity.md)
  records four-vertex light/color terrain, smooth solid/fluid lighting and AO,
  cached biome blending, the Iris attribute bridge, and the live visual canary.
- [Terrain runtime architecture](../design/terrain-runtime-architecture.md)
  records the Minosoft scheduler, mesher, visibility, batching, and upload
  baseline plus an implementation-neutral prioritized improvement path.
- [Render-substrate target architecture](backlog/render-substrate.md) defines
  the single replacement pipeline, built-in/optimized-terrain/shader-pipeline
  ownership contracts, objective completion function, delivery rungs, and
  small-model support queue.
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
- [Creative creature catalog evidence](evidence/2026-07-26-creative-creature-catalog.md)
  records the searchable entity-registry tab, production-model preview
  boundary, and server-authoritative one-click spawn path.
- [Iris and JEI activation evidence](evidence/2026-07-23-iris-jei-activation.md)
  records their exact pinned artifacts, blocker-free adapted activation,
  generation-owned render/reload and recipe-viewer hooks, live invocation and
  cleanup evidence, and the GUI quad-index invariant exposed by the recipe view.
- [Iris and JEI behavioral acceptance](evidence/2026-07-23-iris-jei-acceptance.md)
- [Iris render-pipeline support boundary](evidence/2026-07-26-iris-render-pipeline-support.md)
  records a clean four-generation supervised run, a real render-queue shader
  reload, JEI server presence and complete recipe-view navigation, stable
  parent/server ownership, and the launcher/navigation defects found by the
  stricter gates.
- [Complementary motion-noise and hand evidence](evidence/2026-07-28-complementary-motion-and-hand.md)
  separates pack-authored temporal cloud/foliage sampling from duplicate
  rendering, records the managed motion-quality profile, and restores the
  pinned Iris cutout alpha test for first-person arm and held-item routes.
- [Camera-motion noise measurement evidence](evidence/2026-07-28-camera-motion-noise-measurement.md)
  records the same-pose motion/control metric, representative-cadence override,
  live cloud-region convergence result, and exact pose/throttle restoration.
- [Cloud and foliage motion-noise diagnosis](evidence/2026-07-28-cloud-foliage-motion-noise-diagnosis.md)
  separates fullscreen shader clouds, authored foliage waving, TAA-jitter
  speckle, stable terrain publication, and entity distance boundaries while
  keeping the entity pixel A/B explicitly unaccepted.
- [Complementary temporal-stability implementation](evidence/2026-07-29-complementary-temporal-stability.md)
  records the managed zero-jitter/static-foliage defaults, previous-wind motion
  vectors, non-jittered material-254 routes, distance/mip foliage stabilization,
  world-reprojected cloud history, and live disabled/enabled-waving driver
  gates.
- [Distant Horizons and Bliss integration checkpoint](evidence/2026-07-29-distant-horizons-bliss-integration.md)
  records the exact DH/Bliss artifacts, bounded explored/generated/network LOD
  store, cross-session persistence, managed-server transfer, native settings,
  narrow dependency exception, focused selectable pack with Terralith/Tectonic,
  executable distant terrain/water/shadow ABI, and driver-bound exact-Bliss
  validation on a large diverse world.
- [Chest-rendering evidence](evidence/2026-07-23-chest-rendering.md) records the
  `builtin/entity` item fallback gap, entity-backed section invalidation
  contract, the 61-block-item vanilla special-render audit, crafted-shield
  fallback, namespace-agnostic mod diagnostics, focused and broad regressions,
  and a live harvest/pickup/place sequence that remained on one client
  generation.
- [Generated block-item rendering evidence](evidence/2026-07-23-generated-block-item-rendering.md)
  records the dedicated item-model precedence rule, flat ladder versus 3D block
  regression coverage, and live crafting-result validation after hot reload.
- [Asset primitive decomposition evidence](evidence/2026-08-08-asset-primitive-decomposition.md)
  is a generation contract for the remaining 454 audited missing texture
  targets: it specifies the shared primitive/shape/material vocabulary, exact
  palette and tone tables, the path-keyed family classifier, and per-family
  raster recipes with the FNV-1a/Java-Random determinism rule so another
  generation process can reproduce the assets byte-identically for intake into
  the standalone content pipeline.
- [Asset preview continuation evidence](evidence/2026-08-08-asset-preview-continuation.md)
  closes local block placement, lighting, and structured-rejection acceptance;
  isolates the transient GL error stage, records clean-reference rules, and
  identifies the remaining external content-forge model-contract boundary.
- [Offline integration asset evidence](evidence/2026-08-10-offline-integration-assets.md)
  records the Mojang-index-independent renderer/credits stand-in, the optional
  out-of-source content-forge/Blockbench validation lane, and the currently
  stale external provenance boundary.
- [Content-fidelity foundation evidence](evidence/2026-07-24-content-fidelity-foundation.md)
  records the OptiFine native-adapter decision, explicit model-culling and AO
  contracts, tiled animation support, and the ordered gates for EMF, ETF,
  GeckoLib, and Animated Java.
- [Content-fidelity native adapter evidence](evidence/2026-07-24-content-fidelity-native-adapters.md)
  records exact ETF/EMF/GeckoLib activation, headless ingestion, the neutral
  renderer bridge, transactional generation leases, existing-texture live
  retained-model swaps, focused tests, and the remaining runtime/visual gates.
- [Content-fidelity continuation-gate completion](evidence/2026-07-26-content-fidelity-gate-completion.md)
  audits every named EMF, ETF, GeckoLib, Animated Java, headless, multi-version,
  transactional-reload, and GPU-cleanup requirement against current source,
  automated tests, managed fixtures, and final real-OpenGL records while
  keeping broader upstream parity explicitly separate.
- [EMF/ETF living-entity render reference evidence](evidence/2026-07-26-emf-etf-render-reference.md)
  records the managed zombie fixture, exact `rule_index` boundary, settled
  zero-tolerance OpenGL reference, rejected upload/publication accounting, and
  the distinction between a consumer fixture and a real Blockbench export.
- [Naturalist registry-sync evidence](evidence/2026-07-26-naturalist-registry-sync.md)
  records the pinned entity surface, standard Fabric configuration handshake,
  owner/session transaction boundary, focused dependent-mod integration gate,
  and the exact live-render and tracked-data work still required.
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
- [Render-churn performance correction](evidence/2026-07-31-render-churn-performance.md)
  records persistent hitbox buffers, replacement cleanup, attributed terrain
  requests, primitive tint caching, hot draw-state identifiers, focused gates,
  and the fixed-pose live rerun.
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
