<!-- Copyright (C) 2026 Jacob Repp -->

# Terrain consolidation implementation start

## Status

The Phase 0/Phase 1 contract boundary and the production Phase 2 through Phase
8 implementation slices from the
[terrain pipeline consolidation plan](../../design/terrain-pipeline-consolidation-plan.md)
are accepted as the current consolidation checkpoint. Near and distant work now
share the process CPU service, semantic artifact and transactional region
storage boundaries, and bounded upload/publication rules. The consolidated
near-region and distant-hierarchy paths are the production default behind one
process-pinned rollback selection. The complete multi-version, seam-motion,
server/reconnect, visual, performance, diagnostic-generation, and legacy-
retirement exit gates below remain open; this record does not declare the
overall plan complete.

## Headless contract boundary

The dependency-clean `render-contracts` module now owns:

- immutable world, page, and complete build identities;
- bounded urgency/tenant-aware CPU scheduling, logical cancellation, a bounded
  completion mailbox, and fixed primitive runtime snapshots;
- checked absolute/page-relative coordinates, origin-rebased frame transforms,
  and the initial forward OpenGL depth declaration;
- the readiness-aware coverage lifecycle and deterministic snapshots;
- provider-neutral interoperability declarations and near/distant provider
  markers;
- immutable material-table generation metadata and explicit geometry-reuse
  decisions;
- named `LEGACY`, `UNIFIED_COMPARE`, and `UNIFIED` modes;
- one atomic, leased near+distant+material+shader pipeline generation;
- process, rendering-device, and world-epoch runtime scope contracts;
- checked world/device residency accounting;
- device-neutral submission serial and completion state;
- structured failure categories and event-gated retry/quarantine rules; and
- immutable distant authority/provenance records with deterministic atomic
  merge outcomes.

These contracts use Kotlin/JDK types only. They do not depend on the
application world model, GUI, Fabric, a window, LWJGL, or OpenGL.

The pipeline registry constructs and validates the complete candidate before
publication, leases one complete generation, and retires all candidate-owned
resources in reverse order only after the last lease closes. Candidate
construction failure preserves the active generation. This registry is not yet
the production `TerrainBackendRegistry`; adopting it is a later Phase 1/Phase 2
boundary.

## Diagnostic schema freeze

The headless contract layer now defines version-one terrain diagnostic
capabilities, structured rejection codes, and one immutable
`TerrainDiagnosticSnapshot`. A snapshot aligns world, provider, pipeline,
material, coverage, page-registry, residency, and submission generations.

Page queries require the world epoch, an area or canonical-prefix selector, and
an explicit maximum of 1–1,024 results. Continuation cursors are opaque,
URL-safe, and bound to the query plus diagnostic generation. A changed
generation, selector, world, or malformed cursor rejects explicitly instead of
continuing against mixed state.

JDK-only canonical JSON encoders and three checked version-one fixtures freeze
capability, rejection, provider, identity, page, coverage, and snapshot field
semantics. A capability-scoped response envelope permits partial production
adoption without inventing unavailable state.

The client now registers `render.terrain-diagnostics`. It captures the active
provider descriptor and generation under one render-thread backend lease and
encodes the result through the frozen schema. Its truthful initial capability
set is `ATOMIC_SNAPSHOT`, `PROVIDER_DESCRIPTOR`, and
`STRUCTURED_REJECTION`. Requests for world identity, complete
pipeline/material generation, page/coverage state, residency, or submission
return `MISSING_CAPABILITY` until those consolidated production owners exist.
Unknown selectors return `INVALID_SELECTOR`.

## Package-direction ratchet

`TerrainArchitectureBoundaryTest` scans current main source and rejects:

- imports from `modding.loader.fabric` in `terrain` or
  `gui.rendering.terrain`; and
- any new Fabric implementation of renderer, terrain-backend, or
  terrain-provider interfaces.

The Sodium lifecycle implementation has moved into the neutral
`TerrainProviderRendererBridge`, and the Iris presentation lifecycle has moved
into the neutral `ShaderPipelineRendererBridge`; Fabric retains only the
exact-artifact builders, controllers, settings/events, and diagnostic
callbacks. The renderer/provider ratchet now has no allowlist entries.

Normalized detached distant columns, tiles, bounded tile storage, source
provenance, snapshots, renderer-facing diagnostic DTOs, and neutral
render-source/configuration seams live under `terrain.distant`. The renderer,
shader, planner, mesh builder, and coverage helpers now live under
`gui.rendering.terrain.distant`. Fabric imports those types inward for
exact-artifact activation, source ingestion/controller ownership, v1 protocol,
persistence, settings, debug translation, and renderer registration. The
architecture ratchet prevents those normalized declarations from returning to
Fabric and prevents the headless scheduler/mailbox types from returning to the
application rendering package.

This was a package-ownership move only. The owner ID, v1 persistence bytes,
network messages, tile planner, render phases, material/surface/source wire
values, and pixel behavior are unchanged. Shared scheduling, visibility,
residency/GPU ownership, and complete-provider publication remain later
consolidation work.

