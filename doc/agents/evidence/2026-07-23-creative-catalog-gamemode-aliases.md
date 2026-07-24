<!-- Copyright (C) 2026 Jacob Repp -->

# Creative catalog and gamemode aliases — 2026-07-23 HST

## Scope

This change adds a native item catalog to the player inventory while the local
player is in Creative mode and adds the exact client command aliases
`/gamemode c` and `/gamemode s`.

## Stable behavior

- The catalog is built from the active play session's item registry, excludes
  only `minecraft:air`, and sorts identifiers for deterministic paging. It does
  not maintain a second version-specific item list.
- The catalog renders beside the normal inventory as 63 entries per page.
  Mouse-wheel input accumulates to two standard wheel steps per page, including
  fractional trackpad deltas, while a direction change discards old momentum.
  Hover uses the normal item popper, left or middle click picks up the item's
  maximum stack, and right click picks up one. Placement continues through the
  existing creative container action and `ItemStackCreateC2SP` path.
- The catalog search bar filters the same registry-backed list by localized item
  name, path, or namespaced identifier. Matching is case-insensitive, supports
  multiple whitespace-separated terms, and resets paging without duplicating
  item catalog state. Printable character input starts and focuses search even
  before a click; clicking the field accepts both normalized primary-button
  representations.
- The catalog is rendered and interactive only while the player gamemode is
  Creative. The normal survival inventory layout is unchanged.
- Pressing `E` closes the player inventory on the initial key press. A focused
  creative search bar retains `E` for text entry; Escape continues through the
  shared GUI back action.
- Chat execution expands only the exact aliases `/gamemode c` and
  `/gamemode s`. The expanded vanilla commands are sent before the
  server-provided command parser can reject the shorthand; the server remains
  authoritative for permission and command validity.

## Validation

- `CreativeCatalogPagerTest` proves complete paging, empty-catalog stability,
  slower accumulated scrolling, direction-change handling, clamping at both
  ends, localized/identifier-ready term filtering, page reset, clearing, and
  no-result behavior.
- `CreativeCatalogInputTest` proves Creative-only character capture and both
  primary mouse-button aliases used by normal and injected input paths.
- `InventoryScreenTest` proves `E` closes only on the initial press and remains
  available while the search input is focused.
- `CommandAliasesTest` proves both exact expansions and non-interference with
  ordinary chat, full gamemode names, and commands with extra arguments.
- `ChatNodeTest` proves the chat entry path sends `/gamemode creative` and
  `/gamemode survival`.
- Live supervised Minecraft 1.20.4 acceptance rendered 21 catalog pages,
  changed page 1 to page 2 through normal scroll injection, and showed a
  selected catalog stack following the cursor over the player inventory.

The live client generation was replaced through the normal `play.sh dev`
hot-reload path while the play parent and Fabric server remained active.
