<!-- Copyright (C) 2026 Jacob Repp -->

# Headless render contracts

This module owns rendering contracts that must compile and test without the
Minosoft application, Minecraft state, a window, LWJGL, or OpenGL:

- immutable render graph descriptions and owner-scoped publication;
- typed render target and vertex-layout declarations;
- leased candidate/publish/retire generation and single-override ownership;
- fixed-storage terrain performance telemetry;
- normalized terrain world, page, build, coverage, and interoperability
  contracts;
- bounded urgency-aware terrain CPU scheduling, logical cancellation, a bounded
  completion mailbox, tenant/domain fairness, process-shareable typed tenant
  leases, reusable worker contexts, and fixed primitive snapshots;
- immutable owned terrain mesh artifacts with checked byte counts, semantic
  partitions, deterministic digests/comparison, and explicit release;
- transactional region upload plans with separate checked vertex/index arenas,
  atomic page publication, CPU/device-completion retirement leases, deterministic
  headless storage, cached region/material/view command templates, conventional
  draw fallback, and independent CPU/upload admission budgets;
- readiness-aware spatial coverage tracking that retains last-known-good pages
  through replacement retries, plus immutable frame scene snapshots that pin
  near coverage, distant selection, seam policy, layout/material generations,
  and main/auxiliary views to one decision boundary;
- bounded vertical-run distant columns, deterministic reduction, a checked
  quadtree page index with independent source/dirty/render revisions, atomic
  removal and stitch invalidation, screen-space-error selection with
  hysteresis/budgets/neighbour balancing, and semantic page meshing with greedy
  merging and explicit incomplete-neighbour fallback;
- one normalized voxel-to-vertical-run sampler and a canonical bounded
  schema-v2 page codec shared by store and network records;
- atomic, frame-leased near/distant/material/shader pipeline generations; and
- versioned capabilities, structured rejections, immutable complete and
  capability-scoped diagnostic responses, bounded page selectors,
  generation-bound cursors, and canonical JSON fixtures.

The application module consumes these contracts and owns concrete world,
renderer, compatibility-adapter, and GPU implementations. Keep dependencies
pointing from the application to this module; do not add an application
dependency here to make a concrete implementation compile.
