package uk.gov.hmcts.cp.informantregister.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureCategoryMapping;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedRequestRepository;

/**
 * The processed log's state machine: what a delivery may do, and what a run may record.
 *
 * <p>The guard decides; the listener settles and the pipeline runs. Every path returns one of four
 * decisions, so there is no way to fall off the end of a branch and leave a delivery neither run nor
 * settled — which is the silent loss this service exists to cure (constitution Principle VI).
 *
 * <p>Three properties are worth reading the code with in mind:
 *
 * <ul>
 *   <li><strong>The affected-row count is the decision.</strong> Each conditional statement is asked
 *       once and its answer is final. After a claim acquisition that affected nothing the delivery is
 *       handed back immediately — never re-read in a loop. Broker redelivery is the retry mechanism,
 *       and it already carries back-off and a delivery budget.</li>
 *   <li><strong>Claim liveness is the database's decision, not this class's.</strong> The record's
 *       expiry is read only so a log line can mention it; whether the claim may be taken is settled
 *       inside the conditional update, comparing the stored expiry against the database's own
 *       {@code now()}. Nothing here compares a JVM clock reading against a stored timestamp.</li>
 *   <li><strong>A superseded runner writes nothing.</strong> Outcome writes are predicated on the
 *       owner and the token that acquired the claim, so a runner whose claim was reclaimed while it
 *       worked affects no rows; it discards its result rather than overwriting the new owner's.</li>
 * </ul>
 */
