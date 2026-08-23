# Technical Design — service-cp-crime-informant-register

## Overview

| Field         | Value                                                                 |
|---------------|-----------------------------------------------------------------------|
| Service name  | `service-cp-crime-informant-register` (deployment name `informantregister-service`) |
| Package       | `uk.gov.hmcts.cp.informantregister` (root `uk.gov.hmcts.cp`)           |
| Framework     | Spring Boot 4.1                                                       |
| Java          | 25                                                                     |
| Build         | Gradle (template: `hmcts/service-hmcts-crime-springboot-template`)      |
| Port          | 8082 (local) / 4550 (Kubernetes)                                      |
| HTTP surface  | **Actuator only — no REST API**                                        |
| Inbound       | Azure Service Bus queue `informantregister.requests` (+ DLQ)           |
| Outbound      | `POST add-informant-register` → `cpp-context-results` command API      |
| Database      | PostgreSQL (service-owned processed-log), Flyway migrations            |
| Current story | **CRA-220** — Informant register, initial POC (walking skeleton)       |

Design source: `~/moj/analysis/results-distribution/InformantRegister/option2-implementation-page.md`
(agreed 19 Aug 2026). Current-state detail (what must be ported):
`service-cp-crime-informant-register-design.md` §2.

## The change in one picture

```
TODAY
  hearing resulted → Results ── Event Grid ──▶ informantregister FUNCTION APP (Node.js)
                                                 build register → match subscriptions
                                                 → POST add-informant-register ─▶ Results
  Results: informant_register table → 19:00 scheduled job → CSV → email via GOV.UK Notify

AFTER
  hearing resulted → Results ── NEW: dedicated ASB queue ──▶ informantregister-service (Spring Boot, AKS)
                                                 SAME logic, ported JS → Java
                                                 → POST add-informant-register ─▶ Results   (unchanged)
  Results: informant_register table → 19:00 scheduled job → CSV → email    (completely unchanged)
```

This is a like-for-like port. The service reads the same Redis cache, calls the same reference-data
and results APIs, produces the same documents, and POSTs them to the same Results command endpoint.
Everything downstream of that POST is untouched.

## Architecture — ports and adapters pipeline

```
ASB queue informantregister.requests
   │  peek-lock delivery
   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ INBOUND ADAPTER — InformantRegisterMessageListener                        │
│ parse DistributionCommand · MDC · exactly one settlement per message      │
│ complete() / abandon() / deadLetter() — no business logic                 │
├──────────────────────────────────────────────────────────────────────────┤
│ APPLICATION — DistributionPipeline (the use case; ports only, no I/O)     │
│  ├─ IdempotencyGuard          (source, requestId) processed-log claim     │
│  ├─ HearingPayloadSource «port»   Redis INT_ keys → results-query fallback│
│  ├─ RegisterTransformer  «port»   fragments → subscriptions → documents   │
│  └─ RegisterSubmissionClient «port» POST add-informant-register per authority │
├──────────────────────────────────────────────────────────────────────────┤
│ ADAPTERS — the only place infrastructure types appear                     │
│  stub/ (logging no-ops / refusals) · payload/ (Lettuce + RestClient)      │
│  refdata/ (RestClient) · results/ (RestClient + retry policy)             │
├──────────────────────────────────────────────────────────────────────────┤
│ PERSISTENCE — ProcessingStateService, repositories, Flyway migrations     │
├──────────────────────────────────────────────────────────────────────────┤
│ CROSS-CUTTING — @ConfigurationProperties, ObjectMapper, health, logging   │
└──────────────────────────────────────────────────────────────────────────┘
```

The application layer depends on interfaces only. CRA-220 ships stub adapters behind the real ports;
the Redis and results adapters replace them later with **no pipeline change**. If swapping an adapter
forces a pipeline edit, the port is wrong.

## Package Structure

```
uk.gov.hmcts.cp.informantregister
├── inbound/       ServiceBus config, message listener, DistributionCommand parsing
├── application/   DistributionPipeline, IdempotencyGuard, ProcessingStateService, port interfaces
├── domain/        records + enums (DistributionCommand, RequestStatus, OutputStatus)
├── adapter/
│   ├── stub/      logging no-op / refusing implementations, for local runs and suites
│   ├── payload/   Redis + results-query-api payload source
│   ├── refdata/   referencedata-query-api now-subscriptions source
│   └── results/   add-informant-register submission client + retry policy
├── pipeline/      RegisterBuilder, SubscriptionMatcher, AggregationMapper
├── persistence/   entities + repositories; migrations in resources/db/migration
└── config/        typed properties, ObjectMapper, health indicators
```

