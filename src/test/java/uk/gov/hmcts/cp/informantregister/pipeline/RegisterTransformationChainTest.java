package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.informantregister.support.ParityCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three ported activities, chained exactly as the orchestrator chains them.
 *
 * <p>What is under test here is the <em>composition</em>: the order of the three steps, the
 * reference-data call that sits between the first and the second, and the two places the legacy
 * chain stops. The steps themselves are covered by their own twins; this is about the wiring
 * between them, so it runs the real steps rather than mocks — a composition test over mocked steps
 * proves only that the mocks were called.
 *
 * <p>The clock is pinned to the instant the parity pack pinned, because the transformation reads
 * "now" ({@code DateService.js:37}) and that value reaches the register date.
 */
@DisplayName("The transformation chain: SetInformantRegister -> InformantRegisterSubscriptions "
        + "-> OutboundInformantRegister")
class RegisterTransformationChainTest {

    private static final String CORPUS = "recorded";

    private final RecordingSubscriptionsSource source = new RecordingSubscriptionsSource();

    private RegisterTransformationChain chainFor(final ParityCase parityCase) {
        final Clock clock = Clock.fixed(parityCase.clockPin(), ZoneOffset.UTC);
        final HearingDates dates = new HearingDates(clock);
        return new RegisterTransformationChain(
                new RegisterBuilder(dates),
                new SubscriptionMatcher(new SubscriptionRules()),
                new AggregationMapper(dates),
                source);
    }

    /** A source that records what it was asked for and answers what the test tells it to. */
    private static final class RecordingSubscriptionsSource implements NowSubscriptionsSource {

        private final List<LocalDate> asked = new ArrayList<>();
        private JsonNode answer;
        private RuntimeException failure;

        void answers(final JsonNode body) {
            this.answer = body;
            this.failure = null;
        }

        void fails(final RuntimeException cause) {
            this.failure = cause;
        }

