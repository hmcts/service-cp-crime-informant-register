package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JUnit twins of {@code HearingResultedCacheQuery/test/index.test.js}.
 *
 * <p>Same fixture, byte for byte: {@code hearing.1828f356-f746-4f2d-932b-79ef2df95c80.test.json} is
 * copied from the function app's own {@code testing/} directory into
 * {@code src/test/resources/fixtures/}, and the assertions read the same field the Jest cases read —
 * the first defendant of the first prosecution case. A hand-written payload would prove that this
 * port agrees with whatever the port's author assumed; the fixture proves it agrees with the
 * function app.
 *
 * <p>The shape of each case is the Jest case's shape too: a fake cache that answers with a stored
 * string or with nothing, and a stubbed query side. What is asserted is the source that answered and
 * the number of requests it took, because that is the whole of what this activity decides.
 *
 * <p>Three of the eight Jest cases have no twin, for reasons rather than by omission:
 *
 * <ul>
 *   <li>the two {@code EXT_} cases exercise the external hearing-details endpoint, which belongs to
 *       the court-register flow — this service consumes {@code INT_} only ({@code
 *       doc/API_CONTRACTS.md}, and the {@code SJP_}/{@code EXT_} prefixes are out of scope);</li>
 *   <li>"should throw an exception if payloadPrefix is not supplied" tests an activity input that is
 *       configuration here, not a message field: an absent prefix is refused at startup
 *       ({@code PropertiesValidator}), so no delivery can reach the adapter without one.</li>
 * </ul>
 *
 * <p>The retry twin uses two attempts because the Jest environment does: {@code
 * setJestEnvironmentVars.js} sets {@code DEFAULT_PUBLISH_RETRY_COUNT=2}, while the wrapper's own
 * default — and this service's — is three. The rule being twinned is "a 5xx is attempted again until
 * the count runs out", not the count itself.
 */
@DisplayName("Hearing payload parity with the function app")
class HearingPayloadParityTest {

    /** The hearing the Jest fixture is about. */
    private static final UUID HEARING_ID = UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80");

    /** The hearing date the dated Jest case supplies. */
    private static final LocalDate HEARING_DATE = LocalDate.of(2021, 3, 3);

    /** The field every Jest case asserts on. */
    private static final String FIRST_DEFENDANT = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private static final String PREFIX = "INT_";
    private static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/internal/" + HEARING_ID;
    private static final String IDENTITY = "dummy_key_value";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String fixture;
    private static WireMockServer server;

    private FakeCache cache;

    /**
     * The Jest {@code redisClientFake}: a store that answers with what was put in it, and with
     * nothing otherwise.
     */
    private static final class FakeCache implements HearingPayloadCache {

        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<JsonNode> read(final String key) {
            final String value = values.get(key);
            return value == null ? Optional.empty() : Optional.of(MAPPER.readTree(value));
        }

        void put(final String key, final String value) {
            values.put(key, value);
        }
    }

    @BeforeAll
    static void loadTheFixtureAndStartTheStub() throws Exception {
        try (var stream = HearingPayloadParityTest.class.getResourceAsStream(
                "/fixtures/hearing.1828f356-f746-4f2d-932b-79ef2df95c80.test.json")) {
            fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopTheStub() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        cache = new FakeCache();
    }

    private CachedHearingPayloadAdapter adapterWith(final String identity, final int maxAttempts) {
        return new CachedHearingPayloadAdapter(
                cache,
                new ResultsQueryHearingPayloadClient(
                        RestClient.builder().baseUrl(server.baseUrl()).build(),
                        identity, MAPPER, maxAttempts, Duration.ZERO),
                PREFIX);
    }

    private CachedHearingPayloadAdapter adapter() {
        return adapterWith(IDENTITY, 3);
    }

    private static DistributionCommand command(final LocalDate hearingDay) {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34"),
                HEARING_ID,
                hearingDay,
                Instant.parse("2026-08-21T08:00:00Z"),
                "Hearing_Resulted");
    }

