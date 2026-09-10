<!-- Copyright (C) 2026 Jacob Repp -->

# Agent trajectory tooling backlog

## Status

This document mixes implemented checkpoints with remaining **Target** guidance.
The status column and each workflow distinguish current behavior from the next
acceptance boundary. Source and focused tests remain authoritative.

The [2026-09-07 agent-native assessment](agent-native-debugging-plan.md)
refines the next delivery order for debugging/query/probe work: typed discovery,
persistent probes and presentation restoration precede deferred motion capture.

## Objective

Reduce agent time spent rediscovering live ownership, collecting scattered
evidence, recovering from concurrent mutations, and manually reproducing safe
visual/world A/B runs. New tooling must compose the existing `Play`,
`DebugClient`, scenario runner, and owner-thread debug operations rather than
creating another automation transport.

## Priorities

| Priority | Status | Tool | Outcome |
| --- | --- | --- | --- |
| P0 | In progress | Runtime mutation lease | Bounded file-backed `client`, `server-world`, `pack`, and `source` leases exist; broader mutating-command enforcement remains. |
| P0 | In progress | Atomic diagnosis bundle | One bounded bundle exists; prepared-reference pairing and exact process-log deltas remain. |
| P0 | In progress | Saved-world snapshot | A stopped/saved-world immutable copy exists; live server-thread save/flush remains. |
| P1 | In progress | Compare-and-restore checkpoint | Player pose has capture/mark/CAS restore; GUI/weather/presentation/shader fields remain. |
| P1 | In progress | Visual diagnosis/burst | Fixed-pose motion-noise measurement exists; the multi-state Iris/presentation burst remains. |
| P1 | Target | Isolated world/server creation | Seed, level, server directory/port, border, safe spawn, pack, backup, start, inspection, and handoff become one recoverable command. |
| P1 | Target | Explicit fixture overlay | Acceptance fixtures mount by named overlay/trajectory and cannot silently enter ordinary play packs. |
| P2 | Target | GPU steady-state guard | Warmup/reload/unload samples compare typed resource counts and retain allocation evidence only on threshold failure. |
| P2 | Target | Trajectory explain/lint | A command reports isolated versus shared paths/processes and checks evidence/fixture/manifest drift. |

## Implemented checkpoint

The first four ownership/evidence primitives are repository commands:

```sh
./play.sh lease acquire --scope server-world --trajectory NAME --ttl 20m
./play.sh diagnose capture --trajectory NAME --visual --json
./play.sh worldgen snapshot WORLD --output SNAPSHOT --trajectory NAME
./play.sh checkpoint capture --trajectory NAME --output CHECKPOINT
./play.sh checkpoint mark CHECKPOINT
./play.sh checkpoint restore CHECKPOINT
```

- Lease records are atomically published under ignored `.run/leases`, expire
  after a bounded TTL, and use an OS file lock for cross-process conflict
  checks. `client` conflicts are trajectory-local; other scopes are shared.
- Diagnosis writes a bounded manifest plus status, endpoint capabilities and
  metrics, client/server samples, render substrate, fixture inventory, optional
  framebuffer, bounded log tails, and hashes. Missing endpoints become explicit
  warnings rather than an internally inconsistent bundle.
- `worldgen snapshot` refuses a reachable or managed running server, acquires or
  validates a `server-world` lease, copies into a partial candidate, rejects
  symlinks/source mutation, resolves source and output-parent identity before
  containment checks, preserves existing links, hashes the result, and
  atomically publishes it.
- Checkpoints cover `player-pose`. Capture records the original pose, `mark`
  establishes the expected post-mutation value, and restore refuses a mismatch
  before server-authoritative teleport. Conflict and restored records are
  terminal and retained for diagnosis.

These slices do not satisfy the remaining target bullets below.

## P0 acceptance contracts

### Runtime mutation lease

Implemented shape:

```sh
./play.sh lease acquire --trajectory NAME --scope client
./play.sh lease acquire --trajectory NAME --scope server-world --ttl 20m
./play.sh lease status --json
./play.sh lease release TOKEN
```

- Current scopes distinguish trajectory-local client work from shared
  server-world, pack-publication, and source/hot-reload work.
- The lease records owner, process identity, trajectory, acquisition time,
  expiry, and intended mutation class without exposing credentials.
- Read-only status/capture remains available while another owner holds a lease.
- `worldgen snapshot` and `checkpoint restore` require or automatically acquire
  the compatible lease. Enforcing leases across every mutating debug/scenario
  operation remains target work.
- Expiry makes the current lease stale and recoverable without signaling a
  process. Early process-death recovery remains target work.

### Atomic diagnosis bundle

Implemented first slice:

```sh
./play.sh diagnose capture --trajectory NAME --visual --json
```

One render/server-bracketed run writes:

```text
.run/diagnostics/<run-id>/
├── manifest.json
├── status.json
├── client-player.json
├── client-world.json
├── server-summary.json
├── iris-presentation.json
├── render-substrate.json
├── fixtures.json
├── frame.png
└── logs.json
```

