# Tasks: Replay a parked request by broker sequence number

**Input**: Design documents from `/specs/001-replay-by-sequence-number/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/replay-rule-delta.md, quickstart.md

**Tests**: Mandatory throughout (constitution Principle II). Every test task is ordered before the
implementation task it guards; each one's commit/PR narrative must record the observed failing
assertion before the matching implementation task's green run.

**Organization**: Phase 3 ("Foundational") carries the single, structurally inseparable schema +
decision-branch change that FR-003/FR-004/FR-006/FR-007/FR-011/FR-012 are — none of the four user
stories can be exercised without it, because it is one `if`/`else` and the one SQL statement it
calls, not four. Each user story phase then adds the behaviour and tests that **are** genuinely
separable: FR-008's two logging obligations split across US1 (replay) and US2 (re-park); the
legacy-row heuristic (US3) is an additional branch; the real re-park reason/category (US4) is a
follow-on enhancement to a branch that already exists. See plan.md's "Technical Context" for why
this is not the usual one-story-per-slice shape.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US4); Setup/Foundational/Polish tasks carry none
- File paths are exact and relative to the repository root

---

## Phase 1: Setup

**Purpose**: The one prerequisite Principle III (Message-Contract First) makes non-negotiable
before any guard code changes: the contract document changes first.

- [X] T001 Apply the `contracts/replay-rule-delta.md` text delta to `doc/API_CONTRACTS.md` — the
      "Replay rule" section, the "Failure behaviour" table's two `messageId`-keyed rows, and the new
      re-park dead-letter-description row (FR-013)

**Checkpoint**: The documented contract now describes the sequence-number-based replay rule. No
production code has changed yet — this is the spec that Phase 2 onward implements against.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The schema, domain types, repository statements and guard/listener mechanics every
user story depends on. FR-003, FR-004, FR-006, FR-007, FR-011 and FR-012 land here because they
are one decision and one SQL statement — splitting them across stories would mean shipping an
`if` with no `else`.

**⚠️ CRITICAL**: No user story task may start until this phase's checkpoint is reached.

**Note on ordering within this phase** (addresses /speckit-analyze finding F1): the usual
"all tests, then all implementation" split does not fully hold here, because three of the
tests below construct a `DeliveryIdentity` with a real sequence number through the shared
`ProcessedLogTestSupport`/`delivery(int)`-style test helpers, and that helper cannot be
updated to the new shape until the migration and the enlarged `DeliveryIdentity` it reads
exist. This phase is therefore sequenced in four parts, not two, with the dependency
annotations on each task stating the true build order explicitly.

### Part A — Tests needing no fixture change (write first) ⚠️

- [X] T002 [P] Test: a Flyway migration applied over existing `processed_request` rows (no parking
      columns) leaves them valid and queryable — extend
      `src/test/java/uk/gov/hmcts/cp/informantregister/support/PostgresTestSupport.java`'s fixture or
      add a focused migration test asserting `parking_sequence_number`/`parking_category` are both
      `NULL` on a pre-existing row and both constraints are satisfied (FR-014; FR-015 "the migration
      leaves existing rows valid")

### Part A — Implementation unblocking the remaining Foundational tests

- [X] T003 Create `src/main/resources/db/migration/V2__add_parking_sequence_and_category.sql`: add
      nullable `parking_sequence_number bigint` and `parking_category text` to `processed_request`,
      plus `processed_request_parking_category_chk` (`IN ('EXHAUSTED','NON_TRANSIENT')`) and
      `processed_request_parking_pair_chk` (both null or both set) — per data-model.md's schema delta
      (depends on: T002)
- [X] T004 [P] Add `sequenceNumber`, `deliveryCount` and `enqueueTime` to
      `src/main/java/uk/gov/hmcts/cp/informantregister/domain/DeliveryIdentity.java`; update its
      existing two-argument convenience constructor's javadoc and add a constructor/factory that
      takes the full broker-fact set. No guarding test of its own — it is a data-shape addition
      consumed by T005's helper and by T006/T007's decision-table tests, which must therefore come
      after it, not before (corrected per /speckit-analyze finding F1 follow-up)
- [X] T005 Test infrastructure, moved here from Polish because T006/T007/T008 below need it to
      even compile (see /speckit-analyze finding F1) — not a behaviour test itself, so it is placed
      alongside the Foundational implementation it mirrors rather than under "write first". Update `src/test/java/uk/gov/hmcts/cp/informantregister/support/ProcessedLogTestSupport.java`'s
      `Row` record (add `parkingSequenceNumber`, `parkingCategory`) and its backing query, and give
      its `delivery(int)`-style helpers (here and in `FailedReplayIT`, `IdempotencyGuardIT`,
      `MessageListenerSettlementTest`, `DeliveryExhaustionIT`) a matching distinct sequence number per
      call, replacing the `messageId`-only minting convention (depends on: T003, T004)

### Part B — Remaining Foundational tests (write first) ⚠️

T006–T008 need T005's fixture update; T009–T011 do not (they drive the listener directly
against mocked/emulator broker objects, not through the `delivery(int)` helper) — grouped
together here only because both sets are now unblocked.

- [X] T006 [P] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/persistence/IdempotencyGuardIT.java`'s
      `ParkedRequest` nested class, add cases for the new decision table — same sequence number
      re-parks with no state change; different sequence number (same `messageId` included) replays
      regardless of `messageId`; a replay's compare-and-set is predicated on the exact `attempts` and
      `parking_sequence_number` observed on the read, not the current row (FR-003, FR-004, FR-006) (depends on: T003, T004, T005)
