<!-- Copyright (C) 2026 Jacob Repp -->

# Object-model evidence map

## Boundary

The object model represents Minecraft concepts independent of how they are drawn:
resource locations, registries, blocks/items, entity data, containers, chat,
chunks, dimensions, and session-owned world state.

## Evidence map

| Status | Claim | Evidence |
| --- | --- | --- |
| Observed | `data/` is the dominant model package and contains registries, entities, containers, text, and world state. | `src/main/java/de/bixilon/minosoft/data/`. |
| Observed | Registries are version-aware and can inherit from a parent set. | `data/registries/registries/Registries.kt` owns `Version`, registry loading, and `parent`. |
| Observed | A `World` belongs to one `PlaySession`. | `World(val session: PlaySession)` and `PlaySession.val world`. |
| Observed | Entities also carry their owning play session. | `data/entities/entities/Entity.kt`. |
| Verified | Chunk, registry, entity-data, container, text, and model behavior have broad fixture-backed coverage. | Matching unit/integration packages and `src/integration-test/resources/chunk/` and `packets/`. |
| Observed | The model boundary is currently porous. | `Entity` exposes renderer state and imports GUI/physics; `data` imports protocol, GUI, input, and camera types. |
| Verified | Adapted Fabric content can install deterministic block/item registry IDs and a separate property-bearing block-state palette into one play session. | `FabricWorldContentReader`, `FabricRegistrySnapshot`, `FabricSessionContentBridge`, focused tests, and [Tech Reborn world-generation evidence](../evidence/2026-07-21-tech-reborn-worldgen.md). |
| Verified | Player arm-swing timing is shared player state rather than first- or third-person renderer-local state; active swings are bounded to 200 ms and do not restart on every held-input callback. | `ArmSwingState`, `PlayerEntity.swingHand`, focused timing tests, and [harvesting evidence](../evidence/2026-07-22-resource-pack-opengl-audio.md). |
| Verified | Inventory-bearing server containers map their 36 player slots into the session-owned `PlayerInventory` through actual slot-map changes, so hotbar add/replace/remove state remains identical during and after a crafting session. | `InventorySynchronizedContainer`, `InventorySynchronizedContainerTest`, and [Inventory Management evidence](../evidence/2026-07-22-inventory-management.md). |
| Verified | The Creative inventory catalog derives its deterministic pages from the active session item registry and hands selected stacks to the existing player-container creative action path; no version-specific item catalog is duplicated in GUI state. | `CreativeItemCatalogElement`, `CreativeCatalogPagerTest`, and [creative catalog evidence](../evidence/2026-07-23-creative-catalog-gamemode-aliases.md). |
| Verified | Session-owned local display and interaction entities retain command-visible position, rotation, tags, nested data, and a canonical four-signed-int UUID view. Entity-backed datapack macros and `data ... from entity` read the same snapshot, so Animated Java callback and UUID utilities do not create a second entity truth. | `LocalDataPackEntityAccess`, `LocalDataPackCommandAuthority`, `InteractionEntity`, `LocalDisplayEntityFactoryTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | Entity leash ownership is distinct from vehicle/passenger state. Modern attach packets mutate `leashHolder`; legacy packets retain their explicit vehicle-versus-leash mode, and local datapack transaction rollback restores both relationships. | `EntityAttachment`, `EntityAttachS2CP`, `LocalDataPackEntityAccess`, `LocalDisplayEntityFactoryTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | Mob body yaw is client-derived state, not an alias for protocol entity yaw. `EntityRenderInfo` retains previous/current vanilla body-control and head-yaw values, applies the moving/stationary/rider rules, and exposes one partial-tick body pose to skeletal roots and attached render features. Non-mob entities currently fall back to interpolated entity yaw. | `EntityBodyRotation`, `EntityRenderInfo`, `Mob.maxHeadRotation`, `EntityBodyRotationTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | Living entities retain the client-visible ten-tick hurt countdown and death animation age as model state. Damage events restart the hurt countdown; normal ticks decrement it and advance or clear death age from synchronized health. EMF expressions consume the same counters with render partial ticks rather than inventing renderer-local timers. | `LivingEntity.hurtTime`, `LivingEntity.deathTime`, `CemEntityExpressionContextFactory`, `CemEntityExpressionContextFactoryTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | Living entities derive elapsed active-item use ticks from synchronized hand flags and the equipped stack identity. Timing resets on stop, hand change, or stack replacement and supplies remote bow/crossbow/brush item-model predicates without renderer-owned clocks. | `LivingEntity.itemUseTicks`, `ItemPredicateContext`, 1.19.4/1.20.4 `ItemFeaturePredicateTest`, and [item-predicate evidence](../evidence/2026-07-24-item-model-predicates.md). |
| Verified | Biome precipitation is position-dependent model state. The native biome model preserves the temperature modifier and evaluates Minecraft 1.20.4's fixed-seed simplex noise, frozen-ocean patches, high-altitude cooling, and `0.15` rain/snow threshold without importing Mojang runtime classes. EMF wetness consumes this model result instead of treating a biome's base precipitation label as sufficient. | `Biome.temperatureAt`, `Biome.precipitationAt`, `VanillaBiomeTemperature`, `VanillaBiomeTemperatureTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | A world-level block revision advances once for each non-empty post-commit Fabric mutation batch. Presentation caches can therefore invalidate after terrain changes without treating the occlusion counter as a general block-change signal. | `World.blockRevision`, `FabricBlockMutationEvents`, the stationary-shadow mutation case in `EntityRenderEffectFeatureTest`, and [content-fidelity adapter evidence](../evidence/2026-07-24-content-fidelity-native-adapters.md). |
| Verified | Modern biome tag packets decode against the session biome registry, and tag membership can be queried without duplicating biome identity in the renderer. ETF derives `biomeTag` from this session-owned state while retaining the legacy tag path for older protocol versions. | `TagsS2CP`, `MinecraftTagTypes.BIOME`, `TagList.matching`, `EntityTextureContextFactory`, `TagListTest`, and `EntityTextureContextFactoryTest`. |

## Stable contracts

- Never share mutable world, entity, registry, or container state across play
  sessions.
- Keep resource identifiers and registry lookup semantics stable at boundaries.
- Protocol decoding populates the model; graphics derives presentation state.
  Neither wire layout nor a GPU cache should become the model's source of truth.
- Version/dimension-dependent coordinate, palette, and registry assumptions must
  remain explicit.
- Runtime registry additions require one immutable fingerprint and explicit
  authority agreement. Never infer cross-process compatibility from matching
  identifiers alone.

## Trajectory

**Target:** make the object model usable by headless clients, local simulation,
graphics, and mods without requiring a graphical runtime. Move renderer handles
and UI behavior behind adapters/events as touched. Favor explicit immutable value
objects and lifecycle-owned mutable aggregates over new global registries.

## Next evidence

1. Map identity and ownership for registry entries, worlds, chunks, and entities.
2. Mark model types that embed rendering, input, or protocol implementation.
3. Record mutation/event paths for blocks, light, entity data, and containers.
4. Establish serialization/compatibility tests for types exposed to mods.

## Validation

- `src/test/java/de/bixilon/minosoft/data/`
- `src/integration-test/kotlin/de/bixilon/minosoft/data/`
- Relevant packet fixtures when model construction begins at the wire boundary
- Boundary-version and boundary-coordinate cases for registry/chunk changes

## References

- [Architecture: Version data](../../Architecture.md#version-data)
- [Minecraft versions](../../MinecraftVersions.md)
- [Version support](../../VersionSupport.md)
