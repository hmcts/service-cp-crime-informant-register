package uk.gov.hmcts.cp.simulation;

import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.jms.JmsDsl.jms;

import io.gatling.javaapi.core.ScenarioBuilder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The publish chain shared by every scenario: generate a message, publish it, and record it in the
 * {@link PublishManifest} only once the send has succeeded.
 *
 * <p>When an ephemeral Redis URI is configured, the claim-check payload is seeded before the send
 * and before the timestamp; a hearing left out of Redis gets its own query-fallback WireMock stub
 * instead ({@link FallbackHearingStubs}). The verifier's publish-to-POST latency includes the broker publish but
 * not fixture setup. A failed send exits the virtual user before the manifest write: a message that
 * never reached the queue must not be counted as a request that produced no output.
 */
final class PublishScenarios {

    private PublishScenarios() {
    }

    static ScenarioBuilder publishing(final String simulation, final String scenarioName,
                                      final String requestName, final String queueName,
                                      final AtomicInteger publishedCount) {
        return scenario(scenarioName)
                .exec(session -> {
                    final PublishedMessage message = MessageBuilder.next();
                    if (!RedisPayloadSeeder.seed(message)) {
                        FallbackHearingStubs.register(message);
                    }
                    return session.set("message", message)
                            // bytesMessage(String) below resolves the EL to the session value and
                            // casts it straight to byte[] — it does not encode a String for you,
                            // unlike textMessage. The payload has to already be bytes.
                            .set("payload", message.body().getBytes(StandardCharsets.UTF_8))
                            .set("publishedAt", System.currentTimeMillis());
                })
                .exec(jms(requestName).send()
                        .queue(queueName)
                        // A JMS TextMessage encodes on the wire as an AMQP AmqpValue body, which
                        // ServiceBusReceivedMessage#getBody() cannot read ("This body type not is
                        // supported: VALUE"); a BytesMessage carries a Data section, which it can.
                        .bytesMessage("#{payload}"))
                .exitHereIfFailed()
                .exec(session -> {
                    PublishManifest.record(simulation, scenarioName,
                            session.<PublishedMessage>get("message"),
                            session.getLong("publishedAt"));
                    publishedCount.incrementAndGet();
                    return session;
                });
    }
}
