# Quickstart — Replay a parked request by broker sequence number

This feature has no new runtime switch and no new endpoint — there is nothing to turn on. What
changes is how an already-`FAILED` request responds to a re-send. This quickstart is about
*proving* that, locally and in CI, since the whole point of the defect being fixed is that it was
invisible without a real broker.

## Prerequisites

Same as the CRA-220 quickstart: Java 25, Docker running, nothing else — the broker and database
are containers (`docker/servicebus-emulator/config.json`, mounted by both `docker-compose.yml` and
`ServiceBusEmulatorTestSupport`).

## Running the feature's own test suites

```bash
# Unit-level: the guard's decision table, against the real Postgres-backed repository fixture.
./gradlew test --tests 'uk.gov.hmcts.cp.informantregister.persistence.FailedReplayIT'
./gradlew test --tests 'uk.gov.hmcts.cp.informantregister.persistence.IdempotencyGuardIT'

# Listener-level: settlement discipline, broker-metadata-read-once, the re-park reason/category.
./gradlew test --tests 'uk.gov.hmcts.cp.informantregister.inbound.MessageListenerSettlementTest'

# Against a real emulator: sequence-number behaviour across abandon/redeliver/exhaustion,
# and (this feature) that a re-send gets a new sequence number while a redelivery keeps the old one.
./gradlew test --tests 'uk.gov.hmcts.cp.informantregister.e2e.DeliveryExhaustionIT'
./gradlew test --tests 'uk.gov.hmcts.cp.informantregister.e2e.QueueSettlementIT'

# Against a real emulator, the defect itself: five real deliveries park the request, an unchanged
# re-send (same messageId, same body) after the duplicate-detection window elapses recovers it to
# COMPLETED. Runs against a second, short-window test queue — see the suite's own javadoc for why.
./gradlew test --tests 'uk.gov.hmcts.cp.informantregister.e2e.UnchangedResendRecoveryIT'
```

All five need Docker; the first two do not need the Service Bus emulator specifically (they drive
the guard and the repository directly against Testcontainers Postgres), the last three do.

## Manually proving the fix end to end (what support will actually do)

The defect this feature fixes is specifically about the **Azure portal's dead-letter "Re-send"**,
which this quickstart cannot drive directly — there is no portal against the local emulator. The
emulator does support receiving from, and sending back onto, the dead-letter sub-queue
(`com.azure.messaging.servicebus.models.SubQueue.DEAD_LETTER`, already used by
`ServiceBusEmulatorTestSupport`), which is the same operation the portal's button performs — copy
the body, including `messageId`, from the DLQ back onto the live queue.

1. Bring the stack up and run the service (see the CRA-220 quickstart's "Interactive local run" for
   the exact environment variables).
2. Send a distribution request, then force it to fail non-transiently (five transient failures, or
   one simulated non-transient one via
   `--informantregister.stub.payload-failure-mode=TRANSIENT`/the submission-failure stub, matching
   whichever failure path you want to exercise) until `processed_request.status = 'FAILED'`. Note
   the row's `parking_sequence_number` and `parking_category`.
3. Using a short AMQP client (or the pattern in `ServiceBusEmulatorTestSupport`), receive the
   message from the queue's dead-letter sub-queue and re-send its body — **byte for byte, same
   `messageId`** — onto `informantregister.requests`.
4. **Before this feature**: the service dead-letters it again, under `exhausted` /
   `DELIVERY_LIMIT_EXHAUSTED`, and the row stays `FAILED` unchanged. This is the defect.
5. **After this feature**: because the broker accepted and enqueued the re-send, it carries a
   **new sequence number**, different from the one recorded on the row. The service treats it as a
   resubmission: `processed_request.status` moves to `RECEIVED`, then (assuming the underlying
   cause is fixed, or the fault was transient) to `COMPLETED`, and the dead-letter entry from step 2
   is the last one for this request.
6. Repeat step 3 **without waiting**, immediately after step 2, and the broker's own
   duplicate-detection window (`PT5M` in `docker/servicebus-emulator/config.json`) silently
   discards the second re-send — nothing arrives at the service at all. This is unchanged by this
   feature and is the one case where a fresh `messageId` still helps (see
   `contracts/replay-rule-delta.md`).

## Proving the legacy-row heuristic (User Story 3)

A row parked by the code **before** this feature has `parking_sequence_number IS NULL`. The
cleanest local reproduction is to insert such a row directly (or temporarily point at a database
that already has one from before the migration), then re-send:

- A re-send whose delivery count is 0 and whose enqueue time is safely past
  `updated_at + informantregister.replay.legacy-margin` (default 5 minutes) replays.
- A re-send that arrives sooner, or whose delivery count is already ≥ 1 for any reason, re-parks —
  this is the accepted residual false-*re-park* case the spec documents, never a false replay.

## Full verification before commit

Unchanged from the CRA-220 quickstart:

```bash
./gradlew build              # compile + entire test suite (unit + *IT; Docker needed for the ITs)
./gradlew pmdMain            # PMD — explicit, not part of build
./gradlew jacocoTestReport   # coverage report
```

Additionally for this feature, because it touches a reviewed attribution entry and several
cross-referenced docs (see the spec's "Documents the implementation must update"): confirm
`doc/API_CONTRACTS.md`, `doc/DEVIATIONS.md` #16, `doc/CHANGELOG.md`, and
`specs/CRA-220-informant-register-initial-poc/{spec.md,data-model.md}` were all updated in the same
change as the code, not after.
