package uk.gov.hmcts.cp.informantregister.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
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

    private final ProcessedRequestRepository repository;
    private final ProcessingMetrics metrics;

    /** Creates the guard over the processed-request log. */
    public IdempotencyGuard(
            final ProcessedRequestRepository repository,
            final ProcessingMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
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
                delivery.messageId(), delivery.finalPermittedDelivery());
        final GuardDecision decision;

        if (repository.insertNew(command, fingerprint, claim)) {
            LOG.info("Request recorded and claimed. source={} requestId={} hearingId={} hearingDay={}",
                    command.source(), command.requestId(), command.hearingId(), command.hearingDay());
            decision = new GuardDecision.Run(claim);
        } else {
            final Optional<ProcessedRequestRecord> found =
                    repository.read(command.source(), command.requestId());
            decision = found
                    .map(record -> branch(record, command, fingerprint, claim))
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
        if (repository.recordFailed(claim, reason.code())) {
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
     * identity of the delivery that exhausted it.
     *
     * <p>That identity is the claim's own, not a parameter. The delivery that exhausts the retries is
     * by definition the one that was running, and a record parked under some other delivery's identity
     * would replay when that delivery came back and re-park when the real one did.
     */
    public GuardDecision recordExhaustion(final RunClaim claim, final ReasonCode reason) {
        final GuardDecision decision;
        if (repository.recordFailed(claim, reason.code())) {
            LOG.info("Request parked after its final permitted delivery. source={} requestId={} reason={}",
                    claim.source(), claim.requestId(), reason.code());
            decision = new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED);
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
            final RunClaim claim) {

        // The one point at which the record's own history is visible. RECORD_COMPLETED sets
        // failure_reason to NULL and REPLAY_FAILED clears it, so a request that failed four times
        // and then succeeded ends up as a row that looks like a clean first pass — and support is
        // left with `attempts > 1` as the only evidence. Writing the prior state here puts that
        // history in the log index, where it survives the row being tidied up.
        LOG.info("Delivery matched an existing record. source={} requestId={} priorStatus={} "
                        + "priorFailureReason={} attempts={}",
                command.source(), command.requestId(), record.status(),
                record.failureReason() == null ? "none" : record.failureReason(), record.attempts());

        final GuardDecision decision;
        if (fingerprint.equals(record.fingerprint())) {
            decision = byState(record, command, claim);
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

    /** The state half of the table, reached only once the fingerprint has agreed. */
    private GuardDecision byState(
            final ProcessedRequestRecord record,
            final DistributionCommand command,
            final RunClaim claim) {

        return switch (record.status()) {
            case COMPLETED -> {
                LOG.info("Request already completed; acknowledging without a run. "
                                + "source={} requestId={} attempts={}",
                        command.source(), command.requestId(), record.attempts());
                yield new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED);
            }
            case FAILED -> replayOrPark(record, claim);
            case RECEIVED, RETRYING -> claimOrHandBack(record, claim);
        };
    }

    /**
     * A parked request, decided by which identity is delivering it.
     *
     * <p>The same identity that exhausted the retries is dead-lettering that did not settle: nothing
     * runs and the parking is attempted again. Any other identity is a deliberate resubmission.
     */
    private GuardDecision replayOrPark(
            final ProcessedRequestRecord record,
            final RunClaim claim) {

        final GuardDecision decision;
        if (Objects.equals(record.exhaustedMessageId(), claim.messageId())) {
            LOG.warn("Redelivery of the identity that exhausted the retries; re-parking. "
                    + "source={} requestId={}", claim.source(), claim.requestId());
            decision = new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED);
        } else if (repository.replayFailed(claim, replayNote(record))) {
            LOG.info("Parked request replayed under a fresh identity. source={} requestId={}",
                    claim.source(), claim.requestId());
            decision = new GuardDecision.Run(claim);
        } else {
            // Not "the same identity" — that is decided on the read. The record moved underneath us.
            LOG.warn("Replay not admitted; the record changed under it. source={} requestId={}",
                    claim.source(), claim.requestId());
            decision = new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED);
        }
        return decision;
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
