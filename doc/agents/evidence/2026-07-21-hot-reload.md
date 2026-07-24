<!-- Copyright (C) 2026 Jacob Repp -->

# Hot-reload acceptance evidence — 2026-07-21 HST

## Environment

- Host: Darwin 25.2.0 arm64
- Java: OpenJDK 17.0.19
- Minecraft protocol/server: 1.20.4, local offline-mode test server
- Pack: `sodium`, trajectory `acceptance-2026-07-21`
- Runtime store: isolated under `/tmp`; no third-party artifact was written into
  the repository
- Lifecycle session: `2026-07-22T07:00:06.678569Z-15381` (UTC timestamps)

## Results

| Gate | Result | Evidence |
| --- | --- | --- |
| Initial ownership | Pass | Parent `15381`, server `15386`, client `15491`, `serverReady=true`. |
| Valid source reload | Pass | Detection to activation: 5.750 s. Client `15491 -> 15989`; parent/server unchanged. |
| Failed candidate preservation | Pass | Deliberate Kotlin syntax error failed after 1.181 s. Client remained `15989`; parent `15381` and server `15386` remained stable. |
| Recovery after failure | Pass | Restored source activated in 5.609 s. Client `15989 -> 16835`; parent/server unchanged. |
| External trajectory trigger | Pass | Event beneath `MINOSOFT_HOT_RELOAD_PATHS` activated in 3.191 s. Client `16835 -> 17234`; parent/server unchanged. |
| Sodium pack preflight | Pass | Pinned artifact/index verification succeeded; report remained `activation=blocked` with five explicit blockers. |
| Clean shutdown | Pass | `parent_stopping -> parent_stopped` took 1.395 s; final parent/server/client PIDs were null and `serverReady=false`. |
| Source restoration | Pass | Acceptance probe token was removed; focused tests and the full compile/test/assemble run then passed. |

Initial parent-to-server readiness took 4.035 s. Initial parent-to-client start
took 10.444 s. These are observations from one local run, not performance budgets.

## Pack result

```text
pack=minosoft_sodium_ladder version=0.1.0 mods=1 activation=blocked
mod=sodium version=0.5.8+mc1.20.4 environment=client activation=blocked blockers=ACTIVATION_ADAPTER,ENTRYPOINT_LINKAGE,MIXINS,ACCESS_WIDENER,NESTED_JARS
```

## Learned boundaries

- The server must remain a child of the Java play parent; detached launch is not
  the supported lifecycle.
- Candidate compilation and pack preparation happen before the active client is
  stopped, which is what preserved PID `15989` on failure.
- A successful process-generation reload reconnects a fresh client and does not
  preserve arbitrary live client state.
- External watched paths are trigger roots. Their build/composite-build or
  immutable-publication contract remains owned by the external trajectory.
- Sodium is a useful pack/preflight fixture but is not yet a mod activation or
  rendering acceptance fixture.

## Commands checked

```sh
./play.sh status --json
./play.sh modpack inspect sodium --trajectory acceptance-2026-07-21
./play.sh stop
env -u NO_COLOR ./gradlew test assemble
```
