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
| Verified | Crafty API 1.0.0 is available through an explicit bearer-authenticated client covering player search/profile, Java/Bedrock server ping, skin retrieval, and all six documented image generators. It is not initialized at boot and does not mount returned content as assets. | `integrations/crafty/`, focused transport tests, [user documentation](../../CraftyAPI.md), [test plan](../../CraftyAPI-TestPlan.md), and [Crafty API evidence](../evidence/2026-07-24-crafty-api.md). |
| Verified | Crash-report environment capture redacts secret-shaped variable names, including `CRAFTY_API_TOKEN`, while preserving non-sensitive diagnostics. | `EnvironmentSanitizer`, `RuntimeSection`, and `EnvironmentSanitizerTest`. |
| Verified | Distant-terrain persistence is profile-owned rather than packaged or runtime-global. A versioned, palette-encoded GZIP file is keyed by connection and world identity, bounded during decode, written through a coalescing I/O worker, and atomically replaced; it contains resource identifiers rather than live registry IDs or chunk references. | `DistantLodPersistence`, `DistantLodPersistenceWriter`, focused round-trip tests, and [DH/Bliss integration evidence](../evidence/2026-07-29-distant-horizons-bliss-integration.md). |
| Verified | Java 17 is the CI runtime while compiled output targets JVM 11. | Both CI definitions select Java 17; `build.gradle.kts` sets Java/Kotlin target 11. |
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
