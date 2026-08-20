# Tasks: Informant Register Service — Initial POC (walking skeleton)

**Input**: Design documents from `/specs/CRA-220-informant-register-initial-poc/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs — interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` — so that the recorded red run is a **failing
assertion**, never a missing class or a compile error. The failing assertion is quoted in the test
task's commit narrative; the paired implementation task's narrative quotes the green run.

**Phase 1 setup tasks are infrastructure, not TDD pairs**: T001–T003 create build configuration,
a test profile and shared test fixtures. They have no assertable behaviour of their own, so the
red-run convention does not apply to them (constitution Principle II's mechanical/infrastructure
exemption); their commits record verification evidence (a passing suite, a started container)
instead of a red assertion.

**[A] Acceptance/characterisation tasks**: a small number of tests verify already-fixed behaviour
(broker configuration, assembled end-to-end behaviour, the container). No implementation task
follows them, they may legitimately pass on introduction, and **no red run is required** — the
task records the initial observed result instead. These are marked **[A]**.

**Conventions**: package root `uk.gov.hmcts.cp.informantregister`; production code under
`src/main/java/uk/gov/hmcts/cp/informantregister/`, tests under
`src/test/java/uk/gov/hmcts/cp/informantregister/`. `*IT` suites need Docker and run inside
`./gradlew test`. Conventional Commits; no AI attribution.

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency)
- **[A]**: acceptance/characterisation — see above
- **[US#]**: the spec user story the task traces to

---

## Phase 1: Setup

- [ ] T001 Add the plan's dependency table to `build.gradle` (starter-jdbc, starter-flyway,
      flyway-database-postgresql, postgresql, azure-messaging-servicebus, azure-identity,
      micrometer-registry-prometheus; test: testcontainers postgresql/junit-jupiter/azure,
      networknt json-schema-validator as testImplementation only). Run `./gradlew dependencies`
      and record **three facts** in the commit narrative: the Jackson generation the BOM
      resolves, the exact `JsonNode` class that generation provides, and the exact name of its
      big-decimal-for-floats deserialisation feature — this closes the plan's Principle-IV
      pending verification. If the generation is not Jackson 3 (`tools.jackson`), STOP and flag
      before proceeding. Verify `./gradlew compileJava` passes.
- [ ] T002 [P] Create the `test` profile (`src/test/resources/application-test.yaml`):
      `informantregister.consumer.enabled=false`, datasource auto-configuration excluded,
      readiness group include overridden to `ping` (plan §Configuration). Verify the existing
      `ActuatorIntegrationTest` still passes with no Docker running.
- [ ] T003 [P] Shared Testcontainers fixtures in `support/`: `PostgresTestSupport` (postgres:16,
      reused across PG suites, with the pause/unpause helper for store-outage suites, and an
      **apply-Flyway helper** — persistence slice suites migrate their container themselves,
      since production migration is deferred off the context-refresh path per research §7) and
      `ServiceBusEmulatorTestSupport` (`ServiceBusEmulatorContainer`, mounting
      `docker/servicebus-emulator/config.json` so compose and tests share one queue definition).

**Checkpoint**: build compiles with all dependencies; template tests green; fixtures ready.

---

## Phase 2: Foundational (blocking — no later phase starts before this completes)

- [ ] T004 [P] [US1] Write `persistence/SchemaMigrationIT` (red) — applies Flyway to the
      Testcontainers Postgres and asserts every `data-model.md` V1 fact, enumerated: both
      tables; every column's type and nullability; the composite primary keys; the four
      `processed_request` CHECKs (status enum, attempts ≥ 0, claim-triple all-or-nothing,
      FAILED ⇒ exhausted_message_id); `attempts DEFAULT 0`; `created_at`/`updated_at`
      `DEFAULT now()` on both tables; the composite PK `(source, request_id)` on
      `processed_request` and the single-column PK `output_id` on `processed_output`;
      **no database default on `output_id`** (application-generated); `processed_output` status
      CHECK (PENDING/POSTED/FAILED), unique `(source, request_id, prosecution_authority_id)`,
      FK with `ON DELETE RESTRICT`; the `(hearing_id, hearing_day)` index.
