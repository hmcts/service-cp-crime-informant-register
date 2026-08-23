package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.informantregister.support.JsonParity;
import uk.gov.hmcts.cp.informantregister.support.ParityCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Every recorded case of the parity pack, run through the whole ported chain.
 *
 * <p>Each case in {@code src/test/resources/parity/recorded/} is a recording of the <em>real</em>
 * Node function app turning a hearing payload into outbound {@code add-informant-register}
 * documents, captured at Node commit {@code a8d3c00b} with the wall clock pinned. The Java port is
 * correct when it produces the same documents.
 *
 * <p><strong>Not every case is a parity obligation, and the corpus says which.</strong> Each
 * {@code meta.json} carries a {@code contract.status}, and it decides which assertion a case gets:
 *
 * <ul>
 *   <li>{@code IN_CONTRACT} — the input satisfies the published schema, so the producer can send it
 *       and the port must reproduce the recorded documents exactly, under the comparator's rules;</li>
 *   <li>{@code SCHEMA_INVALID} / {@code PRODUCER_IMPLAUSIBLE} — the producer cannot send it. The
 *       recording is real evidence of what Node does, but it is not something this port owes. What
 *       it <em>does</em> owe is an explicit outcome: documents, or a classified failure, never
 *       silence and never an exception nothing named. Holding these to golden equality would make
 *       the port promise to reproduce bodies that violate the frozen contract — 18 of them carry an
 *       explicit {@code null} where the schema demands a string.</li>
 * </ul>
 *
 * <p><strong>Where Node lost the hearing, the port must say so.</strong> Fifteen cases record an
 * exception the function app caught, logged and discarded ({@code observed.outcome ==
 * "swallowed-exception"}); the parity requirement is the documents — none — and the divergence is
 * that here the request reaches a classified, recorded failure instead. That is
 * {@code doc/DEVIATIONS.md} entry 7. Nine more record a hearing that legitimately produced no
 * fragments, which is entry 6, and those must complete quietly with nothing.
 *
 * <p><strong>A reference-data outage is the one place the documents deliberately differ.</strong>
 * Twenty cases make the now-subscriptions call fail. Node swallows that and ships a register with no
 * recipients at all — the outage is invisible in the outbound body — and pinning entry {@code d03}
 * requires this port to classify it transiently instead. Registered as entry 14, and asserted here
 * as a classified failure rather than as those documents.
 */