## Neutral near-provider ownership

The Sodium adapter still recognizes the exact artifact, supplies the existing
owner/implementation descriptor, exposes settings/debug translation, and
installs its renderer event hook. Provider selection and the concrete
chunk-renderer delegation now live under
`gui.rendering.terrain.near.provider` as `TerrainProviderSelection` and
`ChunkRendererNearTerrainBackend`.

The preserved descriptor values are:

- owner: `minosoft:sodium-compatible-terrain`;
- implementation: `sodium-0.5.8-adapter-minosoft-core`.

Focused tests protect those values, delegated frame operations, idempotent
close, use-after-close rejection, and adapter activation. This is an ownership
move only; it does not claim upstream Sodium algorithms or change rendering
output.

## Phase 2 identity and scheduler start

The JDK-only `TerrainBuildRuntime` now lives in `render-contracts` rather than
the application rendering package. It owns a bounded queue and completion
mailbox whose outstanding cap includes queued, running, and
completed-but-undrained work. Jobs carry the frozen `TerrainBuildIdentity`, a
stable tenant, and an explicit urgency. Equal-urgency work rotates between
tenants, near work wins the ordinary tie, and a configured near burst reserves
bounded progress for queued distant work. Worker contexts remain reusable and
close with the runtime; cancellation remains logical and never interrupts a
worker that may already have advanced to another job.

The immutable runtime snapshot exposes queue, completion, outstanding, and
active depths; their high-water marks; and requested, admitted, rejected,
started, completed, cancelled, and failed counters. Completion draining now
requires an explicit positive bound. The production near queue drains a bounded
amount per frame and drains all retained completions during owned shutdown.

`World.terrainEpoch` advances at the existing reset/clear boundary, including
an empty world. Near section requests translate to checked near-domain page
keys carrying that epoch and now capture the request/model, provisional
provider/layout, provisional shader-material, coverage, and priority identity.
Until the complete production pipeline generation is adopted, the current
backend generation supplies both provider and layout fields, and the current
shader registry generation supplies the material field; the diagnostic endpoint
therefore does not claim those missing complete-generation capabilities.
Publication constructs the current identity and calls the centralized mismatch
function; an old-world, stale request/model, provider/layout, or shader-material
result cannot publish. The former application-local duplicate build identity
was removed.

The process-scoped `TerrainProcessBuildService` now gives near and distant
renderers typed tenant leases over one bounded scheduler and completion
mailbox. Per-worker provider contexts remain reusable; completion routing does
not invoke another world's publication callback; tenant shutdown cancels and
drains only owned work, disposes retained results, closes reusable contexts,
and permits reload to register the same tenant again. The distant renderer no
longer owns an executor or `CompletableFuture`, and its completions use the same
centralized identity mismatch gate as near terrain.

This is still not the Phase 2 exit gate. Separate CPU/output estimates and
render-thread upload admission, failure-state adoption, complete atomic runtime
diagnostics, and deterministic cross-provider feature-switch barriers remain
open.

## Phase 3 detached near-meshing start

Production solid and fluid geometry now read block state, neighbour opacity,
effective light, biome/tint inputs, and block-entity positions from a detached
`TerrainBuildSnapshot`. Ambient occlusion, smooth light, fluid height/culling,
and tint caches have snapshot-backed paths. Concrete block-entity renderers are
materialized only on the render thread after the complete build identity is
accepted. Focused mutation-after-capture tests prove that later block, light,
and fluid changes are not observed by the candidate, and the architecture
ratchet pins production solid/fluid dispatch to the snapshot overloads.
Entity-dependent block models receive a candidate-local entity reconstructed
from recursively detached NBT rather than the live section entity. Sign front/
back text now participates in that snapshot boundary. No live block entity is
retained by the snapshot or semantic artifact.

The dependency-clean `TerrainMeshArtifact` envelope owns immutable checked
vertex/index streams, semantic partitions, bounds, connectivity, coverage and
entity metadata, exact byte counts, deterministic versioned SHA-256 digests,
semantic difference classification, and idempotent release. `UNIFIED_COMPARE`
attaches this envelope to production near candidates and releases its duplicate
CPU bytes after legacy GPU upload or discard. The default `UNIFIED` route
consumes the same envelope through region storage directly. A
deterministic detached-versus-legacy solid matrix matches through the semantic
digest boundary for 1.7.10, 1.12.2, and 1.19.3 across opaque, tinted, explicit
light, detached-NBT meshed entity, renderer-entity, and neighbour-culling
fixtures. The same supported-version matrix now runs real registry water/lava
blocks through the detached production `FluidSectionMesher`, the production
water/lava model and tint branches, and deterministic headless physical texture
roles. Still water, flowing water, and still lava each retain an exact frozen
artifact digest, byte count, bounds, translucent semantic partition, and quad
topology. All three versions normalize to the same accepted signatures; the
fixture also proves the water-flow and water/lava roles remain distinct. The
semantic builders are explicitly dropped after the copied artifact is frozen,
so the comparison no longer relies on finalizer cleanup.

