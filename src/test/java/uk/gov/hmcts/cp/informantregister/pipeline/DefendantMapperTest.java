package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The JUnit twins of the legacy {@code DefendantMapper} Jest suite.
 *
 * <p>Ten twins, one per Jest case, in the order the legacy file declares them. The last four call
 * {@code getDefendants} directly, as the Jest cases do, so the gathering half is asserted separately
 * from the mapping half.
 *
 * <p>Every one of the ten legacy cases uses a person defendant. The organisation defendants that
 * appear on real registers have no assertion anywhere in the legacy repository — parity-pack BS-06,
 * a whole defendant class with zero coverage — and are covered here instead.
 *
 * <p><strong>One difference in how the inputs are built, and it is deliberate.</strong> The Jest
 * suite mocks the offence and result mappers away, so its hearings can leave out anything only those
 * two read — every defendant it builds has no {@code offences} field at all. Unmocked, the legacy
 * dies on exactly that: the offence mapper concatenates the missing list and reads a property off
 * {@code undefined}. Rather than mock the collaborators back out and inherit an input the real flow
 * would refuse, each defendant here carries an empty offence list, which is what a hearing that
 * genuinely has no offences sends. The assertions are the Jest ones, unchanged; the refusal itself
 * has its own case below.
 */
@DisplayName("DefendantMapper — parity with the legacy DefendantMapper")
class DefendantMapperTest {

    private static final String AUTHORITY = "31af405e-7b60-4dd8-a244-c24c2d3fa595";

