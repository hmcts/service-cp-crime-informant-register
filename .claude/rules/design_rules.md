# Architecture & Domain Rules

This service is a **message-driven pipeline**, not a REST application. There is no controller layer
and no public HTTP API — actuator only. Everything below assumes that shape.

## Pipeline Architecture (ports and adapters)

```
ASB queue informantregister.requests
   │  (peek-lock delivery)
   ▼
InformantRegisterMessageListener        inbound adapter — parse + settle ONLY
   ▼
DistributionPipeline                    application service — the use case, no I/O of its own
   ├─▶ IdempotencyGuard                 (source, requestId) processed-log — has this been done?
   ├─▶ HearingPayloadSource      «port» fetch hearing payload   (Redis INT_ keys → results-query-api fallback)
   ├─▶ RegisterTransformer       «port» fragments → subscriptions → per-authority documents  (LATER STORY)
   └─▶ RegisterSubmissionClient  «port» POST add-informant-register, per authority, with retry (LATER STORY)
   ▼
ProcessingStateService                  writes processed_request / processed_output rows
```

- **Inbound adapter:** deserialises the queue message into `DistributionCommand`, calls the pipeline,
  and performs exactly one settlement (`complete` / `abandon` / `deadLetter`) on every path.
  NO business logic. NO transformation. NO downstream calls.
- **Application service (`DistributionPipeline`):** orchestrates the use case against **ports only**.
  It MUST NOT import Azure, Redis, or HTTP client types, nor any other infrastructure wire type.
  Jackson is the one qualified exception: the hearing payload crosses the core as
  `com.fasterxml.jackson.databind.JsonNode` by design (Principle IV, "canonical JSON in"), treated as
  immutable — read it, derive from it, never mutate a node the core did not construct. That
  permission covers `JsonNode` and its subtypes only; Jackson's binding, streaming and
  `ObjectMapper` configuration machinery stays in the adapters and in `config/`.
  The service is unit-testable with plain mocks and no Spring context.
- **Ports:** Java interfaces owned by the application package. One port per external capability.
  Adapters implement them and live in their own package.
- **Adapters:** the only place infrastructure types appear. CRA-220 ships **stub adapters**
  (logging no-ops) behind the real port interfaces; the Redis/results adapters replace them in
  later stories with **zero change to the pipeline**. If swapping an adapter forces a pipeline
  edit, the port is wrong — fix the port, not the pipeline.
- **Persistence:** Spring Data JDBC/JPA repositories, accessed only by `ProcessingStateService`
  and `IdempotencyGuard`. Never from the listener.

NEVER put business logic in the message listener.
NEVER call a repository or an HTTP client from the listener.
NEVER reference `ServiceBusReceivedMessage` outside the inbound adapter.

### Package structure

```
uk.gov.hmcts.cp.informantregister
├── inbound/       ServiceBusProcessorClient config, message listener, DistributionCommand parsing
├── application/   DistributionPipeline, IdempotencyGuard, ProcessingStateService, port interfaces
├── domain/        records + enums (DistributionCommand, RequestStatus, OutputStatus, …)
├── adapter/
│   ├── stub/      CRA-220 logging no-op implementations of every port
│   ├── payload/   Redis + results-query-api payload source        (later story)
│   └── results/   add-informant-register submission client         (later story)
├── pipeline/      ported transformation: RegisterBuilder, SubscriptionMatcher, AggregationMapper (later story)
├── persistence/   repositories + entities; Flyway migrations in src/main/resources/db/migration
└── config/        typed @ConfigurationProperties, ObjectMapper, health indicators
```

## Domain Model

| Type | Kind | Fields / meaning |
|------|------|------------------|
| `DistributionCommand` | record (inbound message) | `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType` |
| `ProcessedRequest` | entity | PK `(source, requestId)`; `hearingId`, `hearingDay`, `eventType`, `status`, `attempts`, `receivedAt`, `updatedAt`, `failureReason` |
| `ProcessedOutput` | entity | PK `outputId`; FK `(source, requestId)`; `prosecutionAuthorityId`, `prosecutionAuthorityCode`, `registerDate`, `fileName`, `requestDigest`, `status`, `responseCode`, `postedAt`; UNIQUE `(source, requestId, prosecutionAuthorityId)` |
| `RegisterFragment` | record (later story) | One per prosecuting authority — defendants, cases/applications, results after court-extract filtering |
| `InformantRegisterDocument` | record (later story) | The outbound `add-informant-register` body for one authority |

Inbound is **JsonNode-canonical**: the hearing payload stays a Jackson tree and is read through a
typed facade; only what this service *produces* is modelled as typed records. Do not write a full
typed model of the hearing.

