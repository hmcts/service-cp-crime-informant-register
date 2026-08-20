# Feature Specification: Informant Register Service — Initial POC (walking skeleton)

**Feature Branch**: `CRA-220-informant-register-initial-poc`
**Created**: 2026-08-20
**Status**: Draft
**Input**: User description: "CRA-220 Informant register — Initial POC. Create the informant register service walking skeleton: consume distribution requests from the dedicated queue with reliable settlement, guard against duplicate processing with a durable processed-log, define the ports for payload fetch and register submission with stub adapters, expose service health, and produce a runnable container. The ported transformation pipeline and the real payload/submission adapters are later stories."

## Context

The informant register is the daily record of hearing outcomes sent to prosecuting authorities.
Today it is produced by a function app that is triggered by an event broadcast, has no retries, no
dead-letter parking and no alerting — a register that fails to be produced is lost silently. This
service replaces that trigger with a dedicated request queue and makes every failure visible,
retryable and recoverable.

This increment is deliberately a **walking skeleton**: the thinnest end-to-end slice that proves the
delivery machinery — receive a request, guard it against duplicates, run it through a (stubbed)
processing pipeline, record the outcome, and settle the message correctly. The business
transformation (building register documents and submitting them to the Results context) is ported in
later stories behind the ports this increment defines. A stub is the agreed shape of this increment,
not a gap.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A distribution request is received, processed and settled reliably (Priority: P1)

The Results context announces each resulted hearing by placing a small request message on the
service's dedicated queue. The service picks the request up and checks it against the agreed
contract. Only when that check passes does the request enter the recorded state machine: it is
recorded as received, run through the processing pipeline (stubbed in this increment), its outcome
is recorded, and the message is acknowledged so it leaves the queue.

**Why this priority**: This is the walking skeleton's spine — without reliable receipt, recording
and acknowledgement, nothing else in the delivery can be built or demonstrated.

**Independent Test**: Place a single valid request on the queue and observe: the request is recorded
as received and then completed, the processing pipeline ran once for it, and the message is gone
from the queue (neither redelivered nor dead-lettered).

**Acceptance Scenarios**:

1. **Given** an empty processed-log and a running service, **When** a valid request message arrives
   on the queue, **Then** the service validates it against the contract, records the request as
   RECEIVED keyed by its source and request id, runs the processing pipeline once, records the
   outcome, and acknowledges the message.
2. **Given** the stubbed pipeline produces no per-authority output, **When** processing completes,
   **Then** the request is recorded as COMPLETED with the recorded reason `no-authorities`, and no
   error is raised — an empty result is a legitimate outcome, not a failure.
3. **Given** a valid request has been processed, **When** the service is restarted, **Then** the
   record of that request survives the restart (the processed-log is durable, not in-memory).

---

### User Story 2 - A duplicate or redelivered request never causes duplicate processing in normal operation (Priority: P1)

Requests can legitimately arrive more than once: the producer may republish the same request, the
queue may redeliver after a timeout, or support may resubmit a parked message. The service must
recognise a request it has already completed and acknowledge it without running the pipeline again.

The guarantees the service actually offers, stated plainly:

- at most one pipeline run is in flight for a request at any moment;
- a request recorded COMPLETED is never run again;
- if the service crashes after a run finishes but before its outcome is durably recorded, the
  redelivered message causes a further, sequential run — and if the crash repeats in that same
  window, so does the run. What is guaranteed is not a bounded number of runs but their shape: never
  two runs at once, and never any run once COMPLETED has been durably recorded. This is the accepted
  crash window recorded in Assumptions.

**Why this priority**: The downstream submission (in later stories) creates a duplicate register row
per duplicate call. The duplicate guard is the reason this service has a processed-log at all, and
it must be proven before any real submission adapter is attached.

**Independent Test**: Deliver the same request (same source and request id) twice and observe a
single pipeline run; the second delivery is acknowledged with no new processing.

**Acceptance Scenarios**:

