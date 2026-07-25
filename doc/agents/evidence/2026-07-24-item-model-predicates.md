<!-- Copyright (C) 2026 Jacob Repp -->

# Minecraft 1.20.4 item-model predicate evidence

## Boundary and source

This evidence is scoped to legacy JSON item-model `overrides` for Minecraft
Java Edition 1.20.4. It does not cover the component-based item-model format
introduced by later versions.

The authoritative comparison was the mapped Minecraft 1.20.4 client class
`net.minecraft.client.item.ModelPredicateProviderRegistry`, reached through
Yarn `1.20.4+build.3` in the repository's Fabric Loom cache. The audited merged
JAR has SHA-256
`a153a6cfeaf62ac183573ae38db58bd9a9fbcdfbbaec10600ffa6b5e087c7212`;
the mapping JAR has SHA-256
`d61e0d3031d6518ea00b3ca401af9a8c2dd4a8b07e7a343ba340b382f6117759`.

The registry exposes global `damaged`, `damage`, `lefthanded`, `cooldown`,
`trim_type`, and `custom_model_data` providers. Its item-specific providers are
`pull`, `brushing`, `pulling`, `filled`, `time`, `angle`, `charged`,
`firework`, `broken`, `cast`, `blocking`, `throwing`, `level`, and `tooting`.
An unavailable provider contributes negative infinity during override
selection; it is not the numeric value zero.

## Implemented support

`ItemPredicate` now preserves vanilla last-match ordering and fails closed for
unknown or wrong-item predicates, including negative thresholds.
`ItemPredicateContext` separates deterministic stack state from live
entity/world inputs and gives native adapters an explicit finite-value
extension boundary.

| Predicate | Minosoft 1.20.4 source | Proven scope |
| --- | --- | --- |
| `custom_model_data` | Stack NBT; first float is also accepted from the newer component-shaped representation | Headless and exact Animated Java export |
| `damage`, `damaged` | `DurableItem` remaining durability and unbreakable state | Headless |
| `charged`, `firework` | Crossbow `Charged` and `ChargedProjectiles` NBT | Headless |
| `broken` | Elytra remaining durability | Headless |
| `level` | Light-item `BlockStateTag.level`, with vanilla's missing-value fallback | Headless |
| `lefthanded` | Live player main arm | First-person and GUI resolution |
| `cooldown` | Local player's live cooldown interval | First-person and GUI resolution |
| `pull`, `pulling` | Active stack identity and elapsed use ticks; crossbow quick-charge enchantment and charged state | Local and remote living entities |
| `brushing` | Active stack and elapsed use ticks | Local and remote living entities |
| `cast` | Session fishing-bobber ownership | First-person and GUI resolution |
| `blocking`, `throwing`, `tooting` | Active stack identity | First-person living-entity resolution |
| `filled` | Bundle `Items`, registry-backed maximum stack sizes, nested-bundle cost, and occupied beehive/bee-nest saturation | Headless registry integration |
| `time` | Natural-dimension sky angle or deterministic scatter with vanilla wrapped interpolation and damping | Headless world integration and live entity mesh replacement |
| `angle` | Spawn, lodestone, or last-death target; dimension/distance checks, item seed scatter, and local-player wrapped interpolation | Headless world/entity integration |
| `trim_type` | Trimmable armor gate and the fixed 1.20.4 quartz-through-amethyst material indices | Headless registry/tag integration |

`ItemPredicateContext.values` can supply an exact namespaced value from a
headless fixture, future world/registry implementation, or source-native
compatibility adapter. Values must be finite.

`ItemPredicateRuntime` is owned by `RenderContext`, matching the retained
provider state in the audited client instead of attaching smoothing state to an
item stack or content generation. Initialize and respawn packets retain the
local player's last-death position for recovery-compass resolution.
First-person and GUI items resolve the current override on draw. `ItemFeature`
does so on each update; when a live threshold crossing selects a different
model, the normal entity render lifecycle retires the stale mesh and rebuilds
from the new model.

Every provider registered by the audited 1.20.4 catalog now has an
implementation. `ItemPredicateCatalog` is selected from the entity's session:
1.19.4 excludes the `brushing` and `trim_type` providers introduced in 1.20,
while 1.20.4 admits the full audited set. Older releases use a deliberately
named compatibility catalog until their provider boundaries are audited; this
is not a universal-version claim. `LivingEntity` retains
elapsed use ticks from synchronized hand flags and equipped-stack identity,
resetting on stop, hand change, or replacement. The remote `pull` and
`brushing` paths are covered for 1.19.4 and 1.20.4 respectively.

## Automated evidence

Java 17 focused unit coverage verifies:

- last-match threshold selection;
- unknown-provider negative-infinity behavior and explicit adapter values;
- item-specific gating;
- crossbow charged/firework and charged-pulling behavior;
- light level and elytra broken state;
- active bow pull thresholds; and
- composed blocking/left-handed predicates;
- bundle occupancy using a non-64 registry stack size;
- fixed trim-material lookup;
- deterministic clock and compass/recovery-compass inputs;
- wrapped clock interpolation retained across ticks;
- remote active-hand elapsed timing and reset behavior in 1.19.4 and 1.20.4;
  and
- remote bow threshold and brush-frame selection; and
- session-selected 1.19.4/1.20.4 provider admission, including fail-closed
  rejection of later providers.

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateTest \
  --tests de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateRuntimeTest \
  -x :debug-core:test

./gradlew integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateIntegrationTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.feature.item.ItemFeaturePredicateTest \
  -x :debug-core:test
```

The renderer integration keeps one clock stack installed while world time
crosses an override threshold. It proves the selected model identity changes,
the stale model is not rebuilt, and the new model produces the replacement
mesh. It uses the dummy render system, so it is CPU/GPU-lifecycle logic
evidence, not a pixel reference.

The unmodified Blockbench export and display regression gates also remain
green:

```sh
./gradlew integrationTest \
  --tests de.bixilon.minosoft.local.datapack.AnimatedJavaExportFixtureTest \
  --tests de.bixilon.minosoft.local.datapack.LocalDisplayEntityFactoryTest \
  -x :debug-core:test
```

That gate verifies the exact Animated Java 1.10.2 export's seven
custom-model-data nodes, command lifecycle, repeated replacement, rollback, and
CPU cleanup. It remains a headless integration test, not a rendered-reference
claim.

## Continuation

1. Audit and split the pre-1.19.4 compatibility catalog at each provider
   introduction boundary.
2. Add modern component-based item-model dispatch with version-specific
   fixtures.
3. Carry the exact exported models through the real-OpenGL reference and
   repeated resource-accounting protocol in the Blockbench export evidence.
