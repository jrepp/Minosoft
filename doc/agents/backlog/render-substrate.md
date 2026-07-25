<!-- Copyright (C) 2026 Jacob Repp -->

# Render-substrate target architecture

## Status and decision

This document is **Target** guidance and the normative completion function.
The current implementation checkpoint is recorded in
[R0–R7 evidence](../evidence/2026-07-24-render-substrate-r0-r7.md), with layer
summaries in the [graphics](../areas/05-graphics.md) and
[modding](../areas/08-modding.md) maps.

Replace the current world-render pipeline with one canonical render substrate.
Do not preserve the existing pipeline as a second/default implementation.
Unmodded Minosoft remains supported by built-in terrain and shader providers
that use the same contracts as adapted render mods. Sodium and Iris replace
their respective providers; they do not wrap a separately executing native
world pipeline.

The compatibility target is the exact pinned Sodium and Iris artifact set, not
arbitrary Fabric/Mojang binary compatibility. Prefer isolated use or adaptation
of their core algorithms where the host contract is sufficient. Keep Mojang
type translation at the compatibility boundary instead of leaking it into the
render substrate.

## Objective and completion function

The objective is to make Minosoft's native render substrate closely satisfy the
host semantics needed by the pinned Sodium and Iris contracts, while letting:

- built-in providers render the base profile;
- Sodium own terrain preparation and submission when active; and
- Iris own shader-pack programs, views, targets, and composites when active.

The objective is complete only when every predicate below is true:

```text
RENDER_SUBSTRATE_COMPLETE =
    SINGLE_PIPELINE
 && BASE_PROFILE_ACCEPTED
 && SODIUM_OWNS_TERRAIN
 && IRIS_OWNS_SHADER_PIPELINE
 && IRIS_SODIUM_COMPOSE
 && TRANSACTIONAL_RESOURCES
 && LIFECYCLE_CLEAN
 && HEADLESS_SAFE
 && MULTI_VERSION_SAFE
 && PERFORMANCE_ACCEPTED
 && LEGACY_REMOVED
 && DOCUMENTATION_TRUTHFUL
```

| Predicate | Required evidence |
| --- | --- |
| `SINGLE_PIPELINE` | Base, Sodium, Iris, and combined profiles build and execute the same graph and resource contracts; provider selection changes ownership, not the frame architecture. |
| `BASE_PROFILE_ACCEPTED` | An unmodded session renders the agreed sky, terrain material classes, entities, block entities, particles/weather, world composite, and HUD gates through built-in providers. |
| `SODIUM_OWNS_TERRAIN` | With Sodium active, the exact pinned compatibility module owns terrain build scheduling, mesh organization/upload, visibility/batching, and terrain draw submission. No built-in terrain draw runs for the same view. |
| `IRIS_OWNS_SHADER_PIPELINE` | With Iris active, a real pinned shader pack—not a Minosoft presentation effect—selects programs, declares its required world/shadow views and targets, binds its uniforms/samplers, and executes composites. |
| `IRIS_SODIUM_COMPOSE` | The combined profile renders Sodium terrain through Iris-selected programs and targets without duplicate terrain submission, missing phase data, or a fallback post-process substitution. |
| `TRANSACTIONAL_RESOURCES` | Graph, program, texture, target, and vertex-layout candidates prepare off the active generation; failure preserves the last-known-good generation; successful swap retires replaced GPU objects on the render thread. |
| `LIFECYCLE_CLEAN` | Twenty valid/invalid reload cycles and repeated activation/deactivation return registrations, callbacks, graph generations, buffers, textures, programs, and targets to the recorded steady-state counts. |
| `HEADLESS_SAFE` | Graph descriptions, provider selection, shader-pack planning, terrain snapshots, and validation work with the dummy rendering system; OpenGL allocation and execution remain optional boundaries. |
| `MULTI_VERSION_SAFE` | The substrate consumes version-normalized world/model inputs, and focused fixtures pass for every advertised protocol/content version touched by the change. No renderer contract embeds a 1.20.4-only registry layout. |
| `PERFORMANCE_ACCEPTED` | On the recorded R0 scene and settings, the base profile's median CPU frame time is at most 10% above baseline and p95 at most 15% above baseline. The Sodium profile is no slower than the accepted base profile for p95 terrain preparation and submission. Counts remain bounded after warmup and reload. |
| `LEGACY_REMOVED` | The old flat world pipeline and single-world-framebuffer compositor have no production call sites or compatibility shims. Their tests and documentation are migrated or removed. |
| `DOCUMENTATION_TRUTHFUL` | Evidence maps and dated evidence distinguish activation from behavioral ownership and name the exact commands, assets, captures, counters, and artifact hashes used for acceptance. |