1. **Given** a request already recorded as COMPLETED, **When** a message with the same source and
   request id is delivered again, **Then** the service acknowledges it without invoking the pipeline
   and the processed-log is unchanged.
2. **Given** a request whose record is non-terminal and whose single-runner claim is held by a live
   runner, **When** a second delivery of the same request arrives concurrently, **Then** the
   competing delivery is returned to the queue for retry and is never acknowledged, so only one
   pipeline run is ever in flight; a delivery is acknowledged without a pipeline run only when the
   record is COMPLETED.
3. **Given** the queue's duplicate-detection window, **When** the producer republishes a message
   with the same message identity within that window, **Then** the broker itself discards the
   duplicate and the service never sees it (broker-level dedupe is the first line of defence; the
   processed-log is the second).

---

### User Story 3 - A failing request is retried, then parked visibly, and can be resubmitted (Priority: P2)

When processing a request fails (in this increment, a simulated pipeline failure), the service must
not lose it and must not acknowledge it as done. The request is returned to the queue for retry;
after the delivery limit for that message is exhausted, it is parked on the dead-letter queue where
support can see it. Support can resubmit a parked request under a fresh message identity and the
service will process it afresh.

Retry exhaustion is judged by the current message's delivery count against the queue's limit of 5,
never by the request's cumulative attempt count. The attempt count is a lifetime tally of pipeline
runs started for the request, so five failed deliveries followed by one successful replay leave the
record showing 6 attempts.

**Why this priority**: "Failure is loud, retried and recoverable" is the single behavioural
improvement this migration delivers over the function app. It must exist from the first increment,
but it depends on the P1 spine being in place.

**Independent Test**: Deliver a request that the stub pipeline is configured to fail; observe
repeated redelivery with the failure recorded each time, then the message parked on the dead-letter
queue after the fifth delivery with the request recorded as FAILED; resubmit it under a fresh
message identity and observe successful reprocessing.

**Acceptance Scenarios**:

1. **Given** a request whose processing fails with a transient processing failure — in this
   increment the simulated pipeline failure, induced solely by test-controlled configuration of the
   stub adapters and never by a field in the message or any HTTP endpoint — **When** the delivery
   fails, **Then** the message is returned to the queue for redelivery (not acknowledged, not parked
   yet), and the request is recorded as RETRYING with its cumulative attempt count and the failure
   reason.
2. **Given** a request that keeps failing, **When** its fifth delivery fails (the queue's
   configured delivery limit for that message), **Then** the message is parked on the dead-letter
   queue and the request is recorded as FAILED with the final failure reason and the identity of the
   delivery that exhausted the limit.
3. **Given** a request recorded as FAILED, **When** a deliberate resubmission for the same source
   and request id arrives under a message identity different from the one recorded as having
   exhausted the retries, **Then** the guard transitions the record from FAILED back to RECEIVED
   (preserving the cumulative attempt count, with an audit note recording the resubmission) and the
   pipeline runs again.
4. **Given** a request recorded as FAILED, **When** a redelivery arrives carrying the same message
   identity that exhausted the retries (for example, dead-lettering did not settle and the delivery
   lock expired), **Then** the record stays FAILED, the pipeline does not run, and dead-lettering is
   attempted again.
5. **Given** a message whose body is not a valid request (malformed, missing required fields,
   unknown fields, or values outside the agreed contract), **When** it is delivered, **Then** the
   service parks it on the dead-letter queue immediately with a sanitised reason describing the
   validation failure, without creating any processed-request record — such a message may not carry
   a usable source and request id — and records an ERROR log and a failure metric instead. Retrying
   a message that can never validate is pointless and would waste the delivery limit.

---

### User Story 4 - The service is observable and deployable (Priority: P2)

Operations staff can see whether the service is alive and ready, run it as a container, and rely on
it degrading gracefully: an unreachable queue must not make the service report itself broken (it
cannot heal the queue by restarting).

**Why this priority**: CRA-220's stated purpose is proving the deployment pipeline groundwork
(STE through to production). A skeleton that cannot be run as a container with meaningful health
reporting proves nothing.

