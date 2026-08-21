package uk.gov.hmcts.cp.informantregister.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.Application;

/**
 * The whole service, started by a test that needs to control <em>when</em> it starts.
 *
 * <p>{@code @SpringBootTest} builds its context before the first callback of the class and caches it
 * afterwards. Neither is what an outage suite needs. Such a suite must decide what the world looks
 * like <strong>before</strong> the context refreshes — a store that is already down is a different
 * scenario from a store that goes down later — and it must be certain no other consumer is alive on
 * the shared queue while it counts what its own message did. A context built here is built when the
 * test says so, is never cached, and is closed by the test's own try-with-resources.
 */
public final class ServiceTestSupport {

    private ServiceTestSupport() {
        // Static fixture holder.
    }

    /**
     * Starts the service against the shared containers, with the given settings applied on top.
     *
     * @param overrides settings this suite needs to differ — a different database, a faster probe
     * @return the running context, to be closed by the caller
     */
    public static ConfigurableApplicationContext start(final Map<String, String> overrides) {
        final Map<String, String> properties = new LinkedHashMap<>(defaults());
        properties.putAll(overrides);
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(asArguments(properties));
    }

    /**
     * The settings as command-line arguments, which is the only shape that actually wins.
     *
     * <p>{@code SpringApplicationBuilder.properties(...)} feeds {@code setDefaultProperties}, and
     * default properties sit <em>below</em> {@code application.yaml} in Spring's precedence order —
     * so a suite pointing the service at a Testcontainers database would silently be answered by
     * the local development default in the committed configuration, and would fail authenticating
     * against whatever was listening on port 5432. Command-line arguments sit above the
     * configuration files, which is what a container override has to do.
     */
    private static String[] asArguments(final Map<String, String> properties) {
        return properties.entrySet().stream()
                .map(setting -> "--" + setting.getKey() + '=' + setting.getValue())
                .toArray(String[]::new);
    }

    /**
     * What every suite here wants: the shared store, the shared broker, and timings short enough
     * that an outage is observable inside a test rather than inside a coffee break.
     */
    private static Map<String, String> defaults() {
        final Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", PostgresTestSupport.jdbcUrl());
        properties.put("spring.datasource.username", PostgresTestSupport.username());
        properties.put("spring.datasource.password", PostgresTestSupport.password());
        // A frozen container swallows the connection attempt rather than refusing it, so the driver
        // otherwise waits out the deployed thirty-second connect timeout on every probe and every
        // health poll. Three seconds keeps the outage observable without changing what is observed.
        properties.put("spring.datasource.hikari.connection-timeout", "3000");
        properties.put("spring.datasource.hikari.validation-timeout", "2000");
        properties.put("informantregister.servicebus.connection-string",
                ServiceBusEmulatorTestSupport.connectionString());
        // The deployed interval is ten seconds. Two makes a resume observable without making the
        // probe itself the thing under test.
        properties.put("informantregister.store.probe-interval", "2s");
        return properties;
    }

    /**
     * Publishes a body under a fresh broker identity, and returns that identity.
     *
     * <p>Fresh every time: the queue has duplicate detection on, so a republish under an identity
     * already seen would be discarded by the broker and the suite would be asserting nothing.
     *
     * @param body the message body
     * @return the broker identity the message was published under
     */
    public static String publish(final String body) {
        final String messageId = ProcessedLogTestSupport.SOURCE + ':' + UUID.randomUUID();
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(ServiceBusEmulatorTestSupport.connectionString())
                .sender()
                .queueName(ServiceBusEmulatorTestSupport.QUEUE_NAME)
                .buildClient()) {
            sender.sendMessage(
                    new ServiceBusMessage(BinaryData.fromString(body)).setMessageId(messageId));
        }
        return messageId;
    }

    /**
     * A valid request body for the given identifiers.
     */
    public static String validBody(final UUID requestId, final UUID hearingId) {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-21",
                  "sharedTime": "2026-08-21T08:00:00Z",
                  "eventType": "Hearing_Resulted"
                }
                """.formatted(requestId, hearingId);
    }

    /**
     * A body that can never validate: the contract is closed, so an extra field is a contract
     * breach rather than something to ignore.
     */
    public static String contractInvalidBody(final UUID requestId, final UUID hearingId) {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2026-08-21",
                  "sharedTime": "2026-08-21T08:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "unexpectedField": "the producer added something"
                }
                """.formatted(requestId, hearingId);
    }
}