- [X] T007 [P] Test: in `src/test/java/uk/gov/hmcts/cp/informantregister/persistence/FailedReplayIT.java`,
      add cases for: two concurrent re-sends admitting exactly one replay; a
      `FAILED → RECEIVED → FAILED` race (a second failure recorded between another delivery's read
      and its replay attempt) never replaying the newer failure; the compare-and-set's null-safe half
      matching a `NULL` `parking_sequence_number` to a `NULL` observed value correctly (FR-006; FR-015
      "two concurrent re-sends admit one replay... the compare-and-set matches a null parking
      sequence correctly") (depends on: T003, T004, T005)
- [X] T008 [P] Test: a stale runner (claim reclaimed mid-run) cannot overwrite
      `parking_sequence_number`/`parking_category` when it loses the owner-and-token predicate on its
      own `recordNonTransientFailure`/`recordExhaustion` write — extend
      `src/test/java/uk/gov/hmcts/cp/informantregister/application/OutcomeWriteExhaustionTest.java`
      (FR-002) (depends on: T003, T004, T005)
- [X] T009 [P] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/inbound/MessageListenerSettlementTest.java`,
      assert each of `getSequenceNumber()`, `getDeliveryCount()`, `getMessageId()`, `getLockToken()`
      and `getEnqueuedTime()` on a mocked `ServiceBusReceivedMessage` is invoked **at most once** per
      delivery across a full `onMessage` call, for both a successful run and a dead-lettered one
      (FR-011; FR-015 "each broker accessor is called at most once")
- [X] T010 [P] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/inbound/MessageListenerSettlementTest.java`,
      a `ServiceBusReceivedMessage` whose `getSequenceNumber()` (or
      any other required accessor) throws still results in exactly one settlement call
      (`abandon()`, never `deadLetter()`, because the final-delivery position cannot be determined) and
      exactly one ERROR log line; a message with no `enqueuedTime` annotation settles normally with
      the enqueue time recorded as absent (FR-012; FR-015 "a failure... still makes exactly one
      settlement attempt... an absent enqueue time continues normally")
- [X] T011 [P] Test: against the real emulator, extend
      `src/test/java/uk/gov/hmcts/cp/informantregister/e2e/QueueSettlementIT.java` with: a message's
      sequence number is unchanged across an `abandon()`-and-redeliver cycle, and a re-send (DLQ
      receive + send back onto the live queue) is assigned a new one (FR-015, last bullet)

### Part B — Remaining Foundational implementation

- [X] T012 [P] Add `sequenceNumber` to
      `src/main/java/uk/gov/hmcts/cp/informantregister/domain/RunClaim.java`, carried from the
      `DeliveryIdentity` that acquired the claim, the same way `messageId` already is (depends on: T004)
- [X] T013 [P] Add `parkingSequenceNumber` (`Long`), `parkingCategory` (`String`) and `updatedAt`
      (`Instant`) to
      `src/main/java/uk/gov/hmcts/cp/informantregister/domain/ProcessedRequestRecord.java` (depends
      on: T006)
- [X] T014 Update `READ_RECORD` in
      `src/main/java/uk/gov/hmcts/cp/informantregister/persistence/ProcessedRequestRepository.java`
      to select `parking_sequence_number, parking_category, updated_at` and map them into the
      enlarged `ProcessedRequestRecord` (depends on: T003, T013)
- [X] T015 In
      `src/main/java/uk/gov/hmcts/cp/informantregister/persistence/ProcessedRequestRepository.java`,
      update `RECORD_FAILED` (backing `recordNonTransientFailure` and `recordExhaustion`) to
      additionally set `parking_sequence_number = :sequenceNumber, parking_category = :category` in
      the same statement as `status = 'FAILED'`, bound from the claim's `sequenceNumber` and the
      caller's `DeadLetterReason` name (`NON_TRANSIENT` or `EXHAUSTED` — no lookup needed, the caller
      already knows which) (depends on: T012, T014, T008)
- [X] T016 In the same file, rewrite `REPLAY_FAILED`'s `WHERE` clause from `status = 'FAILED' AND
      exhausted_message_id <> :messageId` to `status = 'FAILED' AND attempts = :observedAttempts AND
      parking_sequence_number IS NOT DISTINCT FROM :observedParkingSequenceNumber`; add
      `parking_sequence_number = NULL, parking_category = NULL` to its `SET` clause alongside the
      existing `exhausted_message_id = NULL` (depends on: T015, T007)
- [X] T017 Rewrite `IdempotencyGuard.replayOrPark` in
      `src/main/java/uk/gov/hmcts/cp/informantregister/application/IdempotencyGuard.java`: when
      `record.parkingSequenceNumber()` is present, decide by equality against the delivery's
      sequence number (equal → re-park unchanged, with today's `EXHAUSTED`/`DELIVERY_LIMIT_EXHAUSTED`
      reason for now — US4 replaces this; different → call the rewritten replay statement passing
      the observed `attempts`/`parkingSequenceNumber`). `messageId` equality is removed as the
      decision — `exhausted_message_id` is still written and still read into the audit note, never
      consulted for the branch itself (FR-003, FR-004, FR-006, FR-007) (depends on: T016, T012, T006)
- [X] T018 In
      `src/main/java/uk/gov/hmcts/cp/informantregister/inbound/InformantRegisterMessageListener.java`,
      introduce a `BrokerDeliverySnapshot` record (`sequenceNumber`, `deliveryCount`, `messageId`,
      `lockToken`, nullable `enqueueTime`, `finalPermittedDelivery`) built once, inside the existing
      `try` in `outcomeOf`, before `storeGate.storeAvailable()` is checked. Replace
      `correlateDelivery`, `identityOf` and every call to `isFinalPermittedDelivery(message)` with
      reads of the one snapshot; `identityOf` now builds `DeliveryIdentity` from the snapshot instead
      of the raw message (FR-011) (depends on: T004, T009)
- [X] T019 In
      `src/main/java/uk/gov/hmcts/cp/informantregister/inbound/InformantRegisterMessageListener.java`,
      ensure a throw while building the snapshot is caught by the existing
      `RuntimeException` branch in `outcomeOf` and settles via `unexpectedFailure`'s `handBack` path —
      which must `abandon()` unconditionally here, never `deadLetter()`, because
      `finalPermittedDelivery` cannot be read from a snapshot that failed to build (FR-012) (depends
      on: T018, T010)

**Checkpoint**: Replay-by-sequence-number and re-park-on-redelivery both work end to end, with the
pre-existing dead-letter reason reporting; every broker accessor is read once per delivery; a
capture failure still settles exactly once. User story work can now begin.

---

## Phase 3: User Story 1 - Support recovers a parked request by re-sending it unchanged (Priority: P1) 🎯 MVP

**Goal**: A dead-letter "Re-send" — same body, same `messageId` — is recognised as a resubmission
and replays, once the broker actually accepts and enqueues it (i.e. outside the duplicate-detection
window).

**Independent Test**: Park a request, wait past the duplicate-detection window, re-send the
dead-lettered message unchanged, and observe `FAILED → RECEIVED` and a re-run.

### Tests for User Story 1 (write first) ⚠️

- [X] T020 [P] [US1] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/persistence/FailedReplayIT.java`, a verbatim
      re-send (same `messageId`, new sequence number because the broker accepted it as a new send)
      replays; authorities already `POSTED` from the earlier run are skipped on the replay — extend
      the existing attribution fixture or add a sibling case (FR-004, FR-007; Acceptance Scenario
      US1-1, US1-2)
- [X] T021 [P] [US1] Test: in the same file, a delivery carrying a **fresh** `messageId` and a
      different sequence number still replays exactly as before — the pre-existing replay route must
      not regress (Acceptance Scenario US1-3)
- [X] T022 [P] [US1] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/inbound/DeliveryReceiptLogTest.java`, a
      replay's log line names the parked delivery's sequence number,
      the replaying delivery's sequence number, and no PII (FR-008; Acceptance Scenario US1-4)
- [X] T023 [P] [US1] Test: `src/test/java/uk/gov/hmcts/cp/informantregister/e2e/UnchangedResendRecoveryIT.java`
      (not `QueueSettlementIT.java` as first scoped — that suite mocks `DistributionPipeline`
      deliberately, so it cannot exercise the real `FAILED → RECEIVED → COMPLETED` recovery this task
      needs; a new, real-`@SpringBootTest` suite was added instead, alongside `DeliveryExhaustionIT`
      and `DuplicateDetectionIT`). Parks via five real deliveries, re-sends the message unchanged
      (same `messageId`, same body) after the duplicate-detection window elapses, and observes the
      run complete. Runs against a second, test-only queue
      (`informantregister.requests.resend-window-test`, `docker/servicebus-emulator/config.json`)
      with a ten-second window rather than the production-mirroring queue's five minutes, so the
      wait is practical in CI; `DuplicateDetectionIT` keeps asserting the real five-minute value
      against the real queue, so production fidelity there is unchanged

### Implementation for User Story 1

- [X] T024 [US1] In
      `src/main/java/uk/gov/hmcts/cp/informantregister/application/IdempotencyGuard.java`, add the
      FR-008 log line to the replay branch of `replayOrPark`
      (parked sequence number or `"none"`, current sequence number, delivery count, enqueue time —
      no PII), alongside the existing audit-note write (depends on: T017, T020, T022)

**Checkpoint**: User Story 1 — the defect — is fixed and independently verifiable. This is the
suggested MVP cut.

---

## Phase 4: User Story 2 - The delivery that parked the current failure is never re-run (Priority: P1)

**Goal**: A redelivery of the exact delivery that parked the current `FAILED` generation (same
sequence number) still never runs, even after a lost lock or a closed connection.

**Independent Test**: Park a request, arrange for the same message (same sequence number) to be
delivered again, and observe nothing runs and it is dead-lettered again.

### Tests for User Story 2 (write first) ⚠️

- [X] T025 [P] [US2] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/persistence/IdempotencyGuardIT.java`'s
      `ParkedRequest` nested class, a redelivery
      carrying the recorded parking sequence number re-parks with **no** state change — assert the
      full row is byte-identical before and after (Acceptance Scenario US2-1)
- [X] T026 [P] [US2] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/inbound/ListenerExhaustionTest.java`, exactly
      one settlement attempt is made for the re-parked delivery (Acceptance Scenario
      US2-2)
- [X] T027 [P] [US2] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/inbound/DeliveryReceiptLogTest.java`, the
      re-park decision's log line names the parked sequence number, the
      current delivery's sequence number, delivery count and enqueue time, no PII (FR-008)

### Implementation for User Story 2

- [X] T028 [US2] In
      `src/main/java/uk/gov/hmcts/cp/informantregister/application/IdempotencyGuard.java`, add the
      FR-008 log line to the re-park (same-sequence-number) branch of
      `replayOrPark`, mirroring T024's replay-side line (depends on: T017, T025,
      T027)

