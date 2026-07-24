<!-- Copyright (C) 2026 Jacob Repp -->

# Right-drag crafting distribution evidence — 2026-07-23

## Decision

Use Minecraft's standard container quick-craft protocol for held-right-button
recipe drawing. A gesture sends mode `5` with right-drag start (`button=4`),
one add (`button=5`) for each newly crossed eligible slot, and end
(`button=6`) on release. This keeps Minosoft interoperable with vanilla and
Fabric servers instead of synthesizing a series of unrelated clicks.

## Implemented boundary

`FloatingItem` owns the input gesture because it represents the stack attached
to the pointer. `RightDragDistribution` retains insertion order and admits each
slot only once during a gesture. `DistributeContainerAction` owns protocol
encoding and local prediction.

On release, one item is placed into each distinct eligible slot until the held
stack is exhausted. Eligibility follows the container's `SlotType.canPut`
policy, rejects crafting/output and other remove-only slots, rejects
incompatible occupied stacks, and respects item stack limits. A right click on
an ineligible target retains the existing single-slot split/swap behavior.

The server remains authoritative. The end packet carries the predicted slot
changes and remaining floating stack so modern revision reconciliation sees the
same state as the local UI.

## Transaction-queue learning

Live input exposed an older bounded-queue defect:
`ContainerTransactionManager` tried to remove an entry before advancing its
iterator after 30 unacknowledged actions. Rapid inventory use—and especially a
multi-packet quick-craft gesture—could therefore throw `IllegalStateException`
and make later clicks appear inert. Eviction now advances to the oldest entry
before removal. A capacity regression commits 31 transactions and verifies
that the newest transaction remains revertible.

Client generation 25 loaded the queue correction while remaining connected to
the Fabric 1.20.4 server. No new transaction-manager exception appeared after
activation.

## Acceptance

Focused behavior checks cover:

- ordered, once-only slot traversal and gesture reset;
- exact start/add/add/end packet mode, button, slot, and action IDs;
- one-item prediction and carried-stack remainder;
- duplicate paths and a carried stack smaller than the path;
- incompatible stacks, full stacks, and remove-only output slots; and
- transaction-queue eviction beyond its 30-entry bound.

The focused JUnit gesture suite passes three tests. The TestNG action suite
passes four tests, the transaction-manager regression passes, and the full
integration suite succeeds:

```sh
./gradlew :test --tests \
  'de.bixilon.minosoft.gui.rendering.gui.gui.dragged.elements.item.RightDragDistributionTest'
./gradlew :integrationTest
```

## Debug-input note

The first synthetic mouse move after a GUI handler becomes active is
intentionally consumed by `InputHandlerManager.skipMouse`. Automated visual
scripts must prime the pointer with a second move before clicking. Framebuffer
captures use top-left image coordinates, while the renderer's internal GUI
position and macOS content scale still need to be treated as separate evidence
surfaces; do not infer a successful slot gesture from an injected event count
alone.
