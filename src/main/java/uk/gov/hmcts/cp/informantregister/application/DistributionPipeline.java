package uk.gov.hmcts.cp.informantregister.application;

import java.time.Clock;
import java.time.Duration;

import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;

/**
 * Seam for T025. The behaviour is specified by {@code DistributionPipelineTest}.
 */
public class DistributionPipeline {

    private final IdempotencyGuard guard;
    private final HearingPayloadSource payloadSource;
    private final RegisterSubmissionClient submissionClient;
    private final ProcessingMetrics metrics;
    private final Clock clock;
    private final Duration processingDeadline;

    @SuppressWarnings("PMD.ExcessiveParameterList") // Seam: the collaborators the implementation binds.
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
     * @return what the delivery should do next
     */
    public GuardDecision process(final DistributionCommand command, final DeliveryIdentity delivery) {
        throw new UnsupportedOperationException("the pipeline runs the request through the ports");
    }
}