A partial profile may remain useful, but no subset of these predicates may be
described as completion.

## Current gap

The flat world pipeline and presentation-only Iris substitute have been removed.
Production now uses one immutable graph, typed main/shadow targets,
transactional terrain/shader provider registries, stable scene semantics, and
last-known-good OpenGL program publication.

The remaining gap is behavioral rather than structural. The Sodium-labelled
provider is exclusive, but delegates the existing Minosoft chunk core instead of
owning the pinned Sodium algorithms. The Iris provider executes a bounded
project reference pack through terrain/shadow/composite programs, but has not
accepted an independent pinned real-world Iris/OptiFine pack or its broader
uniform, sampler, program-family, and target contract. Full GPU-object
steady-state baselines, complete base-scene visual coverage, and the recorded
performance thresholds also remain open. Typed per-context OpenGL accounting
now supplies the required counts but has not yet accepted the reload/unload
baseline protocol. See the dated checkpoint for exact evidence.

## Canonical target shape

Names below are descriptive, not mandated:

```text
PlaySession / normalized world snapshots
                    |
             FrameCoordinator
                    |
       immutable RenderGraphGeneration
        /           |                 \
 views/phases   typed resources    shared frame data
        |           |                 |
   draw queues  target allocator   camera/matrices/time
        |
  +-----+-------------------------------+
  |                                     |
TerrainBackend                     Scene producers
built-in | Sodium                  sky/entities/particles
  |                                     |
  +---------- ShaderPipeline -----------+
             built-in | Iris
                    |
             world composites
                    |
              HUD / screens
```

The graph is an immutable generation. It declares views, phases, dependencies,
resource reads/writes, and state requirements. The frame coordinator executes
one accepted generation; it does not discover mutable renderer lists while a
frame is in progress.

### Render graph

The graph must represent at least:

- main and optional shadow/auxiliary views;
- sky and atmosphere;
- opaque, cutout, and translucent terrain;
- entities, block entities, particles, weather, and world overlays;
- deferred or forward program families as selected by the shader provider;
- composite/final world presentation; and
- a defined boundary before HUD, screens, and popovers.

Pass IDs are stable and owner-scoped. Ordering uses explicit dependencies and
phase constraints. Registration returns a cleanup handle and rebuilds a
candidate graph; removing a provider cannot mutate the graph currently being
executed.

Each pass declares the graphics state it requires. Execution establishes that
state or restores the caller contract explicitly; order-dependent ambient
OpenGL state is not an API.

### Views and frame data

One immutable frame snapshot supplies camera transforms, previous/current
interpolation, fog/weather/time, light access, and normalized world identity.
Additional views derive from that snapshot and declare their own camera,
viewport, culling, and target set. A shadow view is therefore a first-class
view, not a hidden second traversal initiated inside an arbitrary renderer.

Frame data is host-owned and version-normalized. Providers may retain only
explicit generation or frame leases; they must not retain mutable session
objects past the declared boundary.

### Typed render resources

Targets are declared by semantic use and typed format: dimensions, samples,
color/depth format, layers, clear policy, and read/write role. Allocation is
generation-scoped. The allocator validates driver limits and attachment
compatibility before publication and aliases transient targets only when their
lifetimes do not overlap.

Programs, textures, samplers, vertex layouts, buffers, and targets follow the
same candidate/publish/retire protocol. Retirement occurs on the render thread
after the last frame lease. Context loss or recreation invalidates the complete
GPU generation; no provider-owned OpenGL object silently crosses that boundary.

### Terrain contract

The world supplies immutable, bounded section snapshots and invalidation
reasons. A selected `TerrainBackend` owns:

