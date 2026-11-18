package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
        return new DeliveryIdentity(
                "msg-" + number, "runner-1/delivery-" + number, false, number, 0L, null);
    }

    /**
     * A re-send accepted and enqueued as a new message: same `messageId` as an earlier delivery
     * (support's verbatim re-send), but a fresh sequence number — the broker's own proof that this
     * is a different send, not the old message come round again (spec FR-004).
     */
    private static DeliveryIdentity resendOf(final int originalNumber, final long newSequenceNumber) {
        return new DeliveryIdentity(
                "msg-" + originalNumber, "runner-1/delivery-resend", false, newSequenceNumber, 0L,
                null);
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
                DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
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
                                null,
                                500L,
                                "EXHAUSTED",
                                Instant.parse("2026-08-20T09:00:00Z")));
                    }
                },
                metrics);

        final GuardDecision decision = staleReadingGuard.admit(command, delivery(6));

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
        assertThat(row()).isEqualTo(before);
    }

    /**
     * A guard whose read always answers with the same fixed snapshot — for simulating a delivery
     * that read the processed log at some instant and is only now acting on it, exactly as two
     * deliveries racing to replay the same parked request would.
     */
    private IdempotencyGuard guardReadingAsFixed(final ProcessedRequestRecord fixed) {
        return new IdempotencyGuard(
                new ProcessedRequestRepository(ProcessedLogTestSupport.jdbcClient(), LEASE) {
                    @Override
                    public Optional<ProcessedRequestRecord> read(
                            final String source, final UUID requestId) {
                        return Optional.of(fixed);
                    }
                },
                metrics);
    }

    /**
     * Two accepted re-sends of the same parked message is not a hypothetical — a double click on
     * "Re-send", or two operators recovering the same ticket — and exactly one of them may replay
     * (spec FR-006, edge case "Two re-sends of the same parked message"; FR-015 "two concurrent
     * re-sends admit one replay").
     *
     * <p>Simulated by having both readers observe the <em>same</em> stale snapshot of the parked
     * generation, then racing their writes against the real row: the first's compare-and-set still
     * matches it, the second's does not, because the first has already moved the generation on.
     */
    @Test
    @DisplayName("two concurrent re-sends admit exactly one replay")
    void two_concurrent_resends_should_admit_exactly_one_replay() {
        exhaustEveryPermittedDelivery();
        // Must match what exhaustEveryPermittedDelivery() actually wrote: delivery(PERMITTED_DELIVERIES)
        // carries sequence number PERMITTED_DELIVERIES, so that is the real parking sequence number.
        final ProcessedRequestRecord observed = new ProcessedRequestRecord(
                RequestStatus.FAILED, RequestFingerprint.of(command),
                ReasonCode.PIPELINE_TRANSIENT_FAILURE.code(), "msg-5", PERMITTED_DELIVERIES,
                null, null, (long) PERMITTED_DELIVERIES, "EXHAUSTED",
                Instant.parse("2026-08-20T09:00:00Z"));

        final GuardDecision first = guardReadingAsFixed(observed)
                .admit(command, resendOf(5, 601L));
        final GuardDecision second = guardReadingAsFixed(observed)
                .admit(command, resendOf(5, 602L));

        assertThat(first).isInstanceOf(GuardDecision.Run.class);
        assertThat(second).isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
        assertThat(row().status()).isEqualTo("RECEIVED");
        assertThat(row().attempts())
                .as("exactly one replay was admitted, so the generation advanced exactly once")
                .isEqualTo(PERMITTED_DELIVERIES + 1);
    }

    /**
     * A delivery that read the parked generation before it was replayed, and only acts after that
     * replay has run and failed again, must not replay the <em>newer</em> failure using what it read
     * about the old one (spec FR-006; the edge case "A stale replay decision"; FR-015
     * "the FAILED→RECEIVED→FAILED race cannot replay a newer failure").
     */
    @Test
    @DisplayName("a stale read of an earlier generation cannot replay a newer failure")
    void a_stale_read_of_an_earlier_generation_should_not_replay_a_newer_failure() {
        exhaustEveryPermittedDelivery();
        // As above: matches what exhaustEveryPermittedDelivery() actually wrote, so this read is
        // genuinely of the first (now superseded) generation, not a mismatch from the very start.
        final ProcessedRequestRecord staleGeneration = new ProcessedRequestRecord(
                RequestStatus.FAILED, RequestFingerprint.of(command),
                ReasonCode.PIPELINE_TRANSIENT_FAILURE.code(), "msg-5", PERMITTED_DELIVERIES,
                null, null, (long) PERMITTED_DELIVERIES, "EXHAUSTED",
                Instant.parse("2026-08-20T09:00:00Z"));

        // The generation moves on without the stale reader's knowledge: replayed, then parked again.
        final RunClaim replayed = runClaimOf(guard.admit(command, resendOf(5, 700L)));
        guard.recordExhaustion(replayed, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        final Row newerFailure = row();
        assertThat(newerFailure.status()).isEqualTo("FAILED");
        assertThat(newerFailure.parkingSequenceNumber()).isEqualTo(700L);

        final GuardDecision decision =
                guardReadingAsFixed(staleGeneration).admit(command, resendOf(5, 999L));

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
        assertThat(row())
                .as("the newer failure the stale reader never saw must be exactly as it was")
                .isEqualTo(newerFailure);
    }

    /**
     * Who a replayed run is made as (deviations-register entry 16).
     *
     * <p>The replay procedure requires the dead-lettered body to be re-sent <strong>verbatim</strong>,
     * changing only the broker {@code messageId} (`doc/API_CONTRACTS.md`, "Replay rule") — a
     * requirement on whoever replays, not something this service checks. What is pinned here is the
     * consumer's answer to each body it might be handed. Attribution is purely message-driven — the
     * run's {@link CallerIdentity} is read from the command and from nothing else — so a replay that
     * does carry the original body is run as the original user, without the processed log storing a
     * user identifier anywhere. That is the whole of the claim, and it is
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

        /**
         * User Story 1's defect, proven through the full pipeline rather than the guard alone: the
         * Azure portal's dead-letter "Re-send" copies the body <strong>verbatim, {@code messageId}
         * included</strong>. Before this feature that identity was read as a redelivery of the
         * message that exhausted the retries and re-parked without running; the only thing that
         * changed between the parking delivery and this one is the broker's sequence number.
         */
        @Test
        @DisplayName("a re-send under the SAME messageId still replays, once the broker assigns it "
                + "a new sequence number")
        void a_resend_under_the_same_message_id_should_still_replay() {
            final String published = publishedBody();
            exhaustEveryPermittedDelivery(parser.parse(published));
            theReplayedRunProduces(List.of(document("CPS"), document("TVL")));

            // Re-sent exactly as the dead-lettered body stood — same messageId as the delivery that
            // exhausted the retries ("msg-5") — but the broker accepted and enqueued it as a new
            // message, so it carries a fresh sequence number.
            final GuardDecision decision =
                    pipeline.process(parser.parse(published), resendOf(PERMITTED_DELIVERIES, 900L));

            assertThat(decision).isEqualTo(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
            final Row row = replayedRow();
            assertThat(row.status()).isEqualTo("COMPLETED");
            assertThat(row.attempts())
                    .as("the replay is a sixth run of the same request, not a fresh one")
                    .isEqualTo(PERMITTED_DELIVERIES + 1);
            assertThat(theIdentitiesEverythingWasSubmittedUnder(2))
                    .containsExactly(
                            new CallerIdentity(Optional.of(SHARING_USER)),
                            new CallerIdentity(Optional.of(SHARING_USER)));
        }
    }

    /**
     * User Story 3: a {@code FAILED} row with no recorded parking sequence number — parked before
     * this feature, or by an older consumer mid-rollout — has no exact identity test available, so
     * the guard falls back to the documented two-condition heuristic (spec FR-005): a delivery
     * whose broker delivery count is 0 <strong>and</strong> whose broker enqueue time is later than
     * the parking time by more than the configured margin is treated as a resubmission; any
     * delivery failing either condition is re-parked.
     *
     * <p>Every case here parks a request normally (so {@code updated_at} is a real, database-written
     * parking time), then downgrades it to the legacy shape by nulling the two new columns directly
     * — exactly the state a row parked before this migration is in.
     */
    @Nested
    @DisplayName("a legacy FAILED record with no recorded parking sequence number")
    class LegacyRecords {

        /** Short and explicit, so boundary cases are expressed in seconds, not real-time waits. */
        private static final Duration MARGIN = Duration.ofMinutes(1);

        private final IdempotencyGuard legacyGuard =
                ProcessedLogTestSupport.guard(LEASE, metrics, MARGIN);

        /** Parks a request normally, then strips it down to the legacy shape, and returns its row. */
        private Row parkedAsLegacy() {
            exhaustEveryPermittedDelivery();
            final int cleared = ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            UPDATE processed_request
                               SET parking_sequence_number = NULL, parking_category = NULL
                             WHERE source = :source AND request_id = :requestId
                            """)
                    .param("source", command.source())
                    .param("requestId", command.requestId())
                    .update();
            assertThat(cleared).as("exactly the row just parked").isEqualTo(1);
            final Row legacy = row();
            assertThat(legacy.parkingSequenceNumber()).isNull();
            assertThat(legacy.parkingCategory()).isNull();
            return legacy;
        }

        private DeliveryIdentity legacyDelivery(final long deliveryCount, final Instant enqueueTime) {
            return new DeliveryIdentity(
                    "msg-resubmitted", "runner-1/delivery-legacy", false, 1234L, deliveryCount,
                    enqueueTime);
        }

        @Test
        @DisplayName("count 0, enqueued after the parking time plus the margin, replays")
        void a_first_delivery_enqueued_well_after_parking_should_replay() {
            final Row legacy = parkedAsLegacy();
            final Instant wellAfter = legacy.updatedAt().plus(MARGIN).plusSeconds(5);

            final GuardDecision decision =
                    legacyGuard.admit(command, legacyDelivery(0L, wellAfter));

            assertThat(decision).isInstanceOf(GuardDecision.Run.class);
            assertThat(row().status()).isEqualTo("RECEIVED");
        }

        @Test
        @DisplayName("a delivery count of 1 or more re-parks, however late it was enqueued")
        void a_delivery_count_of_one_or_more_should_repark() {
            final Row legacy = parkedAsLegacy();
            final Instant wellAfter = legacy.updatedAt().plus(MARGIN).plusSeconds(5);

            final GuardDecision decision =
                    legacyGuard.admit(command, legacyDelivery(1L, wellAfter));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(legacy);
        }

        @Test
        @DisplayName("an enqueue time not later than the parking time plus the margin re-parks")
        void an_enqueue_time_not_later_than_the_margin_should_repark() {
            final Row legacy = parkedAsLegacy();
            final Instant notLateEnough = legacy.updatedAt().plus(MARGIN).minusSeconds(1);

            final GuardDecision decision =
                    legacyGuard.admit(command, legacyDelivery(0L, notLateEnough));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(legacy);
        }

        @Test
        @DisplayName("an enqueue time exactly equal to the parking time plus the margin re-parks")
        void an_enqueue_time_exactly_at_the_margin_should_repark() {
            final Row legacy = parkedAsLegacy();
            final Instant exactlyAtMargin = legacy.updatedAt().plus(MARGIN);

            final GuardDecision decision =
                    legacyGuard.admit(command, legacyDelivery(0L, exactlyAtMargin));

            assertThat(decision)
                    .as("strictly later than the margin is required; equal is not later")
                    .isEqualTo(new GuardDecision.DeadLetter(
                            DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(legacy);
        }

        @Test
        @DisplayName("an absent enqueue time re-parks — there is nothing to compare")
        void an_absent_enqueue_time_should_repark() {
            final Row legacy = parkedAsLegacy();

            final GuardDecision decision = legacyGuard.admit(command, legacyDelivery(0L, null));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(legacy);
        }

        /**
         * The redelivery of the actual parking message, under this heuristic rather than an exact
         * sequence-number test: released by a closed connection (so the broker never incremented
         * its delivery count) and therefore still reading as count 0, but it was enqueued <em>before</em>
         * the request was parked — enqueue time cannot be later than a parking time that had not
         * happened yet. This is the case the spec calls out by name (FR-015).
         */
        @Test
        @DisplayName("a parking-message redelivery released with count 0 but enqueued before "
                + "parking re-parks")
        void a_redelivery_enqueued_before_parking_should_repark() {
            final Row legacy = parkedAsLegacy();
            final Instant beforeParking = legacy.updatedAt().minusSeconds(30);

            final GuardDecision decision =
                    legacyGuard.admit(command, legacyDelivery(0L, beforeParking));

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PIPELINE_TRANSIENT_FAILURE));
            assertThat(row()).isEqualTo(legacy);
        }

        /**
         * A legacy resubmission handed back once — the store was unavailable, or a concurrent
         * replay won the compare-and-set — is judged <strong>afresh</strong> on its next delivery,
         * never remembered as rejected (spec User Story 3, Acceptance Scenario 3). Simulated with a
         * guard whose read is deliberately stale, so the compare-and-set loses the race the first
         * time regardless of how good the heuristic's answer was.
         */
        @Test
        @DisplayName("a legacy resubmission handed back once is re-parked, and judged afresh next time")
        void a_legacy_resubmission_handed_back_once_should_be_judged_afresh() {
            final Row legacy = parkedAsLegacy();
            final Instant wellAfter = legacy.updatedAt().plus(MARGIN).plusSeconds(5);
            final DeliveryIdentity resubmission = legacyDelivery(0L, wellAfter);

            final IdempotencyGuard staleReadingGuard = new IdempotencyGuard(
                    new ProcessedRequestRepository(ProcessedLogTestSupport.jdbcClient(), LEASE) {
                        @Override
                        public Optional<ProcessedRequestRecord> read(
                                final String source, final UUID requestId) {
                            // Stale: attempts one lower than what is really stored, so the
                            // compare-and-set cannot match the real row — standing in for "the
                            // store was unavailable" or "a concurrent replay already won".
                            return Optional.of(new ProcessedRequestRecord(
                                    RequestStatus.FAILED, legacy.requestFingerprint(),
                                    legacy.failureReason(), legacy.exhaustedMessageId(),
                                    legacy.attempts() - 1, null, null, null, null,
                                    legacy.updatedAt()));
                        }
                    },
                    metrics, MARGIN);

            final GuardDecision firstAttempt = staleReadingGuard.admit(command, resubmission);
            assertThat(firstAttempt)
                    .as("the heuristic admitted it, but the compare-and-set lost the race")
                    .isEqualTo(new GuardDecision.Abandon(ReasonCode.REPLAY_NOT_ADMITTED));
            assertThat(row())
                    .as("a hand-back changes nothing: the record is exactly as it was")
                    .isEqualTo(legacy);

            // Judged afresh against the real, unmoved record: the same resubmission now succeeds.
            final GuardDecision secondAttempt = legacyGuard.admit(command, resubmission);
            assertThat(secondAttempt).isInstanceOf(GuardDecision.Run.class);
            assertThat(row().status()).isEqualTo("RECEIVED");
        }

        /**
         * The explicitly accepted residual risk (spec FR-005, the "Assumptions" section): a release
         * without a delivery-count increment, combined with a broker clock running ahead of the
         * database clock by more than the margin, is read as a resubmission even though nothing was
         * actually resubmitted. Pinned here as an accepted case, not a defect — the heuristic cannot
         * distinguish it from a genuine resubmission by construction, and the spec says so rather
         * than hiding it.
         */
        @Test
        @DisplayName("a simulated broker-clock lead greater than the margin demonstrates the "
                + "accepted residual false-replay case")
        void a_broker_clock_lead_beyond_the_margin_should_be_accepted_as_a_replay() {
            final Row legacy = parkedAsLegacy();
            // A broker enqueue time that is only this far ahead of the database's own clock would
            // not occur under a synchronised clock; it stands in for the accepted skew case.
            final Instant skewedEnqueueTime = legacy.updatedAt().plus(MARGIN).plusSeconds(10);

            final GuardDecision decision =
                    legacyGuard.admit(command, legacyDelivery(0L, skewedEnqueueTime));

            assertThat(decision)
                    .as("the heuristic cannot distinguish this from a genuine resubmission, and the "
                            + "spec accepts that rather than hiding it")
                    .isInstanceOf(GuardDecision.Run.class);
        }
    }
}
