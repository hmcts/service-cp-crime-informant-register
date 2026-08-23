package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback read, against a stub that answers exactly as the query side does.
 *
 * <p>Three things are the query side's contract and not this service's: the path, the vendor media
 * type and the identity header. A wrong media type is answered with a 406 by the real service and
 * with a perfectly good payload by any stub that does not check, so the stub checks.
 *
 * <p>The retry rule is asserted by counting requests, because it is the only way to see it. The
 * function app's wrapper abandons the moment a response arrives carrying a status of 429 or below
 * and retries anything above it, which makes 429 the single least-retried failure there is. That is
 * a defect in the legacy policy and it is ported deliberately (constitution Principle I), so it is
 * pinned here rather than left to be "fixed" by the next person who reads the loop.
 *
 * <p>Not-found deserves its own note. The query side answers a hearing it does not have with
 * {@code 200} and an empty object, never a {@code 404} — {@code ResultsQueryView} builds an empty
 * object and returns it — so "the response parsed" cannot be the success test. Content is.
 */
@DisplayName("Results query hearing payload client")
class ResultsQueryHearingPayloadClientTest {

    private static final UUID HEARING_ID = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");
    private static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/internal/" + HEARING_ID;
    private static final String SYSTEM_USER_ID = "9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    /** Stands in for the defendant detail a truncated response would have the parser quote back. */
    private static final String DEFENDANT_MARKER = "DEFENDANTMARKERZQX7";
    private static final String PAYLOAD = """
            {"hearing":{"id":"1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa"},
             "sharedTime":"2026-08-21T08:00:00Z"}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WireMockServer server;

    private ResultsQueryHearingPayloadClient client;

    @BeforeAll
    static void startStub() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop();
    }

    @BeforeEach
    void resetStub() {
        server.resetAll();
        client = clientFor(SYSTEM_USER_ID);
    }

    private static ResultsQueryHearingPayloadClient clientFor(final String systemUserId) {
        // The legacy interval is a second. Waiting three of them to observe a retry count would make
        // the suite slow without making it say anything more.
        return clientFor(systemUserId, 3, Duration.ZERO);
    }

    private static ResultsQueryHearingPayloadClient clientFor(final String systemUserId,
            final int maxAttempts, final Duration retryInterval) {
        return new ResultsQueryHearingPayloadClient(
                RestClient.builder().baseUrl(server.baseUrl()).build(),
                systemUserId,
                MAPPER,
                maxAttempts,
                retryInterval);
    }

    private static DistributionCommand command() {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34"),
                HEARING_ID,
                LocalDate.of(2026, 8, 21),
                Instant.parse("2026-08-21T08:00:00Z"),
                "Hearing_Resulted");
    }

    private static void respondWith(final int status, final String body) {
        server.stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", ResultsQueryHearingPayloadClient.ACCEPT)
                        .withBody(body)));
    }

    @Nested
    @DisplayName("the request")
    class Request {

        @Test
        void fetch_should_call_the_internal_hearing_details_path_for_the_hearing() {
            respondWith(200, PAYLOAD);

            client.fetch(command());

            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_send_the_internal_hearing_details_vendor_media_type() {
            respondWith(200, PAYLOAD);

            client.fetch(command());

            server.verify(getRequestedFor(urlEqualTo(PATH)).withHeader("Accept",
                    equalTo("application/vnd.results.hearing-details-internal+json")));
        }

        @Test
        void fetch_should_send_the_system_user_identity_header() {
            respondWith(200, PAYLOAD);

            client.fetch(command());

            server.verify(getRequestedFor(urlEqualTo(PATH))
                    .withHeader("CJSCPPUID", equalTo(SYSTEM_USER_ID)));
        }
    }

    @Nested
    @DisplayName("a successful response")
    class Success {

        @Test
        void fetch_should_return_the_payload_the_query_side_supplied() {
            respondWith(200, PAYLOAD);

            final Optional<JsonNode> fetched = client.fetch(command());

            assertThat(fetched).isPresent();
            assertThat(fetched.get().path("sharedTime").asString())
                    .isEqualTo("2026-08-21T08:00:00Z");
        }

        @Test
        void fetch_should_return_the_whole_envelope_rather_than_the_hearing_alone() {
            respondWith(200, PAYLOAD);

            assertThat(client.fetch(command()))
                    .get()
                    .satisfies(node -> {
                        assertThat(node.has("hearing")).isTrue();
                        assertThat(node.has("sharedTime")).isTrue();
                    });
        }
    }

    @Nested
    @DisplayName("a response with nothing in it")
    class Empty {

        @Test
        void fetch_should_report_nothing_when_the_query_side_answers_with_an_empty_object() {
            respondWith(200, "{}");

            assertThat(client.fetch(command())).isEmpty();
        }

        @Test
        void fetch_should_not_retry_an_empty_answer() {
            respondWith(200, "{}");

            client.fetch(command());

            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_report_nothing_when_the_body_is_absent() {
            respondWith(200, "");

            assertThat(client.fetch(command())).isEmpty();
        }

        @Test
        void fetch_should_report_nothing_when_the_body_is_not_json() {
            respondWith(200, "<html>a gateway wrote this</html>");

            assertThat(client.fetch(command())).isEmpty();
        }

        /**
         * A truncated response is the commonest way a hearing reaches a log index: the parser names
         * the token it stopped on, and in a hearing document that token is a name, an address or a
         * URN. The line therefore carries the failure's type and nothing the query side sent
         * (constitution Principle VII).
         */
        @Test
        void fetch_should_not_write_out_anything_a_malformed_response_contained() {
            respondWith(200, "{\"hearing\":{\"defendant\": " + DEFENDANT_MARKER);

            try (CapturedLog log = CapturedLog.of(ResultsQueryHearingPayloadClient.class)) {
                assertThat(client.fetch(command())).isEmpty();

                assertThat(log.renderings())
                        .as("the parser's words quote the response it failed on")
                        .noneMatch(line -> line.contains(DEFENDANT_MARKER));
                assertThat(log.messages()).anyMatch(line -> line.contains("not JSON"));
            }
        }

        /**
         * The query side's declared schema allows a null document, so the literal is a shape a
         * conforming implementation may send — and it carries no more payload than an empty object.
         */
        @Test
        void fetch_should_report_nothing_when_the_body_is_the_json_null_literal() {
            respondWith(200, "null");

            assertThat(client.fetch(command())).isEmpty();
        }
    }

    @Nested
    @DisplayName("the ported retry rule")
    class Retrying {

        @Test
        void fetch_should_retry_a_server_error_up_to_the_legacy_attempt_count() {
            respondWith(500, "{}");

            assertThat(client.fetch(command())).isEmpty();
            server.verify(3, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_return_the_payload_a_retry_finally_produced() {
            server.stubFor(get(urlEqualTo(PATH)).inScenario("recovering")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo("up"));
            server.stubFor(get(urlEqualTo(PATH)).inScenario("recovering")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", ResultsQueryHearingPayloadClient.ACCEPT)
                            .withBody(PAYLOAD)));

            assertThat(client.fetch(command())).isPresent();
            server.verify(2, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_not_retry_a_client_error() {
            respondWith(404, "{}");

            assertThat(client.fetch(command())).isEmpty();
            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_not_retry_a_forbidden_response() {
            respondWith(403, "{}");

            assertThat(client.fetch(command())).isEmpty();
            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        /**
         * The legacy wrapper's cut-off is {@code status <= 429}, so 429 — the one status that is an
         * explicit invitation to try again — is the one it never retries. Ported as written, and
         * pinned here so the oddity is a decision on the record rather than a loop somebody tidies.
         */
        @Test
        void fetch_should_not_retry_a_rate_limited_response_because_legacy_does_not() {
            respondWith(429, "{}");

            assertThat(client.fetch(command())).isEmpty();
            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_retry_a_response_above_the_legacy_cut_off() {
            respondWith(430, "{}");

            assertThat(client.fetch(command())).isEmpty();
            server.verify(3, getRequestedFor(urlEqualTo(PATH)));
        }

        /**
         * With the interval genuinely elapsing, so the wait between attempts is exercised rather
         * than skipped. Twenty milliseconds rather than the legacy second: what is being proven is
         * that waiting does not lose an attempt, not how long the wait is.
         */
        @Test
        void fetch_should_still_make_every_attempt_when_the_interval_actually_elapses() {
            respondWith(500, "{}");

            assertThat(clientFor(SYSTEM_USER_ID, 3, Duration.ofMillis(20)).fetch(command()))
                    .isEmpty();
            server.verify(3, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_make_a_single_attempt_when_only_one_is_allowed() {
            respondWith(500, "{}");

            assertThat(clientFor(SYSTEM_USER_ID, 1, Duration.ZERO).fetch(command())).isEmpty();
            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_retry_a_connection_that_never_answered() {
            server.stubFor(get(urlEqualTo(PATH)).willReturn(
                    aResponse().withFault(Fault.EMPTY_RESPONSE)));

            assertThat(client.fetch(command())).isEmpty();
            server.verify(3, getRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("without a system user identity")
    class NoIdentity {

        /**
         * The function app guards the fallback on {@code cjscppuid} being present and logs that it is
         * not — the query side would answer 403 anyway, and spending three attempts and two seconds
         * discovering that is worse than saying so at once.
         */
        @Test
        void fetch_should_not_call_the_query_side_at_all_when_no_identity_is_configured() {
            respondWith(200, PAYLOAD);

            assertThat(clientFor("  ").fetch(command())).isEmpty();
            server.verify(0, getRequestedFor(urlEqualTo(PATH)));
        }

        /**
         * Unset and blank have to behave alike. The setting has no default precisely because it is a
         * secret, so "absent" is the shape a misconfigured environment actually produces.
         */
        @Test
        void fetch_should_not_call_the_query_side_when_the_identity_is_absent_entirely() {
            respondWith(200, PAYLOAD);

            assertThat(clientFor(null).fetch(command())).isEmpty();
            server.verify(0, getRequestedFor(urlEqualTo(PATH)));
        }
    }
}