**Checkpoint**: User Stories 1 AND 2 both hold — the fix works and the protection it must not
remove still works.

---

## Phase 5: User Story 3 - Requests parked before this change can be recovered (Priority: P2)

**Goal**: A `FAILED` row with no recorded parking sequence number (parked before this change, or by
an older consumer mid-rollout) is still recoverable via the documented two-condition heuristic.

**Independent Test**: Create a `FAILED` record with no parking sequence number; a first delivery
(count 0) enqueued well after the parking time replays; one failing either condition re-parks.

### Tests for User Story 3 (write first) ⚠️

- [X] T029 [P] [US3] Test: in a new `@Nested LegacyRecords` class inside
      `src/test/java/uk/gov/hmcts/cp/informantregister/persistence/FailedReplayIT.java`,
      construct a `FAILED` row with `parking_sequence_number IS NULL` directly against the fixture,
      then assert: delivery count 0 **and** enqueue time later than `updated_at` + margin → replay;
      delivery count ≥ 1 → re-park; enqueue time not later than `updated_at` + margin (including
      exactly equal) → re-park; enqueue time absent → re-park (FR-005; the full FR-015 legacy-record
      bullet list)
- [X] T030 [US3] Test: in the same `LegacyRecords` class as T029 — not parallel with it, same file
      — a legacy parking-message redelivery
      released with count 0 but enqueued
      before the parking time re-parks; a legacy resubmission handed back once (e.g. the store was
      unavailable, or a concurrent replay won) is re-parked on its next delivery and judged afresh,
      not remembered (FR-015; spec User Story 3, Acceptance Scenario 3)
