# Research — CRA-220 walking skeleton

Phase 0 output. Every decision below is settled; there are no open `NEEDS CLARIFICATION` items.
Decisions 1–4 were fixed during the design/options work (18–20 Aug 2026, recorded on the live
Confluence dev page and in `doc/TECHNICAL_DESIGN.md`); 5–12 are planning-level choices made here.

## 1. Trigger transport: dedicated Azure Service Bus queue

- **Decision**: dedicated queue `informantregister.requests` + DLQ, owned by this service.
- **Rationale**: peek-lock retries, dead-lettering and back-pressure — none of which the current
  Event Grid push trigger offers; a dedicated queue (not a shared topic) for ownership, control and
  awareness, agreed with the Results team lead.
- **Alternatives considered**: Event Grid subscription (status quo — retries opaque, no consumer
  back-pressure, failure invisible); Azure Storage Queue (no broker duplicate detection, no
  first-class DLQ, polling consumer); shared ASB topic with per-service subscriptions (rejected for
  ownership/awareness reasons); direct synchronous POST from Results (loses retry decoupling and
  risks rolling back the Results transaction).

## 2. Consumer client: raw `azure-messaging-servicebus` `ServiceBusProcessorClient`

- **Decision**: the raw Azure SDK processor client, explicit `complete()`/`abandon()`/`deadLetter()`,
  auto-complete disabled, `maxConcurrentCalls` 2.
- **Rationale**: the constitution names it; settlement discipline is the core of this feature and
  the raw client makes every settlement call explicit and testable; the vendored
  `azure-sdk-guide.md` precedence note selects it over the Spring starter for this service.
- **Alternatives considered**: Spring Cloud Azure Service Bus starter (hides settlement behind
  container abstractions — exactly what this spec must make explicit); JMS-over-AMQP
  (`spring-jms` + Qpid: loses native dead-letter reason/description fields and delivery-count
  fidelity).

## 3. Idempotency store: service-owned PostgreSQL processed-log

- **Decision**: Postgres tables `processed_request` (+ `processed_output` schema-only), Flyway V1.
- **Rationale**: durable across restarts (spec US1-3), supports the atomic claim and fingerprint
  rules, and doubles as the support answer to "was this hearing processed?".
- **Alternatives considered**: broker-only dedupe (duplicate-detection window is finite and
  identity-based — cannot express COMPLETED-vs-FAILED replay semantics); Redis (already in the
  estate but not durable enough to be the system of record for idempotency).

## 4. Message contract: closed six-field JSON with deterministic `requestId`

- **Decision**: `{source, requestId, hearingId, hearingDay, sharedTime, eventType}`, draft-07
  schema, `additionalProperties: false`; `messageId = source:requestId` for normal publishing;
  replays mint a fresh identity.
- **Rationale**: thin claim-check message (payload stays in Redis, as today); deterministic
  `requestId` from `hearingId | hearingDay | sharedTime` makes republishes dedupe-able while
  re-shares process legitimately. Jointly owned with `cpp-context-results`.
- **Alternatives considered**: fat message carrying the hearing payload (breaks the claim-check
  pattern, exceeds sensible message sizes); open schema (silently absorbs producer drift — the
  spec requires drift to surface loudly).

## 5. Persistence access: Spring JDBC (`JdbcClient`) with hand-written SQL, not JPA

- **Decision**: `org.springframework.boot:spring-boot-starter-jdbc` + `JdbcClient`; explicit SQL for
  the guard's conditional updates; Flyway for schema (Boot 4's modular
  `spring-boot-starter-flyway` plus `org.flywaydb:flyway-database-postgresql`).
- **Rationale**: the guard's correctness rests on single-statement atomic claims
  (`INSERT … ON CONFLICT DO NOTHING`; `UPDATE … WHERE status IN ('RECEIVED','RETRYING') AND
  (claim_expires_at IS NULL OR claim_expires_at < now())`; outcome writes `… WHERE claim_owner =
  :owner AND claim_token = :token`) whose affected-row counts ARE the decision points of the state
  machine. JPA's session/dirty-checking indirection obscures exactly the property under test. Two
  tables, no object graph — an ORM buys nothing here. The full statement set is written out in
  `data-model.md` "Guard operations".
