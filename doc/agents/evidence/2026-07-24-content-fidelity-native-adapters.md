<!-- Copyright (C) 2026 Jacob Repp -->

# Content-fidelity native adapter evidence

## Outcome

OptiFine still cannot run as an ordinary Minosoft mod. The implemented path
supports selected OptiFine/Blockbench content through Minosoft-owned parsers and
renderer services. Exact Entity Texture Features 7.0.13, Entity Model Features
3.0.17, and GeckoLib 4.4.4 metadata now select adapters for Minecraft 1.20.4.
The adapters do not link or execute their Mojang-targeted entrypoints, mixins,
access wideners, or nested libraries.

Real pack inspection now reports `activation=adapted` for all four pinned
artifacts. Feature catalogs remain honest: ingestion is mapped while missing
runtime, API, rendering, reload, and configuration surfaces remain partial or
unmapped.

## Implemented contracts

- `AssetsManager.list` enumerates the effective namespaced resource view for
  directory, ZIP/file, client-JAR, index, and priority managers.
- `ContentFidelityLoader` discovers CEM JEM roots, referenced JPM parts,
  GeckoLib geometry/animation files, and ETF random-entity properties from that
  normal priority view. One malformed input rejects the entire candidate.
- `ContentGenerationStore` prepares before commit, preserves the active value
  on preparation/commit failure, leases readers, and defers cleanup until the
  final lease exits.
- `PlaySession` publishes loaded assets and their immutable content snapshot in
  one generation. Headless sessions execute the same parse path.
- CEM ingestion retains hierarchy, boxes, box/per-face UV, pivots, rotation,
  scale, axis inversion, mirroring flags, inflation, attachments, external JPM
  merge, and compiled expression bindings.
- The expression VM is bounded and reflection-free. Arithmetic, conditions,
  lazy branches, supported math functions, variables, and caller-owned
  deterministic random input are tested. A per-skeletal-instance evaluator
  preserves ordered `var.*`/`varb.*` state and applies transform/visibility
  outputs after neutral animation. Version/entity part aliases are resolved
  during binding for geometry, transforms, and expression targets; collisions
  fail instead of silently overwriting parts.
- ETF ingestion retains ordered predicates, deterministic weighted variants,
  string/wildcard/regex/boolean/numeric/range/custom conditions, stable cache
  ownership, and base/emissive/blink/blink-emissive frame state. The skeletal
  loader discovers the finite material set before upload, and entity rendering
  selects a cached frame and restores state after an explicit full-bright
  emissive pass. Runtime context now includes biome/weather/name/height/time,
  health and flags, team, villager/type data, color/tame/movement state, and
  depth/value-bounded nested entity NBT.
- GeckoLib ingestion retains geometry documents, parented bones, cubes,
  box/per-face UV, pivots, transforms, animation loop modes, numeric/expression
  channels, interpolation names, easing names, and arguments.
- Matching GeckoLib geometry and animation documents are joined by namespace
  and basename before publication. The native runtime evaluates loop/hold/once
  clips, expressions, linear/step/Catmull-Rom channels, and a bounded easing
  subset; named playback and transition blending can update retained bones. A
  separate source-native controller facade adds predicate decisions,
  restart/stop, concurrent replace/add layers, expression data, and
  generation-owned animatable caches. It intentionally does not claim binary
  linkage for classes compiled against GeckoLib and Mojang types.
- `SkeletalModelBinder` converts neutral geometry into the retained renderer
  model without OpenGL. Static transforms, direct OptiFine texture locations,
  cube/per-face UV, and UV rotation survive. The loader leases the content
  generation through GPU model lifetime; animal and humanoid renderers prefer a
  matching CEM filename/entity ID. `SkeletalModelComposer` preserves unrelated
  native parts, replaces only ordinary CEM targets, and gives `attach:true`
  roots private child transforms so their geometry/expressions do not move the
  native target.
- Baked skeletal models now have an explicit retirement boundary. Retirement
  rejects new instances but keeps uploaded meshes alive for existing retained
  instances; the last instance release unloads GPU buffers, while an unuploaded
  retired generation drops its CPU buffers. Each content model also holds its
  exact content-generation lease, keeping ETF catalog/cache state alive until
  that final release. This establishes safe old-model lifetime semantics but
  does not yet perform the live candidate swap.
- Item model overrides implement last-match `custom_model_data`, `damage`, and
  `damaged` predicates. Element-backed override models retain and bake their
  cuboid geometry instead of falling back to a flat sprite. Display entities
  retain protocol transformations and item/block/text metadata; native
  renderers cover transform interpolation, fixed/entity billboards, packed
  light overrides, wrapped/aligned text, opacity, background, shadow, and
  see-through state.
- Resource packs and data packs mount through distinct `assets/` and `data/`
  views and publish with the same session content generation. The datapack
  runtime discovers singular and plural function/tag paths, expands required
  and optional tags, executes load/tick functions, expands storage-backed
  macros, schedules delayed functions, and bounds recursion and per-tick
  command work.
