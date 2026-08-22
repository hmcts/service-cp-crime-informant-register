package uk.gov.hmcts.cp.informantregister.adapter.results;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.networknt.schema.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.InformantRegisterProperties;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearing;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearingVenue;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.informantregister.support.ResultsCommandSchemas;

/**
 * The outbound leg against a real HTTP server, stubbed.
 *
 * <p>Two things are under test and they are worth separating. The first is the <strong>contract</strong>:
 * one exact path, one exact vendor media type, the identity header, and a body the results-owned
 * schema accepts. None of that is assertable against a mock of an HTTP client — a mock agrees with
 * whatever the code does — so the suite drives a socket.
 *
 * <p>The second is the <strong>retry classification</strong>, which is the one behaviour change this
 * delivery is allowed to make over the function app: it swallowed every one of these outcomes. A 5xx,
 * a 429 and a dropped connection are retried; any other 4xx is a refusal and is never retried,
 * because the same bytes will be refused again and the delivery budget is finite.
 *
 * <p>An outcome that stays unknown — a connection dropped after the request went out — is retried
 * and then handed back transient. That is deliberate and it is not at-most-once: a duplicate
 * register row is absorbed downstream, and a lost one is silent. Nothing here promises more.
 *
 * <p>Waiting is injected rather than performed, so a suite that proves a two-second {@code
 * Retry-After} was honoured takes no two seconds to do it.
 */
@DisplayName("Results command gateway")
class ResultsCommandGatewayTest {

