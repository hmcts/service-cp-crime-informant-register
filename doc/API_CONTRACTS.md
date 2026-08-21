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
  "eventType": "Hearing_Resulted"
}
```

### Field semantics

| Field | Type | Required | Semantics |
|-------|------|----------|-----------|
| `source` | string | yes | Publishing system. `RESULTS` today. Half of the idempotency key — it namespaces `requestId`, so a future second publisher cannot collide with Results. |
| `requestId` | string (UUID) | yes | Publisher-minted, **deterministic** from `hearingId \| hearingDay \| sharedTime`. A republish of the *same* share therefore carries the same id (dedupe-able); a genuine re-share of the hearing produces a new `sharedTime` and so a new id, and **must** be reprocessed — re-shares are legitimate business events. |
| `hearingId` | string (UUID) | yes | The resulted hearing. Combined with `hearingDay` it forms the Redis cache key and the query-API fallback path. |
| `hearingDay` | string (`YYYY-MM-DD`) | yes | The hearing day this share relates to. Part of the Redis key (`INT_{hearingId}_{hearingDay}_result_`); a legacy key form without it also exists and is tried as a fallback. |
| `sharedTime` | string (ISO-8601 instant) | yes | When the hearing was shared. Becomes the register date used for subscription lookup (`now-subscriptions?on={registerDate}`) and appears in the outbound document. |
| `eventType` | string | yes | `Hearing_Resulted` only. **SJP hearings are out of scope** — they stay in the NOWs function app. Any other value is a non-transient failure (dead-letter with reason), never a silent skip. |

The payload itself is **not** in the message — this is a claim check. The full hearing payload is
fetched from Redis, with the results query API as fallback.

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
| Headers | `CJSCPPUID` (identity — treated as a secret) |
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
replay. The `processed_output` half of that is the **design for the later real-submission stories**:
this increment creates the table and writes no rows, because the stub pipeline produces no outputs
and this contract is not called at all yet.

**Retry policy:** connect/IO errors, 5xx and 429 (honouring `Retry-After`) are retried with
exponential back-off; other 4xx are non-transient and go straight to `FAILED` + dead-letter. This is
the one deliberate behaviour change from the function app, which swallowed these errors entirely.

---

## Other outbound calls (not contracts this service owns)

| Call | Purpose |
|------|---------|
| Redis `GET INT_{hearingId}_{hearingDay}_result_` (and the legacy key without `hearingDay`) | Hearing payload (claim check) |
| `GET {results}/results-query-api/query/api/rest/results/hearingDetails/internal/{hearingId}`, `Accept: application/vnd.results.hearing-details-internal+json`, `CJSCPPUID` | Payload fallback on cache miss |
| `GET {referencedata}/…/now-subscriptions?on={registerDate}` | Informant-register subscription matching |

---

## Actuator (operational surface only)

| Path | Purpose |
|------|---------|
| `/actuator/health/liveness` | Liveness |
| `/actuator/health/readiness` | Readiness — **excludes** Service Bus processor health |
| `/actuator/info` | Build/git info |
| `/actuator/prometheus` | Metrics: the service's own counters and gauges (processed/failed, dead-lettered, settlement failures, intake suspension, Service Bus health). Queue and DLQ **depth** are not here — they come from Azure Monitor's native broker metrics |

Not a consumer contract; not versioned; not exposed outside the mesh.