- **Alternatives considered**: Spring Data JPA (entity lifecycle and flush timing hide the atomic
  compare-and-claim semantics); jOOQ (fine, but a new dependency and codegen step the estate
  template does not carry, for two tables).

## 6. Single-runner claim: owner + token + expiry, with an enforced processing deadline

- **Decision**: the claim is a triple — `claim_owner`, `claim_token`, `claim_expires_at` — taken or
  reclaimed by a single conditional `INSERT`/`UPDATE`; exactly one racing delivery wins.
  - Every acquisition **mints a fresh `claim_token`** (UUID) alongside owner and expiry.
  - An enforced **processing deadline** (`informantregister.claim.processing-deadline`, default
    4 minutes) is strictly shorter than the **claim lease** (`informantregister.claim.lease`,
    default 5 minutes). A runner that reaches the deadline aborts its own run and treats it as a
    transient failure (RETRYING, or abandon if the outcome write is itself the thing that hung), so
    it stops working **before** its lease can expire. A live-but-slow runner therefore cannot
    overlap a reclaimer — the overlap window is closed by construction, not by hoping the lease is
    long enough.
  - **Every outcome write and claim release is conditional on `claim_owner = :owner AND
    claim_token = :token`.** Zero rows affected means the claim was reclaimed in the meantime: the
    stale runner discards its result, logs at WARN, increments
    `informantregister_stale_runner_rejections_total`, and abandons the delivery. Broker redelivery
    re-enters the state machine — nothing is lost and nothing is written twice.
  - `maxAutoLockRenewDuration` on the processor client is set to at least **processing deadline +
    a fixed 30 s renewal margin** (both timing relationships validated at startup)
    (default 5 minutes, matching the lease) so the broker lock always outlives a legitimate run;
    the run is bounded by the deadline, not by the lock.
  - The **database clock (`now()`) is the single time source** for lease and expiry comparisons —
    expiry is written as `now() + :lease` and compared to `now()` in SQL. No JVM clock reading is
    ever compared against a stored timestamp, so clock skew between pods cannot grant two runners
    the same claim.
- **Rationale**: survives a crashed pod without operator action (the expiry is the recovery path —
  spec FR-008/edge cases); no session affinity; visible in the row for support. The token turns
  "the lease has probably not expired" into an enforced check: correctness no longer depends on a
  timing assumption about how long a run takes.
- **Alternatives considered**: expiry-only claims with a generous lease (the original shape —
  rejected: a slow run and a reclaimer can genuinely overlap, and nothing detects it); Postgres
  advisory locks (session-scoped — a crashed pod's lock vanishes, but so does the audit trail, and
  lock identity ↔ request mapping is implicit); `SELECT … FOR UPDATE` (holds a transaction open for
  the whole pipeline run — long transactions, and death of the connection releases the lock with no
  record it was ever held).
- **Test**: a stale runner that attempts to write its outcome after its claim has been reclaimed is
  rejected by the token predicate, writes nothing, and abandons (test matrix, plan.md).

Exact SQL for every guard statement is in `data-model.md` "Guard operations".

## 7. Store-outage intake suspension: an explicit consumer lifecycle controller