## Processing State Machine

Every command reaches an explicit recorded outcome. "Nothing happened" is never an acceptable end
state — silent failure is the disease this service exists to cure.

```
message received
   ▼
(source, requestId) already COMPLETED? ── yes ─▶ log + complete()  [no re-POST, no state change]
(source, requestId) already FAILED?    ── yes ─▶ FAILED → RECEIVED [audit note, attempts preserved]
   │ no                                          │  then reprocess below,
   │                                             │  skipping POSTED authorities
   ▼ INSERT processed_request status=RECEIVED, attempts=1
   ▼ fetch payload            (port)
   ▼ transform                (port, later story)
   ▼ submit per authority     (port, later story) → processed_output row per authority
   ├─ all authorities settled ─▶ status=COMPLETED ─▶ complete()
   ├─ transient failure ──────▶ status=RETRYING, attempts++ ─▶ abandon()  → ASB redelivers
   └─ non-transient failure ──▶ status=FAILED  + reason ────▶ deadLetter() → DLQ alert
        (also: attempts exhausted at maxDeliveryCount ⇒ FAILED + deadLetter())
```

Statuses — request level: `RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED`.
Statuses — per authority (`processed_output`): `POSTED`, `FAILED`.

Rules:

- `COMPLETED` and `FAILED` are **terminal**, but they are not treated alike on a resubmission:
  - `COMPLETED` — acknowledged and `complete()`d without reprocessing. Nothing is re-POSTed and no
    state changes.
  - `FAILED` — **replayable**. A resubmitted message (fresh broker `messageId`, same `requestId`)
    makes the guard transition `FAILED` → `RECEIVED`, preserving `attempts` and writing an audit
    note recording the replay, then reprocess. Authorities already `POSTED` in `processed_output`
    are skipped, so only the work that failed is repeated. This is the supported way to recover a
    dead-lettered request — there is no operator-remembered flag and no manual row edit.
  - Ordinary broker redelivery of the *same* message is unaffected: it is a redelivery, not a
    resubmission, and a terminal request short-circuits it.
- A hearing that legitimately produces no authorities still ends `COMPLETED`, with the reason
  `no-authorities` recorded — it is a business outcome, not an error, and it is not a status of its
  own.
- **Transient** (retry, `abandon()`): connection/IO errors, HTTP 5xx, HTTP 429 (honour `Retry-After`),
  payload source unavailable, both Redis and the fallback unavailable.
- **Non-transient** (straight to `FAILED`, `deadLetter()`): unparseable message, schema violation,
  4xx other than 429 from a downstream command, transformation errors.
- Every state transition is persisted **before** the message is settled. Settle last.
- Attempts counter is authoritative in the DB; it is cross-checked against the message's
  `deliveryCount` but never derived from it alone.

## Queue Semantics Rules

Queue **`informantregister.requests`** (+ its dead-letter queue), owned by this service.

- **Peek-lock only.** `ReceiveAndDelete` is banned — it loses messages on crash.
- **Auto-complete disabled.** Every path through the listener performs exactly one explicit
  `complete()`, `abandon()`, or `deadLetter()`. A path that can return without settling is a bug;
  reviewers reject it.
- `maxDeliveryCount` = **5**. After the fifth delivery the broker dead-letters. The service must
  reach `FAILED` + `deadLetter()` on its own before that where the failure is known to be terminal.
- **Broker duplicate detection is ON.** `messageId` = `"{source}:{requestId}"`. Publishers must set
  it; the service must not depend on it alone — the `(source, requestId)` processed-log is the real
  guard.
- **Replay tooling always mints a fresh `messageId`** (and keeps the original `requestId`), so a
  deliberate replay is never swallowed by the broker's duplicate-detection window. A replay is then
  filtered by the processed-log, which is the intended, observable behaviour.
