package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterOffence;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The JUnit twins of the legacy {@code OffenceMapper} Jest suite.
 *
 * <p>Eight twins from the first describe and a three-row table from the second, in the order the
 * legacy file declares them, built from {@link ModelObjects} exactly as the Jest suite builds them
 * from its own helper.
 *
 * <p>Two of the parity pack's coverage findings live here. BS-05 is the cross-authority filter, whose
 * false leg the legacy suite never executes — the one control that stops one prosecutor's offences
 * reaching another's register. The rest are the unguarded dereferences this mapper makes on its way
 * into a hearing.
 */
@DisplayName("OffenceMapper — parity with the legacy OffenceMapper")
class OffenceMapperTest {

    private static final String AUTHORITY = "31af405e-7b60-4dd8-a244-c24c2d3fa595";
    private static final String MASTER_DEFENDANT = "MASTER_10001";

    /** No case here reads the clock; the fixed value only makes that visible. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private final ResultDataMapper resultDataMapper = new ResultDataMapper(new HearingDates(FROZEN));

    @Nested
    @DisplayName("Offence mapper works correctly")
    class LegacyTwins {

        @Test
        @DisplayName("when defendant has offence then mapper should give offence")
        void one_offence_on_one_case_should_be_mapped_whole() {
            final ObjectNode hearing = hearingWithOneCase("caseURN", null);

            final List<InformantRegisterOffence> offences = build(hearing);

            assertThat(offences).hasSize(1);
            assertThat(offences.getFirst().originatingCaseUrn()).isEqualTo("caseURN");
            assertThat(offences.getFirst().offenceCode()).isEqualTo("Code");
            assertThat(offences.getFirst().orderIndex()).isEqualTo(1001);
            assertThat(offences.getFirst().offenceTitle()).isEqualTo("Title");
            assertThat(offences.getFirst().pleaValue()).isEqualTo("plea");
            assertThat(offences.getFirst().verdict().verdictCode()).isEqualTo("G");
            assertThat(offences.getFirst().verdict().verdictType()).isEqualTo("FOUND_GUILTY");
            assertThat(offences.getFirst().verdict().verdictDate()).isEqualTo("2019-11-14");
        }

        @Test
        @DisplayName("when defendant has multiple offence then mapper should give multiple offence")
        void two_offences_on_one_case_should_share_that_case_reference() {
            final ObjectNode first = unverdictedOffence();
            final ObjectNode second = unverdictedOffence();
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array(first, second));
            defendant.put("masterDefendantId", MASTER_DEFENDANT);
            final ObjectNode prosecutionCase =
                    ModelObjects.prosecutionCase(AUTHORITY, "caseURN123", null);
            prosecutionCase.set("defendants", ModelObjects.array(defendant));
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("prosecutionCases", ModelObjects.array(prosecutionCase));

            final List<InformantRegisterOffence> offences = build(hearing);

            assertThat(offences).hasSize(2);
            assertThat(offences.get(0).originatingCaseUrn()).isEqualTo("caseURN123");
            assertThat(offences.get(1).originatingCaseUrn()).isEqualTo("caseURN123");
        }

        @Test
        @DisplayName("when defendant has multiple offence different case then mapper should give "
                + "multiple offence")
        void offences_on_two_cases_should_each_carry_their_own_case_reference() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("prosecutionCases", ModelObjects.array(
                    caseWithOneOffence("caseURN_first", unverdictedOffence()),
                    caseWithOneOffence("caseURN_second", unverdictedOffence())));

            final List<InformantRegisterOffence> offences = build(hearing);

            assertThat(offences).hasSize(2);
            assertThat(offences.get(0).originatingCaseUrn()).isEqualTo("caseURN_first");
            assertThat(offences.get(1).originatingCaseUrn()).isEqualTo("caseURN_second");
        }

        @Test
        @DisplayName("when there are no prosecution case")
        void a_hearing_with_neither_cases_nor_applications_should_give_no_offences() {
            assertThat(build(ModelObjects.hearing())).isEmpty();
        }

        @Test
        @DisplayName("when courtApplication has courtApplicationCases")
        void an_application_case_offence_should_be_mapped_whole() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications",
                    ModelObjects.array(applicationWithApplicationCases("caseURN")));

            assertOffence(build(hearing), 1);
        }

        @Test
        @DisplayName("when prosecutionCase has offences and courtApplication has "
                + "courtApplicationCases with no offence for linked application")
        void an_application_case_without_offences_should_add_nothing() {
            final ObjectNode hearing = hearingWithOneCase("caseURN", null);
            hearing.set("courtApplications",
                    ModelObjects.array(applicationWithEmptyApplicationCase("caseURN")));

            assertOffence(build(hearing), 1);
        }

        @Test
        @DisplayName("when courtApplication has courtOrder")
        void a_court_order_offence_should_be_mapped_whole() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications",
                    ModelObjects.array(applicationWithCourtOrder("caseURN")));

            assertOffence(build(hearing), 1);
        }

        @Test
        @DisplayName("when both courtApplicationCase and CourtOrder has offences")
        void an_application_with_both_shapes_should_map_both_offences() {
            final ObjectNode application = applicationWithApplicationCases("caseURN");
            application.set("courtOrder",
                    applicationWithCourtOrder("caseURN").get("courtOrder"));
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications", ModelObjects.array(application));

            assertOffence(build(hearing), 2);
        }
    }

    @Nested
    @DisplayName("check originatingCaseURN for different input combinations of caseURN and "
            + "prosecutionAuthorityReference")
    class OriginatingCaseUrnTable {

        /**
         * The legacy {@code jest-each} table, transcribed row for row. The third row is the one that
         * makes the rule a truthiness test rather than a null check: an empty {@code caseURN} falls
         * back to the authority's own reference.
         *
         * @param caseUrn            the case URN on the identifier
         * @param authorityReference the authority reference on the identifier
         * @param expected           the reference the offence should carry
         */
        @ParameterizedTest(name = "caseURN=[{0}] prosecutionAuthorityReference=[{1}] -> [{2}]")
        @CsvSource({
            "caseUrn, paRef, caseUrn",
            "caseUrn, '',    caseUrn",
            "'',      paRef, paRef",
        })
        @DisplayName("when defendant has offence then mapper should give offence")
        void the_case_reference_is_the_urn_when_truthy_and_the_authority_reference_otherwise(
                final String caseUrn, final String authorityReference, final String expected) {

            final List<InformantRegisterOffence> offences =
                    build(hearingWithOneCase(caseUrn, authorityReference));

            assertThat(offences).hasSize(1);
            assertThat(offences.getFirst().originatingCaseUrn()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Pinned oddities — a correction here needs a deviations-register entry first")
    class PinnedOddities {

        /**
         * Parity-pack pinning case {@code s04-case-reference-urn-then-authority-reference}, the
         * offence half. The pack is explicit that the rule is truthiness, not nullness: writing this
         * as {@code Objects.requireNonNullElse} would return the empty string for an empty URN and
         * diverge. The empty-URN row of the table above is the assertion that catches it; this case
         * pins the absent-URN half, which the recorded corpus contains.
         */
        @Test
        @DisplayName("s04 — a case with no URN at all falls back to the authority reference")
        void an_absent_case_urn_falls_back_to_the_authority_reference() {
            final List<InformantRegisterOffence> offences =
                    build(hearingWithOneCase(null, "TVL298320922"));

            assertThat(offences.getFirst().originatingCaseUrn()).isEqualTo("TVL298320922");
        }

        /**
         * The verdict is emitted only when a verdict code is truthy, and the code is read from
         * {@code offence.verdict.verdictType.verdictCode} — a level deeper than the field name
         * suggests. An offence with the empty verdict object the legacy builder starts from carries
         * no verdict at all.
         */
        @Test
        @DisplayName("an offence with no verdict code carries no verdict")
        void an_offence_with_no_verdict_code_carries_no_verdict() {
            final List<InformantRegisterOffence> offences =
                    build(hearingWithOneCase("caseURN", null, ModelObjects.offence(
                            "Code", 1001, "Title")));

            assertThat(offences.getFirst().verdict()).isNull();
        }

        /**
         * Deviations-register entry 9. An unmapped verdict code makes the legacy write
         * {@code verdictType: null} into the outbound body, which the frozen contract types as a
         * string; the typed tree omits the component instead. The verdict itself is still emitted,
         * carrying the code the payload sent — only the derived type differs.
         */
        @Test
        @DisplayName("deviation 9 — an unmapped verdict code yields a verdict with no type")
        void an_unmapped_verdict_code_yields_a_verdict_with_no_type() {
            final List<InformantRegisterOffence> offences =
                    build(hearingWithOneCase("caseURN", null, unverdictedOffence()));

            assertThat(offences.getFirst().verdict().verdictCode()).isEqualTo("verdict-code");
            assertThat(offences.getFirst().verdict().verdictType()).isNull();
        }
    }

    @Nested
    @DisplayName("Branches the legacy suite never executes (parity-pack BS-05)")
    class UncoveredBranches {

        /**
         * BS-05: the false leg of the authority filter at {@code OffenceMapper.js:17} records zero
         * executions across the whole legacy suite, and it is the only control scoping offences to
         * the authority whose register is being built. A hearing carrying two prosecutors must not
         * leak one's offences into the other's document.
         */
        @Test
        @DisplayName("BS-05 — another prosecutor's case contributes no offences")
        void a_case_belonging_to_another_authority_should_contribute_nothing() {
            final ObjectNode otherAuthorityCase = ModelObjects.prosecutionCase(
                    "a-different-authority", "OTHER-URN", null);
            final ObjectNode defendant =
                    ModelObjects.defendant(ModelObjects.array(exampleOffence()));
            defendant.put("masterDefendantId", MASTER_DEFENDANT);
            otherAuthorityCase.set("defendants", ModelObjects.array(defendant));

            final ObjectNode hearing = hearingWithOneCase("caseURN", null);
            hearing.withArray("prosecutionCases").add(otherAuthorityCase);

            final List<InformantRegisterOffence> offences = build(hearing);

            assertThat(offences).hasSize(1);
            assertThat(offences.getFirst().originatingCaseUrn()).isEqualTo("caseURN");
        }

        /**
         * BS-05's sibling on the same filter: a case belonging to the right authority but carrying a
         * different defendant. The offence list is per defendant as well as per authority.
         */
        @Test
        @DisplayName("BS-05 — another defendant on the same authority contributes no offences")
        void a_case_carrying_another_defendant_should_contribute_nothing() {
            final ObjectNode prosecutionCase =
                    ModelObjects.prosecutionCase(AUTHORITY, "caseURN", null);
            final ObjectNode defendant =
                    ModelObjects.defendant(ModelObjects.array(exampleOffence()));
            defendant.put("masterDefendantId", "MASTER_20002");
            prosecutionCase.set("defendants", ModelObjects.array(defendant));
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("prosecutionCases", ModelObjects.array(prosecutionCase));

            assertThat(build(hearing)).isEmpty();
        }

        /**
         * The application filter needs all four of its conjuncts. An application whose applicant is
         * another authority contributes nothing, which the legacy suite does cover on this branch —
         * asserted here so the port's two filters are held to the same standard.
         */
        @Test
        @DisplayName("an application belonging to another authority contributes no offences")
        void an_application_belonging_to_another_authority_should_contribute_nothing() {
            final ObjectNode application = applicationWithApplicationCases("caseURN");
            ((ObjectNode) application.get("applicant").get("prosecutingAuthority"))
                    .put("prosecutionAuthorityId", "a-different-authority");
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications", ModelObjects.array(application));

            assertThat(build(hearing)).isEmpty();
        }

        /**
         * {@code prosecutionCase.defendants.filter(...)} is dereferenced with no guard
         * (`OffenceMapper.js:18`), so a case without the field kills the hearing in the legacy.
         * Refused here rather than read as "no defendants", under deviations-register entry 7.
         */
        @Test
        @DisplayName("a case with no defendants is refused, not read as empty")
        void a_case_without_defendants_should_refuse() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("prosecutionCases",
                    ModelObjects.array(ModelObjects.prosecutionCase(AUTHORITY, "caseURN", null)));

            assertThatThrownBy(() -> build(hearing))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * {@code courtApplication.applicant.prosecutingAuthority} is dereferenced with no guard on
         * {@code applicant} (`OffenceMapper.js:31`) and {@code courtApplication.subject} likewise at
         * {@code :33}. Both are refusals here for the same reason.
         */
        @Test
        @DisplayName("an application with no applicant is refused, not skipped")
        void an_application_without_an_applicant_should_refuse() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications", ModelObjects.array(
                    ModelObjects.courtApplication(
                            ModelObjects.subject(ModelObjects.masterDefendant()), null)));

            assertThatThrownBy(() -> build(hearing))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * {@code prosecutionCase.prosecutionCaseIdentifier.prosecutionAuthorityId}
         * (`OffenceMapper.js:17`) is read off every case in the hearing before any filtering, so a
         * case with no identifier ends the hearing there — even one belonging to another authority
         * entirely. Reading it as "does not match" would emit a register the legacy never sent.
         */
        @Test
        @DisplayName("a case with no identifier is refused, not read as another authority's")
        void a_case_without_an_identifier_should_refuse() {
            final ObjectNode hearing = hearingWithOneCase("caseURN", null);
            hearing.withArray("prosecutionCases").add(hearing.objectNode());

            assertThatThrownBy(() -> build(hearing))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * {@code derivedOffence.offenceCode = offence.offenceCode} (`OffenceMapper.js:62`) is read
         * straight off the array member, so a null offence is a {@code TypeError}. Emitting an
         * offence with every field absent instead would put a row on a real register that the
         * legacy never produced — the opposite direction from entry 7's usual shape, and the one
         * that reaches an authority.
         */
        @Test
        @DisplayName("a null offence is refused, not mapped to an offence with nothing in it")
        void a_null_offence_should_refuse() {
            final ObjectNode hearing = ModelObjects.hearing();
            final ObjectNode prosecutionCase =
                    ModelObjects.prosecutionCase(AUTHORITY, "caseURN", null);
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
            defendant.put("masterDefendantId", MASTER_DEFENDANT);
            defendant.withArray("offences").addNull();
            prosecutionCase.set("defendants", ModelObjects.array(defendant));
            hearing.set("prosecutionCases", ModelObjects.array(prosecutionCase));

            assertThatThrownBy(() -> build(hearing))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    @Nested
    @DisplayName("orderIndex — carried only when it is one")
    class OrderIndex {

        /**
         * Deviation 11. The legacy copies {@code offence.orderIndex} across untouched
         * (`OffenceMapper.js:63`) and the contract types the component {@code integer}, so
         * {@code 1.5} is a body the consumer rejects — no register either way. Truncating it to
         * {@code 1} would be the third answer: a body that passes validation carrying an index the
         * payload never sent.
         */
        @Test
        @DisplayName("deviation 11 — a fractional index is absent, never truncated")
        void a_fractional_order_index_is_absent_rather_than_truncated() {
            final ObjectNode offence = exampleOffence();
            offence.put("orderIndex", new BigDecimal("1.5"));

            final List<InformantRegisterOffence> offences =
                    build(hearingWithOneCase("caseURN", null, offence));

            assertThat(offences).hasSize(1);
            assertThat(offences.getFirst().orderIndex()).isNull();
        }

        /**
         * The same rule at the other end: a value past {@link Integer#MAX_VALUE} would come back as
         * whatever the low thirty-two bits held.
         */
        @Test
        @DisplayName("deviation 11 — an index too large for an int is absent, never wrapped")
        void an_oversized_order_index_is_absent_rather_than_wrapped() {
            final ObjectNode offence = exampleOffence();
            offence.put("orderIndex", 4_294_967_297L);

            final List<InformantRegisterOffence> offences =
                    build(hearingWithOneCase("caseURN", null, offence));

            assertThat(offences).hasSize(1);
            assertThat(offences.getFirst().orderIndex()).isNull();
        }
    }

    /**
     * Runs the mapper over one hearing, for this suite's authority and defendant.
     *
     * @param hearing the hearing tree
     * @return the mapped offences
     */
    private List<InformantRegisterOffence> build(final ObjectNode hearing) {
        final RegisterDefendant defendant = ModelObjects.defendantContextBase(MASTER_DEFENDANT);
        final RegisterFragment fragment = ModelObjects.fragment(AUTHORITY, defendant);
        return new OffenceMapper(
                hearing, fragment, defendant, new ResultMapper(defendant, resultDataMapper))
                .build();
    }

    /**
     * Asserts the offence the legacy suite's own {@code verifyOffences} helper asserts.
     *
     * @param offences the mapped offences
     * @param count    the number of offences expected
     */
    private static void assertOffence(
            final List<InformantRegisterOffence> offences, final int count) {
        assertThat(offences).hasSize(count);
        assertThat(offences.getFirst().originatingCaseUrn()).isEqualTo("caseURN");
        assertThat(offences.getFirst().offenceCode()).isEqualTo("Code");
        assertThat(offences.getFirst().orderIndex()).isEqualTo(1001);
        assertThat(offences.getFirst().offenceTitle()).isEqualTo("Title");
        assertThat(offences.getFirst().pleaValue()).isEqualTo("plea");
        assertThat(offences.getFirst().verdict().verdictCode()).isEqualTo("G");
        assertThat(offences.getFirst().verdict().verdictType()).isEqualTo("FOUND_GUILTY");
        assertThat(offences.getFirst().verdict().verdictDate()).isEqualTo("2019-11-14");
    }

    /**
     * The legacy suite's {@code getAnExampleOffence} — a pleaded, guilty-verdicted offence.
     *
     * @return the offence tree
     */
    private static ObjectNode exampleOffence() {
        final ObjectNode offence = ModelObjects.offence("Code", 1001, "Title");
        ((ObjectNode) offence.get("plea")).put("pleaValue", "plea");
        final ObjectNode verdict = (ObjectNode) offence.get("verdict");
        verdict.put("verdictDate", "2019-11-14");
        verdict.set("verdictType", verdict.objectNode()
                .put("verdictCode", "G").put("description", "verdict-desc"));
        return offence;
    }

    /**
     * The offence two of the legacy cases build inline, whose verdict code maps to nothing.
     *
     * @return the offence tree
     */
    private static ObjectNode unverdictedOffence() {
        final ObjectNode offence = ModelObjects.offence("Code", 1001, "Title");
        ((ObjectNode) offence.get("plea")).put("pleaValue", "plea");
        final ObjectNode verdict = (ObjectNode) offence.get("verdict");
        verdict.set("verdictType", verdict.objectNode().put("verdictCode", "verdict-code"));
        return offence;
    }

    /**
     * The legacy suite's {@code setUpOffenseData} — one case, one defendant, one example offence.
     *
     * @param caseUrn            the case URN; {@code null} to leave it undefined
     * @param authorityReference the authority reference; {@code null} to leave it undefined
     * @return the hearing tree
     */
    private static ObjectNode hearingWithOneCase(
            final String caseUrn, final String authorityReference) {
        return hearingWithOneCase(caseUrn, authorityReference, exampleOffence());
    }

    /**
     * The same hearing, with a caller-chosen offence.
     *
     * @param caseUrn            the case URN; {@code null} to leave it undefined
     * @param authorityReference the authority reference; {@code null} to leave it undefined
     * @param offence            the offence the defendant carries
     * @return the hearing tree
     */
    private static ObjectNode hearingWithOneCase(
            final String caseUrn, final String authorityReference, final ObjectNode offence) {
        final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array(offence));
        defendant.put("masterDefendantId", MASTER_DEFENDANT);
        final ObjectNode prosecutionCase =
                ModelObjects.prosecutionCase(AUTHORITY, caseUrn, authorityReference);
        prosecutionCase.set("defendants", ModelObjects.array(defendant));
        final ObjectNode hearing = ModelObjects.hearing();
        hearing.set("prosecutionCases", ModelObjects.array(prosecutionCase));
        return hearing;
    }

    /**
     * One prosecution case carrying one defendant with one offence.
     *
     * @param caseUrn the case URN
     * @param offence the offence
     * @return the prosecution-case tree
     */
    private static ObjectNode caseWithOneOffence(final String caseUrn, final ObjectNode offence) {
        final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array(offence));
        defendant.put("masterDefendantId", MASTER_DEFENDANT);
        final ObjectNode prosecutionCase =
                ModelObjects.prosecutionCase(AUTHORITY, caseUrn, null);
        prosecutionCase.set("defendants", ModelObjects.array(defendant));
        return prosecutionCase;
    }

    /**
     * The legacy suite's {@code setupCourtApplicationWithCourtApplicationCases}.
     *
     * @param caseUrn the case URN on the application case
     * @return the court-application tree
     */
    private static ObjectNode applicationWithApplicationCases(final String caseUrn) {
        final ObjectNode application = subjectApplication();
        application.set("courtApplicationCases", ModelObjects.array(
                ModelObjects.courtApplicationCase(
                        ModelObjects.array(exampleOffence()), caseUrn, null)));
        return application;
    }

    /**
     * The legacy suite's
     * {@code setupCourtApplicationWithCourtApplicationCasesForLinkedApplication} — an application
     * case with no offences at all.
     *
     * @param caseUrn the case URN on the application case
     * @return the court-application tree
     */
    private static ObjectNode applicationWithEmptyApplicationCase(final String caseUrn) {
        final ObjectNode application = subjectApplication();
        application.set("courtApplicationCases", ModelObjects.array(
                ModelObjects.courtApplicationCase(null, caseUrn, null)));
        return application;
    }

    /**
     * The legacy suite's {@code setupCourtApplicationWithCourtOrder}.
     *
     * @param caseUrn the case URN on the court-order offence
     * @return the court-application tree
     */
    private static ObjectNode applicationWithCourtOrder(final String caseUrn) {
        final ObjectNode application = subjectApplication();
        application.set("courtOrder", ModelObjects.courtOrder(
                ModelObjects.courtOrderOffence(exampleOffence(), caseUrn, null)));
        return application;
    }

    /**
     * A court application for this suite's authority and master defendant.
     *
     * @return the court-application tree
     */
    private static ObjectNode subjectApplication() {
        final ObjectNode masterDefendant = ModelObjects.masterDefendant();
        masterDefendant.put("masterDefendantId", MASTER_DEFENDANT);
        return ModelObjects.courtApplication(
                ModelObjects.subject(masterDefendant),
                ModelObjects.applicant(ModelObjects.prosecutingAuthority(AUTHORITY, null)));
    }
}