    /** No case here reads the clock; the fixed value only makes that visible. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private final ResultDataMapper resultDataMapper = new ResultDataMapper(new HearingDates(FROZEN));

    @Nested
    @DisplayName("Defendant mapper works correctly")
    class LegacyTwins {

        @Test
        @DisplayName("when all information given should populate the personal details of defendant")
        void a_fully_populated_person_should_map_every_personal_component() {
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
            defendant.put("masterDefendantId", "MASTER_10001");
            defendant.set("personDefendant", personDetails());

            final ObjectNode prosecutionCase =
                    ModelObjects.prosecutionCase(AUTHORITY, "pCaseURN", null);
            prosecutionCase.put("id", "caseId1");
            prosecutionCase.set("defendants", ModelObjects.array(defendant));
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("prosecutionCases", ModelObjects.array(prosecutionCase));

            final List<InformantRegisterDefendant> defendants = build(hearing,
                    ModelObjects.registerDefendant(
                            "MASTER_10001", List.of("caseId1"), null, List.of()));

            assertThat(defendants).hasSize(1);
            assertThat(defendants.getFirst().name()).isEqualTo("First Middle Last");
            assertThat(defendants.getFirst().dateOfBirth()).isEqualTo("01/01/1900");
            assertThat(defendants.getFirst().nationality()).isEqualTo("GB");
            assertThat(defendants.getFirst().address1()).isEqualTo("A-1");
            assertThat(defendants.getFirst().address2()).isEqualTo("A-2");
            assertThat(defendants.getFirst().address3()).isEqualTo("A-3");
            assertThat(defendants.getFirst().address4()).isEqualTo("A-4");
            assertThat(defendants.getFirst().address5()).isEqualTo("A-5");
            assertThat(defendants.getFirst().firstName()).isEqualTo("First");
            assertThat(defendants.getFirst().lastName()).isEqualTo("Last");
            assertThat(defendants.getFirst().title()).isEqualTo("title");
            assertThat(defendants.getFirst().postCode()).isEqualTo("PostCode");
            assertThat(defendants.getFirst().prosecutionCasesOrApplications()).hasSize(1);
            assertThat(defendants.getFirst().prosecutionCasesOrApplications().getFirst()
                    .caseOrApplicationReference()).isEqualTo("pCaseURN");
        }

        @Test
        @DisplayName("when the case have two defendant then mapper return two defendant")
        void two_defendants_on_one_case_should_map_to_two_entries() {
            final ObjectNode hearing = hearingWith(caseWith(AUTHORITY,
                    namedDefendant("MASTER_10001", "Luis"),
                    namedDefendant("MASTER_10002", "Anna")));

            final List<InformantRegisterDefendant> defendants = build(hearing,
                    ModelObjects.defendantContextBase("MASTER_10001"),
                    ModelObjects.defendantContextBase("MASTER_10002"));

            assertThat(defendants).hasSize(2);
            assertThat(defendants.get(0).name()).isEqualTo("Luis");
            assertThat(defendants.get(1).name()).isEqualTo("Anna");
            assertThat(defendants.get(0).firstName()).isEqualTo("Luis");
            assertThat(defendants.get(1).firstName()).isEqualTo("Anna");
        }

        @Test
        @DisplayName("when multiple cases_same_prosecutionAuthId have one defendant for each then "
                + "mapper return two defendant")
        void one_defendant_on_each_of_two_cases_should_map_to_two_entries() {
            final ObjectNode hearing = hearingWith(
                    caseWith(AUTHORITY, namedDefendant("MASTER_10001", "Luis")),
                    caseWith(AUTHORITY, namedDefendant("MASTER_10002", "Anna")));

            assertThat(build(hearing,
                    ModelObjects.registerDefendant("MASTER_10001", null, null, List.of()),
                    ModelObjects.registerDefendant("MASTER_10002", null, null, List.of())))
                    .hasSize(2);
        }

        @Test
        @DisplayName("when multiple cases_same_prosecutionAuthId have same defendant then mapper "
                + "return one defendant")
        void the_same_defendant_on_two_cases_should_map_to_one_entry() {
            final ObjectNode hearing = hearingWith(
                    caseWith(AUTHORITY, namedDefendant("MASTER_10001", "Luis")),
                    caseWith(AUTHORITY, namedDefendant("MASTER_10001", "Luis")));

            assertThat(build(hearing,
                    ModelObjects.registerDefendant("MASTER_10001", null, null, List.of())))
                    .hasSize(1);
        }

        @Test
        @DisplayName("when multiple cases_NOT_same_prosecutionAuthId then mapper return defendants "
                + "from the same prosecutionAuthId only")
        void a_defendant_reached_through_two_authorities_still_maps_once() {
            final ObjectNode hearing = hearingWith(
                    caseWith(AUTHORITY, namedDefendant("MASTER_10001", "Luis")),
                    caseWith("31af405e-7b60-4dd8-a244-different",
                            namedDefendant("MASTER_10001", "Luis")));

            assertThat(build(hearing,
                    ModelObjects.registerDefendant("MASTER_10001", null, null, List.of())))
                    .hasSize(1);
        }

        @Test
        @DisplayName("when multiple cases_same_prosecutionAuthId have same defendant for each then "
                + "defendant will have two arrestSummonsNumber")
        void the_same_defendant_with_two_arrest_summons_numbers_still_maps_once() {
            final ObjectNode hearing = hearingWith(
                    caseWith(AUTHORITY, defendantWithAsn("MASTER_10001", "ASN_0001")),
                    caseWith(AUTHORITY, defendantWithAsn("MASTER_10001", "ASN_0002")));

            assertThat(build(hearing, ModelObjects.defendantContextBase("MASTER_10001")))
                    .hasSize(1);
        }

        @Test
        @DisplayName("when there are matching defendants from both case and application")
        void gathering_should_reach_a_defendant_through_a_case_and_an_application() {
            final ObjectNode hearing = hearingWith(
                    caseWith(AUTHORITY, defendantWithAsn("MASTER_10001", "ASN_0001")));
            hearing.set("courtApplications", ModelObjects.array(ModelObjects.courtApplication(
                    ModelObjects.subject(defendantWithAsn("MASTER_10001", "ASN_0002")), null)));

            assertThat(gather(hearing, "MASTER_10001")).hasSize(2);
        }

        @Test
        @DisplayName("when there are defendants only in application")
        void gathering_should_reach_defendants_through_applications_alone() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications", ModelObjects.array(
                    ModelObjects.courtApplication(
                            ModelObjects.subject(defendantWithAsn("MASTER_10001", "ASN_0001")),
                            null),
                    ModelObjects.courtApplication(
                            ModelObjects.subject(defendantWithAsn("MASTER_10001", "ASN_0002")),
                            null),
                    ModelObjects.courtApplication(
                            ModelObjects.subject(defendantWithAsn("MASTER_10002", "ASN_0002")),
                            null)));

            assertThat(gather(hearing, "MASTER_10001")).hasSize(2);
        }

        @Test
        @DisplayName("when there are defendants in application without masterdefendant in subject")
        void gathering_should_skip_an_application_whose_subject_has_no_master_defendant() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications", ModelObjects.array(
                    ModelObjects.courtApplication(
                            ModelObjects.subject(defendantWithAsn("MASTER_10001", "ASN_0001")),
                            null),
                    ModelObjects.courtApplication(ModelObjects.subject(null), null)));

            assertThat(gather(hearing, "MASTER_10001")).hasSize(1);
        }

        @Test
        @DisplayName("when there are no matching defendants")
        void gathering_should_find_nothing_for_an_identity_the_hearing_does_not_carry() {
            final ObjectNode hearing = hearingWith(
                    caseWith(AUTHORITY, defendantWithAsn("MASTER_10001", "ASN_0001")));
            hearing.set("courtApplications", ModelObjects.array(ModelObjects.courtApplication(
                    ModelObjects.subject(defendantWithAsn("MASTER_10001", "ASN_0002")), null)));

            assertThat(gather(hearing, "MASTER_10002")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Pinned oddities — a correction here needs a deviations-register entry first")
    class PinnedOddities {

        /**
         * "First defendant wins." Where a master defendant matches several case defendants, the
         * legacy takes {@code defendants[0]} and discards the others' personal details entirely —
         * their addresses and names never reach the register, even when the first record is the
         * emptier of the two. Their arrest summons numbers are still reachable per case, which is
         * what makes the loss easy to miss.
         */
        @Test
        @DisplayName("the first matching defendant record supplies every personal detail")
        void the_first_matching_defendant_record_wins() {
            final ObjectNode sparse = ModelObjects.defendant(ModelObjects.array());
            sparse.put("masterDefendantId", "MASTER_10001");
            sparse.set("personDefendant", sparse.objectNode()
                    .<ObjectNode>set("personDetails", sparse.objectNode()
                            .put("firstName", "Sparse")));

            final ObjectNode full = ModelObjects.defendant(ModelObjects.array());
            full.put("masterDefendantId", "MASTER_10001");
            full.set("personDefendant", personDetails());

            final ObjectNode hearing = hearingWith(caseWith(AUTHORITY, sparse, full));

            final List<InformantRegisterDefendant> defendants = build(hearing,
                    ModelObjects.registerDefendant("MASTER_10001", null, null, List.of()));

            assertThat(defendants).hasSize(1);
            assertThat(defendants.getFirst().name()).isEqualTo("Sparse");
            assertThat(defendants.getFirst().address1()).isNull();
        }