- [X] T031 [P] [US3] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/config/PropertiesValidatorTest.java`,
      a configured `informantregister.replay.legacy-margin` shorter than
      `informantregister.servicebus.lockDuration` fails startup (FR-005; FR-015 "a configured margin
      shorter than the queue's lock duration is rejected")
- [X] T032 [US3] Test: in the same `LegacyRecords` class as T029/T030 — not parallel with them, same
      file — in
      `src/test/java/uk/gov/hmcts/cp/informantregister/persistence/FailedReplayIT.java`, a simulated
      broker-clock lead greater than the margin demonstrates the
      documented, accepted residual false-replay case (a release without a delivery-count increment,
      combined with that clock skew) — pin it as an explicitly accepted case, not a regression
      (FR-005; FR-015)

### Implementation for User Story 3

- [X] T033 [P] [US3] Add `Replay(legacyMargin)` nested record (default `PT5M`) to
      `src/main/java/uk/gov/hmcts/cp/informantregister/config/InformantRegisterProperties.java`
      (depends on: T031)
- [X] T034 [US3] Add the startup check to
      `src/main/java/uk/gov/hmcts/cp/informantregister/config/PropertiesValidator.java`: refuse to
      start if `replay.legacyMargin` is shorter than `servicebus.lockDuration` (depends on: T033,
      T031)
- [X] T035 [US3] In
      `src/main/java/uk/gov/hmcts/cp/informantregister/application/IdempotencyGuard.java`, add the
      legacy-heuristic branch to `replayOrPark`: when
      `record.parkingSequenceNumber()` is `null`, replay only if `delivery.deliveryCount() == 0 &&
      delivery.enqueueTime() != null && delivery.enqueueTime().isAfter(record.updatedAt().plus(
      legacyMargin))`, via the same compare-and-set as T016/T017 (its null-safe half matches `NULL`
      to the observed `null`); otherwise re-park with today's reason for now (US4 replaces it)
      (depends on: T017, T029, T030, T034)

**Checkpoint**: All three P1/P2 stories are independently functional; rows from before this change
are recoverable.

---

## Phase 6: User Story 4 - A re-parked delivery says why the request failed (Priority: P3)

**Goal**: A re-park's dead-letter reason and description are the request's own recorded (or
mapped) category and failure reason, never the generic `DELIVERY_LIMIT_EXHAUSTED`.

**Independent Test**: Park a request with a non-transient failure (e.g. `SUBMISSION_REJECTED`),
trigger a re-park, and read the dead-letter reason/description.

### Tests for User Story 4 (write first) ⚠️

- [X] T036 [P] [US4] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/domain/ReasonCodeTest.java`, `fromCode`
      round-trips every declared `ReasonCode` and returns empty for an unrecognised string
