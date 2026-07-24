<!-- Copyright (C) 2026 Jacob Repp -->

# Portable resource-pack stack evidence — 2026-07-22

## Decision

Pack-owned resource packs use the same verified, content-addressed artifact
pipeline as mods, but remain a distinct non-executable artifact class:

```text
modpacks/<pack>/resourcepacks/*.pw.toml
  -> artifacts/<hash-format>/<hash>/<file>
  -> packs/<pack>/<fingerprint>/resourcepacks/<file>
  -> trajectory resources profile
  -> SessionAssetsManager priority stack
```

The Java play parent validates the indexed metadata, URL, side, filename, hash,
ZIP structure, `pack.mcmeta`, and `assets/` tree. It writes managed ZIP entries
atomically into the trajectory's resources profile immediately before client
launch. This timing prevents the outgoing client generation from overwriting a
candidate profile during shutdown. Existing user-managed entries are preserved
after the managed list and therefore retain higher priority.

Resource-pack metadata participates in the pack fingerprint and hot-reload
watch tree. Artifact bytes remain out of source; only URLs, hashes, update
identities, and ordering are tracked.

## Initial stack

| Order | Pack | Version evidence | Integrity |
| ---: | --- | --- | --- |
| 1 | Enhanced Audio | Modrinth r7 explicitly lists Minecraft 1.20.4; ZIP contains `sounds.json` and 662 OGG files. | SHA-512 `7db245…1451fe` |
| 2 | Faithful 64x | Local ZIP exactly matched official Modrinth Release 14. Release 14 targets Minecraft 26.2, so newer-only assets may not apply to the 1.20.4 session. | SHA-512 `63afb2…0b531e0` |

Later managed entries have higher asset priority because `AssetsLoader` adds the
profile list in reverse. The two packs affect different primary domains
(sounds versus textures), so their current overlap is minimal.

## Automated and live evidence

- `:play-util:installDist` compiled the separate artifact staging and atomic
  profile materialization path.
- `PlayUtilityTest` passed all four launcher contract tests.
- Full Java 17 acceptance passed: 1,434 unit tests and 2,001 integration tests
  with zero failures (116 integration skips).
- `./play.sh modpack prepare fabric-stack --trajectory debug-control-plane`
  resolved two resource packs into immutable view fingerprint
  `10595552e082…`.
- Parent session `2026-07-23T02:49:18.018447Z-77297` launched generation 1 with
  both managed profile entries, then the asset-mount diagnostic change
  hot-reloaded generation 2 without a candidate failure.
- Generation 2 (PID 78216) reported ready, rendering, and `playing` in
  `minecraft:overworld`.
- Runtime asset logs recorded both `Mounting zip resource pack` entries followed
  by `Assets verified!`; no fatal asset failure occurred.
- Debug visual capture at frame 4046 showed the Faithful high-resolution birch,
  foliage, terrain, and HUD textures in the live world.

The Enhanced Audio resources are mounted and available to the sound manager.
Resource-pack selection did not silently change user audio policy. Master audio
was later enabled explicitly through the new profile-backed in-game controls;
see [audio-menu evidence](2026-07-22-audio-menu.md).

Live playback exposed a separate index-composition defect: the pack's partial
`sounds.json` initially shadowed the vanilla index. `SoundManager` now merges
all layers from base to highest priority, preserving unrelated events and
honoring per-event append/replace semantics.
