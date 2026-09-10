<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.

  This program is distributed in the hope that it will be useful, but WITHOUT
  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
  FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with
  this program. If not, see <https://www.gnu.org/licenses/>.
-->

# Agent-native debugging plan

Status: **Proposed**, 2026-09-07. This is an assessment and delivery plan, not an
implemented API. It refines the [trajectory tooling backlog](agent-trajectory-tooling.md)
for the current visual-debugging workload. The [render checkpoint](../evidence/2026-09-07-small-survival-render-fixes.md)
records accepted observations and the remaining failures separately.

## Assessment

The transport foundation is sufficient. The missing layer is a persistent,
typed workflow that answers a bounded question and owns a complete probe.
An agent should select a trajectory, inspect a scene, run a named experiment,
and receive a concise result with evidence attachments. It should not need to
write Java/Python glue or rediscover handler argument syntax for each experiment.

| Current capability | Friction observed in this session | Existing implementation to extend |
| --- | --- | --- |
| Authenticated local IPC, exact endpoint generations, persistent DebugClient | Ordinary CLI and scenario requests repeatedly reconnect; the agent also repeatedly starts the CLI JVM | `DebugClient`, `DebugChannelServer`, `Play.scenarioRequest` |
| Operation discovery | Names and owners are exposed; request fields, effects, thread and restoration contracts require source inspection | `DebugOperationRegistry`, `ModDebugRegistrar`, `core.capabilities` |
| Leases, hashed diagnosis bundles, pose checkpoints | GUI, weather, shader options, DH overrides and key release were managed in temporary scripts | `TrajectoryCheckpointStore`, `TrajectoryDiagnostics`, existing motion-noise runner |
| Compact metrics and bounded terrain page APIs | We inferred view-distance authority and visibility from source; large substrate/GLSL dumps overwhelmed useful output | `ClientDebugChannel`, terrain diagnostic schemas and cursors |
| Final-frame screenshots and returned-pose motion-noise checks | Synchronous framebuffer readback stalls motion; images and separately sampled state can describe different frames | `ScreenshotTaker`, `Play.debugMotionNoise`, `SceneReviewCapture` |
| Shader route and depth-copy counters | They establish that work ran, but cannot explain the surviving hand imprint or identify a missing section at a particular frame | `render.substrate`, graph depth boundaries, terrain publication diagnostics |

The last visual tests still showed terrain popping, a built-in DH gap and a hand
imprint. Passing mathematical and source-transform tests did not close those
pixel failures. The improved tools must make that distinction explicit.

## Delivery slices

### 1. Typed discovery and persistent probe execution

Add operation descriptors in `debug-core`: schema/version, bounded request and
response fields, defaults/enums/ranges, endpoint role, owner thread, read versus
mutation effects, artifact types, and restoration semantics. Handlers remain the
authority. Discovery advertises which metadata older providers lack.

Extend the checked scenario runner with one persistent DebugClient per role,
named results, pinned generations, bounded steps/deadlines, cancellation, and a
finally/restore phase. Extend checkpoints from pose to the presentation fields
already supported by existing operations. Restoration must compare expected
values, report conflicts, release injected keys, and handle disconnect/reload.
Long operations return a job token and observable terminal state rather than
making a request timeout indistinguishable from a failed mutation.

Expose the same runner and descriptors as native agent tools through a thin
adapter, with the CLI as another frontend. An MCP-facing adapter is one suitable
frontend; it must consume DebugClient and preserve the existing local debug
wire, discovery, credentials and ownership rules. No shell execution or second
game-control protocol is needed.

Suggested frontend actions: resolve runtime, describe operation, query, run probe,
inspect/cancel probe, and read artifact. Names are proposals. A runtime handle
pins the trajectory and both generations; mutation never silently retargets a
replacement process.

