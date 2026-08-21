package uk.gov.hmcts.cp.informantregister.config;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusFailureReason;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Whether the broker is reachable — reported loudly, and never allowed to gate readiness.
 *
 * <p>Registered <strong>outside</strong> the readiness group on purpose (spec FR-011). A pod cannot
 * heal a broker by restarting, so a broker in readiness turns a blip into a rolling restart of every
 * consumer at once — while the queue, which was the only thing actually wrong, stays exactly as
 * wrong as it was. The state still has to be visible, so it is its own health component and its own
 * gauge.
 *
 * <p><strong>The signal.</strong> Nothing here polls the broker: a health check that sent a probe
 * message would cost a delivery every time it ran and would race the support tooling that drains the
 * queue. It reads the two things the SDK already reports — the {@code processError} callback, and
 * the fact that a delivery arrived — and answers from the relationship between them:
 *
 * <ul>
 *   <li>a <strong>connection-class</strong> failure with nothing since means the queue is
 *       unreachable. A message-level failure — a lock lost, a message not found — does not: those
 *       arrive over a connection that plainly worked;</li>
 *   <li>traffic <strong>after</strong> the failure is the answer to the failure. A receive that
 *       succeeded says more about reachability than an error that preceded it;</li>
 *   <li>and a failure older than {@code informantregister.servicebus.health-staleness} with nothing
 *       since is <strong>not</strong> an outage. An idle queue produces no traffic, so "no traffic
 *       since the error" is the normal state of a healthy service at four in the morning, and a
 *       component that reported DOWN for it would be reporting the working day rather than the
 *       broker.</li>
 * </ul>
 *
 * <p>The gauge is set from the same evaluation, so a dashboard and a probe can never disagree about
 * what this component thinks.
 *
 * <p>Details carry a bounded condition name and two timestamps. Never the exception's own text:
 * these are transport faults whose messages carry namespaces, entity paths and, on an
 * authentication failure, whatever the credential layer felt like quoting.
 */
