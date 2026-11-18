# Feature Specification: Replay a parked request by broker sequence number

**Feature Branch**: `001-replay-by-sequence-number`
**Created**: 2026-11-18
**Status**: Draft
**Input**: User description: "Replay a parked request by broker sequence number. Today the idempotency guard tells 'a redelivery of the delivery that parked a request' apart from 'a deliberate resubmission' by comparing messageId with the stored exhausted_message_id. A resubmission that reuses the original messageId (which is what the Azure portal's dead-letter 'Re-send' does, since it copies the message as-is) is therefore indistinguishable from a redelivery, so it is re-parked without running and the request cannot be recovered; the re-park also reports DELIVERY_LIMIT_EXHAUSTED, hiding the original cause. Change the guard to identify the parking delivery by the broker-assigned sequenceNumber, handle requests parked before the change, make replay a compare-and-set on the observed generation, make a re-park report the stored failure reason and category, capture broker metadata once inside the settlement boundary, and relax the replay rule in the contract."

## Context

When a request cannot be completed — Results refuses the submission, or the delivery budget runs out
— the service records it as `FAILED` and parks the message on the dead-letter queue. Parking is meant
to be recoverable: the supported way to recover a parked request is to **resubmit** it, at which point
the processed-log moves it from `FAILED` back to `RECEIVED` and runs it again, skipping the
authorities that were already submitted.

To decide whether a delivery of a `FAILED` request is a deliberate resubmission or simply the parking
delivery coming round again (because its dead-lettering was not accepted), the service compares the
delivery's `messageId` with the `messageId` it recorded when it parked the request. Same identity
means "redelivery — park it again without running"; a different identity means "resubmission —
replay it".

That test is the wrong one in practice:

- **The usual resubmission route reuses the original identity.** Producers publish with a
  deterministic `messageId` (`{source}:{requestId}`), and the dead-letter "Re-send" in the Azure
  portal's Service Bus Explorer — the tool support actually has — copies the message as it stands,
  `messageId` included. Every such resubmission is read as a redelivery and parked again without
  running. A parked request therefore cannot be recovered without bespoke tooling that mints a
  fresh `messageId`.
- **The broker does not need a fresh identity.** Duplicate detection only remembers identities for a
  short window after each accepted send, so outside that window the broker accepts a same-identity
  re-send. The service's own guard is the only thing refusing it.
- **The re-park hides the cause.** A delivery parked again is dead-lettered as
  `exhausted / DELIVERY_LIMIT_EXHAUSTED`, whatever the request originally failed with. A request
  that Results rejected reads on the dead-letter queue as though retries had run out, and the real
  reason is only visible in the processed-log.

The broker already provides the right identity: the **sequence number** it assigns when it accepts a
message onto the queue. It is unique within the queue and does not change when the same stored
message is redelivered (after an abandon, a lock expiry, a closed connection or a dead-letter
operation that was not accepted). Every newly **accepted and enqueued** send — portal re-send, replay
tooling or producer republish — receives a new one; a send discarded by duplicate detection receives
none and never reaches the service. This feature makes the sequence number, not the `messageId`, the
test of "is this the delivery that parked the request?".

This is a change to **transport recovery**, which is the sanctioned area of behavioural change for
this service. Register content and the successful path are untouched, so it is not a parity
deviation.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Support recovers a parked request by re-sending it unchanged (Priority: P1)

A support engineer finds a parked message on the dead-letter queue, fixes (or confirms the fix of)
whatever caused it to fail, and uses the dead-letter "Re-send" to put the message back on the queue
exactly as it is — same body, same `messageId`. The service recognises this as a deliberate
resubmission and replays the request.

**Why this priority**: This is the defect. Without it a parked request is effectively unrecoverable
with the tools support has, which defeats the purpose of parking it.

**Independent Test**: Park a request (for example by making the submission fail with a 4xx), wait
until the broker's duplicate-detection window for its `messageId` has passed, re-send the
dead-lettered message unchanged, and observe the request move `FAILED → RECEIVED` and run again.

**Acceptance Scenarios**:

