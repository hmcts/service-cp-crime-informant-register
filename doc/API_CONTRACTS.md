# Contracts — service-cp-crime-informant-register

## There is no REST API

This service exposes **no REST API**. The only HTTP surface is Spring Boot Actuator
(health, info, metrics, Prometheus), which is operational, not a contract with any consumer.

There is no OpenAPI specification — `doc/openapi.yaml` exists only as a comment-only stub explaining this — and no `springdoc`, no Swagger UI, no controller layer.
`doc/openapi.yaml` exists only as a stub explaining this and pointing back here. Do not add
endpoints without an agreed story — a status/replay API is a separate decision, not a default.

This service has exactly **two** contracts:

| # | Contract | Direction | Owner | Shape |
|---|----------|-----------|-------|-------|
| 1 | ASB queue `informantregister.requests` message | Inbound | **Agreed jointly with `cpp-context-results`** (publisher); changes are a cross-team event | JSON message body, documented below |
| 2 | `add-informant-register` command | Outbound | **`cpp-context-results`** | Vendor media type, RAML + JSON schema in the Results repo |

---

## 1. Inbound contract — the queue message

**Queue:** `informantregister.requests` (dedicated to this service) plus its dead-letter queue.
**Publisher:** `cpp-context-results` (`results-event-processor`), one message per resulted regular
hearing.
**Body:** UTF-8 JSON, `application/json`.

```json
{
  "source": "RESULTS",
  "requestId": "6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34",
  "hearingId": "1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa",
  "hearingDay": "2026-08-19",
  "sharedTime": "2026-08-19T16:42:07.512Z",
  "eventType": "Hearing_Resulted",
  "userId": "0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48"
}
```

### Field semantics

| Field | Type | Required | Semantics |
|-------|------|----------|-----------|
| `source` | string | yes | Publishing system. `RESULTS` today. Half of the idempotency key — it namespaces `requestId`, so a future second publisher cannot collide with Results. |
| `requestId` | string (UUID) | yes | Publisher-minted, **deterministic** from `hearingId \| hearingDay \| sharedTime`. A republish of the *same* share therefore carries the same id (dedupe-able); a genuine re-share of the hearing produces a new `sharedTime` and so a new id, and **must** be reprocessed — re-shares are legitimate business events. |
| `hearingId` | string (UUID) | yes | The resulted hearing. Combined with `hearingDay` it forms the Redis cache key and the query-API fallback path. |
| `hearingDay` | string (`YYYY-MM-DD`) | yes | The hearing day this share relates to. Part of the Redis key (`INT_{hearingId}_{hearingDay}_result_`); a legacy key form without it also exists and is tried as a fallback. |
| `sharedTime` | string (ISO-8601 instant) | yes | When the hearing was shared. Drives the request fingerprint and the processed-log row — the message's own jobs. The register date stamped on the outbound document and used for subscription lookup (`now-subscriptions?on={registerDate}`) comes from the **payload's** `sharedTime`, not this field, exactly as the legacy orchestrator reads it (`InformantRegisterOrchestrator/index.js:23`). |
| `eventType` | string | yes | `Hearing_Resulted` only. **SJP hearings are out of scope** — they stay in the NOWs function app. Any other value is a non-transient failure (dead-letter with reason), never a silent skip. |
| `userId` | string (UUID) | **no** | The CPP user who shared the hearing results. Carried so every downstream call made for this message is attributed to that user through `CJSCPPUID`, which is what the function app does today. When absent, the service's own configured system identity is used. When present it MUST be a canonical UUID: anything else is a contract violation and dead-letters like any other. See "User attribution" below. |

The payload itself is **not** in the message — this is a claim check. The full hearing payload is
fetched from Redis, with the results query API as fallback.

**The fetched payload is a wrapper, not a hearing.** The `INT_` cache document is
`{isReshare, hearingDay, sharedTime, hearing}` and the query-API answer is
`{hearing, sharedTime}`. The pipeline opens it at the transformation seam the way the legacy
orchestrator does (`InformantRegisterOrchestrator/index.js:21-24`): the `hearing` member goes to
the transformation under the wrapper's own `sharedTime`. A wrapper whose `hearing` is missing or
`null` is a non-transient failure (the legacy throws there and swallows the run — deviations
entry 7); a wrapper *without* a `sharedTime` is not an error — the register is stamped with the
clock, as `moment.tz(undefined, zone)` stamps it today — but an explicit `null` or non-scalar
`sharedTime` is a non-transient failure, because the legacy renders those `"Invalid dateZ"` into
a `date-time`-typed component, which deviations entry 10 refuses.