        /**
         * The full name is the truthy parts of the person's name joined by a single space, so a
         * missing middle name does not leave a double space behind.
         */
        @Test
        @DisplayName("a person with no middle name is named without a double space")
        void a_person_without_a_middle_name_is_named_with_single_spaces() {
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
            defendant.put("masterDefendantId", "MASTER_10001");
            defendant.set("personDefendant", defendant.objectNode()
                    .<ObjectNode>set("personDetails", defendant.objectNode()
                            .put("firstName", "First").put("lastName", "Last")));

            final List<InformantRegisterDefendant> defendants =
                    build(hearingWith(caseWith(AUTHORITY, defendant)),
                            ModelObjects.registerDefendant(
                                    "MASTER_10001", null, null, List.of()));

            assertThat(defendants.getFirst().name()).isEqualTo("First Last");
        }
    }

    @Nested
    @DisplayName("Organisation defendants (parity-pack BS-06)")
    class LegalEntityDefendants {

        /**
         * BS-06: every {@code legalEntityDefendant} leg of this mapper records zero executions across
         * the whole legacy suite — the name, the fallback last name, all five address lines and the
         * postcode. An organisation takes its name from the organisation and its address from the
         * organisation's address, and has no date of birth, nationality or title.
         */
        @Test
        @DisplayName("BS-06 — an organisation takes its name and address from the organisation")
        void an_organisation_defendant_maps_its_name_and_address() {
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
            defendant.put("masterDefendantId", "MASTER_10001");
            defendant.remove("personDefendant");
            defendant.set("legalEntityDefendant", defendant.objectNode()
                    .<ObjectNode>set("organisation", defendant.objectNode()
                            .put("name", "Acme Haulage Limited")
                            .<ObjectNode>set("address", defendant.objectNode()
                                    .put("address1", "O-1").put("address2", "O-2")
                                    .put("address3", "O-3").put("address4", "O-4")
                                    .put("address5", "O-5").put("postcode", "OR1 2AN"))));

            final List<InformantRegisterDefendant> defendants =
                    build(hearingWith(caseWith(AUTHORITY, defendant)),
                            ModelObjects.registerDefendant(
                                    "MASTER_10001", null, null, List.of()));

            assertThat(defendants).hasSize(1);
            assertThat(defendants.getFirst().name()).isEqualTo("Acme Haulage Limited");
            assertThat(defendants.getFirst().lastName()).isEqualTo("Acme Haulage Limited");
            assertThat(defendants.getFirst().address1()).isEqualTo("O-1");
            assertThat(defendants.getFirst().address2()).isEqualTo("O-2");
            assertThat(defendants.getFirst().address3()).isEqualTo("O-3");
            assertThat(defendants.getFirst().address4()).isEqualTo("O-4");
            assertThat(defendants.getFirst().address5()).isEqualTo("O-5");
            assertThat(defendants.getFirst().postCode()).isEqualTo("OR1 2AN");
            assertThat(defendants.getFirst().dateOfBirth()).isNull();
            assertThat(defendants.getFirst().nationality()).isNull();
            assertThat(defendants.getFirst().title()).isNull();
            assertThat(defendants.getFirst().firstName()).isNull();
        }

        /**
         * BS-06: an organisation with no address at all. The name still maps; every address
         * component is absent rather than empty.
         */
        @Test
        @DisplayName("BS-06 — an organisation with no address maps its name and nothing else")
        void an_organisation_without_an_address_maps_only_its_name() {
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
            defendant.put("masterDefendantId", "MASTER_10001");
            defendant.remove("personDefendant");
            defendant.set("legalEntityDefendant", defendant.objectNode()
                    .<ObjectNode>set("organisation", defendant.objectNode()
                            .put("name", "Acme Haulage Limited")));

            final List<InformantRegisterDefendant> defendants =
                    build(hearingWith(caseWith(AUTHORITY, defendant)),
                            ModelObjects.registerDefendant(
                                    "MASTER_10001", null, null, List.of()));

            assertThat(defendants.getFirst().name()).isEqualTo("Acme Haulage Limited");
            assertThat(defendants.getFirst().address1()).isNull();
            assertThat(defendants.getFirst().postCode()).isNull();
        }

        /**
         * BS-06's precedence half: a defendant carrying both records is a person. Every one of the
         * legacy's tests is on the person leg, so nothing anywhere pins which wins.
         */
        @Test
        @DisplayName("BS-06 — a defendant carrying both records is mapped as the person")
        void a_defendant_with_both_records_is_mapped_as_the_person() {
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
            defendant.put("masterDefendantId", "MASTER_10001");
            defendant.set("personDefendant", personDetails());
            defendant.set("legalEntityDefendant", defendant.objectNode()
                    .<ObjectNode>set("organisation", defendant.objectNode()
                            .put("name", "Acme Haulage Limited")
                            .<ObjectNode>set("address", defendant.objectNode()
                                    .put("address1", "O-1"))));

            final List<InformantRegisterDefendant> defendants =
                    build(hearingWith(caseWith(AUTHORITY, defendant)),
                            ModelObjects.registerDefendant(
                                    "MASTER_10001", null, null, List.of()));

            assertThat(defendants.getFirst().name()).isEqualTo("First Middle Last");
            assertThat(defendants.getFirst().address1()).isEqualTo("A-1");
            assertThat(defendants.getFirst().lastName()).isEqualTo("Last");
        }
    }

    @Nested
    @DisplayName("Unguarded dereferences the legacy dies on")
    class Refusals {

        /**
         * {@code defendant.personDefendant.personDetails.dateOfBirth} is read with no guard on
         * {@code personDetails} (`DefendantMapper.js:109`), so a person record shaped without one
         * kills the hearing in the legacy. Refused here, under deviations-register entry 7 — this is
         * the shape the legacy suite's own builder produces, which is why every case that maps a name
         * overwrites it.
         */
        @Test
        @DisplayName("a person record with no personDetails is refused, not read as empty")
        void a_person_record_without_person_details_should_refuse() {
            final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
            defendant.put("masterDefendantId", "MASTER_10001");

            final ObjectNode hearing = hearingWith(caseWith(AUTHORITY, defendant));

            assertThatThrownBy(() -> build(hearing,
                    ModelObjects.registerDefendant("MASTER_10001", null, null, List.of())))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * {@code courtApplication.subject.masterDefendant} is reached through an unguarded
         * {@code subject} (`DefendantMapper.js:180`).
         */
        @Test
        @DisplayName("an application with no subject is refused, not skipped")
        void an_application_without_a_subject_should_refuse() {
            final ObjectNode hearing = ModelObjects.hearing();
            hearing.set("courtApplications",
                    ModelObjects.array(ModelObjects.hearing().objectNode()));

            assertThatThrownBy(() -> gather(hearing, "MASTER_10001"))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    /**
     * Runs the mapper over one hearing for the given register defendants.
     *
     * @param hearing            the hearing tree
     * @param registerDefendants the fragment's defendants
     * @return the mapped defendants
     */
    private List<InformantRegisterDefendant> build(
            final JsonNode hearing, final RegisterDefendant... registerDefendants) {
        final RegisterFragment fragment = ModelObjects.fragment(AUTHORITY, registerDefendants);
        return new DefendantMapper(hearing, fragment, resultDataMapper).build();
    }

    /**
     * Runs the mapper's gathering half, as four of the legacy cases do.
     *
     * @param hearing           the hearing tree
     * @param masterDefendantId the identity to gather for
     * @return the defendant records found
     */
    private List<JsonNode> gather(final JsonNode hearing, final String masterDefendantId) {
        return new DefendantMapper(hearing, ModelObjects.fragment(AUTHORITY), resultDataMapper)
                .defendantsOf(masterDefendantId);
    }

    /**
     * The fully populated person record the first legacy case builds.
     *
     * @return the person-defendant tree
     */
    private static ObjectNode personDetails() {
        final ObjectNode person = ModelObjects.hearing().objectNode();
        final ObjectNode details = person.objectNode();
        details.put("firstName", "First");
        details.put("middleName", "Middle");
        details.put("lastName", "Last");
        details.put("title", "title");
        details.put("nationalityCode", "GB");
        details.put("dateOfBirth", "01/01/1900");
        details.set("address", person.objectNode()
                .put("address1", "A-1").put("address2", "A-2").put("address3", "A-3")
                .put("address4", "A-4").put("address5", "A-5").put("postcode", "PostCode"));
        person.set("personDetails", details);
        return person;
    }

    /**
     * A defendant record carrying only a first name, as several legacy cases build.
     *
     * @param masterDefendantId the identity
     * @param firstName         the first name
     * @return the defendant tree
     */
    private static ObjectNode namedDefendant(
            final String masterDefendantId, final String firstName) {
        final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
        defendant.put("masterDefendantId", masterDefendantId);
        defendant.set("personDefendant", defendant.objectNode()
                .<ObjectNode>set("personDetails", defendant.objectNode()
                        .put("firstName", firstName)));
        return defendant;
    }

    /**
     * The legacy suite's {@code constructDefendant} — a first name and an arrest summons number.
     *
     * @param masterDefendantId the identity
     * @param arrestSummons     the arrest summons number
     * @return the defendant tree
     */
    private static ObjectNode defendantWithAsn(
            final String masterDefendantId, final String arrestSummons) {
        final ObjectNode defendant = ModelObjects.defendant(ModelObjects.array());
        defendant.put("masterDefendantId", masterDefendantId);
        defendant.set("personDefendant", defendant.objectNode()
                .put("arrestSummonsNumber", arrestSummons)
                .<ObjectNode>set("personDetails", defendant.objectNode()
                        .put("firstName", "Luis=")));
        return defendant;
    }

    /**
     * One prosecution case carrying the given defendants.
     *
     * @param authorityId the prosecuting authority
     * @param defendants  the defendants
     * @return the prosecution-case tree
     */
    private static ObjectNode caseWith(
            final String authorityId, final ObjectNode... defendants) {
        final ObjectNode prosecutionCase =
                ModelObjects.prosecutionCase(authorityId, null, null);
        prosecutionCase.set("defendants", ModelObjects.array(defendants));
        return prosecutionCase;
    }

    /**
     * A hearing carrying the given prosecution cases.
     *
     * @param cases the prosecution cases
     * @return the hearing tree
     */
    private static ObjectNode hearingWith(final ObjectNode... cases) {
        final ObjectNode hearing = ModelObjects.hearing();
        hearing.set("prosecutionCases", ModelObjects.array(cases));
        return hearing;
    }
}
