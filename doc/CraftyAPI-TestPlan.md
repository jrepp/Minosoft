<!-- Copyright (C) 2026 Jacob Repp -->

# Crafty public API integration test plan

## Purpose

This plan verifies Minosoft's opt-in Crafty public API client against its local
contract and, when an API token is explicitly supplied, Crafty's live API. It
also holds the security and content-provenance boundaries established for
external content.

The integration is release-ready only when the required automated gates pass
and the live smoke suite has passed with an authorized test account.

## Scope

In scope:

- bearer-token configuration and request authentication;
- every endpoint exposed by `CraftyApiClient`;
- HTTP method, route, path, and query encoding;
- flexible player JSON and typed server-ping parsing;
- skin and generated-image binary responses;
- timeouts, HTTP errors, service error envelopes, malformed responses, and
  empty binary responses;
- token handling and crash-report redaction;
- opt-in behavior and separation from boot, assets, rendering, and mod loading;
- Java 25 execution with Java 25 emitted bytecode.

Out of scope:

- testing Crafty's internal implementation or uptime;
- high-volume, soak, or rate-limit testing without Crafty's approval;
- validating ownership or licensing of arbitrary third-party skins;
- automatically mounting remote responses as Minosoft assets;
- UI behavior, because this integration currently has no UI surface.

## Test ownership

| Area | Owner |
| --- | --- |
| Deterministic unit/contract suite | Minosoft |
| Opt-in live smoke suite | Minosoft maintainer with a Crafty token |
| API availability and service-side correctness | Crafty |
| Test-account, player, server, and content authorization | Person running the live suite |

## Entry criteria

- `CraftyApiClient`, its models, and exceptions compile.
- Crafty's documented API version and endpoint list have been reviewed for
  changes.
- No unrelated Gradle failure is present on the test branch.
- Live testing uses a dedicated or approved Crafty token.
- Live player/server/skin test data is owned by the tester or explicitly
  authorized for testing.

## Test environments

### Deterministic environment

The unit suite uses `CraftyApiTransport` with recorded in-memory requests and
synthetic responses. It must not use DNS or the network and requires no token.

### Live environment

Live tests target `https://api.crafty.gg/api/v2` and are disabled by default.
Use environment variables so credentials and test data never enter source
control:

| Variable | Required | Purpose |
| --- | --- | --- |
| `CRAFTY_API_TOKEN` | Yes | Dedicated bearer token |
| `CRAFTY_TEST_USERNAME` | Yes | Authorized player lookup and raw-skin subject |
| `CRAFTY_TEST_SERVER` | Yes | Operator-approved Java server address |
| `CRAFTY_TEST_BEDROCK_SERVER` | No | Operator-approved Bedrock server address |
| `CRAFTY_TEST_SKIN_HASH` | No | Authorized known skin hash |

Do not place real token values in Gradle properties, profiles, command-line
arguments, logs, screenshots, reports, or issue descriptions.

## Automated contract matrix

These cases belong in
`src/test/java/de/bixilon/minosoft/integrations/crafty/CraftyApiClientTest.kt`.

| ID | Method | Expected request | Core assertions |
| --- | --- | --- | --- |
| CT-001 | `searchPlayers` | `GET /players?search=...` | Search is percent-encoded; successful `data` is unwrapped as flexible JSON. |
| CT-002 | `getPlayer` | `GET /players/{username}` | Username is encoded as one path segment; JSON remains schema-flexible. |
| CT-003 | `pingServer` Java | `GET /servers/ping?ip=...&edition=java` | Address is encoded; version, players, descriptions, information, and mod list parse. |
| CT-004 | `pingServer` Bedrock | `GET /servers/ping?ip=...&edition=bedrock` | Bedrock edition has the documented wire value. |
| CT-005 | `getRawSkin` | `GET /skins/{username}/raw` | Media type and exact bytes are returned without persistence or mutation. |
| CT-006 | `getSkinByHash` | `GET /skins/{hash}` | Hash is encoded and exact binary content is caller-owned. |
| CT-007 | `generateAchievement` | `POST /canvas/achievement` | Item, title, and text are encoded query parameters; response is binary. |
| CT-008 | `generateDeathScreen` | `POST /canvas/death-screen` | Text and score are encoded; response is binary. |
| CT-009 | `generateObserver` | `POST /canvas/observer` | Top and bottom are encoded; response is binary. |
| CT-010 | `generateSign` | `POST /canvas/sign` | Text is encoded; response is binary. |
| CT-011 | `generateSplashText` | `POST /canvas/splashtext` | Text is encoded; response is binary. |
| CT-012 | `generateText` | `POST /canvas/text` | Text is encoded; response is binary. |
| CT-013 | Authentication | All routes | Exactly one `Authorization: Bearer ...` header is present; token is absent from URI and errors. |
| CT-014 | Headers | JSON/binary routes | User agent and endpoint-appropriate `Accept` values are present. |
| CT-015 | Timeout | All routes | Request timeout is 15 seconds unless explicitly overridden. |
| CT-016 | Optional parameters | Canvas routes | Null parameters are omitted; empty and Unicode values are encoded predictably. |
| CT-017 | Validation | Required inputs | Blank token, username, search, address, or hash fails before transport execution. |
| CT-018 | Base URI | Construction | Relative, query-bearing, and fragment-bearing base URIs are rejected. |

## Response and failure matrix