### User attribution (`userId`)

**What the legacy does.** The hearing-resulted envelope carries the sharing user in its metadata as
`userId`. The function app's trigger copies it straight into the orchestration input as `cjscppuid`
(`InformantRegisterEventGridTrigger/index.js:15`, `InformantRegisterQueueTrigger/index.js:17`), and
`InformantRegisterOrchestrator/index.js:13,31,46` threads that one value, unchanged, into all three
downstream calls of the run: the hearing payload read (`HearingResultedCacheQuery/index.js:40`), the
now-subscriptions read (`ReferenceDataService.js:44`) and the `add-informant-register` POST
(`ProcessOutboundInformantRegister/index.js:21`). **One `cjscppuid` per run, from the user who
shared the results.**

**What this service does.** The same. A message carrying `userId` runs every one of those three
calls under it; a message without one runs them under the configured system identity
(`informantregister.results.system-user-id`, and `informantregister.referencedata.system-user-id`
for the reference-data read). The per-request identity takes precedence where it is present, and it
is the *same* value for all three calls of the run — the legacy's single-`cjscppuid` semantics, not
three independently resolved identities.

**Why optional and not required.** Two legitimate producers of this message have no user to name:

- **support replay tooling**, where the replay is built by hand rather than re-sent verbatim, or is
  re-sent deliberately without the field because the original user has been deactivated (see the
  replay rule below — a *verbatim* replay still names the user the original named); and
- **the transition window** — producer builds from before this field existed. The consumer ships
  first (see the rollout note below), so during that window every message arrives without it.

A required field would dead-letter both. The consumer therefore accepts a message **both ways**, and
"no user" is a supported state rather than a defect.

**Absent is not the same as null.** Optional means the property may be *omitted*, and nothing more.
`userId` is typed `string` in the schema, and the schema applies that to the property whenever it is
present — so `"userId": null` is a type violation and dead-letters like any other, while omitting
the key entirely is the supported way to say there is no user. Accepting a null would be the parser
answering a body the contract refuses, and there is nothing for it to mean that omission does not
already say. `RESULTS` never sends either form: it parses the envelope's metadata `userId` before
publishing, so a value that could not be an identity is rejected at the publisher rather than bought
as a guaranteed dead-letter here.

**The identity is never logged.** It is a user identifier, and the no-PII gate applies to it exactly
as it applies to the configured system identity, which is treated as a secret. MDC carries
`requestId`, `hearingId` and `source`; it does not carry `userId`, and neither does any log line or
dead-letter reason.

**It is not part of the request fingerprint.** The fingerprint compares `hearingId`, `hearingDay`,
`sharedTime` and `eventType`. A replay of the same request carrying no `userId` where the original
carried one is the *same* unit of work, not an idempotency collision, and adding the identity to the
fingerprint would turn every replay of an attributed request into a dead-letter.

**Contract change, agreed and sequenced.** Adding `userId` is a change to the inbound contract and
is agreed by the owner of both sides — publisher and consumer are the same team — **project-owner
decision, 2026-08-23**. The rollout order is fixed by the closed contract: **the consumer ships
first**. Until this service is deployed, a message carrying `userId` would be rejected as an unknown
field (`additionalProperties: false`), so `cpp-context-results` must not start sending it until this
version is live in the target environment.

### Message properties (broker-level, part of the contract)

| Property | Value | Why |
|----------|-------|-----|
| `messageId` | `"{source}:{requestId}"` — **normal publishing by the producer** | Deterministic, so broker duplicate detection (enabled on the queue) collapses identical republishes of the same share. A **support resubmission is the documented exception** — see the replay rule below. |
| `contentType` | `application/json` | |
| `correlationId` | `hearingId` (recommended) | Cross-service tracing. |

**Replay rule (the exception to the deterministic `messageId`).** A support resubmission of a parked
message carries the **same body** — the same `requestId`, so it is the same unit of work — but it
MUST be published under a **fresh `messageId`, distinct from any identity previously used for that
request**, and never the identity that exhausted the retries. The shape of that fresh identity is
deliberately not prescribed; distinctness is the whole requirement. Two independent mechanisms
depend on it:

