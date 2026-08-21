package uk.gov.hmcts.cp.informantregister.inbound;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusFailureReason;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.informantregister.domain.ContractValidationException;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
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
 * FR-001, constitution Principle VI). A lock that has expired is discovered from the broker's own
 * refusal of that one attempt — never from a local clock reading — and is then reported and counted
 * under its own instrument, with the broker's redelivery as the recovery (spec FR-016).
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

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String HEARING_ID = "hearingId";
    private static final String HEARING_DAY = "hearingDay";

    /**
     * How deep to walk a refusal's cause chain before giving up — bounded because the chain is
     * supplied by libraries, and a self-referential one must classify a few times and stop rather
     * than hang the settlement path.
     */
    private static final int MAX_CAUSE_DEPTH = 10;

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
    private final ServiceBusHealthIndicator health;
    private final StoreGate storeGate;
    private final int maxDeliveryCount;

    /** Creates the listener; the settlement decision stays here and nowhere else. */
    public InformantRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics,
            final ServiceBusHealthIndicator health,
            final StoreGate storeGate,
            final int maxDeliveryCount) {
        this.parser = parser;
        this.pipeline = pipeline;
        this.metrics = metrics;
        this.health = health;
        this.storeGate = storeGate;
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
            settle(context, storeGate.storeAvailable()
                    ? decide(message)
                    : storeUnavailable());
        } finally {
            clearCorrelation();
        }
    }

    /**
     * The processed log could not be reached, so this delivery is not examined at all (spec FR-015).
     *
     * <p>Availability is a precondition rather than a step, and the ordering is the whole of it. The
     * body is not read, so nothing is judged: a message that could never validate is not
     * dead-lettered on the strength of a check this service was not fit to make, and a message that
     * is perfectly good does not have an attempt recorded against it that never ran. Nothing enters
     * the state machine, nothing is counted as an attempt, and the delivery goes back exactly as it
     * arrived.
     *
     * <p>Then intake is <em>asked</em> to stop. Asked, because this is the broker's own callback
     * thread and stopping a processor from inside one deadlocks the shutdown; the controller carries
     * it out elsewhere. Stopping is the point: without it every message on the queue would be taken,
     * handed back, and taken again until the broker's delivery budget ran out and parked work whose
     * only fault was arriving during an outage of ours.
     *
     * <p>The line carries the bounded reason code and nothing else. There is nothing else to carry:
     * the body was deliberately not read, so there is no request id to correlate on, and the broker's
     * identity for the message is text the producer chose and is never written out. The delivery
     * comes round again once the store is back, and that one is correlated.
     */
    private GuardDecision storeUnavailable() {
        LOG.error("The processed log could not be reached, so the delivery was not examined; "
                        + "returning it and asking for intake to stop. reason={}",
                ReasonCode.STORE_UNAVAILABLE.code());
        return handBackAndSuspend();
    }

    /**
     * The store answered the precondition and then went away underneath the run.
     *
     * <p>The precondition is a check, not a guarantee: a store can die in the moment between
     * answering a probe and being asked to record something, and an outage that begins one
     * millisecond later is the same outage. Without this branch it was reported as an unexpected
     * fault and the delivery was handed back — correctly — but <strong>intake kept running</strong>,
     * so the next delivery met the same dead store, and the next, until the broker's budget was
     * spent and recoverable work was parked. That is the exact failure FR-015 exists to prevent,
     * reached by the door nobody was watching.
     *
     * <p>It is told apart by the failure's own type rather than by where it was thrown, because the
     * store is reached from more than one place inside a run and the answer is the same wherever it
     * was: the request may be perfectly good, and this service was not fit to judge it.
     */
    private GuardDecision storeDiedMidRun() {
        LOG.error("The processed log went away during the run, so nothing was recorded; returning "
                        + "the delivery and asking for intake to stop. reason={}",
                ReasonCode.STORE_UNAVAILABLE.code());
        return handBackAndSuspend();
    }

    /**
     * The two halves a store outage always costs: this delivery back, and intake stopped.
     */
    private GuardDecision handBackAndSuspend() {
        storeGate.suspendIntake();
        return new GuardDecision.Abandon(ReasonCode.STORE_UNAVAILABLE);
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
            decision = examine(message);
        } catch (TransientDataAccessException | RecoverableDataAccessException
                | DataAccessResourceFailureException storeGone) {
            // The outage classes, and deliberately not the whole DataAccessException hierarchy.
            // Spring's own transient/non-transient split is the wrong knife here: the exception a
            // dead store actually produces — DataAccessResourceFailureException, connection
            // acquisition included — sits on the non-transient side, while a constraint violation
            // or a broken statement is the store *answering*, over a connection that plainly
            // worked. Only the store-went-away classes may stop the queue; a per-statement fault
            // is handed back below without turning one poison message into an intake outage.
            decision = storeDiedMidRun();
        } catch (RuntimeException unexpected) {
            decision = unexpectedFailure(unexpected);
        }
        return decision;
    }

    /**
     * Reads the body and runs what it turns out to be — <strong>inside</strong> the boundary above.
     *
     * <p>The body read is the first thing that can fail and it is a call into the SDK: it decodes a
     * received message, and an empty, corrupt or already-disposed one throws rather than returning
     * something disappointing. Read outside the catch it would take the delivery with it — no
     * decision, so no settlement, so a message locked until its lease ran out and then delivered
     * again, four more times, into the same failure. Reading it here means a body that cannot even
     * be fetched is exactly as accounted for as one that cannot be parsed: one ERROR, one metric,
     * one settlement.
     *
     * <p>It is a separate method only so that the body can be a local of the frame that reads it and
     * still reach the validation branch, which needs it for correlation.
     */
    private GuardDecision examine(final ServiceBusReceivedMessage message) {
        final String body = message.getBody().toString();
        GuardDecision decision;
        try {
            decision = process(parser.parse(body), message);
        } catch (ContractValidationException invalid) {
            decision = contractInvalid(body, invalid);
        }
        return decision;
    }

    /**
     * Runs a validated request, under the correlation identifiers it carries.
     */
    private GuardDecision process(
            final DistributionCommand command, final ServiceBusReceivedMessage message) {
        MDC.put(SOURCE, command.source());
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
    private GuardDecision contractInvalid(
            final String body, final ContractValidationException invalid) {
        // Correlation first, so the line that reports the rejection is findable by the one search a
        // support engineer performs. A message rejected with no identifiers at all is a message
        // nobody can look up, which is the opposite of what an ERROR is for — and the producer
        // usually did supply them, because an unknown extra field leaves the other six untouched.
        // Only canonical values are admitted, so nothing a producer wrote reaches the index by
        // being called requestId.
        correlate(parser.canonicalCorrelation(body));
        LOG.error("Message body failed contract validation; parking it. violation={} field={}",
                invalid.violation(), invalid.field());
        return new GuardDecision.DeadLetter(
                DeadLetterReason.VALIDATION, ReasonCode.CONTRACT_VALIDATION_FAILED);
    }

    /**
     * Anything else at all.
     *
     * <p>Reported by type and bounded code, with no stack trace — the same rule as everywhere else,
     * and for a reason that applies here more than anywhere. "Anything else at all" includes a
     * payload adapter quoting the key it was asked for, a parser quoting the bytes it choked on, and
     * a driver quoting a connection URL: the failure is not the message, but its <em>text</em> is
     * routinely made of the message. The type names what happened, and the delivery comes round
     * again to say whether it is still happening.
     */
    private GuardDecision unexpectedFailure(final RuntimeException unexpected) {
        LOG.error("Delivery failed unexpectedly; returning it for redelivery. type={} reason={}",
                unexpected.getClass().getName(), ReasonCode.UNEXPECTED_FAILURE.code());
        // Counted as well as reported. An ERROR nobody is watching for is how an incident is
        // reconstructed afterwards from a dashboard that said the service was fine; the
        // classification is transient because the delivery is handed back, which is the whole of
        // what this service is claiming about it.
        metrics.pipelineFailed(FailureClassification.TRANSIENT);
        return new GuardDecision.Abandon(ReasonCode.UNEXPECTED_FAILURE);
    }

    /**
     * Puts whichever correlation identifiers a body yielded in place, and invents none.
     *
     * <p>An absent identifier stays absent. A placeholder would be searched for, found, and
     * believed.
     */
    private static void correlate(final DistributionCommandParser.Correlation correlation) {
        putIfPresent(SOURCE, correlation.source());
        putIfPresent(REQUEST_ID, correlation.requestId());
        putIfPresent(HEARING_ID, correlation.hearingId());
        putIfPresent(HEARING_DAY, correlation.hearingDay());
    }

    private static void putIfPresent(final String key, final String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    /**
     * The settlement the decision names, made once.
     *
     * <p>Whether the lock is still held is the broker's fact, and it is learned from the settlement
     * call itself: a refusal that names the lock is reported and counted under its own instrument
     * inside {@link #accepted}, and recovery is the broker's redelivery into a state machine that
     * already knows what this delivery achieved (spec FR-016). Deliberately <strong>no</strong>
     * local pre-check against {@code lockedUntil} — that would compare the broker's clock with this
     * pod's, which is the multi-node skew the data model's single-time-authority rule exists to
     * rule out, and a pod running ahead would skip settlements the broker was still willing to
     * accept, completed work included.
     */
    private void settle(
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
                // Built before the guarded call: an option-construction failure is this service's
                // own defect and must not be reported as the broker refusing a settlement.
                final DeadLetterOptions options = optionsFor(parked);
                if (accepted(SettlementOperation.DEADLETTER,
                        () -> context.deadLetter(options))) {
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
                // Reported and counted before the settlement rather than after it: the defect
                // happened whatever the broker then says about the hand-back, and a failure that is
                // only recorded when the recovery succeeds is a failure that disappears exactly
                // when things are going worst.
                LOG.error("A run decision reached settlement; the delivery is being returned. "
                                + "source={} requestId={} reason={}",
                        unfinished.claim().source(), unfinished.claim().requestId(),
                        ReasonCode.UNEXPECTED_FAILURE.code());
                metrics.pipelineFailed(FailureClassification.TRANSIENT);
                accepted(SettlementOperation.ABANDON, context::abandon);
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
            if (lockLost(refused)) {
                // The broker's own statement that the lock has gone. Not a settlement failure —
                // the machinery worked, the lock had simply run out — and not a transport fault,
                // because the refusal arrived over a connection that plainly answered. Counted
                // under its own instrument: a rise means lock renewal is not covering the runs.
                LOG.error("The delivery lock was lost before the settlement was accepted; no "
                                + "second settlement is attempted and recovery is the broker's "
                                + "redelivery. operation={}", operation.label());
                metrics.lockLost();
            } else {
                LOG.error("The broker refused the settlement; no second settlement is attempted "
                                + "and the delivery will come round again. operation={} type={}",
                        operation.label(), refused.getClass().getName());
                metrics.settlementFailed(operation);
                // The same call that failed is also the most recent thing this service knows about
                // the connection, and a refusal is the counterpart of the successful settlement the
                // queue health indicator already counts as evidence of reachability. Reported, not
                // judged: the indicator decides whether this particular refusal means the broker
                // is gone.
                health.recordSettlementRefusal(refused);
            }
        }
        if (settled) {
            // Outside the guard, and this is the whole reason the guard is exactly one call wide.
            // A settlement the broker took is a round trip it completed, which says as much about
            // reachability as a receive does — and rather more when the consumer is working through
            // a backlog it received before a blip. But recording it is telemetry, and telemetry
            // caught by the handler for "the broker refused" would report the broker as having
            // refused a settlement it had just accepted: a settlement-failure counter moving for a
            // settlement that happened, and a transport fault recorded against a connection that
            // plainly worked. A broker in perfect health would go DOWN on a dashboard because a
            // clock threw. So a failure here is allowed to propagate as itself — the delivery is
            // already settled, so nothing is at risk.
            health.recordSettlementAccepted();
        }
        return settled;
    }

    /**
     * Whether a refusal is the broker saying the delivery lock has gone.
     *
     * <p>The cause chain is walked because the SDK wraps: a blocking settlement's failure routinely
     * carries the interesting reason a level or two beneath the exception it throws. The walk is
     * bounded, so a self-referential chain supplied by a library classifies a few times and stops.
     */
    private static boolean lockLost(final Throwable refusal) {
        boolean lost = false;
        Throwable current = refusal;
        for (int depth = 0; !lost && current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            lost = switch (current) {
                case ServiceBusException serviceBus ->
                    serviceBus.getReason() == ServiceBusFailureReason.MESSAGE_LOCK_LOST;
                case AmqpException amqp ->
                    amqp.getErrorCondition() == AmqpErrorCondition.MESSAGE_LOCK_LOST;
                default -> false;
            };
            current = current.getCause();
        }
        return lost;
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
        MDC.remove(SOURCE);
        MDC.remove(REQUEST_ID);
        MDC.remove(HEARING_ID);
        MDC.remove(HEARING_DAY);
    }
}
