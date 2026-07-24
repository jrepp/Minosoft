<!-- Copyright (C) 2026 Jacob Repp -->

# Inventory Management adaptation and UI evidence — 2026-07-22

## Decision

Pin Inventory Management `1.5.0+1.20.3` for Minecraft 1.20.4 and adapt its
first high-value workflow through Minosoft-owned source hooks. Do not link or
execute its Mojang/Fabric client entrypoints or mixins. The exact adapter
validates the embedded `inventorymanagement` version `1.5.0` metadata, then
provides native container controls and delegates authoritative mutations to the
upstream Fabric server mod.

The Modrinth version record names this release for Minecraft 1.20.3–1.20.4. Its
download is SHA-512 pinned in Packwiz and remains in the out-of-source artifact
store. The launcher copies only manifest-declared `both`/`server` artifacts into
the managed Fabric server set and records their exact filenames in
`server/.minosoft-managed-mods`; it does not sweep unrelated server mods.

## Implemented host surface

`FabricContainerScreenExtensions` is a general owner-scoped extension registry:

- factories receive the current renderer, container, and native content size;
- controls render beside player and non-player container screens and participate
  in the normal mouse/input route;
- registrations and live bindings close with their Fabric generation;
- a reusable inventory screen rebuilds its bindings after close/reopen rather
  than retaining a closed or empty extension list.

The Inventory Management adapter exposes:

| Screen | Native controls |
| --- | --- |
| Player inventory | `Sort inventory` |
| Other containers | `Sort inventory`, `Sort container`, `Put all`, `Take all`, `Stack in`, `Stack out` |

The pinned server protocol was inspected from the exact artifact. Each operation
sends one boolean byte on its upstream namespaced channel:

| Operation | Channel |
| --- | --- |
| sort | `inventorymanagement:sort_inventory_packet` |
| transfer all | `inventorymanagement:transfer_all_packet` |
| auto-stack | `inventorymanagement:auto_stack_packet` |

The boolean selects the player-inventory or container direction. The server
continues to own the mutation. Process-local diagnostics expose installed and
invoked `inventory-operation:sort`, `inventory-operation:transfer`, and
`inventory-operation:stack` hooks without attributing server execution time to
the client adapter.

## Player preview and native baseline

The player inventory atlas already reserved a `skin` area, but no element
rendered it. `PlayerSkinElement` now composes the live/default 64×64 player skin
into a deterministic full-body preview with base and transparent outer layers.
Wide and slim arm layouts are tested. It uses the existing dynamic skin texture
and listener lifecycle and does not introduce a world camera/entity renderer
inside the GUI.

This is intentionally a readable 2D character-sheet preview. Equipped armor,
held items, model rotation, and a perspective 3D entity preview remain separate
work.

The existing vanilla slot controls remain available: left/right click,
right-button quick-craft drawing across input slots, shift quick-move,
double-click collect, creative middle-click clone, `Q`/Control-`Q` drop,
`1`–`9` hotbar swap, and `F` offhand swap. The quick-craft implementation and
acceptance evidence are recorded in
[right-drag crafting distribution](2026-07-23-right-drag-crafting.md).
Inventory Management slot locking, whole-row hotbar swapping, durability
alerts, automatic replacement, variant grouping, and RoundaLib configuration
remain unmapped or partial as reported by `FabricFunctionalityCatalog`.

`InventorySynchronizedContainer` now observes its actual slot map and maps the
36 player slots in crafting/chest-style screens back to the session-owned
`PlayerInventory`. The prior `onAdd`/`onSet`/`onRemove` overrides had no caller,
so a crafting table could render a changed hotbar while the HUD retained stale
player-inventory slots after close. Add, replace, remove, transaction rollback,
and server packet updates now cross the same observable map path.

## Acceptance

Pack preparation produced immutable fingerprint
`ae4cd074fbbe60a4e48571942af93f5103e9948a85d56c7ec071f66547fe610e`.
Preflight reported `minosoft_fabric_stack` version `0.3.0`, five mods,
`activation=adapted`, and Inventory Management at four mapped, four partial, and
four unmapped functions with no activation blocker.

The Java parent restarted both sides through the normal workflow. The Fabric
server reported 51 loaded mods including `inventorymanagement 1.5.0`; its
managed directory contained the debug bridge, Fabric API, and the pinned
Inventory Management JAR. Client generation 2 reported all five pack mods
active through exact adapters.

Debug-pipe input and framebuffer capture verified:

1. the full-body player skin and readable `Sort inventory` button render in the
   live 1800×1000 inventory;
2. a debug mouse move plus left-button press traverses normal GUI input;
3. diagnostics record one `inventory-operation:sort` invocation at 64,000 ns;
4. the server remains ready with one connected player;
5. the server response reconciles scattered birch items into one stack; and
6. closing and reopening the reused inventory screen renders the skin and button
   again, with the extension factory invocation count advancing from one to two;
7. a nearby crafting table was located through server AOI at `(17, 71, 23)`;
   moving a birch sapling from hotbar slot 3 to slot 4 updated the table and
   behind-screen HUD together, and the world HUD retained slot 4 after Escape.

Focused layout, catalog, adapter/preflight, launcher, and synchronized-container
tests pass. The broad acceptance suite also passed after the crafting-session
synchronization fix:

```sh
./gradlew :test :integrationTest
```

## Remaining boundary

This slice is exact to the pinned upstream server contract and the launcher-owned
Fabric server. A general remote server still needs capability negotiation.
Revisioned multi-slot plans, stale-state rejection/rollback, normalized
owner-ordered slot policy, slot locks, and automatic replacement remain in the
Fabric hook backlog. Do not infer those features from the mapped buttons.
