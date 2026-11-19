# Implementation Plan: Replay a parked request by broker sequence number

**Branch**: `main` (see "Branch naming" note below) | **Date**: 2026-11-18 | **Spec**:
[spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-replay-by-sequence-number/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See
`.specify/templates/plan-template.md` for the execution workflow.

## Summary

The idempotency guard currently tells a deliberate resubmission of a `FAILED` request apart from a
redelivery of the delivery that parked it by comparing `messageId`. Because the Azure portal's
dead-letter "Re-send" — the only recovery tool support actually has — copies the message including
its `messageId`, every real resubmission reads as a redelivery and is re-parked, unrecovered, under
the generic reason `DELIVERY_LIMIT_EXHAUSTED`. This plan replaces that test with the broker-assigned
**sequence number**, which a re-send always changes and a redelivery never does; adds a
null-safe compare-and-set against the exact failure generation observed, so a stale decision can
never replay a newer failure; adds a two-condition heuristic for the rows parked before this
change, which have no recorded parking sequence number; makes a re-park report the request's own
recorded failure reason and category instead of the generic exhaustion reason; and consolidates the
listener's broker-metadata reads, several of which are currently repeated per delivery, into one
capture inside the existing settlement boundary. The inbound message contract's replay rule is
relaxed to match (`doc/API_CONTRACTS.md`, written before the code, per Principle III).

Technical approach: one additive Flyway migration (two nullable columns, two constraints); the
existing `DeliveryIdentity`/`RunClaim`/`ProcessedRequestRecord` records each gain the broker facts
the decision needs; `IdempotencyGuard.replayOrPark` is rewritten per the decision table in
`data-model.md`; `ProcessedRequestRepository`'s read, park and replay statements change to match;
`InformantRegisterMessageListener` gains a single broker-delivery snapshot built once per delivery,
replacing several repeated accessor calls; two new small pure lookups (`ReasonCode.fromCode`,
`FailureCategoryMapping.categoryFor`) resolve the re-park reason/category. No register content, no
outbound contract, and no payload handling is touched — this is entirely the "transport recovery"
area the constitution already names as the one place behavioural change is sanctioned (Principle I).

## Technical Context

**Language/Version**: Java 25, Gradle (wrapper committed; Maven forbidden per Principle VIII).
**Primary Dependencies**: Spring Boot 4.1; `azure-messaging-servicebus`
(`ServiceBusProcessorClient`/`ServiceBusReceivedMessage`, read only in the inbound adapter);
Spring's `JdbcClient` over hand-written SQL (no ORM — "the affected-row count is the decision");
Flyway; Jackson 3 tree model (`tools.jackson.databind.JsonNode`, unaffected by this feature); SLF4J
+ Logback with `LoggingEventCompositeJsonEncoder`.
**Storage**: PostgreSQL. One additive migration (`V2`) on the existing `processed_request` table;
`processed_output` is untouched.
**Testing**: JUnit Jupiter 6 + Mockito + AssertJ; Testcontainers (PostgreSQL; the Service Bus
emulator via `ServiceBusEmulatorTestSupport`, pinned image tags); the existing suites this feature
extends are `FailedReplayIT`, `IdempotencyGuardIT` (persistence), `MessageListenerSettlementTest`,
`ListenerExhaustionTest`, `SettlementFailureEdgeTest` (inbound), `DeliveryExhaustionIT`,
`QueueSettlementIT` (e2e, real emulator).
**Target Platform**: AKS via Flux (deployed); Docker Compose (local). No platform change.
**Project Type**: single Spring Boot service, message-driven, no REST surface (Principle III) — not
a web/mobile split; the existing repository layout is used as-is (see "Project Structure" below).
**Performance Goals**: none new. The broker-metadata capture must not add a second AMQP round trip
or measurable latency — it replaces repeated in-process accessor calls with a single capture, it
does not add a call to the broker. The replay/re-park decision adds one additional indexed-by-PK
column comparison to statements already keyed on `(source, request_id)`; no new query shape.
**Constraints**: Message-contract-first (Principle III) — `doc/API_CONTRACTS.md`'s replay rule
changes before the code that implements the relaxed behaviour. No change to the outbound
`add-informant-register` command or to register content (explicitly out of scope). TDD
(Principle II) — every FR-015 bullet is a test written before its implementation. Explicit failure
(Principle VI) — the broker-metadata consolidation must still settle every delivery exactly once,
including when a required accessor throws. No PII (Principle VII) — sequence number, delivery
count and enqueue time are broker facts, not personal data, and are already an MDC-correlated
precedent (`sequenceNumber`, `deliveryCount` are logged today). Estate conventions (Principle
VIII) — Checkstyle/PMD/JaCoCo gates apply to every new and changed class; Flyway, never Liquibase;
Conventional Commits; no AI attribution.
**Scale/Scope**: six existing production classes changed
(`DeliveryIdentity`, `RunClaim`, `ProcessedRequestRecord`, `ProcessedRequestRepository`,
`IdempotencyGuard`, `InformantRegisterMessageListener`), two small new domain-level pure types
(a `ReasonCode.fromCode` lookup and a `FailureCategoryMapping` helper), one new
`InformantRegisterProperties.Replay` config record plus one new `PropertiesValidator` startup
check, one additive Flyway migration, and a double-digit number of test classes extended per the
FR-015 gate list (no new IT class is anticipated — see research.md R6). Seven documents outside
`src/` are updated as implementation tasks (listed in the spec's "Documents the implementation must
update" and carried into `tasks.md` unchanged).

### Branch naming — known gap, not actioned by this plan

`.specify/extensions.yml`'s `before_specify` hook creates a Jira-prefixed branch
(`CRA-XXX-short-slug`) automatically, and the constitution requires one (Principle VIII). This
feature's `spec.md` was nonetheless authored and committed directly on `main` — `setup-plan.sh`
confirms `BRANCH=main` for this plan. This plan does not create a branch or move the spec: branch
hygiene is a process decision, not a design one, and `/speckit-plan`'s own `before_plan` hook
(`speckit.git.commit`) is configured disabled in this repo, so nothing here is set up to act on it
automatically either. Flagged for the person running `/speckit-tasks`/`/speckit-implement` next to
decide — most likely, branch from `main` as `CRA-XXX-replay-by-sequence-number` before writing any
code, so the eventual PR is reviewable against a named ticket per Principle VIII.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design — see below.*

| Principle | Check | Result |
|---|---|---|
| I. Behaviour-Parity First | Does this change register content, the legacy transformation, or anything the Jest fixtures pin? | **PASS.** The spec's own Context section names this "transport recovery," the one area the constitution pre-approves for behavioural change. No golden file, no transformation class, no outbound document shape is touched. `doc/DEVIATIONS.md` #16 needs a wording correction ("changing only the broker `messageId`" → "optionally changing the broker `messageId`"), not a new entry — the attribution finding it pins is unaffected by how the parking delivery is identified. |
| II. Test-Driven Development | Is every behaviour change provably test-first? | **PASS, gate carried forward.** FR-015 is an exhaustive, spec-authored test list; `/speckit-tasks` must order every one of its bullets as a test task before the implementation task it guards, per the existing tasks-template convention. |
| III. Message-Contract First | Does a contract change land before the code that depends on it? | **PASS by construction.** `contracts/replay-rule-delta.md` (this plan's Phase 1 output) is the exact text `doc/API_CONTRACTS.md` needs; the first implementation task is that doc edit, before any guard code changes. No REST endpoint is added. |
| IV. Canonical JSON In, Typed Models Out | Does this touch inbound payload handling? | **N/A — untouched.** This feature is entirely about broker delivery identity and the processed-log state machine; the hearing payload is never read differently. |
| V. SOLID with Ports and Adapters | Does any Azure/Redis/HTTP/JDBC type cross into `application`/`domain`? | **PASS, with one design decision to protect it.** The new `BrokerDeliverySnapshot` (research.md R3) is built and consumed entirely inside `inbound/InformantRegisterMessageListener` — it is never passed to `DistributionPipeline` or `IdempotencyGuard`. Those two see only the existing, enlarged `DeliveryIdentity` record (domain), which already crosses this boundary today. `code-reviewer` must specifically check that no `ServiceBusReceivedMessage` (or its package) appears outside `inbound/` once this lands. |
| VI. Explicit Failure | Can the broker-metadata consolidation introduce a path that returns without settling? | **PASS, and this is the point of FR-011/FR-012.** The snapshot is built inside the existing `try` in `outcomeOf`, under the same `RuntimeException` catch-all that already classifies "anything else at all" as `unexpectedFailure` → exactly one settlement. The one new rule: a failure to build the snapshot can never determine `finalPermittedDelivery`, so FR-012 requires it always `abandon()`s, never `deadLetter()`s, even on what might be the last delivery — the conservative choice stated explicitly so a reviewer does not "fix" it into an escalation. |
| VII. Privacy in Telemetry | Does any new field carry PII? | **PASS.** Sequence number, delivery count and enqueue time are broker facts about the message, not about a person — the same class of fact `sequenceNumber`/`deliveryCount` already logged today. Nothing from `failure_reason` or `parking_category` is free text; both are bounded codes already subject to the no-PII gate. |
| VIII. Estate Conventions | Gradle, Flyway, Checkstyle/PMD/JaCoCo, Conventional Commits, no AI attribution? | **PASS.** One additive Flyway migration (`V2`, forward-only, per FR-014). No new static-analysis suppressions anticipated; if the new pure lookups need one (unlikely — they are small switch expressions), it must be inline, narrow and reasoned, per Principle VIII. |

No violations. Complexity Tracking is not filled in.

## Project Structure

### Documentation (this feature)

```text
specs/001-replay-by-sequence-number/
├── plan.md              # This file
├── research.md           # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── replay-rule-delta.md   # Phase 1 output — the doc/API_CONTRACTS.md text delta (FR-013)
└── tasks.md              # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

This is an existing, mature single-service repository — not a template choice. The feature's
changes land inside the layout `design_rules.md` already documents; no new top-level directory is
introduced.

```text
src/main/java/uk/gov/hmcts/cp/informantregister/
├── domain/
│   ├── DeliveryIdentity.java          # + sequenceNumber, deliveryCount, enqueueTime
│   ├── RunClaim.java                  # + sequenceNumber
│   ├── ProcessedRequestRecord.java    # + parkingSequenceNumber, parkingCategory, updatedAt
│   ├── ReasonCode.java                # + fromCode(String): Optional<ReasonCode>
│   └── FailureCategoryMapping.java    # NEW — pure, FR-010's reason→category table
├── application/
│   └── IdempotencyGuard.java          # replayOrPark rewritten per data-model.md's decision table
├── persistence/
│   └── ProcessedRequestRepository.java # READ_RECORD, RECORD_FAILED, REPLAY_FAILED statements
├── inbound/
│   └── InformantRegisterMessageListener.java # BrokerDeliverySnapshot captured once per delivery
└── config/
    ├── InformantRegisterProperties.java # + Replay(legacyMargin) record
    └── PropertiesValidator.java         # + margin-vs-lockDuration startup check

src/main/resources/db/migration/
└── V2__add_parking_sequence_and_category.sql   # NEW — additive, forward-only

src/test/java/uk/gov/hmcts/cp/informantregister/
├── persistence/{FailedReplayIT,IdempotencyGuardIT}.java      # extended per FR-015
├── application/{AdmissionExhaustionTest,OutcomeWriteExhaustionTest}.java  # extended
├── inbound/{MessageListenerSettlementTest,ListenerExhaustionTest,
│            SettlementFailureEdgeTest}.java                  # extended
├── e2e/{DeliveryExhaustionIT,QueueSettlementIT}.java         # extended (emulator-backed FR-015 bullet)
└── support/ProcessedLogTestSupport.java                      # delivery()-style helpers gain a
                                                                # distinct sequence number per call

doc/
├── API_CONTRACTS.md        # replay rule + messageId note (Principle III — updated before code)
├── DEVIATIONS.md           # entry #16 wording correction only
└── CHANGELOG.md            # new entry

specs/CRA-220-informant-register-initial-poc/
├── spec.md                 # replay/redelivery requirements and success criteria updated
└── data-model.md           # columns, constraint notes, state-transition table updated

.claude/rules/{design_rules.md,technical-rules.md}   # "replay tooling always mints a fresh
                                                       # messageId" and the identity rule corrected
.specify/memory/constitution.md                       # wherever it states the replay rule
.claude/agents/{qa,code-reviewer,spec-validator,software-engineer}.md  # replay-rule checks updated
```

**Structure Decision**: no structural change to the repository — this feature is entirely
additive/modifying within the existing `domain → application → persistence`/`inbound` layering
`design_rules.md` already mandates. The new pure lookups (`ReasonCode.fromCode`,
`FailureCategoryMapping`) go in `domain/` because they are closed-vocabulary lookups with no I/O,
consistent with every other type already there (research.md R4). The new config record follows the
existing `InformantRegisterProperties` nested-record convention; the new startup check follows the
existing `PropertiesValidator` convention (research.md R5). Naming note: spec.md's prose calls this
value the "configured safety margin"; the implementation and every other artifact in this feature
call it the **legacy margin** (`Replay.legacyMargin`, `informantregister.replay.legacy-margin`) —
same value, spec.md now cross-references the implementation name at FR-005.

## Complexity Tracking

*No entries — the Constitution Check above found no violation requiring justification.*
