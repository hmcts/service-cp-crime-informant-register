# Phase 1 Data Model: Replay a parked request by broker sequence number

This is a delta against the schema and domain types described in
`specs/CRA-220-informant-register-initial-poc/data-model.md` and implemented in
`src/main/resources/db/migration/V1__create_processed_log.sql` and the `domain`/`persistence`
packages. Nothing here replaces that document; FR-014 requires this change to be additive, and the
"documents the implementation must update" list in the spec names that file as one the
implementation itself updates.

## Schema change — `processed_request` (new migration `V2`)

```sql
ALTER TABLE processed_request
    ADD COLUMN parking_sequence_number bigint,
    ADD COLUMN parking_category        text;

ALTER TABLE processed_request
    ADD CONSTRAINT processed_request_parking_category_chk
        CHECK (parking_category IS NULL OR parking_category IN ('EXHAUSTED', 'NON_TRANSIENT')),
    ADD CONSTRAINT processed_request_parking_pair_chk
        CHECK ((parking_sequence_number IS NULL) = (parking_category IS NULL));
```

- **`parking_sequence_number`** (`bigint`, nullable): the broker sequence number of the delivery
  that parked this request's current `FAILED` generation. `bigint` because
  `ServiceBusReceivedMessage#getSequenceNumber()` returns `long`. Nullable: absent on a row parked
  before this change, or by an older consumer mid-rollout (FR-005, FR-014).
- **`parking_category`** (`text`, nullable): the `DeadLetterReason` enum name (`EXHAUSTED` or
  `NON_TRANSIENT`) the parking write used, stored as recorded and never reconstructed once present
  (FR-010). Nullable for the same reason as above.
- **`processed_request_parking_pair_chk`**: the two columns are set together and cleared together
  (FR-001, FR-014) — this is the schema-level half of that rule; the write-together half is
  statement-level (below).
- **No change** to `exhausted_message_id` or its existing `processed_request_exhausted_id_chk`
  constraint: it is kept for audit (FR-001) and the existing same-identity re-park path
  (`exhausted_message_id` equality, retained as the first-class signal when it is still usable,
  i.e. always — see "Decision table" below) is unaffected by this migration.
- **Existing rows stay valid.** A `FAILED` row from before this migration has
  `parking_sequence_number IS NULL AND parking_category IS NULL`, which satisfies both new
  constraints and is the documented legacy case (FR-005, FR-010, FR-014).

## Statement changes — `ProcessedRequestRepository`

| # | Statement | Change |
|---|-----------|--------|
| 2 | `READ_RECORD` | Select additionally `parking_sequence_number`, `parking_category`, `updated_at`. `updated_at` is new to the read: for a `FAILED` row it **is** the parking time FR-005's margin test needs (constitution "Generation" assumption — nothing changes it while `FAILED`). |
| 4 | `RECORD_FAILED` (used by `recordNonTransientFailure` and `recordExhaustion`) | Additionally sets `parking_sequence_number = :sequenceNumber, parking_category = :category` in the same statement that sets `status = 'FAILED'` and `exhausted_message_id` (FR-001, FR-002 — written only by the statement predicated on `claim_owner`/`claim_token`, so a stale runner cannot write it). |
| 5 | `REPLAY_FAILED` | WHERE clause changes from `status = 'FAILED' AND exhausted_message_id <> :messageId` to `status = 'FAILED' AND attempts = :observedAttempts AND parking_sequence_number IS NOT DISTINCT FROM :observedParkingSequenceNumber` (FR-006; see research.md R2 for the null-safe rationale). `SET` clause additionally clears `parking_sequence_number = NULL, parking_category = NULL` alongside the existing `exhausted_message_id = NULL` (FR-007, FR-014). |

No other statement changes: `INSERT_NEW`, `RECLAIM_STALE_CLAIM`, `RECORD_COMPLETED` and
`RECORD_RETRYING` are untouched by this feature.

## Domain type changes

### `ProcessedRequestRecord` (gains two fields, plus `updatedAt`)

```java
public record ProcessedRequestRecord(
        RequestStatus status,
        String fingerprint,
        String failureReason,
        String exhaustedMessageId,
        int attempts,
        String claimOwner,
        Instant claimExpiresAt,
        Long parkingSequenceNumber,     // new — null on a legacy or pre-migration row
        String parkingCategory,        // new — the stored DeadLetterReason name, or null
        Instant updatedAt) {           // new — the parking time, for a FAILED row
}
```

### `DeliveryIdentity` (gains the broker facts FR-003–FR-005 and FR-011 decide on)

```java
public record DeliveryIdentity(
        String messageId,
        String claimOwner,
        boolean finalPermittedDelivery,
        long sequenceNumber,            // new — the broker's identity for this delivery
        long deliveryCount,             // new — for the legacy heuristic, FR-005
        Instant enqueueTime) {          // new — nullable; absent is recorded, not defaulted
}
```

`messageId` is retained — FR-004 still replays regardless of `messageId`, and FR-013/the audit
trail still want it recorded. The existing two- and three-argument convenience constructors used
throughout the test suite (`new DeliveryIdentity(messageId, claimOwner)`) are replaced by
constructors/factory methods that take the broker facts; every existing call site (chiefly
`FailedReplayIT`'s `delivery(int)` helper and its siblings in `IdempotencyGuardIT`,
`MessageListenerSettlementTest`, `DeliveryExhaustionIT`) is updated to supply a sequence number,
which is exactly what "requires bespoke tooling" in the spec's problem statement is replacing: the
test helpers' identity-minting convention (`"msg-" + number`) gains a matching
`sequenceNumberOf(number)` so each synthetic delivery is distinct on both axes.

### `RunClaim` (gains the parking delivery's own sequence number)