- [ ] T005 [US1] Write `src/main/resources/db/migration/V1__create_processed_log.sql` exactly per
      `data-model.md` (green for T004).
- [ ] T006 [P] [US1] Write `domain/DistributionCommandParserTest` and
      `domain/DistributionCommandSchemaCorpusTest` (red) — the full boundary corpus from
      research §9, every case run through BOTH the production parser and the test-only draft-07
      validator (format assertion on) with agreement asserted; required fields/enums/closedness
      asserted mechanically from the schema document. Include the **ObjectMapper numeric
      assertion**: a JSON number parsed through the shared mapper materialises as a
      BigDecimal-backed node (the feature name recorded by T001), pinning exact monetary
      round-trip. Compile-safe seams: `DistributionCommand` record signature +
      `DistributionCommandParser` skeleton + mapper config skeleton.
- [ ] T007 [US1] Implement `domain/DistributionCommand`, `inbound/DistributionCommandParser`
      (explicit checks, unknown-field rejection, bounded reason codes per failure class) and the
      shared ObjectMapper configuration in `config/JacksonConfig` (green for T006).
- [ ] T008 [P] [US1] Write `domain/RequestFingerprintTest` (red; seam: `RequestFingerprint`
      skeleton) — canonicalisation cases from `data-model.md`: uppercase-hex UUID,
      offset-vs-`Z` instants, fractional seconds; a changed immutable field changes the hash.
- [ ] T009 [US1] Implement `domain/RequestFingerprint` (green for T008).
- [ ] T010 [P] [US4] Write `config/ConfigurationValidationTest` (red; seam: the properties
      classes of T011 as signatures) — asserts **every `informantregister.*` property and
      default in the plan's Configuration table** binds as specified; startup fails fast when
      `processing-deadline >= lease` or `max-auto-lock-renew-duration < processing-deadline + 30s`;
      credential selection: exactly one of connection-string / namespace (both, or neither, =
      startup failure with a clear message). The plan table's Spring-level rows (datasource,
      Flyway, server, management) are NOT asserted here — they land with `application.yaml` in
      T028 and are proven by T028's green context boot plus T036's `HttpSurfaceTest`/
      `ReadinessPolicyIT` assertions.
- [ ] T011 [US4] Implement `config/InformantRegisterProperties` (nested `Servicebus`, `Claim`,
      `Store`, `Stub` records) and `config/PropertiesValidator` (startup validation) (green for
      T010).
- [ ] T012 [P] [US4] Write `config/ProcessingMetricsTest` (red; seam: `ProcessingMetrics`
      skeleton) — one case per instrument in research §11's table: names, types, label sets,
      and each counter's increment condition (including `processing_failures_total` on a
      RETRYING transition and `intake_suspensions_total` on suspension).
- [ ] T013 [US4] Implement `config/ProcessingMetrics` (green for T012).
- [ ] T014 [A] [US4] Container smoke harness (per the plan's CI note this belongs with the
      foundational container work): add the **container smoke** step to
      `.github/workflows/ci-build-publish.yml` — build the image from the existing `Dockerfile`,
      run it against the compose dependencies, poll `/actuator/health/readiness` until ready or
      the 60 s budget expires (SC-004 first half), tear down — and a local equivalent script
      `scripts/container-smoke.sh`. Record the initial observed result (the template app should
      already pass). Re-verified at T047 with the finished skeleton.

**Checkpoint**: schema, contract parsing, fingerprint, configuration, instruments and the
container harness all proven.

---

## Phase 3: The idempotency guard — complete, test-first

The guard is one cohesive unit whose branches interlock (one SQL statement set, one repository),
so — per the review decision at this gate — **all** of its tests are written red first and the
complete guard is implemented once. The tasks trace to the stories their tests serve. All test
tasks share one compile-safe seam (created in T015): `application/IdempotencyGuard` +
`persistence/ProcessedRequestRepository` signatures and the guard-decision domain types.

