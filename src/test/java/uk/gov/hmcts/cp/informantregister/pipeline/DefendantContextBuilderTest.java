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
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.ResultLevel;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The four passes that gather a hearing's judicial results under their defendants.
 *
 * <p>These cases exist because the inherited fixtures reach only part of this logic. The legacy Jest
 * suite for {@code SetInformantRegister} uses hearings built almost entirely from prosecution cases
 * with offence-level results, so whole passes — defendant-case results, application court orders,
 * hearing-level defendant results — are never entered by the golden suite at all. Every expectation
 * below is read from {@code NowsHelper/service/DefendantContextBaseService.js}, not invented, and
 * several of them pin behaviour that only looks arbitrary until you read that source:
 *
 * <ul>
 *   <li>Results reached through an application's cases and court orders are tagged
 *       {@code OFFENCE}, not {@code APPLICATION}, because this flow runs with {@code isRegister}
 *       true.</li>
 *   <li>The offence title is written unconditionally at offence level — so an offence with no title
 *       <em>removes</em> a title the result already carried — but conditionally at case level.</li>
 *   <li>A deleted result is skipped everywhere, at all four levels.</li>
 * </ul>
 */
@DisplayName("DefendantContextBuilder")
class DefendantContextBuilderTest {

    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("results recorded against a defendant's case")
    class CaseLevel {

        @Test
        @DisplayName("are tagged at case level and carry the case and defendant they belong to")
        void are_tagged_at_case_level() {
            final RegisterResult result = onlyResult("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                  "defendantCaseJudicialResults":[{"orderedDate":"2020-01-20"}]}]}]}""");

            assertThat(result.level()).isEqualTo(ResultLevel.CASE);
            assertThat(result.prosecutionCaseId()).isEqualTo("case-1");
            assertThat(result.defendantId()).isEqualTo("def-1");
            assertThat(result.masterDefendantId()).isEqualTo("master-1");
            assertThat(result.judicialResult().get("level").stringValue()).isEqualTo("C");
            assertThat(result.judicialResult().get("prosecutionCaseId").stringValue())
                    .isEqualTo("case-1");
        }