## Processing State Machine

Every command reaches an explicit recorded outcome — "nothing happened" is never an end state.

```
message received
   ▼
processed-log store reachable?
   └─ no ─▶ abandon() + suspend intake until it returns  [no record, attempts unchanged]
   ▼ yes
validate against the message contract
   └─ invalid ─▶ deadLetter() with a sanitised reason   [NO processed_request record]
   ▼ valid — the state machine starts here
look up (source, requestId) in processed_request
   ├─ no record ───────────────▶ INSERT status=RECEIVED, take the claim ─────────▶ RUN
   ├─ record found, fingerprint of the immutable fields DIFFERS
   │        ───────────────────▶ idempotency collision: deadLetter() with a reason,
   │                              record untouched, NO run
   ├─ COMPLETED ───────────────▶ log + complete()            [no run, no re-submission]
   ├─ FAILED, arriving messageId ≠ the recorded exhausting identity
   │        ───────────────────▶ FAILED → RECEIVED, take the claim ──────────────▶ RUN
   │                              [audit note, attempts preserved]
   ├─ FAILED, arriving messageId = the recorded exhausting identity
   │        ───────────────────▶ stays FAILED, NO run, deadLetter() re-attempted
   ├─ RECEIVED/RETRYING, claim held by a LIVE runner
   │        ───────────────────▶ abandon() → broker redelivers    [NO run, never complete()]
   └─ RECEIVED/RETRYING, claim expired or absent (crashed runner)
            ───────────────────▶ reclaim the claim atomically ────────────────────▶ RUN
   ▼
RUN — attempts++ atomically as the run starts
   ▼ fetch payload → transform → submit per authority
   ├─ all authorities settled ─▶ COMPLETED ─▶ complete()
   ├─ transient failure, deliveries of this message remaining
   │        ───────────────────▶ RETRYING + reason ─▶ abandon() → ASB redelivers
   ├─ transient failure on the fifth (final) delivery
   │        ───────────────────▶ FAILED + reason + exhausting messageId ─▶ deadLetter()
   └─ non-transient failure ───▶ FAILED + reason + this messageId ───────▶ deadLetter()
```

Only the three branches that reach RUN take the single-runner claim, and each of them releases it on
the way out, whichever outcome the run reaches — so a crashed runner is the only way a claim is left
held, and its expiry is what the reclaim branch waits on. The branches that settle without a run
(COMPLETED, collision, exhausted-identity redelivery, contested delivery) never take it. There is no
path that inserts a fresh `RECEIVED` record over an existing one: an existing non-terminal record is
either contested (abandon) or reclaimed, never duplicated.

The non-transient RUN branch is taken on the delivery that meets the failure, whatever the delivery
budget still allows: the Results command refusing a body refuses the same body next time, so the
remaining deliveries would buy nothing and would only delay the dead-letter support acts on. Its
sources are a non-429 4xx, a 2xx that is not the contract's `202 Accepted`, and — once the
transformation lands — a transformation error. Contract validation is non-transient too, but it is
settled before the state machine starts and so leaves no row.

- **Request statuses:** `RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED` (last two terminal).
- Terminal is not the same as final: a resubmitted `FAILED` request is replayable (below); a
  resubmitted `COMPLETED` request is acknowledged and never reprocessed.
- **Per-authority statuses** (`processed_output`): `PENDING` (row claimed before the POST),
  `POSTED`, `FAILED`. A `POSTED` authority is skipped on any later delivery; see Idempotency below.
- A hearing that legitimately yields no authorities still ends `COMPLETED`, with the reason
  `no-authorities` recorded — a business outcome, not an error, and not a status of its own.
- **`attempts` semantics.** `attempts` is the **lifetime cumulative count of pipeline-run starts**
  for a request, incremented **atomically as a run begins** — successful runs count too. It is
  **not** incremented for a contract-validation rejection, for a delivery returned because the
  processed-log store was unavailable, or for a duplicate settled without a run (a `COMPLETED`
  acknowledgement, a contested non-terminal delivery, an idempotency collision, or a redelivery of
  the identity that already exhausted the retries). It is a support/diagnostic tally, never a
  control variable: **retry exhaustion is judged solely by the broker's delivery count for the
  current message against `maxDeliveryCount` 5, never by `attempts`.** Five failed deliveries
  followed by one successful resubmission therefore leave the record showing `attempts` = 6.