This is not the Phase 3 exit gate. The supported-version
solid/fluid/model/tint/light/entity semantic matrix has no outstanding delta;
broader named headless plus real-OpenGL acceptance remains open.

## Phase 4 transactional region-storage start

The dependency-clean `terrain.runtime.storage` package now owns checked region
extents, separate deterministic vertex/index allocators, exact immutable upload
plans, atomic page-table replacement, material partitions, layout-generation
validation, CPU range leases, and device-submission retirement. Replacement
uploads always target newly allocated ranges; an allocation or upload failure
releases only the candidate and leaves the active page intact. Retired ranges
remain unavailable until every CPU lease closes and every associated device
serial reports complete. Device invalidation returns logical allocation
accounting to baseline. Cached region/material/view templates issue a fresh
lease per frame, and the contract includes a deterministic conventional draw
loop plus independent CPU-time/upload-byte budget selection.

The default built-in OpenGL adapter uses 8-by-32-by-8 section regions under a
fixed 16-region, 320 MiB device-allocation ceiling. Each region reserves 16 MiB
for vertices and 4 MiB for indices; the original 8+2 MiB split could saturate
one ordinary surface region while most of the process-wide ceiling remained
unused, permanently deferring the first completed upload. It uploads artifacts into
one vertex/index buffer pair per region, submits one multi-draw per non-empty
region/material/view batch when selected, retains an explicit per-command
base-vertex fallback, reuses one bounded 16 MiB ordinary-update staging buffer,
and fences each submitted terrain frame. Completed GL
fences permit range reuse; wait/fence failure remains pinned instead of being
treated as CPU-frame completion. Region-backed candidates discard their
superseded per-section VBOs while retaining block-entity lifecycle. Upload work
occurs outside the loading-queue lock and is admitted under both the existing
render-thread time budget and a fixed 16 MiB per-frame byte budget. Capacity or
region-count rejection falls back to the conventional per-section VBO for that
candidate while preserving any last published region page. This mixed
submission is intentional: a bounded region ceiling must degrade batching, not
leave visible terrain permanently queued.

`render.substrate.terrain.regionStorage` reports active/retired pages and
bytes, separate arena allocation and high-water values, staging capacity,
publication/allocation/upload outcomes, batch-cache behavior, draw
batches/commands/vertices, and pending submission fences.

Artifact digest schema 2 carries explicit triangle/quad topology through the
immutable stream, published page, cached draw command, and OpenGL submission.
The region adapter groups a material/view batch by topology and reports the
actual resulting device-batch count; the native-quad dummy renderer fixture
proves detached and legacy capture retain identical quad streams. The obsolete
application-local allocator, staging, batching, and reference pipeline were
removed. Their L3 coverage now executes the real shared scheduler, artifact,
transactional storage, coverage, visibility, batch, submission, and retirement
contracts, and an architecture ratchet prevents another application-local copy.

This is not the complete Phase 4 exit gate. The region route is now the unified
default and has one named real-OpenGL reload/resource baseline. Adoption into
the complete atomic terrain diagnostic schema, broader recovery/context-loss
coverage, and representative named draw/upload scaling workloads remain open.

## Phase 5 readiness snapshot start

`TerrainCoverageTracker` now applies the complete dependency-clean
`ABSENT`/`REQUESTED`/`BUILDING`/`UPLOAD_PENDING`/`READY`/`RETIRING` lifecycle.
Its spatial revision changes only when contributed surface coverage changes,
while a separate lifecycle revision keeps state diagnostics current. Once a
page is ready, replacement requests, builds, upload deferral, and retries retain
last-known-good coverage until explicit retirement/removal. Immutable snapshots
carry deterministic cells, transition ages, contributed pages, world epoch,
provider generation, and both revisions.

`TerrainCoverageMasking` derives deterministic near/distant draw membership and
weights from the pinned cell age. A bounded transition overlaps initial near
readiness and reverses during retirement; the disabled rollback policy keeps
conservative full overlap instead of prematurely excluding distant geometry.

`TerrainSceneSnapshot` pins one camera/render origin, near coverage object,
distant-page selection, seam policy, layout/material generations,
and declared main/auxiliary views for a frame. View snapshots share the same
coverage and distant selection instances, preventing main and shadow from
recapturing different generations. The existing production near surface index
now delegates to the canonical lifecycle: requests, worker starts, completed CPU
artifacts, and full heightmap-required surface uploads publish
REQUESTED/BUILDING/UPLOAD_PENDING/READY without removing last-known-good
replacement coverage. Its spatial revision changes only when the ready chunk
set or owning world/provider generation changes.

