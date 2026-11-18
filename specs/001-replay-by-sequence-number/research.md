# Phase 0 Research: Replay a parked request by broker sequence number

No item in Technical Context is `NEEDS CLARIFICATION`: language, framework, storage, test stack,
target platform and estate conventions are fixed by the constitution and already in force in this
codebase. What Phase 0 resolves instead is the small set of design decisions the spec leaves
implicit — each one is a point where more than one implementation would satisfy the functional
requirements, and the wrong pick would either violate Principle V (ports and adapters) or leave a
gate in FR-015 unprovable.

## R1 — How the parking category is represented in storage and in code

**Decision**: Store the parking category as the stored name of the existing `DeadLetterReason`
enum (`EXHAUSTED` / `NON_TRANSIENT`), in a new `parking_category text` column, read back through
`DeadLetterReason.valueOf(...)`. No new enum.

**Rationale**: `DeadLetterReason` (`domain/DeadLetterReason.java`) is already the closed,
two-relevant-value set the spec's "category" is — `label()` already supplies `"exhausted"` /
`"non-transient"` for the dead-letter call. Inventing a second enum (or a raw string with its own
parsing) for the same two values would duplicate the closed set the constitution's "bounded reason
code" rule exists to avoid, and would need its own mapping to `DeadLetterReason` at the point of
use. Storing the Java enum name (`EXHAUSTED`, not the label `exhausted`) keeps the column
`Enum.valueOf`-round-trippable without a translation table, and is consistent with how
`RequestStatus` is already stored (`status = 'FAILED'`, read back via `RequestStatus.valueOf`).

**Alternatives considered**: A boolean `parked_as_non_transient` column — rejected, because
`DeadLetterReason` is a closed set declared `NON_NEGOTIABLE`-adjacent in the constitution's
dead-letter discussion, and a boolean loses the name the moment a third category is ever needed.
Reusing `failure_reason` alone (deriving category from it every time) — rejected, because FR-010
explicitly requires the category to be used *as recorded* once recorded, and never reconstructed;
only the no-category legacy case uses the mapping.

## R2 — The null-safe compare-and-set predicate (FR-006)

**Decision**: PostgreSQL `IS NOT DISTINCT FROM` for the parking-sequence-number half of the
replay's WHERE clause: `parking_sequence_number IS NOT DISTINCT FROM :observedParkingSequenceNumber`,
alongside `attempts = :observedAttempts` and `status = 'FAILED'`.

**Rationale**: `IS NOT DISTINCT FROM` is PostgreSQL's null-aware equality — `NULL IS NOT DISTINCT
FROM NULL` is `true`, where plain `=` would be `NULL`. FR-006 requires "compared null-safely, so
'still none' matches for a legacy record" — a legacy row has `parking_sequence_number IS NULL`, and
the compare-and-set must still admit a replay against *that exact* observed generation. Ordinary
`=` would never match a NULL to a NULL and the legacy replay path would never be admitted by the
compare-and-set, contradicting User Story 3. `attempts` is the generation counter (see the
constitution's "Generation" assumption, already relied on by `RunClaim`/`attempts` in the existing
replay statement) — it changes on every transition out of and back into `FAILED`, so comparing it
pins the update to the exact failure generation the delivery observed, which is what rules out the
stale-decision edge case in the spec ("A stale replay decision").

**Alternatives considered**: A separate `generation` counter column — rejected as redundant; the
existing lifetime `attempts` counter already has the needed property (monotonic, incremented on
every claim acquisition including replay) and the constitution's own "Generation" assumption names
it as sufficient. Optimistic-locking via a dedicated `version` column — rejected for the same reason:
it would duplicate `attempts` rather than compose with it, and every other statement in this
repository already treats the affected-row count as the decision (file-level javadoc, "the
affected-row count is the decision"); a second locking column would be the first statement in the
file not to follow that rule.

## R3 — Reading broker metadata exactly once (FR-011/FR-012)

**Decision**: Introduce one new record, `BrokerDeliverySnapshot` (or an equivalent name settled at
implementation time), built by a single method at the top of
`InformantRegisterMessageListener#outcomeOf`, capturing `sequenceNumber`, `deliveryCount`,
`messageId`, `lockToken` and (optional) `enqueueTime` in one pass. Every later use in the class —
`correlateDelivery`, `identityOf` (renamed to build `DeliveryIdentity` from the snapshot),
`isFinalPermittedDelivery`, and the `process` receipt line's `enqueuedTime` — reads the snapshot's
fields and never calls a `ServiceBusReceivedMessage` accessor a second time.

