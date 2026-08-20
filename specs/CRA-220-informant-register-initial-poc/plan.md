# Implementation Plan: Informant Register Service — Initial POC (walking skeleton)

**Branch**: `CRA-220-informant-register-initial-poc` | **Date**: 2026-08-20 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/CRA-220-informant-register-initial-poc/spec.md`

## Summary

Build the walking skeleton of the informant register service: an Azure Service Bus queue consumer
with explicit peek-lock settlement, a durable Postgres idempotency guard (`processed_request` with
fingerprint, single-runner claim and exhausted-message-identity columns; `processed_output` schema
only), port interfaces for payload fetch and register submission served by logging-stub adapters,
actuator health with the agreed readiness policy, failure ERROR logs and metrics, and a container
build — proving the delivery machinery end-to-end before any business logic is ported.

Technical approach: the ports-and-adapters pipeline from `doc/TECHNICAL_DESIGN.md` — an inbound
Service Bus adapter (raw `azure-messaging-servicebus` `ServiceBusProcessorClient`, explicit
settlement) delegating to an application-core `DistributionPipeline` that consults the idempotency
guard (Spring JDBC + Flyway, atomic conditional-update claim semantics with an owner/token/expiry
claim triple) and invokes stub ports. Every state-machine branch in the design is driven by a test
written first (Principle II); Testcontainers (Service Bus emulator + Postgres) provide the
integration harness.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (template `hmcts/service-hmcts-crime-springboot-template`)

**Primary Dependencies** (exact artefact ids; versionless wherever a BOM manages them — the Spring
Boot/template BOM for most, plus one vendor BOM for the Azure SDK, which Boot's BOM does not
manage; the two explicitly-pinned exceptions are marked):

| Artefact | Purpose |
|----------|---------|
| `org.springframework.boot:spring-boot-starter-jdbc` | `JdbcClient`, `DataSource`, transactions |
| `org.springframework.boot:spring-boot-starter-flyway` | Boot 4's modular Flyway starter (Flyway is no longer auto-configured by `flyway-core` alone) |
| `org.flywaydb:flyway-database-postgresql` | Flyway's Postgres dialect module, required from Flyway 10 |
| `org.postgresql:postgresql` | JDBC driver (runtime) |
| `platform('com.azure:azure-sdk-bom:1.3.8')` | Vendor BOM (Gradle-native platform import) — Boot 4.1's BOM does not manage `com.azure:*`; mechanism follows the `cpp-context-results` precedent, version = latest release at adoption (resolves servicebus 7.17.19, identity 1.18.4) |
| `com.azure:azure-messaging-servicebus` | `ServiceBusProcessorClient`, explicit settlement (version from the Azure BOM) |
| `com.azure:azure-identity` | `DefaultAzureCredential` — workload identity in deployed environments (version from the Azure BOM) |
| `io.micrometer:micrometer-registry-prometheus` | Prometheus scrape of the instruments in research §11 |
| `org.testcontainers:testcontainers-postgresql` (test) | Postgres container for the persistence `*IT` suites (Testcontainers 2.x module name, managed by the Boot BOM's `testcontainers-bom` 2.0.5) |
| `org.testcontainers:testcontainers-junit-jupiter` (test) | JUnit integration for Testcontainers (2.x name) |
| `org.testcontainers:testcontainers-azure` (test) | `ServiceBusEmulatorContainer` for the broker `*IT` suites (2.x name) |
| `com.microsoft.sqlserver:mssql-jdbc` (**testRuntimeOnly**) | Required by the emulator companion's `MSSQLServerContainer` readiness check (opens a JDBC connection); Boot-BOM version |
| `com.networknt:json-schema-validator:3.0.7` (**testImplementation only, explicitly pinned**) | Draft-07 validator for the dual-validation corpus (research §9); no BOM manages it; 3.x uses Jackson 3, matching the platform; never on the runtime classpath |
| Awaitility (test) | Async assertions — already transitively present via `spring-boot-starter-test` |

Existing template stack retained: web/actuator/OTEL, Logback + `logstash-logback-encoder`, Lombok.
The exact artefact coordinates above are **confirmed against the template's dependency graph
(`./gradlew dependencies`) at the first implementation task**; nothing is version-pinned in this
plan.

**Storage**: PostgreSQL 16 (service-owned processed-log; Flyway migration V1 creates
`processed_request` + `processed_output`)

**Testing**: JUnit Jupiter 6 (the Boot 4.1 test starter) + Mockito + AssertJ (unit);
Testcontainers — Service Bus emulator (pinned `1.1.2`, with the `mssql/server:2022-latest`
companion) and `postgres:16` (integration, `*IT` suffix, run under `./gradlew test`); Awaitility for
async assertions

**Target Platform**: AKS (container port 4550; local 8082); local dev via `docker-compose.yml`
(Postgres + Service Bus emulator, queue declared in `docker/servicebus-emulator/config.json`)

**Project Type**: Single Spring Boot service, no business HTTP surface (actuator only)

**Performance Goals**: modest — per resulted hearing (hundreds/day); `maxConcurrentCalls` 2
(parity with the Durable Functions throttle); ready within 60 s of start (spec SC-004)

**Constraints**:

- peek-lock with explicit settlement only (auto-complete forbidden)
- `maxDeliveryCount` 5 judged by broker delivery count; broker duplicate detection with
  `messageId = source:requestId`
- queue connectivity must never gate readiness, processed-log store must gate it; store outage
  suspends intake
- a pipeline run is bounded by an enforced processing deadline strictly shorter than the claim
  lease, and every outcome write is conditional on the claim token that acquired the claim
- **single replica for CRA-220** — intake suspension is a per-pod decision, so a store outage is
  only provably non-destructive with one consumer pod. Cluster-safe suspension arrives with the
  KEDA/scale-out story (research §7; `doc/TECHNICAL_DESIGN.md` "CRA-220 scope")
- no PII at INFO+; metric labels are low-cardinality enumerations only; no AI attribution

**Scale/Scope**: walking skeleton only — consumer, guard, stub ports, actuator, container, CI.
No transformation port, no Redis adapter, no Results POST adapter (later stories)

### Configuration (this increment)

Every property the skeleton needs, with its local default. Bound to typed
`@ConfigurationProperties`; `application.yaml` itself is written under TDD at implementation time,
not here.

| Property | Local default | Purpose |
|----------|---------------|---------|
| `server.port` | `8082` | HTTP port (4550 in Kubernetes) |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/informantregister` | Processed-log store — matches the `postgres` service in `docker-compose.yml` |
| `spring.datasource.username` | `informantregister` | as above |
| `spring.datasource.password` | `informantregister` | Local development credential only; deployed value comes from Key Vault via the CSI driver |
| `spring.flyway.enabled` | `true` | Flyway bean configured; **migration is deferred** — a no-op `FlywayMigrationStrategy` keeps it off the context-refresh path, and the lifecycle controller runs `migrate()` on the first successful store probe, before the processor starts (research §7) |
| `spring.datasource.hikari.initialization-fail-timeout` | `-1` | Lazy pool initialisation — no eager connection during context refresh, so the context starts with the store down (research §7) |
| `informantregister.servicebus.connection-string` | emulator string ending `UseDevelopmentEmulator=true;` | **Local/CI only** |
| `informantregister.servicebus.namespace` | *(unset locally)* | **Deployed only** — fully qualified namespace for `DefaultAzureCredential` |
| `informantregister.servicebus.queue-name` | `informantregister.requests` | Inbound queue |
| `informantregister.servicebus.max-concurrent-calls` | `2` | Processor concurrency (Durable Functions parity) |
| `informantregister.servicebus.max-delivery-count` | `5` | Mirrors the broker queue setting; used to recognise the final permitted delivery |
| `informantregister.servicebus.max-auto-lock-renew-duration` | `5m` | MUST be ≥ processing deadline + the fixed **30 s renewal margin**, so the lock outlives any legitimate run. Both timing relationships (`processing-deadline < lease`; `max-auto-lock-renew-duration ≥ processing-deadline + 30s`) are validated at startup and fail fast |
| `informantregister.servicebus.health-staleness` | `60s` | Age past which an unresolved connection error with no traffic stops being reported as DOWN (research §8) |
| `informantregister.consumer.enabled` | `true` (`false` in the `test` profile) | Master switch for starting the processor at all |
| `informantregister.claim.lease` | `5m` | Claim expiry written as `now() + lease` |
| `informantregister.claim.processing-deadline` | `4m` | Enforced run bound; MUST be strictly less than `informantregister.claim.lease` (validated at startup) |
| `informantregister.store.probe-interval` | `10s` | Store-health probe driving start and resume (research §7) |
| `informantregister.stub.payload-failure-mode` | `NONE` | `NONE`\|`TRANSIENT` — test/local-profile only (see Port contracts) |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` | The whole HTTP surface (spec FR-014) |
| `management.endpoint.health.group.readiness.include` | `db` | Store gates readiness; the `servicebus` indicator is deliberately absent. The `test` profile (which excludes the datasource for context-only tests such as `ActuatorIntegrationTest`) overrides this to `ping`, because Spring validates group membership at startup and a readiness group naming an absent `db` contributor would fail the context. The `*IT` suites run with a real Testcontainers datasource and keep `db` |

**Connection-string vs namespace selection rule**: exactly one is set. If
`informantregister.servicebus.connection-string` is present the client is built from it (local and
CI, emulator only); otherwise `informantregister.servicebus.namespace` plus
`DefaultAzureCredential` is used (deployed). Both set, or neither set, is a startup failure — a
silently preferred credential source is how a deployed pod ends up talking to the wrong broker.

## Constitution Check

*GATE: evaluated against constitution v1.0.1 before Phase 0; re-checked after Phase 1 design.*

| # | Principle | Verdict for this increment |
|---|-----------|----------------------------|
| I | Behaviour-Parity First | **PASS (scope-limited)** — no legacy logic is ported this increment, so no golden files apply. The stub adapters are the agreed shape (constitution "Current increment" section). Nothing here may pre-empt parity decisions: the ports expose `JsonNode`-shaped payload output so the later transformation port is unconstrained. |
| II | Test-Driven Development | **PASS** — every state-machine branch, settlement rule and health rule lands test-first; the test matrix below names the planned test for every FR, SC and edge case, and tasks will order test tasks before the implementation tasks they guard; red runs recorded in commit narratives. |
| III | Message-Contract First | **PASS** — inbound schema already versioned at `src/main/resources/contracts/distribution-command.schema.json`; the corpus is dual-validated against parser **and** schema (research §9); `doc/API_CONTRACTS.md` updated ahead of code (done at spec stage). No business HTTP endpoint is added. |
| IV | Canonical JSON In, Typed Models Out | **PASS (pending one named verification)** — see the Jackson note below. |
| V | SOLID with Ports and Adapters | **PASS** — core imports no Azure/JDBC types; constructor injection with `private final`; the port signatures are fixed below; stubs implement the real port interfaces, log their no-op-ness loudly, and are excluded from the production profile once real adapters land (this increment they ARE the deployed adapters, per the agreed scope). |
| VI | Explicit Failure | **PASS with registered waiver** — explicit settlement, no swallowed exceptions, ERROR log + a named failure metric on every failure path (the instrument table in research §11), bounded reason codes rather than raw exception text, and dead-lettered messages countable from `informantregister_deadlettered_total{reason=…}`. **DLQ depth** is observed from Azure Monitor's native `DeadletteredMessages` metric, not polled by service code. Alert **wiring** (dashboards/alert rules over both signals) is deferred: named waiver `doc/DEVIATIONS.md` #3 (see Complexity Tracking). |
| VII | Privacy in Telemetry | **PASS** — correlation set only (`requestId`, `hearingId`, `hearingDay`, `source`, counts, timings) via MDC; no payload logging; metric labels are low-cardinality enumerations and carry no identifiers. The walking skeleton handles no defendant data at all (thin message only), which the privacy tests still pin. |
| VIII | Estate Conventions | **PASS** — Gradle wrapper, package `uk.gov.hmcts.cp.informantregister`, Conventional Commits, Jira-prefixed branch, SLF4J + Logback JSON. PMD and the coverage report are no longer conventions on trust: `./gradlew pmdMain` and `./gradlew jacocoTestReport` are explicit steps in `.github/workflows/ci-build-publish.yml`, so a PMD failure blocks CI. |

**Principle IV — Jackson generation, resolved**: Spring Boot 4.1 ships **Jackson 3**, whose tree
model is `tools.jackson.databind.JsonNode` (the `com.fasterxml.jackson` coordinates are Jackson 2).
This service uses **the platform's Jackson generation end-to-end** rather than pinning a second
Jackson on the classpath. Principle IV's intent — inbound hearing payloads stay canonical
`JsonNode`, monetary values deserialise as `BigDecimal`, unknown fields survive untouched — is
version-neutral and unchanged; only the class coordinate moves. The constitution has been amended
accordingly (v1.0.1, PATCH). Two facts are confirmed from the template's dependency graph at the
first implementation task: the exact `JsonNode` class on the runtime classpath, and the name of the
Jackson 3 equivalent of `USE_BIG_DECIMAL_FOR_FLOATS`; both are then configured on the shared
`ObjectMapper` (or its Jackson 3 equivalent) with a test that pins the BigDecimal behaviour. Verdict
therefore **PASS pending that single verification** — no design depends on which way it resolves.
`DistributionCommand` remains a typed record: it is this service's own closed contract, not a
foreign payload.

**Post-Phase-1 re-check (2026-08-20)**: design artefacts below introduce no new violations; the
single waiver stands unchanged, and the constitution amendment it prompted is a PATCH clarification
rather than a relaxation.

## Project Structure

### Documentation (this feature)

```text
specs/CRA-220-informant-register-initial-poc/
├── spec.md              # Feature specification (committed, review-gated)
├── plan.md              # This file
├── research.md          # Phase 0 — decisions with rationale and alternatives
├── data-model.md        # Phase 1 — processed-log schema, guard SQL + state machine
├── quickstart.md        # Phase 1 — local run + end-to-end demo sequence
├── contracts/           # Phase 1 — pointer to the canonical inbound schema
│   └── README.md
├── checklists/
│   └── requirements.md  # Spec quality checklist (committed)
└── tasks.md             # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
src/main/java/uk/gov/hmcts/cp/informantregister/
├── inbound/          # ServiceBusConsumerConfig, InformantRegisterMessageListener,
│                     # DistributionCommandParser (schema validation),
│                     # ConsumerLifecycleController (RUNNING ↔ SUSPENDED)
├── application/      # DistributionPipeline, IdempotencyGuard, ProcessingStateService,
│                     # port interfaces: HearingPayloadSource, RegisterSubmissionClient
├── domain/           # DistributionCommand record, RequestStatus, OutputStatus,
│                     # settlement decision types, failure reason codes, AuthoritySubmission
├── adapter/
│   └── stub/         # StubHearingPayloadSource, StubRegisterSubmissionClient (logging no-ops)
├── persistence/      # ProcessedRequestRepository (JdbcClient, conditional-update claim SQL)
└── config/           # Typed @ConfigurationProperties, ObjectMapper, health groups,
                      # Service Bus health indicator (non-readiness), metrics