The production hierarchy pins that immutable lifecycle snapshot once at frame
preparation. It keeps selected seam pages resident, then derives both main and
shadow draw lists from one `TerrainCoveragePageMask`. A distant page is hidden
only when every base near chunk it spans retains surface coverage. Missing
cells, a wrong epoch, or unsupported coordinate arithmetic fail open to the
distant page. An eight-frame stable page-coordinate threshold spatially
distributes the handoff; replacement `REQUESTED`/`BUILDING`/`UPLOAD_PENDING`
states retain the already-settled mask through last-known-good coverage. An
explicit removal reveals still-resident distant geometry on the next pinned
frame instead of waiting for a rebuild or upload. The legacy whole-domain route
retains conservative overlap as the rollback.

`render.substrate.terrain.nearCoverage` reports both revisions, state counts,
bounded cells, transition/coverage ages, and contributed ownership. The DH
hierarchy diagnostic additionally reports selected versus masked pages, both
coverage revisions, and the transition duration. Dependency-clean fixtures
cover complete and partial multi-cell pages, negative coordinates, wrong-world
fail-open behavior, deterministic transition, and replacement retention.

This is not the Phase 5 exit gate. Deterministic motion, partial-readiness,
removal, and checked main/shadow seam acceptance remain required; page-granular
masking may need a finer subpage representation if those pixel gates expose a
coarse-page transition artifact.

## Phase 6 hierarchical distant-data start

The dependency-clean distant hierarchy now owns bounded top-down vertical runs
with semantic material, fluid, block/sky light, tint, confidence, and cave/
void/emissive flags. Columns have deterministic digests and a reducer that
retains extrema plus the first opaque/fluid surfaces while ranking material,
emissive, light, and cave transitions. A deterministic four-child page reducer
preserves vertical intervals and minimum child completeness.

`DistantPageHierarchyIndex` applies checked horizontal quadtree math, including
negative-coordinate floor division, and keeps independent source, dirty, and
render revisions. Source publication/removal, exact render-dependency
invalidation, bounded capacity rejection, stale render publication, parent
completeness, build lifecycle, and last-known-good artifact ownership are
atomic. A base-page mutation dirties only its ancestor chain and already-present
cardinal stitch neighbours. `DistantPageSelector` uses projected error, camera
height/FOV/zoom, quality and seam/coverage pressure, separate refine/coarsen
thresholds, bounded node/change work, uploaded-page availability, and a
one-level adjacent-detail constraint. Selection changes do not mutate source or
render revisions.

The selector indexes its previous selection by ancestor and detects adjacent
detail violations through aligned hierarchy neighbours rather than repeated
all-pairs scans. The production runtime reselects only when its block-quantized
camera, viewport/FOV, seam distance, or CPU-page publication revision changes,
and captures one hierarchy snapshot per reselection. Upload promotion and fence
retirement still advance every frame. This removes the former whole-domain
selection copies and scans from an unchanged frame without turning camera
movement into geometry rebuilds.

The semantic page mesher emits page-relative top, required bottom, and four
true run-derived side directions. It subtracts occluding vertical intervals,
preserves fluid beds, material, actual block/sky light, resolved tint, flags,
and confidence, greedily merges compatible rectangles, splits mixed-detail
edges deterministically, and emits a bounded 32-block skirt only for a missing
or incomplete neighbour. Cliff, cave, fluid/bed, light/tint, flat greedy,
fallback, and mixed-detail fixtures pin stable artifact digests and counts.

The existing top-only tile format has an explicit deterministic partial-quality
projection. It never infers fluids from identifier suffixes: production resolves
exact registry `FluidHolder` semantics and opacity before worker submission.
The default unified production route sends base and
derived parent pages through the shared process CPU service, maintains one
pending identity per page, rejects stale child/neighbour completions, uploads
and replaces one solid/water page transactionally, removes evicted hierarchy/
GPU ownership, and supplies independent stateful main/shadow page selectors.
Vertices remain page-relative; `uPageOffset` updates the draw transform after
render-origin movement without rebuilding CPU geometry. Bounded debug output
reports index/source/dirty revisions, state and detail counts, CPU/GPU/pending/
queued/selected totals, and up to 128 page records.

This is not the complete Phase 6 exit gate. The hierarchy is now selected by
default and consumes native vertical pages when available, with the partial
top-only tier retained for v1 migration input. Production still needs the
complete cliff/cave/coast/custom-fluid/tint/light fixture matrix, real-GL
mixed-detail and seam-motion qualification, and representative scaling
evidence beyond the flat workload recorded below.

## Phase 7 vertical sampling and incremental-store start

`DistantVerticalSampler` is now the single dependency-clean voxel-to-run
normalization boundary. It trims unbounded exterior void, retains enclosed cave
intervals, coalesces equivalent semantics, and applies the same deterministic
64-run bound to every source. The Minecraft adapter records exact block/fluid
state (including waterlogged state), block and sky light, biome tint input,
opacity, luminance, generation confidence, and world epoch without retaining a
live chunk. Observed chunks and local generation publish a vertical page beside
the readable top-only compatibility tile; block mutations resample only affected
columns. The hierarchical candidate prefers native vertical pages and uses the
top-only projection only for v1 persistence/network input.

