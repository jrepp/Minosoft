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
| `pull`, `pulling` | Active stack identity and local use ticks; crossbow quick-charge enchantment and charged state | First-person local player; active state only for remote living entities |
| `brushing` | Active stack and local use ticks | First-person local player |
| `cast` | Session fishing-bobber ownership | First-person and GUI resolution |
| `blocking`, `throwing`, `tooting` | Active stack identity | First-person living-entity resolution |

`ItemPredicateContext.values` can supply an exact namespaced value from a
headless fixture, future world/registry implementation, or source-native
compatibility adapter. Values must be finite.

The unsupported 1.20.4 built-ins are `filled` (bundle occupancy), `time`
(clock), `angle` (compass and recovery compass), and `trim_type` (dynamic trim
registry material index). Remote living entities expose the active hand but
Minosoft does not yet retain their item-use elapsed ticks, so remote
`pull`/`brushing` animation progress is not claimed.

## Automated evidence

Java 17 focused unit coverage verifies:

- last-match threshold selection;
- unknown-provider negative-infinity behavior and explicit adapter values;
- item-specific gating;
- crossbow charged/firework and charged-pulling behavior;
- light level and elytra broken state;
- active bow pull thresholds; and
- composed blocking/left-handed predicates.

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.models.item.ItemPredicateTest \
  -x :debug-core:test
```

The unmodified Blockbench export gate also remains green:

```sh
./gradlew integrationTest \
  --tests de.bixilon.minosoft.local.datapack.AnimatedJavaExportFixtureTest \
  -x :debug-core:test
```

That gate verifies the exact Animated Java 1.10.2 export's seven
custom-model-data nodes, command lifecycle, repeated replacement, rollback, and
CPU cleanup. It remains a headless integration test, not a rendered-reference
claim.

## Continuation

1. Add version-keyed predicate catalogs instead of treating the 1.20.4 catalog
   as universal.
2. Implement bundle occupancy and trim-material registry lookup.
3. Add deterministic world/entity inputs for clock and compass properties.
4. Retain remote item-use elapsed time and rebuild held-item meshes when a
   threshold crossing changes the selected override.
5. Carry the exact exported models through the real-OpenGL reference and
   repeated resource-accounting protocol in the Blockbench export evidence.
