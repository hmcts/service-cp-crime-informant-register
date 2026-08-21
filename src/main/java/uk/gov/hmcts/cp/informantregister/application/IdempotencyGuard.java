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
                delivery.messageId());
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
        return decision;
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
                LOG.info("Request already completed; acknowledging without a run. source={} requestId={}",
                        command.source(), command.requestId());
                yield new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED);
            }
            case FAILED -> replayOrPark(record, claim);
            case RECEIVED, RETRYING -> claimOrHandBack(claim);
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
     */
    private GuardDecision claimOrHandBack(final RunClaim claim) {
        final GuardDecision decision;
        if (repository.reclaimStaleClaim(claim)) {
            LOG.info("Claim taken on a non-terminal request. source={} requestId={}",
                    claim.source(), claim.requestId());
            decision = new GuardDecision.Run(claim);
        } else {
            LOG.info("Claim not acquired; returning the delivery for redelivery. source={} requestId={}",
                    claim.source(), claim.requestId());
            decision = new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED);
        }
        return decision;
    }

    /**
     * The claim was reclaimed while this runner was working, so its result is discarded.
     *
     * <p>Loudly: the WARN and the counter are the only trace a run that produced nothing would
     * otherwise leave, and a rise in them means runs are outliving their leases.
     */
    private GuardDecision rejectStaleRunner(final RunClaim claim) {
        LOG.warn("Outcome discarded: this runner's claim was reclaimed while it worked. "
                        + "source={} requestId={} owner={}",
                claim.source(), claim.requestId(), claim.owner());
        metrics.staleRunnerRejected();
        return new GuardDecision.Abandon(ReasonCode.STALE_RUNNER);
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