`DistantVerticalPageCodec` is the canonical bounded schema-v2 record encoding.
It round-trips page/world identity, completeness, source revision, and every run
semantic while bounding bytes, palettes, strings, columns, and runs. It rejects
truncation, old schemas, malformed fields, and trailing data. Checked-in binary
goldens pin the page and all four protocol-v2 message encodings; the page codec
also has a checked-in canonical semantic dump.
`DistantDirectoryTerrainStore` writes one atomic checksum-protected record per
dirty page under a world/level manifest. Recovery ignores interrupted temporary
records, restored pages are rekeyed to the active world epoch, and deterministic
inspection reports record, byte, and temporary counts. Its bounded coalescing
writer keeps a key dirty until its page is durable. The controller reads v1
snapshot storage once, migrates every missing retained tile into a partial v2
page, and thereafter writes only the page-oriented store; the v1 decoder remains
readable rollback input but the whole snapshot is no longer a second writer.
Restored v2 pages derive a low-quality tile only for the legacy renderer.

Capacity now evicts the farthest and then least-recent record relative to the
active player while excluding every dirty pinned key; interrupted over-capacity
replacement is deterministically trimmed during recovery. The adapter exposes a
bounded `store-network-inspection` debug operation with schema/count/bytes/temp/
pinned/eviction state and protocol/pending/outstanding/request/receive state.

`DistantTerrainProtocolV2` is shared by client and managed server through
`render-contracts`. Every message carries protocol/data schema, connection and
world epoch, normalized level, and bounded type-specific fields. Requests carry
64-bit correlation, exact page keys/detail and minimum source revision;
responses carry independently bounded canonical page records. The client keeps
the exact expected set per request and atomically rejects wrong-world, unknown,
unrequested, duplicate, unsupported-detail and stale responses. Dimension
replacement cancels the complete ledger. Timed-out v2 requests now remove their
exact page set, cancel the local ledger, and send a correlated wire cancellation;
a failed send rolls local admission back. The production channel negotiates v2;
v1 remains readable but the managed server no longer advertises or admits v1
generation work.

The managed server validates player, level, epoch, radius, detail, request and
queue bounds at admission and again before publication. It initiates bounded
FULL-chunk futures instead of synchronously forcing generation on the normal
tick, deduplicates and shares identical page work across players while rekeying
the result to each request epoch, caps global in-flight work, and suppresses
queued or in-flight publication after cancellation, dimension change, or
disconnect. Admission is atomic across the whole request, rejects an already
active request ID, validates cancellation world identity, honors the requested
minimum source revision, and retires its request remainder after every success,
failure, stale completion, or radius rejection. Its normalized capture carries semantic block/fluid state, fluid
level/class, block/sky light, biome input, opacity, luminance and generated
provenance through the same vertical sampler.

A headless authority-neutral fixture now proves identical normalized semantics
through native/local/server capture inputs, the network codec, and persisted
page restore. Client v2 world retirement and renegotiation now cancel every
exact outstanding request before clearing the local ledger; cancellation send
and failure counts plus world resets are included in bounded network
inspection. Failed sends are best-effort and cannot retain a local request.
The Java integration fixture proves exact world/request correlation and empty
post-retirement ledgers.

Local authority now prepares a complete 3x3 detached `ChunkBuilder`
neighbourhood before publishing the center page. `DistantDetachedLighting`
copies block luminance, directional propagation, and sky-height behavior into
fixed primitive arrays, propagates both channels without retaining Minecraft
objects, and samples only the center page. The one-chunk/16-block halo is larger
than the maximum non-zero light influence; sources outside it cannot affect the
result. Neighbourhood construction consumes the existing budget as actual
generated chunks, can publish no more than that budget per tick, and uses a
bounded 25-entry LRU so adjacent centers reuse six of nine chunks. World-epoch
change clears pending generation and cached builders. Generated pages now claim
`COMPLETE` only after this boundary succeeds. Core fixtures cover directional
blockers, filtered/direct sky, halo exclusion, and no-skylight dimensions; the
real-registry integration fixture proves a torch in the west halo contributes
block light to a center-page water run with the same filtered skylight boundary.

This is not the Phase 7 exit gate: real adapter-state parity and the live
crash/TPS/epoch/reconnect gates remain open.

## Phase 8 shader/interoperability start

The production distant renderer now implements the neutral
`DistantTerrainProvider` contract with a stable compact physical-layout ID,
semantic-layout ID, opaque/water materials, block/sky-light and resolved-tint
semantics, main/shadow views, buffer-update upload, and the pinned DH profile.
Its provider generation is independent from the selected near provider; distant
build identities no longer borrow the near backend generation. Pipeline
candidates reject an aliased near/distant physical layout or any declared view
that either selected provider cannot serve.

