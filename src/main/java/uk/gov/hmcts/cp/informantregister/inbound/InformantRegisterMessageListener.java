package uk.gov.hmcts.cp.informantregister.inbound;

import java.util.UUID;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.ContractValidationException;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;

/**
 * One delivery in, exactly one settlement out.
 *
 * <p>The transport adapter. It reads the body, hands the request to the core, and performs the one
 * settlement the core's decision names — nothing more. The structure is what guarantees the "exactly
 * one" half: every path produces a {@link GuardDecision}, and settlement happens once, afterwards,
 * in one place. There is no route through this class that reaches the end without a settlement and
 * none that settles twice, which is what stops a delivery being left to time out (spec FR-001,
 * constitution Principle VI).
 *
 * <p>Ordering is the other half. A delivery is acknowledged only after the outcome write has
 * returned durably, because a message acknowledged before the write is a request the processed log
 * has never heard of and the broker will never deliver again.
 *
 * <p>Correlation is put in place as soon as the body yields it and taken down when the delivery
 * ends, so receipt, processing and settlement all carry the same {@code requestId}, {@code hearingId}
 * and {@code hearingDay}. The pod is single-threaded per delivery from the SDK's point of view, and
 * the {@code finally} is what keeps one delivery's identifiers off the next one's lines.
 */
public class InformantRegisterMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(InformantRegisterMessageListener.class);

    private static final String REQUEST_ID = "requestId";
    private static final String HEARING_ID = "hearingId";
    private static final String HEARING_DAY = "hearingDay";

    /**
     * This runner's identity, for the half of {@code claim_owner} that is not the delivery.
     *
     * <p>Minted once per JVM: a pod is a runner, and the identity has to survive every delivery it
     * handles while distinguishing it from every other pod. It is written into the claim so support
     * can see which instance holds a run, and it is never a metric label.
     */
    private static final String INSTANCE = UUID.randomUUID().toString();

    private final DistributionCommandParser parser;
    private final DistributionPipeline pipeline;
    private final ProcessingMetrics metrics;

    public InformantRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics) {
        this.parser = parser;
        this.pipeline = pipeline;
        this.metrics = metrics;
    }

    /**
     * Handles one delivery, from receipt to its single settlement.
     *
     * @param context the delivery, and the settlement calls it permits
     */
    public void onMessage(final ServiceBusReceivedMessageContext context) {
        final ServiceBusReceivedMessage message = context.getMessage();
        try {
            settle(context, decide(message));
        } finally {
            clearCorrelation();
        }
    }

    /**
     * Works out what should happen to the delivery, turning every failure into a decision.
     *
     * <p>Nothing escapes: a decision is the only thing this method can produce, which is what makes
     * the settlement below unconditional.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Deliberate, and narrow: this is the boundary that owns the delivery's settlement. An exception
    // escaping here would leave the message locked with no settlement attempt — the silent loss the
    // whole design exists to prevent — so the catch is total and each branch still logs at ERROR and
    // hands the delivery back. It is a catch-and-settle, not a catch-and-ignore.
    private GuardDecision decide(final ServiceBusReceivedMessage message) {
        GuardDecision decision;
        try {
            decision = process(parser.parse(message.getBody().toString()), message);
        } catch (ContractValidationException invalid) {
            decision = contractInvalid(message, invalid);
        } catch (RuntimeException unexpected) {
            decision = unexpectedFailure(message, unexpected);
        }
        return decision;
    }

    /**
     * Runs a validated request, under the correlation identifiers it carries.
     */
    private GuardDecision process(
            final DistributionCommand command, final ServiceBusReceivedMessage message) {
        MDC.put(REQUEST_ID, command.requestId().toString());
        MDC.put(HEARING_ID, command.hearingId().toString());
        MDC.put(HEARING_DAY, command.hearingDay().toString());
        LOG.info("Delivery received. source={} eventType={} deliveryCount={}",
                command.source(), command.eventType(), message.getDeliveryCount());
        return pipeline.process(command, identityOf(message));
    }

    /**
     * A body that can never be valid.
     *
     * <p><strong>Interim behaviour.</strong> The agreed end state (spec FR-003) is an immediate
     * dead-letter with a bounded reason and no processed-request row, and it arrives with the
     * contract-validation story. Until then the delivery is handed back: it is the only settlement
     * that cannot be wrong, since the delivery budget parks the message anyway, whereas
     * acknowledging it would discard a message nobody has looked at.
     */
    private static GuardDecision contractInvalid(
            final ServiceBusReceivedMessage message, final ContractValidationException invalid) {
        LOG.error("Message body failed contract validation. messageId={} violation={} field={}",
                message.getMessageId(), invalid.violation(), invalid.field());
        return new GuardDecision.Abandon(ReasonCode.CONTRACT_VALIDATION_FAILED);
    }

    /**
     * Anything else at all.
     *
     * <p>The stack trace is kept. Everywhere else in this service a failure is reported as a bounded
     * code because the text would be producer-influenced or PII-bearing; here the failure is by
     * definition <em>not</em> the message — it is this service or the infrastructure beneath it —
     * and an unanticipated fault with no diagnostics is the one that stays unfixed.
     */
    private static GuardDecision unexpectedFailure(
            final ServiceBusReceivedMessage message, final RuntimeException unexpected) {
        LOG.error("Delivery failed unexpectedly; returning it for redelivery. messageId={} type={}",
                message.getMessageId(), unexpected.getClass().getName(), unexpected);
        return new GuardDecision.Abandon(ReasonCode.UNEXPECTED_FAILURE);
    }

    /**
     * The one settlement, performed once.
     */
    private void settle(
            final ServiceBusReceivedMessageContext context, final GuardDecision decision) {
        switch (decision) {
            case GuardDecision.Complete acknowledged -> {
                context.complete();
                LOG.info("Delivery acknowledged. reason={}", acknowledged.reason().code());
            }
            case GuardDecision.Abandon handedBack -> {
                context.abandon();
                LOG.info("Delivery returned for redelivery. reason={}", handedBack.reason().code());
            }
            case GuardDecision.DeadLetter parked -> {
                context.deadLetter(new DeadLetterOptions()
                        .setDeadLetterReason(parked.reason().label())
                        .setDeadLetterErrorDescription(parked.detail().code()));
                // Counted after the call, so the counter records dead-letters that happened rather
                // than dead-letters that were intended.
                metrics.deadLettered(parked.reason());
                LOG.warn("Delivery parked on the dead-letter queue. reason={} detail={}",
                        parked.reason().label(), parked.detail().code());
            }
            // The pipeline always brings a run back to the guard, so a run reaching settlement is a
            // defect in this service rather than anything the broker can produce. It is still
            // settled, and settled the only way that loses nothing: the claim expires and the next
            // delivery reclaims it.
            case GuardDecision.Run unfinished -> {
                context.abandon();
                LOG.error("A run decision reached settlement; the delivery was returned. "
                                + "source={} requestId={}",
                        unfinished.claim().source(), unfinished.claim().requestId());
            }
        }
    }

    /**
     * Who is running this delivery: this pod, and this lock.
     *
     * <p>The lock token is the delivery half — it is unique to the delivery and changes on every
     * redelivery, which is exactly the granularity {@code claim_owner} wants.
     */
    private static DeliveryIdentity identityOf(final ServiceBusReceivedMessage message) {
        return new DeliveryIdentity(message.getMessageId(), INSTANCE + '/' + message.getLockToken());
    }

    private static void clearCorrelation() {
        MDC.remove(REQUEST_ID);
        MDC.remove(HEARING_ID);
        MDC.remove(HEARING_DAY);
    }
}
