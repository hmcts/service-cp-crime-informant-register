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
   │                              …on the final permitted delivery instead:
   │                              deadLetter() CLAIM_NOT_ACQUIRED  [NO run, row untouched]
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
- **Handed back with no delivery left is a third outcome**, and neither of the two above. Three
  admission branches return a delivery rather than running it — the claim could not be taken, a
  `FAILED` record moved under the replay, and the record was absent after the insert race — and each
  expects the broker to deliver again. On the final permitted delivery there is no again: `abandon()`
  carries no back-off, so a hand-back that keeps recurring consumes the budget back-to-back in under
  a second and the broker parks the message under *its own* reason — no code of ours, no
  `deadlettered` metric, nothing in the log index to search for. `IdempotencyGuard.admit` therefore
  escalates the last hand-back to a dead-letter of ours. It carries the branch's own reason code
  (`CLAIM_NOT_ACQUIRED`, `REPLAY_NOT_ADMITTED`, `RECORD_ABSENT`) and **not**
  `DELIVERY_LIMIT_EXHAUSTED`: the budget says *when* a request was parked, never *why*, and the code
  is what tells support whether to look at lease timing or at a stuck runner. **Nothing is written** —
  on all three branches this runner never held the claim, and every terminal write is predicated on
  `claim_owner` and `claim_token`, so what the budget buys is attribution, not state. In particular
  the request is never parked out from under a live holder whose run may still be succeeding; the row
  is left to whoever owns it. Asserted in `application/AdmissionExhaustionTest` and, against the real
  store, in `persistence/CrashWindowIT`.
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
  the exception: a support resubmission must re-send the dead-lettered body **verbatim** (so the
  same `requestId`) and must mint a **fresh `messageId`, distinct from any identity previously used
  for that request**, so the replay is neither swallowed by the duplicate-detection window nor mistaken
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
| `claim_owner`, `claim_token`, `claim_expires_at` | Single-runner claim (spec FR-008) | The claim triple is taken **atomically** in the same conditional `UPDATE`/`INSERT` that moves the record into a running state, stamping the runner's identity (instance + delivery), a **fresh `claim_token` minted on every acquisition**, and an expiry (`now() + lease`). An enforced processing deadline strictly shorter than the lease bounds every run, so a live-but-slow runner aborts (RETRYING) before its lease can lapse — bounded to the deadline plus at most one authority's worst-case POST, because the deadline is tested before each submission and a submission already begun runs to its own timeout (see Claim and lock timing); every outcome write is predicated on `claim_owner` **and** `claim_token`, so a runner whose claim was reclaimed cannot overwrite the new owner's result — it discards its work, logs at WARN and abandons. A competing delivery that finds an **unexpired** claim owned by someone else is abandoned for retry and never acknowledged; one that finds an **expired or absent** claim reclaims it atomically (a conditional update guarded on the old owner/expiry, so exactly one of several racing deliveries wins) and runs. The expiry is what makes a crashed runner recoverable without operator action. Exact SQL: `specs/CRA-220-informant-register-initial-poc/data-model.md` "Guard operations". |

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

## Claim and lock timing

Three durations govern how long a run may take and how quickly a dead runner's work is retaken:
`LockDuration` on the queue, `informantregister.claim.lease` in the processed log, and
`informantregister.claim.processing-deadline` in the pipeline. They are not independent, and every
relationship between them is enforced at startup by `PropertiesValidator` rather than left to review.

**The defect these values close.** With `LockDuration` 1m against a 5m lease, a pod that dies mid-run
leaves `claim_expires_at` almost five minutes in the future. The broker redelivers about a lock
duration later, the redelivery finds a live claim, and `IdempotencyGuard.claimOrHandBack` abandons
with `CLAIM_NOT_ACQUIRED`. Service Bus makes an abandoned message available **immediately** — there
is no back-off on `abandon()` — so the remaining deliveries are consumed back-to-back in under a
second, the broker dead-letters under *its own* reason, and `processed_request` is left `RECEIVED`
with a live claim, no `FAILED` status, no failure reason and no `deadlettered` metric. That is
precisely the silent parking the state machine exists to prevent. Raising `maxDeliveryCount` does not
help: the delivery budget is a count, never a clock, so a budget of 50 burns as fast as a budget of 5.

