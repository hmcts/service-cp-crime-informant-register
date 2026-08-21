package uk.gov.hmcts.cp.informantregister.inbound;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.InformantRegisterProperties;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;

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
 * beans on purpose. In this increment the lifecycle bean below starts it once the context is up and
 * stops it on shutdown, which is honest for a skeleton with no store-outage handling yet. The
 * store-probe-gated start — do not consume anything until the processed log has answered, because a
 * service that consumes without a store abandons a queue's worth of deliveries — belongs to the
 * consumer lifecycle controller in the observability story. That controller replaces this one bean;
 * the client definition, its settings and the listener are untouched by the change.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "informantregister.consumer", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ServiceBusConsumerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusConsumerConfig.class);

    @Bean
    public InformantRegisterMessageListener informantRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics) {
        return new InformantRegisterMessageListener(parser, pipeline, metrics);
    }

    /**
     * The processor client, built but deliberately not started.
     */
    @Bean(destroyMethod = "close")
    public ServiceBusProcessorClient informantRegisterProcessorClient(
            final InformantRegisterProperties properties,
            final InformantRegisterMessageListener listener) {
        final InformantRegisterProperties.Servicebus settings = properties.servicebus();
        LOG.info("Building the Service Bus consumer. queue={} maxConcurrentCalls={} "
                        + "maxAutoLockRenewDuration={} credential={}",
                settings.queueName(), settings.maxConcurrentCalls(),
                settings.maxAutoLockRenewDuration(), credentialSource(settings));
        return credentialledBuilder(settings)
                .processor()
                .queueName(settings.queueName())
                .maxConcurrentCalls(settings.maxConcurrentCalls())
                .maxAutoLockRenewDuration(settings.maxAutoLockRenewDuration())
                .disableAutoComplete()
                .processMessage(listener::onMessage)
                .processError(ServiceBusConsumerConfig::reportProcessorError)
                .buildProcessorClient();
    }

    /**
     * Starts intake with the context and stops it with the shutdown.
     */
    @Bean
    public SmartLifecycle informantRegisterProcessorLifecycle(
            final ServiceBusProcessorClient processor) {
        return new ProcessorLifecycle(processor);
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
     * <p>Logged and nothing else for now: there is no delivery to settle here, and the queue-health
     * indicator that will consume this signal arrives with the observability story. The exception is
     * carried because these are transport faults, not message content.
     */
    private static void reportProcessorError(final ServiceBusErrorContext error) {
        LOG.error("Service Bus processor error. source={} entityPath={}",
                error.getErrorSource(), error.getEntityPath(), error.getException());
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * The one bean the lifecycle controller will take over.
     *
     * <p>A {@link SmartLifecycle} rather than a start call inside the factory method, because a
     * processor started while the context is still refreshing would be consuming against beans that
     * are not there yet.
     */
    static final class ProcessorLifecycle implements SmartLifecycle {

        private final ServiceBusProcessorClient processor;
        private volatile boolean started;

        ProcessorLifecycle(final ServiceBusProcessorClient processor) {
            this.processor = processor;
        }

        @Override
        public void start() {
            processor.start();
            started = true;
            LOG.info("Intake started. queue={}", processor.getQueueName());
        }

        @Override
        public void stop() {
            processor.stop();
            started = false;
            LOG.info("Intake stopped. queue={}", processor.getQueueName());
        }

        @Override
        public boolean isRunning() {
            return started;
        }
    }
}
