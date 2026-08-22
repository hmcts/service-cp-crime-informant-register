package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The vocabulary flags, including the ones this flow can never set.
 *
 * <p>The legacy Jest fixtures for {@code SetInformantRegister} carry no custody, no attendance and
 * no CPS prosecutor, so the golden suite exercises only the default answers. These cases drive the
 * flags that actually vary, from {@code NowsHelper/service/VocabularyService.js}.
 *
 * <p>The two major-creditor lists are asserted empty rather than omitted. That is not a stub: this
 * flow constructs the legacy service with two arguments, which makes the creditor branch return an
 * empty list unconditionally, so empty is the correct and only possible answer here. Pinning it stops
 * somebody later reading the empty lists as an unfinished port and "completing" it — that would be an
 * unregistered behaviour change to subscription matching.
 */
@DisplayName("VocabularyBuilder")
class VocabularyBuilderTest {

    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("custody")
    class Custody {

        @Test
        @DisplayName("is police when the defendant is held at a police station")
        void is_police_when_held_at_a_police_station() {
            final RegisterVocabulary vocabulary = vocabularyOf(defendantHeldAt("Police Station"));
            assertThat(vocabulary.custodyLocationIsPolice()).isTrue();
            assertThat(vocabulary.custodyLocationIsPrison()).isFalse();
            assertThat(vocabulary.inCustody()).isTrue();
        }

        @Test
        @DisplayName("is prison when the defendant is held at a prison")
        void is_prison_when_held_at_a_prison() {
            final RegisterVocabulary vocabulary = vocabularyOf(defendantHeldAt("Prison"));
            assertThat(vocabulary.custodyLocationIsPrison()).isTrue();
            assertThat(vocabulary.custodyLocationIsPolice()).isFalse();
            assertThat(vocabulary.inCustody()).isTrue();
        }

        @Test
        @DisplayName("is neither for a location the legacy does not recognise")
        void is_neither_for_an_unrecognised_location() {
            final RegisterVocabulary vocabulary =
                    vocabularyOf(defendantHeldAt("DETENTIONCENTRE"));
            assertThat(vocabulary.inCustody()).isFalse();
        }