1. **Given** a request recorded `FAILED` with the sequence number of the delivery that parked it,
   **When** a delivery arrives for the same request with the **same `messageId`** but a **different
   sequence number**, **Then** the service treats it as a resubmission: it moves the request
   `FAILED → RECEIVED`, carries the lifetime attempt count forward and increments it once for the
   admitted replay, writes an audit note, and runs it again.
2. **Given** a replayed request whose earlier run had already submitted to some authorities, **When**
   the replay runs, **Then** those authorities are skipped and only the work that failed is repeated.
3. **Given** a request recorded `FAILED`, **When** a delivery arrives with a **fresh `messageId`** and
   a different sequence number, **Then** it is replayed exactly as in scenario 1 — the existing replay
   route keeps working.
4. **Given** a replay is admitted, **When** support searches the logs for the request, **Then** a line
   records the replay with the parked delivery's sequence number and the replaying delivery's sequence
   number (identifiers only, no PII).

---

### User Story 2 - The delivery that parked the current failure is never re-run (Priority: P1)

The protection the current test exists for must survive. The delivery recorded as parking the
**current** `FAILED` generation must not be re-run. If its dead-letter operation is not accepted and
that same stored message returns — after a lost lock or a closed connection — its sequence number
still matches the recorded parking sequence number, so it is parked again without running.

**Why this priority**: Removing the protection would let a request that was deliberately parked run
again on its own, beyond the delivery budget, with nobody having asked for it.

**Independent Test**: Park a request, arrange for the same message (same sequence number) to be
delivered again, and observe that nothing runs and the message is dead-lettered again.

**Acceptance Scenarios**:

1. **Given** a request recorded `FAILED` with the sequence number of the delivery that parked it,
   **When** a delivery arrives carrying **that same sequence number**, **Then** the service runs
   nothing, changes no state, and dead-letters the delivery again.
2. **Given** the same situation, **When** the delivery is re-parked, **Then** exactly one explicit
   settlement attempt is made for it.

---

### User Story 3 - Requests parked before this change can be recovered (Priority: P2)

Requests parked before this change have no sequence number recorded, so the exact test of User
Story 2 is not available for them. Support must still be able to recover them with "Re-send". FR-005
uses the strongest available two-condition heuristic: a delivery failing either condition is
re-parked, and the documented residual false-replay risk is explicitly accepted.

**Why this priority**: Requests already sitting on the dead-letter queue when the change ships are
the first ones support will want to recover.

**Independent Test**: Create a `FAILED` record with no parking sequence number, deliver a newly sent
message for it (first delivery, enqueued well after the request was parked) and observe a replay;
deliver one that fails either condition and observe a re-park.

**Acceptance Scenarios**:

1. **Given** a `FAILED` request with no recorded parking sequence number, **When** a delivery arrives
   whose broker delivery count is 0 **and** whose broker enqueue time is later than the time the
   request was parked by more than the safety margin, **Then** it is treated as a resubmission and
   replayed.
2. **Given** the same request, **When** a delivery arrives that fails either condition (delivery count
   1 or more, enqueue time not later than the parking time plus the margin, or no enqueue time), **Then**
   it is re-parked without running.
3. **Given** a legacy resubmission was handed back before replay admission (for example because the
   store was unavailable, or another replay won the compare-and-set), **When** it later reaches the
   same legacy `FAILED` record with a delivery count of 1 or more, **Then** it is re-parked and support
   sends it again; the new send is judged afresh.

---

### User Story 4 - A re-parked delivery says why the request failed (Priority: P3)

When a delivery is parked again, the dead-letter queue shows the reason the request actually failed
and the category it was originally parked under, not a generic "delivery limit exhausted".

**Why this priority**: It does not block recovery, but it is what support reads first. A request that
Results rejected must not look like a request that ran out of retries.

**Independent Test**: Park a request with a non-transient failure (for example `SUBMISSION_REJECTED`),
trigger a re-park, and read the dead-letter reason and description on the re-parked message.

**Acceptance Scenarios**:

1. **Given** a request parked as `non-transient` with failure reason `SUBMISSION_REJECTED`, **When**
   a delivery for it is re-parked, **Then** the dead-letter reason is `non-transient` and the
   description is `SUBMISSION_REJECTED`.
2. **Given** a request parked as `exhausted` with a transient failure reason, **When** a delivery for
   it is re-parked, **Then** the dead-letter reason is `exhausted` and the description is that
   transient failure reason.
3. **Given** a request with no recorded parking category (parked before this change, or by a consumer
   that predates it), **When** a delivery for it is re-parked, **Then** the category is the one the
   explicit mapping assigns to its stored failure reason.
4. **Given** a request whose stored failure reason is missing or not recognised, **When** a delivery
   for it is re-parked, **Then** it is dead-lettered as `exhausted / UNEXPECTED_FAILURE`, a sanitised
   error records the data anomaly, the unrecognised text is not emitted, and nothing throws.

---

### Edge Cases

- **Two re-sends of the same parked message** (for example a double click): each is a distinct
  accepted enqueue, so each is an independent replay request. If the first replay completes, the
  second is acknowledged as already completed. If the first replay fails and parks again, the second
  replays that newer failure. Both are logged; authorities already submitted are not re-submitted.
- **Two deliveries racing to replay the same failure**: only one replay is admitted. The other finds
  that the record has moved on and is handed back (`REPLAY_NOT_ADMITTED`).
- **A stale replay decision**: a delivery that read the record before it was replayed, run and parked
  again must not replay the newer failure using what it read earlier. Replay only succeeds against the
  exact failure generation the delivery observed.
- **A stale copy of an older parking delivery** returning after a later failure has been recorded has
  a different sequence number from the current parking delivery, so it replays that later failure
  once. This is accepted by the independent-enqueue policy: the run is still subject to the
  single-runner claim and the skip of already-submitted authorities, and it is logged as a replay.
- **Re-send inside the duplicate-detection window**: if a same-`messageId` re-send is made within the
  broker's duplicate-detection window of the last accepted send with that `messageId`, the broker
  silently discards it and the service never sees it. Support must wait for the window to pass, or
  re-send with a fresh `messageId`.
- **Re-send of a request that is not `FAILED`**: a `COMPLETED` request is acknowledged without
  running; a `RECEIVED`/`RETRYING` request follows the existing claim rules. Unchanged.
- **Body edited before re-sending**: any change to the fingerprinted fields is an idempotency
  collision and is dead-lettered as one. Unchanged.
- **Broker metadata cannot be read**: retrieving the message, or reading its sequence number, delivery
  count, `messageId` or lock token, throws or yields nothing. The delivery still receives exactly one
  explicit settlement attempt, through the unexpected-failure route. A missing enqueue time is not a
  failure: it is recorded as absent, logged as `none`, and (for a legacy record only) means re-park.
- **Queue deleted and recreated**: sequence numbers restart. A new message could, in principle, get
  the same sequence number as a recorded parking delivery. That can only cause a false *re-park*
  (visible, recoverable by re-sending again), never a false *replay*.
- **Producer republish of a `FAILED` request** after the duplicate-detection window is now a
  resubmission and replays. This is intended: it is the same unit of work.
- **Re-send not consumed in time**: the queue dead-letters a message whose time-to-live expires. A
  re-send made while no consumer is running, and not picked up before it expires, goes back to the
  dead-letter queue without ever reaching the service; nothing is recorded, and support re-sends it
  once the consumer is back.
- **Rolling deployment**: while an older consumer is still running it can park requests without
  recording the new fields. Those rows are handled exactly like requests parked before the change.

## Requirements *(mandatory)*

### Functional Requirements

**Recording the parking delivery**

- **FR-001**: When the service parks a request (both the non-transient path and the
  delivery-budget-exhausted path), it MUST record, in the same atomic write that sets `FAILED`: the
  broker sequence number of the parking delivery, and the dead-letter category it was parked under
  (`non-transient` or `exhausted`), taken from the failure classification the parking path used. The
  existing record of the parking delivery's `messageId` is kept for audit.