The manifest includes endpoint identities/generations, timestamps, attachment
SHA-256, and missing-capability warnings. The current `--visual` captures the
unmodified framebuffer. Prepared-reference pairing and exact process/session
log-delta boundaries remain target work; current logs are size-bounded tails.

### Saved-world snapshot

Implemented stopped-world slice:

```sh
./play.sh worldgen snapshot --trajectory NAME \
  --output .run/world-snapshots/RUN
./play.sh worldgen inspect .run/world-snapshots/RUN --json
```

- Current code requires the managed/configured server to be stopped and
  unreachable. A bounded live server save/flush operation remains target work.
- The launcher copies only the selected level into a new explicit target and
  verifies that source identity did not change during the snapshot boundary.
  Source and output-parent paths are canonicalized before containment checks,
  so a symlink alias cannot route the candidate back into the active world.
- No autosave state is changed in the stopped-world slice. A later live
  save/flush implementation must resume any suspension in `finally`.
- The current manifest records source/output, trajectory, file count/bytes,
  per-file SHA-256, and a tree hash. Live seed, datapack, server PID, and tick
  identity remain part of the server-thread target.
- Failure deletes or marks only the incomplete candidate; it never edits the
  active world or promotes a partial snapshot.

## P1 workflows

### Compare-and-restore checkpoint

`checkpoint capture`, `checkpoint mark`, and `checkpoint restore` implement the
player-pose slice. Restore compares the live pose with `expectedCurrent`, records
a terminal conflict without mutation, or restores the original pose through the
server endpoint and verifies the client result. Next coverage should include
client GUI/reference suppression, weather canary state, world-border canary
state, selected Iris pack/options/fingerprint, and prepared render canaries. A
conflict must never overwrite a concurrent user's value.

### Visual diagnosis and burst

Implemented first slice:

```sh
./play.sh debug visual motion-noise --trajectory NAME \
  --yaw-delta 5 --samples 2 --recovery-frames 0,4,16,32 \
  --region X,Y,W,H --json
```

This bounded command owns the fixed-pose camera-turn subset: it records exact
requested/actual render-frame checkpoints, compares each return-to-pose image
with an equal-frame stationary control, computes luma/RGB/flat-region speckle
metrics, reports measurement cadence, saves representative crops, and restores
its pose and background-throttle override conditionally. See the
[camera-motion noise evidence](../evidence/2026-07-28-camera-motion-noise-measurement.md).

Remaining proposed bundle:

```sh
./play.sh diagnose visual --trajectory NAME \
  --frames 8 --interval 1s --region X,Y,W,H --iris-ab
```

The command validates a fixed pose, samples blocks around the camera, records
material-animation states, captures a bounded frame burst, computes hashes and
luminance/error series, and optionally performs transactional Iris-disabled and
restored-pack A/B. Classification remains evidence, not an automatic root-cause
claim: GUI/location, temporal material, shader pipeline, producer suppression,
or unknown.

### Isolated world/server creation

Proposed shape:

```sh
./play.sh world create NAME --seed N --diameter 1024 \
  --modpack fabric-stack --server-instance NAME --start
```

The command allocates a distinct server directory and port, refuses an existing
level unless `--reuse` is explicit, installs a world-owned safe-spawn border
policy, starts through normal readiness predicates, and validates the selected
datapacks plus generated biome palettes from a saved snapshot. The default
shared `server/` lane remains available but is never implied to be isolated by
a client trajectory.

### Explicit fixture overlay

Pack definitions separate ordinary content from `acceptance/overlays/*.toml`.
The launcher prints and exposes the exact active overlay list. Animated or
checked-pixel resources require an explicit overlay flag and may not appear in
the default pack fixture table. A lint test rejects that drift.

## P2 diagnostics

### GPU steady-state guard

Capture typed OpenGL resource baselines only after a declared warmup. Sample
before/after reload and after retirement, compare buffers/VAOs/textures/FBOs/
programs/shaders/renderbuffers, and attach allocation stacks only when debug
tracking is enabled and a bounded threshold fails. Separate legitimate
world/chunk working-set growth from generation-retirement leaks.

### Trajectory explain and evidence lint

`trajectory explain` prints every relevant process/path and labels it
`isolated`, `content-addressed shared`, or `mutable shared`. `evidence lint`
checks local Markdown links, required status vocabulary, pack/index hashes, and
whether acceptance-only fixtures are mounted by default. It does not attempt to
prove runtime claims from prose.

## Delivery order

1. ~~Implement leases in `Play` without changing the debug wire.~~ First slice complete.
2. ~~Build diagnosis bundles from existing read-only operations.~~ First slice complete.
3. Extend the stopped-world immutable snapshot with a bounded live
   server-thread save/flush boundary.
4. Extend pose checkpoints and the existing motion-noise command into
   multi-field compare/restore and visual burst workflows.
5. Add isolated server/world allocation and fixture overlays.
6. Add GPU guards and evidence lint after the primary workflows are stable.

Each rung needs focused tests, `core.capabilities`/cleanup validation where the
debug surface changes, and one live acceptance record. Tooling must preserve
headless operation and Java 25 `debug-core` compatibility.
