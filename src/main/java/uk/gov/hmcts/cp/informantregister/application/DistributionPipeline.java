package uk.gov.hmcts.cp.informantregister.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;

/**
 * One request, from the guard admitting it to the guard recording what happened.
 *
 * <p>The application core: it names no broker, no database and no HTTP client, and it is the only
 * place that knows the order the ports are called in. The transport adapter above it decides how a
 * delivery is settled; this decides what the delivery is worth settling as.
 *
 * <p><strong>A run produces no authorities in this increment.</strong> There is no transformation
 * port yet, so the submission port is invoked once per member of an empty set — which is not at all
 * — and the request completes with {@code no-authorities}. An empty result is a legitimate business
 * outcome recorded as such, and a submission stub that is never called is the intended shape of the
 * skeleton rather than a missing step (spec US1-2, FR-010).
 *
 * <p><strong>The run bounds itself.</strong> Before the ports are touched the deadline is fixed at
 * {@code informantregister.claim.processing-deadline} from now, and the run checks it before writing
 * an outcome. The deadline is strictly shorter than the claim lease, so a slow run stops itself
 * while its claim is still unambiguously its own; the alternative is a runner that discovers it has
 * been superseded only when its outcome write affects no rows — which is safe, but leaves the
 * request waiting for a redelivery it could have asked for a minute earlier (data-model invariant 8).
 * The check is against elapsed local time only: nothing here compares a JVM reading with a stored
 * timestamp, which is the multi-node skew the data model's single-time-authority rule exists to rule
 * out.
 *
 * <p>Every failure a run can meet this increment is transient — payload unavailability is transient
 * by construction, and a deadline is not a fault at all — so no failure here is ever parked for
 * being unretryable. What decides the outcome instead is whether the queue will deliver the message
 * again: with deliveries remaining the failure is recorded RETRYING and the delivery is handed back;
 * on the final permitted delivery the same failure is recorded FAILED, with the identity of the
 * delivery that exhausted the budget, and the message is parked. The transport adapter reads that
 * fact from the delivery and carries it in, because the processed log cannot know it — the budget
 * belongs to the message, not to the request.
 *
 * <p>The classification is carried rather than assumed because it already decides the metric label,
 * and it becomes a second branch the moment the submission port has authorities to reject: a 4xx
 * contract rejection is not worth a redelivery whatever the delivery count says, and that branch
 * belongs with the story that can test it.
 */
public class DistributionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(DistributionPipeline.class);

    private final IdempotencyGuard guard;
    private final HearingPayloadSource payloadSource;
    private final RegisterSubmissionClient submissionClient;
    private final ProcessingMetrics metrics;
    private final Clock clock;
    private final Duration processingDeadline;

    public DistributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final RegisterSubmissionClient submissionClient,
            final ProcessingMetrics metrics,
            final Clock clock,
            final Duration processingDeadline) {
        this.guard = guard;
        this.payloadSource = payloadSource;
        this.submissionClient = submissionClient;
        this.metrics = metrics;
        this.clock = clock;
        this.processingDeadline = processingDeadline;
    }

    /**
     * Runs the request through the guard and, if it is admitted, through the ports.
     *
     * @param command  the validated request
     * @param delivery who is delivering it, and under which broker identity
     * @return what the delivery should do next — a settlement, never a run
     */
    public GuardDecision process(final DistributionCommand command, final DeliveryIdentity delivery) {
        final GuardDecision admission = guard.admit(command, delivery);
        final GuardDecision decision;
        if (admission instanceof GuardDecision.Run admitted) {
            decision = runUnder(command, admitted.claim(), delivery.finalPermittedDelivery());
        } else {
            // Already completed, contested, or a collision: the guard has decided, and a run would
            // either duplicate work or overwrite a record that belongs to a different request.
            decision = admission;
        }
        return decision;
    }

    /**
     * The run itself, with the one failure it can meet turned into an outcome.
     */
    private GuardDecision runUnder(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance) {
        GuardDecision outcome;
        try {
            outcome = runToOutcome(command, claim, lastChance);
        } catch (PayloadUnavailableException unavailable) {
            outcome = failed(claim, unavailable.classification(), unavailable.reason(), lastChance);
        }
        return outcome;
    }

    private GuardDecision runToOutcome(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance) {
        final Instant deadline = clock.instant().plus(processingDeadline);

        final JsonNode payload = payloadSource.fetch(command);
        LOG.info("Hearing payload obtained. source={} requestId={} hearingId={} topLevelFields={}",
                command.source(), command.requestId(), command.hearingId(), payload.size());

        // No transformation port exists this increment, so the run produces no authorities. The loop
        // below is therefore the submission port's real call site, executed zero times.
        final List<AuthoritySubmission> submissions = List.of();

        final GuardDecision outcome;
        // Strictly before, so the deadline is a bound that is *reached* rather than passed: a run
        // standing exactly on it has already used the time its claim guarantees and may not write a
        // completion. `isAfter` on the other side of this branch would let that one instant through.
        if (clock.instant().isBefore(deadline)) {
            for (final AuthoritySubmission submission : submissions) {
                submissionClient.submit(submission);
            }
            outcome = completed(claim, submissions.size());
        } else {
            outcome = failed(claim, FailureClassification.TRANSIENT,
                    ReasonCode.PROCESSING_DEADLINE_EXCEEDED, lastChance);
        }
        return outcome;
    }

    /**
     * Records the run's success, and counts it only if the guard accepted the write.
     *
     * <p>A superseded runner's completion affects no rows and comes back as an abandon; counting it
     * as a completed request would report work that was never recorded.
     */
    private GuardDecision completed(final RunClaim claim, final int authorities) {
        final GuardDecision outcome = guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES);
        if (outcome instanceof GuardDecision.Complete) {
            LOG.info("Run finished. source={} requestId={} authorities={}",
                    claim.source(), claim.requestId(), authorities);
            metrics.requestSettled(RequestOutcome.COMPLETED);
        }
        return outcome;
    }

    /**
     * Records a failed run — loudly, and with a bounded reason rather than whatever the layer
     * beneath had to say about it.
     *
     * <p>The same failure means two different things depending on whether the queue will deliver the
     * message again. With deliveries remaining it is recorded RETRYING and the delivery is handed
     * back. On the final permitted delivery it is recorded FAILED, in the transaction that stamps the
     * identity of the delivery that exhausted the budget onto the row, and the message is parked
     * where support can see it. Retry exhaustion is judged by that delivery count alone and never by
     * the cumulative attempt count, which is a lifetime tally and would park a replayed request on
     * its first failure (spec FR-004, FR-009).
     *
     * <p>The terminal outcome is counted only once the guard has accepted the write: a superseded
     * runner's parking affects no rows and comes back as a hand-back, and counting it would report a
     * request parked that is still being worked on by somebody else.
     */
    private GuardDecision failed(
            final RunClaim claim,
            final FailureClassification classification,
            final ReasonCode reason,
            final boolean lastChance) {
        LOG.error("Pipeline run failed. source={} requestId={} classification={} reason={} "
                        + "finalPermittedDelivery={}",
                claim.source(), claim.requestId(), classification.label(), reason.code(), lastChance);
        metrics.pipelineFailed(classification);

        final GuardDecision outcome;
        if (lastChance) {
            outcome = guard.recordExhaustion(claim, reason);
            if (outcome instanceof GuardDecision.DeadLetter) {
                metrics.requestSettled(RequestOutcome.FAILED);
            }
        } else {
            outcome = guard.recordTransientFailure(claim, reason);
        }
        return outcome;
    }
}
