<!-- Copyright (C) 2026 Jacob Repp -->

# Source-native settings forms — 2026-07-24

## Decision

Reusable configuration screens use a Minosoft-owned schema rather than Mojang,
Cloth Config, MidnightLib, or Mod Menu classes. `ConfigEntry<T>` describes a
typed value, control, default, reader, writer, validation, dependency predicate,
and restart boundary without depending on rendering or Fabric.

`SettingsSession` stages one form independently from persisted state:

- reset changes the staged values to schema defaults;
- cancel restores the last successfully loaded or applied baseline;
- apply validates every enabled entry before writing;
- dependency predicates read the complete staged value set;
- a failed writer or persistence callback restores every attempted live value
  in reverse order and retains rollback failures as suppressed exceptions; and
- a successful apply reports the exact changed and restart-required entries.

Persistence callbacks are adapter-owned. They should publish atomically because
the framework can restore live writer values after a failure but cannot reverse
an arbitrary partially written external file.

## Native GUI adapter

`SettingsFormMenu` adapts Boolean, text, stepped, and cycle controls into rows
with inline validation, dependency-driven disabled state, restart markers, and
Reset/Apply/Cancel actions.

`CycleSelectorElement` owns a bounded typed option list. Left click, Enter, and
Right select the next value; right click and Left select the previous value;
Home/End select endpoints; the wheel cycles in either direction. Disabled
selectors do not mutate and cannot receive focus.

`ClippedScrollPanelElement` lays out bounded variable-height rows, renders and
ticks only rows intersecting its viewport, clamps scroll after content or
viewport changes, and routes input in content coordinates. Partial primitives
cross `ClippedGuiVertexConsumer`, which clips textured quads, arbitrary item
quads, solid quads, and italic text while interpolating UVs. Clipped polygons
with more than four vertices are emitted as complete degenerate-triangle quads,
preserving the GUI mesh's four-vertex indexing invariant.

## Fabric ownership

`FabricSettings.register` adapts a `SettingsSchema` to the existing
`FabricScreens` boundary. The returned registration handle therefore owns the
factory and every open screen through the established render-queue cleanup
path. Active registrations appear as configuration buttons in the native Mod
settings menu. This is a source-native adapter; upstream config screen classes
still do not link.

## Automated acceptance

Java 17 checks passed:

```sh
./gradlew compileKotlin
./gradlew :test \
  --tests de.bixilon.minosoft.config.settings.SettingsSessionTest \
  --tests de.bixilon.minosoft.gui.rendering.gui.elements.input.cycle.CycleSelectionTest \
  --tests de.bixilon.minosoft.gui.rendering.gui.elements.scroll.ScrollViewportStateTest \
  --tests de.bixilon.minosoft.gui.rendering.gui.mesh.GuiClipRectTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricSettingsTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest
```

The focused gate covers 24 cases, including dependency validation, reset and
cancel, restart reporting, writer and persistence rollback, duplicate/bounded
schemas, cycle overflow/wrapping, scroll bounds/visibility, position and UV
clipping, invalid/non-finite clip input, owned registration cleanup, and
catalog honesty.

The complete root unit suite passed 1,775 tests with no failures or skips, and
`./gradlew assemble` completed successfully. Existing deprecation and Kotlin
2.5 migration warnings remain outside this slice.

No live visual acceptance was run for this foundational slice. The subsequent
[Fabric UI framework and adapter mapping](2026-07-24-fabric-ui-framework.md)
records the category/search/grid/dialog/map/machine extensions and the
adapter-specific Sodium, Entity Culling, ImmediatelyFast, Iris, Naturalist, JEI,
Inventory Management, and Mod settings mappings.