public class IdempotencyGuard {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyGuard.class);

    /** Bounded, and it never quotes the failure it replaces: the code is the whole of the note. */
    private static final String REPLAY_NOTE_PREFIX = "REPLAYED_AFTER_FAILURE prior=";

    /** {@code informantregister.replay.legacy-margin}'s own default (spec FR-005). */
    private static final Duration DEFAULT_LEGACY_MARGIN = Duration.ofMinutes(5);

    private final ProcessedRequestRepository repository;
    private final ProcessingMetrics metrics;
    private final Duration legacyMargin;

    /**
     * Creates the guard over the processed-request log.
     *
     * @param legacyMargin how long after its parking time a legacy record's (one with no recorded
     *                     parking sequence number) first delivery must be enqueued to be treated as
     *                     a resubmission (spec FR-005). Validated at startup
     *                     ({@link uk.gov.hmcts.cp.informantregister.config.PropertiesValidator}) to
     *                     be at least the queue's lock duration.
     */
    public IdempotencyGuard(
            final ProcessedRequestRepository repository,
            final ProcessingMetrics metrics,
            final Duration legacyMargin) {
        this.repository = repository;
        this.metrics = metrics;
        this.legacyMargin = legacyMargin;
    }

    /**
     * Creates the guard with the documented default legacy margin, for every caller that has no
     * opinion on the legacy-record heuristic.
     */
    public IdempotencyGuard(final ProcessedRequestRepository repository, final ProcessingMetrics metrics) {
        this(repository, metrics, DEFAULT_LEGACY_MARGIN);
    }

    /**
     * Decides what this delivery may do with the request it carries.
     *
     * <p>A fresh request is inserted, claimed and counted in one statement, so a runner that dies
     * mid-flight can never leave a record holding a claim with no attempt recorded against it. Any
     * other answer means the request is already known, and the record decides the rest.
     */
    public GuardDecision admit(final DistributionCommand command, final DeliveryIdentity delivery) {
        final String fingerprint = RequestFingerprint.of(command);
        final RunClaim claim = new RunClaim(
                command.source(), command.requestId(), delivery.claimOwner(), UUID.randomUUID(),
                delivery.messageId(), delivery.finalPermittedDelivery(), delivery.sequenceNumber());
        final GuardDecision decision;

        if (repository.insertNew(command, fingerprint, claim)) {
            LOG.info("Request recorded and claimed. source={} requestId={} hearingId={} hearingDay={}",
                    command.source(), command.requestId(), command.hearingId(), command.hearingDay());
            decision = new GuardDecision.Run(claim);
        } else {
            final Optional<ProcessedRequestRecord> found =
                    repository.read(command.source(), command.requestId());
            decision = found
                    .map(record -> branch(record, command, fingerprint, claim, delivery))
                    .orElseGet(() -> recordAbsent(command));
        }
        return parkIfTheBudgetEndsHere(decision, command, delivery);
    }

    /**
     * Claims the parking of a delivery that cannot be admitted and has no redelivery left.
     *
     * <p>Three admission paths hand a delivery back rather than running it — the claim could not be
     * taken, a parked record moved under the replay, and the record was absent after the insert race
     * — and each is a hand-back the broker is expected to answer with another delivery. On the final
     * permitted delivery there is no other delivery: Service Bus makes an abandoned message
     * available <em>immediately</em>, with no back-off, so an admission hand-back that keeps
     * recurring consumes the budget back-to-back and the broker parks the message under
     * <em>its own</em> reason — no reason code of ours, no {@code deadlettered} metric, nothing in
     * the log index to search for. Converting the last hand-back into a dead-letter of ours is what
     * makes that parking visible.
     *
     * <p>Applied once, over the decision, rather than at each of the three sites: the escalation is
     * the same wherever the hand-back came from, and a fourth admission path added later inherits it
     * instead of having to remember it. The reason code is carried through unchanged — the delivery
     * budget says <em>when</em> the request was parked, never <em>why</em>, and
     * {@code DELIVERY_LIMIT_EXHAUSTED} would replace the one fact support needs (which admission
     * path refused it, and therefore whether to look at lease timing or at a stuck runner) with the
     * one it can already see from the queue.
     *
     * <p><strong>Nothing is written.</strong> On all three paths this runner holds no claim — it
     * never acquired one — and every terminal write is predicated on {@code claim_owner} and
     * {@code claim_token}, so it has nothing to write with. Nor may it write around that predicate:
     * {@code CLAIM_NOT_ACQUIRED} cannot distinguish a dead holder from a live one, so parking the
     * request from underneath the holder would overwrite the outcome of a run that is still
     * succeeding. What the budget buys here is attribution, not state, and the row is left to
     * whoever owns it.
     *
     * <p>Only {@link GuardDecision.Abandon} is escalated, and only from this surface. A
     * {@code Complete} is durably done, a {@code DeadLetter} is already parked, and a {@code Run}
     * has a claim and reaches the outcome writes instead — where {@code recordExhaustion} already
     * consults the same budget. {@code STALE_RUNNER} is therefore untouched here: it is returned from
     * those outcome writes and not from this surface, and it gets the same rule of its own in
     * {@link #rejectStaleRunner(RunClaim)}, off the budget the claim carries.
     */
    private static GuardDecision parkIfTheBudgetEndsHere(
            final GuardDecision decision,
            final DistributionCommand command,
            final DeliveryIdentity delivery) {

        final GuardDecision settled;
        if (delivery.finalPermittedDelivery() && decision instanceof GuardDecision.Abandon handedBack) {
            LOG.warn("Delivery not admitted and none remain; parking it with our own reason. "
                            + "source={} requestId={} reason={}",
                    command.source(), command.requestId(), handedBack.reason().code());
            settled = new GuardDecision.DeadLetter(DeadLetterReason.EXHAUSTED, handedBack.reason());
        } else {
            settled = decision;
        }
        return settled;
    }

    /**
     * The record was not there when the guard read it back.
     *
     * <p>Nothing in this service deletes a processed-request row, and the schema refuses a delete
     * that would orphan an output — but an undecidable delivery must still be handed back rather
     * than dropped on the floor.
     */
    private static GuardDecision recordAbsent(final DistributionCommand command) {
        LOG.warn("Record absent immediately after losing the insert race; returning the delivery. "
                + "source={} requestId={}", command.source(), command.requestId());
        return new GuardDecision.Abandon(ReasonCode.RECORD_ABSENT);
    }

    /**
     * Records a run that succeeded. An empty output set is a legitimate business outcome, recorded
     * with its reason rather than treated as a failure.
     */
    public GuardDecision recordCompletion(final RunClaim claim, final CompletionReason reason) {
        final GuardDecision decision;
        if (repository.recordCompleted(claim, reason.value())) {
            LOG.info("Request completed. source={} requestId={} reason={}",
                    claim.source(), claim.requestId(), reason.value());
            decision = new GuardDecision.Complete(ReasonCode.RUN_COMPLETED);
        } else {
            decision = rejectStaleRunner(claim);
        }
        return decision;
    }

    /**
     * Records a run that failed transiently, with deliveries of this message remaining.
     */
    public GuardDecision recordTransientFailure(final RunClaim claim, final ReasonCode reason) {
        final GuardDecision decision;
        if (repository.recordRetrying(claim, reason.code())) {
            LOG.info("Run failed transiently; recorded for redelivery. source={} requestId={} reason={}",
                    claim.source(), claim.requestId(), reason.code());
            decision = new GuardDecision.Abandon(reason);
        } else {
            decision = rejectStaleRunner(claim);
        }
        return decision;
    }

    /**
     * Records a run that failed in a way no redelivery can change, parking it at once.
     *
     * <p>The delivery budget is irrelevant here and deliberately not consulted. A transformation
     * error reads the same on every delivery, and a body the Results command refused is the same
     * body on the next one, so abandoning it back to the
     * broker would spend four more deliveries reaching the same answer and then park it under
     * {@code DELIVERY_LIMIT_EXHAUSTED} — a reason that tells support the service ran out of tries
     * rather than that the payload was unusable (`design_rules.md`, "Processing State Machine":
     * non-transient goes straight to FAILED and the dead-letter queue).
     *
     * <p>The row is written by the same statement an exhaustion uses, so the identity of this
     * delivery is stamped onto it: a redelivery of the same message re-parks without re-running,
     * while a deliberate resubmission under a fresh identity replays. That is the behaviour a
     * parked request already has, and a non-transient failure is not a different kind of parking.
     */
    public GuardDecision recordNonTransientFailure(final RunClaim claim, final ReasonCode reason) {
        final GuardDecision decision;
        if (repository.recordFailed(claim, reason.code(), DeadLetterReason.NON_TRANSIENT)) {
            LOG.info("Request parked; no redelivery could change it. source={} requestId={} reason={}",
                    claim.source(), claim.requestId(), reason.code());
            decision = new GuardDecision.DeadLetter(DeadLetterReason.NON_TRANSIENT, reason);
        } else {
            decision = rejectStaleRunner(claim);
        }
        return decision;
    }

    /**
     * Records a run that failed on the final permitted delivery, parking the request with the
     * identity of the delivery that exhausted it. The dead-letter retains the same failure reason
     * as the row, so support can identify the cause directly from the queue.
     *
     * <p>That identity is the claim's own, not a parameter. The delivery that exhausts the retries is
     * by definition the one that was running, and a record parked under some other delivery's identity
     * would replay when that delivery came back and re-park when the real one did.
     */
    public GuardDecision recordExhaustion(final RunClaim claim, final ReasonCode reason) {
        final GuardDecision decision;
        if (repository.recordFailed(claim, reason.code(), DeadLetterReason.EXHAUSTED)) {
            LOG.info("Request parked after its final permitted delivery. source={} requestId={} reason={}",
                    claim.source(), claim.requestId(), reason.code());
            decision = new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, reason);
        } else {
            decision = rejectStaleRunner(claim);
        }
        return decision;
    }

    /**
     * The transition table, in the order the data model states it: identity first, then state.
     */
    private GuardDecision branch(
            final ProcessedRequestRecord record,
            final DistributionCommand command,
            final String fingerprint,
            final RunClaim claim,
            final DeliveryIdentity delivery) {

        // The one point at which the record's own history is visible. RECORD_COMPLETED sets
        // failure_reason to NULL and REPLAY_FAILED clears it, so a request that failed four times
        // and then succeeded ends up as a row that looks like a clean first pass — and support is
        // left with `attempts > 1` as the only evidence. Writing the prior state here puts that
        // history in the log index, where it survives the row being tidied up.
        LOG.info("Delivery matched an existing record. source={} requestId={} priorStatus={} "
                        + "priorFailureReason={} attempts={}",
                command.source(), command.requestId(), record.status(),
                sanitisedFailureReason(record.failureReason()), record.attempts());

        final GuardDecision decision;
        if (fingerprint.equals(record.fingerprint())) {
            decision = byState(record, command, claim, delivery);
        } else {
            // The key has been reused for a different request. The record is not written to at all:
            // absorbing this delivery would silently drop one of the two requests.
            LOG.error("Idempotency collision: the key holds a different request. "
                            + "source={} requestId={} hearingId={} hearingDay={}",
                    command.source(), command.requestId(), command.hearingId(), command.hearingDay());
            decision = new GuardDecision.DeadLetter(
                    DeadLetterReason.COLLISION, ReasonCode.IDEMPOTENCY_COLLISION);
        }
        return decision;
    }

    /**
     * A stored {@code failure_reason} as logged — {@code "none"} when absent, {@code "unrecognised"}
     * in place of the raw text when it does not match any declared {@link ReasonCode}.
     *
     * <p>The same sanitisation {@link #unrecognisedFailureReason} applies for the re-park decision
     * itself: an unrecognised reason may be producer-adjacent or arbitrary corruption, and neither
     * is safe to emit into the log index verbatim.
     */
    private static String sanitisedFailureReason(final String storedReason) {
        final String logged;
        if (storedReason == null) {
            logged = "none";
        } else {
            logged = ReasonCode.fromCode(storedReason).isPresent() ? storedReason : "unrecognised";
        }
        return logged;
    }

    /** The state half of the table, reached only once the fingerprint has agreed. */
    private GuardDecision byState(
            final ProcessedRequestRecord record,
            final DistributionCommand command,
            final RunClaim claim,
            final DeliveryIdentity delivery) {

        return switch (record.status()) {
            case COMPLETED -> {
                LOG.info("Request already completed; acknowledging without a run. "
                                + "source={} requestId={} attempts={}",
                        command.source(), command.requestId(), record.attempts());
                yield new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED);
            }
            case FAILED -> replayOrPark(record, claim, delivery);
            case RECEIVED, RETRYING -> claimOrHandBack(record, claim);
        };
    }

    /**
     * A parked request, decided by the broker's own sequence number — never by {@code messageId}
     * (spec 001-replay-by-sequence-number, FR-003, FR-004).
     *
     * <p>A recorded parking sequence number is the ordinary case: the same number redelivering is
     * dead-lettering that did not settle, and a different one is a deliberate resubmission, whatever
     * its {@code messageId} — the exact defect this feature fixes, since a support "Re-send" copies
     * the message as it stands, {@code messageId} included.
     *
     * <p>A record with <strong>no</strong> recorded parking sequence number — parked before this
     * feature, or by an older consumer mid-rollout — has no exact identity test available, so
     * {@link #legacyReplayOrPark} applies the documented two-condition heuristic instead (FR-005).
     */
    private GuardDecision replayOrPark(
            final ProcessedRequestRecord record,
            final RunClaim claim,
            final DeliveryIdentity delivery) {

        final GuardDecision decision;
        if (record.parkingSequenceNumber() == null) {
            decision = legacyReplayOrPark(record, claim, delivery);
        } else if (record.parkingSequenceNumber() == claim.sequenceNumber()) {
            logRepark(record, claim, delivery);
            decision = reparkUnderStoredReason(record);
        } else {
            decision = attemptReplay(record, claim, delivery);
        }
        return decision;
    }

    /**
     * The legacy-record heuristic (spec FR-005, User Story 3): the two-condition compatibility test
     * for a {@code FAILED} row with no recorded parking sequence number — one parked before this
     * feature, or by an older consumer mid-rollout.
     *
     * <p>Neither condition is proof on its own, and the rule is an explicitly accepted
     * compatibility heuristic rather than an exact test: the broker does not increment the delivery
     * count when a message is released by a closed connection, and the enqueue-time test compares
     * the broker's clock with the database's. A false replay requires both a release without an
     * increment <em>and</em> a broker clock ahead of the database clock by more than the margin —
     * the residual risk the spec's assumptions accept, against the alternative of backfilling exact
     * parking sequence numbers for every row already on the dead-letter queue, which is out of
     * scope.
     *
     * <p>A delivery that fails the heuristic is re-parked, including when the enqueue time is
     * absent altogether — there is nothing to compare, so the conservative answer applies. The same
     * {@link #attemptReplay} the ordinary branch uses is reused here: the compare-and-set's
     * null-safe half matches a {@code NULL} {@code parking_sequence_number} to the {@code null}
     * observed here, which is exactly "still none" (spec FR-006).
     */
    private GuardDecision legacyReplayOrPark(
            final ProcessedRequestRecord record,
            final RunClaim claim,
            final DeliveryIdentity delivery) {

        final GuardDecision decision;
        if (isLegacyResubmission(record, delivery)) {
            decision = attemptReplay(record, claim, delivery);
        } else {
            logRepark(record, claim, delivery);
            decision = reparkUnderStoredReason(record);
        }
        return decision;
    }

    /**
     * Whether a delivery of a legacy {@code FAILED} record (no recorded parking sequence number)
     * looks like a resubmission: its broker delivery count is 0, <strong>and</strong> its broker
     * enqueue time is later than the request's parking time — {@link ProcessedRequestRecord#updatedAt()},
     * which nothing changes while the row stays {@code FAILED} — by more than
     * {@link #legacyMargin}.
     */
    private boolean isLegacyResubmission(
            final ProcessedRequestRecord record, final DeliveryIdentity delivery) {
        return delivery.deliveryCount() == 0
                && delivery.enqueueTime() != null
                && delivery.enqueueTime().isAfter(record.updatedAt().plus(legacyMargin));
    }

    /**
     * The one replay attempt both branches of {@link #replayOrPark} make, against the exact failure
     * generation {@code record} was read as (spec FR-006).
     */
    private GuardDecision attemptReplay(
            final ProcessedRequestRecord record, final RunClaim claim, final DeliveryIdentity delivery) {
        final GuardDecision decision;
        if (repository.replayFailed(
                claim, record.attempts(), record.parkingSequenceNumber(), replayNote(record))) {
            // FR-008: the parked delivery's sequence number (or "none" for a legacy record), the
            // replaying delivery's sequence number, delivery count and enqueue time. No PII — every
            // field here is a broker fact.
            LOG.info("Parked request replayed under a different sequence number. "
                            + "source={} requestId={} parkedSequenceNumber={} "
                            + "deliverySequenceNumber={} deliveryCount={} enqueueTime={}",
                    claim.source(), claim.requestId(), parkedSequenceNumberOrNone(record),
                    claim.sequenceNumber(), delivery.deliveryCount(), enqueueTimeOrNone(delivery));
            decision = new GuardDecision.Run(claim);
        } else {
            // Not "the same sequence number" — that is decided on the read, above. The record moved
            // underneath us: a concurrent replay won, or a newer failure was recorded.
            LOG.warn("Replay not admitted; the record changed under it. source={} requestId={}",
                    claim.source(), claim.requestId());
            decision = new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED);
        }
        return decision;
    }

    /** FR-008's re-park log line, shared by the ordinary and the legacy re-park branches. */
    private static void logRepark(
            final ProcessedRequestRecord record, final RunClaim claim, final DeliveryIdentity delivery) {
        LOG.warn("Redelivery of the sequence number that parked the request; re-parking. "
                        + "source={} requestId={} parkedSequenceNumber={} "
                        + "deliverySequenceNumber={} deliveryCount={} enqueueTime={}",
                claim.source(), claim.requestId(), parkedSequenceNumberOrNone(record),
                claim.sequenceNumber(), delivery.deliveryCount(), enqueueTimeOrNone(delivery));
    }

    /** The parked delivery's sequence number as logged — {@code "none"} for a legacy record. */
    private static String parkedSequenceNumberOrNone(final ProcessedRequestRecord record) {
        return record.parkingSequenceNumber() == null
                ? "none" : record.parkingSequenceNumber().toString();
    }

    /** A delivery's enqueue time as logged — {@code "none"} when the broker's annotation was absent. */
    private static String enqueueTimeOrNone(final DeliveryIdentity delivery) {
        return delivery.enqueueTime() == null ? "none" : delivery.enqueueTime().toString();
    }

    /**
     * Re-parks a delivery of an already-FAILED request without running anything or changing state —
     * with the request's own recorded cause, never the generic exhaustion reason (spec FR-009).
     *
     * <p>{@code detail} is the stored {@code failure_reason} turned back into a {@link ReasonCode};
     * a missing or unrecognised one is a data anomaly, logged and reported as
     * {@link ReasonCode#UNEXPECTED_FAILURE} rather than emitting producer-adjacent or corrupted
     * text onto the dead-letter queue. {@code category} is the row's own recorded
     * {@code parking_category} where one is recorded, used exactly as recorded and never
     * reconstructed from the reason code (FR-010); a legacy row with none recorded is categorised
     * by {@link FailureCategoryMapping} instead. <strong>Except</strong> when the reason itself is
     * the anomaly: FR-009 requires {@code exhausted / UNEXPECTED_FAILURE} for a missing or
     * unrecognised stored reason regardless of what category happens to be recorded, since a
     * recorded {@code non-transient} category answers for the specific original cause that the
     * anomaly means this service can no longer identify.
     */
    private GuardDecision reparkUnderStoredReason(final ProcessedRequestRecord record) {
        final Optional<ReasonCode> recognisedReason = ReasonCode.fromCode(record.failureReason());
        final GuardDecision decision;
        if (recognisedReason.isPresent()) {
            final DeadLetterReason category = record.parkingCategory() != null
                    ? DeadLetterReason.valueOf(record.parkingCategory())
                    : FailureCategoryMapping.categoryFor(record.failureReason());
            decision = new GuardDecision.DeadLetter(category, recognisedReason.get());
        } else {
            decision = new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, unrecognisedFailureReason());
        }
        return decision;
    }

    /**
     * A re-park whose stored {@code failure_reason} is missing or does not match any declared
     * {@link ReasonCode} — a data anomaly, not a reason to let the delivery escape unsettled.
     *
     * <p>The unrecognised text itself is never logged or reported: it may be {@code null}, may be
     * producer-adjacent in origin (a reason this service no longer declares), or may be arbitrary
     * corruption, and none of those is safe to emit onto a dead-letter queue or into the log index
     * verbatim. What is logged is the fact of the anomaly; the identifiers needed to find the row
     * are already on the MDC context the listener set for this delivery.
     */
    private static ReasonCode unrecognisedFailureReason() {
        LOG.error("A FAILED request's stored failure reason is missing or unrecognised; re-parking "
                + "as UNEXPECTED_FAILURE rather than emitting it. reason={}",
                ReasonCode.UNEXPECTED_FAILURE.code());
        return ReasonCode.UNEXPECTED_FAILURE;
    }

    /**
     * A non-terminal request: run it if its claim is free, hand the delivery back if it is not.
     *
     * <p>The record is passed in so the line can say <em>why</em> the claim was free. A claim
     * nobody held and a claim reclaimed from a runner that died are the same SQL statement and
     * very different incidents.
     */
    private GuardDecision claimOrHandBack(
            final ProcessedRequestRecord record, final RunClaim claim) {
        final GuardDecision decision;
        if (repository.reclaimStaleClaim(claim)) {
            // Taking the claim on a record that already holds one means the runner that held it is
            // gone: nothing releases a claim except the run that took it, so the only way one
            // expires is a runner that died mid-run. That is worth a WARN and the identity of the
            // pod it happened on — a repeating owner is a pod that keeps dying, which is a
            // different incident from a busy queue.
            if (record.claimOwner() == null) {
                LOG.info("Claim taken on a non-terminal request with no claim held. "
                                + "source={} requestId={} attempts={}",
                        claim.source(), claim.requestId(), record.attempts());
            } else {
                LOG.warn("Claim reclaimed from an expired runner; the previous run did not finish. "
                                + "source={} requestId={} priorOwner={} priorClaimExpiredAt={} "
                                + "attempts={}",
                        claim.source(), claim.requestId(), record.claimOwner(),
                        record.claimExpiresAt(), record.attempts());
            }
            decision = new GuardDecision.Run(claim);
        } else {
            LOG.info("Claim not acquired; returning the delivery for redelivery. "
                            + "source={} requestId={} heldBy={} claimExpiresAt={} attempts={}",
                    claim.source(), claim.requestId(), record.claimOwner(),
                    record.claimExpiresAt(), record.attempts());
            decision = new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED);
        }
        return decision;
    }

    /**
     * The claim was reclaimed while this runner was working, so its result is discarded.
     *
     * <p>Loudly: the WARN and the counter are the only trace a run that produced nothing would
     * otherwise leave, and a rise in them means runs are outliving their leases.
     *
     * <p>All four outcome writes fall back here, which is why the delivery-budget rule is applied
     * here too rather than at each of them: a fifth write added later inherits it instead of having
     * to remember it, the same reasoning that put the admission-side escalation over the whole
     * decision. Ordinarily the delivery goes back — a redelivery meets the reclaiming runner's
     * durable outcome and settles in a moment. On the last delivery the message is entitled to there
     * is no redelivery to meet anything, so handing it back spends the message on the broker's own
     * dead-letter reason and the stale runner never appears in the log index. Under
     * {@link #recordExhaustion} that is not an edge case at all: it is only ever called <em>on</em>
     * the final delivery, so a reclaimed claim there hands back a delivery that has nothing left by
     * definition.
     *
     * <p>The reason code carried into the parking is {@code STALE_RUNNER} and not
     * {@code DELIVERY_LIMIT_EXHAUSTED}: the budget says when the request was parked, never why, and
     * a rise in stale runners is precisely the signal that leases are too short for the pipeline.
     *
     * <p><strong>Nothing is written</strong>, and here the invariant is stronger than on the
     * admission paths. This runner is a non-holder by proof — the write it just attempted was
     * predicated on {@code claim_owner} and {@code claim_token} and affected no row — and the record
     * now belongs to the runner that reclaimed it, which may be succeeding at this moment. Writing
     * around that predicate would overwrite a live run's outcome with a superseded one's. What the
     * budget buys is attribution, not state (data-model invariant 7).
     */
    private GuardDecision rejectStaleRunner(final RunClaim claim) {
        LOG.warn("Outcome discarded: this runner's claim was reclaimed while it worked. "
                        + "source={} requestId={} owner={} finalPermittedDelivery={}",
                claim.source(), claim.requestId(), claim.owner(),
                claim.finalPermittedDelivery());
        metrics.staleRunnerRejected();

        final GuardDecision decision;
        if (claim.finalPermittedDelivery()) {
            LOG.warn("The reclaimed run held the final permitted delivery; parking it with our own "
                            + "reason rather than handing it back into nothing. "
                            + "source={} requestId={} reason={}",
                    claim.source(), claim.requestId(), ReasonCode.STALE_RUNNER.code());
            decision = new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.STALE_RUNNER);
        } else {
            decision = new GuardDecision.Abandon(ReasonCode.STALE_RUNNER);
        }
        return decision;
    }

    /**
     * The audit note a replay leaves behind.
     *
     * <p>The transition clears {@code failure_reason}, so the bounded code the record was parked for
     * is carried into the note; the replay's own timestamp is the row's {@code updated_at}, written by
     * the database, rather than a JVM reading pasted into text.
     */
    private static String replayNote(final ProcessedRequestRecord record) {
        return REPLAY_NOTE_PREFIX + record.failureReason();
    }
}