- build-task scheduling and cancellation;
- material classification and mesh generation;
- buffer organization, upload, and retirement;
- visibility, batching, and draw-command production; and
- terrain submission for every graph view that requests it.

The built-in backend preserves unmodded behavior. The Sodium backend replaces
it completely while active. Selection is one owner per frame generation; two
backends never submit the same terrain view.

Terrain vertices use a negotiated semantic ABI rather than one hard-coded
packed layout. The declaration can include position, texture coordinates,
color, packed light, normal/tangent data, material/block identity, and
shader-pack-specific extension data. Physical encodings remain backend-owned,
but the shader provider receives an exact layout declaration and either accepts
it or rejects the candidate before publication.

Material classes are stable host semantics, not shader names. At minimum they
distinguish opaque, cutout, translucent, emissive/additive where supported, and
shadow participation. Version-specific block/model rules normalize into those
semantics before they reach a terrain backend.

### Shader-pipeline contract

A selected `ShaderPipeline` owns the program and target plan for graph phases.
The built-in provider supplies Minosoft programs for the base profile. The Iris
provider replaces that plan with the exact pinned shader-pack implementation,
including pack parsing, program variants, uniforms, samplers, shadow views,
intermediate targets, and composites.

Scene and terrain producers submit semantic draw data; they do not choose a
hard-coded native shader behind the provider's back. Iris does not become a
single post-processor. Unsupported pack requirements reject the candidate with
structured capability diagnostics and preserve the active generation.

### Ownership and capability negotiation

Provider activation is a candidate transaction:

```text
discover -> describe requirements -> negotiate capabilities
         -> prepare graph/resources -> validate -> publish
                                      failure -> keep active generation
```

Capabilities are exact and inspectable: view kinds, target formats/counts,
vertex semantics, material classes, program stages, sampler limits, and
required host data. Preflight must distinguish unsupported, adapted, and
behaviorally accepted states. Registration scopes own callbacks, graph
contributions, worker tasks, and GPU retirement handles.

## Delivery trajectory

| Rung | Work | Exit evidence |
| --- | --- | --- |
| R0 — Baseline | Record a deterministic base scene, frame phase trace, CPU/GPU timing, draw/upload counts, resource counts, context lifecycle, and current Sodium/Iris traces. | Re-runnable commands and captures identify the exact version, assets, settings, and artifacts used by later comparisons. |
| R1 — Graph kernel | Add immutable graph/view/pass descriptions, stable IDs, dependency validation, recording execution, and owner-scoped candidate publication. Route one bounded world slice through it. | Headless graph tests cover ordering, cycles, removal, failure preservation, and deterministic traces. |
| R2 — Resource generations | Add typed target/program/texture/layout declarations and render-thread candidate/publish/retire ownership. Make shader reload last-known-good. | Invalid candidates preserve IDs and visuals; valid/repeated swaps return resource counts to steady state on dummy and real OpenGL paths. |
| R3 — Terrain boundary | Introduce section snapshots, semantic material/vertex declarations, one selected terrain backend, and graph-view terrain submissions. Migrate the built-in renderer. | Base profile passes visual and multi-version gates through the new backend; duplicate terrain submission is structurally impossible. |
| R4 — Sodium ownership | Build the exact pinned translation boundary and move scheduling, meshing, upload, visibility/batching, and terrain draws to Sodium's core path. | Diagnostics name Sodium as the sole terrain owner; captures, counters, cancellation, unload, and performance gates pass. |
| R5 — Iris ownership | Let the exact pinned Iris path compile a real pack into graph views, programs, targets, uniforms, samplers, and composites. | A real shader pack executes shadow and composite paths; invalid reload preserves the active pack and base profile remains selectable. |
| R6 — Composition | Negotiate Sodium vertex/material output with Iris layouts/programs and exercise auxiliary views without duplicate world preparation. | Base, Sodium, Iris, and combined matrix runs pass; combined captures and traces prove both ownership predicates simultaneously. |
| R7 — Cutover | Migrate remaining scene producers and mod render hooks, remove the old world pipeline/framebuffer path, and update maps and diagnostics. | The complete objective function is true, repository search finds no production legacy call sites, and broad build/integration/hot-reload gates pass. |