**Independent Test**: Build the container image, start it with its local dependencies, and check the
health endpoints; then stop the queue dependency and observe the service stays up and ready while
reporting the queue connection state.

**Acceptance Scenarios**:

1. **Given** the built container image, **When** it is started with its local dependencies
   available, **Then** the service reports itself live and ready via its health endpoints.
2. **Given** a running service, **When** the queue becomes unreachable, **Then** the service remains
   live and ready (queue connectivity must never gate readiness), reports the queue's state through
   a separate health indicator and metric, continues serving health endpoints, and resumes consuming
   when the queue returns.
3. **Given** the service is started while the queue is unavailable, **When** its health endpoints
   are checked, **Then** the service still becomes ready (readiness is gated by the processed-log
   store, not by the queue), reports the queue as down through the separate queue health indicator,
   and begins consuming as soon as the queue becomes available, with no restart.
4. **Given** a request processed end-to-end, **When** its logs, exception messages and metric labels
   are inspected, **Then** the request id, hearing id and hearing day are present for correlation,
   and no defendant-identifying information appears at INFO level and above.

---

### Edge Cases

- **Crash in the instant between pipeline completion and recording the outcome**: the message is
  redelivered and the request reprocessed — this is the accepted at-least-once crash window (see
  Assumptions); with the stub pipeline it is harmless, and with the real submission adapter the
  duplicate is absorbed downstream.
- **Unknown fields in an otherwise valid message**: a contract-validation failure (the contract is
  closed — unknown fields indicate a producer/consumer contract drift that must surface loudly, not
  be ignored) → dead-lettered immediately with a sanitised reason, no retries consumed and no
  processed-request record written.
- **`source` or `eventType` outside the agreed values**: validation failure → dead-letter
  immediately (scenario US3-5).
- **Resubmission that reuses the original message identity within the duplicate-detection window**:
  silently discarded by the broker — the resubmission runbook must use a fresh message identity
  (recorded operational note; the service cannot detect this case).
- **Processed-log store unavailable**: store availability is a precondition checked before a
  delivery is validated or processed, so the current delivery is returned to the queue for retry
  (never acknowledged, never dead-lettered as poison) and intake from the queue is suspended until
  the store returns. Suspension is what stops an outage burning through the delivery limit.
- **Processed-log store unavailable across many retry intervals**: the message is still recoverable
  when the store returns — it must not have been parked on the dead-letter queue in the meantime,
  and the request is processed normally once intake resumes.
- **Contract-invalid message that arrives during a store outage**: it is not examined while the
  store is down (availability is checked first); when the store returns it is validated and
  dead-lettered as normal.
- **Crash after RECEIVED is written but before the run completes**: the abandoned single-runner
  claim expires, so the next delivery reclaims the request and reprocesses it. If instead a live
  runner still holds the claim, the competing delivery is returned for retry rather than
  acknowledged.
- **Same (source, request id) arriving with different immutable request fields**: an idempotency
  collision, not a duplicate — the message is dead-lettered visibly with a reason, the original
  record is left untouched, and the pipeline does not run.
- **Message for a request recorded as RETRYING arrives after a long gap** (e.g. queue redelivery
  after the service crashed mid-processing): treated as a fresh attempt of a non-terminal request —
  the pipeline runs again under the guard's single-runner rule.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST consume request messages from its dedicated request queue and settle
  every delivery explicitly: on every path the handler controls, and while the delivery lock is
  still valid, it MUST make exactly one settlement attempt — acknowledge (done), return-for-retry,
  or park on the dead-letter queue. No delivery may be left to time out by default, and no failure
  may be silently swallowed. The edge cases where a settlement attempt itself fails are covered by
  FR-016.
- **FR-002**: The service MUST validate every message body against the agreed request contract: the
  six fields `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType` — all
  required, no unknown fields, `source` and `eventType` restricted to their agreed values,
  identifiers and dates in their agreed formats.
