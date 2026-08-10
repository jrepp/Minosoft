<!-- Copyright (C) 2026 Jacob Repp -->

# Base evidence map

## Boundary

Base is the process/runtime substrate used by higher layers: application boot,
shared utilities, configuration/profile persistence, assets/resources, logging,
properties, updating, and data-fixing support. It is a conceptual layer inside a
single Gradle module, not a source module today.

## Evidence map

| Status | Claim | Evidence |
| --- | --- | --- |
| Observed | `de.bixilon.minosoft.Minosoft` is the application entry point. | `build.gradle.kts` sets the main class; `Minosoft.kt` coordinates boot tasks and UI/headless selection. |
| Observed | Shared services are spread across top-level packages. | `assets/`, `config/`, `datafixer/`, `main/`, `properties/`, `updater/`, and `util/`. |
| Observed | Assets are layered rather than one immutable classpath tree. | Asset managers cover directories, Minecraft jars/indexes, multiple priorities, resources, and sessions. |
| Verified | Packwiz resource-pack ZIPs are independently SHA-verified, staged outside executable mods, atomically materialized into the trajectory profile before launch, and mounted ahead of vanilla assets. | `Play.prepareModpack`, `materializeResourcePackProfile`, `AssetsLoader`, and [resource-pack evidence](../evidence/2026-07-22-resource-pack-stack.md). |
| Observed | The content-system contract documents exact session priority, vanilla model/texture support, parsed-but-unused metadata, local-only provenance, and the boundary between immutable format ingestion and executable mod adaptation. | [Content and asset system](../../Assets.md), `AssetsLoader`, `PriorityAssetsManager`, model/texture loaders, and their focused tests. |
| Verified | Initial session assets load as a candidate before assignment; a preparation failure unloads that candidate and leaves `PlaySession.assets` uninitialized, while Fabric API observes prepare/apply/complete/failed lifecycle phases. | `PlaySession.load`, `FabricResourceReloadEvents`, its focused transaction tests, and [resource-reload evidence](../evidence/2026-07-22-fabric-resource-reload-events.md). |
| Verified | Jar/index compatibility assets are local-cache-only. A missing local archive, index, or indexed object fails with an import-local-assets instruction; these managers contain no official-service network fallback. Resource identifiers such as `minecraft:*` remain valid compatibility keys and do not establish content provenance. | `LocalAssetSource`, `JarAssetsManager`, `IndexAssetsManager`, focused tests, and [local asset provenance evidence](../evidence/2026-07-24-local-asset-provenance.md). |
| Verified | The default checked-in standalone manifest composes cumulative deterministic audit-generated compatibility content, safe VoxeLibre mappings, managed-mod assets, loose sources, and exact Faithful 32x, Vanilla Evolved, Open Assets Lib, and GUI Revision archives into one bounded, path-contained, hash-addressed resource-pack layout. Faithful supplies its bitmap font and broad assets, Vanilla Evolved refines compatible vanilla paths, Open Assets Lib exposes its reusable namespace, and GUI Revision wins final compatible UI priority. Required notices accompany the view without copying external media into source. The generated source emits conservative missing-model scaffolds, deterministic water-overlay, entity-glint, shadow, and vignette textures, a transparent ETF nose compatibility placeholder that the runtime-derived skin material replaces when needed, and a deterministic rasterization library for every other audited missing texture target (spawn eggs, banners, candle cakes, glass panes, beds, shulker boxes, doors, wood/hyphae bark, hanging signs, heads, coral fans, infested stone, potted plants, waxed copper, and generic block/item tiles) keyed by resource path. The composer drops animation sidecars whose explicit frames became invalid after a higher-priority static texture won. VoxeLibre imports whole files and safe non-blacklisted slices while rejecting every blacklist-marked row. Views and provenance remain out of source; named manifest boundaries and audit stages support repeatable layer-by-layer comparison. Fingerprint and emitted-file determinism is pinned by focused tests and cross-process probes: the generated-provider fingerprint iterates a preserved sorted `TreeMap` rather than an order-unstable immutable copy, and the composer and its fingerprint walk exclude macOS/editor metadata (`.DS_Store`, `._*`, `*~`, `Thumbs.db`) that Finder otherwise injects into store directories. Directory inputs are copied into the immutable published view, and higher-priority overrides replace only that independent copy. The `play.sh content queue` command triages every cumulative audited target into two work lanes: `generate` (a distinct raster family or non-trivial model shape exists) versus `select` (only a generic placeholder exists, so the real asset should be chosen from Faithful, Vanilla Evolved, VoxeLibre, a mod, or a user overlay), and marks targets whose authored asset already wins in the composed stack as `resolved`, probes each concrete non-generated package for exact and sibling-directory near-name candidates, and emits a deterministic fingerprint alongside counts (including `selectWithCandidates` versus `selectUnavailable`), per-entry candidates, family/shape detail, consumers, and selection sources. A documented scoring rule ranks the backlog (`--top K`, optional `--authoring` for new-authored-input only), and `--csv` atomically exports the full triage as a deterministic RFC-4180 CSV for production handoff. | `content-stacks/standalone.json`, `modpacks/distant-horizons-bliss/resourcepacks/`, `GeneratedContentPack`, `GeneratedTextureLibrary`, `ContentStackManifest`, `ContentPackComposer`, `ContentPackAdapter`, their focused tests, `Play.materializeAssetProfile`, and the [terrain testing guide](../../contributing/TerrainTesting.md#open-content-local-dojo). |
| Verified | The remaining standalone missing-texture backlog is covered by a documented generation contract: 454 audited `minecraft:` block/item targets decompose into 12 primitives, 12 shapes, 8 materials, and 5 palettes, each family has an exact 16x16 raster recipe, and the path-keyed FNV-1a/Java-Random seed rule plus fixed tone tables let another generation process reproduce byte-identical assets for intake. | [Asset primitive decomposition evidence](../evidence/2026-08-08-asset-primitive-decomposition.md), `GeneratedTextureLibrary`, `ContentSubmissionQueue`, and the `terrain-local-dojo` cumulative stage audits. |
| Verified | Each render generation owns a bounded missing-content inventory populated only when registry blockstate/model lookup or a file-backed texture load reaches the actual fallback boundary. Snapshots retain the lexicographically smallest bounded set, sort and deduplicate consumers, publish resource-pack target paths and inventory counts, and hash only stable content fields. `content.audit` exposes the snapshot without timestamps or frame counters; `play.sh content audit` atomically writes it beneath `.run/content-audits/` by default. | `ContentAssetAudit`, `BlockLoader`, `ItemLoader`, `Texture`, `ClientDebugChannel`, `Play.runContentAudit`, focused determinism tests, and the live standalone audit workflow. |
| Verified | Crafty API 1.0.0 is available through an explicit bearer-authenticated client covering player search/profile, Java/Bedrock server ping, skin retrieval, and all six documented image generators. It is not initialized at boot and does not mount returned content as assets. | `integrations/crafty/`, focused transport tests, [user documentation](../../CraftyAPI.md), [test plan](../../CraftyAPI-TestPlan.md), and [Crafty API evidence](../evidence/2026-07-24-crafty-api.md). |
| Verified | Crash-report environment capture redacts secret-shaped variable names, including `CRAFTY_API_TOKEN`, while preserving non-sensitive diagnostics. | `EnvironmentSanitizer`, `RuntimeSection`, and `EnvironmentSanitizerTest`. |
| Verified | Distant-terrain persistence is profile-owned rather than packaged or runtime-global. The former palette-encoded GZIP schema is now a bounded read-only decoder pinned by a checked fixture; retained tiles migrate one-way into the checksum-protected page-oriented schema-v2 directory store. Only the bounded dirty-page writer remains authoritative, so updates scale with changed pages and no whole-database worker writes a second store. Records contain semantic resource identities rather than live registry IDs or chunk references. | `DistantLodPersistence`, `DistantDirectoryTerrainStore`, `DistantTerrainStoreWriter`, checked v1/v2 codec and migration fixtures, and the [terrain consolidation implementation start](../evidence/2026-07-30-terrain-consolidation-start.md). |
| Verified | Java 25 is the CI, build, test, and launch runtime, and every compiled artifact targets JVM 25. | The CI workflow selects Java 25; the root, `debug-core`, `play-util`, and `debug-server-fabric` builds use Java 25 toolchains/releases; `play.sh` and `Play` enforce Java 25; and the [Java 25 baseline](../evidence/2026-07-30-java-25-baseline.md) verifies class-file major version 69. |
| Observed | Headless operation is supported. | `Minosoft.kt`, terminal arguments, and [Headless mode](../../Headless.md). |
| Observed | Base is not dependency-clean yet. | Imports from `util`, `config`, and `assets` reach into data, protocol, terminal, and GUI packages. |
| Verified | The `debug-core` Java library owns versioned local IPC DTOs, discovery, launch credentials, Unix/Windows transports, framing, operation ownership, deadlines, and the typed client used by CLI/tests without depending on GUI or mutable session implementations. | `debug-core/`, `:debug-core:test`, and [debug control-plane evidence](../evidence/2026-07-22-debug-control-plane.md). |

## Stable contracts

- Do not make base initialization require JavaFX, an OpenGL context, or a live
  server connection.
- Keep user profiles and runtime caches distinct from packaged/generated assets.
- Preserve resource namespaces, version selection, and priority layering.
- Treat resource identifiers as compatibility keys, not proof of ownership.
  Bundled/displayed payloads must be Minosoft-authored or supplied from the
  user's local system or installed mods; official-service network retrieval is
  outside the asset-manager contract.
- Process-wide state must be intentional; session-specific state belongs above
  this layer.

## Trajectory

**Target:** identify a small, stable kernel/API footprint that can remain loaded
while client and mod generations are replaced. It should contain bootstrap,
loader coordination, lifecycle contracts, diagnostics, and only the services
needed to construct a client generation. New contracts should use plain
Kotlin/JDK types or kernel-owned types and make ownership explicit
(`AutoCloseable` or an equivalent handle).

The kernel must not expose implementation objects from a reloadable generation.
Cross-generation calls use stable interfaces, messages, or opaque handles; this
is what allows a discarded classloader to become collectible.

This is not authorization for a large package move. First add dependency evidence
and seams around behavior being changed; extract modules only when tests can hold
the boundary.

## Next evidence

1. Generate a repeatable top-level package dependency report.
2. Identify process globals and classify them as kernel-stable,
   generation-owned, immutable, or accidental.
3. Map profile/config persistence formats and migration/default behavior.
4. Extend candidate-before-apply from initial session assets to changed-resource
   replacement, including explicit old-manager disposal after a successful swap.

## Validation

- Unit tests: `src/test/java/de/bixilon/minosoft/`
- Base-oriented integration tests: `assets/`, `config/`, `updater/`, and `util/`
  beneath `src/integration-test/kotlin/de/bixilon/minosoft/`
- Exercise a headless path when changing boot or shared initialization.

## References

- [Architecture](../../Architecture.md)
- [Content and asset system](../../Assets.md)
- [Headless mode](../../Headless.md)