**Two layers, not one.** The values below remove the *trigger* — after a crash the claim has always
lapsed by the time the broker redelivers, so the redelivery reclaims on its first attempt and the
bounce never starts. They do not, and cannot, remove the *class*: a hand-back that recurs for some
other reason still walks the same path. The second layer is the guard's escalation of the final
hand-back into a dead-letter of ours (Processing State Machine, "handed back with no delivery left"),
which makes any residual instance visible instead of silent. Timing prevents the common case;
escalation ensures the uncommon one is attributable. Neither substitutes for the other.

### Broker settings — on the queue, not in `application.yaml`

| Setting | Value | Note |
|---------|-------|------|
| `LockDuration` | **5m** | The ASB maximum. Raised from 1m; this is the change that makes crash recovery work |
| `MaxDeliveryCount` | 5 | Unchanged |
| Duplicate detection | on | `messageId` = `{source}:{requestId}` |

The emulator's queue definition — `docker/servicebus-emulator/config.json`, mounted by both
docker-compose and the Testcontainers suites — is where that queue setting actually lives for local
and CI runs, and it carries the same `PT5M`. It was `PT1M` against a 5m lease, which is to say the
defect above was the shipped local configuration and every `*IT` ran against it.

`LockDuration` is mirrored into `informantregister.servicebus.lock-duration` so the validator can
enforce the invariants below. The two must move **together**: changing the queue without the config
silently voids rule 3 and restores the defect. This is the second mirrored broker setting with
nothing verifying the mirror, `max-delivery-count` being the first.

### Timing values

```yaml
informantregister:
  servicebus:
    lock-duration: 5m                # mirrors the queue's LockDuration
    max-auto-lock-renew-duration: 5m
    max-delivery-count: 5
    max-concurrent-calls: 2
  claim:
    lease: PT4M30S
    processing-deadline: 4m
```

**Compound durations must be ISO-8601.** Spring's simple format takes a single value and unit
(`5m`, `30s`), so `4m30s` will not bind — write `PT4M30S`. `ProcessedRequestRepository` relies on
this too: it renders the `Duration` with `toString()` and Postgres reads the ISO-8601 form as an
`interval` (`ProcessedRequestRepository.java:298`).

Every downstream timeout is **unchanged** by this design, which is the reason for choosing it over a
shortened lease: `results` and `referencedata` keep their 5s connect / 30s read, `payload.redis` its
5s / 5s, `payload.fallback` its 5s / 30s with 3 attempts. See Configuration.

### Invariants

| # | Rule | Where | Check |
|---|------|-------|-------|
| 1 | `processing-deadline` < `lease` | `PropertiesValidator.validateRunFinishesBeforeTheClaimExpires` | 4m < 4m30s |
| 2 | `max-auto-lock-renew-duration` >= `processing-deadline` + 30s | `PropertiesValidator.validateLockOutlivesTheRun` | 5m >= 4m30s |
| 3 | `lease` < `lock-duration` | `PropertiesValidator.validateTheClaimLapsesBeforeTheBrokerRedelivers` | 4m30s < 5m |
| 4 | payload fetch worst case < `processing-deadline` | `PropertiesValidator.validateTheFetchFinishesInsideTheRun` | 2m07s < 4m |
| 5 | refdata read worst case < `processing-deadline` | `PropertiesValidator.validateTheSubscriptionsReadFinishesInsideTheRun` | 1m47s < 4m |

Rule 3 is what prevents the bounce: a dead runner's claim has always lapsed by the time the broker
redelivers, so the redelivery reclaims on its **first** attempt instead of burning the budget.

**The submission leg is not in this table, and that is a gap rather than an omission.** Rules 4 and 5
budget the two *reads* against the deadline; nothing budgets the *writes*. One authority's worst case
is `max-attempts 4 x (5s connect + 30s read)` = 140s plus three back-offs which `max-backoff` caps at
20s each when a server supplies a `Retry-After` — **3m20s for a single authority**, against a 4m
deadline, and multiplied by however many authorities a hearing produces. The runtime half of the
answer is in place (`DistributionPipeline.submitWithin` tests the deadline before every POST); the
startup half needs an expected-maximum-authorities figure that is neither configured nor measured.
Until it exists, a `results` retry policy that cannot fit is caught by an aborted run rather than by
a refused boot. See Outstanding code changes.

