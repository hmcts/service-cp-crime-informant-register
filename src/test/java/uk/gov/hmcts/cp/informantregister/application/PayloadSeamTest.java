package uk.gov.hmcts.cp.informantregister.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.pipeline.AggregationMapper;
import uk.gov.hmcts.cp.informantregister.pipeline.HearingDates;
import uk.gov.hmcts.cp.informantregister.pipeline.RegisterBuilder;
import uk.gov.hmcts.cp.informantregister.pipeline.RegisterTransformationChain;
import uk.gov.hmcts.cp.informantregister.pipeline.SubscriptionMatcher;
import uk.gov.hmcts.cp.informantregister.pipeline.SubscriptionRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The seam between the payload source and the transformation, driven through the <em>real</em>
 * ported chain — the test this service's first live defect proved was missing.
 *
 * <p>The payload the sources answer with is a <strong>wrapper</strong>, not a hearing: the
 * {@code INT_} cache document is {@code {isReshare, hearingDay, sharedTime, hearing}} and the
 * query-API answer is {@code {hearing, sharedTime}}. The legacy orchestrator unwraps it —
 * {@code InformantRegisterOrchestrator/index.js:21-24} passes {@code hearingResultedObj.hearing}
 * and {@code hearingResultedObj.sharedTime} to {@code SetInformantRegister}, both read from the
 * fetched document and neither from the queue message. A pipeline that hands the wrapper itself to
 * the chain finds neither {@code prosecutionCases} nor {@code courtApplications} on it and
 * completes every hearing {@code no-authorities} — which is exactly what STE-42 did on
 * 2026-08-23, and what every test below would have caught: the unit suites feed the chain bare
 * hearings, the adapter suite asserts the wrapper, and nothing before this class spanned the two
 * shapes across the seam.
 *
 * <p>The corpus case {@code base__case-and-application} supplies the hearing, the reference-data
 * answer and the expected documents (three authorities, TFL first), so the assertions here are the
 * oracle's own values, not invented ones.
 */
@DisplayName("The payload seam: wrappers in, the payload's own hearing and shared time onward")
class PayloadSeamTest {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private static final String CORPUS_INPUTS = "/parity/recorded/base__case-and-application/inputs";

    /** The corpus recording's clock pin; also what a missing shared time must be stamped with. */
    private static final Instant CLOCK_PIN = Instant.parse("2026-08-21T09:15:00Z");

    /** The shared time inside the payload — the one the legacy stamps the register with. */
    private static final String PAYLOAD_SHARED_TIME = "2021-03-11T22:18:24.506Z";

    /** A command shared time that differs from the payload's, so the seam's choice is visible. */
    private static final Instant COMMAND_SHARED_TIME = Instant.parse("2021-03-12T09:00:00Z");

    /** The first authority of the corpus expectation ({@code expected.json[0]}, TFL). */
    private static final String FIRST_AUTHORITY = "31af405e-7b60-4dd8-a244-c24c2d3fa595";