Acceptance: reproduce the existing Iris/DH A/B with one declarative plan and no
custom script. Force an operation failure, cancellation, generation replacement
and user pose change. Every run must produce a terminal restoration report;
conflicts preserve the user's values. Verify endpoint cleanup and capabilities.

### 2. Bounded queries and explanations at a selected frame

Use the existing terrain summary/pages/coverage cursor contracts as the model.
Add substrate sections, field selection and strict row/byte limits. Default
responses contain a short summary, truncation metadata and artifact references;
full dumps remain opt-in attachments. A proposed initial inline budget is 8 KiB.

Expose announced server distance and its authority, profile distance, effective
native distance, camera chunk/section, loaded bounds, native candidate/drawn
counts, and fixed rejection categories. Provide a bounded section query answering
whether it was absent, awaiting work, outside distance/frustum, disconnected by
visibility traversal, ready, or submitted. Report native and DH ownership in the
same publication context. Counters use fixed numeric categories; detailed
explanations are assembled only on request.

Add shader inspection by generation/program/stage: source digest and line count
by default, bounded line windows on demand. Add explicit frame/pass attachment
taps for small regions: current/pre-hand/DH depth, packed hand marker, current
color and history. Each result identifies the pass boundary and actual frame.
These are samples at declared boundaries, not an unsupported claim to reconstruct
every pixel's last writer.

Acceptance: explain the three remaining visual failures using bounded queries
and small attachment samples. No full substrate or shader dump is required to
choose the next experiment. Mixed-generation and stale-cursor requests reject
with structured, actionable results.

### 3. Render-owned movement and capture schedules

Extend motion-noise rather than replace it. A probe declares a tick/frame-based
input or camera path, warm-up and idle conditions, stationary control, capture
regions, maximum frames/bytes, and presentation A/B variants. Record requested
and actual pose, input timing, server tick, render frame, source/publication state,
and shader/resource fingerprints with every capture.

Schedule capture at the intended render boundary and collect images afterward.
Use a bounded GPU copy/readback queue with explicit late/dropped results and
backpressure. PBO/fence support and its fallback need backend validation. Report
copy, readback and encoding timing separately; deferred readback is not free and
must not be advertised as non-perturbing without measurement. Client/server
samples include their actual skew rather than claiming cross-process atomicity.

Acceptance: repeat the forward/backward hill workload and stationary control
without screenshot collection stretching a held key's duration. Preserve the
existing PNG/hash contract. Qualify Apple OpenGL 4.1 and a second supported
backend; retain a clearly labelled synchronous fallback.

### 4. Reusable experiments and evidence handoff

Publish named plans for motion/culling, hand-history/DH, shader/native A/B, and
material/biome isolation. Each defines invariants, warm-up, bounded queries,
comparisons and restoration. Results distinguish observed improvement, passing
contract tests, unresolved pixels, and incomplete coverage. Save one small index
with representative images and links to detailed artifacts; generate the durable
evidence skeleton from it.

Acceptance: another agent can rerun the remaining Small Survival failures from
that index without this conversation or temporary scripts. Measure tool calls,
inline bytes, process/connection starts and elapsed orchestration time against
the current scripted workload. Set improvement targets after recording that
baseline, rather than inventing savings from this session.

## Scope and first implementation

Start with descriptors, persistent scenario connections and multi-field restore,
then expose the thin agent frontend. Add compact visibility queries before the
next culling pass; attachment taps before asserting a hand-history repair.
Deferred capture follows because it crosses the renderer/backend boundary.

Keep transport and generic lifecycle contracts in Java 25 `debug-core`, terrain
schemas in dependency-clean `render-contracts`, orchestration in `play-util`, and
render/server work on their respective owner threads. Keep ordinary-play fixtures
out of these probes. Use existing leases without adding repetitive permission
prompts for already authorized work.

World/server creation, live world snapshots, broad subscriptions and arbitrary
object browsing are outside this first delivery. They remain in the existing
backlog. No tooling implementation was performed in this assessment.