Worst cases behind rules 4 and 5, with their working, because the next person to raise a
`read-timeout` needs the arithmetic and not the answer:

- **Payload fetch** — 2 x (5s connect + 5s command) Redis = 20s, plus 3 x (5s connect + 30s read)
  fallback = 105s, plus 2 x 1s retry interval = **127s**
- **Reference data** — 3 x (5s connect + 30s read) = 105s, plus 2 x 1s retry interval = **107s**

Document the **inequalities**, not the values. Any change to a downstream timeout, the deadline or
the lease has to be walked through all five rules; all five are startup-fatal, which is what stops a
plausible one-line YAML edit from putting two runners on the same request.

### What the values buy

| Property | Value |
|----------|-------|
| Claim lapses after a crash | 4m30s from claim acquisition |
| First redelivery after a crash | ~5m from receipt, on lock expiry |
| Safety margin (`lock-duration` - `lease`) | 30s |
| Deliveries consumed by one crash | 2 of 5 — the dead one, plus the successful reclaim |
| Crash recovery latency | up to ~5m, no operator action |
| Wedged run (process alive, pipeline blocked) | claim lapses at 4m30s; the SDK stops renewing the lock at 5m, so the redelivery still finds it lapsed |

Five minutes of recovery latency is deliberate and, for this flow, invisible: the register's business
clock is the 19:00 CSV sweep in `cpp-context-results`, so a hearing recovered at 14:03 rather than
13:58 reaches the same batch.

### Outstanding code changes

The values above are necessary but not sufficient. The state-machine completeness violation that sat
alongside them is a timing question in none of its parts, and no lease value substitutes for it: a
recurring hand-back that runs out of deliveries ends as a broker-reasoned dead-letter with no row and
no metric behind it whatever the lease says.

**Landed.** The guard now claims that parking. `IdempotencyGuard.admit` escalates a hand-back to a
dead-letter of ours when the delivery budget ends with this delivery, over the whole decision rather
than at each site, so the three admission paths — `CLAIM_NOT_ACQUIRED`, `REPLAY_NOT_ADMITTED`,
`RECORD_ABSENT` — are covered and a fourth added later inherits it. The path's own reason code is
carried through rather than replaced by `DELIVERY_LIMIT_EXHAUSTED`: the budget says when a request
was parked, never why, and the code is what tells support whether to look at lease timing or at a
stuck runner. Nothing is written to the row — on all three paths the runner holds no claim, so what
the budget buys is attribution, not state (`application/AdmissionExhaustionTest`).

The processing deadline is now tested before **every** POST rather than once before the loop
(`DistributionPipeline.submitWithin`). One authority can spend `results.max-attempts` x
(connect + read) plus back-offs, so a loop entered with seconds of budget left could run minutes past
the deadline and past the lease — at which point a redelivery has reclaimed the request and is
granted every authority not yet `POSTED`, and both runners POST a command that is not idempotent.
Aborting mid-loop is transient and loses nothing: the authorities already sent are skipped on the
redelivery, so only the outstanding ones repeat.

**What it bounds, stated honestly.** The check caps the *number* of overrunning POSTs at one, not the
overrun itself: a submission begun an instant before the deadline still runs to its own timeout, up
to 3m20s. A run can therefore still finish past the 4m30s lease — one authority's worth, not every
remaining authority's — and the crash-window duplicate stays possible in that narrow case. That
residual is the accepted at-least-once case the 19:00 sweep absorbs. Closing it needs the startup
rule in Outstanding code changes, and even then a single authority's worst case has to fit the
budget that is left. Do not write a comment promising the deadline bounds a run outright.

`CrashWindowIT` now races the real lease rather than calling
`ProcessedLogTestSupport.expireClaim(...)`: it configures a short lease, waits for the database's own
clock to pass it, and asserts `claim_expires_at` equals `created_at` plus the configured lease
exactly — the assertion a fixture that rewrites the column makes impossible, and the reason the suite
could not have caught any of this. The final-permitted-delivery parking is asserted there too,
against the real store rather than mocks.

**Landed — the remaining three hand-backs.** Every hand-back this service produces is now escalated
on the same rule, so no path can end as a broker-reasoned dead-letter with no row and no metric.