        @Test
        @DisplayName("is read from an application's master defendant too")
        void is_read_from_an_application_master_defendant() {
            final RegisterVocabulary vocabulary = vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "courtApplications":[{"id":"app-1",
                  "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                  "subject":{"masterDefendant":{"masterDefendantId":"master-1",
                   "personDefendant":{"custodialEstablishment":{"custody":"Prison"}}}},
                  "judicialResults":[{"orderedDate":"2020-01-20"}]}]}""");

            assertThat(vocabulary.custodyLocationIsPrison()).isTrue();
        }

        @Test
        @DisplayName("is not borrowed from a different defendant held elsewhere")
        void is_not_borrowed_from_a_different_defendant() {
            final RegisterVocabulary vocabulary = vocabularyOf("master-1", """
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                  "defendants":[
                   {"id":"def-1","masterDefendantId":"master-1","offences":[],
                    "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]},
                  {"id":"case-2","prosecutionCaseIdentifier":{},
                   "defendants":[{"id":"def-2","masterDefendantId":"master-2","offences":[],
                    "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}],
                    "personDefendant":{"custodialEstablishment":{"custody":"Prison"}}}]}]}""");

            assertThat(vocabulary.inCustody()).isFalse();
        }
    }

    @Nested
    @DisplayName("attendance")
    class Attendance {

        @Test
        @DisplayName("is in person when the defendant attended on a day a result was ordered")
        void is_in_person_when_attended_on_a_resulted_day() {
            final RegisterVocabulary vocabulary = vocabularyOf(attended("IN_PERSON", "2020-01-20"));
            assertThat(vocabulary.appearedInPerson()).isTrue();
            assertThat(vocabulary.appearedByVideoLink()).isFalse();
            assertThat(vocabulary.anyAppearance()).isTrue();
        }

        @Test
        @DisplayName("is by video link when the defendant attended that way")
        void is_by_video_when_attended_that_way() {
            final RegisterVocabulary vocabulary = vocabularyOf(attended("BY_VIDEO", "2020-01-20"));
            assertThat(vocabulary.appearedByVideoLink()).isTrue();
            assertThat(vocabulary.anyAppearance()).isTrue();
        }

        @Test
        @DisplayName("does not count a day on which nothing was ordered")
        void does_not_count_a_day_with_no_result() {
            final RegisterVocabulary vocabulary = vocabularyOf(attended("IN_PERSON", "2019-05-05"));
            assertThat(vocabulary.anyAppearance()).isFalse();
        }

        @Test
        @DisplayName("does not count attendance recorded against another defendant")
        void does_not_count_attendance_of_another_defendant() {
            final RegisterVocabulary vocabulary = vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]}],
                 "defendantAttendance":[{"defendantId":"someone-else",
                  "attendanceDays":[{"day":"2020-01-20","attendanceType":"IN_PERSON"}]}]}""");

            assertThat(vocabulary.anyAppearance()).isFalse();
        }
    }

    @Nested
    @DisplayName("custodial results")
    class CustodialResults {

        @Test
        @DisplayName("are found by the prison prompt reference")
        void are_found_by_the_prison_prompt() {
            final RegisterVocabulary vocabulary =
                    vocabularyOf(promptedWith("prisonOrganisationName"));
            assertThat(vocabulary.atleastOneCustodialResult()).isTrue();
            assertThat(vocabulary.allNonCustodialResults()).isFalse();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isFalse();
        }

        @Test
        @DisplayName("are absent when every prompt is something else")
        void are_absent_when_every_prompt_is_something_else() {
            final RegisterVocabulary vocabulary = vocabularyOf(promptedWith("durationElement"));
            assertThat(vocabulary.atleastOneCustodialResult()).isFalse();
            assertThat(vocabulary.allNonCustodialResults()).isTrue();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isTrue();
        }

        @Test
        @DisplayName("report a non-custodial result alongside a custodial one")
        void report_a_non_custodial_result_alongside_a_custodial_one() {
            final RegisterVocabulary vocabulary = vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20",
                    "judicialResultPrompts":[
                     {"promptReference":"prisonOrganisationName"},
                     {"promptReference":"durationElement"}]}]}]}]}""");

            assertThat(vocabulary.atleastOneCustodialResult()).isTrue();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isTrue();
        }
    }

    @Nested
    @DisplayName("the remaining flags")
    class Remaining {

        @Test
        @DisplayName("mark a CPS prosecutor only when the flag is a real boolean true")
        void mark_a_cps_prosecutor_only_for_boolean_true() {
            assertThat(vocabularyOf(prosecutedBy("true")).isCpsProsecuted()).isTrue();
            assertThat(vocabularyOf(prosecutedBy("false")).isCpsProsecuted()).isFalse();
            assertThat(vocabularyOf(prosecutedBy("\"true\"")).isCpsProsecuted()).isFalse();
        }

        @Test
        @DisplayName("mark a youth defendant, and never both youth and adult")
        void mark_a_youth_defendant() {
            final RegisterVocabulary youth = vocabularyOf("""
                {"courtCentre":{"name":"Lavender Hill"},
                 "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","isYouth":true,
                   "offences":[],"defendantCaseJudicialResults":[
                    {"orderedDate":"2020-01-20"}]}]}]}""");

            assertThat(youth.youthDefendant()).isTrue();
            assertThat(youth.adultDefendant()).isFalse();
            assertThat(youth.adultOrYouthDefendant()).isTrue();
        }

        @Test
        @DisplayName("mark a Welsh hearing, and never both Welsh and English")
        void mark_a_welsh_hearing() {
            final RegisterVocabulary welsh = vocabularyOf("""
                {"courtCentre":{"name":"Cardiff","welshCourtCentre":true},
                 "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                  "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                   "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]}]}""");

            assertThat(welsh.welshCourtHearing()).isTrue();
            assertThat(welsh.englishCourtHearing()).isFalse();
            assertThat(welsh.anyCourtHearing()).isTrue();
        }

        @Test
        @DisplayName("leave both major-creditor lists empty, as the two-argument call requires")
        void leave_both_major_creditor_lists_empty() {
            final RegisterVocabulary vocabulary = vocabularyOf(defendantHeldAt("Prison"));
            assertThat(vocabulary.prosecutorMajorCreditor()).isEmpty();
            assertThat(vocabulary.nonProsecutorMajorCreditor()).isEmpty();
        }

        @Test
        @DisplayName("refuse a hearing with no court centre, as the legacy does")
        void refuse_a_hearing_with_no_court_centre() {
            assertThatThrownBy(() -> vocabularyOf("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                  "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]}]}"""))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> assertThat(failure.classification())
                            .isEqualTo(FailureClassification.NON_TRANSIENT));
        }
    }

    /**
     * The JUnit twins of the legacy {@code VocabularyService} Jest suite.
     *
     * <p>Three of that suite's six cases are twinned here. The other three cannot be:
     *
     * <ul>
     *   <li>{@code Should set the correct flag for non prosecutor major creditors} and
     *       {@code Should set the correct flag for prosecutor major creditors} are written against
     *       the <em>four</em>-argument constructor, with a major-creditor map and a compliance
     *       enforcement list. The informant register constructs the service with <em>two</em>
     *       arguments, which is what makes both creditor lists unconditionally empty here — parity,
     *       not an unfinished port, and pinned as such above. Twinning those two would mean building
     *       a creditor branch this flow never enters.</li>
     *   <li>{@code Should set custody location info with both application and prosecution case in
     *       the hearing} gathers its defendant with {@code (hearing, true, false)} — the plain
     *       register mode, which this port does not have — and calls
     *       {@code getCustodyLocationInfo()} directly rather than {@code getVocabularyInfo()}.</li>
     * </ul>
     *
     * <p><strong>How the input is reconstructed, and why it has to be.</strong> Each Jest case pairs
     * a mocked hearing with a <em>separately</em> mocked {@code DefendantContextBase} that the
     * hearing would not produce — the mocked hearing's defendants carry no results at all, while the
     * mocked context carries two. This port has no way to hand a vocabulary a context that did not
     * come from the hearing, so the hearing below carries both halves: the mocked hearing's custody,
     * attendance, court centre and prosecutor, and the two ordered-on-2020-05-11 results and youth
     * flag of the mocked context. Every asserted value is the Jest case's, unaltered; only the way
     * the input is assembled differs, because the legacy's shape is not reproducible here.
     */
    @Nested
    @DisplayName("VocabularyService — legacy Jest twins")
    class LegacyJestTwins {

        @Test
        @DisplayName("Should set the correct police custody")
        void should_set_the_correct_police_custody() {
            assertMockedHearing(vocabularyOf(mockedHearing("Police Station", "false")), true, false);
        }

        @Test
        @DisplayName("Should set the correct prison custody")
        void should_set_the_correct_prison_custody() {
            assertMockedHearing(vocabularyOf(mockedHearing("Prison", "false")), false, true);
        }

        @Test
        @DisplayName("Should set the correct cps flag")
        void should_set_the_correct_cps_flag() {
            final RegisterVocabulary vocabulary = vocabularyOf(mockedHearing("Prison", "true"));

            assertThat(vocabulary.welshCourtHearing()).isTrue();
            assertThat(vocabulary.englishCourtHearing()).isFalse();
            assertThat(vocabulary.anyCourtHearing()).isTrue();
            assertThat(vocabulary.isCpsProsecuted()).isTrue();
        }

        /**
         * The sixteen flags the two custody cases assert, which differ only in where the defendant
         * is held.
         *
         * @param vocabulary the computed vocabulary
         * @param police     the expected {@code custodyLocationIsPolice}
         * @param prison     the expected {@code custodyLocationIsPrison}
         */
        private void assertMockedHearing(
                final RegisterVocabulary vocabulary, final boolean police, final boolean prison) {

            assertThat(vocabulary.custodyLocationIsPolice()).isEqualTo(police);
            assertThat(vocabulary.custodyLocationIsPrison()).isEqualTo(prison);
            assertThat(vocabulary.atleastOneCustodialResult()).isFalse();
            assertThat(vocabulary.appearedInPerson()).isTrue();
            assertThat(vocabulary.appearedByVideoLink()).isFalse();
            assertThat(vocabulary.allNonCustodialResults()).isTrue();
            assertThat(vocabulary.atleastOneNonCustodialResult()).isTrue();
            assertThat(vocabulary.anyAppearance()).isTrue();
            assertThat(vocabulary.inCustody()).isTrue();
            assertThat(vocabulary.youthDefendant()).isTrue();
            assertThat(vocabulary.adultDefendant()).isFalse();
            assertThat(vocabulary.adultOrYouthDefendant()).isTrue();
            assertThat(vocabulary.welshCourtHearing()).isTrue();
            assertThat(vocabulary.englishCourtHearing()).isFalse();
            assertThat(vocabulary.anyCourtHearing()).isTrue();
            assertThat(vocabulary.isCpsProsecuted()).isFalse();
        }

        /**
         * The Jest suite's {@code getMockedHearingResulted}, carrying the results and youth flag its
         * separately-mocked {@code DefendantContextBase} supplies — see the class comment.
         *
         * @param custody the custody location, as {@code LocationTypeEnum} spells it
         * @param isCps   the raw JSON value of the second case's {@code prosecutor.isCps}
         * @return the hearing as JSON text
         */
        private String mockedHearing(final String custody, final String isCps) {
            return """
                {"prosecutionCases":[
                  {"id":"c10e3b71-6a6d-45ef-9b62-34df4d54971a","prosecutionCaseIdentifier":{},
                   "defendants":[{"id":"6647df67-a065-4d07-90ba-a8daa064ecc4",
                    "masterDefendantId":"6647df67-a065-4d07-90ba-a8daa064ecc4","isYouth":true,
                    "personDefendant":{"custodialEstablishment":{"custody":"%s"}},
                    "offences":[],
                    "defendantCaseJudicialResults":[
                     {"orderedDate":"2020-05-11"},{"orderedDate":"2020-05-11"}]}]},
                  {"id":"07e0a2b1-6dfe-4c5f-9f6f-9d5f2d3d7a4c","prosecutionCaseIdentifier":{},
                   "prosecutor":{"prosecutorId":"cf73207f-3ced-488a-82a0-3fba79c2ce81",
                    "prosecutorCode":"TFL","prosecutorName":"TFL12348","isCps":%s},
                   "defendants":[{"id":"6647df67-a065-4d07-90ba-a8daa064ecc4",
                    "masterDefendantId":"6647df67-a065-4d07-90ba-a8daa064ecc4","isYouth":true,
                    "personDefendant":{"custodialEstablishment":{"custody":"%s"}},
                    "offences":[],"defendantCaseJudicialResults":[]}]}],
                 "defendantAttendance":[{
                  "defendantId":"6647df67-a065-4d07-90ba-a8daa064ecc4",
                  "attendanceDays":[{"attendanceType":"IN_PERSON","day":"2020-05-11"}]}],
                 "courtCentre":{"id":"6647df67-a065-4d07-90ba-a8daa064ecd9",
                  "name":"Lavender Hill","welshCourtCentre":true}}"""
                    .formatted(custody, isCps, custody);
        }
    }

    /**
     * A hearing whose single defendant is held at the given location.
     *
     * @param custody the custody location
     * @return the hearing as JSON text
     */
    private static String defendantHeldAt(final String custody) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "personDefendant":{"custodialEstablishment":{"custody":"%s"}},
               "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]}]}"""
                .formatted(custody);
    }

    /**
     * A hearing whose single defendant attended in the given way, on the given day.
     *
     * @param attendanceType the attendance type
     * @param day            the day attended
     * @return the hearing as JSON text
     */
    private static String attended(final String attendanceType, final String day) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]}],
             "defendantAttendance":[{"defendantId":"def-1",
              "attendanceDays":[{"day":"%s","attendanceType":"%s"}]}]}"""
                .formatted(day, attendanceType);
    }

    /**
     * A hearing whose single result carries one prompt with the given reference.
     *
     * @param promptReference the prompt reference
     * @return the hearing as JSON text
     */
    private static String promptedWith(final String promptReference) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20",
                "judicialResultPrompts":[{"promptReference":"%s"}]}]}]}]}"""
                .formatted(promptReference);
    }

    /**
     * A hearing whose prosecutor carries the given {@code isCps} value verbatim.
     *
     * @param isCps the raw JSON value to place in {@code isCps}
     * @return the hearing as JSON text
     */
    private static String prosecutedBy(final String isCps) {
        return """
            {"courtCentre":{"name":"Lavender Hill"},
             "prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
              "prosecutor":{"isCps":%s},
              "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
               "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]}]}"""
                .formatted(isCps);
    }

    /**
     * Gathers a hearing's single defendant and computes their vocabulary.
     *
     * @param hearing the hearing as JSON text
     * @return the vocabulary
     */
    private RegisterVocabulary vocabularyOf(final String hearing) {
        final JsonNode tree = mapper.readTree(hearing);
        final List<DefendantContext> gathered =
                new DefendantContextBuilder(tree, new HearingDates(FROZEN)).build();
        assertThat(gathered).as("these cases are written around a single defendant").hasSize(1);
        return new VocabularyBuilder(tree).build(gathered.get(0));
    }

    /**
     * Computes the vocabulary of one named defendant in a hearing that gathers several.
     *
     * @param masterDefendantId the defendant to compute for
     * @param hearing           the hearing as JSON text
     * @return that defendant's vocabulary
     */
    private RegisterVocabulary vocabularyOf(
            final String masterDefendantId, final String hearing) {

        final JsonNode tree = mapper.readTree(hearing);
        final DefendantContext defendant =
                new DefendantContextBuilder(tree, new HearingDates(FROZEN)).build().stream()
                        .filter(candidate ->
                                masterDefendantId.equals(candidate.masterDefendantId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "the hearing gathered no defendant " + masterDefendantId));
        return new VocabularyBuilder(tree).build(defendant);
    }
}