- **Decision**: a `ConsumerLifecycleController` owns an explicit two-state machine —
  **RUNNING ↔ SUSPENDED** — and is the only thing permitted to start or stop the
  `ServiceBusProcessorClient`.
  - **Start is gated on a successful store probe.** The application context starts regardless (so
    actuator is up and readiness can report honestly), but the processor is **not** started until
    the first store probe passes. Readiness stays down until then, consistent with
    store-gates-readiness (§8). A service that boots while Postgres is down therefore consumes
    nothing rather than abandoning a queue's worth of deliveries.
  - **Resilient startup makes that possible — deferred migration**: by default, connection-pool
    initialisation and Flyway both connect *during context refresh*, so with the store down the
    refresh would block or fail — retry settings only delay it. The skeleton therefore (a) sets
    Hikari `initialization-fail-timeout: -1` (lazy pool — no eager connection at refresh) and
    (b) registers a **no-op `FlywayMigrationStrategy`** so refresh completes without touching the
    database; the **controller itself invokes `flyway.migrate()` when the first store probe
    passes, before the processor is started** — migration success is part of the probe-gated
    start, so no message can be consumed against an unmigrated schema. Test fixtures that boot
    persistence slices without the controller apply Flyway to their Testcontainers database
    themselves.
  - **Suspension trigger**: store unavailability detected inside the message callback. The callback
    abandons its own delivery first, then *requests* suspension; `stop()` is invoked on a separate
    single-threaded executor, **never inside the SDK callback thread** (stopping a processor from
    its own callback deadlocks the shutdown). In-flight callbacks finish while the processor
    drains — each one hits the same store error and abandons.
  - **Resume**: a scheduled store probe (`informantregister.store.probe-interval`, default 10s)
    restarts the processor when the store answers. Stop and start requests are serialised through
    the controller's single-threaded executor with synchronised state transitions, so they are
    idempotent and cannot race — a second suspension request while SUSPENDED is a no-op, as is a
    resume while RUNNING.
  - **Single-replica constraint for CRA-220**: this increment deploys **one replica**. Suspension is
    a per-pod decision, so with multiple replicas a store outage could still burn deliveries on
    pods that have not yet noticed. Cluster-safe suspension (a shared suspension signal, or KEDA
    scaling the consumer to zero on store health) is deferred to the KEDA/scale-out story. The
    constraint is recorded in plan.md Technical Context and in `doc/TECHNICAL_DESIGN.md`
    "CRA-220 scope".
- **Rationale**: satisfies FR-015 — repeated abandon cycles would increment broker delivery counts
  and burn through `maxDeliveryCount` 5 (spec edge case); stopping intake is the only way to make
  a store outage non-destructive. Stop/start of the processor is a supported SDK operation, but it
  is only safe if exactly one component decides when it happens and it never happens on a callback
  thread — hence the controller rather than ad-hoc calls from the listener.
- **Alternatives considered**: keep consuming and abandon each delivery (burns the delivery
  budget — rejected by the spec); lock renewal while waiting (holds messages hostage to one pod
  and still risks lock loss); prefetch 0 + long back-off (still consumes deliveries); calling
  `stop()` directly from the listener (races with the resume probe and can deadlock on the callback
  thread).
- **Test**: a prolonged outage — the store down across more than five nominal retry intervals — must
  end with the message still on the queue, never on the DLQ, and processed normally once intake
  resumes (spec edge case "store unavailable across many retry intervals").

## 8. Readiness policy: store gates readiness, queue never does

- **Decision**: Spring Boot health groups — the readiness group contains the `db` indicator; a
  custom Service Bus connectivity indicator is registered OUTSIDE the readiness group and exported
  as its own health component and a gauge.
- **Queue-health signal, defined**: the indicator derives its state from two inputs the SDK already
  gives us —
  1. the processor's `processError` callback (records the last error, its class and its timestamp);
  2. the timestamp of the last successful receive or settlement.

  State is **DOWN** when the most recent signal is an unresolved connection-class error (AMQP
  connection/link failure, authentication failure, entity-not-found). It is **UP** when a receive or
  settlement has succeeded more recently than the last error. **Staleness rule**: an error older
  than `informantregister.servicebus.health-staleness` (default 60s) with no traffic since reports
  UP, not DOWN — an idle queue produces no traffic, and absence of traffic is not an outage. Only a
  fresh, unresolved connection-class error is an outage.
- **Reconnection**: the SDK's `AmqpRetryOptions` are configured explicitly — exponential mode,
  `maxRetries` 5, `delay` 500ms, `maxDelay` 10s, `tryTimeout` 30s — so that once the queue is
  restored the client reconnects well inside the 60-second budget in spec SC-004 rather than sitting
  on a long default back-off.
- **Rationale**: spec FR-011 (decided at review round 2): processing is unsafe without the store,
  so the pod should not receive traffic/consume until it is back; a broker blip must not roll the
  pods, and the queue state must still be observable.
- **Alternatives considered**: both in readiness (broker blip restarts pods — forbidden); neither
  (a store-less pod reports ready while unable to work safely); a polling "send a probe message"
  health check (pollutes the queue and costs a delivery per probe).
- **Test** (SC-004): stop the emulator, assert `/actuator/health/readiness` stays UP while the
  `servicebus` component reports DOWN; restart the emulator and assert consumption resumes within
  60 seconds with no restart.

## 9. Contract validation: JSON-schema assertion in tests, explicit parser in production

