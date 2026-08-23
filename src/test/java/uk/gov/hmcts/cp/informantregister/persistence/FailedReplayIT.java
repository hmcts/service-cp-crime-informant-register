package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.application.RegisterTransformer;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A parked request is replayable, and only under a fresh message identity (spec FR-007, SC-003).
 *
 * <p>Which identity a delivery carries is the whole of the decision. A different one is a deliberate
 * resubmission by support: the record goes back to RECEIVED, the attempt count is carried forward
 * rather than reset, the failure reason and the exhausting identity are cleared, and an audit note
 * keeps the reason it was parked. The same one is dead-lettering that did not settle, and it must not
 * run anything.
 *
 * <p>The full SC-003 arithmetic is driven end to end — five failed deliveries, then one replay that
 * succeeds — because `attempts` = 6 is the number a support engineer reads, and it only comes out
 * right if every acquisition increments exactly once and the replay preserves what was there.
 */
class FailedReplayIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int PERMITTED_DELIVERIES = 5;

    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE, metrics);
    private final DistributionCommand command = ProcessedLogTestSupport.command();

    private static DeliveryIdentity delivery(final int number) {
        return new DeliveryIdentity("msg-" + number, "runner-1/delivery-" + number);
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    /** Fails the request through all five permitted deliveries, parking it on the fifth. */
    private void exhaustEveryPermittedDelivery() {
        exhaustEveryPermittedDelivery(command);
    }

    /** The same, for a suite that parks a request of its own making. */
    private void exhaustEveryPermittedDelivery(final DistributionCommand parked) {
        for (int number = 1; number < PERMITTED_DELIVERIES; number++) {
            guard.recordTransientFailure(
                    runClaimOf(guard.admit(parked, delivery(number))),
                    ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        }
        guard.recordExhaustion(
                runClaimOf(guard.admit(parked, delivery(PERMITTED_DELIVERIES))),
                ReasonCode.PIPELINE_TRANSIENT_FAILURE);
    }

    @Test
    @DisplayName("five failed deliveries park the request with the identity that exhausted them")
    void the_fifth_failure_should_park_the_request() {
        exhaustEveryPermittedDelivery();

        final Row row = row();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES);
        assertThat(row.failureReason()).isEqualTo("PIPELINE_TRANSIENT_FAILURE");
        assertThat(row.exhaustedMessageId()).isEqualTo("msg-5");
    }

    @Test
    @DisplayName("a fresh identity replays the request and the run proceeds")
    void a_replay_should_readmit_the_request_carrying_its_attempts_forward() {
        exhaustEveryPermittedDelivery();

        final RunClaim replay = runClaimOf(guard.admit(command, delivery(6)));

        final Row row = row();
        assertThat(row.status()).isEqualTo("RECEIVED");
        assertThat(row.attempts()).isEqualTo(PERMITTED_DELIVERIES + 1);
        assertThat(row.failureReason()).isNull();
        assertThat(row.exhaustedMessageId()).isNull();
        assertThat(row.auditNote()).contains("PIPELINE_TRANSIENT_FAILURE");
        assertThat(row.claimOwner()).isEqualTo(replay.owner());
        assertThat(row.claimToken()).isEqualTo(replay.token());
        assertThat(row.claimExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("the replayed run completes, leaving six lifetime attempts (SC-003)")
    void a_successful_replay_should_leave_the_record_completed_with_six_attempts() {
        exhaustEveryPermittedDelivery();

        guard.recordCompletion(
                runClaimOf(guard.admit(command, delivery(6))), CompletionReason.NO_AUTHORITIES);

        final Row row = row();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completionReason()).isEqualTo("no-authorities");
        assertThat(row.attempts()).isEqualTo(6);
        assertThat(row.claimOwner()).isNull();
    }

    @Test
    @DisplayName("the identity that exhausted the retries is dead-lettered again, and runs nothing")
    void a_redelivery_of_the_exhausting_identity_should_change_nothing() {
        exhaustEveryPermittedDelivery();
        final Row before = row();

        final GuardDecision decision = guard.admit(command, delivery(PERMITTED_DELIVERIES));

        assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                DeadLetterReason.EXHAUSTED, ReasonCode.DELIVERY_LIMIT_EXHAUSTED));
        assertThat(row()).isEqualTo(before);
    }

    /**
     * Zero rows on the replay update never means "the same identity" — that case is decided on the
     * read. It means the record moved between the read and the update: a concurrent replay won, or
     * the record is no longer parked. Per the no-spin rule the delivery is handed back rather than
     * re-read in a loop, and the broker's redelivery re-enters the state machine against whatever
     * the record says by then.
     *
     * <p>Driven with a repository whose read is deliberately stale, because that is precisely the
     * state the guard would have been in when the race was lost.
     */
    @Test
    @DisplayName("a replay whose record moved under it is handed back, not retried in a loop")
    void a_replay_update_matching_no_row_should_abandon_the_delivery() {
        guard.recordCompletion(
                runClaimOf(guard.admit(command, delivery(1))), CompletionReason.NO_AUTHORITIES);
        final Row before = row();

        final IdempotencyGuard staleReadingGuard = new IdempotencyGuard(
                new ProcessedRequestRepository(ProcessedLogTestSupport.jdbcClient(), LEASE) {
                    @Override
                    public Optional<ProcessedRequestRecord> read(
                            final String source, final UUID requestId) {
                        return Optional.of(new ProcessedRequestRecord(
                                RequestStatus.FAILED,
                                RequestFingerprint.of(command),
                                ReasonCode.PIPELINE_TRANSIENT_FAILURE.code(),
                                "msg-5",
                                5,
                                null,
                                null));
                    }
                },
                metrics);

        final GuardDecision decision = staleReadingGuard.admit(command, delivery(6));

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
        assertThat(row()).isEqualTo(before);
    }

    /**
     * Who a replayed run is made as (deviations-register entry 16).
     *
     * <p>The replay procedure re-sends the dead-lettered body <strong>verbatim</strong>, changing
     * only the broker {@code messageId} (`doc/API_CONTRACTS.md`, "Replay rule"). Attribution is
     * purely message-driven — the run's {@link CallerIdentity} is read from the command and from
     * nothing else — so a replay of an attributed message is run as the original user without the
     * processed log storing a user identifier anywhere. That is the whole of the claim, and it is
     * worth pinning because it is a property of two mechanisms agreeing: the guard readmits the
     * request because {@code userId} is outside the request fingerprint, and the pipeline resolves
     * the caller from the body it was handed.
     *
     * <p>The twin case is the operator escape hatch. Where the original user has been deactivated
     * and their identity would be refused downstream, support re-sends the body with the
     * {@code userId} field <em>removed</em>; that message names nobody and the run is made under
     * the configured system identity. It must still be the same unit of work — a fingerprint that
     * included the user would call the recovery message an idempotency collision and park it.
     *
     * <p>Driven through the real guard, against the real store, because the replay half of the
     * claim is a state transition and a mocked guard would assert only that this test knows what it
     * expects. The identity is asserted at the ports the run calls out through; that each port then
     * puts it in a {@code CJSCPPUID} header and nowhere else is pinned on the wire by
     * {@code ResultsCommandGatewayTest}, {@code ReferenceDataNowSubscriptionsClientTest} and
     * {@code ResultsQueryHearingPayloadClientTest}.
     */
    @Nested
    @DisplayName("the identity a replayed message is run under")
    class ReplayAttribution {

        /** The user who shared the results, named by the message the producer published. */
        private static final UUID SHARING_USER =
                UUID.fromString("0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48");

        private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

        private final UUID requestId = UUID.randomUUID();
        private final UUID hearingId = UUID.randomUUID();

        private final DistributionCommandParser parser =
                new DistributionCommandParser(JacksonConfig.contractObjectMapper());

        private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
        private final RegisterTransformer transformer = mock(RegisterTransformer.class);
        private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);

        private final DistributionPipeline pipeline = new DistributionPipeline(
                guard, payloadSource, transformer, submissionClient, metrics, Clock.systemUTC(),
                RUN_DEADLINE);

        /** The message as the producer published it, naming the user who shared the results. */
        private String publishedBody() {
            return """
                    {
                      "source": "RESULTS",
                      "requestId": "%s",
                      "hearingId": "%s",
                      "hearingDay": "2026-08-20",
                      "sharedTime": "2026-08-20T09:00:00Z",
                      "eventType": "Hearing_Resulted",
                      "userId": "%s"
                    }
                    """.formatted(requestId, hearingId, SHARING_USER);
        }

        /** The same request with the user stripped out: the escape hatch, and nothing else. */
        private String bodyWithTheUserStripped() {
            return """
                    {
                      "source": "RESULTS",
                      "requestId": "%s",
                      "hearingId": "%s",
                      "hearingDay": "2026-08-20",
                      "sharedTime": "2026-08-20T09:00:00Z",
                      "eventType": "Hearing_Resulted"
                    }
                    """.formatted(requestId, hearingId);
        }

        private void theReplayedRunProduces(final List<InformantRegisterDocument> documents) {
            when(payloadSource.fetch(any())).thenReturn(payload());
            when(transformer.transform(any(), any(), any())).thenReturn(documents);
        }

        private static JsonNode payload() {
            return JacksonConfig.contractObjectMapper().readTree("{\"hearing\":{\"id\":\"stub\"}}");
        }

        /** A document per authority: the shape the submission port takes, not its content. */
        private static InformantRegisterDocument document(final String authorityCode) {
            return new InformantRegisterDocument(
                    ZonedDateTime.parse("2026-08-20T11:00:00Z"),
                    ZonedDateTime.parse("2026-08-20T10:00:00Z"),
                    UUID.fromString("11111111-2222-4333-8444-555555555555"),
                    UUID.randomUUID(),
                    authorityCode, null, null, null,
                    "informant-register-" + authorityCode + "-20260820.pdf", null, null, null);
        }

        private List<CallerIdentity> theIdentitiesEverythingWasSubmittedUnder(final int expected) {
            final ArgumentCaptor<AuthoritySubmission> submitted =
                    ArgumentCaptor.forClass(AuthoritySubmission.class);
            verify(submissionClient, times(expected)).submit(submitted.capture());
            return submitted.getAllValues().stream().map(AuthoritySubmission::identity).toList();
        }

        private Row replayedRow() {
            return ProcessedLogTestSupport.requireRow(ProcessedLogTestSupport.SOURCE, requestId);
        }

        @Test
        @DisplayName("a body re-sent verbatim replays, and runs as the user it still names")
        void a_verbatim_replay_should_run_as_the_user_the_original_message_named() {
            final String published = publishedBody();
            exhaustEveryPermittedDelivery(parser.parse(published));
            theReplayedRunProduces(List.of(document("CPS"), document("TVL")));

            // The documented replay: the same body, byte for byte, under a fresh identity.
            final GuardDecision decision = pipeline.process(parser.parse(published), delivery(6));

            assertThat(decision).isEqualTo(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            final Row row = replayedRow();
            assertThat(row.status()).isEqualTo("COMPLETED");
            assertThat(row.attempts())
                    .as("the replay is a sixth run of the same request, not a fresh one")
                    .isEqualTo(PERMITTED_DELIVERIES + 1);
            assertThat(row.auditNote()).contains("PIPELINE_TRANSIENT_FAILURE");

            final CallerIdentity theSharingUser = new CallerIdentity(Optional.of(SHARING_USER));
            // The payload port is handed the command itself, so it reads the user from the same
            // field the other two are given; asserting the command is asserting the identity.
            verify(payloadSource).fetch(argThat(replayed ->
                    replayed.userId().equals(Optional.of(SHARING_USER))));
            verify(transformer).transform(any(), any(), eq(theSharingUser));
            assertThat(theIdentitiesEverythingWasSubmittedUnder(2))
                    .containsExactly(theSharingUser, theSharingUser);
        }

        @Test
        @DisplayName("a replay built without the user is the same request, run as the system")
        void a_replay_that_names_no_user_should_run_as_the_system() {
            exhaustEveryPermittedDelivery(parser.parse(publishedBody()));
            theReplayedRunProduces(List.of(document("CPS")));

            final GuardDecision decision =
                    pipeline.process(parser.parse(bodyWithTheUserStripped()), delivery(6));

            assertThat(decision)
                    .as("dropping the user is not a different request: it is outside the fingerprint")
                    .isEqualTo(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            assertThat(replayedRow().status()).isEqualTo("COMPLETED");

            verify(payloadSource).fetch(argThat(replayed -> replayed.userId().isEmpty()));
            verify(transformer).transform(any(), any(), eq(CallerIdentity.SYSTEM));
            assertThat(theIdentitiesEverythingWasSubmittedUnder(1))
                    .containsExactly(CallerIdentity.SYSTEM);
        }
    }
}
