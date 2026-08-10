<!--
 Minosoft
 Copyright (C) 2026 Jacob Repp

 This program is free software: you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation, either version 3 of the License, or (at your option) any later
 version.
-->

# Content stacks

These manifests describe ordered references to local content. They never embed
or authorize copying third-party media into the repository. Sources are applied
from first (lowest priority) to last (highest priority), and machine-local paths
are supplied through the named environment variables.

`standalone.json` is the default for `./play.sh content compose`. It combines a
deterministic pack generated from cumulative local audit stages, the sibling
VoxeLibre checkout, assets collected from the managed `distant-horizons-bliss`
modpack, pinned Faithful 32x, Vanilla Evolved, Open Assets Lib, and GUI Revision
resource packs, optional local mods and loose assets, general resource packs,
and optional additional Faithful overlays into one hash-addressed resource pack
in the configured out-of-source modpack store. The independently licensed,
verified external archives are never copied into this repository. Required
root-level license and notice files are copied unmodified into the composed
view's `third-party-notices/` tree.

Use `./play.sh content compose --stage SOURCE --json` to stop after a named
manifest source. Use `./play.sh content audit --trajectory NAME --stage NAME
--json` to create the cumulative inputs consumed by `generated-compatibility`.

## Machine-local overlay (producer/consumer rendezvous)

A tracked manifest never contains machine-specific paths. To point a source at a
local directory — for example the handoff directory where an external **producer**
(Blockbench + automation) writes generated content for this **consumer** to
ingest — drop a git-ignored overlay beside the manifest:

- `content-stacks/standalone.json` is tracked (the source-controlled structure).
- `content-stacks/standalone.local.json` is git-ignored and, when present, is
  applied automatically whenever the loader reads `standalone.json`.

Copy `standalone.local.json.example` to `standalone.local.json` and edit it. The
overlay is `{"schema": 1, "overrides": {"<label>": {…}}}`, keyed by a source
`label`. Only a source's **resolvable** fields may be retargeted — `default`
(path), `environment` (env-var key), and `optional`. Source order, `type`,
`label`, `artifact`, and `multiple` stay source-controlled: an overlay that tries
to change them, or that names an unknown label, is a hard error. This keeps the
input-queue reference machine-local without letting a local file silently
restructure the composed view.

Typical use — retarget the loose-content directory at the producer's output, or
the `generated-compatibility` audit-stage input at a local set of stages:

```json
{
  "schema": 1,
  "overrides": {
    "loose-content": { "default": "/abs/path/to/producer/out/resourcepack" }
  }
}
```

## Finding the ingestion locations

An agent can reach every concrete ingestion directory by following the hops
named in the [asset primitive decomposition
evidence](../doc/agents/evidence/2026-08-08-asset-primitive-decomposition.md#discovery-trail-to-the-content-ingestion-location).
In short: audit snapshots land under `.run/content-audits/`, the triage queue
under `.run/content-queues/`, and both the generated-compatibility provider and
the composed pack land in the configured out-of-source modpack store
(`MINOSOFT_MODPACK_STORE`, default macOS `~/Library/Caches/Minosoft/modpacks`).
Machine-local authored content arrives through the environment variables named
by each source below; `.run/` and the store are regenerated state and are never
committed.