Hierarchical results enter a bounded render-thread upload mailbox using the
shared independent CPU/byte budget contract. One oversized first item may
advance to avoid starvation, while page count remains capped per frame. Upload
failure retains last-known-good geometry, enters the existing bounded retry/
quarantine policy, and keeps the page dirty. Shader-generation replacement
cancels stale CPU/upload candidates and rebuilds every retained page without
dropping the installed generation. The rollback renderer now loads both solid
and water candidates before replacing either installed mesh, and partial load
failure releases the complete candidate instead of publishing a mixed
generation.

Existing graph and shader gates already pin distant opaque -> requested distant
depth snapshot -> distant water -> near opaque order, independent DH depth
targets, the dedicated `dh_shadow` route, built-in far projection, and the
optional executable Bliss `dh_terrain`/`dh_water` contract. Phase 8 still needs
the complete accepted real-GL/Bliss resource-baseline and scaling matrix for
the hierarchical route before it can close.

A client-only Apple OpenGL 4.1 run on the named
`terrain-consolidation-phase8-2026-07-31` trajectory exercised the Bliss
`dh_terrain`/`dh_shadow` routes and a shader reload. Near region uploads
converged to zero pending work with eight regions, 130 active pages,
23,961,600 resident bytes, and no allocation or upload failures. Reload
advanced the shader and graph generations, retained last-known-good pages while
their replacements built, resumed the requested distant routes, and returned
the observed shader-resource live counts to their pre-reload values. The run
also exposed an unacceptable hierarchical-route scaling result: roughly one
VAO and two buffers per distant page, hundreds of page draws per frame, and
about 1.7 FPS in the flat-world workload. Distant selected-page residency and
region/material/view batching were therefore required before default adoption;
the later continuation below replaces that resource model and records the
accepted no-property default run.

The first stop of that run found a native shutdown defect: session disconnect
could reach near-region `clear()` from the JVM shutdown hook after the OpenGL
context was no longer current, and its `glFinish` call aborted inside LWJGL.
Off-context teardown now abandons logical references without issuing any GL
call; render-thread teardown retains the fenced device cleanup. Repeating the
same client start and supervised stop completed disconnect, audio unload, debug
channel shutdown, and local-world shutdown without another native fatal error.

A later fence-drain run found a distinct context-ownership defect:
`OpenGlNearTerrainRegionRuntime.prepareFrame()` is invoked by
`ChunkRenderer.prepareDrawAsync()` on a rendering-pool worker, so polling a
submission fence there entered `glClientWaitSync` without a current context and
aborted the JVM. Near-region fence polling, retired-range collection, and empty
device closure now run at the context-current post-draw `finishFrame()`
boundary. The shared OpenGL completion adapter also checks its owning render
context before every native fence operation. The same trajectory then remained
healthy beyond 200 frames with zero distant pending fences, one current near
fence, zero retired pages, and no allocation/upload failures; supervised stop
left no live process and emitted no native fatal or context-guard failure.

## Unified production default and bounded hierarchy continuation

The three independent `minosoft.terrain.semantic-artifacts`,
`minosoft.terrain.region-storage`, and `minosoft.terrain.distant-hierarchy`
booleans were removed. `TerrainRuntimeSelection` now pins one immutable process
mode selected by `minosoft.terrain.runtime`: absent or `unified` selects the
complete consolidated near-artifact, near-region, and distant-hierarchy route;
`unified-compare` captures near semantic artifacts while retaining legacy
publication; and `legacy` is the single release-boundary rollback. Invalid or
partial selections fail closed. Focused and architecture tests pin the default,
the coherent mode combinations, and the absence of the old independent
properties. `render.substrate.terrain` reports the selected mode and each
effective route directly.

The distant hierarchy now has an atomic multi-source mutation whose complete
candidate is validated before one commit. It derives each affected ancestor
once, coalesces lineage/neighbour invalidation, rejects stale/conflicting/
over-capacity batches without partial publication, and retains immutable
snapshots for diagnostics while selection uses a synchronized short-lived
no-copy index view. The vertical reducer no longer constructs sequence,
grouping, string-key, or candidate-list intermediates in its hot interval loop.
Production separates bounded source ingestion/derivation from selected artifact
meshing, reorders pending work from camera and seam state, prunes source work to
the selected dependency closure, preserves last-known-good selection during a
replacement, and progressively publishes an empty/subset initial selection.
Source and artifact submissions have independent caps and share bounded
completion/upload drains. Distant region extents are 32 by 1 by 32 pages under
the existing 32-region and 8+2 MiB per-region limits.

Two 30-second JFR samples isolated and removed the largest render-thread
ingestion allocations. Before the direct metadata scan, three GC pauses totaled
647 ms (median 192 ms, p95 271 ms) and `CollectionsKt.asSequence` accounted for
75.43 percent of sampled allocation. After it, three pauses totaled 396 ms
(median 124 ms, p95 160 ms); the sequence allocation disappeared and exposed
`TreeMap.addEntry` snapshot construction as the next dominant source. The
short-lived read view removes that full-tree selection copy. These are targeted
diagnostic samples, not a final cross-build performance acceptance result.

