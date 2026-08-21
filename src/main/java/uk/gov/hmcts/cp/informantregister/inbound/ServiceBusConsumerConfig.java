package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.InformantRegisterProperties;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedLogProbe;

/**
 * The consumer, built entirely from the typed settings.
 *
 * <p>Nothing about the broker is decided here: the queue, the concurrency, the lock-renewal window
 * and the credential all come from {@code informantregister.servicebus.*}, which startup validation
 * has already refused to let through in an unsafe combination. Peek-lock with auto-complete
 * disabled is the one setting that is not configurable — a message completed by the container rather
 * than by the code that recorded its outcome is the failure mode this service exists to remove
 * (constitution Principle VI).
 *
 * <p><strong>Who starts the processor.</strong> Building the client and starting it are separate
 * beans on purpose, and nothing here starts it. {@link ConsumerLifecycleController} does, once the
 * processed log has answered a probe and the deferred migration has run — because a service that
 * consumes without a store abandons a queue's worth of deliveries, and one that consumes against an
 * unmigrated schema loses them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "informantregister.consumer", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ServiceBusConsumerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusConsumerConfig.class);

    /**
     * The reconnection budget, fixed here rather than inherited (research §8).
     *
     * <p>Spec SC-004 gives the service sixty seconds from the queue returning to be consuming
     * again. The SDK's defaults are chosen for a client that can afford to wait; this one cannot,
     * because every second past the budget is a resulted hearing not being distributed. Exponential
     * back-off from half a second, capped at ten, with five attempts and a thirty-second try
     * timeout, brings a reconnection comfortably inside the budget while still backing off enough
     * not to hammer a broker that is genuinely down.
     */
    private static final AmqpRetryOptions RECONNECTION = new AmqpRetryOptions()
            .setMode(AmqpRetryMode.EXPONENTIAL)
            .setMaxRetries(5)
            .setDelay(Duration.ofMillis(500))
            .setMaxDelay(Duration.ofSeconds(10))
            .setTryTimeout(Duration.ofSeconds(30));

    /**
     * The broker's own health component — deliberately not in the readiness group.
     *
     * <p>Named so that Spring's contributor naming yields {@code servicebus}: the component name is
     * what a probe, a dashboard and a runbook all refer to, so it is chosen here rather than
     * inherited from a class name somebody may later rename.
     */
    @Bean
    public ServiceBusHealthIndicator servicebusHealthIndicator(
            final InformantRegisterProperties properties,
            final ProcessingMetrics metrics,
            final Clock clock) {
        return new ServiceBusHealthIndicator(
                properties.servicebus().healthStaleness(), metrics, clock);
    }

    @Bean
    public InformantRegisterMessageListener informantRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics,
            final ServiceBusHealthIndicator health,
            final ObjectProvider<ConsumerLifecycleController> lifecycle,
            final InformantRegisterProperties properties) {
        // The delivery budget is the queue's, mirrored in configuration: the listener recognises the
        // final permitted delivery from it, so the two are changed together or this service is wrong
        // about the broker.
        return new InformantRegisterMessageListener(
                parser, pipeline, metrics, health, new DeferredStoreGate(lifecycle::getObject),
                properties.servicebus().maxDeliveryCount());
    }

    /**
     * The store gate, resolved when it is used rather than when the listener is built.
     *
     * <p>There is a genuine cycle in the object graph and it is not an accident of wiring: the
     * controller owns the processor, the processor is built around the listener, and the listener
     * has to be able to ask the controller to stop intake. Something has to be late, and the gate is
     * the honest place — it is only ever needed while a delivery is being handled, by which time
     * every bean in the cycle exists.
     */
    private record DeferredStoreGate(Supplier<ConsumerLifecycleController> lifecycle)
            implements StoreGate {

        @Override
        public boolean storeAvailable() {
            return lifecycle.get().storeAvailable();
        }

        @Override
        public void suspendIntake() {
            lifecycle.get().suspendIntake();
        }
    }

    /**
     * The processor client, built but deliberately not started.
     */
    @Bean(destroyMethod = "close")
    public ServiceBusProcessorClient informantRegisterProcessorClient(
            final InformantRegisterProperties properties,
            final InformantRegisterMessageListener listener,
            final ServiceBusHealthIndicator health) {
        final InformantRegisterProperties.Servicebus settings = properties.servicebus();
        LOG.info("Building the Service Bus consumer. queue={} maxConcurrentCalls={} "
                        + "maxAutoLockRenewDuration={} credential={}",
                settings.queueName(), settings.maxConcurrentCalls(),
                settings.maxAutoLockRenewDuration(), credentialSource(settings));
        return credentialledBuilder(settings)
                .retryOptions(RECONNECTION)
                .processor()
                .queueName(settings.queueName())
                .maxConcurrentCalls(settings.maxConcurrentCalls())
                .maxAutoLockRenewDuration(settings.maxAutoLockRenewDuration())
                .disableAutoComplete()
                .processMessage(delivery -> handle(delivery, listener, health))
                .processError(error -> reportProcessorError(error, health))
                .buildProcessorClient();
    }

    /**
     * One delivery, and the transport fact it carries.
     *
     * <p>The arrival is recorded here rather than inside the listener because it is a statement
     * about the connection, not about the request: the broker handed us a message, so the broker is
     * reachable, whatever this particular message turns out to be worth. Recording it at the same
     * boundary as {@code processError} keeps the health indicator's two inputs beside each other
     * and leaves the listener to settlement, which is its whole job.
     */
    private static void handle(
            final ServiceBusReceivedMessageContext delivery,
            final InformantRegisterMessageListener listener,
            final ServiceBusHealthIndicator health) {
        health.recordTraffic();
        listener.onMessage(delivery);
    }

    /**
     * The one component permitted to start or stop intake (research §7).
     *
     * <p>It replaces the plain start-with-the-context lifecycle this configuration used to carry.
     * The client definition above, its settings and the listener are untouched by the change — the
     * difference is entirely <em>when</em> the processor is started, and by what.
     */
    @Bean
    public ConsumerLifecycleController informantRegisterConsumerLifecycle(
            final ServiceBusProcessorClient processor,
            final ProcessedLogProbe storeProbe,
            final ObjectProvider<Flyway> flyway,
            final ProcessingMetrics metrics,
            final InformantRegisterProperties properties) {
        return new ConsumerLifecycleController(
                processor, storeProbe, flyway::getIfAvailable, metrics,
                properties.store().probeInterval());
    }

    /**
     * A selection, not a preference.
     *
     * <p>Exactly one of the connection string and the namespace is set — startup validation refuses
     * both and refuses neither — so there is no ordering here that could quietly send a deployed pod
     * to the wrong broker.
     */
    private static ServiceBusClientBuilder credentialledBuilder(
            final InformantRegisterProperties.Servicebus settings) {
        final ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (hasText(settings.connectionString())) {
            builder.connectionString(settings.connectionString());
        } else {
            builder.fullyQualifiedNamespace(settings.namespace())
                    .credential(new DefaultAzureCredentialBuilder().build());
        }
        return builder;
    }

    /** Which source was chosen, for the startup log. Never the credential itself. */
    private static String credentialSource(final InformantRegisterProperties.Servicebus settings) {
        return hasText(settings.connectionString()) ? "connection-string" : "workload-identity";
    }

    /**
     * Errors the processor reports outside a delivery — connection and link failures, chiefly.
     *
     * <p>There is no delivery to settle here, so the fault is reported and handed to the health
     * indicator, which decides whether it means the queue is unreachable. The exception itself is
     * carried into the log because these are transport faults rather than message content: nothing
     * a producer wrote and nothing about a defendant can appear in one, and a connection failure
     * with no diagnostics is the one nobody ever explains.
     */
    private static void reportProcessorError(
            final ServiceBusErrorContext error, final ServiceBusHealthIndicator health) {
        LOG.error("Service Bus processor error. source={} entityPath={}",
                error.getErrorSource(), error.getEntityPath(), error.getException());
        health.recordProcessorError(error.getException());
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