- the broker's **duplicate-detection window** would silently discard a resubmission that reused an
  identity already seen — the replay would simply never arrive;
- the service uses the **difference in identity** to tell a deliberate resubmission apart from the
  exhausted message coming round again, and admits the `FAILED` → `RECEIVED` replay only on the
  former.

**Re-send the body verbatim.** The resubmission is the dead-lettered body as it stands, byte for
byte, with the `messageId` the only thing changed. That is not tidiness: **the body carries the
attribution**. A parked message that named a `userId` replays as that original sharing user, with
nothing recorded server-side and nothing for the operator to remember — while a replay rebuilt by
hand without the original body names no user and runs under the configured system identity
(`doc/DEVIATIONS.md` #16). Rebuilding a body from the processed log therefore loses the attribution
that re-sending it keeps.

**Strip `userId` only as a deliberate escape hatch.** There is one reason to remove the field: the
original user has been deactivated and their identity would now be refused downstream, so every call
of the replayed run would fail on it. Removing the field then makes the run the system's, which is
the honest reading of who initiated it. Dropping it does not make the message a different request —
`userId` is outside the request fingerprint, so the replay is the same unit of work and is readmitted
as one rather than dead-lettered as an idempotency collision. Nothing else in the body may be edited:
any change to `hearingId`, `hearingDay`, `sharedTime` or `eventType` *is* an idempotency collision,
by design.

Given a fresh identity, the service's `(source, requestId)` processed-log then decides — visibly, and
recorded — whether work is repeated:

- a request in `COMPLETED` is **acknowledged without reprocessing** — nothing is re-POSTed;
- a request in `FAILED` is **replayable when the replay carries a fresh `messageId`**: the idempotency
  guard transitions it `FAILED` → `RECEIVED` (attempts preserved, an audit note recording the replay)
  and reprocesses it. Replaying a dead-lettered request is therefore the supported recovery route.
  Once the real submission adapter lands, the replay also repeats only the work that failed, skipping
  every authority already `POSTED` in `processed_output` — that skip is the design for those later
  stories, not behaviour of this increment, which writes no `processed_output` rows at all. A
  redelivery under the *original* `messageId` is not a replay: the record stays `FAILED` and
  dead-lettering is re-attempted.

### Delivery and settlement semantics

| Aspect | Value |
|--------|-------|
| Receive mode | Peek-lock (never `ReceiveAndDelete`) |
| Auto-complete | Disabled — exactly one explicit settlement attempt (`complete()` / `abandon()` / `deadLetter()`) per delivery on every handler-controlled path, while the lock is still held |
| `maxDeliveryCount` | 5 |
| Duplicate detection | On (broker), backed by the `(source, requestId)` processed-log |
| Concurrency | `maxConcurrentCalls` 2 initially (parity with the function app's Durable throttle) |
| Dead-letter | Non-transient failures and exhausted retries; the service counts its own dead-lettering (`informantregister_deadlettered_total`), while DLQ **depth** is observed via Azure Monitor's native `DeadletteredMessages` broker metric (alert wiring is deferred to the operability story — see `doc/DEVIATIONS.md` #3) |
| Health | ASB processor health is **never** in the readiness group — a broker blip must not restart the pod |

### Failure behaviour (contractual, tested)

The request state machine starts **only after** contract validation succeeds. A contract-invalid
delivery therefore gets **no `processed_request` record** — it may not even carry a usable
`source`/`requestId` — and is accounted for by its DLQ entry, an ERROR log and a failure metric.

| Condition | Outcome |
|-----------|---------|
| Body not JSON, or required field missing/blank | Contract-validation failure → immediate `deadLetter()` with a sanitised reason, no retry, **no `processed_request` record**. Never dropped silently. |
| `eventType` not `Hearing_Resulted` | Contract-validation failure → immediate `deadLetter()` with a sanitised reason, no retry, **no `processed_request` record**. |
| Unknown extra fields | Contract-validation failure (the schema is closed, `additionalProperties: false`) → immediate `deadLetter()` with a sanitised reason, no retry, **no `processed_request` record**. Contract drift must surface loudly, never be ignored. |
| `(source, requestId)` already `COMPLETED` | Logged, `complete()`, no re-submission. |
| `(source, requestId)` already `FAILED`, redelivered under the **same** `messageId` that exhausted the retries (e.g. dead-lettering did not settle and the lock expired) | Stays `FAILED`; the pipeline does not run; `deadLetter()` is attempted again. |
| `(source, requestId)` already `FAILED`, resubmitted with a fresh `messageId` | Transitioned `FAILED` → `RECEIVED` with an audit note (attempts preserved) and reprocessed. (Skipping authorities already `POSTED` is deferred with `processed_output` — see the replay rule above.) |
| `(source, requestId)` matches an existing record but the immutable fields (`hearingId`, `hearingDay`, `sharedTime`, `eventType`) differ | Idempotency collision → `deadLetter()` with a reason; the original record is never overwritten and the pipeline does not run. |
| `(source, requestId)` is `RECEIVED`/`RETRYING` and the single-runner claim is held by a live runner | Competing delivery → `abandon()` (never `complete()`); no second pipeline run while a run is in flight. |
| `(source, requestId)` is `RECEIVED`/`RETRYING` and the claim is absent or expired (e.g. a runner crashed mid-run) | The delivery atomically reclaims the request and the pipeline runs again. |
| Processed-log store unavailable | `abandon()` (never `complete()`, never `deadLetter()`), and intake is suspended until the store recovers, so an outage cannot burn through `maxDeliveryCount`. Validation is not even attempted until the store returns. |
| Transient downstream failure | `RETRYING` + `abandon()`; ASB redelivers with back-off. |

### JSON Schema

The machine-readable schema lives at `src/main/resources/contracts/distribution-command.schema.json`
and is the validation source used by the message-contract gate. The schema is **normative**; the
service's own parser is the runtime implementation of it, and the contract tests run every corpus
case through both, asserting they agree on accept and reject. On date and time formats the runtime
parser is the strict authority — the RFC 3339 grammar, a `T`/`t` separator and no leap seconds —
while the reference validator is marginally more lenient on two enumerated forms (a space separator,
and a verified leap second), a deliberate divergence pinned case by case in
`DistributionCommandSchemaCorpusTest`. This document is the narrative companion; if they disagree,
the schema plus its tests win, and this page is corrected.

---

## 2. Outbound contract — `add-informant-register`

**Owned by `cpp-context-results`.** This service is a consumer of that contract and must never
redefine, extend, or relax it.

| Aspect | Value |
|--------|-------|
| Endpoint | `POST {results}/results-command-api/command/api/rest/results/informant-register` |
| Content type | `application/vnd.results.add-informant-register+json` |
| Success | `202 Accepted` |
| Headers | `CJSCPPUID` (identity — treated as a secret): the message's `userId` when it carries one, otherwise the configured system identity |
| Schema | `results-json/.../informantRegisterDocument/informantRegisterDocumentRequest.json` in `cpp-context-results`; declared in `results-command-api.raml` |
| Cardinality | **One POST per prosecuting authority** derived from the hearing |

Required fields: `registerDate`, `hearingDate`, `hearingId`, `prosecutionAuthorityId`,
`prosecutionAuthorityCode`, `fileName`, `hearingVenue`.
Optional: `prosecutionAuthorityOuCode`, `majorCreditorCode`, `prosecutionAuthorityName`,
`recipients[]`, `groupId`.

**`additionalProperties: false`** — an unexpected field is a rejected request, not a warning. Any
change to this body is a change to somebody else's contract: raise it with the Results team first.

**Idempotency:** the command is **not** idempotent. A duplicate POST creates a duplicate register
row. (The 19:00 sweep takes the latest row per hearing, so the CSV self-heals — do not rely on it.)
Duplicate submission is prevented on this side by the `(source, requestId)` processed-log plus a
per-authority `processed_output` row, so an authority already `POSTED` is skipped on redelivery or
replay. That row is written **before** the POST, carrying `request_digest` — SHA-256 of exactly the
bytes sent — and moved to `POSTED` or `FAILED` afterwards, so a POST whose outcome is never learned
still leaves evidence of what was attempted.

The guarantee, stated honestly: at-most-once submission in normal operation, redeliveries and
replays included; across a crash in the instant between an accepted POST and the row being marked
`POSTED`, at-least-once. The duplicate is absorbed downstream exactly like a re-share.

**Success is `202 Accepted` and nothing else.** Any other 2xx is treated as a failure: it means
something other than the command endpoint answered — a proxy, or a route that no longer reaches it —
and marking the authority `POSTED` for a command nothing enqueued would lose the register with the
log saying it was sent. It is not retried either, because the body may already have been applied, so
it is reported non-transient under `SUBMISSION_NOT_ACCEPTED` and parked for somebody to look at the
endpoint.

**Retry policy:** connect/IO errors, 5xx and 429 (honouring `Retry-After` in its delta-seconds form;
an HTTP-date falls back to the back-off rather than measuring a remote clock against this pod's) are
retried with exponential back-off; other 4xx are non-transient and go straight to `FAILED` +
dead-letter, on the delivery that met them rather than after the delivery budget is spent. An
**ambiguous** outcome — a timeout, a dropped connection — is retried, deliberately preferring a
possible duplicate (absorbed) over a possible loss (silent). This is the one deliberate behaviour
change from the function app, which swallowed these errors entirely (`doc/DEVIATIONS.md` #2).

The numbers are this service's own, because the contract documents that back-off happens and not how
much of it: `informantregister.results.max-attempts` (4), `initial-backoff` (500ms, doubling),
`max-backoff` (20s — the ceiling on a server-supplied `Retry-After` too, so a misconfigured server
cannot park a run past its claim), `connect-timeout` (5s) and `read-timeout` (30s). The worst case
must stay well inside `informantregister.claim.processing-deadline`.

**Identity and authorisation:** `CJSCPPUID` is the documented header. Its value is the message's
`userId` where the message carries one — the legacy attributes this POST to the user who shared the
results (`ProcessOutboundInformantRegister/index.js:21`) and so does this — and
`informantregister.results.system-user-id` otherwise. The configured identity is
**required** whether or not messages carry a user, because a message without one is a supported
state and the POST still has to be attributable: the gateway refuses to be built
without it, so a deployment missing the identity fails to start rather than dead-lettering every
hearing it is given, one 403 at a time. The Results side additionally applies access-control
rules requiring the caller to be in a named user group (`System Users` is the applicable one for a
service caller), and no repo document states how that identity is presented on the wire. Rather than
guess, every further header is configuration — `informantregister.results.headers.<name>` — so
whatever the mesh requires can be supplied without a code change. **Open decision**, see the
blockers recorded against the submission story.

---

## Other outbound calls (not contracts this service owns)

In every `CJSCPPUID` below, the value is the message's `userId` where it carries one and the
configured system identity otherwise — one identity for the whole run, as the legacy threads one
`cjscppuid` through all three calls. See "User attribution" above.

| Call | Purpose |
|------|---------|
| Redis `GET INT_{hearingId}_{hearingDay}_result_` (and the legacy key without `hearingDay`) | Hearing payload (claim check) |
| `GET {results}/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}`, `Accept: application/vnd.results.hearing-details-internal+json`, `CJSCPPUID` | Payload fallback on cache miss |
| `GET {referencedata}/referencedata-query-api/query/api/rest/referencedata/now-subscriptions?on={YYYY-MM-DD}`, `Accept: application/vnd.referencedata.query.get-now-subscriptions+json`, `CJSCPPUID` | Informant-register subscription matching. `on` is the register date's own day, read with its misleading literal `Z` at face value exactly as the legacy reads it (defect D9). A failure to obtain the body — a connect failure, a read timeout, any status outside 2xx (5xx, 429, 404, and a 304 or unfollowed redirect with them, which is what the ported axios call also treats as a failure), or an answer that is not JSON — is transient and reported, never answered as "nobody is subscribed" (deviation 14). The two headers above are sent exactly once each: a header configured under either name through `informantregister.referencedata.headers` replaces the contract's value rather than joining it. What an obtained body *contains* is never a failure — no `nowSubscriptions` member, or none marked as an informant-register subscription, are business outcomes the matching step reads |

---

## Actuator (operational surface only)

| Path | Purpose |
|------|---------|
| `/actuator/health/liveness` | Liveness |
| `/actuator/health/readiness` | Readiness — **excludes** Service Bus processor health |
| `/actuator/info` | Build/git info |
| `/actuator/prometheus` | Metrics: the service's own counters and gauges (processed/failed, dead-lettered, settlement failures, intake suspension, Service Bus health). Queue and DLQ **depth** are not here — they come from Azure Monitor's native broker metrics |

Not a consumer contract; not versioned; not exposed outside the mesh.