- [X] T037 [P] [US4] Test: new
      `src/test/java/uk/gov/hmcts/cp/informantregister/domain/FailureCategoryMappingTest.java` —
      `SUBMISSION_REJECTED`, `SUBMISSION_NOT_ACCEPTED`, `TRANSFORMATION_FAILED` map to
      `NON_TRANSIENT`; every other declared `ReasonCode` and an arbitrary unrecognised string map to
      `EXHAUSTED` (FR-010)
- [X] T038 [P] [US4] Test: in
      `src/test/java/uk/gov/hmcts/cp/informantregister/persistence/IdempotencyGuardIT.java`'s
      `ParkedRequest` nested class, a re-park of a
      row parked `non-transient`/`SUBMISSION_REJECTED` dead-letters as `non-transient` /
      `SUBMISSION_REJECTED`; a row parked `exhausted` with a transient reason re-parks as `exhausted`
      / that reason (Acceptance Scenarios US4-1, US4-2)
- [X] T039 [US4] Test: in the same `ParkedRequest` nested class as T038 — not parallel with it, same
      file — a re-park of a legacy row (no
      recorded category) uses
      `FailureCategoryMapping` against its stored reason (Acceptance Scenario US4-3). Additionally,
      pin the precedence rule FR-010 states but the agreeing-case tests above don't exercise: a row
      with a **recorded** `parking_category = EXHAUSTED` whose `failure_reason` is
      `SUBMISSION_REJECTED` (which `FailureCategoryMapping` alone would call `NON_TRANSIENT`) must
      still re-park as `exhausted` — the stored value is used as recorded and is never reconstructed
      from the reason code