### Tests first ⚠️ (all [P] with each other once T015's seam exists)

- [ ] T015 [US1] Write `persistence/IdempotencyGuardIT` (red) — **one case per
      `data-model.md` transition-table row**: insert-new (RECEIVED, claim triple set,
      `attempts` = 1, fingerprint stored); RECEIVED/RETRYING → COMPLETED (claim cleared,
      `completion_reason = 'no-authorities'` on the empty-output path); RECEIVED/RETRYING →
      RETRYING (failure recorded, claim cleared); RECEIVED/RETRYING → FAILED
      (`exhausted_message_id` in the same transaction); FAILED → RECEIVED (fresh identity);
      FAILED same-identity (no transition, re-dead-letter decision); COMPLETED delivery →
      skip with row untouched; RETRYING redelivered after a long gap → runs under the
      single-runner rule. Plus `persistence/ProcessedLogDurabilityIT` (row survives container
      restart — spec US1-3).
- [ ] T016 [P] [US2] Write `persistence/ClaimContentionIT` (red) — N concurrent deliveries of
      one request → exactly one claim winner; competitor decision = abandon, never acknowledge
      (SC-002).
- [ ] T017 [P] [US2] Write `persistence/ClaimReclamationIT` and `persistence/CrashWindowIT`
      (red) — expired/absent claim on a non-terminal row reclaimed atomically by exactly one of
      several racing deliveries (crash-after-RECEIVED edge); outcome write suppressed after a
      run (crash window) → redelivery causes one further **sequential** run, never concurrent.
- [ ] T018 [P] [US2] Write `persistence/StaleRunnerRejectionIT` (red) — a runner whose claim was
      reclaimed gets zero rows from its owner+token-predicated outcome write; result discarded,
      WARN + `stale_runner_rejections_total`, delivery-abandon decision (FR-016 half).
- [ ] T019 [P] [US2] Write `persistence/IdempotencyCollisionIT` (red) — same
      `(source, requestId)`, different immutable field → dead-letter decision with bounded
      collision reason; original row byte-for-byte untouched; no run.
- [ ] T020 [P] [US3] Write `persistence/FailedReplayIT` (red) — fresh messageId → FAILED→RECEIVED
      (attempts preserved and incremented → 6 after 5 failures; `failure_reason` and
      `exhausted_message_id` cleared; `audit_note` written) and the run proceeds; SAME
      messageId → stays FAILED, no run, re-dead-letter decision; zero-row race on the replay
      update → abandon (data-model §Guard operations 5).

### Implementation

- [ ] T021 [US1] Implement the **complete** guard: `application/IdempotencyGuard`,
      `persistence/ProcessedRequestRepository` (JdbcClient; the exact statements from
      `data-model.md` §Guard operations 1–6, including the no-spin rule and the owner+token
      outcome predicates) and the guard-decision domain types — green for T015–T020 together.

**Checkpoint**: every state-machine branch proven at the persistence layer.

---

## Phase 4: User Story 1 — received, processed and settled reliably (P1) 🎯 MVP

**Goal**: valid message → validated → recorded → one stub-pipeline run → outcome recorded →
acknowledged (spec US1; FR-001/002/004/005/010).
**Independent Test**: spec US1 — one valid message in, COMPLETED `no-authorities` row out, queue empty.

### Tests first ⚠️

- [ ] T022 [P] [US1] Write `application/DistributionPipelineTest` (red, pure unit; seams: the
      two port interfaces with the plan's exact signatures, `AuthoritySubmission` record,
      `PayloadUnavailableException`/`SubmissionFailedException` with transient/non-transient
      classification, `DistributionPipeline` skeleton) — guard consulted; payload stub invoked
      once; the pipeline itself produces an empty authority set (no transformation port);
      submission port NEVER invoked; outcome COMPLETED `no-authorities`; with
      `stub.payload-failure-mode=TRANSIENT` the run raises the transient classification (the
      FR-009 simulated failure; no message field, no endpoint); **a run exceeding the
      configured processing deadline aborts as a transient failure before the lease can lapse**
      (data-model invariant 8; use a controllable clock/blocking stub, not sleeps); the
      exception classification and `AuthoritySubmission` shape are exercised by explicit cases,
      not just referenced.
