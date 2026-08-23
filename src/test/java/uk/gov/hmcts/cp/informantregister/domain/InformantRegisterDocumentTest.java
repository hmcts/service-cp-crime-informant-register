package uk.gov.hmcts.cp.informantregister.domain;

import java.lang.reflect.RecordComponent;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.networknt.schema.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.support.ResultsCommandSchemas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds the outbound document tree to the results-owned {@code add-informant-register} schema.
 *
 * <p>This service does not own this contract, so "the records match the schema" cannot be asserted
 * by reading them: it is asserted by serialising real documents and validating the result against
 * byte-identical copies of the schema files, and by comparing every record's components against the
 * property list its schema declares. A field added, renamed or dropped on either side fails here.
 *
 * <p>Two documents carry the serialisation assertions, and they are chosen to bracket the contract
 * rather than to be representative: one populates every optional property at every depth, so no
 * property escapes validation; the other populates only what the schema requires, so the rendering
 * of absence is exercised at the same time as the rendering of presence.
 *
 * <p>Negative controls sit beside both. A schema that accepts everything would let all of this pass,
 * so the suite also proves the schema rejects a missing required field, an empty array, and a
 * timestamp written as a number.
 */
class InformantRegisterDocumentTest {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();
    private static final Schema SCHEMA = ResultsCommandSchemas.addInformantRegisterSchema();