        @Test
        @DisplayName("borrow the title of the offence they name")
        void borrow_the_title_of_the_offence_they_name() {
            final RegisterResult result = onlyResult("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "offences":[{"id":"off-1","offenceTitle":"Theft"}],
                  "defendantCaseJudicialResults":[
                   {"orderedDate":"2020-01-20","offenceId":"off-1"}]}]}]}""");

            assertThat(result.offenceId()).isEqualTo("off-1");
            assertThat(result.judicialResult().get("offenceTitle").stringValue())
                    .isEqualTo("Theft");
        }

        @Test
        @DisplayName("get no title when the offence they name has none")
        void get_no_title_when_the_offence_has_none() {
            final RegisterResult result = onlyResult("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "offences":[{"id":"off-1"}],
                  "defendantCaseJudicialResults":[
                   {"orderedDate":"2020-01-20","offenceId":"off-1"}]}]}]}""");

            assertThat(result.judicialResult().has("offenceTitle")).isFalse();
        }

        @Test
        @DisplayName("get no title when they name an offence the defendant does not have")
        void get_no_title_when_the_offence_is_not_the_defendants() {
            final RegisterResult result = onlyResult("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "offences":[{"id":"other","offenceTitle":"Theft"}],
                  "defendantCaseJudicialResults":[
                   {"orderedDate":"2020-01-20","offenceId":"off-1"}]}]}]}""");

            assertThat(result.judicialResult().has("offenceTitle")).isFalse();
        }

        @Test
        @DisplayName("are skipped when deleted")
        void are_skipped_when_deleted() {
            assertThat(results("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                  "defendantCaseJudicialResults":[
                   {"orderedDate":"2020-01-20","isDeleted":true}]}]}]}""")).isEmpty();
        }
    }

    @Nested
    @DisplayName("results recorded against an offence")
    class OffenceLevel {

        @Test
        @DisplayName("are tagged at offence level and take the offence's title")
        void are_tagged_at_offence_level() {
            final RegisterResult result = onlyResult("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "defendantCaseJudicialResults":[],
                  "offences":[{"id":"off-1","offenceTitle":"Theft",
                   "judicialResults":[{"orderedDate":"2020-01-20"}]}]}]}]}""");

            assertThat(result.level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(result.offenceId()).isEqualTo("off-1");
            assertThat(result.judicialResult().get("level").stringValue()).isEqualTo("O");
            assertThat(result.judicialResult().get("offenceTitle").stringValue())
                    .isEqualTo("Theft");
        }

        @Test
        @DisplayName("lose a title they already carried when the offence has none")
        void lose_a_title_they_already_carried_when_the_offence_has_none() {
            // The legacy assigns the offence's title unconditionally here, and assigning undefined
            // deletes the property as far as the output is concerned. Writing a null instead would
            // leave a field the legacy never emits.
            final RegisterResult result = onlyResult("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "defendantCaseJudicialResults":[],
                  "offences":[{"id":"off-1",
                   "judicialResults":[
                    {"orderedDate":"2020-01-20","offenceTitle":"stale"}]}]}]}]}""");

            assertThat(result.judicialResult().has("offenceTitle")).isFalse();
        }

        @Test
        @DisplayName("are skipped when deleted")
        void are_skipped_when_deleted() {
            assertThat(results("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "defendantCaseJudicialResults":[],
                  "offences":[{"id":"off-1","judicialResults":[
                   {"orderedDate":"2020-01-20","isDeleted":true}]}]}]}]}""")).isEmpty();
        }
    }

    @Nested
    @DisplayName("results reached through a court application")
    class ApplicationLevel {

        @Test
        @DisplayName("recorded on the application are tagged at application level")
        void recorded_on_the_application_are_tagged_at_application_level() {
            final RegisterResult result = onlyResult("""
                {"courtApplications":[{"id":"app-1",
                 "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                 "subject":{"masterDefendant":{"masterDefendantId":"master-1"}},
                 "judicialResults":[{"orderedDate":"2020-01-20"}]}]}""");

            assertThat(result.level()).isEqualTo(ResultLevel.APPLICATION);
            assertThat(result.applicationId()).isEqualTo("app-1");
            assertThat(result.isApplicant()).isTrue();
            assertThat(result.includeInNcesResult()).isTrue();
        }

        @Test
        @DisplayName("recorded on a linked case's offence are tagged at offence level, not application")
        void recorded_on_a_linked_case_offence_are_tagged_at_offence_level() {
            // isRegister is true for this flow, which is what chooses OFFENCE over APPLICATION.
            final RegisterResult result = onlyResult("""
                {"courtApplications":[{"id":"app-1",
                 "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                 "subject":{"masterDefendant":{"masterDefendantId":"master-1"}},
                 "courtApplicationCases":[{"prosecutionCaseId":"case-9",
                  "offences":[{"id":"off-1","offenceTitle":"Theft",
                   "judicialResults":[{"orderedDate":"2020-01-20"}]}]}]}]}""");

            assertThat(result.level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(result.offenceId()).isEqualTo("off-1");
            assertThat(result.applicationId()).isEqualTo("app-1");
            assertThat(result.isApplicant()).isTrue();
            assertThat(result.judicialResult().get("offenceTitle").stringValue())
                    .isEqualTo("Theft");
        }

        @Test
        @DisplayName("recorded on a court order's offence are tagged at offence level")
        void recorded_on_a_court_order_offence_are_tagged_at_offence_level() {
            final RegisterResult result = onlyResult("""
                {"courtApplications":[{"id":"app-1",
                 "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                 "subject":{"masterDefendant":{"masterDefendantId":"master-1"}},
                 "courtOrder":{"courtOrderOffences":[{"prosecutionCaseId":"case-9",
                  "offence":{"id":"off-2",
                   "judicialResults":[{"orderedDate":"2020-01-20"}]}}]}}]}""");

            assertThat(result.level()).isEqualTo(ResultLevel.OFFENCE);
            assertThat(result.offenceId()).isEqualTo("off-2");
            assertThat(result.judicialResult().get("offenceId").stringValue()).isEqualTo("off-2");
        }

        @Test
        @DisplayName("are skipped when deleted, at every application level")
        void are_skipped_when_deleted() {
            assertThat(results("""
                {"courtApplications":[{"id":"app-1",
                 "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                 "subject":{"masterDefendant":{"masterDefendantId":"master-1"}},
                 "judicialResults":[{"orderedDate":"2020-01-20","isDeleted":true}],
                 "courtApplicationCases":[{"prosecutionCaseId":"case-9","offences":[
                  {"id":"off-1","judicialResults":[
                   {"orderedDate":"2020-01-20","isDeleted":true}]}]}],
                 "courtOrder":{"courtOrderOffences":[{"prosecutionCaseId":"case-8",
                  "offence":{"id":"off-2","judicialResults":[
                   {"orderedDate":"2020-01-20","isDeleted":true}]}}]}}]}""")).isEmpty();
        }

        @Test
        @DisplayName("bring the application's linked cases with them, without duplicates")
        void bring_the_applications_linked_cases_without_duplicates() {
            final List<DefendantContext> gathered = build("""
                {"courtApplications":[{"id":"app-1",
                 "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                 "subject":{"masterDefendant":{"masterDefendantId":"master-1"}},
                 "courtApplicationCases":[{"prosecutionCaseId":"case-9"},
                                          {"prosecutionCaseId":"case-9"}],
                 "courtOrder":{"courtOrderOffences":[{"prosecutionCaseId":"case-9"},
                                                     {"prosecutionCaseId":"case-8"}]}}]}""");

            assertThat(gathered).hasSize(1);
            assertThat(gathered.get(0).cases()).containsExactly("case-9", "case-8");
            assertThat(gathered.get(0).applications()).containsExactly("app-1");
        }

        @Test
        @DisplayName("are ignored entirely when the application has no prosecuting applicant")
        void are_ignored_when_there_is_no_prosecuting_applicant() {
            assertThat(build("""
                {"courtApplications":[{"id":"app-1","applicant":{},
                 "subject":{"masterDefendant":{"masterDefendantId":"master-1"}},
                 "judicialResults":[{"orderedDate":"2020-01-20"}]}]}""")).isEmpty();
        }

        @Test
        @DisplayName("are ignored entirely when the subject has no master defendant")
        void are_ignored_when_the_subject_has_no_master_defendant() {
            assertThat(build("""
                {"courtApplications":[{"id":"app-1",
                 "applicant":{"prosecutingAuthority":{"prosecutionAuthorityId":"auth-1"}},
                 "subject":{"id":"subject-1"},
                 "judicialResults":[{"orderedDate":"2020-01-20"}]}]}""")).isEmpty();
        }
    }

    @Nested
    @DisplayName("results recorded against the defendant across their cases")
    class DefendantLevel {

        @Test
        @DisplayName("are tagged at defendant level and joined to the gathered defendant")
        void are_tagged_at_defendant_level() {
            final List<RegisterResult> gathered = results("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                  "defendantCaseJudicialResults":[]}]}],
                 "defendantJudicialResults":[{"masterDefendantId":"master-1",
                  "judicialResult":{"orderedDate":"2020-01-20"}}]}""");

            assertThat(gathered).hasSize(1);
            assertThat(gathered.get(0).level()).isEqualTo(ResultLevel.DEFENDANT);
            assertThat(gathered.get(0).judicialResult().get("level").stringValue())
                    .isEqualTo("D");
        }

        @Test
        @DisplayName("are skipped when deleted, leaving the defendant with none")
        void are_skipped_when_deleted() {
            assertThat(results("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                  "defendantCaseJudicialResults":[]}]}],
                 "defendantJudicialResults":[{"masterDefendantId":"master-1",
                  "judicialResult":{"orderedDate":"2020-01-20","isDeleted":true}}]}""")).isEmpty();
        }

        @Test
        @DisplayName("fail the hearing when they name a defendant that appears nowhere else")
        void fail_the_hearing_when_the_defendant_appears_nowhere_else() {
            // The legacy dereferences the missing context and throws, and the activity swallows it
            // so the hearing silently produces nothing. Here the failure is classified at the throw
            // site as non-transient and propagates out of the transformation carrying that
            // classification — no redelivery can turn this payload into a register, so none is spent
            // on it. Ending the swallow is the one thing this port is sanctioned to change
            // (deviations-register entry 2).
            assertThatThrownBy(() -> build("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                  "defendantCaseJudicialResults":[]}]}],
                 "defendantJudicialResults":[{"masterDefendantId":"a-stranger",
                  "judicialResult":{"orderedDate":"2020-01-20"}}]}"""))
                    .asInstanceOf(throwable(TransformationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.reason()).isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                    });
        }

        @Test
        @DisplayName("never quote the identity they could not place")
        void never_quote_the_identity_they_could_not_place() {
            assertThatThrownBy(() -> build("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","offences":[],
                  "defendantCaseJudicialResults":[]}]}],
                 "defendantJudicialResults":[{"masterDefendantId":"a-stranger",
                  "judicialResult":{"orderedDate":"2020-01-20"}}]}"""))
                    .hasMessageNotContaining("a-stranger");
        }
    }

    @Nested
    @DisplayName("the gathered defendant")
    class Gathered {

        @Test
        @DisplayName("takes the latest ordered date across all of its results")
        void takes_the_latest_ordered_date() {
            final List<DefendantContext> gathered = build("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "defendantCaseJudicialResults":[],
                  "offences":[{"id":"off-1","judicialResults":[
                   {"orderedDate":"2020-01-20"},
                   {"orderedDate":"2021-11-02"},
                   {"orderedDate":"2020-06-30"}]}]}]}]}""");

            assertThat(gathered.get(0).freeze().orderedDate()).isEqualTo("2021-11-02");
        }

        @Test
        @DisplayName("is dropped when it never acquired a master defendant identity")
        void is_dropped_without_a_master_defendant_identity() {
            assertThat(build("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","offences":[],
                  "defendantCaseJudicialResults":[]}]}]}""")).isEmpty();
        }

        @Test
        @DisplayName("records a youth flag when the payload carries one")
        void records_a_youth_flag_when_present() {
            assertThat(build("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1","isYouth":true,
                  "offences":[],"defendantCaseJudicialResults":[]}]}]}""")
                    .get(0).freeze().isYouthDefendant()).isTrue();
        }

        @Test
        @DisplayName("leaves the youth flag absent when the payload omits it")
        void leaves_the_youth_flag_absent_when_omitted() {
            assertThat(build("""
                {"prosecutionCases":[{"id":"case-1","prosecutionCaseIdentifier":{},
                 "defendants":[{"id":"def-1","masterDefendantId":"master-1",
                  "offences":[],"defendantCaseJudicialResults":[]}]}]}""")
                    .get(0).freeze().isYouthDefendant()).isNull();
        }
    }

    /**
     * Gathers the defendant contexts of a hearing given as JSON text.
     *
     * @param hearing the hearing as JSON text
     * @return the gathered contexts
     */
    private List<DefendantContext> build(final String hearing) {
        final JsonNode tree = mapper.readTree(hearing);
        return new DefendantContextBuilder(tree, new HearingDates(FROZEN)).build();
    }

    /**
     * The results of the single defendant the hearing is expected to gather.
     *
     * @param hearing the hearing as JSON text
     * @return that defendant's results
     */
    private List<RegisterResult> results(final String hearing) {
        final List<DefendantContext> gathered = build(hearing);
        assertThat(gathered).as("these cases are written around a single defendant").hasSize(1);
        return gathered.get(0).results();
    }

    /**
     * The single result the hearing is expected to gather.
     *
     * @param hearing the hearing as JSON text
     * @return that result
     */
    private RegisterResult onlyResult(final String hearing) {
        final List<RegisterResult> gathered = results(hearing);
        assertThat(gathered).hasSize(1);
        return gathered.get(0);
    }
}