- [ ] T023 [P] [US1] Write `inbound/MessageListenerSettlementTest` (red, mocked
      `ServiceBusReceivedMessageContext`; seam: listener skeleton) — exactly one settlement per
      delivery on every handler-controlled path; `complete()` only after the outcome write
      returned durably.
- [ ] T024 [US1] (depends on T023 — it drives the listener seam T023 creates; deliberately NOT
      [P]) Write `e2e/QueueSettlementIT` (red) — broker-level proof over the emulator:
      a completed delivery leaves the queue (no redelivery); an abandoned delivery comes back
      with an incremented delivery count; a dead-lettered delivery lands on the DLQ with reason
      and description (FR-001's matrix pair to T023).

### Implementation

- [ ] T025 [US1] Implement the ports, `application/DistributionPipeline` (deadline bound
      included), the outcome/classification domain types, and `adapter/stub/StubHearingPayloadSource`
      + `adapter/stub/StubRegisterSubmissionClient` (loud logging no-ops; failure mode read from
      `InformantRegisterProperties.Stub`) — green for T022.
- [ ] T026 [US1] Implement `inbound/InformantRegisterMessageListener` +
      `inbound/ServiceBusConsumerConfig` — **every consumer setting read from the typed
      properties** (queue name, `maxConcurrentCalls`, `maxAutoLockRenewDuration`, credential
      selection), peek-lock, auto-complete off, MDC correlation fields — green for T023 + T024.
- [ ] T027 [US1] Write `e2e/WalkingSkeletonIT` (red — full-context wiring does not exist yet):
      send valid message → COMPLETED `no-authorities` row → payload stub logged, submission
      stub NOT invoked → queue empty, DLQ empty; second send under a fresh messageId → no
      second run (SC-006).
- [ ] T028 [US1] Complete `src/main/resources/application.yaml` (every property/default from the
      plan's Configuration table) and the remaining Spring wiring the red run of T027
      identifies (bean registration, profile guards) — green for T027; quickstart's demo
      command now works.

**Checkpoint**: MVP demonstrable end-to-end.

---

## Phase 5: User Story 3 — failing requests retried, parked visibly, resubmittable (P2)

**Goal**: transient failures retry; the fifth failed delivery parks with `exhausted_message_id`; a
fresh-identity resubmission replays to success; invalid messages dead-letter immediately;
settlement edges behave (spec US3; FR-003/007/009/016).
**Independent Test**: spec US3 — a failing request parks after 5 deliveries and reprocesses after
one resubmission.

### Tests first ⚠️

- [ ] T029 [P] [US3] Write `e2e/DeliveryExhaustionIT` (red) — stub failure mode TRANSIENT: each
      failed delivery records RETRYING + reason + attempt; the fifth delivery's failure records
      FAILED + `exhausted_message_id` and dead-letters after exactly 5 broker deliveries; then
      **resubmit under a fresh message identity with the stub restored to success → the replay
      runs, settles, and the row ends COMPLETED with `attempts` = 6** (SC-003 in full).
- [ ] T030 [P] [US3] Write `e2e/ContractValidationDeadLetterIT` (red) — malformed body, missing
      field, unknown field, bad enum: immediate dead-letter with bounded reason, NO
      `processed_request` row, no attempt consumed, `deadlettered_total{reason=validation}`
      incremented.
- [ ] T031 [P] [US3] Write `inbound/SettlementFailureEdgeTest` (unit, red) — settlement call
      itself fails after COMPLETED (record stays COMPLETED; the later redelivery is acknowledged
      without work); dead-letter fails after FAILED (same-identity path re-attempts
      dead-letter); lock loss = no settlement attempted, ERROR + `lock_loss_total`, recovery
      left to broker redelivery.

### Implementation (serialised — all touch the listener)

- [ ] T032 [US3] Implement final-delivery detection (broker delivery count vs configured
      `max-delivery-count`), the FAILED park + dead-letter settlement, and the replay admission
      path in `inbound/InformantRegisterMessageListener` — green for T029.
- [ ] T033 [US3] Implement the contract-validation dead-letter path in the listener (before the
      state machine, bounded reasons from the parser) — green for T030.
- [ ] T034 [US3] Implement the settlement-failure and lock-loss handling in the listener (and a
      `domain/SettlementOutcome` type if the red run shows one is needed) — green for T031.
- [ ] T035 [A] [US3] Write `e2e/DuplicateDetectionIT` — republish with the SAME messageId inside
      the duplicate-detection window → the broker discards it; the service never sees a second
      delivery. Characterises committed broker configuration; record the initial result.

**Checkpoint**: failure is loud, bounded, parked and recoverable.

---

## Phase 6: User Story 4 — observable and deployable (P2)

**Goal**: readiness policy, queue health, intake suspension, telemetry discipline (spec US4;
FR-011/012/015/017; US2's remaining e2e evidence).
**Independent Test**: spec US4 — queue outage never breaks readiness; store outage suspends intake.

### Tests first ⚠️

- [ ] T036 [P] [US4] Write `e2e/ReadinessPolicyIT` and `config/HttpSurfaceTest` (red) —
      readiness contains `db` and not `servicebus`; store down → readiness DOWN; queue down →
      readiness UP with the `servicebus` component DOWN; **staleness rule: an unresolved
      connection error older than `health-staleness` with no traffic reports UP** (research §8);
      exposure list is exactly `health,info,metrics,prometheus` (FR-014).
- [ ] T037 [P] [US4] Write `e2e/StoreOutageIT` and `e2e/ProlongedStoreOutageIT` (red) — pause
      Postgres: in-hand delivery abandoned (never completed/dead-lettered), intake suspends
      (gauge 1, `intake_suspensions_total` incremented); invalid message during the outage is
      not examined until resume, then dead-letters; **context started while the store is
      already down: context refresh completes (research §7's resilient startup — lazy pool
      initialisation + deferred migration), readiness DOWN, and no consumption until the first
      successful store probe, which also runs the deferred migration**; prolonged outage (> 5
      nominal retry intervals) → message never reaches the DLQ, processes on resume; **the
      `intake_suspended` gauge returns to `0` on resume** (not only `1` on suspension).
- [ ] T038 [P] [US4] Write `e2e/QueueOutageRecoveryIT` and `e2e/StartupWithQueueDownIT` (red) —
      stop the emulator: readiness stays UP, `servicebus` DOWN; restart: consumption resumes
      within 60 s (SC-004); start with the queue already down: becomes ready, reports DOWN,
      consumes once the queue appears (spec US4-3); **the `informantregister_servicebus_up`
      gauge mirrors the health component as `1`/`0` across the outage and the recovery**.
- [ ] T039 [P] [US4] Write `config/TelemetryPrivacyTest`, `e2e/TraceabilityIT` and
      `e2e/FailureSignalIT` (red) — captured appender: **every** processing log line carries
      `requestId`/`hearingId`/`hearingDay`; no defendant-identifying fields at INFO+; **no
      whole message/payload dump at ANY level under the deployed (non-local) configuration; no
      secret (connection string, token) at any level**; a request is traceable
      receipt→settlement from logs alone (SC-005); every failure path emits exactly one
      sanitised ERROR and its instrument increments (FR-017).

### Implementation (serialised where files overlap)

- [ ] T040 [US4] Implement the health groups, `config/ServiceBusHealthIndicator` (processError +
      last-receive signal, staleness rule) and the `AmqpRetryOptions` from research §8 in
      `inbound/ServiceBusConsumerConfig` — green for T036 and T038's recovery/startup cases.
- [ ] T041 [US4] Implement `inbound/ConsumerLifecycleController` (RUNNING↔SUSPENDED, store-probe
      gated start, stop off the callback thread, serialised idempotent transitions,
      probe-driven resume), the **listener→controller suspension integration** (the callback's
      store-unavailability signal abandons the delivery and requests suspension), and the
      **deferred-migration mechanism** from research §7 (a no-op `FlywayMigrationStrategy` bean
      in `config/`, plus the controller invoking `flyway.migrate()` on the first successful
      store probe, before the processor starts). The Hikari
      `initialization-fail-timeout: -1` property is already in `application.yaml` from T028 —
      this task adds **code only**, no configuration file changes — green for T037.
- [ ] T042 [US4] Close the exact telemetry gaps T039's red runs identify — expected files:
      `inbound/InformantRegisterMessageListener` (MDC scope), `application/DistributionPipeline`
      (log lines), `config/ProcessingMetrics` (increment call sites), `logback.xml` only if a
      provider is missing — green for T039.
- [ ] T043 [A] [US1/2/3] Write `e2e/MessageAccountingIT` — mixed batch (valid, invalid, failing,
      duplicate, in-flight): every message lands in exactly one of SC-001's outcomes.
      Acceptance over assembled behaviour; record the initial result; any failure here is a
      defect that spawns its own red-first fix pair.

**Checkpoint**: all four stories hold; the skeleton is observable and deployable.

---

## Phase 7: Polish & verification

- [ ] T044 Run the full quickstart sequence exactly as written (`quickstart.md`).
      **Validation-only**: any drift or failure found spawns a new red-first fix pair (or a
      quickstart wording fix if the document is what is wrong); this task changes no production
      behaviour itself (SC-006).
- [ ] T045 [P] `./gradlew pmdMain` clean (suppressions inline, narrow, with reasons);
      `./gradlew jacocoTestReport` generated and sanity-checked.
- [ ] T046 [P] Documentation sync pass — **implementation documentation only**: README
      quickstart block, `doc/CHANGELOG.md`, `doc/DEVIATIONS.md` for anything touching parity.
      The committed spec/plan/data-model are fixed gate inputs and are NOT modified; a genuine
      design change discovered during implementation stops work and goes back through the gate.
- [ ] T047 Final gates: `./gradlew build` green from clean; container smoke (T014 harness)
      re-run against the finished skeleton; every test-matrix row's named test exists and
      passes; spec checklist re-verified; handover list written into the completion report of
      what CRA-220 still needs outside this repo (queue provisioning, Flux/ADO wiring, GitHub
      remote, alert-wiring ticket).

---

## Dependencies & execution order

- **Phases are sequential barriers**: 1 → 2 → 3 → 4 → 5 → 6 → 7. (Phase 5 and Phase 6 test
  tasks may be written in parallel once Phase 4 completes, but see the serialisation rule.)
- **Within each phase**: test tasks strictly before the implementation tasks they guard; T015's
  seam before the other Phase 3 test tasks.
- **Serialisation rule for shared files**: only **test tasks** are ever [P]. Every
  implementation task that touches the shared production files (`IdempotencyGuard`,
  `ProcessedRequestRepository`, `DistributionPipeline`, `InformantRegisterMessageListener`,
  `ServiceBusConsumerConfig`) executes **strictly in task-ID order with a single owner** —
  T021 → T025 → T026 → T028 → T032 → T033 → T034 → T040 → T041 → T042. No two of these run
  concurrently.
- Reviewer agents (`code-reviewer`, `qa`, `spec-validator`) run per batch and MUST return
  PASS/COMPLIANT before the batch's commits are considered done (constitution workflow).

## Notes

- Store-outage suites use T003's pause/unpause helper; Toxiproxy only as the recorded fallback
  (plan §Test matrix).
- Plain unit tests must not require Docker; `*IT` suites run inside `./gradlew test`.
- A guard test that unexpectedly passes on first run indicates its behaviour arrived with T021's
  complete implementation — that is expected for Phase 4+ tests exercising guard branches, and
  the narrative says so; it is NOT claimed as a red run.