R1 and R2 may be developed together, but their acceptance remains separate.
R4 and R5 may proceed in parallel only after R3 fixes the shared terrain ABI and
resource-generation contract.

## Small-model support sub-trajectory

**Reported status:** SM0–SM6 complete. Their accepted output is support evidence,
not completion of any unmet main predicate. Duplicate synthetic graph/matrix
implementations have been removed after their useful assertions were absorbed
into the canonical graph, provider tests, runtime diagnostics, and dated
evidence.

This is a work queue for a smaller/low-reasoning coding model supporting the
main effort. It is not a second architecture and does not authorize the worker
to redesign provider or compatibility boundaries. Every task must start from a
reviewed interface/fixture specification and end in one independently
reviewable conventional commit.

| Step | Bounded task | Completion gate |
| --- | --- | --- |
| SM0 — Inventory | Produce repository-search-backed tables of pipeline call sites, framebuffer/resource owners, shader reload call sites, and current Sodium/Iris adapter hooks. | Every row names a source path and symbol; no target claim is presented as observed behavior. |
| SM1 — Trace fixture | Add a CPU-only recording executor for an already-reviewed graph interface and assert a supplied phase/dependency trace. | Focused tests cover deterministic order, rejected cycles, and owner removal; no OpenGL dependency enters the test. |
| SM2 — Canary scene | Add a deterministic, version-neutral canary scene specified by the lead: opaque, cutout, translucent, and entity draws using project-owned procedural assets. | The base profile emits the reviewed trace headlessly and a stable real-GL capture; the fixture bypasses neither the production graph nor provider selection. |
| SM3 — Failure matrix | Add table-driven tests for supplied invalid target formats, missing semantics, program failure, candidate cancellation, and scope closure. | Each failure keeps the old generation selected and each successful retry returns counts to the stated baseline. |
| SM4 — Mechanical migration | Move one named producer at a time from the legacy list into a pre-existing graph phase without changing its rendering policy. | Focused visual/trace tests pass, legacy references decrease, and unrelated producers are untouched. |
| SM5 — Matrix harness | Extend existing launcher/test utilities with reviewed base/Sodium/Iris/combined profiles and bounded counters. | One command produces machine-readable ownership, phase, timing, and resource results for all four profiles; raw runtime logs remain uncommitted. |
| SM6 — Evidence upkeep | Update the applicable map and dated evidence from accepted command output. | Claims use Observed/Verified/Target correctly and link the exact fixtures and commands. |

The worker must stop and escalate when a task requires changing the graph model,
terrain ABI, provider ownership, Mojang/Fabric translation policy, performance
thresholds, or the completion predicates. It must also stop on an unexpected
dirty-tree overlap instead of rewriting another contributor's work.

The support trajectory is complete when SM0–SM6 have accepted commits, their
focused checks pass, and the resulting evidence is consumed by at least one
main R1–R7 exit gate. Completing SM0–SM6 alone does not make
`RENDER_SUBSTRATE_COMPLETE` true.

## Required validation

Run the smallest focused test first, then broaden in proportion to the rung:

- CPU graph, negotiation, lifetime, and dummy-renderer tests;
- real-OpenGL target/program/layout creation and failure preservation;
- deterministic framebuffer captures for base, Sodium, Iris, and combined
  profiles;
- repeated activation, failed reload, valid reload, and teardown accounting;
- multi-version world/model fixtures and headless graph preparation;
- the hot-reload acceptance protocol where play-parent behavior changes; and
- `./gradlew compileKotlin`, focused tests, `integrationTest`, and `assemble`
  before the R7 cutover is accepted.

Record commands and durable conclusions in dated evidence. Do not commit raw
`.run/` lifecycle logs.

## Non-goals

- Preserving the implementation, draw-list shape, or incidental ordering of the
  current default pipeline.
- Claiming arbitrary Fabric, Mojang, Sodium, Iris, or shader-pack compatibility.
- Running two terrain pipelines and compositing their results.
- Treating adapter activation, a scheduling hint, or a presentation shader as
  behavioral compatibility.
- Making OpenGL/window construction mandatory for headless parsing, planning,
  tests, or server behavior.