- **Decision**: production code parses/validates `DistributionCommand` with explicit checks
  (required fields, UUID/date/instant formats, enum values, unknown-field rejection via Jackson
  `FAIL_ON_UNKNOWN_PROPERTIES` on this one deserialisation); the committed schema is asserted
  against the parser by contract tests (valid/invalid corpus), keeping schema and code provably in
  step.
- **Rationale**: one validation implementation at runtime (fast, precise failure reasons for DLQ
  descriptions); the schema stays the contract source of truth via tests rather than a runtime
  schema-validator dependency.
- **Alternatives considered**: runtime JSON-schema validation library (a new dependency; generic
  error strings make poorer DLQ reasons; still needs the typed record afterwards).
- **Dual validation of the corpus**: "the tests keep them in step" only holds if the tests actually
  compare the two. **Every** corpus case — valid and invalid alike — is run through **both** the
  production parser **and** a test-only draft-07 validator with format assertion enabled, and the
  test asserts the two **agree on accept/reject** for every case. A case the schema accepts and the
  parser rejects (or vice versa) is a failure, not a curiosity.
  - The corpus deliberately includes the strict boundary cases where a hand-written parser and a
    schema most easily drift: `2026-02-30` and `2026-13-01` (well-formed but non-existent dates),
    `2025-02-29`, non-canonical UUIDs (uppercase, braced, 32-char unhyphenated), offset-bearing vs
    `Z` instants for the same moment, instants with and without fractional seconds, empty strings,
    nulls for required fields, and a single unknown extra field on an otherwise valid body.
  - Required-field presence, the `source`/`eventType` enum members and `additionalProperties:
    false` are additionally asserted **mechanically from the schema document** — the test reads the
    schema and checks the parser rejects a body missing each required field in turn and a body
    carrying an unknown field, so adding a field to the schema without teaching the parser fails
    the build.
  - The validator is `testImplementation` only. It never appears on the runtime classpath, so the
    production failure reasons stay precise and no new deployed dependency is introduced.
- **Documentation**: `doc/API_CONTRACTS.md` "JSON Schema" states the same division — the schema is
  normative, the tested parser is the runtime implementation of it.

## 10. Local + CI broker: Service Bus emulator (Testcontainers and docker-compose)

- **Decision**: `mcr.microsoft.com/azure-messaging/servicebus-emulator` **pinned to `1.1.2`** with
  the `mcr.microsoft.com/mssql/server:2022-latest` companion it needs for its own state, via
  Testcontainers for `*IT` suites and via `docker-compose.yml` for local runs; queue with
  `maxDeliveryCount` 5 and duplicate detection declared in `docker/servicebus-emulator/config.json`.
- **Pinning and companion, explicitly**: `latest` on a broker emulator is a silent-breakage risk —
  a tag move can change settlement or delivery-count behaviour under a green build — so the tag is
  pinned and bumped deliberately as its own change. The companion is
  `mcr.microsoft.com/mssql/server:2022-latest` per Microsoft's current emulator compose topology;
  the previously used `azure-sql-edge` image is retired and must not be reintroduced.
- **Two harnesses, one queue definition**: Testcontainers' `ServiceBusEmulatorContainer` manages its
  own companion container, so the compose file's companion service is for the interactive local
  stack only. Both harnesses mount the **same** `docker/servicebus-emulator/config.json`, so the
  queue's delivery limit and duplicate-detection settings cannot drift between the local run and CI.
- **Rationale**: faithful AMQP semantics for peek-lock settlement, delivery counts, DLQ and
  duplicate detection — the behaviours under test; free and local.
- **Known limits accepted** (recorded during design): no restart persistence, ≤1 h TTL, no Entra,
  connection/entity caps — irrelevant to test scenarios; STE/prod use real namespaces
  (provisioning out of scope).
- **Alternatives considered**: shared real nonlive namespace for CI (network + auth coupling in
  CI; cost); mocking the SDK for integration tests (would not exercise real settlement or
  delivery-count semantics — unit tests mock, ITs do not).

## 11. Observability: named Micrometer instruments + MDC-structured logs

- **Decision**: the instrument set below is fixed now, so the tests assert on names and labels
  rather than on "a counter went up", and the later alert rules are written against a stable
  surface. Exported through the template's existing actuator/OTEL stack (Prometheus endpoint).