A clean Java 25 Apple OpenGL 4.1 client generation then ran the Bliss trajectory
without any terrain rollout property. `render.substrate` reported
`runtimeMode=unified` with semantic artifacts, region storage, and distant
hierarchy all selected. The flat 1.20.4 workload converged to 1,360 selected GPU
pages across detail levels 0 through 3, 23 distant regions, 983,760 resident
bytes, 46 device batches, 2,720 commands, zero queued/pending pages, zero
allocation/upload failures, and one current-frame submission fence. Near region
storage simultaneously retained 25 active pages in four regions with zero
retired bytes and zero allocation/upload failures.

A live Bliss reload advanced shader generation 1 to 2. During rebuild the same
1,360 distant GPU pages remained installed while the bounded source/artifact
queues refilled. They returned to zero pending/queued work, and the exact
pre-reload GPU ledger returned to 523 live names (216 buffers, 108 vertex
arrays, 56 textures, one renderbuffer, seven framebuffers, zero shaders, and
135 programs). Near and distant retired bytes returned to zero. Supervised stop
completed disconnect and audio teardown without a native fatal or context-
ownership failure; no client/server process, lease, or transient override
remained. This closes the default-selection and this named reload/resource
baseline, but does not substitute for diverse geometry, motion/seam, checked-
pixel, server, reconnect, or cross-driver gates. The sky-facing capture from the
same flat trajectory is explicitly excluded from visual acceptance.

The enabled page-mask continuation repeated that named Java 25/Apple OpenGL
4.1 Bliss trajectory after regenerating its isolated flat local world. At the
idle boundary, all 16,129 source tiles had produced 21,597 indexed pages,
20,924 CPU pages, and 1,744 selected GPU pages across detail levels zero through
three. Main and shadow selected the same 1,744 pages and independently reported
the same 112 masked pages from near coverage revision 114/lifecycle revision
1,076 with an eight-frame transition. Both hierarchy queues were zero; 23
distant regions retained 1,260,240 bytes with zero retired bytes, allocation
failures, or upload failures and one current-frame fence. The additional seam
fallback residency is 384 pages and 276,480 bytes above the prior 1,360-page
selection. Near storage simultaneously retained 114 pages in six regions with
zero retired bytes or failures. The total driver ledger was 529 live names:
220 buffers, 110 vertex arrays, 56 textures, one renderbuffer, seven
framebuffers, zero shaders, and 135 programs. Supervised stop again completed
cleanly, removed the endpoint and every process, and emitted no current-run
native fatal, context-ownership failure, or OpenGL error. This is lifecycle,
mask-agreement, bounded-residency, and cleanup evidence; the flat pose remains
excluded from checked-pixel seam acceptance.

## Validation

Java 25.0.1 was used throughout. The complete combined diff passed the
following serial checks:

```sh
./gradlew :render-contracts:test
./gradlew compileKotlin :test \
  --tests \
  'de.bixilon.minosoft.architecture.TerrainArchitectureBoundaryTest' \
  --tests \
  'de.bixilon.minosoft.debug.terrain.*' \
  --tests \
  'de.bixilon.minosoft.gui.rendering.terrain.runtime.TerrainBuildRuntimeTest' \
  --tests \
  'de.bixilon.minosoft.terrain.distant.DistantLodDataTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.DistantHorizonsCompatibilityAdapterTest' \
  --tests \
  'de.bixilon.minosoft.gui.rendering.terrain.near.provider.ChunkRendererNearTerrainBackendTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.SodiumTerrainProviderDescriptorTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest' \
  --tests \
  'de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest'
./gradlew integrationTest \
  --tests 'de.bixilon.minosoft.terrain.runtime.TerrainWorldEpochTest'
./gradlew test
git diff --check
```

The dependency-clean scheduler suite additionally covers distant reservation
under near churn, equal-urgency tenant rotation, bounded completion drains and
primitive counters, saturation rejection/cancellation, worker-context reuse,
logical cancellation, owned shutdown, and cleanup failure propagation. The
later bounded trajectories additionally passed focused diagnostic
operation/schema tests and 30 neutral-data, architecture, v1 persistence/
protocol, and distant-render tests before the combined gate. The Phase 6 start
additionally passed the complete `:render-contracts:test` suite plus focused
application compilation, architecture, top-only migration, and distant-render/
protocol regression gates. The later default-route acceptance below supersedes
the original lack of a real-GL hierarchy claim. The Phase-7 gate added
checked-in page/protocol
goldens, one-way top-only migration, authority-neutral network/store parity,
correlated client/server cancellation cleanup, and passed another complete
`./gradlew test` plus `git diff --check`.