    private static final Duration PROCESSING_DEADLINE = Duration.ofMinutes(4);
    private static final String OWNER = "runner-1/delivery-1";
    private static final String MESSAGE_ID = "RESULTS:1";

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);
    private final Clock clock = Clock.fixed(CLOCK_PIN, ZoneOffset.UTC);
    private final HearingDates dates = new HearingDates(clock);

    /** The real chain: the ported steps, with only the reference-data port answered in place. */
    private final RegisterTransformer transformer = new RegisterTransformationChain(
            new RegisterBuilder(dates),
            new SubscriptionMatcher(new SubscriptionRules()),
            new AggregationMapper(dates),
            (on, identity) -> resource("subscriptions.json"));

    private final DistributionPipeline pipeline = new DistributionPipeline(
            guard, payloadSource, transformer, submissionClient,
            new ProcessingMetrics(new SimpleMeterRegistry()), clock, PROCESSING_DEADLINE);

    private final DistributionCommand command = new DistributionCommand(
            "RESULTS",
            UUID.randomUUID(),
            UUID.fromString("053cb01f-def9-4182-8fef-d4970afeb1da"),
            LocalDate.of(2021, 3, 11),
            COMMAND_SHARED_TIME,
            "Hearing_Resulted");

    private final DeliveryIdentity delivery = new DeliveryIdentity(MESSAGE_ID, OWNER);

    private final RunClaim claim = new RunClaim(
            command.source(), command.requestId(), OWNER, UUID.randomUUID(), MESSAGE_ID);

    // --- fixtures --------------------------------------------------------------------------

    private static JsonNode resource(final String name) {
        return MAPPER.readTree(PayloadSeamTest.class.getResourceAsStream(CORPUS_INPUTS + '/' + name));
    }

    /** The {@code INT_} cache document shape, as results caches it. */
    private static ObjectNode cacheWrapped() {
        final ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("isReshare", false);
        wrapper.put("hearingDay", "2021-03-11");
        wrapper.put("sharedTime", PAYLOAD_SHARED_TIME);
        wrapper.set("hearing", resource("hearing.json"));
        return wrapper;
    }

    /** The query-API answer shape ({@code hearingDetails/internal}). */
    private static ObjectNode queryApiWrapped() {
        final ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("hearing", resource("hearing.json"));
        wrapper.put("sharedTime", PAYLOAD_SHARED_TIME);
        return wrapper;
    }

    private void guardAdmits(final JsonNode payload) {
        when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
        when(payloadSource.fetch(command)).thenReturn(payload);
        when(guard.recordCompletion(claim, CompletionReason.AUTHORITIES_SUBMITTED))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
    }

    private List<AuthoritySubmission> submissions() {
        final ArgumentCaptor<AuthoritySubmission> captor =
                ArgumentCaptor.forClass(AuthoritySubmission.class);
        verify(submissionClient, org.mockito.Mockito.times(3)).submit(captor.capture());
        return captor.getAllValues();
    }

    // --- the wrapper shapes ----------------------------------------------------------------

    @Nested
    @DisplayName("a payload wrapped the way the sources actually answer")
    class WrappedPayload {

        @Test
        void process_a_cache_shaped_wrapper_should_submit_one_document_per_authority() {
            guardAdmits(cacheWrapped());

            pipeline.process(command, delivery);

            final List<AuthoritySubmission> posted = submissions();
            assertThat(posted).hasSize(3);
            assertThat(posted.getFirst().prosecutionAuthorityId()).isEqualTo(FIRST_AUTHORITY);
        }

        @Test
        void process_a_query_api_shaped_wrapper_should_submit_the_same_authorities() {
            guardAdmits(queryApiWrapped());

            pipeline.process(command, delivery);

            final List<AuthoritySubmission> posted = submissions();
            assertThat(posted).hasSize(3);
            assertThat(posted.getFirst().prosecutionAuthorityId()).isEqualTo(FIRST_AUTHORITY);
        }

        /**
         * The discriminating case: the payload's shared time and the command's differ, and the
         * register must be stamped with the payload's — {@code index.js:23} passes
         * {@code hearingResultedObj.sharedTime}, not the envelope the trigger received.
         */
        @Test
        void process_should_stamp_the_register_date_from_the_payload_shared_time() {
            guardAdmits(cacheWrapped());

            pipeline.process(command, delivery);

            assertThat(submissions().getFirst().document().registerDate())
                    .isEqualTo(ZonedDateTime.parse("2021-03-11T22:18:24Z"));
        }
    }

    // --- wrappers missing a member -----------------------------------------------------------

    @Nested
    @DisplayName("a wrapper missing a member, resolved the way the legacy resolves it")
    class MissingMembers {

        /**
         * No {@code hearing} member: the legacy throws — {@code SetInformantRegister/index.js:29}
         * reads {@code input.hearingObj.id} off {@code undefined} — and the orchestrator's
         * catch-all swallows the run. Deviations-register entry 7 maps exactly that class of
         * failure to a non-transient refusal here.
         */
        @Test
        void process_a_wrapper_without_a_hearing_should_park_the_request() {
            final ObjectNode wrapper = cacheWrapped();
            wrapper.remove("hearing");
            assertParkedNonTransient(wrapper);
        }

        /** An explicit {@code hearing: null} is the same {@code TypeError} in the legacy. */
        @Test
        void process_a_wrapper_with_a_null_hearing_should_park_the_request() {
            final ObjectNode wrapper = cacheWrapped();
            wrapper.set("hearing", NullNode.getInstance());
            assertParkedNonTransient(wrapper);
        }

        /**
         * No {@code sharedTime}: the legacy passes {@code undefined} through and
         * {@code moment.tz(undefined, zone)} is the current time, so the register is stamped with
         * the wall clock ({@code HearingDates}, "absent input means now") and the hearing still
         * goes out.
         */
        @Test
        void process_a_wrapper_without_a_shared_time_should_stamp_the_clock() {
            final ObjectNode wrapper = cacheWrapped();
            wrapper.remove("sharedTime");
            guardAdmits(wrapper);

            pipeline.process(command, delivery);

            assertThat(submissions().getFirst().document().registerDate())
                    .isEqualTo(ZonedDateTime.parse("2026-08-21T10:15:00Z"));
        }

        private void assertParkedNonTransient(final JsonNode payload) {
            when(guard.admit(command, delivery)).thenReturn(new GuardDecision.Run(claim));
            when(payloadSource.fetch(command)).thenReturn(payload);
            final GuardDecision parked = new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.TRANSFORMATION_FAILED);
            when(guard.recordNonTransientFailure(claim, ReasonCode.TRANSFORMATION_FAILED))
                    .thenReturn(parked);

            final GuardDecision decision = pipeline.process(command, delivery);

            assertThat(decision).isEqualTo(parked);
            verifyNoInteractions(submissionClient);
        }
    }
}