| ID | Condition | Expected result |
| --- | --- | --- |
| FL-001 | HTTP 401/403 JSON error | `CraftyApiException` retains status and safe service message. |
| FL-002 | HTTP 404 JSON error | Exception retains status; response is not treated as empty success. |
| FL-003 | HTTP 429 | Rate-limit response is surfaced; caller can decide when to retry. No automatic retry storm occurs. |
| FL-004 | HTTP 500/502/503 | Exception retains status and never exposes bearer token. |
| FL-005 | HTTP error with non-JSON body | A safe status-based fallback message is used. |
| FL-006 | HTTP 200 with `success: false` | Service message becomes `CraftyApiException`. |
| FL-007 | Malformed JSON | Exception identifies malformed JSON and retains the HTTP status. |
| FL-008 | JSON returned by binary endpoint | JSON error envelope is handled; unexpected successful JSON is rejected. |
| FL-009 | Empty binary response | Response is rejected as invalid. |
| FL-010 | Missing `Content-Type` on valid binary | Bytes remain usable and media type is null. |
| FL-011 | Connection timeout | Network exception reaches the caller within the configured bound. |
| FL-012 | Interrupted request | Thread interruption is not converted into an infinite retry. |

## Security and privacy tests

| ID | Test | Pass condition |
| --- | --- | --- |
| SEC-001 | Missing environment token | `fromEnvironment` fails locally without a request. |
| SEC-002 | Header injection token | Tokens containing CR/LF are rejected. |
| SEC-003 | Crash environment | `CRAFTY_API_TOKEN` is rendered as `<redacted>`. |
| SEC-004 | General secret redaction | Token, secret, password, private/API/access-key, credential, and authorization variables are redacted. |
| SEC-005 | Ordinary diagnostics | Non-sensitive variables remain available in crash reports. |
| SEC-006 | Error paths | Token value is absent from exception messages and test reports. |
| SEC-007 | Repository scan | No real Crafty token or captured private response is present in tracked files. |
| SEC-008 | Request destination | Default production requests target only Crafty's HTTPS API base URI. |

## Provenance and lifecycle tests

| ID | Test | Pass condition |
| --- | --- | --- |
| PV-001 | Application boot without token | Minosoft starts without contacting or configuring Crafty. |
| PV-002 | Headless boot without token | Headless behavior remains unchanged. |
| PV-003 | Asset manager isolation | No Crafty client or response is registered as a session asset provider. |
| PV-004 | Rendering isolation | Skin and generated-image responses are not automatically uploaded or displayed. |
| PV-005 | Mod-loader isolation | Crafty availability does not change mod discovery or activation. |
| PV-006 | Caller ownership | Binary response bytes are returned directly to the explicit caller without cache writes. |

## Opt-in live smoke suite

Implement live cases under
`src/integration-test/kotlin/de/bixilon/minosoft/integrations/crafty/` and place
them in an `external` group. The suite must skip, not fail, when
`CRAFTY_API_TOKEN` or required authorized test data is absent.

Run live cases sequentially to minimize API usage:

| ID | Live case | Pass condition |
| --- | --- | --- |
| LV-001 | Player quick search | 2xx success; response is valid JSON and includes a plausible result structure. |
| LV-002 | Player profile | Authorized username returns valid JSON without token exposure. |
| LV-003 | Java server ping | Typed response includes a non-negative player count and version/description data when supplied by the server. |
| LV-004 | Bedrock server ping | Runs only when an approved address is supplied; response parses without Java-only assumptions. |
| LV-005 | Raw skin | Response is non-empty and has an image or binary media type; bytes are not written to the repository. |
| LV-006 | Skin by hash | Runs only when an authorized hash is supplied; response is non-empty. |
| LV-007 | Six image generators | Each returns non-empty image bytes for original test strings; no generated result is committed. |
| LV-008 | Invalid token | A deliberately invalid temporary token produces the expected authentication error without printing either token. |

Use original neutral generator text such as `Minosoft integration test`; do not
submit copied Minecraft text, artwork, or third-party personal data.

## Commands

Focused deterministic gate:

```sh
JAVA_HOME=/path/to/java25 ./gradlew :test -x :debug-core:test \
  --tests de.bixilon.minosoft.integrations.crafty.CraftyApiClientTest \
  --tests de.bixilon.minosoft.util.crash.section.EnvironmentSanitizerTest
```

Broad release gate:

```sh
JAVA_HOME=/path/to/java25 ./gradlew \
  test integrationTest assemble \
  :debug-core:test :play-util:installDist :debug-server-fabric:remapJar
```

The live suite command must be added with the live test implementation. It must
require an explicit opt-in property in addition to the token, for example
`-PcraftyLiveTests=true`, so an inherited environment token cannot unexpectedly
produce network traffic.

## Evidence collection

Record:

- date, Java version, branch/commit, and Crafty API version reviewed;
- focused and broad Gradle outcomes;
- live case IDs and pass/fail/skip counts;
- HTTP status, response media type, and elapsed time, but not response bodies
  containing player data;
- whether Crafty's documented endpoints or schemas changed;
- defects and follow-up owners.

Never record bearer tokens, authorization headers, full skin bytes, generated
images, or private player response bodies in test reports.

## Exit criteria

Required for merge:

- all deterministic contract, failure, security, and redaction tests pass;
- the broad Gradle gate passes;
- repository scans find no credential or captured private content;
- the implementation remains opt-in and isolated from asset/session startup.

Required before claiming live compatibility:

- LV-001 through LV-003, LV-005, LV-007, and LV-008 pass with authorized data;
- optional LV-004 and LV-006 either pass or are explicitly skipped because
  approved test data was not supplied;
- no token or private content appears in logs or reports;
- any API drift is reflected in code, tests, this plan, and the integration
  documentation.

## Known follow-up

The deterministic suite exists. The environment-gated live integration suite
described above has not yet been implemented, and no live compatibility claim
should be made until it is present and passes with an authorized token.
