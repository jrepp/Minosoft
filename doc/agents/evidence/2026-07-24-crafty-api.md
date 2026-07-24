<!-- Copyright (C) 2026 Jacob Repp -->

# Crafty public API evidence

Date: 2026-07-24

## External contract

Crafty's public API 1.0.0 documents bearer authentication and eleven endpoints:
six image generators, player quick search and profile lookup, Java/Bedrock
server ping, raw skin lookup by username, and skin lookup by hash.

## Minosoft boundary

- `CraftyApiClient` is opt-in and reads `CRAFTY_API_TOKEN` only when
  `fromEnvironment` is explicitly called.
- No boot, session, asset-manager, rendering, or mod-loader path constructs the
  client.
- Every request sets bearer authorization, a 15-second timeout, a Minosoft user
  agent, an endpoint-appropriate `Accept` header, and percent-encoded path/query
  values.
- Player JSON stays schema-flexible. Server ping maps the documented stable
  fields to typed models. Skin and canvas responses remain caller-owned binary
  data with their media type.
- HTTP errors and unsuccessful JSON envelopes preserve status and service
  message without including the token.
- Crafty-returned content is never automatically cached, mounted, bundled, or
  rendered. An explicit caller owns any further provenance decision.

## Secret handling

`RuntimeSection` previously included `System.getenv()` verbatim.
`EnvironmentSanitizer` now redacts variable names shaped like tokens, secrets,
passwords, private/API/access keys, credentials, or authorization values before
the environment reaches a crash report. This includes `CRAFTY_API_TOKEN`.

## Validation

`CraftyApiClientTest` uses a deterministic recording transport to verify every
documented route, GET/POST method selection, bearer authentication, encoding,
typed server parsing, flexible player parsing, binary ownership, HTTP errors,
JSON error envelopes, and explicit environment configuration.
`EnvironmentSanitizerTest` verifies secrets are redacted and ordinary diagnostic
variables remain visible.

Eight focused tests passed. The Java 17 broad gate also passed with `test`,
`integrationTest`, `assemble`, `:debug-core:test`, `:play-util:installDist`, and
`:debug-server-fabric:remapJar`. An authenticated live request was not executed
because no Crafty API token was supplied; no credential was inferred, persisted,
or printed.
