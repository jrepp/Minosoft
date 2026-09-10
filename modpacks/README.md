<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric compatibility packs

This directory contains source-controlled pack definitions and compatibility
ladders. Third-party JARs, expanded metadata, profiles, logs, and runtime state do
not belong here; `play.sh` resolves them into a content-addressed out-of-source
store.

## Run a pack

```sh
./play.sh --modpack sodium --trajectory sodium-main
```

With no explicit action, the Java play utility remains the parent process and
hot-reloads successful client builds while keeping the server stable. Use
`./play.sh start client ...` when a parent-owned, non-watching launch is wanted.

`--trajectory` isolates mutable Minosoft home/profile state so separate branches
or experiments can use the same immutable artifact cache without sharing runtime
state. Override the defaults when needed:

```sh
MINOSOFT_MODPACK_STORE=/absolute/cache/path \
MINOSOFT_MODPACKS_DIR=/absolute/manifest/path \
./play.sh --modpack sodium --trajectory experiment-a
```

Locally built or not-yet-published artifacts can live in a portable,
content-addressed download cache. The launcher verifies them before copying them
into its platform-specific internal store:

```sh
export MINOSOFT_MODPACK_CACHE=/absolute/portable-cache
./play.sh modpack cache add /absolute/path/to/artifact.jar
./play.sh modpack prepare fabric-stack --trajectory experiment-a
```

The portable layout is `<cache>/<hash-format>/<hash>/<filename>`, so the cache
can be copied between machines without changing checked-in manifests. A
`minosoft-cache:` download URL deliberately has no network fallback; if its
exact hash is absent, preparation explains how to seed the cache. Do not commit
cached binaries to this repository.

List, prepare, or inspect packs without starting the client:

```sh
./play.sh setup list
./play.sh setup list --json
./play.sh modpack list
./play.sh modpack prepare sodium --trajectory experiment-a
./play.sh modpack inspect sodium --trajectory experiment-a
./play.sh modpack prepare content-fidelity --trajectory content-fidelity-main
./play.sh modpack inspect content-fidelity --trajectory content-fidelity-main
./play.sh modpack prepare distant-horizons-bliss --trajectory dh-bliss-main
./play.sh modpack inspect distant-horizons-bliss --trajectory dh-bliss-main
```

`setup list` reports the existing trajectory/modpack pairs in the configured
out-of-source store. Its JSON form includes the exact path, whether the pack
still has a source-controlled definition, and a ready-to-run launch command.
`modpack list` remains the inventory of source-controlled pack definitions,
including packs that have not been prepared into a trajectory yet.

## Pack contract

Each pack directory contains:

- `pack.toml` and `index.toml` — Packwiz 1.1 metadata and integrity index.
- `mods/*.pw.toml` — immutable third-party artifact URLs and hashes.
- `resourcepacks/*.pw.toml` — immutable client resource-pack ZIPs, mounted in
  filename order with later entries taking higher asset priority.
- `shaderpacks/*.pw.toml` — at most one immutable client shader-pack ZIP,
  selected through the Iris adapter unless `MINOSOFT_SHADER_PACK` explicitly
  overrides it. An optional top-level `shader-options = "NAME=value;..."` sets
  the verified compatibility baseline unless `MINOSOFT_SHADER_OPTIONS`
  explicitly overrides it.
- `fabric.mod.json` — Fabric metadata for the aggregate pack entry.
- `ladder.tsv` — ordered compatibility claims and acceptance evidence.

The launcher implements the small Packwiz subset above, verifies every indexed
manifest, and verifies every download before publishing it to the shared artifact
store. Resource packs are staged separately from executable mod JARs and written
into the trajectory's resources profile immediately before client launch; manual
profile packs are preserved after the managed stack. `packwiz refresh` can be
used when Packwiz is installed; checked-in hashes must remain consistent.
Shader packs are staged separately, validated for a `shaders/` tree, and passed
to the client from the out-of-source store; they are never mounted as resource
packs or expanded into the repository.

Pack selection currently performs Fabric metadata discovery and compatibility
preflight. A mod marked blocked is staged but not activated. This distinction is
intentional while the reloadable Fabric adapter is built.

`content-fidelity` is deliberately a partial preparation pack. Its existing
Fabric API adapter can activate independently, while pinned Entity Texture
Features, Entity Model Features, and GeckoLib remain blocked so their exact
metadata/dependency surfaces can drive adapter work. OptiFine is recorded only
as a CEM format reference because it is not a Fabric mod and conflicts with the
Fabric replacements. Animated Java is an authoring/export workflow, so it has
no JAR entry; its resource-pack/data-pack acceptance gates live in that pack's
ladder and in [`doc/Assets.md`](../doc/Assets.md).