- **FR-003**: Messages that fail contract validation MUST be parked on the dead-letter queue
  immediately, with a recorded sanitised reason, without consuming retry attempts and without
  creating a processed-request record. A contract-invalid message may not carry a usable source and
  request id, so it is accounted for by its dead-letter entry, an ERROR log and a failure metric
  rather than by the processed-log.
- **FR-004**: The service MUST maintain a durable processed-log keyed by (source, request id),
  recording for each request its state, cumulative attempt count, timestamps, failure reason (when
  any) and the identity of the delivery that exhausted the retry limit (once one has). `attempts` is
  the lifetime cumulative count of pipeline-run starts for the request, incremented atomically when
  a run begins. It MUST NOT be incremented for a validation rejection, for a delivery returned
  because the processed-log store was unavailable, or for a duplicate settled without a run. Retry
  exhaustion is judged by the current message's broker delivery count against the limit of 5, never
  by the cumulative attempt count: five failed deliveries followed by one successful replay leave
  the record showing `attempts` = 6. Per-authority output records are defined by the same log, but
  none are written in this increment because the stub pipeline produces no outputs.
- **FR-005**: The request state machine begins only on successful contract validation — a message
  that fails validation never enters it and gets no record (FR-003). From there, state MUST follow
  the agreed transitions:

  | From | To | When |
  |------|----|------|
  | (no record) | RECEIVED | first validated delivery of the request |
  | RECEIVED | RETRYING | processing failure with deliveries of the message remaining |
  | RETRYING | RETRYING | further processing failure with deliveries remaining |
  | RECEIVED or RETRYING | COMPLETED | processing succeeds; an empty output set completes the request with the recorded reason `no-authorities` |
  | RECEIVED or RETRYING | FAILED | processing failure on the final permitted delivery |
  | FAILED | RECEIVED | only on a deliberate resubmission under a different message identity (FR-007) |
  | COMPLETED | — | terminal: no outbound transitions |

- **FR-006**: A delivery for a request already COMPLETED MUST be acknowledged without reprocessing.
- **FR-007**: A delivery for a request recorded FAILED MUST be handled according to the identity of
  the delivering message. When that identity differs from the one recorded as having exhausted the
  retry limit, the delivery is a deliberate resubmission: the record transitions FAILED → RECEIVED
  (cumulative attempt count preserved, with an audit note) and is reprocessed. When the identity is
  the same one that exhausted the limit — for example, dead-lettering did not settle and the
  delivery lock expired — the record MUST stay FAILED, the pipeline MUST NOT run, and dead-lettering
  MUST be attempted again.
- **FR-008**: For any single request, at most one pipeline run may be in flight at a time,
  regardless of concurrent deliveries. The single-runner claim MUST be taken atomically and MUST
  carry an expiry or equivalent liveness marker, so a claim abandoned by a crashed runner can be
  reclaimed by a later delivery. While the record is non-terminal and a live runner holds the claim,
  a competing delivery MUST be returned for retry and MUST NOT be acknowledged; a delivery is
  acknowledged without a pipeline run only when the record is COMPLETED.
- **FR-009**: On a processing failure, the service MUST record the failure (state RETRYING,
  cumulative attempt count, sanitised reason) and return the message for redelivery; on the final
  permitted delivery's failure it MUST record FAILED, with the identity of that delivery, and park
  the message. In this increment the simulated pipeline failure is a transient (retryable)
  processing failure and is induced solely by test-controlled configuration of the stub adapters —
  never by a field in the message and never through an HTTP endpoint. Contract-validation failure
  (FR-003) is the only non-transient, immediately parked path in this increment.
- **FR-010**: The processing pipeline MUST be invoked through defined ports (payload fetch; register
  submission) so later stories can attach real adapters without changing the skeleton. In this
  increment both ports are served by stub adapters that log their invocation and return fixed
  results; the happy path produces no authorities and therefore invokes no submission at all.
- **FR-011**: The service MUST expose liveness and readiness health reporting. The processed-log
  store MUST gate readiness, because processing is unsafe without it. Queue connectivity MUST NOT
  gate readiness, but MUST be observable as a separate health indicator and metric.