`STALE_RUNNER` is returned when a terminal write affects no row, which happens after admission and so
never reaches `admit`'s escalation. The delivery's budget position now travels on `RunClaim`
alongside the message identity, and `IdempotencyGuard.rejectStaleRunner` — the single rejection all
four outcome writes fall back to — parks the message with `STALE_RUNNER` as the detail when the budget
ends with this delivery. Applying it in the rejection rather than at the four writes is the same
reasoning that put the admission escalation over the whole decision: a fifth write inherits it. Under
`recordExhaustion` this is not an edge case but the normal case, that method being reached only *on*
the final delivery. Nothing is written, and the invariant is stronger here than on the admission
paths: a stale runner is a non-holder *by proof* — the write it just attempted was predicated on
`claim_owner` and `claim_token` and affected nothing — and the row now belongs to the runner that
reclaimed it, which may be succeeding at this moment (`application/OutcomeWriteExhaustionTest`).

`STORE_UNAVAILABLE` and `UNEXPECTED_FAILURE` are manufactured in the listener, which owns its own
settlement decision, and are escalated there by one `handBack` helper both routes pass through — again
so a third hand-back added to that class inherits the rule. These two are where the broker's own
reason costs most, because neither failure is the message's fault: parked as `STORE_UNAVAILABLE` the
message tells support to go and look at the database, and parked as `MaxDeliveryCountExceeded` it
tells them nothing. Nothing is written on either path and nothing could be — on the store path the log
is unreachable and the body was deliberately never read, so there is no key to write under, and on
the fault path the listener holds no claim. Intake still stops on the store path: that the store is
down does not stop being true because this delivery was parked. The unexpected-fault metric stays
`TRANSIENT` whichever way the delivery settles, because the classification describes the fault and not
the settlement — reclassifying on the last delivery would make the series report a different kind of
failure at the moment it met the same one for the fifth time; what became of the message is recorded
by the dead-letter counter (`inbound/ListenerExhaustionTest`).

The two ERROR lines on the store paths no longer say the delivery is being returned. They cannot: the
budget decides that, and `handBack` names the settlement that actually happened.

**Still outstanding:**

| Change | Location |
|--------|----------|
| A startup rule that the submission leg fits inside `processing-deadline` — `payload worst case + refdata worst case + (results worst case x expected max authorities)`. Needs an expected-maximum-authorities figure, which is neither configured nor measured today; the per-POST check in `DistributionPipeline.submitWithin` catches the overrun at runtime in the meantime | `PropertiesValidator` |

No Flyway migration is required: `claim_expires_at` already exists and is already written as
`now() + lease`. No `doc/DEVIATIONS.md` entry is required either — this sits inside the sanctioned
transport-reliability change and has no function-app counterpart, the Durable framework having owned
this concern there.

### Deferred — renewable lease, if recovery latency starts to matter

The single `lease` knob is over-loaded: it must be longer than the slowest legitimate socket read
(127s) and shorter than the fastest broker redelivery. Those are satisfiable together only because
`LockDuration` was raised to its maximum. A **heartbeat-renewed lease** removes the conflict — the
lease stops meaning "a work budget" and starts meaning "how long since this runner last proved it was
alive", which decouples it from the deadline entirely.

**Nothing below is in effect.** These are the values this design *would* take; what ships today is
`Timing values` above, and the two blocks are deliberately the same shape so they can be read
side by side. Every comment here reads "today → proposed".

```yaml
# PROPOSED — NOT the running configuration. See "Timing values" above for what ships.
informantregister:
  servicebus:
    lock-duration: 1m                 # 5m today; back to 1m, so recovery is ~60s not ~5m
    max-auto-lock-renew-duration: 5m  # 5m today; untouched, still >= deadline + 30s
  claim:
    lease: 30s                        # PT4M30S today; no longer sized to the slowest run
    heartbeat-interval: 10s           # new key, does not exist today
    processing-deadline: 4m           # 4m today; untouched, now independent of the lease
```

| Rule | Check |
|------|-------|
| Rule 1 (`processing-deadline` < `lease`) | **removed** — no longer meaningful |
| `lease` + `heartbeat-interval` < `lock-duration` | 40s < 60s |
| `lease` >= 3 x `heartbeat-interval` | 30s >= 30s — tolerates two consecutive missed renewals |
| `deadline` + `lease` < `max-auto-lock-renew-duration` + `lock-duration` | 4m30s < 6m |

