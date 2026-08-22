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
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.RedisTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * <p>A cache that cannot be reached is absorbed here, and only here: this is the class that knows
 * what a failure of the cache technology looks like, so it is the class that may call one a miss.
 * That is registered deviation 5 — legacy absorbs a failed {@code GET} but not a failed connection.
 * Anything that is not the cache's own failure is let out, because it is a defect rather than a miss.
 */
@DisplayName("Lettuce hearing payload cache")
class LettuceHearingPayloadCacheIT {

    private static final String PREFIX = "INT_";

    /** Stands in for the defendant detail a corrupt document would have the parser quote back. */
    private static final String DEFENDANT_MARKER = "DEFENDANTMARKERZQX7";

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

        /**
         * A cached {@code null} literal parses perfectly well and carries no payload. Handing it on
         * would put a null node into a transformation that has no way to tell it from a hearing.
         */
        @Test
        void read_should_report_nothing_when_the_cached_value_is_the_json_null_literal() {
            final UUID hearingId = UUID.randomUUID();
            final String key = write(hearingId, LocalDate.of(2026, 8, 21), "null");

            assertThat(cache.read(key)).isEmpty();
        }
    }

    @Nested
    @DisplayName("connection lifecycle")
    class Lifecycle {

        /**
         * A pod runs for weeks and a cache is restarted inside them, so the first read after a
         * connection has gone must open another one rather than fail for ever on a closed handle.
         */
        @Test
        void read_should_open_a_fresh_connection_after_the_previous_one_was_closed() {
            final UUID hearingId = UUID.randomUUID();
            final String key = write(hearingId, LocalDate.of(2026, 8, 21),
                    PAYLOAD.formatted(hearingId));
            assertThat(cache.read(key)).isPresent();

            cache.close();

            assertThat(cache.read(key)).isPresent();
        }

        @Test
        void close_should_be_safe_to_call_when_nothing_is_open() {
            cache.close();

            assertThatCode(cache::close).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("a cache that cannot be reached — registered deviation 5")
    class Unreachable {

        private static LettuceHearingPayloadCache cacheNowhere(final RedisClient nowhere) {
            return new LettuceHearingPayloadCache(nowhere, MAPPER);
        }

        private static RedisClient nowhere() {
            return RedisClient.create(
                    RedisURI.builder()
                            .withHost("127.0.0.1")
                            .withPort(1)
                            .withTimeout(Duration.ofMillis(250))
                            .build());
        }

        /**
         * Registered deviation 5. The function app obtains its client outside the catch that absorbs
         * a failed {@code GET}, so a connection it cannot make throws out of the activity and the
         * query side is never asked. Here it is a miss, because the design rules classify the
         * transient case as "both Redis <em>and</em> the fallback unavailable" — which presumes the
         * fallback is attempted when the cache is not there.
         */
        @Test
        void read_should_report_nothing_when_the_cache_cannot_be_reached() {
            final RedisClient nowhere = nowhere();
            try (LettuceHearingPayloadCache unreachable = cacheNowhere(nowhere)) {
                assertThat(unreachable.read("INT_anything_result_")).isEmpty();
            } finally {
                nowhere.shutdown();
            }
        }

        /**
         * The outage is absorbed, not hidden: there is a line for it, and the line names the failure
         * by type rather than by the client's own words, which carry the address and the credentials
         * the connection was attempted with.
         */
        @Test
        void read_should_report_an_unreachable_cache_without_quoting_the_client() {
            final RedisClient nowhere = nowhere();
            try (LettuceHearingPayloadCache unreachable = cacheNowhere(nowhere);
                 CapturedLog log = CapturedLog.of(LettuceHearingPayloadCache.class)) {
                unreachable.read("INT_anything_result_");

                assertThat(log.messages()).anyMatch(line -> line.contains("could not answer"));
                assertThat(log.events()).allSatisfy(event ->
                        assertThat(event.getThrowableProxy()).isNull());
            } finally {
                nowhere.shutdown();
            }
        }

        /**
         * Only the cache's own failures are absorbed. A client that fails for any other reason is a
         * defect in this service, and a defect that came back as a miss would be paid for with a
         * query-side round trip and never seen again.
         */
        @Test
        void read_should_let_a_failure_that_is_not_the_cache_technology_out() {
            final RedisClient broken = mock(RedisClient.class);
            when(broken.connect()).thenThrow(new IllegalStateException("a defect, not a miss"));

            try (LettuceHearingPayloadCache unreachable = cacheNowhere(broken)) {
                assertThatThrownBy(() -> unreachable.read("INT_anything_result_"))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Nested
    @DisplayName("a corrupt cached value")
    class Corrupt {

        /**
         * A parser quotes the token it choked on. In a truncated hearing document that token is a
         * name, an address or a URN, so the line reports the failure's type and nothing the producer
         * wrote — the rule {@code DistributionCommandParser} already follows for a message body.
         */
        @Test
        void read_should_not_write_out_anything_the_corrupt_value_contained() {
            final UUID hearingId = UUID.randomUUID();
            final String key = write(hearingId, LocalDate.of(2026, 8, 21),
                    "{\"hearing\":{\"defendant\": " + DEFENDANT_MARKER);

            try (CapturedLog log = CapturedLog.of(LettuceHearingPayloadCache.class)) {
                assertThat(cache.read(key)).isEmpty();

                assertThat(log.renderings())
                        .as("the parser's words quote the payload it failed on")
                        .noneMatch(line -> line.contains(DEFENDANT_MARKER));
                assertThat(log.messages()).anyMatch(line -> line.contains("could not be parsed"));
            }
        }
    }
}