```java
public record RunClaim(
        String source,
        UUID requestId,
        String owner,
        UUID token,
        String messageId,
        boolean finalPermittedDelivery,
        long sequenceNumber) {          // new — the claim's own delivery, for RECORD_FAILED
}
```

Same reasoning as the existing `messageId` field's javadoc: "the delivery that acquired it is by
definition the one whose failure parks the request," so the sequence number travels with the claim
rather than being passed separately into `recordNonTransientFailure`/`recordExhaustion`.

### New: `BrokerDeliverySnapshot` (listener-local; never crosses into `application`/`domain`)

```java
record BrokerDeliverySnapshot(
        long sequenceNumber,
        long deliveryCount,
        String messageId,
        String lockToken,
        Instant enqueueTime,            // null when the broker annotation is absent
        boolean finalPermittedDelivery) {
}
```

Built once, at the top of `InformantRegisterMessageListener#outcomeOf`, inside the existing
`try`/catch boundary (research.md R3). It is the single point every broker accessor is called from;
`DeliveryIdentity` is constructed from it, never from the `ServiceBusReceivedMessage` directly, once
built. This type is an inbound-adapter implementation detail — it is not listed as a port or a
core type because Principle V's boundary is the core (`application`/`domain`), not the listener
class itself, and the core already receives exactly the facts it needs via `DeliveryIdentity`.

### New: pure lookups (`domain` package)

```java
// ReasonCode — one new method, alongside the existing `code()`.
public static Optional<ReasonCode> fromCode(String storedCode)

// New small type, or a package-private static utility — settled at tasks.md/implementation time.
public final class FailureCategoryMapping {
    public static DeadLetterReason categoryFor(String storedReasonCode) { ... }
}
```

`fromCode` is total over the full `ReasonCode` enum (every value round-trips); any string not
matching a declared `storedCode` returns empty, which FR-009 maps to `UNEXPECTED_FAILURE` at the
call site, logging the anomaly rather than the unrecognised text. `categoryFor` implements FR-010's
closed table (`SUBMISSION_REJECTED`, `SUBMISSION_NOT_ACCEPTED`, `TRANSFORMATION_FAILED` →
`NON_TRANSIENT`; everything else → `EXHAUSTED`) and never throws.

## Decision table — `IdempotencyGuard.replayOrPark` (replaces the current one-branch version)

| Recorded parking sequence number | Delivery's sequence number | Decision |
|---|---|---|
| present | **equal** to it | Re-park. No run, no state change. `fromCode(failure_reason)` decides first: if it resolves, detail = that reason and category = stored `parking_category` (or the FR-010 mapping if absent — cannot happen when the sequence number is present, since FR-001 writes them together). If it does **not** resolve, FR-009 forces `exhausted / UNEXPECTED_FAILURE` outright — the recorded category is **not** used, because it answers for a specific original cause this service can no longer identify. |
| present | **different** | Replay: compare-and-set against `(status='FAILED', attempts, parking_sequence_number)` as observed on the read (FR-004, FR-006). Success → `FAILED → RECEIVED` as today (FR-007). Failure (0 rows) → `Abandon(REPLAY_NOT_ADMITTED)`, unchanged from today. |
| **absent** (legacy) | — | Apply the FR-005 heuristic: delivery count **0** *and* enqueue time **>** `updated_at + legacy-margin` ⇒ treat as resubmission, replay via the **same** compare-and-set (its null-safe half matches `NULL` to `NULL`, FR-006). Otherwise (either condition fails, or enqueue time is absent) ⇒ re-park: if `fromCode(failure_reason)` resolves, detail = that reason and category = `FailureCategoryMapping.categoryFor(failure_reason)` (FR-010); otherwise FR-009 forces `exhausted / UNEXPECTED_FAILURE` outright, the same as the row above. |

The existing `exhausted_message_id` equality check is **removed** as the deciding test — FR-004 is
explicit that a different sequence number replays "regardless of its `messageId`" — but the column
is still written (audit) and still read into `ProcessedRequestRecord` for the replay note's
wording, unchanged from today.

## State-transition table — delta only

The `FAILED → RECEIVED` row in the CRA-220 data model gains the generation predicate and the
cleared columns:

| From | To | Trigger | Write |
|---|---|---|---|
| FAILED | RECEIVED | delivery whose sequence number differs from the recorded parking sequence number (or, for a legacy row, the FR-005 heuristic admits it) | `attempts` preserved and incremented; `failure_reason`, `exhausted_message_id`, `parking_sequence_number`, `parking_category` all cleared; `audit_note` written; claim taken. Predicated additionally on the observed `attempts` and `parking_sequence_number` matching what was read (compare-and-set, FR-006). |
| RECEIVED/RETRYING | FAILED | transient failure on the final permitted delivery, or a non-transient failure at any delivery | same statement as the CRA-220 model; additionally writes `parking_sequence_number` and `parking_category` from the claim/classification (FR-001). |

## Invariants added

1. `parking_sequence_number` and `parking_category` are set together and cleared together, never
   independently (schema constraint + statement-level predicate, FR-001/FR-014).
2. A `FAILED` row with a recorded `parking_sequence_number` is decided by sequence-number equality
   alone; `messageId` is never consulted for that decision again (FR-004).
3. A `FAILED` row with no recorded `parking_sequence_number` is decided by the two-condition legacy
   heuristic, and by nothing else; the same row reached again after a hand-back is judged afresh,
   not remembered (spec, User Story 3, Acceptance Scenario 3).
4. The replay compare-and-set's predicate is exactly what was observed on the read that produced
   the decision to replay — never the current, possibly-moved-on, state (FR-006).
5. A re-park's dead-letter reason is the request's own recorded (or mapped) category and reason,
   never `DELIVERY_LIMIT_EXHAUSTED` as a substitute (FR-009).