- Local sessions lease that generation and run its load tag after the local
  world entity reset. A failed replacement load retains the previous executing
  function runtime. Candidate load evaluation is transactional: failure
  restores scoreboard/storage state and the prior session entity graph,
  including identity, IDs/UUIDs, command NBT/tags, transforms, mounts, removals,
  and creations. Disconnect removes the runtime tick callback before closing
  its lease.
- The local authority implements dummy scoreboards, command storage, SNBT,
  return semantics, selector filters, entity score holders, entity
  data/tag/kill/ride/teleport commands, and fail-closed unknown commands. Its
  bounded `execute` subset covers `as`, `at`, `on passengers`, `positioned`,
  `anchored`, coordinate/entity `facing`, `rotated`,
  entity/score/storage/function conditions, and result/success stores to score,
  storage, and entity NBT while retaining executor, position, rotation, and
  feet/eyes anchor through nested functions. Terminal entity conditions expose
  the match count required by the exporter.
- The local `summon` boundary accepts absolute, relative, and local positions
  and creates session-owned item, block, and text display, interaction, and
  marker entities. It retains transformations, billboard/light/text/item/block
  state, command tags, UUIDs, and bounded nested passenger attachment state.
  The shared `DisplayEntity` metadata class is registered for versioned
  protocol field lookup.
- A reduced exporter-shaped fixture records Animated Java 1.10.2 and upstream
  commit `a5fc548d2a53cc0887fa070db33ccfcef1cd3541`. Against 1.20.4 assets it proves
  data/resource discovery and load → summon/init → tick/frame mutation →
  removal. It is deliberately not represented as an unmodified exporter
  output.

## Automated evidence

Java 17 focused runs passed for:

- CEM JEM/JPM parsing and standalone JPM;
- GeckoLib geometry and animation ingestion;
- bounded expression evaluation and failure limits;
- entity-part alias version/entity precedence and removal;
- ETF rules, variants, custom predicates, cache closure, and material frames;
- resource discovery and malformed all-or-nothing candidate behavior;
- generation preparation/commit failure, reader retirement, and cleanup;
- neutral-to-renderer hierarchy, transform, texture, and UV binding;
- native-part preservation, targeted CEM replacement, and isolated attachment
  composition;
- loaded and pre-upload skeletal retirement with final-instance GPU unload/CPU
  drop and per-model content/cache lease accounting;
- Gecko clip attachment, runtime interpolation, expressions, loop modes, and
  controller transitions;
- item predicate selection and display transform interpolation;
- arbitrary cuboid item-model retention and integration-test compilation;
- datapack function/tag discovery, macro expansion, deterministic scheduling,
  recursion limits, and command budgets;
- separate data-namespace mounting, generation runtime publication,
  scoreboard/storage/SNBT command state, execute conditions/stores, selectors,
  entity mutation/lifecycle, and fail-closed dispatch;
- failed candidate-load rollback for scoreboard/storage state and for entity
  creation, removal, NBT, tags, transforms, identity, ownership, and mounts;
- a real 1.20.4 registry integration fixture that summons an item-display root
  with a text-display passenger and verifies transformation, material, tag,
  light, and attachment state;
- the reduced pinned Animated Java 1.10.2 lifecycle fixture described above;
- a durable headless fixture that binds one CEM/ETF/Gecko pack for both 1.19.4
  and 1.20.4, including version-specific CEM aliases, expression/clip
  evaluation, and ETF variant/emissive discovery;
- exact ETF/EMF/GeckoLib adapter activation and scope cleanup;
- functionality catalog capability honesty.

The real pinned pack was inspected with:

```text
MINOSOFT_JAVA_HOME=/tmp/minosoft-temurin17/Contents/Home \
./play.sh modpack inspect content-fidelity \
  --trajectory content-fidelity-etf
```

It reported ETF `7.0.13`, EMF `3.0.17`, GeckoLib `4.4.4`, and Fabric API as
adapted with no artifact blockers or dependency issues.

No live visual or repeated GPU-reload acceptance is claimed by this evidence.

## Remaining gates

1. Complete the EMF entity/version part-alias catalog, variable catalog, exact
   absolute part-property behavior, complex attachment parity, renderer feature layers,
   fallback diagnostics, and reference captures.
2. Complete ETF predicate parity, feature/player textures,
   configuration, live reload, and visual reference captures. The retained
   skeletal-entity base/emissive path is implemented but is not the whole ETF
   surface.
3. Complete Gecko easing, events, render layers, automatic model/controller
   routing, and validate the source-native controller/cache facade with
   dependent mods. Binary GeckoLib/Mojang compatibility remains a separate
   explicit project or requires exact native adapters per dependent mod.
4. Complete the remaining Animated Java surface: exporter-used target/attacker
   relationships, data-manager and UUID utilities, and interaction callbacks.
   Replace or supplement the reduced
   pinned fixture with unmodified exporter output and prove remote-server
   behavior.
5. Extend the implemented content-generation, failed-load command/entity
   transactions, and retained-model retirement through render-thread candidate
   bake/apply, then add repeated real-GL unload accounting, broader
   multi-version fixtures, and live visual captures
   before any “fully supported” claim.