Rules 2, 4 and 5 carry over unchanged. Rule 3 is **superseded**, not dropped: `lease` +
`heartbeat-interval` < `lock-duration` is the same guarantee — the claim lapses before the broker
redelivers — restated for a lease that is renewed rather than sized to the work. Crash recovery
drops from ~5m to ~60s.

Design notes for whoever picks this up:

- **Renewal must be asynchronous to the run.** Renewing inline at the pipeline's checkpoints cannot
  work: one payload fetch can legitimately spend 127s, far longer than any lease short enough to be
  useful, so a healthy run would lose its claim mid-socket.
- **Renewal is evidence, not a decision.** Nothing asks whether the pod is alive; the scheduled write
  *is* the proof, and its absence is the signal. Never consult Kubernetes for this — on AKS a
  `NotReady` node does not mean the process stopped, and a peer trusting the API would hand a live
  runner's work to a second runner.
- **Cap the renewal window at `run start + processing-deadline`.** A bare heartbeat proves the
  renewer thread is alive, not that the run is progressing; without the cap a wedged pipeline holds
  its claim indefinitely while the heartbeat cheerfully renews it.
- **Errored is not lost.** Only a renewal affecting zero rows proves the claim is gone; an exception
  means unknown, so keep working and retry the next tick. Conflating them makes every database hiccup
  abandon every in-flight run.
- The renewal statement needs `claim_expires_at >= now()` alongside the existing owner-and-token
  predicate, so a zombie runner cannot resurrect a claim that has already lapsed.
- The window narrows but never closes: a runner paused between its last liveness check and its POST
  can still emit a duplicate. That residual is the accepted at-least-once case the 19:00 sweep
  absorbs, and no comment should promise otherwise.

### Considered and rejected

- **Short lease with the timeout tree cut to fit** (lease under ~55s, deadline under 55s). Requires
  `read-timeout` down from 30s to ~10s on Results and reference data, turning slow-downstream periods
  into dead-lettered hearings. Trades a rare failure for a common one.
- **Raising `maxDeliveryCount`.** Achieves nothing, for the reason given above.
- **Lowering the lease alone** (5m to 4m30s, `LockDuration` left at 1m). Indistinguishable from doing
  nothing: the budget burns in under a second either way.

## Porting map (JS → Java)

All Node sources under `cpp-context-azure-legalaidagency/azure-functions/durable-functions/`.

| # | Function-app activity | Service component | Notes |
|---|-----------------------|-------------------|-------|
| 1 | `HearingResultedCacheQuery` | `adapter/payload` | Same `INT_{hearingId}_{hearingDay}_result_` key (both key forms), same `hearingDetails/internal` REST fallback; **verified TLS** (fixes `rejectUnauthorized:false`) |
| 2 | `SetInformantRegister` | `pipeline/RegisterBuilder` | One fragment per prosecuting authority; court-extract filtering; first-occurrence-wins identifier dedupe; **group proceedings are NOT skipped** — do not "fix" this |
| 3 | `InformantRegisterSubscriptions` | `pipeline/SubscriptionMatcher` + `adapter/refdata` | The matching is pure and the fetch is a port. `now-subscriptions?on={registerDate}` with the vendor `Accept` type and `CJSCPPUID`; the `on` day honours the register date's misleading `Z` (D9), the retry rule is the legacy `AxiosRetryWrapper`'s, and a failure to obtain the body is reported rather than answered as `null` (deviation 14). Port the 2-arg vocabulary call exactly (major-creditor lists always empty today — parity, not a bug fix) |
| 4 | `OutboundInformantRegister` mappers | `pipeline/AggregationMapper` | Per-authority document incl. recipients, filename, verdict mapping |
| 5 | `ProcessOutboundInformantRegister` | `adapter/results` | POST per authority — **now with retry on connect/IO/5xx/429 and DLQ on exhaustion** (today errors are swallowed) |
| — | `NowsHelper/service/DateService.js` (a helper, not an activity — rows 2, 3 and 4 all read dates through it) | `pipeline/HearingDates` | Reproduces `moment` 2.30.1 as vendored, not as documented. **Two parse paths, because the legacy uses two formats**: `parse`'s `YYYY/MM/DD` behind `orderingKey`, and `formatDate`'s hard-coded `DD/MM/YYYY` behind `formattedLocalDateTime` — which is why an ISO duration date ships as the literal `"Invalid dateZ"` (D11) and why `registerDate` / `hearingDate` carry a literal `Z` whatever the offset was (D9). Rules that belong to moment's **tokeniser** rather than to either format string apply to **both** paths — the two-digit-year pivot (`parseTwoDigitYear`, exactly two digits, 68 forward / 69 back) is the one that exists, and it was implemented on one path only until `PORT_AUDIT.md` caught it |