- [X] T040 [US4] Test: in the same `ParkedRequest` nested class as T038/T039 — not parallel with
      them, same file — a re-park whose stored
      `failure_reason` is missing or unrecognised
      dead-letters as `exhausted` / `UNEXPECTED_FAILURE`, logs a sanitised anomaly that never emits
      the raw stored text, and never throws (Acceptance Scenario US4-4; FR-009)

### Implementation for User Story 4

- [X] T041 [P] [US4] Add `ReasonCode.fromCode(String): Optional<ReasonCode>` to
      `src/main/java/uk/gov/hmcts/cp/informantregister/domain/ReasonCode.java` (depends on: T036)
- [X] T042 [P] [US4] Add
      `src/main/java/uk/gov/hmcts/cp/informantregister/domain/FailureCategoryMapping.java` — a pure,
      total `categoryFor(String storedReasonCode): DeadLetterReason` per FR-010's table (depends on:
      T037)
- [X] T043 [US4] In
      `src/main/java/uk/gov/hmcts/cp/informantregister/application/IdempotencyGuard.java`, update
      both re-park branches in `replayOrPark` (the same-sequence
      case from T017, the legacy-heuristic-fails case from T035) to dead-letter with: category =
      `record.parkingCategory()` if present, else `FailureCategoryMapping.categoryFor(record
      .failureReason())`; detail = `ReasonCode.fromCode(record.failureReason())` if present, else log
      the sanitised anomaly and use `ReasonCode.UNEXPECTED_FAILURE` (depends on: T041, T042, T017,
      T035, T038, T039, T040)

**Checkpoint**: All four user stories are independently functional. The re-park path now reports
the real cause everywhere it occurs.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: The documentation and reference-material updates the spec names as required
(`doc/DEVIATIONS.md` entry text is corrected, never re-litigated) and final validation.

- [X] T044 [P] Correct `doc/DEVIATIONS.md` entry #16's replay-procedure wording ("changing only the
      broker `messageId`" → "optionally changing the broker `messageId`") — a wording maintenance of
      an existing attribution entry; no new deviation entry is needed
- [X] T045 [P] Update `specs/CRA-220-informant-register-initial-poc/spec.md` and
      `specs/CRA-220-informant-register-initial-poc/data-model.md` to reflect the sequence-number
      replay rule and the new columns, per data-model.md's state-transition delta
- [X] T046 [P] Update `.claude/rules/design_rules.md` and `.claude/rules/technical-rules.md`'s
      "replay tooling always mints a fresh messageId" and identity-rule language to match the relaxed
      contract
- [X] T047 [P] Update `.specify/memory/constitution.md` wherever it states the replay rule, bumping
      its version per the amendment procedure (likely MINOR — new behaviour, not a redefinition of an
      existing NON-NEGOTIABLE principle)
- [X] T048 [P] Update the replay-rule checks in `.claude/agents/qa.md`,
      `.claude/agents/code-reviewer.md`, `.claude/agents/spec-validator.md` and
      `.claude/agents/software-engineer.md` to assert sequence-number identity instead of
      `messageId`
- [X] T049 [P] Add a `doc/CHANGELOG.md` entry
- [ ] T050 Run `quickstart.md`'s manual DLQ re-send walkthrough once against the real stack, and its
      legacy-row heuristic walkthrough with a hand-inserted pre-migration-shaped row
