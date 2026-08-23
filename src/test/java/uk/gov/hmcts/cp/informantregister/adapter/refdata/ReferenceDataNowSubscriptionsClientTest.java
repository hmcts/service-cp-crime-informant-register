package uk.gov.hmcts.cp.informantregister.adapter.refdata;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

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
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The now-subscriptions read, against a stub that answers exactly as reference data does.
 *
 * <p>Three things belong to the reference-data contract and not to this service: the path, the
 * {@code on} query parameter and the vendor media type. All three are asserted against a stub that
 * checks them, because a wrong media type is answered with a 406 by the real service and with a
 * perfectly good body by any stub that does not look
 * ({@code referencedata-query-api.raml:2352-2374}, {@code ReferenceDataService.js:40-47}).
 *
 * <p>The retry rule is the function app's own, ported rather than improved: the same
 * {@code AxiosRetryWrapper.getWrapperWithDefault} the payload fallback goes through
 * ({@code ReferenceDataService.js:48}, {@code AxiosRetryWrapper.js:28-41,74-76}). It abandons the
 * moment a response arrives carrying a status of 429 or below, and retries anything above it — which
 * makes 429 the single least-retried failure there is. That is a defect in the legacy policy and it
 * is pinned here rather than left to be tidied up by the next reader of the loop.
 *
 * <p>What is <em>not</em> ported is the answer on failure. The legacy returns {@code null}
 * ({@code ReferenceDataService.js:50-53}), which the matching step cannot tell from "reference data
 * answered, nobody is subscribed", so an outage ships a register that reaches nobody. Every failure
 * here is reported instead — {@code doc/DEVIATIONS.md} entry 14.
 */
@DisplayName("Reference data now-subscriptions client")
class ReferenceDataNowSubscriptionsClientTest {