- **Transient** (abandon → retry): connect/IO, 5xx, 429 (honour `Retry-After`), payload source
  unavailable, and processed-log store unavailable — the last of these also suspends intake until
  the store recovers, so an outage cannot burn through `maxDeliveryCount`.
- **Non-transient** (dead-letter): unparseable message, schema violation, non-429 4xx from a
  downstream command, transformation error, and an idempotency collision (same `(source, requestId)`
  carrying a different immutable-field fingerprint). A contract-validation failure is dead-lettered
  *before* the state machine starts, so it produces no `processed_request` row — it is accounted for
  by the DLQ entry, an ERROR log and a failure metric.
- **Lock loss is neither.** Losing the delivery lock is not an "abandon → retry" outcome, because
  once the lock is gone the handler can no longer settle the message at all — there is nothing left
  to abandon, complete or dead-letter. The outcome is therefore a sanitised ERROR log plus a failure
  metric, and recovery relies entirely on the broker redelivering the message when the lock expires.
  The redelivery is then handled by the state machine above like any other delivery.
- State is persisted **before** the message is settled.

## Queue and message

- Dedicated queue **`informantregister.requests`** + dead-letter queue, owned by this service
  (per-service queues, not a shared topic).
- Peek-lock, auto-complete disabled, explicit complete/abandon/dead-letter, `maxDeliveryCount` **5**,
  broker duplicate detection **on**, `maxConcurrentCalls` **2** to start (parity with the function
  app's Durable throttle).
- `messageId` = `"{source}:{requestId}"` for **normal publishing by the producer**. Replay tooling is
  the exception: a support resubmission re-sends the dead-lettered body **verbatim** (so the same
  `requestId`) but must mint a **fresh `messageId`, distinct from any identity previously used for
  that request**, so the replay is neither swallowed by the duplicate-detection window nor mistaken
  for the delivery that exhausted the retries. Verbatim is load-bearing: the body carries the
  attribution, so a replayed message that named a `userId` runs as that original sharing user, while
  one rebuilt without it runs under the system identity. The `userId` field is stripped only as the
  deliberate escape hatch — a user since deactivated, whose identity would now be refused downstream
  — and doing so is not an idempotency collision, because `userId` is outside the request
  fingerprint (`doc/API_CONTRACTS.md`, "Replay rule"; `doc/DEVIATIONS.md` #16). The processed-log then decides, visibly. A resubmitted request in
  `COMPLETED` is acknowledged without reprocessing; one in `FAILED` is **replayable under a fresh
  `messageId`** — the guard transitions it `FAILED` → `RECEIVED` (attempts preserved, audit note) and
  reprocesses it. Replaying a dead-lettered message is the supported recovery route. A redelivery
  carrying the *original* `messageId` (e.g. dead-lettering did not settle and the lock expired) is
  not a replay: the record stays `FAILED`, the pipeline does not run, and dead-lettering is
  re-attempted. A replayed run skips authorities already `POSTED`, so only the work that failed is
  repeated (see Idempotency).
- `requestId` is minted deterministically by the publisher from `hearingId | hearingDay | sharedTime`,
  so a republish of the same share carries the same id while a genuine re-share mints a new one
  (re-shares are legitimate and must be reprocessed).
- **ASB health must never gate readiness** — a broker blip must not restart the pod.

Message shape and field semantics: `doc/API_CONTRACTS.md`.

## Idempotency

`add-informant-register` is **not idempotent** — a duplicate POST creates a duplicate register row.
(The 19:00 sweep takes the latest row per hearing so the CSV self-heals, but nothing relies on that.)

| Table | Key | Purpose |
|-------|-----|---------|
| `processed_request` | PK `(source, request_id)` | The idempotency claim. Insert-on-first-sight; a unique violation means a concurrent/duplicate delivery. Carries `hearing_id`, `hearing_day`, `shared_time`, `event_type`, `status`, `attempts`, `completion_reason`, `failure_reason`, timestamps — plus the three groups of columns the state machine's branches need, below. |
| `processed_output` | PK `output_id`, UNIQUE `(source, request_id, prosecution_authority_id)` | One row per authority, claimed **before** the POST and updated after. Created complete in V1, so the submission story added rows rather than columns. See below. |

`processed_request` columns that the branches of the state machine depend on:

| Column(s) | Rule it serves | Design |
|-----------|----------------|--------|
| `request_fingerprint` | Idempotency collision (spec FR-018) | A **stored SHA-256 hash** over the canonical form of the four immutable fields — `hearingId \| hearingDay \| sharedTime \| eventType` — written when the record is created and never updated. The individual fields are also kept as their own columns for support querying, but the **hash is what the collision check compares**: one fixed-width equality test, no field-by-field drift and no risk of a comparison silently omitting a field as the message contract grows. A mismatch is dead-lettered with a reason and the record is left untouched. |
| `exhausted_message_id` | FAILED redelivery vs. resubmission (spec FR-007) | The broker message identity of the delivery that exhausted `maxDeliveryCount` — written in the same transaction that sets `status = FAILED`, `NULL` before that. A later delivery of a `FAILED` request compares its own `messageId` against this value: **equal** means the same exhausted message coming round again (stays `FAILED`, no run, dead-letter re-attempted); **different** means a deliberate support resubmission (`FAILED` → `RECEIVED`, attempts preserved, audit note, run). |
| `claim_owner`, `claim_token`, `claim_expires_at` | Single-runner claim (spec FR-008) | The claim triple is taken **atomically** in the same conditional `UPDATE`/`INSERT` that moves the record into a running state, stamping the runner's identity (instance + delivery), a **fresh `claim_token` minted on every acquisition**, and an expiry (`now() + lease`). An enforced processing deadline strictly shorter than the lease bounds every run, so a live-but-slow runner aborts (RETRYING) before its lease can lapse; every outcome write is predicated on `claim_owner` **and** `claim_token`, so a runner whose claim was reclaimed cannot overwrite the new owner's result — it discards its work, logs at WARN and abandons. A competing delivery that finds an **unexpired** claim owned by someone else is abandoned for retry and never acknowledged; one that finds an **expired or absent** claim reclaims it atomically (a conditional update guarded on the old owner/expiry, so exactly one of several racing deliveries wins) and runs. The expiry is what makes a crashed runner recoverable without operator action. Exact SQL: `specs/CRA-220-informant-register-initial-poc/data-model.md` "Guard operations". |

`processed_output`, as the submission story implemented it: one row per authority, claimed **before**
the POST and updated after, so an authority already `POSTED` is skipped on redelivery or replay and
partial progress is never re-sent; `request_digest` (SHA-256 of exactly the bytes sent) carried for
reconciliation and replay diffing, and left in place after a failure because what was attempted is
the evidence. The claim and the skip are **one conditional upsert**, not a read followed by a write:
two deliveries of a request can be in flight, and a `SELECT status` is stale by the time the caller
acts on it. The rows only appear once a run produces authorities, which needs the transformation
story; until then the statements are exercised by their own suites and the pipeline still produces
none.

**Delivery guarantee.** At-most-once submission in all normal operation, redeliveries and replays
included. Across a crash in the instant between a successful POST and recording it, the guarantee
degrades to at-least-once — the duplicate row is absorbed downstream exactly like a re-share (the
19:00 sweep dedupes to the latest row per hearing). Strict at-most-once is impossible without
Results-side idempotency, which is out of scope: `add-informant-register` is a frozen, results-owned
contract.

**Ambiguous outcomes.** A POST that times out, loses its connection, or otherwise returns an unknown
outcome is **retried**. The choice is deliberate: a possible duplicate is absorbed, a possible loss is
silent, and silence is the failure mode this service was commissioned to end.

Migrations are **Flyway** (Boot convention in this estate) — not Liquibase. Hearing payloads are
never persisted; Redis and the query API remain the payload source. The log doubles as the support
answer to "was this hearing processed?", queryable by `hearing_id` / `hearing_day`.

## Porting map (JS → Java)

All Node sources under `cpp-context-azure-legalaidagency/azure-functions/durable-functions/`.

| # | Function-app activity | Service component | Notes |
|---|-----------------------|-------------------|-------|
| 1 | `HearingResultedCacheQuery` | `adapter/payload` | Same `INT_{hearingId}_{hearingDay}_result_` key (both key forms), same `hearingDetails/internal` REST fallback; **verified TLS** (fixes `rejectUnauthorized:false`) |
| 2 | `SetInformantRegister` | `pipeline/RegisterBuilder` | One fragment per prosecuting authority; court-extract filtering; first-occurrence-wins identifier dedupe; **group proceedings are NOT skipped** — do not "fix" this |
| 3 | `InformantRegisterSubscriptions` | `pipeline/SubscriptionMatcher` + `adapter/refdata` | The matching is pure and the fetch is a port. `now-subscriptions?on={registerDate}` with the vendor `Accept` type and `CJSCPPUID`; the `on` day honours the register date's misleading `Z` (D9), the retry rule is the legacy `AxiosRetryWrapper`'s, and a failure to obtain the body is reported rather than answered as `null` (deviation 14). Port the 2-arg vocabulary call exactly (major-creditor lists always empty today — parity, not a bug fix) |
| 4 | `OutboundInformantRegister` mappers | `pipeline/AggregationMapper` | Per-authority document incl. recipients, filename, verdict mapping |
| 5 | `ProcessOutboundInformantRegister` | `adapter/results` | POST per authority — **now with retry on connect/IO/5xx/429 and DLQ on exhaustion** (today errors are swallowed) |

Technique: **JsonNode-canonical inbound, typed outbound**. One Java class per JS activity so the
Jest → JUnit mapping stays 1:1.

## Quality gate — behaviour parity by golden files

The JS has ~73 Jest tests plus JSON fixtures. Copy the fixtures byte-identical; write a JUnit twin
for every Jest case; run recorded real hearing payloads through old and new and fail on any
unexplained difference. The recorded set must include multi-authority hearings, court applications,
group proceedings, re-shares and legal-entity defendants. Any deliberate difference is a named entry
in `doc/DEVIATIONS.md` with its own assertion — the harness fails on any *unregistered* divergence.
The build is done when every twin passes and the recorded set matches.

## CRA-220 scope — walking skeleton

**Build now:** ASB consumer with correct settlement discipline · `DistributionCommand` parsing and
validation · `(source, requestId)` idempotency guard + `processed_request` (Flyway) · port interfaces
with **stub adapters** (payload fetch and register submission as logging no-ops) · processing-state
writes · actuator · container build · structured logging · failure ERROR logs and failure/DLQ
metrics. `processed_output` is created as schema only — the stub pipeline produces no outputs, so no
rows are written this increment.

**Since CRA-220:** the submission leg landed — `adapter/results/` POSTs `add-informant-register` per
authority with the retry policy above, and the `processed_output` statements claim, skip and record
around it. It is wired and tested, but produces no rows in normal running until the transformation
story gives a run some authorities to submit.

**Single replica.** CRA-220 deploys **one** consumer pod. Intake suspension on a processed-log
outage is a per-pod decision, so with several replicas an outage could still burn deliveries on the
pods that have not yet noticed it. Cluster-safe suspension — a shared suspension signal, or KEDA
scaling the consumer to zero on store health — is deferred to the KEDA/scale-out story below.

**Later stories:** Redis payload adapter + query-API fallback · the ported transformation pipeline ·
the results submission adapter with retry policy · `processed_output` population · KEDA scaling ·
DLQ **alert wiring** (dashboards and alert rules over the metrics shipped here) and reconciliation
tooling.

## Configuration

Everything `${ENV_VAR:default}` in `application.yaml`, bound to typed `@ConfigurationProperties`.

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8082 | HTTP port (4550 in Kubernetes) |
| `informantregister.servicebus.queue-name` | `informantregister.requests` | Inbound queue |
| `informantregister.servicebus.max-concurrent-calls` | 2 | Processor concurrency |
| `informantregister.servicebus.max-delivery-count` | 5 | Broker dead-letter threshold (mirrors the queue setting) |
| `informantregister.payload.redis.*` | — | Redis host/port/TLS (later story) |
| `informantregister.results.base-url` | — | Results command API base; no default, the local value in `application.yaml` is the command API's own declared `baseUri` |
| `informantregister.results.system-user-id` | — | `CJSCPPUID` identity; a secret, from Key Vault. **Required**: the gateway refuses to be built without one, so a deployment missing it fails to start rather than having every command refused |
| `informantregister.results.headers.*` | — | Any further header the mesh requires; configuration because the authorisation scheme is undocumented |
| `informantregister.results.max-attempts` / `initial-backoff` / `max-backoff` | 4 / 500ms / 20s | POST retry policy; `max-backoff` also caps a server-supplied `Retry-After` |
| `informantregister.results.connect-timeout` / `read-timeout` | 5s / 30s | Worst case must stay inside `claim.processing-deadline` |
| `informantregister.referencedata.mode` | `LIVE` | `LIVE` is the reference-data query-API adapter; `STUB` is the refusing stub, for local runs and the suites that address no register. Startup refuses `STUB` on the deployed credential source |
| `informantregister.referencedata.base-url` | — | Reference-data query API base; no default, the local value in `application.yaml` is the query API's own declared `baseUri`. **Required in `LIVE`** |
| `informantregister.referencedata.system-user-id` | — | `CJSCPPUID` identity; a secret, from Key Vault. **Required in `LIVE`** — reference data authorises the query on it. Falls back to `INFORMANT_REGISTER_SYSTEM_USER_ID`, because the function app threads one `cjscppuid` through both calls |
| `informantregister.referencedata.headers.*` | — | Any further header the mesh requires; same reason as the Results one |
| `informantregister.referencedata.max-attempts` / `retry-interval` | 3 / 1s | The legacy `AxiosRetryWrapper` defaults, ported — including its inverted rule that a status at or below 429 is never retried while a 5xx is |
| `informantregister.referencedata.connect-timeout` / `read-timeout` | 5s / 30s | Worst case must stay inside `claim.processing-deadline` |
| `spring.flyway.enabled` | `true` | Processed-log migrations |

Secrets and identity: Key Vault CSI → env vars; workload identity (`DefaultAzureCredential`) for
Service Bus. **No static keys, no committed connection strings.**

## Testing Strategy

| Test type | Framework | Command |
|-----------|-----------|---------|
| Unit (application layer, no Spring) | JUnit Jupiter 6 (Boot 4.1 test starter) + Mockito + AssertJ | `./gradlew test` |
| Consumer integration | Testcontainers `servicebus-emulator` | `./gradlew test` |
| Persistence integration | Testcontainers Postgres | `./gradlew test` |
| Downstream stubs | WireMock (`dynamicPort()`, exact vendor media types) | `./gradlew test` |
| Golden parity | JUnit twins of the Jest suite over byte-identical fixtures | `./gradlew test` |

TDD is mandatory: failing test first, every commit.

## Observability

Structured JSON logs with `requestId` / `hearingId` in MDC (no defendant PII at `info` or above);
metrics for processed / failed and queue + DLQ depth. Every failure path — processing failure,
contract-validation dead-letter, store-outage suspension, settlement failure, lock loss — emits a
sanitised ERROR log and increments a failure metric, and dead-lettered messages are countable from a
metric.

**Logs and metrics ship in CRA-220; alert wiring does not.** The intended alerts — DLQ depth > 0 and
failures sustained over 15 minutes — are dashboards and alert rules built on these metrics, and they
are deferred to the later operability story. That deferral is a named waiver of the constitution's
alerting requirement (Principle VI), recorded in `doc/DEVIATIONS.md` #3. Even without the alert
rules, the logs and metrics are already a step change from the function app, which fails silently.

## Security

- No public HTTP surface; actuator only, Istio-internal
- Workload identity + Key Vault CSI for Service Bus, Redis and the `CJSCPPUID` identity header
- Verified Redis TLS (the function app disables certificate verification)
- No secrets in the repo; committed function-app keys are being rotated separately

## Deployment

- Container: Dockerfile from the crime Spring Boot template (base `hmcts/apm-services:25-jre`)
- CI/CD: GitHub Actions (`ci-draft` / `ci-released`) → ADO pipeline 460 → `crmdvrepo01.azurecr.io`
- Runtime: AKS via the Flux route (`cpp-flux-config`), `springboot-app` chart, container port 4550
- Chart gaps to raise with Platform: no `ScaledObject` template (KEDA on queue depth is wanted);
  ASB health must be excluded from the readiness group

## Cutover and rollback

No parallel running — both paths POST into the same table, so running both duplicates rows. The
switch is exclusive, and because the data store and the generation leg are identical on both paths,
rollback is trivial:

1. Deploy the service with the Results publisher toggle **off**; function app untouched. Nothing changes.
2. **Cutover (one change window):** turn the publisher toggle on **and** disable the function app's
   trigger (`AzureWebJobs.InformantRegisterEventGridTrigger.Disabled=true`). A few minutes' overlap is
   harmless — a double-processed hearing is absorbed exactly like a re-share.
3. Verify: service consuming; rows appearing in `informant_register`; that evening's 19:00 CSV normal;
   DLQ empty.
4. **Rollback = reverse the two settings.** Event Grid retains up to 24 h of undelivered retries for a
   disabled function, so the backlog redelivers by itself. No data migration, no purge.
5. Function app stays deployed-but-disabled for ≥2 sprints, then is deleted with its Event Grid
   subscription.
