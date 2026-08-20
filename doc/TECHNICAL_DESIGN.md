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
│  stub/ (CRA-220 logging no-ops) · payload/ (Lettuce + RestClient)         │
│  results/ (RestClient + retry policy)                                     │
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
│   ├── stub/      CRA-220 logging no-op implementations of every port
│   ├── payload/   Redis + results-query-api payload source            (later story)
│   └── results/   add-informant-register submission client            (later story)
├── pipeline/      RegisterBuilder, SubscriptionMatcher, AggregationMapper (later story)
├── persistence/   entities + repositories; migrations in resources/db/migration
└── config/        typed properties, ObjectMapper, health indicators
```

## Processing State Machine

Every command reaches an explicit recorded outcome — "nothing happened" is never an end state.

```
message received
   ▼
(source, requestId) already COMPLETED? ── yes ─▶ log + complete()   [no re-submission]
(source, requestId) already FAILED?    ── yes ─▶ FAILED → RECEIVED  [audit note, attempts kept]
   │ no                                         │  then reprocess below,
   │                                            │  skipping POSTED authorities
   ▼ INSERT processed_request status=RECEIVED
   ▼ fetch payload → transform → submit per authority
   ├─ all authorities settled ─▶ COMPLETED ─▶ complete()
   ├─ transient failure ──────▶ RETRYING, attempts++ ─▶ abandon() → ASB redelivers
   └─ non-transient failure ──▶ FAILED + reason ─────▶ deadLetter() → DLQ alert
```

- **Request statuses:** `RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED` (last two terminal).
- Terminal is not the same as final: a resubmitted `FAILED` request is replayable (below); a
  resubmitted `COMPLETED` request is acknowledged and never reprocessed.
- **Per-authority statuses** (`processed_output`): `POSTED`, `FAILED`.
- A hearing that legitimately yields no authorities still ends `COMPLETED`, with the reason
  `no-authorities` recorded — a business outcome, not an error, and not a status of its own.
- **Transient** (abandon → retry): connect/IO, 5xx, 429 (honour `Retry-After`), payload source
  unavailable, lock lost.
- **Non-transient** (dead-letter): unparseable message, schema violation, non-429 4xx from a
  downstream command, transformation error.
- State is persisted **before** the message is settled.

## Queue and message

- Dedicated queue **`informantregister.requests`** + dead-letter queue, owned by this service
  (per-service queues, not a shared topic).
- Peek-lock, auto-complete disabled, explicit complete/abandon/dead-letter, `maxDeliveryCount` **5**,
  broker duplicate detection **on**, `maxConcurrentCalls` **2** to start (parity with the function
  app's Durable throttle).
- `messageId` = `"{source}:{requestId}"`. Replay tooling always mints a **fresh** `messageId` so a
  deliberate replay is not swallowed by the duplicate-detection window; the processed-log then
  decides, visibly. A resubmitted request in `COMPLETED` is acknowledged without reprocessing; one in
  `FAILED` is **replayable** — the guard transitions it `FAILED` → `RECEIVED` (attempts preserved,
  audit note) and reprocesses it, skipping authorities already `POSTED`. Replaying a dead-lettered
  message is the supported recovery route.
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
| `processed_request` | PK `(source, request_id)` | The idempotency claim. Insert-on-first-sight; a unique violation means a concurrent/duplicate delivery. Carries `hearing_id`, `hearing_day`, `event_type`, `status`, `attempts`, timestamps, `failure_reason`. |
| `processed_output` | PK `output_id`, UNIQUE `(source, request_id, prosecution_authority_id)` | One row per authority, written **before** the POST and updated after. Already-`POSTED` authorities are skipped on redelivery or replay, so partial progress is never re-sent. Carries `request_digest` (SHA-256 of the outbound body) for reconciliation. |

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
| 3 | `InformantRegisterSubscriptions` | `pipeline/SubscriptionMatcher` | `now-subscriptions?on={registerDate}`; port the 2-arg vocabulary call exactly (major-creditor lists always empty today — parity, not a bug fix) |
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
writes · actuator · container build · structured logging.

**Later stories:** Redis payload adapter + query-API fallback · the ported transformation pipeline ·
the results submission adapter with retry policy · `processed_output` population · KEDA scaling ·
DLQ alerting and reconciliation tooling.

## Configuration

Everything `${ENV_VAR:default}` in `application.yaml`, bound to typed `@ConfigurationProperties`.

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8082 | HTTP port (4550 in Kubernetes) |
| `informantregister.servicebus.queue-name` | `informantregister.requests` | Inbound queue |
| `informantregister.servicebus.max-concurrent-calls` | 2 | Processor concurrency |
| `informantregister.servicebus.max-delivery-count` | 5 | Broker dead-letter threshold (mirrors the queue setting) |
| `informantregister.payload.redis.*` | — | Redis host/port/TLS (later story) |
| `informantregister.results.base-url` | — | Results command/query API base (later story) |
| `spring.flyway.enabled` | `true` | Processed-log migrations |

Secrets and identity: Key Vault CSI → env vars; workload identity (`DefaultAzureCredential`) for
Service Bus. **No static keys, no committed connection strings.**

## Testing Strategy

| Test type | Framework | Command |
|-----------|-----------|---------|
| Unit (application layer, no Spring) | JUnit 5 + Mockito + AssertJ | `./gradlew test` |
| Consumer integration | Testcontainers `servicebus-emulator` | `./gradlew test` |
| Persistence integration | Testcontainers Postgres | `./gradlew test` |
| Downstream stubs | WireMock (`dynamicPort()`, exact vendor media types) | `./gradlew test` |
| Golden parity | JUnit twins of the Jest suite over byte-identical fixtures | `./gradlew test` |

TDD is mandatory: failing test first, every commit.

## Observability

Structured JSON logs with `requestId` / `hearingId` in MDC (no defendant PII at `info`); metrics for
processed / failed and queue + DLQ depth; alerts on DLQ > 0 and on sustained failures over 15 minutes.
This alone is a step change from the function app, which fails silently.

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