@DisplayName("Golden parity: the ported chain against every recorded Node run")
class RegisterTransformerParityTest {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterTransformerParityTest.class);

    private static final String CORPUS = "recorded";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * Cases held back on a decision that is not this port's to take.
     *
     * <p>The value is the decision item, quoted so a reader of the skip message knows who has to
     * answer it before the case can be armed. Nothing is deleted; a held-back case is reported as
     * skipped on every run, which is how it stays visible.
     */
    private static final Map<String, String> BLOCKED_ON_A_DECISION = Map.of(
            "base__outbound-hearing",
            "parity pin s05 / doc/DEVIATIONS.md entry 10 — the hearing date on this UNMODIFIED real "
                    + "fixture comes out as the literal string \"Invalid dateZ\", which Node POSTs "
                    + "into a component the frozen contract types as a date-time. The port refuses "
                    + "instead. Reproduce or dead-letter is an open question with the Results team "
                    + "(parity-pack/README.md §5 finding 6); until it is answered this case cannot "
                    + "be asserted either way without choosing for them",

            "base__outbound-hearing-with-application",
            "parity pin s05 / doc/DEVIATIONS.md entry 10 — as base__outbound-hearing, on the "
                    + "second unmodified fixture that produces the same literal \"Invalid dateZ\" "
                    + "hearing date");

    static Stream<Arguments> recordedCases() {
        return ParityCase.caseIds(CORPUS).stream()
                .map(id -> ParityCase.load(CORPUS, id))
                .map(parityCase -> Arguments.of(Named.of(parityCase.caseId(), parityCase)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedCases")
    void transform_recordedCase_should_agree_with_the_node_oracle(final ParityCase parityCase) {
        final String blocked = BLOCKED_ON_A_DECISION.get(parityCase.caseId());
        if (blocked != null) {
            Assumptions.abort("HELD BACK on an undecided item — " + blocked
                    + ". The case is kept and reported rather than deleted.");
        }

        final Outcome outcome = run(parityCase, parityCase.subscriptions());

        if (parityCase.refdata() == ParityCase.RefdataAnswer.REJECT) {
            assertReferenceDataOutageIsClassified(parityCase, outcome);
            return;
        }

        if (!"IN_CONTRACT".equals(parityCase.contractStatus())) {
            // The producer cannot send this input, so what Node does with it is evidence and not an
            // obligation — in either direction. What the port owes is an explicit outcome, and both
            // answers are logged so a change of behaviour on these inputs shows up in the run rather
            // than passing unnoticed. See the class comment.
            outcome.requireAnExplicitOutcome(parityCase);
            LOG.info("{} [{}]: out-of-contract input; Node recorded {} and the port answered {}",
                    parityCase.caseId(), parityCase.contractStatus(), parityCase.oracleOutcome(),
                    outcome.describe());
            return;
        }

        switch (parityCase.oracleOutcome()) {
            case "documents" -> assertDocuments(parityCase, outcome);
            case "no-fragments" -> assertProducedNothingQuietly(parityCase, outcome);
            case "swallowed-exception" -> assertRefusedWhereNodeSwallowed(parityCase, outcome);
            default -> fail("%s: the corpus records an outcome this test does not handle: %s",
                    parityCase.caseId(), parityCase.oracleOutcome());
        }

        assertSecondDeliveryMatches(parityCase);
    }

    // --- the four assertions -------------------------------------------------------------------

    /**
     * Node produced an array. Whether that is an obligation depends on the contract status.
     *
     * @param parityCase the case
     * @param outcome    what the port did
     */
    private static void assertDocuments(final ParityCase parityCase, final Outcome outcome) {
        outcome.requireDocuments(parityCase);
        JsonParity.assertMatches(
                parityCase.expected(), MAPPER.valueToTree(outcome.documents()),
                parityCase.caseId());
    }

    /**
     * Node legitimately produced no fragments, so nothing is sent and nothing failed.
     *
     * @param parityCase the case
     * @param outcome    what the port did
     */
    private static void assertProducedNothingQuietly(
            final ParityCase parityCase, final Outcome outcome) {

        assertThat(outcome.failure())
                .as("%s: the legacy produced no fragments without failing, so this must complete "
                        + "with nothing rather than refuse (doc/DEVIATIONS.md entry 6)",
                        parityCase.caseId())
                .isNull();
        assertThat(outcome.documents())
                .as("%s: the legacy produced no documents", parityCase.caseId())
                .isEmpty();
    }

    /**
     * Node threw, caught it, logged it and lost the hearing. The port must reach a named failure.
     *
     * @param parityCase the case
     * @param outcome    what the port did
     */
    private static void assertRefusedWhereNodeSwallowed(
            final ParityCase parityCase, final Outcome outcome) {

        assertThat(outcome.failure())
                .as("%s: the legacy swallowed an exception here and the hearing disappeared. The "
                        + "port must reach a classified failure instead (doc/DEVIATIONS.md entry 7)."
                        + " It answered: %s", parityCase.caseId(), outcome.describe())
                .isInstanceOf(TransformationFailedException.class);
        assertThat(((TransformationFailedException) outcome.failure()).reason())
                .isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
    }

    /**
     * Reference data could not be asked, and the port must say so rather than address nobody.
     *
     * @param parityCase the case
     * @param outcome    what the port did
     */
    private static void assertReferenceDataOutageIsClassified(
            final ParityCase parityCase, final Outcome outcome) {

        assertThat(outcome.failure())
                .as("%s: the legacy swallows a reference-data outage and POSTs a register with no "
                        + "recipients (%d document(s) recorded). The port must classify it "
                        + "transiently instead (pinning d03, doc/DEVIATIONS.md entry 14). It "
                        + "answered: %s",
                        parityCase.caseId(),
                        parityCase.expected().isNull() ? 0 : parityCase.expected().size(),
                        outcome.describe())
                .isInstanceOf(ReferenceDataUnavailableException.class);
    }

    /**
     * A re-share case is two deliveries, and the second must be the recorded second.
     *
     * <p>The corpus keeps these because two deliveries of the same hearing are two orchestrations,
     * each parsing the payload afresh; a port that carried state between them would answer
     * differently the second time.
     *
     * @param parityCase the case
     */
    private static void assertSecondDeliveryMatches(final ParityCase parityCase) {
        final JsonNode second = parityCase.expectedSecondDelivery();
        if (second == null || !"IN_CONTRACT".equals(parityCase.contractStatus())) {
            return;
        }
        final Outcome redelivered = run(parityCase, parityCase.subscriptions());
        redelivered.requireDocuments(parityCase);
        JsonParity.assertMatches(second, MAPPER.valueToTree(redelivered.documents()),
                parityCase.caseId() + " (second delivery)");
    }

    // --- running one case ------------------------------------------------------------------------

    private static Outcome run(final ParityCase parityCase, final JsonNode answer) {
        final Clock clock = Clock.fixed(parityCase.clockPin(), ZoneOffset.UTC);
        final HearingDates dates = new HearingDates(clock);
        final RegisterTransformationChain chain = new RegisterTransformationChain(
                new RegisterBuilder(dates),
                new SubscriptionMatcher(new SubscriptionRules()),
                new AggregationMapper(dates),
                sourceFor(parityCase, answer));
        try {
            return new Outcome(chain.transform(parityCase.hearing(), parityCase.sharedTime()), null);
        } catch (TransformationFailedException | ReferenceDataUnavailableException classified) {
            return new Outcome(null, classified);
        }
    }

    /**
     * The reference-data port, answering the way the case declares.
     *
     * <p>A {@code sequence} case is the retry loop recovering — the first attempt fails and the
     * second answers — so from this port's side it is an answer, not an outage. That distinction is
     * the adapter's to keep, and the corpus proves it matters: the always-500 control on the same
     * base loses every recipient.
     *
     * @param parityCase the case
     * @param answer     the subscriptions body to answer with
     * @return the port
     */
    private static NowSubscriptionsSource sourceFor(
            final ParityCase parityCase, final JsonNode answer) {

        if (parityCase.refdata() == ParityCase.RefdataAnswer.REJECT) {
            return on -> {
                throw new ReferenceDataUnavailableException(ReasonCode.REFERENCE_DATA_UNAVAILABLE);
            };
        }
        return new RecordedQueryDateSource(parityCase, answer);
    }

    /** Answers the recorded body, and checks the query date the chain derived on the way past. */
    private record RecordedQueryDateSource(ParityCase parityCase, JsonNode answer)
            implements NowSubscriptionsSource {

        @Override
        public JsonNode fetch(final LocalDate on) {
            final String recorded = parityCase.recordedRefdataQueryDate();
            if (recorded != null) {
                assertThat(on)
                        .as("%s: the recording shows reference data being asked for %s",
                                parityCase.caseId(), recorded)
                        .isEqualTo(LocalDate.parse(recorded));
            }
            return answer;
        }
    }

    /** What the chain did: documents, or the classified failure it raised instead. */
    private record Outcome(List<InformantRegisterDocument> documents, RuntimeException failure) {

        void requireDocuments(final ParityCase parityCase) {
            assertThat(failure)
                    .as("%s: the legacy produced %d document(s); the port refused instead",
                            parityCase.caseId(),
                            parityCase.expected().isNull() ? 0 : parityCase.expected().size())
                    .isNull();
        }

        void requireAnExplicitOutcome(final ParityCase parityCase) {
            assertThat(documents == null ^ failure == null)
                    .as("%s: the port must answer with documents or with a classified failure",
                            parityCase.caseId())
                    .isTrue();
        }

        String describe() {
            return failure == null
                    ? documents.size() + " document(s)"
                    : failure.getClass().getSimpleName() + " (" + failure.getMessage() + ")";
        }
    }
}