        @Override
        public JsonNode fetch(final LocalDate on) {
            asked.add(on);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    @Nested
    @DisplayName("a hearing that produces fragments")
    class ProducesFragments {

        private final ParityCase parityCase = ParityCase.load(CORPUS, "base__prosecution-case");

        @Test
        @DisplayName("produces one document per authority, in the order the builder produced them")
        void produces_one_document_per_authority() {
            source.answers(parityCase.subscriptions());

            final List<InformantRegisterDocument> documents =
                    chainFor(parityCase).transform(parityCase.hearing(), parityCase.sharedTime());

            assertThat(documents).hasSize(2);
            assertThat(documents).extracting(InformantRegisterDocument::prosecutionAuthorityCode)
                    .containsExactly("TFL", "TVL");
        }

        @Test
        @DisplayName("asks reference data for the day the recording shows it was asked for")
        void asks_reference_data_for_the_recorded_day() {
            // `ReferenceDataService.js:38` builds the `on` parameter as
            // `new Date(registerDate).toISOString().slice(0, 10)`, and the recording carries the
            // value the real service produced.
            source.answers(parityCase.subscriptions());

            chainFor(parityCase).transform(parityCase.hearing(), parityCase.sharedTime());

            assertThat(source.asked)
                    .containsExactly(LocalDate.parse(parityCase.recordedRefdataQueryDate()));
        }

        @Test
        @DisplayName("hands the aggregation the hearing it was given, not one an earlier step wrote")
        void hands_the_aggregation_the_original_hearing() {
            // The activity boundary is a real boundary: `SetInformantRegister` mutates its own
            // deserialised copy and `OutboundInformantRegister` receives the orchestrator's
            // original (`InformantRegisterOrchestrator/index.js:39`, oracle/README.md §1). The
            // canonical tree is treated as immutable here, which is the same thing.
            source.answers(parityCase.subscriptions());
            final JsonNode hearing = parityCase.hearing();
            final String before = hearing.toString();

            chainFor(parityCase).transform(hearing, parityCase.sharedTime());

            assertThat(hearing.toString()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("a hearing the builder produces nothing for")
    class ProducesNoFragments {

        private final ParityCase parityCase =
                ParityCase.load(CORPUS, "base__court-application-without-masterdefendant");

        @Test
        @DisplayName("stops before reference data, because the orchestrator's guard stops there")
        void stops_before_reference_data() {
            // `InformantRegisterOrchestrator/index.js:27` — a falsy fragment list skips subscription
            // matching, the outbound mapping and the POST alike.
            source.answers(parityCase.subscriptions());

            final List<InformantRegisterDocument> documents =
                    chainFor(parityCase).transform(parityCase.hearing(), parityCase.sharedTime());

            assertThat(documents).isEmpty();
            assertThat(source.asked).isEmpty();
        }
    }

    @Nested
    @DisplayName("a register date reference data cannot be dated with")
    class UnreadableRegisterDate {

        private final ParityCase parityCase = ParityCase.load(
                CORPUS, "mut__prosecution-case__shared-time__unparseable");

        @Test
        @DisplayName("refuses, because the legacy's own date call throws outside its try block")
        void refuses_an_unreadable_register_date() {
            // `ReferenceDataService.js:38` calls `new Date(on).toISOString()` OUTSIDE the try that
            // starts at :41, so a RangeError escapes to
            // `InformantRegisterSubscriptions/index.js:89`, is swallowed there, and
            // `OutboundInformantRegister/index.js:17` then throws on `.length` and swallows in turn.
            // The hearing produces nothing. Here it produces a classified failure instead —
            // deviations-register entry 7 — and reference data is never called.
            source.answers(parityCase.subscriptions());

            assertThatThrownBy(() ->
                    chainFor(parityCase).transform(parityCase.hearing(), parityCase.sharedTime()))
                    .isInstanceOf(TransformationFailedException.class);
            assertThat(source.asked).isEmpty();
        }
    }

    @Nested
    @DisplayName("reference data that cannot be reached")
    class ReferenceDataUnavailable {

        private final ParityCase parityCase = ParityCase.load(
                CORPUS, "mut__prosecution-case__refdata-unavailable__http-500");

        @Test
        @DisplayName("is a transient failure, never a register that quietly reaches nobody")
        void is_a_transient_failure() {
            // The legacy swallows the failure (`ReferenceDataService.js:52` returns null) and ships
            // a register with no recipients at all — the outage is invisible in the outbound body.
            // Pinning entry d03 requires the port to classify it instead.
            source.fails(new ReferenceDataUnavailableException(
                    ReasonCode.REFERENCE_DATA_UNAVAILABLE));

            assertThatThrownBy(() ->
                    chainFor(parityCase).transform(parityCase.hearing(), parityCase.sharedTime()))
                    .isInstanceOf(ReferenceDataUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("reference data that answers with nothing to match against")
    class ReferenceDataAnswersNothing {

        private final ParityCase parityCase = ParityCase.load(CORPUS, "base__prosecution-case");

        @Test
        @DisplayName("still produces the documents, with no recipients component at all")
        void produces_documents_with_no_recipients() {
            // `InformantRegisterSubscriptions/index.js:22-25` returns the fragments untouched, so
            // `matchedSubscriptions` is absent and `RecipientMapper` is handed `|| []`
            // (`OutboundInformantRegister/index.js:44`).
            source.answers(null);

            final List<InformantRegisterDocument> documents =
                    chainFor(parityCase).transform(parityCase.hearing(), parityCase.sharedTime());

            assertThat(documents).hasSize(2);
            assertThat(documents).extracting(InformantRegisterDocument::recipients)
                    .containsOnlyNulls();
        }
    }

    @Nested
    @DisplayName("the clock the chain is given")
    class ClockPin {

        @Test
        @DisplayName("decides the register date when the hearing was shared without one")
        void decides_the_register_date_when_no_shared_time_was_given() {
            // `moment.tz(undefined, 'Europe/London')` is *now*, so an absent shared time stamps the
            // register with the processing time (`DateService.js:37`). Nine corpus cases move with
            // the clock for this reason; the chain must take the instant it is given.
            final ParityCase parityCase =
                    ParityCase.load(CORPUS, "mut__prosecution-case__shared-time__absent");
            source.answers(parityCase.subscriptions());

            final List<InformantRegisterDocument> documents =
                    chainFor(parityCase).transform(parityCase.hearing(), parityCase.sharedTime());

            assertThat(documents).isNotEmpty();
            assertThat(documents.getFirst().registerDate().toInstant())
                    .isEqualTo(Instant.parse("2026-08-21T10:15:00Z"));
        }
    }
}
