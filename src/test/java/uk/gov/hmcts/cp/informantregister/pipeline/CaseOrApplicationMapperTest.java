package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
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
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterCaseOrApplication;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The JUnit twins of the legacy {@code ProsecutionCaseOrApplicationMapper} Jest suite.
 *
 * <p>Four twins, one per Jest case, each running against byte-identical copies of the four hearing
 * fixtures and the register fragment the Jest suite loads.
 *
 * <p>The second twin is also the D8 witness at this level: the two case entries it produces each
 * carry <em>both</em> of the defendant's offences, because the legacy asks this mapper's offence
 * collaborator for the defendant's whole list once per entry. See {@link OffenceMapper} for why that
 * is preserved.
 */
@DisplayName("CaseOrApplicationMapper — parity with the legacy ProsecutionCaseOrApplicationMapper")
class CaseOrApplicationMapperTest {

    /** No case here reads the clock; the fixed value only makes that visible. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private static final String FIXTURES = "/fixtures/outboundinformantregister/mapper/";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final ResultDataMapper resultDataMapper = new ResultDataMapper(new HearingDates(FROZEN));

    @Nested
    @DisplayName("ProsecutionCaseOrApplicationMapper mapper works correctly")
    class LegacyTwins {

        @Test
        @DisplayName("should populate prosecution case or application information")
        void one_matching_case_should_produce_one_entry() {
            final List<InformantRegisterCaseOrApplication> entries = build("hearing.json");

            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().caseOrApplicationReference()).isEqualTo("TFL4359536");
            assertThat(entries.getFirst().results()).hasSize(1);
            assertThat(entries.getFirst().offences()).hasSize(1);
            assertThat(entries.getFirst().arrestSummonsNumber()).isEqualTo("asn1234");
        }

        @Test
        @DisplayName("should populate prosecution cases or application information if multiple "
                + "cases found for that defendant")
        void two_matching_cases_should_produce_two_entries_each_with_every_offence() {
            final List<InformantRegisterCaseOrApplication> entries =
                    build("hearing_with_multiple_cases.json");

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).caseOrApplicationReference()).isEqualTo("TFL4359536");
            assertThat(entries.get(0).results()).hasSize(1);
            assertThat(entries.get(0).offences()).hasSize(2);
            assertThat(entries.get(0).arrestSummonsNumber()).isEqualTo("asn1234");
            assertThat(entries.get(1).caseOrApplicationReference()).isEqualTo("TFL4359536");
            assertThat(entries.get(1).results()).hasSize(1);
            assertThat(entries.get(1).offences()).hasSize(2);
            assertThat(entries.get(1).arrestSummonsNumber()).isEqualTo("asn1234");
        }

        @Test
        @DisplayName("should populate prosecution cases and application information from "
                + "applications")
        void a_matching_application_should_produce_its_own_entry() {
            final List<InformantRegisterCaseOrApplication> entries =
                    build("hearing_with_court_applications.json");

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).caseOrApplicationReference()).isEqualTo("TFL4359536");
            assertThat(entries.get(0).arrestSummonsNumber()).isEqualTo("asn1234");
            assertThat(entries.get(1).caseOrApplicationReference()).isEqualTo("TFL4359536");
            assertThat(entries.get(1).arrestSummonsNumber()).isEqualTo("TFL");
        }

        @Test
        @DisplayName("should populate prosecution cases and application information from "
                + "applications only for matching data registerDefendant.applications.ids are not "
                + "matching with hearingJson.courtApplications.id")
        void an_application_the_hearing_does_not_carry_should_produce_no_entry() {
            final List<InformantRegisterCaseOrApplication> entries =
                    build("hearing_without_matching_court_applications.json");

            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().caseOrApplicationReference()).isEqualTo("TFL4359536");
            assertThat(entries.getFirst().arrestSummonsNumber()).isEqualTo("asn1234");
        }
    }

    @Nested
    @DisplayName("Pinned oddities — a correction here needs a deviations-register entry first")
    class PinnedOddities {

        /**
         * Parity-pack pinning case {@code d08-offences-duplicated-onto-every-case}, at this mapper's
         * own level and asserted by name rather than by count alone. The two entries produced from
         * {@code hearing_with_multiple_cases.json} both carry both offences, and each of those
         * offences still points at the case it really came from — so the entry contradicts itself.
         * A Java offence mapper scoped to its own case, which is the obvious tidy design, produces
         * one offence per entry and must fail here.
         */
        @Test
        @DisplayName("d08 — each case entry carries the other case's offence too, and says so")
        void every_case_entry_carries_the_defendants_whole_offence_list() {
            final List<InformantRegisterCaseOrApplication> entries =
                    build("hearing_with_multiple_cases.json");

            assertThat(entries).hasSize(2);
            for (final InformantRegisterCaseOrApplication entry : entries) {
                assertThat(entry.caseOrApplicationReference()).isEqualTo("TFL4359536");
                assertThat(entry.offences()).hasSize(2);
                assertThat(entry.offences())
                        .extracting(offence -> offence.offenceCode())
                        .containsExactly("PS90010", "PS90010");
            }
        }
    }

    @Nested
    @DisplayName("Branches the legacy suite never executes (parity-pack BS-15)")
    class UncoveredBranches {

        /**
         * BS-15, first uncovered leg: {@code getProsecutionCase} tests {@code hearingJson
         * .prosecutionCases} before looking anything up, and answers nothing when the hearing has
         * none. The defendant's case ids then match nothing and no entry is produced — which, with
         * no applications either, makes the whole component absent rather than empty.
         */
        @Test
        @DisplayName("BS-15 — a hearing with no prosecution cases produces no entries at all")
        void a_hearing_without_prosecution_cases_should_produce_nothing() {
            final RegisterDefendant defendant = ModelObjects.registerDefendant(
                    "MASTER_10001", List.of("caseId1"), null, List.of());

            assertThat(buildFor(ModelObjects.hearing(), defendant)).isNull();
        }

        /**
         * BS-15, second uncovered leg: the application ASN is read only when the subject's master
         * defendant is <em>this</em> defendant. When it is not, the entry is still produced — the
         * application was on the defendant's own list — but with no arrest summons number.
         */
        @Test
        @DisplayName("BS-15 — an application whose subject is another defendant has no ASN")
        void an_application_subject_that_is_another_defendant_yields_no_asn() {
            final var masterDefendant = ModelObjects.masterDefendant();
            masterDefendant.put("masterDefendantId", "MASTER_20002");
            masterDefendant.set("personDefendant",
                    masterDefendant.objectNode().put("arrestSummonsNumber", "ASN_0002"));
            final var application = ModelObjects.courtApplication(
                    ModelObjects.subject(masterDefendant),
                    ModelObjects.applicant(ModelObjects.prosecutingAuthority(
                            "31af405e-7b60-4dd8-a244-c24c2d3fa595", null)));
            application.put("id", "application-1");
            application.put("applicationReference", "APP-REF-1");
            final var hearing = ModelObjects.hearing();
            hearing.set("courtApplications", ModelObjects.array(application));

            final RegisterDefendant defendant = ModelObjects.registerDefendant(
                    "MASTER_10001", null, List.of("application-1"), List.of());

            final List<InformantRegisterCaseOrApplication> entries =
                    buildFor(hearing, defendant);

            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().caseOrApplicationReference()).isEqualTo("APP-REF-1");
            assertThat(entries.getFirst().arrestSummonsNumber()).isNull();
        }

        /**
         * {@code getASNForCase} dereferences {@code prosecutionCase.defendants} with no guard
         * (`ProsecutionCaseOrApplicationMapper.js:60`), so a case without the field kills the hearing
         * in the legacy. Refused here, under deviations-register entry 7.
         */
        @Test
        @DisplayName("a case with no defendants is refused, not read as having no ASN")
        void a_case_without_defendants_should_refuse() {
            final var prosecutionCase = ModelObjects.prosecutionCase(
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", "TFL4359536", null);
            prosecutionCase.put("id", "caseId1");
            final var hearing = ModelObjects.hearing();
            hearing.set("prosecutionCases", ModelObjects.array(prosecutionCase));

            final RegisterDefendant defendant = ModelObjects.registerDefendant(
                    "MASTER_10001", List.of("caseId1"), null, List.of());

            assertThatThrownBy(() -> buildFor(hearing, defendant))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    /**
     * Runs the mapper over one hearing fixture and the register fragment the Jest suite loads.
     *
     * @param hearingFixture the hearing fixture file name
     * @return the mapped entries
     */
    private List<InformantRegisterCaseOrApplication> build(final String hearingFixture) {
        final RegisterFragment fragment =
                ModelObjects.fragmentFrom(fixture("informantRegister.json"));
        return build(fixture(hearingFixture), fragment, fragment.registerDefendants().getFirst());
    }

    /**
     * Runs the mapper over a hearing built inline, for one defendant of this suite's authority.
     *
     * @param hearing   the hearing tree
     * @param defendant the defendant
     * @return the mapped entries
     */
    private List<InformantRegisterCaseOrApplication> buildFor(
            final JsonNode hearing, final RegisterDefendant defendant) {
        return build(hearing,
                ModelObjects.fragment("31af405e-7b60-4dd8-a244-c24c2d3fa595", defendant),
                defendant);
    }

    /**
     * Runs the mapper.
     *
     * @param hearing   the hearing tree
     * @param fragment  the authority's fragment
     * @param defendant the defendant whose cases and applications are being mapped
     * @return the mapped entries
     */
    private List<InformantRegisterCaseOrApplication> build(
            final JsonNode hearing,
            final RegisterFragment fragment,
            final RegisterDefendant defendant) {
        return new CaseOrApplicationMapper(
                hearing, fragment, defendant, new ResultMapper(defendant, resultDataMapper))
                .build();
    }

    /**
     * Reads one of the byte-identical fixture copies.
     *
     * @param name the fixture file name
     * @return the parsed tree
     */
    private JsonNode fixture(final String name) {
        final String resource = FIXTURES + name;
        try (InputStream source = CaseOrApplicationMapperTest.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return mapper.readTree(source);
        } catch (java.io.IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