src/main/resources/
├── application.yaml
├── contracts/distribution-command.schema.json   # canonical inbound contract (exists)
├── db/migration/V1__create_processed_log.sql
└── logback.xml

src/test/java/uk/gov/hmcts/cp/informantregister/
├── application/      # Pipeline + guard unit tests (no Spring, no containers)
├── domain/           # Command validation contract tests against the JSON schema
├── inbound/          # Listener settlement + lifecycle-controller unit tests (mock client)
├── persistence/      # Guard state machine against Testcontainers Postgres (*IT)
└── e2e/              # WalkingSkeletonIT — emulator + Postgres end-to-end (*IT)
```

**Structure Decision**: single-project layout following the package map in
`doc/TECHNICAL_DESIGN.md`. `adapter/payload/`, `adapter/results/` and `pipeline/` are deliberately
absent — they arrive with the later stories; creating them empty now would misstate the increment.

### Port contracts (this increment)

The two port interfaces are the whole point of the skeleton, so their signatures are fixed here
rather than discovered during implementation. Both live in `application/`; neither mentions an
Azure, Redis, HTTP or JDBC type.

```java
public interface HearingPayloadSource {
    JsonNode fetch(DistributionCommand command) throws PayloadUnavailableException;
}

public interface RegisterSubmissionClient {
    void submit(AuthoritySubmission submission) throws SubmissionFailedException;
}
```

- `PayloadUnavailableException` is a **transient** failure by construction (there is no
  non-transient payload-fetch case this increment): it maps to RETRYING + `abandon()`, and to FAILED
  + `deadLetter()` on the final permitted delivery.
- `SubmissionFailedException` **carries its transient/non-transient classification** (the later
  Results adapter needs both: connect/IO/5xx/429 retry, a 4xx contract rejection does not), so the
  pipeline branches on the classification rather than on the exception type.
- `AuthoritySubmission` is a typed record — prosecuting-authority identifier plus the outbound
  document as a `JsonNode` placeholder until the transformation story defines it (Principle IV:
  typed out, with the not-yet-designed document deliberately left as a tree).
- `StubHearingPayloadSource` returns a fixed minimal payload and logs loudly at INFO that it is a
  no-op. `StubRegisterSubmissionClient` logs and returns.

**Skeleton pipeline behaviour, stated precisely**: guard admits the request → the payload stub is
invoked → **the pipeline itself produces an empty authority set, because no transformation port
exists this increment** → therefore the submission port is invoked **zero** times → the request
completes with `completion_reason = 'no-authorities'`. The submission stub existing but never being
called on the happy path is the correct, intended shape, not a gap.

**Test-only failure control (spec FR-009)**: the simulated transient failure is induced by
`informantregister.stub.payload-failure-mode` (`NONE` | `TRANSIENT`, default `NONE`), set only by
tests and the local profile. It is **never a field in the message and never an HTTP endpoint** — a
message-driven or endpoint-driven failure switch would be a production fault-injection surface on a
service whose whole purpose is not losing work.

### Test matrix

Every spec requirement, success criterion and edge case, mapped to the test planned to prove it.
Layers: **U** unit (no Spring, no containers) · **PG** Postgres Testcontainers `*IT` · **SB**
Service Bus emulator Testcontainers `*IT` · **E2E** full context, both containers · **CS** container
smoke (a CI job step, not JUnit).

| Spec item | Layer | Planned test |
|-----------|-------|--------------|
| FR-001 exactly one explicit settlement per delivery | U, SB | `MessageListenerSettlementTest`, `QueueSettlementIT` |
| FR-002 six-field contract validation | U | `DistributionCommandParserTest`, `DistributionCommandSchemaCorpusTest` |
| FR-003 invalid → immediate DLQ, no record, no attempt | SB | `ContractValidationDeadLetterIT` |
| FR-004 durable processed-log, attempts semantics | PG | `IdempotencyGuardIT`, `ProcessedLogDurabilityIT` |
| FR-005 state-machine transitions | PG | `IdempotencyGuardIT` (one case per table row) |
| FR-006 COMPLETED delivery acknowledged, no run | PG, E2E | `IdempotencyGuardIT`, `WalkingSkeletonIT` |
| FR-007 FAILED replay by message identity | PG | `FailedReplayIT` (fresh id → RECEIVED; same id → stays FAILED, re-dead-letter) |
| FR-008 at most one run in flight; claim reclaimable | PG | `ClaimContentionIT`, `ClaimReclamationIT`, `StaleRunnerRejectionIT` |
| FR-009 failure recorded, retried, then parked | U, SB | `DistributionPipelineTest` (stub failure mode), `DeliveryExhaustionIT` |
| FR-010 pipeline invoked through the ports | U, E2E | `DistributionPipelineTest`, `WalkingSkeletonIT` (payload stub logged, submission stub not invoked) |
| FR-011 store gates readiness, queue never does | SB | `ReadinessPolicyIT` |
| FR-012 correlation fields, no PII at INFO+ | U, E2E | `TelemetryPrivacyTest`, `TraceabilityIT` |
| FR-013 container image starts healthy; CI definitions present | CS | `Container smoke` CI step |
| FR-014 actuator-only HTTP surface | U | `HttpSurfaceTest` (asserts the exposure list), existing `ActuatorIntegrationTest` |
| FR-015 store outage → abandon + suspend intake | PG, SB | `StoreOutageIT` |
| FR-016 settlement-failure edges, lock loss | U, PG | `SettlementFailureEdgeTest`, `StaleRunnerRejectionIT` |
| FR-017 ERROR log + failure metric on every failure path | U, E2E | `ProcessingMetricsTest` (one case per instrument), `FailureSignalIT` |
| FR-018 fingerprint collision → DLQ, record untouched | U, PG | `RequestFingerprintTest`, `IdempotencyCollisionIT` |
| SC-001 zero silent loss — every message accounted for | E2E | `MessageAccountingIT` (mixed batch: valid, invalid, failing, duplicate; asserts the accounting partition) |
| SC-002 zero duplicate processing in normal operation | PG, E2E | `ClaimContentionIT` (N concurrent deliveries → one run), `WalkingSkeletonIT` |
| SC-003 parked after exactly 5 deliveries; replay → 6 attempts | SB | `DeliveryExhaustionIT`, `FailedReplayIT` |
| SC-004 ready < 60s; consumption resumes < 60s after queue returns | CS, SB | `Container smoke` CI step, `QueueOutageRecoveryIT` |
| SC-005 trace a request end-to-end from logs alone | E2E | `TraceabilityIT` (captures the appender, asserts `requestId` on every line from receipt to settlement) |
| SC-006 one documented command demonstrates the skeleton | E2E | `WalkingSkeletonIT` + `quickstart.md` |
| Edge: crash between run completion and outcome write | PG | `CrashWindowIT` (outcome write suppressed → redelivery reruns, sequentially) |
| Edge: unknown fields in an otherwise valid message | U, SB | `DistributionCommandSchemaCorpusTest`, `ContractValidationDeadLetterIT` |
| Edge: `source`/`eventType` outside agreed values | U | `DistributionCommandSchemaCorpusTest` |
| Edge: resubmission reusing the original message identity | SB | `DuplicateDetectionIT` (broker discards it; the service never sees it) |
| Edge: processed-log store unavailable | PG, SB | `StoreOutageIT` — abandon, never complete, never dead-letter |
| Edge: store unavailable across many retry intervals | PG, SB | `ProlongedStoreOutageIT` — store down for more than five nominal retry intervals; asserts the message never reaches the DLQ and processes cleanly on resume |
| Edge: contract-invalid message arriving during a store outage | SB | `StoreOutageIT` (case: not examined while down; validated and dead-lettered on resume) |
| Edge: crash after RECEIVED, before the run completes | PG | `ClaimReclamationIT` (expired claim reclaimed by the next delivery) |
| Edge: same key, different immutable fields | PG | `IdempotencyCollisionIT` |
| Edge: RETRYING request redelivered after a long gap | PG | `IdempotencyGuardIT` (RETRYING → run under the single-runner rule) |
| Startup with the queue already down | SB | `StartupWithQueueDownIT` (spec US4-3: becomes ready, reports the queue DOWN, consumes when it returns) |
| Stale runner finishing after reclamation | PG | `StaleRunnerRejectionIT` — the owner+token predicate matches zero rows; the result is discarded, WARN + counter, delivery abandoned |

**Store-outage mechanics**: the outage is produced by **pausing the Testcontainers Postgres
container** (`ContainerState.getDockerClient().pauseContainerCmd(...)`), which severs the
connections without losing the volume, and unpausing to recover. Toxiproxy is introduced **only if
pausing proves insufficient** (for example if the driver reports a misleading error class) — it is a
fallback, not the plan, and adopting it would be recorded as a task-level note.

**Test profile**: a `test` Spring profile sets `informantregister.consumer.enabled=false` and
excludes the datasource auto-configuration, so the existing `ActuatorIntegrationTest` and any plain
context-load tests keep running with **no broker and no database**. Container-backed suites activate
the real configuration explicitly. Without this, adding the consumer would break tests that have
nothing to do with it.

**CI**: `./gradlew build` already runs the `test` task, and the `*IT` suites live in `test` (there
is no separate `integrationTest` task), so **Docker must be available on the CI runner** — it is on
`ubuntu-latest`. `./gradlew pmdMain` and `./gradlew jacocoTestReport` are added as explicit steps
after the build in `.github/workflows/ci-build-publish.yml`, because PMD is excluded from `build` by
an `onlyIf` and would otherwise never run in CI. The **container smoke** step — build the image from
`Dockerfile`, run it against the compose dependencies, poll `/actuator/health/readiness` until ready
or a 60-second timeout expires (spec SC-004), then tear down — is a CI job step rather than a JUnit
test, and is added to the workflow by the container task in Phase 2 alongside the Dockerfile work it
proves.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Principle VI alerting clause waived for this increment (`doc/DEVIATIONS.md` #3) | Alert rules/dashboards live outside this repo (platform observability stack) and belong with the operability story; the metrics and ERROR logs the alerts will fire on ship now, and DLQ depth is already emitted by Azure Monitor | Wiring alerts now would couple CRA-220 to platform config this repo does not own and cannot test; shipping the metrics first keeps the waiver narrow and auditable |