public class ServiceBusHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusHealthIndicator.class);

    /**
     * How deep to walk a cause chain before giving up.
     *
     * <p>Bounded because a cause chain is supplied by libraries, and a self-referential one would
     * otherwise hang a health check — which is the one thing a health check may never do. The bound
     * is the whole defence: a chain that loops simply classifies the same fault a few times and
     * stops, which needs no reference comparison to detect.
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * AMQP conditions that mean the link or the connection itself, rather than one message on it.
     */
    private static final Set<AmqpErrorCondition> MESSAGE_LEVEL_CONDITIONS = Set.of(
            AmqpErrorCondition.MESSAGE_LOCK_LOST,
            AmqpErrorCondition.MESSAGE_NOT_FOUND,
            AmqpErrorCondition.SESSION_LOCK_LOST,
            AmqpErrorCondition.SESSION_NOT_FOUND,
            AmqpErrorCondition.SESSION_CANNOT_BE_LOCKED,
            AmqpErrorCondition.LINK_PAYLOAD_SIZE_EXCEEDED);

    /**
     * Service Bus failure reasons that mean this consumer cannot reach its queue at all.
     *
     * <p>Authentication and entity-not-found are here beside the transport faults deliberately: a
     * credential that has expired and a queue that has been renamed are both "this pod is not
     * consuming anything until somebody changes something", which is exactly what the component is
     * for. They are not readiness, because restarting fixes neither.
     */
    private static final Set<ServiceBusFailureReason> UNREACHABLE_REASONS = Set.of(
            ServiceBusFailureReason.SERVICE_COMMUNICATION_ERROR,
            ServiceBusFailureReason.SERVICE_TIMEOUT,
            ServiceBusFailureReason.SERVICE_BUSY,
            ServiceBusFailureReason.UNAUTHORIZED,
            ServiceBusFailureReason.MESSAGING_ENTITY_NOT_FOUND,
            ServiceBusFailureReason.MESSAGING_ENTITY_DISABLED);

    /**
     * Service Bus failure reasons that are about one message rather than about the connection.
     */
    private static final Set<ServiceBusFailureReason> MESSAGE_LEVEL_REASONS = Set.of(
            ServiceBusFailureReason.MESSAGE_LOCK_LOST,
            ServiceBusFailureReason.MESSAGE_NOT_FOUND,
            ServiceBusFailureReason.MESSAGE_SIZE_EXCEEDED,
            ServiceBusFailureReason.SESSION_LOCK_LOST,
            ServiceBusFailureReason.SESSION_CANNOT_BE_LOCKED);

    /** What an AMQP fault with no condition of its own is called. */
    private static final String AMQP_TRANSPORT = "AMQP_TRANSPORT";

    /** What a refusal the SDK described only in its own vocabulary is called. */
    private static final String SETTLEMENT_REFUSED = "SETTLEMENT_REFUSED";

    /** What a bare network fault is called. */
    private static final String NETWORK = "NETWORK";

    private static final String NONE = "none";

    private final Duration staleness;
    private final Clock clock;

    /** The last connection-class failure, if one is still unanswered. */
    private final AtomicReference<Fault> lastFault = new AtomicReference<>();

    /** When the broker last did something for us. */
    private final AtomicReference<Instant> lastTraffic = new AtomicReference<>();

    /**
     * When intake actually started, so "we have never once heard from the broker" can be told apart
     * both from "we have not heard from it lately" and from "we have not asked it anything yet".
     *
     * <p>Null until the processor is running. A pod gated on its store can sit in that state for a
     * long time, and a broker-DOWN alert raised because a <em>database</em> was down would send
     * somebody to the wrong system entirely.
     */
    private final AtomicReference<Instant> intakeStartedAt = new AtomicReference<>();

    /** Creates the indicator; {@code staleness} bounds how old a probe result may be. */
    public ServiceBusHealthIndicator(
            final Duration staleness, final ProcessingMetrics metrics, final Clock clock) {
        this.staleness = staleness;
        this.clock = clock;
        // The gauge asks this component the same question the health endpoint asks, at the moment
        // it is asked. Prometheus does not call the health endpoint on its way past, and both
        // answers move with time rather than only with events.
        metrics.bindServiceBusUp(this::reachableNow);
    }

    /**
     * Records a fault the processor reported outside a delivery.
     *
     * <p>Only a connection-class failure is remembered. Everything else the processor can report is
     * either about one message — and the message paths have their own instruments — or about this
     * service, and neither makes the queue unreachable.
     *
     * @param failure what the processor reported
     */
    public void recordProcessorError(
            final String errorSource, final String entityPath, final Throwable failure) {
        final Optional<String> condition = connectionCondition(failure);
        // Reported by what it is, never by what it said. A transport fault's message is written by
        // the far end: it carries namespaces, entity paths, tracking ids and — on an authentication
        // failure — whatever the credential layer felt like quoting. The type and the derived
        // condition are this service's own vocabulary and are enough to act on.
        LOG.error("Service Bus processor error. source={} entityPath={} type={} condition={}",
                errorSource, entityPath, failure.getClass().getName(), condition.orElse(NONE));
        condition.ifPresent(named -> lastFault.set(new Fault(named, clock.instant())));
    }

    /**
     * Records a settlement the broker refused.
     *
     * <p><strong>Not in research §8's original pair of inputs, and here because the pair does not
     * work.</strong> §8 expected the SDK's {@code processError} callback to report a broker that
     * had gone away. Measured against the emulator it does not: with the broker's container stopped
     * outright, an idle consumer received no callback in five minutes, because
     * {@code ServiceBusProcessorClient} treats a lost connection as retryable and rolls its message
     * pump silently and indefinitely. The first callback of any kind arrived only once the broker
     * came back.
     *
     * <p>A refused settlement is the exact counterpart of the input §8 does name — "the timestamp of
     * the last successful receive or settlement" — costs nothing extra, pollutes no queue, and is
     * the signal that exists precisely when an outage matters most: while there is work in hand.
     * The change is recorded as a deviation for review.
     *
     * @param refusal what the broker answered the settlement call with
     */
    public void recordSettlementRefusal(final Throwable refusal) {
        // Classified the other way round from a processor error, and deliberately. A settlement is
        // a round trip the broker was asked to complete and did not, so the presumption is that the
        // transport is the problem — unless the fault is recognisably about this one message, which
        // arrives over a connection that plainly worked. Reading it the other way does not survive
        // contact with the SDK: a blocking settlement reports its failure wrapped in a Reactor
        // exception whose cause chain routinely says nothing about AMQP at all, so a rule that
        // waited to be told "connection" would wait for ever. A wrong DOWN costs one health cycle
        // and is cleared by the next successful round trip; a wrong UP hides an outage.
        if (!aboutThisMessage(refusal)) {
            lastFault.set(new Fault(
                    connectionCondition(refusal).orElse(SETTLEMENT_REFUSED), clock.instant()));
        }
    }

    /**
     * Whether a refusal is about the message rather than about the connection.
     */
    private static boolean aboutThisMessage(final Throwable refusal) {
        boolean thisMessage = false;
        Throwable current = refusal;
        for (int depth = 0; !thisMessage && current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            thisMessage = messageLevel(current);
            current = current.getCause();
        }
        return thisMessage;
    }

    private static boolean messageLevel(final Throwable failure) {
        return switch (failure) {
            case AmqpException amqp -> contains(MESSAGE_LEVEL_CONDITIONS, amqp.getErrorCondition());
            case ServiceBusException serviceBus ->
                contains(MESSAGE_LEVEL_REASONS, serviceBus.getReason());
            default -> false;
        };
    }

    /**
     * Set membership that tolerates an absent value.
     *
     * <p>The SDK does not promise a condition or a reason on every fault it raises, and an
     * immutable set answers {@code contains(null)} with a NullPointerException rather than
     * {@code false}. A health check that threw would take the whole endpoint down over a fault it
     * merely failed to recognise — the one thing a health check may never do.
     */
    private static boolean contains(final Set<?> known, final Object value) {
        return value != null && known.contains(value);
    }



    /**
     * Records that the broker answered: a delivery arrived.
     */
    public void recordTraffic() {
        lastTraffic.set(clock.instant());
    }

    /**
     * Records that the broker accepted a settlement.
     *
     * <p>Traffic is traffic. Research §8 names "the last successful receive <em>or settlement</em>"
     * for a reason: a consumer working steadily through a backlog it received before a blip is
     * completing round trips constantly, and one that counted only receives would report an outage
     * it is plainly not having.
     */
    public void recordSettlementAccepted() {
        lastTraffic.set(clock.instant());
    }

    /**
     * Records that intake has actually started, which is when this component starts having an
     * opinion about a broker nobody has yet spoken to.
     */
    public void recordIntakeStarted() {
        intakeStartedAt.compareAndSet(null, clock.instant());
    }

    /**
     * Whether the broker is reachable, evaluated now.
     */
    public boolean reachableNow() {
        return reachable(lastFault.get(), lastTraffic.get());
    }

    @Override
    public Health health() {
        final Fault fault = lastFault.get();
        final Instant traffic = lastTraffic.get();
        final boolean up = reachable(fault, traffic);

        return (up ? Health.up() : Health.down())
                .withDetail("condition", fault == null ? NONE : fault.condition())
                .withDetail("lastErrorAt", fault == null ? NONE : fault.at().toString())
                .withDetail("lastTrafficAt", traffic == null ? NONE : traffic.toString())
                .withDetail("stalenessWindow", staleness.toString())
                .withDetail("intakeStartedAt", startedAtOrNone())
                .build();
    }

    /**
     * The rule, in one place.
     *
     * <p>A recorded failure is answered by one of two things: traffic since — a receive that
     * succeeded says more about reachability than an error that preceded it — or age, <em>for a
     * consumer the broker has answered before</em>. A failure nothing has repeated for longer than
     * the staleness window stops being reported, strictly longer, because a failure exactly on the
     * window is not yet older than it. That is the rule that keeps an idle queue from looking like
     * an outage — and it is conditional on ever having heard from the broker, because an idle queue
     * is only the innocent explanation of silence when the connection is known to have worked. For
     * a consumer that has <em>never once</em> been answered, the recorded fault is the last thing
     * the transport ever said, the SDK will not repeat it (see {@link #recordSettlementRefusal}),
     * and letting it quietly age into UP would hide a total outage behind the very rule meant to
     * excuse a quiet night. Such a consumer keeps the same startup grace an unfaulted start gets,
     * and after it says DOWN until first contact.
     *
     * <p>With no failure recorded, the question is whether this consumer has <em>ever</em> heard
     * from the broker. Not having heard lately is normal; not having heard at all is not, and it is
     * the only evidence available for a pod that started while the queue was unavailable — the SDK
     * reports nothing at all in that case. One staleness window of grace covers an ordinary start,
     * and after it a consumer that has never once been answered says so. Any contact at all clears
     * it permanently.
     */
    private boolean reachable(final Fault fault, final Instant traffic) {
        final boolean answered;
        if (fault == null) {
            answered = traffic != null || withinStartupGrace();
        } else if (traffic == null) {
            answered = withinStartupGrace();
        } else {
            answered = traffic.isAfter(fault.at())
                    || Duration.between(fault.at(), clock.instant()).compareTo(staleness) > 0;
        }
        return answered;
    }

    /**
     * Whether a consumer that has never been answered is still entitled to the benefit of the doubt.
     *
     * <p>Strictly inside the window, so a <em>completed</em> window reports the outage — the same
     * boundary the fault rule uses from the other side, where a failure exactly as old as the
     * window is not yet older than it. Both edges therefore report DOWN, which is the answer that
     * costs an operator a look rather than a register.
     */
    private boolean withinStartupGrace() {
        final Instant startedAt = intakeStartedAt.get();
        return startedAt == null
                || Duration.between(startedAt, clock.instant()).compareTo(staleness) < 0;
    }

    /**
     * The bounded name for a failure that means the queue is unreachable, if it is one.
     *
     * <p>The whole cause chain is examined, because the SDK wraps: the interesting condition is
     * routinely two or three levels beneath a {@code ServiceBusException} whose own reason is only
     * {@code GENERAL_ERROR}.
     *
     * @return the condition's bounded name, or empty if this failure says nothing about reachability
     */
    private static Optional<String> connectionCondition(final Throwable failure) {
        Optional<String> condition = Optional.empty();
        Throwable current = failure;
        for (int depth = 0;
             condition.isEmpty() && current != null && depth < MAX_CAUSE_DEPTH;
             depth++) {
            condition = conditionOf(current);
            current = current.getCause();
        }
        return condition;
    }

    private static Optional<String> conditionOf(final Throwable failure) {
        return switch (failure) {
            case AmqpException amqp -> amqpCondition(amqp);
            case ServiceBusException serviceBus -> serviceBusCondition(serviceBus);
            case SSLException ignored -> Optional.of(NETWORK);
            case TimeoutException ignored -> Optional.of(NETWORK);
            case IOException ignored -> Optional.of(NETWORK);
            default -> Optional.empty();
        };
    }

    /**
     * An AMQP fault is about the connection unless its condition says it is about one message.
     *
     * <p>That way round deliberately: a condition this service has never heard of arriving from the
     * transport layer is far more likely to be a new way of losing a connection than a new kind of
     * message-level complaint, and reporting an outage that is not one is recoverable — the next
     * receive clears it — while missing one is the silence this service exists to remove.
     */
    private static Optional<String> amqpCondition(final AmqpException amqp) {
        final AmqpErrorCondition condition = amqp.getErrorCondition();
        final Optional<String> named;
        if (condition == null) {
            named = Optional.of(AMQP_TRANSPORT);
        } else {
            named = contains(MESSAGE_LEVEL_CONDITIONS, condition)
                    ? Optional.empty()
                    : Optional.of(condition.name());
        }
        return named;
    }

    private static Optional<String> serviceBusCondition(final ServiceBusException serviceBus) {
        final ServiceBusFailureReason reason = serviceBus.getReason();
        return contains(UNREACHABLE_REASONS, reason)
                ? Optional.of(reason.toString())
                : Optional.empty();
    }

    private String startedAtOrNone() {
        final Instant startedAt = intakeStartedAt.get();
        return startedAt == null ? NONE : startedAt.toString();
    }

    /**
     * A connection-class failure and when it happened. Never the exception itself: nothing beyond
     * these two values may reach a health response.
     */
    private record Fault(String condition, Instant at) {
    }
}