    private static final LocalDate ON = LocalDate.of(2020, 6, 2);
    private static final String PATH =
            "/referencedata-query-api/query/api/rest/referencedata/now-subscriptions?on=2020-06-02";
    private static final String SYSTEM_USER_ID = "9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    /**
     * The shape reference data really answers with — the recorded oracle call took
     * {@code {"nowSubscriptions":[…]}} from the same endpoint
     * ({@code parity-pack/recorded/…/meta.json}, {@code observed.refdataCalls}).
     */
    private static final String BODY = """
            {"nowSubscriptions":[{"id":"6e3f5c0a-6d4a-4f2b-9c11-2f8a3d7b4e55",
             "isInformantRegisterSubscription":true}]}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WireMockServer server;

    private ReferenceDataNowSubscriptionsClient client;

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
        client = clientFor(Map.of());
    }

    private static ReferenceDataNowSubscriptionsClient clientFor(
            final Map<String, String> extraHeaders) {
        // The legacy interval is a second. Waiting three of them to observe a retry count would make
        // the suite slow without making it say anything more.
        return new ReferenceDataNowSubscriptionsClient(
                RestClient.builder().baseUrl(server.baseUrl()).build(),
                SYSTEM_USER_ID,
                extraHeaders,
                MAPPER,
                3,
                Duration.ZERO);
    }

    private static void respondWith(final int status, final String body) {
        server.stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", ReferenceDataNowSubscriptionsClient.ACCEPT)
                        .withBody(body)));
    }

    @Nested
    @DisplayName("the request")
    class Request {

        @Test
        void fetch_should_call_the_now_subscriptions_path_dated_with_the_day_it_was_given() {
            // `ReferenceDataService.js:40` — the path, and `on` carrying a plain YYYY-MM-DD day.
            respondWith(200, BODY);

            client.fetch(ON);

            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_ask_with_the_vendor_media_type_and_the_identity_reference_data_wants() {
            // `ReferenceDataService.js:42-47` — CJSCPPUID and the vendor Accept type, both of which
            // the recorded oracle call carries verbatim.
            respondWith(200, BODY);

            client.fetch(ON);

            server.verify(getRequestedFor(urlEqualTo(PATH))
                    .withHeader("Accept",
                            equalTo(ReferenceDataNowSubscriptionsClient.ACCEPT))
                    .withHeader(ReferenceDataNowSubscriptionsClient.IDENTITY_HEADER,
                            equalTo(SYSTEM_USER_ID)));
        }

        @Test
        void fetch_should_send_whatever_further_headers_the_mesh_is_configured_to_need() {
            respondWith(200, BODY);

            clientFor(Map.of("X-Mesh-Route", "referencedata")).fetch(ON);

            server.verify(getRequestedFor(urlEqualTo(PATH))
                    .withHeader("X-Mesh-Route", equalTo("referencedata")));
        }
    }

    @Nested
    @DisplayName("what reference data answered")
    class Answer {

        @Test
        void fetch_should_return_the_body_reference_data_answered_with() {
            // `ReferenceDataService.js:49` returns `response.data` whole; the matching step is what
            // reads `nowSubscriptions` off it (`InformantRegisterSubscriptions/index.js:22,28`).
            respondWith(200, BODY);

            final JsonNode answer = client.fetch(ON);

            assertThat(answer).isNotNull();
            assertThat(answer.get("nowSubscriptions").size()).isEqualTo(1);
        }

        @Test
        void fetch_should_return_an_answer_carrying_no_subscriptions_exactly_as_it_arrived() {
            // A bare array is what the function app's own Jest mocks answer with (blind spot BS-01),
            // and `!subscriptionsMetaData.nowSubscriptions` then returns the fragments untouched.
            // That is a business outcome, not a failure, so it is passed through rather than refused.
            respondWith(200, "[]");

            final JsonNode answer = client.fetch(ON);

            assertThat(answer).isNotNull();
            assertThat(answer.isArray()).isTrue();
        }

        @Test
        void fetch_should_answer_nothing_when_reference_data_sent_no_body_at_all() {
            // `response.data` is then the empty string, which is falsy, so
            // `InformantRegisterSubscriptions/index.js:22` returns the fragments unchanged. An empty
            // answer is not an outage and must not be reported as one.
            respondWith(200, "");

            assertThat(client.fetch(ON)).isNull();
        }
    }

    @Nested
    @DisplayName("a failure of the fetch")
    class Failure {

        @Test
        void fetch_should_report_a_refused_connection_after_spending_its_attempts() {
            // No response at all, so `error.response` is undefined and `AxiosRetryWrapper.js:34`
            // retries until the budget is gone.
            server.stubFor(get(urlEqualTo(PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatThrownBy(() -> client.fetch(ON))
                    .isInstanceOf(ReferenceDataUnavailableException.class);

            server.verify(3, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_report_a_server_error_after_spending_its_attempts() {
            // 500 is above the wrapper's cut-off, so it is the one failure that is retried.
            respondWith(500, "{}");

            assertThatThrownBy(() -> client.fetch(ON))
                    .isInstanceOf(ReferenceDataUnavailableException.class)
                    .satisfies(failure -> assertThat(
                            ((ReferenceDataUnavailableException) failure).reason())
                            .isEqualTo(ReasonCode.REFERENCE_DATA_UNAVAILABLE));

            server.verify(3, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_recover_when_a_retried_attempt_answers() {
            server.stubFor(get(urlEqualTo(PATH)).inScenario("recovery")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(500))
                    .willSetStateTo("answering"));
            server.stubFor(get(urlEqualTo(PATH)).inScenario("recovery")
                    .whenScenarioStateIs("answering")
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", ReferenceDataNowSubscriptionsClient.ACCEPT)
                            .withBody(BODY)));

            final JsonNode answer = client.fetch(ON);

            assertThat(answer.get("nowSubscriptions").size()).isEqualTo(1);
            server.verify(2, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_report_a_not_found_without_retrying_it() {
            // `AxiosRetryWrapper.js:34` rethrows immediately for any status at or below 429.
            respondWith(404, "{}");

            assertThatThrownBy(() -> client.fetch(ON))
                    .isInstanceOf(ReferenceDataUnavailableException.class);

            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_report_a_rate_limit_without_retrying_it() {
            // The legacy's inverted policy: 429 is at the cut-off, so it is never retried while a
            // 500 is. Ported deliberately (constitution Principle I) and pinned here.
            respondWith(429, "{}");

            assertThatThrownBy(() -> client.fetch(ON))
                    .isInstanceOf(ReferenceDataUnavailableException.class);

            server.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetch_should_report_a_body_it_cannot_read_rather_than_address_nobody() {
            // A 200 carrying something that is not the now-subscriptions body — a gateway's error
            // page is the everyday case. Returning it as "no subscriptions" would be exactly the
            // silent loss `doc/DEVIATIONS.md` entry 14 exists to end.
            respondWith(200, "<html>gateway error</html>");

            assertThatThrownBy(() -> client.fetch(ON))
                    .isInstanceOf(ReferenceDataUnavailableException.class);
        }
    }
}
