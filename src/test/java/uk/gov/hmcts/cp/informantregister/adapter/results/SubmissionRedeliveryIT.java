package uk.gov.hmcts.cp.informantregister.adapter.results;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.config.InformantRegisterProperties;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearing;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearingVenue;
import uk.gov.hmcts.cp.informantregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;

/**
 * The idempotency gate, proven end to end across the two halves that actually enforce it.
 *
 * <p>The unit suites hold each half to its own contract: {@code ProcessedOutputRepositoryIT} proves
 * the statements against a real Postgres, and {@code ResultsRegisterSubmissionClientTest} proves
 * the adapter's ordering against a mocked repository and a mocked transport. Neither can fail if
 * the two agree with each other and disagree with the database — a mock returns whatever it was
 * told to, so "an authority already POSTED is skipped" is asserted there against a stub of the very
 * decision under test. Here the repository is real, the store is real, and the POST reaches a
 * socket, so the claim being made is the one the design rules make: <strong>a redelivery must not
 * produce a second submission</strong> (workflow gate 3).
 *
 * <p>The partial case is the one worth the container. {@code add-informant-register} is not
 * idempotent, so a redelivery after two of three authorities went must repeat exactly the one that
 * did not — no fewer, which loses a register, and no more, which duplicates one.
 *
 * <p>One attempt per POST, and a wait that is recorded rather than taken: what is under test is
 * what the log permits a second delivery to do, not how patiently the transport retries, which
 * {@link ResultsCommandGatewayTest} settles on its own.
 */
@DisplayName("submission under redelivery")
class SubmissionRedeliveryIT {

    private static final String PATH =
            "/results-command-api/command/api/rest/results/informant-register";
    private static final String IDENTITY = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final String AUTHORITY_A = "3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8";
    private static final String AUTHORITY_B = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private WireMockServer results;

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @BeforeEach
    void startResults() {
        results = new WireMockServer(wireMockConfig().dynamicPort());
        results.start();
    }

    @AfterEach
    void stopResults() {
        results.stop();
    }

    /**
     * A submission client wired exactly as {@code PipelineConfig} wires it, except that the wait
     * between attempts is recorded rather than slept and there is only one attempt to wait between.
     *
     * <p>A fresh instance per delivery, and a fresh repository behind it, because a redelivery is
     * not the same object calling twice — it is another runner, possibly another pod, and an
     * instance that remembered anything in a field would prove the wrong thing.
     */
    private RegisterSubmissionClient delivery() {
        final ResultsCommandGateway gateway = new ResultsCommandGateway(
                new InformantRegisterProperties.Results(
                        "http://localhost:" + results.port(),
                        IDENTITY,
                        Map.of(),
                        1,
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5)),
                duration -> {
                    // Nothing waits here; a single attempt never reaches a wait at all.
                });
        return new ResultsRegisterSubmissionClient(
                new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient()),
                gateway,
                MAPPER);
    }

    @Test
    void a_redelivery_should_not_post_an_authority_that_already_went() {
        final DistributionCommand command = seededRequest();
        results.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        delivery().submit(submission(command, AUTHORITY_A));
        delivery().submit(submission(command, AUTHORITY_A));

        assertThat(postsCarrying(AUTHORITY_A))
                .as("the register is not idempotent; a second POST is a second register row")
                .isEqualTo(1);
        assertThat(status(command, AUTHORITY_A)).isEqualTo("POSTED");
    }

    @Test
    void a_redelivery_after_a_partial_failure_should_repeat_only_the_authority_that_failed() {
        final DistributionCommand command = seededRequest();
        results.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing(AUTHORITY_A))
                .willReturn(aResponse().withStatus(202)));
        results.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing(AUTHORITY_B))
                .inScenario("B recovers").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("up"));
        results.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing(AUTHORITY_B))
                .inScenario("B recovers").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(202)));

        final RegisterSubmissionClient first = delivery();
        first.submit(submission(command, AUTHORITY_A));
        assertThatThrownBy(() -> first.submit(submission(command, AUTHORITY_B)))
                .isInstanceOf(SubmissionFailedException.class);

        final RegisterSubmissionClient second = delivery();
        second.submit(submission(command, AUTHORITY_A));
        second.submit(submission(command, AUTHORITY_B));

        assertThat(postsCarrying(AUTHORITY_A))
                .as("the authority that went must not go again")
                .isEqualTo(1);
        assertThat(postsCarrying(AUTHORITY_B))
                .as("the authority that did not go must be repeated, or the register is lost")
                .isEqualTo(2);
        assertThat(status(command, AUTHORITY_A)).isEqualTo("POSTED");
        assertThat(status(command, AUTHORITY_B)).isEqualTo("POSTED");
    }

    /**
     * The failed half of the same rule: a refusal leaves FAILED behind, not a row that looks like a
     * submission still in flight, and the next delivery is free to try it again.
     */
    @Test
    void a_refused_authority_should_be_left_failed_and_be_retried_by_the_next_delivery() {
        final DistributionCommand command = seededRequest();
        results.stubFor(post(urlEqualTo(PATH)).inScenario("refuses then accepts")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(400))
                .willSetStateTo("accepting"));

        assertThatThrownBy(() -> delivery().submit(submission(command, AUTHORITY_A)))
                .isInstanceOf(SubmissionFailedException.class);
        assertThat(status(command, AUTHORITY_A)).isEqualTo("FAILED");

        results.stubFor(post(urlEqualTo(PATH)).inScenario("refuses then accepts")
                .whenScenarioStateIs("accepting")
                .willReturn(aResponse().withStatus(202)));

        delivery().submit(submission(command, AUTHORITY_A));

        assertThat(postsCarrying(AUTHORITY_A)).isEqualTo(2);
        assertThat(status(command, AUTHORITY_A)).isEqualTo("POSTED");
    }

    /** Seeds the request row the output rows' foreign key requires. */
    private static DistributionCommand seededRequest() {
        final DistributionCommand command = ProcessedLogTestSupport.command();
        final RunClaim claim = new RunClaim(
                command.source(), command.requestId(), "runner-1", UUID.randomUUID(), "msg-1");
        ProcessedLogTestSupport.repository(LEASE)
                .insertNew(command, RequestFingerprint.of(command), claim);
        return command;
    }

    private int postsCarrying(final String authority) {
        return results.findAll(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(containing(authority))).size();
    }

    private static String status(final DistributionCommand command, final String authority) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                           AND prosecution_authority_id = :authority
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param("authority", authority)
                .query(String.class)
                .single();
    }

    private static AuthoritySubmission submission(
            final DistributionCommand command, final String authority) {
        return new AuthoritySubmission(
                command.source(), command.requestId(), authority, document(command, authority),
                CallerIdentity.of(command));
    }

    private static InformantRegisterDocument document(
            final DistributionCommand command, final String authority) {
        final InformantRegisterDefendant defendant = new InformantRegisterDefendant(
                "SMITH, John", null, "1 High Street", null, null, null, null,
                null, null, null, null, null, null, null);
        final InformantRegisterHearing session =
                new InformantRegisterHearing("Court 1", "10:00:00Z", List.of(defendant));
        final InformantRegisterHearingVenue venue =
                new InformantRegisterHearingVenue(null, "Bristol Magistrates' Court", List.of(session));
        return new InformantRegisterDocument(
                ZonedDateTime.parse("2026-08-20T11:00:00Z"),
                ZonedDateTime.parse("2026-08-19T10:00:00Z"),
                command.hearingId(),
                UUID.fromString(authority),
                "CPS", null, null, null,
                "informant-register-CPS-20260820.pdf", null, venue, null);
    }
}