- [X] T051 `./gradlew build && ./gradlew pmdMain && ./gradlew jacocoTestReport` — full gate per
      constitution "Development Workflow & Quality Gates"

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup (Principle III: the contract doc change lands before
  any guard code change) — BLOCKS every user story
- **User Stories (Phase 3–6)**: All depend on Foundational completion
  - US1 and US2 (both P1) have no dependency on each other beyond the shared Foundational branch —
    their test tasks can run in parallel; their one implementation task each (T024, T028) touches
    the same method and should not be worked concurrently by two people without coordinating
  - US3 depends on Foundational only, and adds a branch alongside US1/US2's — it does not depend on
    US1 or US2's tasks completing, but its implementation task (T035) edits the same method as T017
  - US4 depends on US2's and US3's re-park branches existing (T017, T035) because it edits them —
    it may start once Foundational is done, but T043 cannot land before T017 and T035 have
- **Polish (Phase 7)**: Depends on all four user stories being complete

### Within Each Phase

- Tests MUST be written, run, and observed to fail before the implementation task they guard
- Foundational's domain-record tasks (T004, T012, T013 — not contiguous; T005–T011 sit between
  them and are the relocated test-fixture task plus the Foundational tests) before the
  repository tasks that read/write them (T014–T016), before the guard rewrite (T017), before
  the listener rewrite (T018–T019)
- Within a story: tests before implementation; story complete before moving to the next priority if
  working sequentially

### Parallel Opportunities

- All Foundational test tasks (T002, T006–T011 — not T003–T005, which are implementation) can
  be written in parallel — different files/classes
- T004, T012, T013 (the three domain-record changes) are parallel — different files
- Once Foundational's checkpoint is reached, US1's and US3's test tasks can run in parallel with
  each other (different files); US2's can too. Implementation tasks T024/T028/T035/T043 all
  eventually touch `IdempotencyGuard.replayOrPark` and should be sequenced by whoever is
  implementing, even though they are written here as separate story phases
- All of Phase 7's documentation tasks (T044–T049) are parallel — different files, no code
  dependency

---

## Parallel Example: Foundational Tests

```bash
Task: "Migration leaves existing rows valid — PostgresTestSupport / a focused migration test"
Task: "IdempotencyGuardIT.ParkedRequest — sequence-number decision table cases"
Task: "FailedReplayIT — concurrent replay race and null-safe compare-and-set cases"
Task: "OutcomeWriteExhaustionTest — stale runner cannot overwrite parking metadata"
Task: "MessageListenerSettlementTest — each broker accessor called at most once"
Task: "MessageListenerSettlementTest — accessor failure still settles exactly once, conservatively"
Task: "QueueSettlementIT — sequence number unchanged across abandon; new one on a re-send"
```

---

## Implementation Strategy

### MVP First (Foundational + User Story 1)

1. Complete Phase 1: Setup (the contract doc change)
2. Complete Phase 2: Foundational — this is the actual defect fix; US1's and US2's acceptance
   scenarios already pass once it's green, so validate both informally even before writing US1/US2's
   own test tasks
3. Complete Phase 3: User Story 1 — adds the FR-008 replay-side log line and its own test coverage
4. **STOP and VALIDATE**: run `quickstart.md`'s manual DLQ walkthrough
5. Deploy/demo if ready — this alone fixes the defect the spec exists to fix

### Incremental Delivery

1. Setup + Foundational → the fix exists and the protection holds
2. Add US1 → logging + dedicated tests → Deploy/Demo (MVP)
3. Add US2 → the protection's own logging + dedicated tests → Deploy/Demo
4. Add US3 → legacy-row recovery → Deploy/Demo
5. Add US4 → honest re-park reasons → Deploy/Demo
6. Phase 7 → documentation parity, full gate

### Notes

- [P] tasks = different files, no dependencies
- Unlike a typical web-service feature, Foundational here is not boilerplate scaffolding — it is the
  feature's core mechanism. Read plan.md's Technical Context before assuming the usual "thin
  Foundational, thick stories" shape applies
- Commit after each task or logical group; never amend a commit whose hook already failed
- Avoid: vague tasks, same-file conflicts without coordination, and — specifically for this feature —
  treating T017/T024/T028/T035/T043 as if they could be implemented by four people simultaneously
  without talking to each other, since they are four edits to one method
