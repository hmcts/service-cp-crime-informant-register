package uk.gov.hmcts.cp.simulation;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Seeds the ephemeral validation Redis before a message is published. The same hearing envelope
 * backs the query-API WireMock response, so cache hits and misses transform into the same registers.
 * An absent {@code gatling.redisUri} keeps local emulator runs on the query fallback.
 */
final class RedisPayloadSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(RedisPayloadSeeder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RedisPayloadSeeder INSTANCE = create();

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final JsonNode envelopeTemplate;
    private final int hitPercent;
    private final AtomicLong seeded = new AtomicLong();
    private final AtomicLong unseeded = new AtomicLong();

    private RedisPayloadSeeder(final String uri, final int configuredHitPercent) {
        if (configuredHitPercent < 0 || configuredHitPercent > 100) {
            throw new IllegalArgumentException("gatling.redisHitPercent must be between 0 and 100");
        }
        hitPercent = configuredHitPercent;
        envelopeTemplate = loadEnvelope();
        client = RedisClient.create(uri);
        connection = client.connect();
        if (!"PONG".equals(connection.sync().ping())) {
            throw new IllegalStateException("Validation Redis did not answer PING");
        }
        LOG.info("Validation Redis ready; {}% of unique hearings will be cached", hitPercent);
    }

    static void verifyReady() {
        if (INSTANCE != null && !"PONG".equals(INSTANCE.connection.sync().ping())) {
            throw new IllegalStateException("Validation Redis stopped answering PING");
        }
    }

    static void seed(final PublishedMessage message) {
        if (INSTANCE == null) {
            return;
        }
        INSTANCE.seedMessage(message);
    }

    static void close() {
        if (INSTANCE != null) {
            LOG.info("Validation Redis seeding complete: cachedPublishes={} fallbackPublishes={}",
                    INSTANCE.seeded.get(), INSTANCE.unseeded.get());
            INSTANCE.connection.close();
            INSTANCE.client.shutdown();
        }
    }

    private void seedMessage(final PublishedMessage message) {
        // A duplicate has the same hearingId and therefore keeps the same cache-hit decision.
        if (Math.floorMod(message.hearingId().hashCode(), 100) >= hitPercent) {
            unseeded.incrementAndGet();
            return;
        }
        final ObjectNode envelope = (ObjectNode) envelopeTemplate.deepCopy();
        ((ObjectNode) envelope.path("hearing")).put("id", message.hearingId().toString());
        final String key = "INT_" + message.hearingId() + '_'
                + message.hearingDay() + "_result_";
        // The namespace is ephemeral, so the test keys are removed with the Redis pod.
        connection.sync().set(key, MAPPER.writeValueAsString(envelope));
        seeded.incrementAndGet();
    }

    private static RedisPayloadSeeder create() {
        final String uri = System.getProperty("gatling.redisUri", "");
        if (uri.isBlank()) {
            return null;
        }
        return new RedisPayloadSeeder(uri,
                Integer.parseInt(System.getProperty("gatling.redisHitPercent", "80")));
    }

    private static JsonNode loadEnvelope() {
        try (InputStream in = RedisPayloadSeeder.class.getClassLoader().getResourceAsStream(
                "wiremock/mappings/hearing-payload.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing hearing-payload WireMock mapping");
            }
            final JsonNode envelope = MAPPER.readTree(in).path("response").path("jsonBody");
            if (!(envelope instanceof ObjectNode) || !(envelope.path("hearing") instanceof ObjectNode)) {
                throw new IllegalStateException("Hearing-payload mapping has no JSON hearing envelope");
            }
            return envelope;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read hearing-payload WireMock mapping", e);
        }
    }
}
