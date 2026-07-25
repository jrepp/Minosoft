<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric UI framework and adapter mapping — 2026-07-24

## Decision

Fabric compatibility configuration remains source-native. Upstream Mojang,
Cloth Config, MidnightLib, Mod Menu, Iris, and JEI screen classes do not cross
the host ABI. Exact compatibility adapters instead register bounded
`SettingsSchema` factories through `FabricSettings`; each factory binds the
active renderer/session without retaining it beyond the owned screen.

Adapter-only values persist atomically below the configured profile root in
`fabric-options/`. They do not write into the repository, `.run/`, server tree,
or staged mod artifact. Native profile values continue to use their existing
profile invalidation/save path.

## Reusable UI boundary

The settings form now supports declared categories, a keyboard-operable tab
bar, focused term search, selection reconciliation, descriptions rendered as
hover poppers, dependency-disabled controls, validation, reset/apply/cancel,
restart markers, and error status. The existing typed Boolean, text, stepped,
and cycle controls remain the only schema control types.

Scrollable collections have two distinct implementations:

- `ClippedScrollPanelElement` lays out variable-height rows and touches only
  intersecting rows.
- `ClippedVirtualGridElement` instantiates, ticks, hit-tests, and renders only
  intersecting cells. Both cross the same CPU clip consumer.

The shared presentation surface also includes generic confirmation actions,
severity banners, item tooltip cells, revisioned machine snapshots,
tank/energy/progress gauges, bounded payload controls, and a map canvas with
anchored pan/zoom transforms, visible bounds, marker selection/tooltips, and
waypoint-edit callbacks.

## Current Fabric-stack mappings

- Sodium owns a categorized video form for view distance, GUI scale,
  fullscreen, VSync, smooth lighting, cloud quality, biome radius/algorithm,
  mipmaps with a restart marker, and bounded chunk-transfer policy.
- Entity Culling owns persisted occlusion enablement and an identifier
  whitelist. Disabled or whitelisted entities bypass only `OCCLUDED`; native
  view-distance and frustum rejection remain.
- ImmediatelyFast owns persisted frame/HUD/screen retained-batching policy; the
  frame flag gates its actual queue-flush hook.
- Iris discovers profile-root shader-pack directories/zip files, persists pack
  selection, extracts bounded Boolean/enumerated `#define` options, validates
  overrides, and compiles them into the candidate pipeline before swap.
- Naturalist exposes every pinned client model-removal route as a searchable
  MidnightLib-shaped category and updates owner-scoped Gecko routes at runtime.
  Server spawn weights remain server/gameplay state.
- JEI container extensions expose a clipped searchable ingredient overlay,
  ingredient tooltips/bookmarks, recipe cards with ghost ingredient/result
  slots, category/search filtering, selection, recipe bookmarks, and a
  handler-backed transfer button. Transfer stays disabled unless an active
  container handler reports support.
- Inventory Management persists visibility for sort, transfer, and stack
  control groups without changing its pinned server payload contract.

The native Mod settings catalog now decodes descriptions, icon paths, Mod Menu
badges, parents, and dependencies; presents clipped search and
all/configurable/library/blocked filters; and opens owner-matched source-native
configuration directly. Icon presence is shown, but archive pixels are not yet
decoded into a GUI texture.

## Remaining non-UI dependencies

The UI-side boundaries no longer require new primitive controls. Completion of
these behaviors still requires their owning data/gameplay systems:

- a JEI recipe-transfer handler for each concrete container protocol;
- Xaero terrain tiles, explored-world persistence, radar data, and waypoint
  storage/synchronization;
- Tech Reborn machine inventories, processing ticks, energy networks, payload
  codecs, and server authority; and
- broader OptiFine/Iris shader grammar and program coverage.

Do not relabel those systems as UI work. Likewise, source-native screens do not
claim binary linkage for upstream screen factories.

## Acceptance

The focused Java 17 gate covers settings staging/filtering, tabs, clipping,
scrolling, virtual-grid geometry, map transforms, machine snapshot revisions,
metadata UI fields, atomic adapter options, Iris define discovery/overrides,
and owned settings cleanup. A complete root test/assemble gate and live visual
acceptance must be recorded before calling this slice runtime-accepted.
