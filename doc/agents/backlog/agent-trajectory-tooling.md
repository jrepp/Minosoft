<!-- Copyright (C) 2026 Jacob Repp -->

# Agent trajectory tooling backlog

## Status

This document is **Target** guidance. It translates repeated development-run
failure modes into repository tooling proposals; none is implemented merely
because it appears here.

## Objective

Reduce agent time spent rediscovering live ownership, collecting scattered
evidence, recovering from concurrent mutations, and manually reproducing safe
visual/world A/B runs. New tooling must compose the existing `Play`,
`DebugClient`, scenario runner, and owner-thread debug operations rather than
creating another automation transport.

## Priorities

| Priority | Tool | Outcome |
| --- | --- | --- |
| P0 | Runtime mutation lease | One named owner coordinates shared client/server/world mutations and concurrent sessions fail clearly or remain read-only. |
| P0 | Atomic diagnosis bundle | One command captures status, endpoints, pose/world, shader/presentation, render substrate, fixture list, screenshot, and bounded log delta into a manifest. |
| P0 | Saved-world snapshot | World inspection reads a flushed immutable copy rather than racing live Anvil writes. |
| P1 | Compare-and-restore checkpoint | A run restores pose/weather/presentation/shader state only when current values still match its own mutation. |
| P1 | Visual diagnosis/burst | Fixed-pose captures, samples, Iris/presentation A/B, and temporal classification run as one checked scenario. |
| P1 | Isolated world/server creation | Seed, level, server directory/port, border, safe spawn, pack, backup, start, inspection, and handoff become one recoverable command. |
| P1 | Explicit fixture overlay | Acceptance fixtures mount by named overlay/trajectory and cannot silently enter ordinary play packs. |
| P2 | GPU steady-state guard | Warmup/reload/unload samples compare typed resource counts and retain allocation evidence only on threshold failure. |
| P2 | Trajectory explain/lint | A command reports isolated versus shared paths/processes and checks evidence/fixture/manifest drift. |

## P0 acceptance contracts

### Runtime mutation lease

Proposed shape:

```sh
./play.sh lease acquire --trajectory NAME --scope client
./play.sh lease acquire --trajectory NAME --scope server-world --ttl 20m
./play.sh lease status --json
./play.sh lease release TOKEN
```

- Scopes distinguish client presentation, client gameplay, shared server,
  shared server world, pack publication, and source/hot reload.
- The lease records owner, process identity, trajectory, acquisition time,
  expiry, and intended mutation class without exposing credentials.
- Read-only status/capture remains available while another owner holds a lease.
- Mutating CLI/scenario operations require the compatible lease or an explicit
  user override.
- Process death/expiry makes the lease stale and recoverable; it never signals
  an unrelated PID.

### Atomic diagnosis bundle

Proposed shape:

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

The manifest includes endpoint IDs/generations, timestamps/frames, attachment
SHA-256, command version, and missing-capability warnings. `--visual` captures
both the unmodified frame and a prepared reference when they differ. Bounded
log extraction uses the current process/session boundary, not the entire
accumulated log.

### Saved-world snapshot

Proposed shape:

```sh
./play.sh world snapshot --trajectory NAME --output .run/world-snapshots/RUN
./play.sh worldgen inspect .run/world-snapshots/RUN --json
```

- A bounded server operation requests save/flush on the server thread.
- The launcher copies only the selected level into a new explicit target and
  verifies that source identity did not change during the snapshot boundary.
- Autosave suspension, if required, is always resumed in `finally`.
- The snapshot manifest records seed, level name, enabled datapacks, server PID,
  tick, file count/bytes, and per-file or tree hash.
- Failure deletes or marks only the incomplete candidate; it never edits the
  active world or promotes a partial snapshot.

## P1 workflows

### Compare-and-restore checkpoint

`checkpoint capture` returns an opaque record covering only requested fields.
`checkpoint restore` performs compare-and-set restoration and reports
`restored`, `already-restored`, or `conflict` per field. Initial coverage should
include player pose, client GUI/reference suppression, weather canary state,
world-border canary state, selected Iris pack/options/fingerprint, and prepared
render canaries. A conflict never overwrites a concurrent user's value.

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

1. Implement leases in `Play` without changing the debug wire.
2. Build diagnosis bundles from existing read-only operations.
3. Add the bounded server save/snapshot operation and immutable copy.
4. Layer checkpoint/restore and visual burst scenarios on those primitives.
5. Add isolated server/world allocation and fixture overlays.
6. Add GPU guards and evidence lint after the primary workflows are stable.

Each rung needs focused tests, `core.capabilities`/cleanup validation where the
debug surface changes, and one live acceptance record. Tooling must preserve
headless operation and Java 11 `debug-core` compatibility.