| Instrument | Type | Labels | Incremented / set when |
|------------|------|--------|------------------------|
| `informantregister_processed_total` | counter | `outcome` = `completed`\|`failed` | a request reaches a terminal outcome |
| `informantregister_processing_failures_total` | counter | `classification` = `transient`\|`non-transient` | **every** failed pipeline run, including transient failures that end in RETRYING — not only terminal exhaustion (spec FR-017) |
| `informantregister_intake_suspensions_total` | counter | none | each transition into SUSPENDED — the failure-metric increment FR-017 requires for the store-outage path, alongside the gauge below |
| `informantregister_deadlettered_total` | counter | `reason` = `validation`\|`collision`\|`exhausted`\|`non-transient` | a delivery is dead-lettered, labelled by why |
| `informantregister_settlement_failures_total` | counter | `operation` = `complete`\|`abandon`\|`deadletter` | a settlement call itself fails (spec FR-016) |
| `informantregister_lock_loss_total` | counter | none | the delivery lock is lost before settlement |
| `informantregister_stale_runner_rejections_total` | counter | none | an outcome write is rejected by the owner+token predicate (§6) |
| `informantregister_intake_suspended` | gauge | none | `1` while the lifecycle controller is SUSPENDED, `0` while RUNNING (§7) |
| `informantregister_servicebus_up` | gauge | none | `1`/`0` mirroring the Service Bus health component (§8) |

- **Label discipline**: labels are low-cardinality enumerations only. A request id, hearing id,
  message id, authority id or exception message MUST NEVER be a label value — that is both a
  cardinality explosion and, for hearing data, a privacy breach (constitution Principle VII). The
  correlation identifiers live in the MDC-structured logs, which carry
  `requestId`/`hearingId`/`hearingDay`/`source`, and every failure path emits exactly one sanitised
  ERROR log carrying whichever of those the message yielded.
- **Failure reasons are bounded codes**: what lands in `failure_reason`, in a DLQ reason/description
  and in a log's reason field is a **stable enum reason code** (for example
  `CONTRACT_VALIDATION_FAILED`, `IDEMPOTENCY_COLLISION`, `PIPELINE_TRANSIENT_FAILURE`,
  `DELIVERY_LIMIT_EXHAUSTED`, `STORE_UNAVAILABLE`, `PROCESSING_DEADLINE_EXCEEDED`) plus a sanitised
  summary the service composes itself. **Never a raw exception message and never a fragment of the
  message body** — both are attacker-influenced or PII-bearing content, and both would leak into
  the DLQ and the log index. This is the same rule spec FR-012 and FR-017 state, made concrete.
- **DLQ depth is a platform metric, not service code.** Dead-letter depth in deployed environments
  is read from **Azure Monitor's native Service Bus metric `DeadletteredMessages`** on the queue.
  The service does not poll the DLQ to publish a depth gauge: polling costs a receiver connection,
  races with support tooling draining the DLQ, and would duplicate a number the platform already
  emits accurately. `informantregister_deadlettered_total` (this service's own count of
  dead-letters it performed, by reason) and the platform depth metric answer different questions and
  are both wanted. The deferred alert in waiver `doc/DEVIATIONS.md` #3 fires on the **platform**
  metric.
- **Rationale**: spec FR-017 and constitution Principle VI; metrics ship now, alert wiring is the
  registered waiver (`doc/DEVIATIONS.md` #3).
- **Alternatives considered**: a service-published DLQ-depth gauge (rejected above); free-form
  reason strings (unbounded label/field cardinality and a PII leak path); the template stack itself
  is uncontested — no alternative considered.

## 12. End-to-end demo shape (spec SC-006)

- **Decision**: `WalkingSkeletonIT` (Testcontainers: emulator + Postgres + full Spring context)
  is the demonstrable end-to-end sequence — send message → observe processed-log row + stub log
  lines + settlement. `quickstart.md` documents it plus the interactive `docker compose` +
  `bootRun` route for manual poking.
- **Rationale**: one command (`./gradlew test --tests '*WalkingSkeletonIT'`), no bespoke demo
  tooling to maintain; the interactive route reuses the committed compose file.
- **Alternatives considered**: bespoke message-sender CLI in the repo (more surface to maintain in
  an increment whose point is the skeleton; can still be added by the operability story for
  DLQ-replay tooling).