    private static final UUID HEARING_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID AUTHORITY_ID = UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8");
    private static final UUID GROUP_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private static final ZonedDateTime REGISTER_DATE =
            ZonedDateTime.of(2026, 8, 20, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime HEARING_DATE =
            ZonedDateTime.of(2026, 8, 19, 9, 30, 0, 0, ZoneOffset.UTC);

    /**
     * A {@code hearingStartTime} in the shape {@code informantRegisterHearing.json} declares —
     * RFC 3339 {@code full-time}, which is a time with an offset.
     */
    private static final String CONTRACT_FORM_HEARING_START_TIME = "10:00:00Z";

    /**
     * A {@code hearingStartTime} in the shape the function app actually renders.
     *
     * <p>{@code CourtSessionMapper.getHearingStartTime()} returns
     * {@code DateService.getLocalDateTime(sittingDay)}, which is
     * {@code moment.tz(value, "Europe/London").format('YYYY-MM-DDTHH:mm:ss') + 'Z'} — a full
     * date-time, not a time. See {@code OutboundInformantRegister/InformantRegisterAggregationRequest/
     * Mapper/CourtSessionMapper.js} and {@code NowsHelper/service/DateService.js} in the function app.
     */
    private static final String PARITY_FORM_HEARING_START_TIME = "2020-06-19T10:08:03Z";

    // --- documents -------------------------------------------------------------------------

    /**
     * A document with every property of every node populated.
     *
     * <p>Written out in full rather than built by a helper: a builder would let an optional property
     * be forgotten without the test noticing, and the point of this document is that nothing is
     * forgotten. The component-name assertions below are what keep it honest as the contract moves.
     */
    private static InformantRegisterDocument fullyPopulated() {
        final InformantRegisterResultData resultData = new InformantRegisterResultData(
                "150.00", "2026-09-01", "Bristol Magistrates' Court",
                "12", "MONTHS", "2026-08-20", "2027-08-19", "6", "WEEKS");
        final InformantRegisterResult result =
                new InformantRegisterResult("Fine of £150", "FO", resultData);
        final InformantRegisterVerdict verdict =
                new InformantRegisterVerdict("G", "2026-08-19", "FOUND_GUILTY");
        final InformantRegisterOffence offence = new InformantRegisterOffence(
                "20AB1234567", "TH68001", 0, "Theft from a shop", "GUILTY", verdict, List.of(result));
        final InformantRegisterCaseOrApplication caseOrApplication = new InformantRegisterCaseOrApplication(
                "20AB1234567", "20/1234/56A", "Particulars of the application",
                List.of(offence), List.of(result));
        final InformantRegisterDefendant defendant = new InformantRegisterDefendant(
                "SMITH, John", "1990-01-31", "1 High Street", "Bedminster", "Bristol",
                "Avon", "England", "BS1 1AA", "British", "Mr", "John", "Smith",
                List.of(caseOrApplication), List.of(result));
        final InformantRegisterHearing session =
                new InformantRegisterHearing("Court 1", CONTRACT_FORM_HEARING_START_TIME, List.of(defendant));
        final InformantRegisterHearingVenue venue = new InformantRegisterHearingVenue(
                "Avon and Somerset", "Bristol Magistrates' Court", List.of(session));
        final InformantRegisterRecipient recipient = new InformantRegisterRecipient(
                "Informant Register Inbox", "register@example.gov.uk",
                "register.copy@example.gov.uk", "INFORMANT_REGISTER");

        return new InformantRegisterDocument(
                REGISTER_DATE, HEARING_DATE, HEARING_ID, AUTHORITY_ID,
                "CPS", "CPS001", "MC01", "Crown Prosecution Service",
                "informant-register-CPS-20260820.pdf", List.of(recipient), venue, GROUP_ID);
    }

    /** A document carrying exactly what the schema requires and nothing else. */
    private static InformantRegisterDocument minimal() {
        final InformantRegisterDefendant defendant = new InformantRegisterDefendant(
                "SMITH, John", null, "1 High Street", null, null, null, null,
                null, null, null, null, null, null, null);
        final InformantRegisterHearing session =
                new InformantRegisterHearing("Court 1", CONTRACT_FORM_HEARING_START_TIME, List.of(defendant));
        final InformantRegisterHearingVenue venue =
                new InformantRegisterHearingVenue(null, "Bristol Magistrates' Court", List.of(session));

        return new InformantRegisterDocument(
                REGISTER_DATE, HEARING_DATE, HEARING_ID, AUTHORITY_ID,
                "CPS", null, null, null,
                "informant-register-CPS-20260820.pdf", null, venue, null);
    }

    private static JsonNode serialised(final Object document) {
        return MAPPER.readTree(MAPPER.writeValueAsString(document));
    }

    private static List<String> violations(final JsonNode body) {
        return SCHEMA.validate(body).stream().map(Object::toString).toList();
    }

    @Nested
    @DisplayName("Serialisation")
    class Serialisation {

        @Test
        @DisplayName("a document populated at every depth serialises to a schema-valid body")
        void a_fully_populated_document_should_serialise_to_a_schema_valid_body() {
            final JsonNode body = serialised(fullyPopulated());

            assertThat(violations(body)).isEmpty();
        }

        @Test
        @DisplayName("a document carrying only the required properties serialises to a schema-valid body")
        void a_minimal_document_should_serialise_to_a_schema_valid_body() {
            final JsonNode body = serialised(minimal());

            assertThat(violations(body)).isEmpty();
        }

        @Test
        @DisplayName("an absent property is omitted rather than written as null")
        void an_absent_property_should_be_omitted_rather_than_written_as_null() {
            // The schema gives every optional property a JSON type, so a null would be a violation
            // rather than a tolerated blank. NON_NULL is what makes absence expressible at all, and
            // this asserts it at every depth rather than only on the root.
            final JsonNode body = serialised(minimal());

            assertThat(nullBearingPaths(body, "")).isEmpty();
        }

        @Test
        @DisplayName("the minimal body carries exactly the properties the schema requires")
        void the_minimal_body_should_carry_exactly_the_required_properties() {
            final JsonNode body = serialised(minimal());
            final JsonNode schemaDocument =
                    ResultsCommandSchemas.schemaDocument("informantRegisterDocumentRequest.json");
            final List<String> required = new ArrayList<>();
            schemaDocument.get("required").forEach(node -> required.add(node.stringValue()));

            assertThat(List.copyOf(body.propertyNames()))
                    .containsExactlyInAnyOrderElementsOf(required);
        }

        @Test
        @DisplayName("the timestamps are written as text whatever the mapper would prefer")
        void a_timestamp_should_be_written_as_text_whatever_the_mapper_prefers() {
            // WRITE_DATES_AS_TIMESTAMPS is a global switch on a shared mapper. If the wire form
            // depended on it, somebody customising serialisation for an unrelated reason would turn
            // this body into a pair of epoch numbers, and nothing here would notice — the consumer's
            // schema would.
            final ObjectMapper epochPreferring = JacksonConfig
                    .applyContractDefaults(JsonMapper.builder())
                    .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

            final JsonNode body = epochPreferring.readTree(epochPreferring.writeValueAsString(minimal()));

            assertThat(body.get("registerDate").stringValue()).isEqualTo("2026-08-20T00:00:00Z");
            assertThat(body.get("hearingDate").stringValue()).isEqualTo("2026-08-19T09:30:00Z");
            assertThat(violations(body)).isEmpty();
        }

        @Test
        @DisplayName("the identifiers are written in the canonical hyphenated form")
        void an_identifier_should_be_written_in_the_canonical_form() {
            final JsonNode body = serialised(fullyPopulated());

            assertThat(body.get("hearingId").stringValue()).isEqualTo(HEARING_ID.toString());
            assertThat(body.get("prosecutionAuthorityId").stringValue()).isEqualTo(AUTHORITY_ID.toString());
            assertThat(body.get("groupId").stringValue()).isEqualTo(GROUP_ID.toString());
        }
    }

    @Nested
    @DisplayName("Negative controls")
    class NegativeControls {

        @Test
        @DisplayName("a document missing a required property is rejected")
        void a_document_missing_a_required_property_should_be_rejected() {
            final InformantRegisterDocument document = minimal();
            final InformantRegisterDocument withoutFileName = new InformantRegisterDocument(
                    document.registerDate(), document.hearingDate(), document.hearingId(),
                    document.prosecutionAuthorityId(), document.prosecutionAuthorityCode(),
                    null, null, null, null, null, document.hearingVenue(), null);

            assertThat(violations(serialised(withoutFileName))).isNotEmpty();
        }

        @Test
        @DisplayName("an empty array is rejected, so omission is the only rendering of absence")
        void an_empty_array_should_be_rejected() {
            // Every array in the tree declares minItems: 1. That is why a list-valued component is
            // left null rather than defaulted to an empty list — an empty list is not a shorter way
            // of saying nothing, it is an invalid body.
            final InformantRegisterDocument document = minimal();
            final InformantRegisterDocument withNoRecipients = new InformantRegisterDocument(
                    document.registerDate(), document.hearingDate(), document.hearingId(),
                    document.prosecutionAuthorityId(), document.prosecutionAuthorityCode(),
                    null, null, null, document.fileName(), List.of(), document.hearingVenue(), null);

            assertThat(violations(serialised(withNoRecipients))).isNotEmpty();
        }

        @Test
        @DisplayName("a body carrying an unknown property is rejected, so the contract is closed in fact")
        void an_unknown_property_should_be_rejected() {
            final ObjectNode body = (ObjectNode) serialised(minimal());
            body.put("courtCentreId", "abc");

            assertThat(violations(body)).isNotEmpty();
        }

        @Test
        @DisplayName("a timestamp written as a number is rejected, so the format assertion is live")
        void a_numeric_timestamp_should_be_rejected() {
            final ObjectNode body = (ObjectNode) serialised(minimal());
            body.put("registerDate", 1_755_648_000L);

            assertThat(violations(body)).isNotEmpty();
        }

        @Test
        @DisplayName("a timestamp written as a bare date is rejected, so date-time means date-time")
        void a_bare_date_timestamp_should_be_rejected() {
            // The command API's own RAML example sends "registerDate": "2020-01-20" and omits both
            // hearingId and hearingDate, so the example is not valid against the schema it
            // illustrates. This pins which of the two the records follow: the schema.
            final ObjectNode body = (ObjectNode) serialised(minimal());
            body.put("registerDate", "2026-08-20");

            assertThat(violations(body)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Open questions the contract itself does not settle")
    class OpenQuestions {

        @Test
        @DisplayName("hearingStartTime as the function app renders it is refused by the declared format")
        void the_parity_form_of_hearing_start_time_should_be_refused_by_the_declared_format() {
            // Recorded rather than resolved. The schema declares format: time, so an RFC 3339
            // full-time; the function app renders a full date-time; and every example body in the
            // results repository — the RAML example and the integration-test template — carries a
            // bare date, which is neither. Three sources, three shapes.
            //
            // Nothing is decided here. The component is a String, so this record can carry any of
            // the three, and which one the transformer writes is a question for Results, not a
            // question this test may answer by picking a fixture. What this pins is that the choice
            // is real and observable: if somebody later makes the transformer emit the parity form,
            // the body it produces will not satisfy the schema as written.
            final InformantRegisterDocument document = minimal();
            final InformantRegisterHearingVenue venue = document.hearingVenue();
            final InformantRegisterHearing session = venue.courtSessions().getFirst();
            final InformantRegisterDocument asTheFunctionAppRendersIt = new InformantRegisterDocument(
                    document.registerDate(), document.hearingDate(), document.hearingId(),
                    document.prosecutionAuthorityId(), document.prosecutionAuthorityCode(),
                    null, null, null, document.fileName(), null,
                    new InformantRegisterHearingVenue(venue.ljaName(), venue.courtHouse(),
                            List.of(new InformantRegisterHearing(session.courtRoom(),
                                    PARITY_FORM_HEARING_START_TIME, session.defendants()))),
                    null);

            assertThat(violations(serialised(asTheFunctionAppRendersIt)))
                    .anySatisfy(violation -> assertThat(violation).contains("hearingStartTime"));
        }

        @Test
        @DisplayName("registerDate and hearingDate as the function app renders them are accepted")
        void the_parity_form_of_the_timestamps_should_be_accepted() {
            // The counterpart, and the reason the two timestamps are typed rather than left as
            // strings: the function app's own Jest expectations are '2020-01-20T11:00:00Z' and
            // '2020-01-20T10:00:00Z' (OutboundInformantRegister/test/index.test.js), which is
            // exactly what a UTC ZonedDateTime serialises to here. The RAML example's bare
            // "registerDate": "2020-01-20" agrees with neither the schema nor the producer.
            final InformantRegisterDocument document = minimal();
            final ZonedDateTime asTheJestCaseWritesIt =
                    ZonedDateTime.of(2020, 1, 20, 11, 0, 0, 0, ZoneOffset.UTC);
            final InformantRegisterDocument dated = new InformantRegisterDocument(
                    asTheJestCaseWritesIt, document.hearingDate(), document.hearingId(),
                    document.prosecutionAuthorityId(), document.prosecutionAuthorityCode(),
                    null, null, null, document.fileName(), null, document.hearingVenue(), null);

            final JsonNode body = serialised(dated);

            assertThat(body.get("registerDate").stringValue()).isEqualTo("2020-01-20T11:00:00Z");
            assertThat(violations(body)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Contract shape")
    class ContractShape {

        /**
         * Each record of the tree beside the schema file it renders.
         *
         * <p>This is the mapping the "no extra fields, no renames" gate is enforced through: the
         * schema files are the contract, and every one of them must have exactly one record whose
         * components are exactly its properties.
         */
        static Stream<Object[]> recordsAndSchemas() {
            return Stream.of(
                    new Object[] {InformantRegisterDocument.class, "informantRegisterDocumentRequest.json"},
                    new Object[] {InformantRegisterHearingVenue.class, "informantRegisterHearingVenue.json"},
                    new Object[] {InformantRegisterHearing.class, "informantRegisterHearing.json"},
                    new Object[] {InformantRegisterDefendant.class, "informantRegisterDefendant.json"},
                    new Object[] {InformantRegisterCaseOrApplication.class,
                            "informantRegisterCaseOrApplication.json"},
                    new Object[] {InformantRegisterOffence.class, "informantRegisterOffence.json"},
                    new Object[] {InformantRegisterResult.class, "informantRegisterResult.json"},
                    new Object[] {InformantRegisterResultData.class, "informantRegisterResultData.json"},
                    new Object[] {InformantRegisterVerdict.class, "verdict.json"},
                    new Object[] {InformantRegisterRecipient.class, "informantRegisterRecipient.json"});
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("recordsAndSchemas")
        @DisplayName("a record declares exactly the properties its schema declares")
        void a_record_should_declare_exactly_the_properties_its_schema_declares(
                final Class<?> type, final String schemaFile) {
            final List<String> components = Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            final List<String> properties = List.copyOf(
                    ResultsCommandSchemas.schemaDocument(schemaFile).get("properties").propertyNames());

            assertThat(components).containsExactlyInAnyOrderElementsOf(properties);
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("recordsAndSchemas")
        @DisplayName("every schema in the tree is closed")
        void a_schema_in_the_tree_should_be_closed(final Class<?> type, final String schemaFile) {
            assertThat(ResultsCommandSchemas.schemaDocument(schemaFile)
                    .get("additionalProperties").booleanValue())
                    .as("%s must stay additionalProperties:false — %s may not widen it",
                            schemaFile, type.getSimpleName())
                    .isFalse();
        }

        @Test
        @DisplayName("every schema file of the contract has a record")
        void every_schema_file_should_have_a_record() {
            // Without this, a node added to the contract would simply have no record and no test —
            // the per-file assertions above only cover the files somebody remembered to map.
            final List<String> mapped = recordsAndSchemas().map(row -> (String) row[1]).toList();

            assertThat(mapped)
                    .containsExactlyInAnyOrderElementsOf(ResultsCommandSchemas.DOCUMENT_SCHEMA_FILES);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("a list handed in is copied, so the caller cannot change the document afterwards")
        void a_list_handed_in_should_be_copied() {
            final List<InformantRegisterResult> results = new ArrayList<>();
            results.add(new InformantRegisterResult("Fine of £150", null, null));
            final InformantRegisterOffence offence =
                    new InformantRegisterOffence(null, "TH68001", 0, "Theft from a shop", null, null, results);

            results.add(new InformantRegisterResult("Costs of £85", null, null));

            assertThat(offence.offenceResults()).hasSize(1);
        }

        @Test
        @DisplayName("a list component cannot be modified through the accessor")
        void a_list_component_should_be_unmodifiable() {
            final InformantRegisterOffence offence = new InformantRegisterOffence(
                    null, "TH68001", 0, "Theft from a shop", null, null,
                    List.of(new InformantRegisterResult("Fine of £150", null, null)));

            assertThatThrownBy(() -> offence.offenceResults().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("an absent list stays absent rather than becoming empty")
        void an_absent_list_should_stay_absent() {
            final InformantRegisterOffence offence =
                    new InformantRegisterOffence(null, "TH68001", 0, "Theft from a shop", null, null, null);

            assertThat(offence.offenceResults()).isNull();
        }

        @Test
        @DisplayName("every list-valued component of the tree is frozen")
        void every_list_valued_component_should_be_frozen() {
            // Named individually so a record that forgets its compact constructor fails here rather
            // than waiting for a caller to mutate one in production.
            final InformantRegisterDocument document = fullyPopulated();
            final InformantRegisterHearingVenue venue = document.hearingVenue();
            final InformantRegisterHearing session = venue.courtSessions().getFirst();
            final InformantRegisterDefendant defendant = session.defendants().getFirst();
            final InformantRegisterCaseOrApplication caseOrApplication =
                    defendant.prosecutionCasesOrApplications().getFirst();
            final InformantRegisterOffence offence = caseOrApplication.offences().getFirst();

            assertThat(List.of(
                    document.recipients(),
                    venue.courtSessions(),
                    session.defendants(),
                    defendant.prosecutionCasesOrApplications(),
                    defendant.results(),
                    caseOrApplication.offences(),
                    caseOrApplication.results(),
                    offence.offenceResults()))
                    .allSatisfy(list -> assertThatThrownBy(list::clear)
                            .isInstanceOf(UnsupportedOperationException.class));
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Every path in the body whose value is a JSON null, so absence can be asserted tree-wide. */
    private static List<String> nullBearingPaths(final JsonNode node, final String path) {
        final List<String> paths = new ArrayList<>();
        if (node.isNull()) {
            paths.add(path);
        } else if (node.isObject()) {
            node.properties().forEach(entry ->
                    paths.addAll(nullBearingPaths(entry.getValue(), path + "/" + entry.getKey())));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                paths.addAll(nullBearingPaths(node.get(index), path + "[" + index + "]"));
            }
        }
        return paths;
    }
}