    private static final String PATH =
            "/results-command-api/command/api/rest/results/informant-register";
    private static final String MEDIA_TYPE = "application/vnd.results.add-informant-register+json";
    private static final String IDENTITY = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";

    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);
    private static final int MAX_ATTEMPTS = 4;

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();
    private static final Schema SCHEMA = ResultsCommandSchemas.addInformantRegisterSchema();

    private WireMockServer results;
    private RecordingPause pause;

    @BeforeEach
    void startResults() {
        results = new WireMockServer(wireMockConfig().dynamicPort());
        results.start();
        pause = new RecordingPause();
    }

    @AfterEach
    void stopResults() {
        results.stop();
    }

    private ResultsCommandGateway gateway() {
        return new ResultsCommandGateway(
                new InformantRegisterProperties.Results(
                        "http://localhost:" + results.port(),
                        IDENTITY,
                        Map.of("X-Mesh-Route", "results"),
                        MAX_ATTEMPTS,
                        INITIAL_BACKOFF,
                        MAX_BACKOFF,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5)),
                pause);
    }

    @Nested
    @DisplayName("the contract on the wire")
    class Contract {

        @Test
        void an_accepted_post_should_carry_the_contract_path_media_type_and_identity() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            results.verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader("Content-Type", equalTo(MEDIA_TYPE))
                    .withHeader("CJSCPPUID", equalTo(IDENTITY))
                    .withHeader("X-Mesh-Route", equalTo("results")));
        }

        @Test
        void the_posted_body_should_satisfy_the_results_owned_schema() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            final JsonNode sent = MAPPER.readTree(
                    results.getAllServeEvents().getFirst().getRequest().getBodyAsString());
            assertThat(SCHEMA.validate(sent).stream().map(Object::toString).toList()).isEmpty();
        }

        @Test
        void an_accepted_post_should_be_attempted_exactly_once() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(results.getAllServeEvents()).hasSize(1);
            assertThat(pause.waits).isEmpty();
        }
    }

    @Nested
    @DisplayName("outcomes worth another attempt")
    class Retried {

        @Test
        void a_server_error_should_be_retried_and_the_next_attempt_can_succeed() {
            results.stubFor(post(urlEqualTo(PATH)).inScenario("recovers")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(500))
                    .willSetStateTo("up"));
            results.stubFor(post(urlEqualTo(PATH)).inScenario("recovers")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(results.getAllServeEvents()).hasSize(2);
            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        @Test
        void a_dropped_connection_should_be_retried_because_the_outcome_is_unknown() {
            results.stubFor(post(urlEqualTo(PATH)).inScenario("drops")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                    .willSetStateTo("up"));
            results.stubFor(post(urlEqualTo(PATH)).inScenario("drops")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        @Test
        void a_rate_limit_should_wait_exactly_as_long_as_the_server_asked() {
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2"))
                    .willSetStateTo("up"));
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(pause.waits).containsExactly(Duration.ofSeconds(2));
        }

        @Test
        void a_rate_limit_without_a_usable_retry_after_should_fall_back_to_the_backoff() {
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "when I say so"))
                    .willSetStateTo("up"));
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        /**
         * RFC 9110 allows an HTTP-date and this client deliberately does not act on one: honouring
         * it would mean measuring a remote clock against this pod's, and a server a few minutes
         * ahead would park a run past the claim it holds. The fallback is the back-off, which is
         * what no header at all would give and is bounded by the same ceiling.
         */
        @Test
        void a_retry_after_as_an_http_date_should_fall_back_to_the_backoff_rather_than_a_remote_clock() {
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429)
                            .withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"))
                    .willSetStateTo("up"));
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        @Test
        void a_retry_after_too_large_to_be_a_number_should_fall_back_rather_than_overflow() {
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429)
                            .withHeader("Retry-After", "999999999999999999999"))
                    .willSetStateTo("up"));
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(pause.waits).containsExactly(INITIAL_BACKOFF);
        }

        @Test
        void a_retry_after_beyond_the_ceiling_should_be_capped_so_a_run_outlives_its_claim() {
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3600"))
                    .willSetStateTo("up"));
            results.stubFor(post(urlEqualTo(PATH)).inScenario("throttled")
                    .whenScenarioStateIs("up")
                    .willReturn(aResponse().withStatus(202)));

            gateway().post(body());

            assertThat(pause.waits).containsExactly(MAX_BACKOFF);
        }

        @Test
        void the_wait_between_attempts_should_grow_rather_than_hammer_a_struggling_server() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class);

            assertThat(pause.waits).containsExactly(
                    INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2), INITIAL_BACKOFF.multipliedBy(4));
        }

        @Test
        void repeated_server_errors_should_run_out_of_attempts_and_hand_the_delivery_back() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.TRANSIENT);

            assertThat(results.getAllServeEvents()).hasSize(MAX_ATTEMPTS);
        }

        @Test
        void an_outcome_that_stays_unknown_should_end_transient_rather_than_be_written_off() {
            results.stubFor(post(urlEqualTo(PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.TRANSIENT);
        }
    }

    @Nested
    @DisplayName("outcomes no redelivery can change")
    class Rejected {

        @Test
        void a_refused_body_should_never_be_retried() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.NON_TRANSIENT);

            assertThat(results.getAllServeEvents()).hasSize(1);
            assertThat(pause.waits).isEmpty();
        }

        @Test
        void an_unauthorised_caller_should_be_a_refusal_rather_than_a_retry_loop() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).reason())
                    .isEqualTo(ReasonCode.SUBMISSION_REJECTED);

            assertThat(results.getAllServeEvents()).hasSize(1);
        }

        /**
         * The contract declares one success, {@code 202 Accepted}. A 200 means something other than
         * the command endpoint answered — a proxy, or a route that no longer reaches it — and
         * calling it success would mark the authority POSTED for a command nothing enqueued: a
         * register lost with the log saying it was sent, which is the failure mode this service
         * exists to remove.
         */
        @Test
        void a_success_the_contract_does_not_define_should_not_be_taken_for_an_accepted_command() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).reason())
                    .isEqualTo(ReasonCode.SUBMISSION_NOT_ACCEPTED);
        }

        @Test
        void a_success_the_contract_does_not_define_should_never_be_posted_a_second_time() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.NON_TRANSIENT);

            assertThat(results.getAllServeEvents())
                    .as("the body may already have been applied; a retry could duplicate the register")
                    .hasSize(1);
            assertThat(pause.waits).isEmpty();
        }

        @Test
        void a_refusal_should_carry_a_bounded_code_and_none_of_the_servers_own_words() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(422)
                    .withBody("{\"error\":\"defendant SMITH, John is not known here\"}")));

            assertThatThrownBy(() -> gateway().post(body()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .hasMessage(ReasonCode.SUBMISSION_REJECTED.code())
                    .hasMessageNotContaining("SMITH");
        }
    }

    @Nested
    @DisplayName("configuration and shutdown")
    class Edges {

        @Test
        void an_unconfigured_endpoint_should_fail_at_startup_rather_than_post_somewhere_else() {
            final InformantRegisterProperties.Results noEndpoint =
                    new InformantRegisterProperties.Results(
                            " ", IDENTITY, Map.of(), MAX_ATTEMPTS, INITIAL_BACKOFF, MAX_BACKOFF,
                            Duration.ofSeconds(2), Duration.ofSeconds(5));

            assertThatThrownBy(() -> new ResultsCommandGateway(noEndpoint, pause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("base-url");
        }

        /**
         * {@code CJSCPPUID} is part of the contract, not an optional courtesy: without it the
         * command is anonymous and Results refuses it. A service that starts anyway would
         * dead-letter every hearing it was given, one 403 at a time, so the fault belongs at
         * startup where a deployment fails on it.
         */
        @Test
        void an_absent_identity_should_fail_at_startup_rather_than_post_anonymously() {
            final InformantRegisterProperties.Results noIdentity =
                    new InformantRegisterProperties.Results(
                            "http://localhost:" + results.port(), null, Map.of(), MAX_ATTEMPTS,
                            INITIAL_BACKOFF, MAX_BACKOFF, Duration.ofSeconds(2), Duration.ofSeconds(5));

            assertThatThrownBy(() -> new ResultsCommandGateway(noIdentity, pause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("system-user-id");
        }

        @Test
        void a_blank_identity_should_be_refused_the_same_way_as_an_absent_one() {
            final InformantRegisterProperties.Results blankIdentity =
                    new InformantRegisterProperties.Results(
                            "http://localhost:" + results.port(), "  ", Map.of(), MAX_ATTEMPTS,
                            INITIAL_BACKOFF, MAX_BACKOFF, Duration.ofSeconds(2), Duration.ofSeconds(5));

            assertThatThrownBy(() -> new ResultsCommandGateway(blankIdentity, pause))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("system-user-id");
        }

        @Test
        void an_interrupted_wait_should_give_up_transient_with_the_interrupt_restored() {
            results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
            final ResultsCommandGateway interruptible = new ResultsCommandGateway(
                    new InformantRegisterProperties.Results(
                            "http://localhost:" + results.port(), IDENTITY, Map.of(), MAX_ATTEMPTS,
                            INITIAL_BACKOFF, MAX_BACKOFF, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                    duration -> {
                        throw new InterruptedException("shutting down");
                    });

            try {
                assertThatThrownBy(() -> interruptible.post(body()))
                        .isInstanceOf(SubmissionFailedException.class)
                        .extracting(failure -> ((SubmissionFailedException) failure).classification())
                        .isEqualTo(FailureClassification.TRANSIENT);

                assertThat(Thread.currentThread().isInterrupted())
                        .as("an interrupt is a shutdown, and swallowing it would hide one")
                        .isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    /** A minimal document, which is all the transport leg needs: valid bytes with a valid shape. */
    private static byte[] body() {
        final InformantRegisterDefendant defendant = new InformantRegisterDefendant(
                "SMITH, John", null, "1 High Street", null, null, null, null,
                null, null, null, null, null, null, null);
        final InformantRegisterHearing session =
                new InformantRegisterHearing("Court 1", "10:00:00Z", List.of(defendant));
        final InformantRegisterHearingVenue venue =
                new InformantRegisterHearingVenue(null, "Bristol Magistrates' Court", List.of(session));
        final InformantRegisterDocument document = new InformantRegisterDocument(
                ZonedDateTime.parse("2026-08-20T11:00:00Z"),
                ZonedDateTime.parse("2026-08-19T10:00:00Z"),
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8"),
                "CPS", null, null, null,
                "informant-register-CPS-20260820.pdf", null, venue, null);
        return MAPPER.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
    }

    /** Records what the gateway would have waited, so the suite proves the policy without living it. */
    private static final class RecordingPause implements SubmissionPause {

        private final List<Duration> waits = new ArrayList<>();

        @Override
        public void pause(final Duration duration) {
            waits.add(duration);
        }
    }
}
