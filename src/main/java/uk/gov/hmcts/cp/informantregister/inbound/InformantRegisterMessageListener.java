package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.OffsetDateTime;
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
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.SettlementOperation;

/**
 * One delivery in, exactly one settlement out.
 *
 * <p>The transport adapter. It reads the body, hands the request to the core, and performs the one
 * settlement the core's decision names — nothing more. The structure is what guarantees the "exactly
 * one" half: every path produces a {@link GuardDecision}, and settlement happens once, afterwards,
 * in one place. There is no route through this class that reaches the end without a settlement
 * attempt and none that settles twice, which is what stops a delivery being left to time out (spec
 * FR-001, constitution Principle VI). The single exception is a lock that has already expired: the
 * call could not succeed, so it is reported and counted rather than attempted, and the broker's
 * redelivery is the recovery (spec FR-016).
 *
 * <p>Ordering is the other half. A delivery is acknowledged only after the outcome write has
 * returned durably, because a message acknowledged before the write is a request the processed log
 * has never heard of and the broker will never deliver again.
 *
 * <p>One broker fact is read here and nowhere else: whether the queue will deliver this message
 * again. The processed log cannot answer it — the delivery budget belongs to the message, not to the
 * request — so the transport adapter reads it from the delivery and carries it into the core, where
 * it decides whether a failing run is recorded as retrying or parked.
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
    private final int maxDeliveryCount;

    public InformantRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics,
            final int maxDeliveryCount) {
        this.parser = parser;
        this.pipeline = pipeline;
        this.metrics = metrics;
        this.maxDeliveryCount = maxDeliveryCount;
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
    // names a settlement: the body that can never be valid is parked, and the fault nothing
    // anticipated is handed back. It is a catch-and-settle, not a catch-and-ignore.
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
        LOG.info("Delivery received. source={} eventType={} deliveryCount={} finalPermittedDelivery={}",
                command.source(), command.eventType(), message.getDeliveryCount(),
                isFinalPermittedDelivery(message));
        return pipeline.process(command, identityOf(message));
    }

    /**
     * A body that can never be valid, parked at once (spec FR-003).
     *
     * <p>Retrying is pointless — no redelivery turns an unknown field into a known one — and it is
     * destructive, because the delivery budget is spent on the impossible and the message ends up on
     * the dead-letter queue under the broker's own rule, with nothing recorded about what was wrong
     * with it. Parking it here spends no attempt and puts this service's reason on the message, which
     * is what support reads.
     *
     * <p>The state machine is never entered, so no processed-request row is written. A body this
     * service could not read may not carry a usable key at all, and a row keyed on a value the parser
     * rejected would be a record of something that never happened. The delivery is accounted for by
     * its dead-letter entry, this ERROR line and the validation counter instead.
     *
     * <p>What travels with the message is the bounded reason and nothing else. The violation and the
     * offending field name are diagnostics for the log, where a reader can correlate them with the
     * producer's own release; the dead-letter description a support tool reads carries only the code.
     */
    private static GuardDecision contractInvalid(
            final ServiceBusReceivedMessage message, final ContractValidationException invalid) {
        LOG.error("Message body failed contract validation; parking it. "
                        + "messageId={} violation={} field={}",
                message.getMessageId(), invalid.violation(), invalid.field());
        return new GuardDecision.DeadLetter(
                DeadLetterReason.VALIDATION, ReasonCode.CONTRACT_VALIDATION_FAILED);
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
     * The one settlement — attempted once, and only while there is still a lock to settle against.
     *
     * <p>Spec FR-001 asks for exactly one settlement attempt <em>while the delivery lock is still
     * valid</em>. A lock that has already run out makes the attempt impossible rather than optional:
     * the call would be refused whatever it was, so it is not made, the loss is reported and counted
     * under its own instrument, and recovery is the broker's redelivery into a state machine that
     * already knows what this delivery achieved (spec FR-016).
     */
    private void settle(
            final ServiceBusReceivedMessageContext context, final GuardDecision decision) {
        if (lockHeld(context.getMessage())) {
            perform(context, decision);
        } else {
            LOG.error("The delivery lock was lost before settlement, so none was attempted; "
                            + "recovery is the broker's redelivery. messageId={} decision={}",
                    context.getMessage().getMessageId(), decision.getClass().getSimpleName());
            metrics.lockLost();
        }
    }

    /**
     * Whether this delivery's lock is still ours to settle against.
     *
     * <p>A broker that has not said when the lock expires is not evidence that it has gone, so an
     * absent expiry is read as held: refusing to settle a delivery this service could have settled
     * would leave the message to come round again for no reason. The lock is renewed automatically
     * up to {@code max-auto-lock-renew-duration}, which startup validation keeps comfortably longer
     * than a run's own deadline, so a live run reaching this check with an expired lock means the
     * renewal itself stopped — which is exactly what the instrument is for.
     */
    private static boolean lockHeld(final ServiceBusReceivedMessage message) {
        final OffsetDateTime lockedUntil = message.getLockedUntil();
        return lockedUntil == null || lockedUntil.isAfter(OffsetDateTime.now());
    }

    /**
     * The settlement the decision names, made once.
     */
    private void perform(
            final ServiceBusReceivedMessageContext context, final GuardDecision decision) {
        switch (decision) {
            case GuardDecision.Complete acknowledged -> {
                if (accepted(SettlementOperation.COMPLETE, context::complete)) {
                    LOG.info("Delivery acknowledged. reason={}", acknowledged.reason().code());
                }
            }
            case GuardDecision.Abandon handedBack -> {
                if (accepted(SettlementOperation.ABANDON, context::abandon)) {
                    LOG.info("Delivery returned for redelivery. reason={}",
                            handedBack.reason().code());
                }
            }
            case GuardDecision.DeadLetter parked -> {
                if (accepted(SettlementOperation.DEADLETTER,
                        () -> context.deadLetter(optionsFor(parked)))) {
                    // Counted after the call was accepted, so the counter records dead-letters that
                    // happened rather than dead-letters that were intended.
                    metrics.deadLettered(parked.reason());
                    LOG.warn("Delivery parked on the dead-letter queue. reason={} detail={}",
                            parked.reason().label(), parked.detail().code());
                }
            }
            // The pipeline always brings a run back to the guard, so a run reaching settlement is a
            // defect in this service rather than anything the broker can produce. It is still
            // settled, and settled the only way that loses nothing: the claim expires and the next
            // delivery reclaims it.
            case GuardDecision.Run unfinished -> {
                if (accepted(SettlementOperation.ABANDON, context::abandon)) {
                    LOG.error("A run decision reached settlement; the delivery was returned. "
                                    + "source={} requestId={}",
                            unfinished.claim().source(), unfinished.claim().requestId());
                }
            }
        }
    }

    private static DeadLetterOptions optionsFor(final GuardDecision.DeadLetter parked) {
        return new DeadLetterOptions()
                .setDeadLetterReason(parked.reason().label())
                .setDeadLetterErrorDescription(parked.detail().code());
    }

    /**
     * One settlement call — and nothing else inside the guard around it.
     *
     * <p>The boundary is exactly the broker call, and the answer is whether the broker took it. What
     * follows a settlement — the line that records it, the counter that counts it — runs
     * <em>outside</em>, because a fault there is not this call's failure and must not be dressed up
     * as one. A wider boundary would report an unreachable meter registry as "the broker refused the
     * settlement", add a reading to the settlement-failure series that never happened, and leave a
     * message that <em>is</em> parked looking unparked — while the real fault disappeared behind
     * somebody else's name. Such a failure is therefore allowed to propagate: the delivery is already
     * settled, so nothing is at risk, and the processor's error handler reports it as what it is
     * rather than this method reporting it as what it is not (constitution Principle VI — surfaced,
     * never swallowed).
     *
     * <p>A refusal by the broker is reported and counted, and that is all. It is deliberately
     * <strong>not</strong> followed by a settlement of another kind: handing a delivery back because
     * acknowledging it failed would either double-settle a lock this service still holds, or
     * succeed — turning work that <em>is</em> durably recorded into a redelivery that runs again. The
     * outcome was written before the settlement was attempted, so the redelivery meets a record that
     * already knows the answer: a completed request is acknowledged without a run, and a parked one
     * is parked again (spec FR-016, FR-007).
     *
     * @param operation  which settlement is being attempted, for the counter
     * @param brokerCall the settlement call, and only the settlement call
     * @return whether the broker accepted it
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // The SDK reports a refused settlement as a ServiceBusException, but the failure that matters
    // here is "the call did not happen", whatever type carried that news. A narrower catch would let
    // an unanticipated one escape with the delivery unaccounted for and no instrument describing it.
    private boolean accepted(final SettlementOperation operation, final Runnable brokerCall) {
        boolean settled = false;
        try {
            brokerCall.run();
            settled = true;
        } catch (RuntimeException refused) {
            LOG.error("The broker refused the settlement; no second settlement is attempted and the "
                            + "delivery will come round again. operation={} type={}",
                    operation.label(), refused.getClass().getName(), refused);
            metrics.settlementFailed(operation);
        }
        return settled;
    }

    /**
     * Who is running this delivery: this pod, and this lock.
     *
     * <p>The lock token is the delivery half — it is unique to the delivery and changes on every
     * redelivery, which is exactly the granularity {@code claim_owner} wants.
     */
    private DeliveryIdentity identityOf(final ServiceBusReceivedMessage message) {
        return new DeliveryIdentity(
                message.getMessageId(),
                INSTANCE + '/' + message.getLockToken(),
                isFinalPermittedDelivery(message));
    }

    /**
     * Whether the queue will deliver this message again after this delivery.
     *
     * <p><strong>The count is zero-based.</strong> The broker counts previous <em>unsuccessful</em>
     * deliveries, so a first delivery has had none and the last delivery a message is entitled to
     * carries {@code maxDeliveryCount - 1}. That is observed against a real broker in
     * {@code QueueSettlementIT}, not assumed from the property's name, because both mistakes are
     * quiet and both are damaging: reading it as {@code maxDeliveryCount} means this service parks
     * nothing and the broker parks the message a delivery later under its own reason with no FAILED
     * record behind it, while reading it a delivery early throws away a retry the queue was willing
     * to give.
     *
     * <p>The limit is the configured one rather than a constant, because it mirrors a setting on the
     * queue itself: the two are changed together or the service is wrong about the broker. The
     * comparison is {@code >=} so that a message somehow arriving past the budget — a queue
     * reconfigured downwards while messages were in flight — is still parked rather than never
     * parked at all.
     */
    private boolean isFinalPermittedDelivery(final ServiceBusReceivedMessage message) {
        return message.getDeliveryCount() >= (long) maxDeliveryCount - 1;
    }

    private static void clearCorrelation() {
        MDC.remove(REQUEST_ID);
        MDC.remove(HEARING_ID);
        MDC.remove(HEARING_DAY);
    }
}