- **FR-012**: The service MUST log each request's progress with the request id, hearing id and
  hearing day as correlation fields, and MUST NOT log defendant-identifying information at INFO
  level and above. Whole message or document payloads MUST NOT be logged at any level in a deployed
  environment, and secrets MUST NOT appear at any level.
- **FR-013**: The service MUST be buildable into a container image that starts and reports healthy
  with only its declared local dependencies, and the repository MUST carry the build pipeline
  definitions needed for continuous integration.
- **FR-014**: The service exposes no business HTTP surface: only operational actuator endpoints
  (health/liveness/readiness, info, metrics, Prometheus) are exposed; there is no business API and
  no replay endpoint — replay is by resubmitting parked messages.
- **FR-015**: Processed-log store availability is a precondition, checked before a delivery is
  validated or processed. When the store is unavailable, the current delivery MUST be returned for
  retry — never acknowledged and never dead-lettered — and the service MUST suspend intake from the
  queue until the store recovers, so a store outage cannot burn through the delivery limit and cause
  the broker to dead-letter recoverable work. Because availability is checked first, a
  contract-invalid message that arrives during an outage is not examined until the store returns; it
  is then validated and dead-lettered as normal.
- **FR-016**: Settlement MUST stay unambiguous at its edges. If the acknowledgement fails after
  COMPLETED has been recorded, the record stays COMPLETED and the broker's redelivery is
  acknowledged without any work being repeated. If dead-lettering fails after FAILED has been
  recorded, the redelivery is handled per FR-007 — same message identity, so the record stays FAILED,
  no pipeline run occurs, and dead-lettering is attempted again. Loss of the delivery lock MUST be
  logged and counted, and recovery then relies on the broker redelivering the message.
- **FR-017**: Every failure path — processing failure, contract-validation dead-letter, store-outage
  suspension, settlement failure and lock loss — MUST emit a sanitised ERROR log carrying the
  correlation fields available for that message, and MUST increment a failure metric; dead-lettered
  messages MUST be countable from a metric. Wiring those signals into dashboards and alert rules is
  deferred to the later operability story and is recorded as a waiver in the deviations register.
- **FR-018**: The processed-request record MUST hold a fingerprint of the request's immutable fields
  (`hearingId`, `hearingDay`, `sharedTime`, `eventType`). A delivery whose (source, request id)
  matches an existing record with a matching fingerprint is a duplicate and is handled by the rules
  above. A delivery whose (source, request id) matches an existing record with a DIFFERENT
  fingerprint is an idempotency collision: it MUST be dead-lettered visibly with a reason, the
  original record MUST NOT be overwritten, and the pipeline MUST NOT run.

### Key Entities

- **Distribution request (queue message)**: the unit of work. Six fields: `source` (producing
  context, agreed value "RESULTS"), `requestId` (deterministic identifier of this share — the
  idempotency key), `hearingId`, `hearingDay`, `sharedTime` (which hearing/share it concerns),
  `eventType` (agreed value "Hearing_Resulted"). The contract is closed: unknown fields are a
  contract breach. Jointly owned with the producing context.
- **Processed request record**: one per (source, request id), created only once a delivery has
  passed contract validation. Holds state (RECEIVED/RETRYING/COMPLETED/FAILED), the cumulative
  attempt count (FR-004), received/updated timestamps, failure reason, completion reason (e.g.
  `no-authorities`), the fingerprint of the request's immutable fields (FR-018) and the identity of
  the delivery that exhausted the retry limit (FR-007). The durable memory that makes the service
  idempotent.
- **Processed output record**: zero or more per request; one per prosecuting authority output
  produced. Its schema is created in this increment, but no records are written because the stub
  pipeline produces no outputs; the replay/skip semantics (which outputs were already submitted, so
  a replayed request skips them) are specified with the real submission stories.
