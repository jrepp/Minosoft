<!-- Copyright (C) 2026 Jacob Repp -->

# Canary and base reload evidence — 2026-07-21 HST

## Environment

- Host: Darwin arm64
- Java: OpenJDK 17.0.19
- Minecraft protocol/server: 1.20.4, local offline-mode test server
- Runtime store: `/tmp/minosoft-canary-acceptance`
- Lifecycle session: `2026-07-22T07:28:41.940733Z-36322`

## Results

| Gate | Result | Evidence |
| --- | --- | --- |
| Initial canary activation | Pass | Parent `36322`, server `36330`, client `36447`; log contained `HOT_RELOAD_CANARY marker=canary-v1`. |
| Canary-only recompile | Pass | Marker `v1 -> v2`; `reloadKind=canary`; candidate hash changed to `f9f4c06da8c0...`; client `36447 -> 36700`; parent/server unchanged. |
| Base-game recompile | Pass | Reversible `Minosoft.kt` probe emitted `reloadKind=base`; client `36700 -> 37102`; parent/server unchanged; canary `v2` loaded again. |
| Source restoration | Pass | Both probes were restored; final client loaded `marker=canary-v1`; no acceptance token remains in source. |
| Artifact placement | Pass | Canary candidates were read-only SHA-256-addressed JARs beneath the external runtime store; no mod JAR was placed in tracked source. |
| Reproducible publication | Pass | After enabling reproducible JAR order/timestamps, `v1 -> v2 -> v1` returned to the same `v1` hash `a038af653d2e...`. |

The canary candidate reached `candidate_ready` 1.024 s after detection and was
active after 3.449 s. The base candidate reached ready after 6.535 s and was
active after 8.957 s. These single-host observations demonstrate that the mod
lane skips `installDist`; they are not performance budgets.

## Boundary learned

- A native Minosoft mod can already be compiled independently and injected from
  an archive through `--mod-source=pre=archive:...`.
- Build isolation is real, but runtime isolation is not yet: both accepted edits
  reconnect a new client process. Mod-only classloader swap still requires an
  owned disposal contract, callback quiescence, and collection evidence.
- A base-game edit rebuilds the canary too, ensuring the sample is checked
  against the candidate host API before the old client is stopped.
- Reproducible JAR settings are part of the identity contract; otherwise ZIP
  entry timestamps create redundant immutable artifacts for identical source.
- Lifecycle JSON now identifies `reloadKind` and the immutable `canaryHash`, so
  later in-process work can retain the same observable acceptance contract.

## Commands and probes

```sh
MINOSOFT_MODPACK_STORE=/tmp/minosoft-canary-acceptance \
MINECRAFT_EULA_ACCEPTED=true \
./play.sh --canary

./play.sh status --json
tail -n 20 .run/play-events.jsonl
rg HOT_RELOAD_CANARY .run/minosoft-client.log
```

The canary probe changed only `HotReloadCanary.MARKER`. The base probe was a
temporary comment in `Minosoft.kt`; both were restored with `apply_patch`.
