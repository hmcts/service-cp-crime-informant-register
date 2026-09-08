package uk.gov.hmcts.cp.informantregister.adapter.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearing;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterHearingVenue;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;

/**
 * The order the submission leg does things in, which is the whole of its safety.
 *
 * <p>{@code add-informant-register} is not idempotent: a second POST makes a second register row.
 * Three properties keep an at-least-once delivery safe to submit from, and each is asserted here
 * rather than argued for in a comment.
 *
 * <ul>
 *   <li><strong>The row is claimed before the POST.</strong> A POST whose outcome is never learned —
 *       a timeout, a dropped connection — must still leave evidence that it was attempted, and what
 *       was in it. Writing the row afterwards would lose exactly the case the evidence is for.</li>
 *   <li><strong>An authority already POSTED is skipped.</strong> Partial progress across five
 *       authorities survives a redelivery, so the three that went do not go twice.</li>
 *   <li><strong>A failure is recorded before it is rethrown.</strong> The pipeline turns the
 *       exception into a settlement; if the row were left PENDING the log would say a submission was
 *       in flight that nothing was going to finish.</li>
 * </ul>
 *
 * <p>The digest is asserted against the bytes that actually went out, not against a re-serialisation
 * of the document. It exists for reconciliation and replay diffing, and a digest of something other
 * than what was sent is worse than none.
 *
 * <p>The transport is mocked here on purpose. What the wire looks like is settled against a real
 * socket in {@link ResultsCommandGatewayTest}; what is settled here is ordering, and ordering is
 * exactly what a stubbed HTTP server cannot show.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Results register submission client")
class ResultsRegisterSubmissionClientTest {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();
    private static final String SOURCE = "RESULTS";
    private static final String AUTHORITY = "3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8";
    private static final String DEFENDANT_NAME = "SMITH, John";

    /** The run's caller, carried on the submission and handed to the transport unchanged. */
    private static final CallerIdentity IDENTITY = new CallerIdentity(
            Optional.of(UUID.fromString("0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48")));

    @Mock
    private ProcessedOutputRepository outputs;

    @Mock
    private ResultsCommandGateway gateway;

    private final UUID requestId = UUID.randomUUID();

    private ResultsRegisterSubmissionClient client() {
        return new ResultsRegisterSubmissionClient(outputs, gateway, MAPPER);
    }

    private AuthoritySubmission submission() {
        return new AuthoritySubmission(SOURCE, requestId, AUTHORITY, document(), IDENTITY);
    }

    @Nested
    @DisplayName("the happy path, in order")
    class Accepted {

        @Test
        void the_row_should_be_claimed_before_the_post_and_marked_posted_after() {
            when(outputs.claimPending(any(), eq(SOURCE), eq(requestId), eq(AUTHORITY), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(true);

            client().submit(submission());

            final InOrder order = inOrder(outputs, gateway);
            order.verify(outputs).claimPending(any(), eq(SOURCE), eq(requestId), eq(AUTHORITY), anyString());
            order.verify(gateway).post(
                    any(byte[].class), any(CallerIdentity.class), any(CommandCorrelation.class));
            order.verify(outputs).recordPosted(SOURCE, requestId, AUTHORITY);
            order.verifyNoMoreInteractions();
        }

        /**
         * The identifiers handed to the gateway are the ones its lines will be searched by.
         *
         * <p>{@code source} and {@code requestId} were already on this class's own lines; the
         * hearing was not, and "was this hearing's register filed?" is the question support
         * actually arrives with. It comes off the document, because the document is the register of
         * that hearing — reading it from anywhere else would be a second answer to the same
         * question.
         */
        @Test
        void the_gateway_should_be_told_which_request_and_hearing_the_command_belongs_to() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(true);
            final ArgumentCaptor<CommandCorrelation> correlation =
                    ArgumentCaptor.forClass(CommandCorrelation.class);

            client().submit(submission());

            verify(gateway).post(any(), any(), correlation.capture());
            assertThat(correlation.getValue().source()).isEqualTo(SOURCE);
            assertThat(correlation.getValue().requestId()).isEqualTo(requestId);
            assertThat(correlation.getValue().authorityId()).isEqualTo(AUTHORITY);
            assertThat(correlation.getValue().hearingId())
                    .as("the hearing the document is a register of, not some other hearing")
                    .isEqualTo(submission().document().hearingId());
        }

        @Test
        void the_bytes_posted_should_be_the_document_this_service_produced() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);

            client().submit(submission());

            verify(gateway).post(sent.capture(), any(), any());
            assertThat(new String(sent.getValue(), StandardCharsets.UTF_8))
                    .isEqualTo(MAPPER.writeValueAsString(document()));
        }

        @Test
        void the_caller_posted_as_should_be_the_one_the_submission_carries() {
            // The adapter resolves no identity of its own. The run decided who it is made as, once,
            // and this leg passes it through — which is what makes every authority of a run go out
            // under the same caller, as `ProcessOutboundInformantRegister/index.js:21` does.
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(true);
            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);

            client().submit(submission());

            verify(gateway).post(any(), caller.capture(), any());
            assertThat(caller.getValue()).isEqualTo(IDENTITY);
        }

        @Test
        void the_identity_should_never_reach_a_log_line() {
            // A user identifier at info level is the no-PII gate's business, and this leg logs one
            // line per authority on the happy path.
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(true);

            try (CapturedLog log = CapturedLog.of(ResultsRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings())
                        .noneMatch(line -> line.contains(IDENTITY.userId().orElseThrow().toString()));
            }
        }

        @Test
        void the_digest_recorded_should_be_the_sha_256_of_the_bytes_that_went_out() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(true);
            final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
            final ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);

            client().submit(submission());

            verify(outputs).claimPending(any(), anyString(), any(), anyString(), digest.capture());
            verify(gateway).post(sent.capture(), any(), any());
            assertThat(digest.getValue()).isEqualTo(sha256(sent.getValue()));
        }
    }

    @Nested
    @DisplayName("an authority that has already gone")
    class AlreadyPosted {

        @Test
        void a_refused_claim_should_skip_the_post_entirely() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(false);

            client().submit(submission());

            verify(gateway, never()).post(any(), any(), any());
            verify(outputs, never()).recordPosted(anyString(), any(), anyString());
            verify(outputs, never()).recordFailed(anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("a submission that did not go")
    class Failed {

        @Test
        void a_transient_failure_should_be_recorded_before_it_is_rethrown() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordFailed(SOURCE, requestId, AUTHORITY)).thenReturn(true);
            doThrow(new SubmissionFailedException(
                    FailureClassification.TRANSIENT, ReasonCode.PIPELINE_TRANSIENT_FAILURE))
                    .when(gateway).post(any(), any(), any());

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).classification())
                    .isEqualTo(FailureClassification.TRANSIENT);

            final InOrder order = inOrder(gateway, outputs);
            order.verify(gateway).post(any(), any(), any());
            order.verify(outputs).recordFailed(SOURCE, requestId, AUTHORITY);
            verify(outputs, never()).recordPosted(anyString(), any(), anyString());
        }

        @Test
        void a_refusal_should_reach_the_pipeline_with_its_classification_intact() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordFailed(SOURCE, requestId, AUTHORITY)).thenReturn(true);
            doThrow(new SubmissionFailedException(
                    FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED))
                    .when(gateway).post(any(), any(), any());

            assertThatThrownBy(() -> client().submit(submission()))
                    .isInstanceOf(SubmissionFailedException.class)
                    .extracting(failure -> ((SubmissionFailedException) failure).reason())
                    .isEqualTo(ReasonCode.SUBMISSION_REJECTED);

            verify(outputs).recordFailed(SOURCE, requestId, AUTHORITY);
        }
    }

    /**
     * The outcome write is the durable half of a submission, and it is checked rather than assumed.
     *
     * <p>A claim was granted moments before, so the only way one of these statements can affect no
     * row is that a delivery this one overlapped with reached the row first and POSTED it — POSTED
     * being terminal in the log. That means two runners were working the same request, which is
     * worth an ERROR: what this runner believes happened is not what the log durably says.
     */
    @Nested
    @DisplayName("an outcome write that lands on nothing")
    class OutcomeNotRecorded {

        @Test
        void a_post_that_recorded_nothing_should_be_reported_rather_than_assumed_written() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(false);

            try (CapturedLog log = CapturedLog.of(ResultsRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings())
                        .anyMatch(line -> line.contains("Outcome write affected no row")
                                && line.contains(AUTHORITY));
            }
        }

        @Test
        void a_failure_that_recorded_nothing_should_still_reach_the_pipeline() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordFailed(SOURCE, requestId, AUTHORITY)).thenReturn(false);
            doThrow(new SubmissionFailedException(
                    FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED))
                    .when(gateway).post(any(), any(), any());

            try (CapturedLog log = CapturedLog.of(ResultsRegisterSubmissionClient.class)) {
                assertThatThrownBy(() -> client().submit(submission()))
                        .isInstanceOf(SubmissionFailedException.class);

                assertThat(log.renderings())
                        .as("a failure the log could not record is still a failure")
                        .anyMatch(line -> line.contains("Outcome write affected no row"));
            }
        }
    }

    @Nested
    @DisplayName("what the log is allowed to say")
    class Privacy {

        @Test
        void no_line_should_carry_the_document_or_anyone_named_in_it() {
            when(outputs.claimPending(any(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);
            when(outputs.recordPosted(SOURCE, requestId, AUTHORITY)).thenReturn(true);

            try (CapturedLog log = CapturedLog.of(ResultsRegisterSubmissionClient.class)) {
                client().submit(submission());

                assertThat(log.renderings())
                        .as("a register names defendants; identifiers are all a log line may carry")
                        .noneMatch(line -> line.contains(DEFENDANT_NAME))
                        .noneMatch(line -> line.contains("1 High Street"));
            }
        }
    }

    private static String sha256(final byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static InformantRegisterDocument document() {
        final InformantRegisterDefendant defendant = new InformantRegisterDefendant(
                DEFENDANT_NAME, null, "1 High Street", null, null, null, null,
                null, null, null, null, null, null, null);
        final InformantRegisterHearing session =
                new InformantRegisterHearing("Court 1", "10:00:00Z", List.of(defendant));
        final InformantRegisterHearingVenue venue =
                new InformantRegisterHearingVenue(null, "Bristol Magistrates' Court", List.of(session));
        return new InformantRegisterDocument(
                ZonedDateTime.parse("2026-08-20T11:00:00Z"),
                ZonedDateTime.parse("2026-08-19T10:00:00Z"),
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                UUID.fromString(AUTHORITY),
                "CPS", null, null, null,
                "informant-register-CPS-20260820.pdf", null, venue, null);
    }
}
