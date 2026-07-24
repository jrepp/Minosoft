<!-- Copyright (C) 2026 Jacob Repp -->

# Sodium adapted activation evidence — 2026-07-21 HST

## Scope

This evidence proves activation of the pinned Sodium 0.5.8 + Minecraft 1.20.4
compatibility adapter and execution of its Minosoft-native graphics hook. It does
not claim that Sodium's Mojang-targeted classes link directly, nor that full
Sodium rendering behavior or performance parity has been implemented.

## Environment

- Host: Darwin arm64
- Java: OpenJDK 17.0.19
- Server/protocol: Minecraft 1.20.4, local offline-mode server
- Pack trajectory: `sodium-validation-2026-07-21`
- Runtime store: `/tmp/minosoft-sodium-live`
- Lifecycle session: `2026-07-22T07:54:37.118559Z-53584`

## Results

| Gate | Result | Evidence |
| --- | --- | --- |
| Immutable resolution | Pass | Sodium JAR matched the Packwiz SHA-512 and the final adapted manifest was staged under immutable pack view `ea26f6437a8f...`. |
| Structured preflight | Pass | Pack and mod report `activation=adapted`; adapter is `minosoft:sodium-0.5.8-mc1.20.4`; blocker list is empty. |
| Runtime activation | Pass | Client emitted `FABRIC_PACK_ACTIVE pack=minosoft_sodium_ladder mode=adapted mods=sodium`. |
| Hook installation | Pass | Render thread emitted `SODIUM_HOOK_INSTALLED hook=chunk-scheduling` against Minosoft's `ChunkRenderer`. |
| Hook execution | Pass | Render thread emitted `SODIUM_HOOK_INVOKED hook=chunk-scheduling`, proving the hook crossed the frame-preparation path. |
| Process stability | Pass | Activation candidate changed client `53793 -> 61093`; final manifest recovery settled on client `67110`. Parent `53584` and server `53589` remained stable with `serverReady=true`. |
| Owned cleanup | Pass | Focused test activates then deactivates the pack and proves the renderer registry returns to empty; renderer unload also emits a hook-unloaded marker. |
| Manifest failure preservation | Pass | Intermediate ladder/index hash mismatches emitted `candidate_failed` and preserved the active client; the consistent manifest then activated normally. |

## Structured result

```text
pack=minosoft_sodium_ladder version=0.1.0 mods=1 activation=adapted
mod=sodium version=0.5.8+mc1.20.4 environment=client activation=adapted adapter=minosoft:sodium-0.5.8-mc1.20.4 blockers=
```

## Boundary learned

- Adapter selection is deny-by-default and requires the exact Sodium version,
  environment, entrypoint keys, transformation declarations, and nested-JAR
  count expected by the compatibility implementation.
- `ADAPTED` is distinct from direct Fabric binary loading. The upstream artifact
  supplies pinned identity and compatibility metadata; explicit Minosoft source
  hooks replace its Mojang mixin/access-widener boundary.
- The first behavioral slice owns chunk-transfer scheduling in the render
  preparation lifecycle. Broader mesh, shader, option, visual, and performance
  parity remains represented as partial ladder work, not as an activation
  blocker.
- Registration cleanup is transactional and reverse-ordered. Current hot reload
  still replaces the client process; an in-process mod-generation swap remains a
  later reload rung.

## Commands checked

```sh
MINOSOFT_MODPACK_STORE=/tmp/minosoft-sodium-live \
./play.sh --modpack sodium --trajectory sodium-validation-2026-07-21

MINOSOFT_MODPACK_STORE=/tmp/minosoft-sodium-live \
./play.sh modpack inspect sodium --trajectory sodium-validation-2026-07-21

./play.sh status --json
rg 'FABRIC_PACK_ACTIVE|SODIUM_HOOK_(INSTALLED|INVOKED)' .run/minosoft-client.log
./gradlew test --tests de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest
```