**Rationale**: the existing listener already violates the will-be-FR-011 rule today, demonstrably:
`isFinalPermittedDelivery(message)` (which calls `message.getDeliveryCount()`) is invoked three
times in the current source — once in `outcomeOf` (line ~307), once in `process`'s receipt log
(line ~428), and once again inside `identityOf` (line ~706) — and `message.getSequenceNumber()` /
`message.getMessageId()` / `message.getLockToken()` are each read once per call site rather than
once per delivery. The listener's own javadoc already names the broker's accessors as suspect
("`getSequenceNumber()` casts an annotation whose type it does not check, so a message stamped
unusually... throws on being *described* rather than on being processed") — multiplying the number
of call sites multiplies the chances of hitting that throw outside the catch-and-settle boundary.
Capturing once, at the top, inside the existing `try` in `outcomeOf`, means a throw from any
accessor is caught by the same `RuntimeException` branch that already classifies "anything else at
all" as `unexpectedFailure`, which per FR-012 must still settle exactly once. Because the snapshot
is unavailable in that failure, `finalDelivery` cannot be read either — so the conservative rule in
FR-012 ("where the final-delivery position cannot be determined... hand the delivery back with
`UNEXPECTED_FAILURE`") means this one failure mode must *always* abandon, never dead-letter, even on
what might have been the last delivery: there is no reliable way to know it was.

**Alternatives considered**: Reading each field lazily via memoised suppliers on
`ServiceBusReceivedMessage` itself — rejected; the class is SDK-owned and not safe to subclass or
wrap transparently, and memoisation spread across several `Supplier` fields is harder to audit for
"called at most once" than a single record built in one place (which FR-015's test list requires to
be provable). A second `DeliveryIdentity`-shaped type for "everything read from the broker" —
rejected as duplicating `DeliveryIdentity`; instead `DeliveryIdentity` itself gains the new fields
(see data-model.md) and the snapshot is purely a listener-local capture step, never passed past the
transport adapter boundary (Principle V: the core sees `DeliveryIdentity`, never a
`ServiceBusReceivedMessage` or anything shaped like one).

## R4 — Where the re-park reason/category lookup and the legacy mapping live

**Decision**: Two small, pure, domain-level lookups, each total (no exception on an unrecognised
input):

- `ReasonCode.fromCode(String)` returning `Optional<ReasonCode>` — added to `ReasonCode` itself,
  following the existing house convention ("Include a `fromValue(String)` factory when parsing wire
  strings; unknown values are an explicit non-transient failure, never a silent default",
  `technical-rules.md`). Used by the re-park path to turn the stored `failure_reason` text back into
  a `ReasonCode` for the dead-letter detail (FR-009); an empty `Optional` is the "missing or
  unrecognised" case that dead-letters as `exhausted / UNEXPECTED_FAILURE` and logs the anomaly,
  never the raw stored text.
- A small static mapping, `FailureCategoryMapping.categoryFor(String storedReasonCode)` returning
  `DeadLetterReason`, encoding FR-010's explicit table (`SUBMISSION_REJECTED`,
  `SUBMISSION_NOT_ACCEPTED`, `TRANSFORMATION_FAILED` → `NON_TRANSIENT`; everything else, including
  unrecognised text, → `EXHAUSTED`). Used only when `parking_category` is absent (a legacy row, or a
  row written by an older consumer mid-rollout).

**Rationale**: both are pure functions over bounded, already-closed vocabularies
(`ReasonCode`/`DeadLetterReason`), so they belong in `domain/`, not in the guard or the repository —
consistent with Principle V ("no I/O, no clock, no randomness" for anything that is really just a
lookup) and with keeping `IdempotencyGuard` (which already carries substantial branching) from
growing a third responsibility. Two functions rather than one, because they answer different
questions at different times: `fromCode` runs on every re-park (recognised or not), while
`categoryFor` runs only on the legacy-row path, and merging them would make a reader re-derive which
one a given call site needed.

**Alternatives considered**: A single combined `ReParkDecision.of(String storedReason, String
storedCategory)` value object computed once and threaded through — considered, and may still be
how `IdempotencyGuard` chooses to call the two functions; left as an implementation detail for
`tasks.md` rather than fixed here, since it does not change the two underlying lookups or their
test surface.

## R5 — The legacy replay margin: configuration and startup validation

**Decision**: A new `Replay` nested record on `InformantRegisterProperties`
(`informantregister.replay.legacy-margin`, default `PT5M`), validated in `PropertiesValidator`
against `servicebus.lockDuration` at startup — refusing to start if the margin is shorter than the
lock duration, per FR-005 and the matching FR-015 test ("a configured margin shorter than the
queue's lock duration is rejected").

**Rationale**: every other timing constant this service depends on
(`servicebus.lockDuration`, `claim.lease`, `claim.processingDeadline`) is bound through
`InformantRegisterProperties` and cross-checked at startup by `PropertiesValidator` rather than
defended against at the point of use — `PropertiesValidator`'s own javadoc states the house rule
plainly: "Refuses to let the application start on a configuration that cannot be operated safely."
A margin shorter than the lock duration is exactly that class of defect: the spec's own assumption
section states the legacy margin "is therefore at least the lock duration," so a configuration that
violates its own documented precondition must fail loudly at startup, not silently produce the
"false replay" risk the spec already accepts at the *correct* margin.

**Alternatives considered**: A constant, not a property — rejected; `lockDuration` and
`maxDeliveryCount` are already configurable to mirror the broker's own queue settings, and a margin
derived from a broker timing (duplicate-detection window, lock duration) that is itself
configurable cannot safely be a compile-time constant.

## R6 — Proving the broker-emulator-backed FR-015 gates

**Decision**: Extend the existing `QueueSettlementIT`/`DeliveryExhaustionIT` Testcontainers
ServiceBus-emulator suites with two new assertions rather than a new IT class: "the sequence number
is unchanged across an abandon" and "a re-send gets a new one" (the last bullet of FR-015).

**Rationale**: those suites already stand up the emulator and already drive abandon/redeliver and
exhaustion scenarios end-to-end; the two new assertions are additional observations on deliveries
the suites already produce; a third IT class would pay the emulator's startup cost again for
assertions that fit naturally alongside what is already being observed there.

**Alternatives considered**: A dedicated `SequenceNumberIT` — rejected as the suites above already
cover the delivery lifecycle the sequence-number facts are observed on; kept as a fallback if the
existing suites turn out to be awkward to extend once implementation starts.
