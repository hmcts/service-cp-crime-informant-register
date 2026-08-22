package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.support.RedisTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache read, against a real server.
 *
 * <p>The question worth answering is whether the key this service builds finds the value the
 * producer wrote, and a mocked client answers it by agreeing with whatever the test assumed. So the
 * suite writes under the literal key form and reads through {@link HearingPayloadCacheKey}, which is
 * the only arrangement in which a disagreement between the two can fail.
 *
 * <p>The unreadable-value case is parity, not tidiness. {@code getResultFromCache} runs its
 * {@code JSON.parse} inside the catch that returns {@code null}, so a value that will not parse is a
 * miss there and a miss here, and the query side gets its turn.
 *
 * <p>A cache that cannot be reached is the one thing this class does <em>not</em> absorb. It lets
 * the failure out, because deciding what an unreachable cache means belongs to the composite adapter
 * and is decided there once.
 */
@DisplayName("Lettuce hearing payload cache")
class LettuceHearingPayloadCacheIT {

    private static final String PREFIX = "INT_";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PAYLOAD =
            "{\"hearing\":{\"id\":\"%s\"},\"sharedTime\":\"2026-08-21T08:00:00Z\"}";

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> writer;

    private static LettuceHearingPayloadCache cache;

    @BeforeAll
    static void connect() {
        client = RedisClient.create(RedisURI.create(RedisTestSupport.uri()));
        writer = client.connect();
        cache = new LettuceHearingPayloadCache(client, MAPPER);
    }

    @AfterAll
    static void disconnect() {
        cache.close();
        writer.close();
        client.shutdown();
    }

    private static String write(final UUID hearingId, final LocalDate hearingDay,
            final String value) {
        final String key = HearingPayloadCacheKey.cacheKey(PREFIX, hearingId, hearingDay);
        writer.sync().set(key, value);
        return key;
    }

    @Nested
    @DisplayName("a cached payload")
    class Cached {

        @Test
        void read_should_return_the_payload_stored_under_the_dated_key() {
            final UUID hearingId = UUID.randomUUID();
            final String key = write(hearingId, LocalDate.of(2026, 8, 21),
                    PAYLOAD.formatted(hearingId));

            final Optional<JsonNode> read = cache.read(key);

            assertThat(read).isPresent();
            assertThat(read.get().path("hearing").path("id").asString())
                    .isEqualTo(hearingId.toString());
        }

        /**
         * The producer writes the key; this service only builds it. Writing under the literal form
         * and reading through the builder is what makes a disagreement between them observable.
         */
        @Test
        void read_should_find_a_payload_written_under_the_literal_key_form() {
            final UUID hearingId = UUID.randomUUID();
            writer.sync().set("INT_" + hearingId + "_2026-08-21_result_",
                    PAYLOAD.formatted(hearingId));

            assertThat(cache.read(HearingPayloadCacheKey.cacheKey(
                    PREFIX, hearingId, LocalDate.of(2026, 8, 21)))).isPresent();
        }

        @Test
        void read_should_find_a_payload_written_under_the_legacy_undated_key_form() {
            final UUID hearingId = UUID.randomUUID();
            writer.sync().set("INT_" + hearingId + "_result_", PAYLOAD.formatted(hearingId));

            assertThat(cache.read(HearingPayloadCacheKey.cacheKey(PREFIX, hearingId, null)))
                    .isPresent();
        }

        @Test
        void read_should_return_the_whole_envelope_the_producer_cached() {
            final UUID hearingId = UUID.randomUUID();
            final String key = write(hearingId, LocalDate.of(2026, 8, 21),
                    PAYLOAD.formatted(hearingId));

            assertThat(cache.read(key)).get().satisfies(node -> {
                assertThat(node.has("hearing")).isTrue();
                assertThat(node.has("sharedTime")).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("nothing cached")
    class Missing {

        @Test
        void read_should_report_nothing_when_the_key_is_absent() {
            assertThat(cache.read(HearingPayloadCacheKey.cacheKey(
                    PREFIX, UUID.randomUUID(), LocalDate.of(2026, 8, 21)))).isEmpty();
        }

        @Test
        void read_should_report_nothing_when_the_cached_value_will_not_parse() {
            final UUID hearingId = UUID.randomUUID();
            final String key = write(hearingId, LocalDate.of(2026, 8, 21), "not json at all");

            assertThat(cache.read(key)).isEmpty();
        }

        @Test
        void read_should_report_nothing_when_the_cached_value_is_empty() {
            final UUID hearingId = UUID.randomUUID();
            final String key = write(hearingId, LocalDate.of(2026, 8, 21), "");

            assertThat(cache.read(key)).isEmpty();
        }
    }

    @Nested
    @DisplayName("a cache that cannot be reached")
    class Unreachable {

        /**
         * Deliberately not absorbed here. The composite adapter catches it, logs it and goes to the
         * query side; swallowing it at this level would make that decision invisible and would leave
         * "the key is absent" and "the cache is gone" indistinguishable to every caller.
         */
        @Test
        void read_should_let_a_connection_failure_out() {
            final RedisClient nowhere = RedisClient.create(
                    RedisURI.builder()
                            .withHost("127.0.0.1")
                            .withPort(1)
                            .withTimeout(Duration.ofMillis(250))
                            .build());
            try (LettuceHearingPayloadCache unreachable =
                         new LettuceHearingPayloadCache(nowhere, MAPPER)) {
                assertThatThrownBy(() -> unreachable.read("INT_anything_result_"))
                        .isInstanceOf(RuntimeException.class);
            } finally {
                nowhere.shutdown();
            }
        }
    }
}