Technique: **JsonNode-canonical inbound, typed outbound**. One Java class per JS activity so the
Jest → JUnit mapping stays 1:1.

## Quality gate — behaviour parity by golden files

The JS has ~73 Jest tests plus JSON fixtures. Copy the fixtures byte-identical; write a JUnit twin
for every Jest case; run recorded real hearing payloads through old and new and fail on any
unexplained difference. The recorded set must include multi-authority hearings, court applications,
group proceedings, re-shares and legal-entity defendants. Any deliberate difference is a named entry
in `doc/DEVIATIONS.md` with its own assertion — the harness fails on any *unregistered* divergence.
The build is done when every twin passes and the recorded set matches.

**What the gate does not cover, and what covers it instead.** The gate is bounded by the corpus: a
behaviour no fixture exercises is not under test, however thorough the twins are. The two-digit-year
pivot is the worked example — the corpus carries no duration date written `26/02/19`, so every twin
passed while one of the two date paths resolved it to the year 19 AD and shipped it. Behaviour
reproduced from the **vendored library's rules** rather than from a recorded value therefore needs
its own pinning, and the expectations must be taken by *running the vendored copy* — moment 2.30.1
under `azure-functions/durable-functions/node_modules/` — never from the library's documentation,
which describes a version this function app may not be on. Where a rule has more than one
implementation site, each site needs its own case: one passing site proves nothing about the other.

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
| `informantregister.servicebus.lock-duration` | 5m | Broker lock duration (mirrors the queue setting). Validated against `claim.lease` — see Claim and lock timing |
| `informantregister.servicebus.max-auto-lock-renew-duration` | 5m | How long the processor keeps renewing the lock. Must be at least `claim.processing-deadline` + 30s |
| `informantregister.claim.lease` | `PT4M30S` | Claim expiry, written as `now() + lease` by the database. ISO-8601 — Spring's simple format cannot express a compound duration |
| `informantregister.claim.processing-deadline` | 4m | Enforced bound on a run, strictly shorter than the lease so a slow runner stops before its claim can be reclaimed |
| `informantregister.payload.redis.*` | — | Redis host/port/TLS (later story) |
| `informantregister.results.base-url` | — | Results command API base; no default, the local value in `application.yaml` is the command API's own declared `baseUri` |
| `informantregister.results.system-user-id` | — | `CJSCPPUID` identity; a secret, from Key Vault. **Required**: the gateway refuses to be built without one, so a deployment missing it fails to start rather than having every command refused |
| `informantregister.results.headers.*` | — | Any further header the mesh requires; configuration because the authorisation scheme is undocumented |
| `informantregister.results.max-attempts` / `initial-backoff` / `max-backoff` | 4 / 500ms / 20s | POST retry policy; `max-backoff` also caps a server-supplied `Retry-After` |
| `informantregister.results.connect-timeout` / `read-timeout` | 5s / 30s | Worst case must stay inside `claim.processing-deadline` — **not enforced at startup**, unlike the reference-data row below: no rule budgets the submission leg (see Invariants). A policy that cannot fit aborts a run instead of refusing a boot |
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

**Time is raced, never simulated, where the mechanism under test is time.** A fixture that writes
`claim_expires_at` into the past proves the reclaim predicate and hides everything upstream of it —
whether the configured lease reaches the column at all, and whether a claim lapses without anyone's
help. Suites about the lapse configure a short real lease and wait for the database's own clock
(`persistence/CrashWindowIT`); the fixture remains legitimate in suites that are about something
else and merely need an expired claim to exist.

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