- **Queue contract (externally provisioned)**: dedicated request queue plus dead-letter queue;
  competing-consumer delivery with per-delivery locks; delivery limit of 5; broker duplicate
  detection keyed on message identity derived as `source:requestId`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero silent loss: in any test run, every message placed on the request queue is
  accounted for as exactly one of — recorded COMPLETED; recorded FAILED with the message parked on
  the dead-letter queue; parked as contract-invalid with a sanitised reason, an ERROR log and a
  failure metric but no processed-request record; actively in flight — currently locked and being
  processed, with a corresponding non-terminal record; or still queued or in retry. No message
  disappears without an account of it.
- **SC-002**: Zero duplicate processing in uninterrupted normal operation: with no crash between a
  pipeline run and the recording of its outcome, delivering the same request N times (N ≥ 2,
  including concurrent deliveries) produces exactly one pipeline run and one completion record. A
  crash in the accepted window (see Assumptions) may cause a further sequential run, and repeated
  crashes in that window may cause repeated sequential runs; the criterion is not a cap on the
  number of runs but that no two runs for a request are ever concurrent, and that no run occurs once
  COMPLETED has been durably recorded.
- **SC-003**: A persistently failing request is parked after exactly 5 deliveries of that message,
  with its state, cumulative attempt count and final reason readable from the processed-log, and is
  successfully reprocessed after one resubmission under a fresh message identity — leaving the
  record showing 6 cumulative attempts.
- **SC-004**: The service, started as a container with its local dependencies, reports ready within
  60 seconds; with the queue stopped it remains ready and resumes consumption within 60 seconds of
  the queue returning, with no restart.
- **SC-005**: A reviewer can trace any single request end-to-end from logs alone using the request
  id — from receipt to settlement — without access to the database.
- **SC-006**: The walking skeleton is demonstrable end-to-end on a development machine with one
  documented command sequence (dependencies up, service up, message sent, outcome observed).

## Out of Scope (this increment)

- The ported register-building pipeline (payload fetch, register fragments, subscription matching,
  outbound document mapping) — later stories behind the ports defined here.
- The real payload-fetch adapter (hearing payload cache/query) and the real submission adapter
  (POST to the Results context) — stubs only in this increment.
- The Results-side publisher change (separate ticket in the Results context).
- Queue/infrastructure provisioning, deployment wiring outside this repository (separate platform
  tickets), and the GitHub remote (local git only until naming is confirmed).
- Alert wiring — dashboards and alert rules over the failure and dead-letter metrics — deferred to
  the later operability story and recorded as a waiver in the deviations register. FR-017 still
  requires the ERROR logs and the metrics themselves in this increment.
- Any behaviour change to the register content, the daily CSV leg, or SJP hearings.

## Assumptions

- **Delivery contract (agreed, honest)**: at-most-once submission per request in all normal
  operation, including redeliveries and replays. Across a crash in the instant between a successful
  submission and recording it, delivery is at-least-once — the duplicate is absorbed downstream
  exactly like a re-share. Strict at-most-once is impossible without idempotency in the downstream
  intake, which is out of scope (frozen contract). An ambiguous submission outcome is retried: a
  possible absorbed duplicate is preferred over possible loss.
- The dedicated request queue, its dead-letter queue, delivery limit 5 and duplicate detection are
  provisioned externally per the agreed design; locally they are emulated by the declared
  development dependencies.
- `requestId` is minted deterministically by the producer from the share's identity, so a republish
  of the same share carries the same id, while a genuine re-share carries a new one and is
  legitimately processed again.
- Resubmission of parked messages uses existing queue tooling with a **fresh message identity**
  (operational runbook note — a resubmission reusing the original identity within the
  duplicate-detection window is silently discarded by the broker).
- The processed-log lives in a durable store owned by this service; its technology is a planning
  decision, not part of this specification.
- Message throughput is modest (per resulted hearing, hundreds per day); no volume-driven
  requirements beyond the correctness guarantees above.
- This specification is bounded by the agreed Option 2 design (live Confluence dev page is the
  design master); the constitution's parity and TDD principles govern how it is built.