- `maxConcurrentCalls` starts at **2** (parity with the function app's Durable throttle). Raise only
  once golden-parity tests prove the pipeline is stateless.
- Lock renewal must cover the worst-case pipeline duration; a lock-lost exception is transient.
- **ASB health MUST NEVER gate readiness.** Register the processor health as a non-readiness
  indicator (or `management.endpoint.health.group.readiness` excluding it). A broker blip must not
  restart the pod.
- Message parsing failures dead-letter with a reason; they are never silently dropped and never
  abandoned into an infinite redelivery loop.

## Idempotency Log Design

`add-informant-register` is **not idempotent** — a duplicate POST creates a duplicate register row.
The processed-log is what makes at-least-once delivery safe.

**The guarantee, stated honestly.** At-most-once submission in all normal operation, redeliveries and
replays included; across a crash in the instant between a successful POST and recording it,
at-least-once — the duplicate row is absorbed downstream exactly like a re-share (the 19:00 sweep
dedupes to the latest row per hearing). Strict at-most-once is impossible without Results-side
idempotency, which is out of scope (frozen contract). Consequently an **ambiguous POST** — timeout,
dropped connection, outcome unknown — is **retried**: prefer a possible duplicate, which is absorbed,
over a possible loss, which is silent. Do not write code, or a comment, that promises more than this.

- `processed_request` — PK `(source, request_id)`. Insert on first sight; the insert itself is the
  claim. A unique-violation on insert means a concurrent delivery is already processing: treat it as
  a duplicate, not an error.
- `processed_output` — one row per `(source, requestId, prosecutionAuthorityId)`, written **before**
  the POST (status not yet `POSTED`) and updated after. On redelivery or replay, authorities already
  `POSTED` are **skipped**, so partial progress is never re-sent. Replay is safe by construction —
  no operator-remembered flags.
- `request_digest` (SHA-256 of the outbound body) is stored for reconciliation and replay diffing.
- Migrations are **Flyway** (`src/main/resources/db/migration/V<n>__<description>.sql`) — never
  Liquibase, which is the WildFly-context convention.
- Hearing payloads are **never persisted**. Redis and the results query API remain the payload source.
- The log doubles as the support answer to "was this hearing processed?" — keep it queryable by
  `hearing_id` and `hearing_day`.

## Parity and the Deviations Register

This port is **bug-for-bug parity** with the Node function app. Known oddities are ported as-is.

- Any behavioural difference from the function app MUST be a **named, reviewed entry** in
  `doc/DEVIATIONS.md`, cross-referenced to the defect register (D1–D17) in the design doc.
  The parity harness asserts each registered deviation explicitly and **fails on any unregistered
  difference**.
- Do NOT "fix" behaviour that looks wrong while porting. Specifically, and non-exhaustively:
  group proceedings are **not** skipped by this flow (unlike other register flows); the vocabulary
  call is the 2-arg form (major-creditor lists always empty); `groupId` comes only from group-master
  prosecution cases; letter-delivery recipients are ignored. These are intended behaviour here.
- The one sanctioned behaviour change in this delivery is **transport reliability**: retries, DLQ,
  alerting, and the end of swallowed errors. Everything else waits for business sign-off.
- Golden files: Jest fixtures are copied byte-identical into `src/test/resources/fixtures/`; every
  Jest case gets a JUnit twin. Comparison is field-order-insensitive, array-order-sensitive,
  BigDecimal-tolerant.

## Error Handling and Logging (domain-specific)

- **NO swallowed exceptions, ever.** No empty catch, no `catch (Exception e) { log.debug(...); }`,
  no returning a "success" object from a catch block. Catch to classify and rethrow, or to map onto
  a `FAILED` state that is persisted and settled explicitly.
- Every log line carries `requestId` and `hearingId` (MDC). `source` and `prosecutionAuthorityCode`
  where relevant.
- **No defendant PII at `info`** — no names, addresses, dates of birth, ASNs, or URNs. Identifiers
  only. PII-bearing detail belongs at `debug` and must be off in deployed environments.

## CRA-220 Scope (walking skeleton — remove this section once past the POC)

### Build now
- ASB consumer with correct peek-lock settlement discipline
- `DistributionCommand` parsing + validation
- `(source, requestId)` idempotency guard + `processed_request` table (Flyway)
- Port interfaces with **stub adapters**: payload fetch and register submission as logging no-ops
- Processing state writes for the states reachable without the real adapters
- Actuator health/info, container build, structured logging

### Defer (later stories)
- Redis payload adapter + results-query-api fallback
- The ported transformation pipeline (fragments, subscription matching, aggregation mapping)
- Results `add-informant-register` submission adapter with retry policy
- `processed_output` per-authority rows (schema may land early; population is a later story)
- KEDA scaling, DLQ alerting dashboards, reconciliation tooling

## Out of Scope — do not build here

- Any REST API. If a status/replay surface is ever wanted, it is a separate, agreed story.
- SJP hearings — they stay in the NOWs function app.
- The 19:00 CSV sweep, File Service, and GOV.UK Notify legs — they stay in `cpp-context-results`.
- The `informant_register` table and `results.prosecutor-results` query.
- Any change to the shape of the `add-informant-register` command — it is results-owned and
  `additionalProperties: false`.