The Phase 8 acceptance used only the named client-local trajectory and restored
its reference presentation before stopping it. No client or server remains
running, its lease was released, and no transient reference/canary override is
active. The acceptance framebuffer is
`/tmp/terrain-consolidation-phase8-bliss.png` with SHA-256
`06124862cb7b92cfc4f11e2c579bc3a4be1f7552d8b27af9c352dbf2a38827a5`;
raw lifecycle logs remain untracked runtime state.

The later topology/legacy-retirement slice passed `:render-contracts:test`, the
focused consolidated L3 and architecture tests, and the complete
`./gradlew integrationTest` suite on Java 25.0.1. The class-filtered TestNG
attempt was discarded because omitting the declared `block` dependency group
prevents suite construction; it did not execute or fail a test body.

The distant-selection scaling slice passed the focused dependency-clean
selector suite plus application Kotlin/Java compilation on Java 25. Its
hierarchy-indexed adjacency result remains protected by the existing mixed-
detail balancing fixture; the next named live workload must quantify the
steady-frame and build-churn improvement before it is accepted as Phase 6
scaling evidence.

The enabled coverage-mask slice passed the focused
`TerrainCoverageMaskingTest`, the complete `:render-contracts:test` suite, the
complete root `:test` suite, the architecture and distant-adapter focused gate,
and `git diff --check` on Java 25.0.1. Its supervised live run used no terrain
rollout property and left no process, debug endpoint, server state, or lease.

The empty-Fabric-allowlist and client-ledger lifecycle slice passed the focused
architecture, Iris shader, Fabric preflight, distant-adapter, and Java
integration lifecycle gates on Java 25.0.1. The integration build now includes
the conventional `src/integration-test/java` source directory so the lifecycle
fixture can use the existing fully initialized session harness without widening
Kotlin-internal production APIs.

The expanded near semantic matrix passed through the standalone Java integration
entry point on Java 25.0.1. It exposed and then pinned the detached block-entity
NBT boundary instead of accepting missing entity-dependent model output as an
intentional delta.

The dev supervisor now stages a base-game candidate under a generation-specific
distribution before stopping the active client. This prevents `installDist`
from replacing lazily loaded classes beneath the old JVM; the isolated
destination gate verified that staging did not change the active distribution.
A bounded Bliss/local-world hot reload then moved generation 1 to generation 2,
with the candidate running entirely from the isolated distribution. The old
generation completed world/Fabric/audio teardown without the prior synthetic-
class linkage failure, generation 2 reached render-ready, and supervised stop
removed the client, endpoint, staged generation, and trajectory lease.

## Remaining Phase 0/Phase 1 gates

- retain reproducible semantic, visual, memory, GPU-resource, workload, and
  defect baselines;
- expand the provider-only live diagnostic response only as consolidated
  production owners make complete generations and bounded state available;
- complete the remaining visibility, residency/GPU ownership, and
  complete-provider publication boundaries outside Fabric;
- publish production near+distant+shader selection through the complete
  pipeline generation;
- adopt material, depth, residency, submission, and structured rejection
  contracts at their production boundaries; and
- retain the now-empty renderer/provider architecture allowlist while closing
  the remaining production publication boundaries.

## Remaining Phase 2 gates

- add independent estimated CPU/output and render-thread upload admission;
- adopt centralized failure retry/quarantine state at the page owner;
- expose the scheduler/mailbox counters through the atomic terrain diagnostic
  snapshot when its production world owner exists; and
- prove wrong-world/stale completion, saturation, fairness, and shutdown across
  both providers with deterministic barriers and the runtime feature switch.

## Remaining Phase 3 gates

- pass the named headless and real-OpenGL near-terrain acceptance gates.

## Remaining Phase 4 through Phase 6 gates

- complete transactional region-storage recovery diagnostics and the named
  upload/draw scaling workloads across representative near terrain;
- pass deterministic partial-readiness/removal movement plus main/shadow seam
  acceptance for the enabled production page mask;
- pass the complete headless, real-GL, visual, motion, and high-distance Phase 6
  fixture/scaling matrix on the now-default hierarchy.

## Remaining Phase 7 through Phase 9 gates

- pass live dimension/reconnect/world-epoch, corrupt-store recovery, server
  cancellation, semantic-parity, and named tick/TPS gates;
- finish the accepted built-in and Bliss checked-pixel/seam/shadow scenarios,
  cross-driver resource baselines, and matched scaling/soak workloads;
- publish near, distant, material, shader, graph, world, residency, coverage,
  submission, and scheduler state through the complete atomic production
  pipeline/diagnostic generation rather than the current partial owners;
- connect the remaining provider-neutral visibility/publication boundaries;
- remove legacy near/distant worker, upload, whole-domain mesh, aliases, and
  debug claims after the documented rollback release boundary while retaining
  the v1 store decoder and conventional draw fallback; and
- update the functionality catalog and all affected evidence maps, then pass
  the complete L0 through L6, multi-version, headless, integration, real-GL,
  reload, dimension, reconnect, performance, and soak handoff matrix.