- **FR-002**: The parking sequence number and category MUST be written only by the run that holds the
  claim; a stale runner MUST NOT be able to overwrite them.

**Deciding a delivery of a `FAILED` request**

- **FR-003**: For a `FAILED` request with a recorded parking sequence number, a delivery with the
  **same** sequence number MUST be re-parked without running and without any state change.
- **FR-004**: For a `FAILED` request with a recorded parking sequence number, a delivery with a
  **different** sequence number MUST be treated as a resubmission and replayed, regardless of its
  `messageId`.
- **FR-005**: For a `FAILED` request with **no** recorded parking sequence number, a delivery MUST be
  treated as a resubmission only when **both** hold: its broker delivery count is 0, **and** its broker
  enqueue time is later than the time the request was parked by more than a configured safety margin
  (default 5 minutes, at least the queue's lock duration). Otherwise, including when the enqueue time
  is absent, it MUST be re-parked. Neither condition is proof on its own and the rule is an explicitly
  accepted compatibility heuristic: the broker does not increment the delivery count when a message is
  released by a closed connection, and the enqueue-time test compares broker time with database time.
  A false replay requires both a release without increment **and** a broker clock ahead of the
  database clock by more than the margin. Removing the residual risk entirely would need the exact
  parking sequence numbers backfilled from the dead-letter queue, which is out of scope.
- **FR-006**: A replay MUST be a compare-and-set against the failure generation the delivery observed:
  it succeeds only if the record is still `FAILED` with the same attempt count and the same parking
  sequence number (compared null-safely, so "still none" matches for a legacy record) that were read.
  If it does not succeed, the delivery MUST be handed back with `REPLAY_NOT_ADMITTED`.
- **FR-007**: A replay MUST keep today's semantics: `FAILED → RECEIVED`, lifetime attempt count
  carried forward and incremented, audit note written, parking `messageId`, sequence number and
  category cleared, and already-submitted authorities skipped.
- **FR-008**: The replay and re-park decisions MUST each be logged with the request identifiers, the
  parked delivery's sequence number (or `none`) and the current delivery's sequence number, delivery
  count and enqueue time. No PII.

**Re-park reason**

- **FR-009**: A re-park with a recognised stored failure reason MUST dead-letter with the request's
  category and that reason, and MUST NOT use `DELIVERY_LIMIT_EXHAUSTED` as a substitute for the
  original cause. A missing or unrecognised stored reason MUST dead-letter as
  `exhausted / UNEXPECTED_FAILURE`, MUST log a sanitised error identifying the data anomaly, and MUST
  neither emit the unrecognised text nor throw.
- **FR-010**: When a `FAILED` request has no recorded category — parked before this change, or by an
  older consumer during a rolling deployment — the category MUST come from an explicit, reviewed
  mapping of its stored failure reason, applied when the decision is made (so rows written after any
  migration are covered too). The reasons the non-transient path can record today
  (`SUBMISSION_REJECTED`, `SUBMISSION_NOT_ACCEPTED`, `TRANSFORMATION_FAILED`) map to `non-transient`;
  all others map to `exhausted`. Where a category is recorded, it is used as recorded and never
  reconstructed from the reason code.

**Broker metadata**

- **FR-011**: Retrieving the received message and reading its broker metadata MUST happen once per
  delivery, inside the boundary that guarantees a settlement attempt. Sequence number, delivery count,
  `messageId` and lock token are required; enqueue time is optional and captured as absent when
  missing. Every later use (log context, the delivery identity, the claim, the final-delivery check)
  MUST use those captured values, and no accessor MUST be called more than once.
- **FR-012**: A failure to retrieve the message or to read any required metadata MUST still result in
  exactly one explicit settlement attempt; no such failure may escape before that attempt. Where the
  final-delivery position cannot be determined, the decision is the conservative one: hand the
  delivery back with `UNEXPECTED_FAILURE`. Today both the message retrieval and the delivery-count
  read happen outside that boundary; both move inside it.

**Contract and schema**

- **FR-013**: The replay rule in the inbound contract MUST be changed **before** the code: a fresh
  `messageId` is no longer required by the service. It is still needed by the broker when re-sending
  within the duplicate-detection window of the last accepted send with that `messageId`. Normal
  producer publishing keeps the deterministic `messageId`.
- **FR-014**: The schema change MUST be additive and forward-only. Existing rows stay valid; the
  existing constraint that a `FAILED` request has a parking `messageId` is kept; a `FAILED` row with no
  parking sequence number and no parking category is a valid state (FR-005, FR-010). New `FAILED`
  writes MUST set sequence number and category together, and both MUST be cleared whenever the row
  leaves `FAILED`. The service MUST NOT assume that a migration alone prevents a `FAILED` row without
  the new fields: during a rolling deployment an older consumer can still write one.

**Quality gates**

- **FR-015**: Every requirement above MUST be proven by a test written before the code, covering at
  least:
  - same sequence number re-parks; different sequence number with the **same** `messageId` replays;
  - legacy record: count 0 and enqueued after parking plus margin replays; count ≥ 1 re-parks;
    enqueued before or within the margin re-parks; no enqueue time re-parks; a legacy parking-message
    redelivery released with count 0 but enqueued before parking re-parks; a legacy resubmission
    handed back once is re-parked on its next delivery; an enqueue time exactly equal to the parking
    time plus the margin re-parks; a configured margin shorter than the queue's lock duration is
    rejected; a simulated broker clock lead greater than the margin demonstrates the accepted
    residual false-replay case;
  - two concurrent re-sends admit one replay; the FAILED→RECEIVED→FAILED race cannot replay a newer
    failure; the compare-and-set matches a null parking sequence correctly;
  - the independent-enqueue policy after a first replay has produced a newer failure;
  - a stale runner cannot overwrite the parking metadata;
  - a re-park carries the recorded category and reason; a missing category uses the mapping; a missing
    or unrecognised reason uses `exhausted / UNEXPECTED_FAILURE` without throwing;
  - each broker accessor is called at most once; a failure of message retrieval or of any required
    accessor still makes exactly one settlement attempt; an absent enqueue time continues normally;
  - the migration leaves existing rows valid;
  - against the broker emulator: the sequence number is unchanged across an abandon, and a re-send
    gets a new one.

### Key Entities

- **Processed request** (`processed_request`): gains the **parking sequence number** (the broker
  sequence number of the delivery that parked it) and the **parking category** (`non-transient` or
  `exhausted`). Both are set together with `FAILED` by this version, may both be absent on a row
  parked otherwise, and are cleared on replay. The existing parking `messageId`, failure reason,
  attempt count, audit note and update time keep their current meaning; for a `FAILED` row the update
  time is the time it was parked.
- **Delivery identity**: the facts about one delivery that the guard decides on. It gains the sequence
  number, delivery count and (optional) enqueue time captured from the broker, alongside the
  `messageId` and claim owner it carries today.
- **Dead-letter reason**: unchanged set (`validation`, `collision`, `exhausted`, `non-transient`); a
  re-park now uses the request's category instead of always choosing `exhausted`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a request parked by this version, every accepted re-send carrying a different
  sequence number is replayed, whatever its `messageId`. For a request parked before this version, an
  unchanged re-send is replayed when it reaches the decision on its first delivery and was enqueued
  after the parking time plus the margin; otherwise support sends it again.
- **SC-002**: No run is started by a delivery whose sequence number equals the parking sequence
  number recorded for the current `FAILED` generation.
- **SC-003**: No authority is submitted twice as a result of a replay, a re-send or a race between
  them, in normal operation.
- **SC-004**: Every re-park with a recognised stored failure reason shows that reason and its
  category on the dead-letter queue. A missing or unrecognised reason shows
  `exhausted / UNEXPECTED_FAILURE` and never causes a delivery to escape unsettled.
- **SC-005**: A support engineer can recover a parked request using only the dead-letter "Re-send",
  with no message edits and no database changes.
- **SC-006**: Every delivery, including one whose broker metadata cannot be read, receives exactly one
  explicit settlement attempt; no second attempt is made if the broker refuses the first.

## Out of Scope

- Why Results rejects a submission. A replay only succeeds if the cause has been fixed; this feature
  makes the replay possible, not successful.
- Network access and role assignments needed to use the portal against a given namespace.
- Clearing old copies off the dead-letter queue after a successful replay.
- Backfilling exact parking sequence numbers for requests parked before this change.
- A replay or status API. Replay remains "re-send the message".
- Any change to the outbound `add-informant-register` command or to register content.

## Documents the implementation must update

Not edited in this specification's PR. The implementation updates them, with the contract first:

- `doc/API_CONTRACTS.md` — the replay rule and the `messageId` property note.
- `doc/DEVIATIONS.md` entry 16 — its description of the replay procedure ("changing only the broker
  `messageId`") must allow an unchanged `messageId`. This is maintenance of an existing attribution
  entry; no new parity entry is needed.
- `specs/CRA-220-informant-register-initial-poc/spec.md` (the replay and redelivery requirements and
  success criteria) and `data-model.md` (columns, constraint notes, the state-transition table).
- `.claude/rules/design_rules.md` and `.claude/rules/technical-rules.md` — "replay tooling always mints
  a fresh `messageId`" and the identity rule.
- `.specify/memory/constitution.md`, where it states the replay rule.
- `.claude/agents/` definitions that check the replay rule (`qa`, `code-reviewer`, `spec-validator`,
  `software-engineer`).
- The quickstart and any replay-tooling notes that tell operators to mint a fresh `messageId`.
- `doc/CHANGELOG.md`.
- Tests that assert identity by `messageId` (`FailedReplayIT`, `IdempotencyGuardIT`,
  `MessageListenerSettlementTest`, `DeliveryExhaustionIT`) and end-to-end helpers that find messages
  by `messageId`, which become ambiguous once several accepted sends can share one.
- The Confluence design page — flagged to its owner, not edited as part of this work.

## Assumptions

Each assumption below was checked against the broker documentation, the queue's infrastructure
definition or the code before this specification was written.

- **Sequence numbers.** The broker assigns a sequence number when it accepts a message; it is unique
  within the entity, and a message resubmitted from the dead-letter queue receives a new sequence
  number and enqueue time. Redelivery does not re-enqueue, so it keeps the number.
- **Delivery count.** The broker increments the delivery count when a lock expires or a message is
  abandoned, but **not** when a message is released because its connection closed. The service
  closes its connection on every stop. FR-005 is written with that in mind.
- **Queue shape.** The queue is not partitioned (neither the queue nor its namespace), has no
  sessions, and neither forwards messages nor forwards its dead letters elsewhere — so a message has
  exactly one sequence number on this queue. For a partitioned queue sequence numbers would still be
  unique and usable for equality, but must not be used to order messages.
- **Duplicate detection and timings.** The queue's duplicate-detection window is 5 minutes, its lock
  duration 5 minutes, its delivery limit 5, and its time-to-live 1 hour with dead-lettering on expiry.
  The legacy margin (FR-005) is therefore at least the lock duration. If the duplicate-detection window
  were lengthened, same-`messageId` re-sends would be discarded for longer, and support would need a
  fresh `messageId` within it.
- **Portal re-send.** The portal's dead-letter re-send copies the `messageId`, and a message re-sent
  that way can be read normally by the service, sequence number included.
- **Dead-letter copies.** A dead-lettered message keeps the sequence number and enqueue time it had on
  the queue, so the dead-letter queue shows which delivery was parked.
- **Generation.** While a row is `FAILED`, nothing in the service changes its attempt count or update
  time; every path back out of `FAILED` and in again increments the attempt count. That is what makes
  the attempt count a sufficient generation for FR-006, and the update time the parking time for
  FR-005.
- **Classification.** The category of a failure is decided by the parking path's failure
  classification, not by its reason code: the typed non-transient failures are classified where they
  are raised, and an unexpected failure is classified transient by the pipeline.
- **Policy.** A deliberate resubmission is any accepted new send. The service does not try to work out
  *who* sent it.