    private static void queryAnswers(final int status, final String body) {
        server.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", ResultsQueryHearingPayloadClient.ACCEPT)
                .withBody(body)));
    }

    private static String firstDefendantOf(final JsonNode envelope) {
        return envelope.path("hearing").path("prosecutionCases").path(0)
                .path("defendants").path(0).path("id").asString();
    }

    @Nested
    @DisplayName("from the cache")
    class FromTheCache {

        /**
         * Twin of "should fetch hearing if it is in the cache", whose input carries no hearing date
         * and so reads the undated key. Every delivery to this service carries one, so the same
         * cached value is reached here through the legacy undated form — registered deviation 4.
         */
        @Test
        void fetch_should_return_the_cached_hearing_held_under_the_undated_key() {
            cache.put(HearingPayloadCacheKey.cacheKey(PREFIX, HEARING_ID, null), fixture);

            assertThat(firstDefendantOf(adapter().fetch(command(HEARING_DATE))))
                    .isEqualTo(FIRST_DEFENDANT);
        }

        /**
         * Twin of "should fetch hearing along with hearing id and hearing date if it is in the
         * cache" — the key carries the date when the delivery supplies one, which every delivery to
         * this service does.
         */
        @Test
        void fetch_should_return_the_cached_hearing_stored_under_the_dated_key() {
            cache.put(HearingPayloadCacheKey.cacheKey(PREFIX, HEARING_ID, HEARING_DATE), fixture);

            assertThat(firstDefendantOf(adapter().fetch(command(HEARING_DATE))))
                    .isEqualTo(FIRST_DEFENDANT);
        }

        @Test
        void fetch_should_not_reach_the_query_side_when_the_cache_answered() {
            cache.put(HearingPayloadCacheKey.cacheKey(PREFIX, HEARING_ID, HEARING_DATE), fixture);

            adapter().fetch(command(HEARING_DATE));

            server.verify(0, getRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("from the query side")
    class FromTheQuerySide {

        /**
         * Twin of "should fetch hearing if not in cache, if CJSCCPUID is supplied", on the
         * {@code INT_} prefix — the internal hearing-details path, which is the one this service
         * reads.
         */
        @Test
        void fetch_should_return_the_queried_hearing_when_the_cache_holds_nothing() {
            queryAnswers(200, fixture);

            assertThat(firstDefendantOf(adapter().fetch(command(HEARING_DATE))))
                    .isEqualTo(FIRST_DEFENDANT);
            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        /**
         * Twin of "should try more than once while getting timeout": a 5xx is attempted again until
         * the configured count runs out. Two attempts, because the Jest environment configures two.
         */
        @Test
        void fetch_should_attempt_the_query_side_again_after_a_server_error() {
            queryAnswers(500, "Internal Server Error");
            final CachedHearingPayloadAdapter adapter = adapterWith(IDENTITY, 2);
            final DistributionCommand command = command(HEARING_DATE);

            assertThatThrownBy(() -> adapter.fetch(command))
                    .isInstanceOf(PayloadUnavailableException.class);
            server.verify(2, getRequestedFor(urlEqualTo(PATH)));
        }

        /**
         * Twin of "should not throw an exception if not in cache and CJSCCPUID is not supplied" —
         * except that here it does raise, which is registered deviation 2. Legacy returned nothing,
         * the orchestrator skipped every remaining step, and the run reported success having
         * produced no register; this service records the request as failed and hands the delivery
         * back. The half being twinned is the half that is parity: the query side is not called at
         * all without an identity.
         */
        @Test
        void fetch_should_not_call_the_query_side_at_all_without_an_identity() {
            queryAnswers(200, fixture);
            final CachedHearingPayloadAdapter adapter = adapterWith(null, 3);
            final DistributionCommand command = command(HEARING_DATE);

            assertThatThrownBy(() -> adapter.fetch(command))
                    .isInstanceOf(PayloadUnavailableException.class);
            server.verify(0, getRequestedFor(urlEqualTo(PATH)));
        }
    }
}
