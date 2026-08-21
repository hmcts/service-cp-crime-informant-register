package uk.gov.hmcts.cp.informantregister.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the production parser and the committed draft-07 schema to each other.
 *
 * <p>"The tests keep them in step" only means something if the tests actually compare the two, so
 * every corpus case — valid and invalid alike — is run through both, and the test asserts they agree
 * on accept or reject. A case the schema accepts and the parser rejects, or the reverse, is a
 * failure rather than a curiosity.
 *
 * <p>The corpus is deliberately weighted towards the boundaries where a hand-written parser and a
 * schema most easily drift: well-formed but non-existent dates, non-canonical identifiers,
 * offset-bearing against {@code Z} instants, empty strings and nulls, and one unknown field on an
 * otherwise valid body.
 *
 * <p>Required-field presence, the two enumerations and the closedness of the contract are asserted
 * <em>from the schema document itself</em>, so adding a field to the schema without teaching the
 * parser about it fails the build.
 *
 * <p>The validator is a test-only dependency. It never appears on the runtime classpath.
 */
class DistributionCommandSchemaCorpusTest {

    private static final String SCHEMA_RESOURCE = "contracts/distribution-command.schema.json";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();
    private static final DistributionCommandParser PARSER = new DistributionCommandParser(MAPPER);

    private static final String SCHEMA_TEXT = readSchemaText();
    private static final JsonNode SCHEMA_DOCUMENT = MAPPER.readTree(SCHEMA_TEXT);
    private static final Schema SCHEMA = draft07Schema();

    private static final String CANONICAL_REQUEST_ID = "\"3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8\"";
    private static final String CANONICAL_HEARING_ID = "\"11111111-2222-4333-8444-555555555555\"";

    // --- fixture plumbing ------------------------------------------------------------------

    private static String readSchemaText() {
        try (InputStream stream = DistributionCommandSchemaCorpusTest.class
                .getClassLoader()
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Contract schema not found: " + SCHEMA_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Contract schema could not be read: " + SCHEMA_RESOURCE, e);
        }
    }

    private static Schema draft07Schema() {
        final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                // Formats are assertions here, not annotations: `uuid`, `date` and `date-time` must
                // actually be checked or the corpus proves nothing about format drift.
                .formatAssertionsEnabled(Boolean.TRUE)
                .build();
        final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_7,
                builder -> builder.schemaRegistryConfig(config));
        return registry.getSchema(SCHEMA_TEXT);
    }

    /**
     * Renders a body from field name to raw JSON text, so a case can supply a null, a number or an
     * empty string as easily as a well-formed value.
     */
    private static String render(final Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> "  \"" + entry.getKey() + "\": " + entry.getValue())
                .collect(Collectors.joining(",\n", "{\n", "\n}"));
    }

    private static Map<String, String> canonicalFields() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("source", "\"RESULTS\"");
        fields.put("requestId", CANONICAL_REQUEST_ID);
        fields.put("hearingId", CANONICAL_HEARING_ID);
        fields.put("hearingDay", "\"2026-08-20\"");
        fields.put("sharedTime", "\"2026-08-20T09:00:00Z\"");
        fields.put("eventType", "\"Hearing_Resulted\"");
        return fields;
    }

    private static String bodyWith(final String field, final String rawValue) {
        final Map<String, String> fields = canonicalFields();
        fields.put(field, rawValue);
        return render(fields);
    }

    private static String bodyWithout(final String field) {
        final Map<String, String> fields = canonicalFields();
        fields.remove(field);
        return render(fields);
    }

    private static boolean parserAccepts(final String body) {
        try {
            PARSER.parse(body);
            return true;
        } catch (ContractValidationException rejected) {
            return false;
        }
    }

    private static boolean schemaAccepts(final String body) {
        final JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (JacksonException malformed) {
            // A body the parser cannot even read is a rejection on both sides.
            return false;
        }
        return SCHEMA.validate(node).isEmpty();
    }

    private static void assertAgreement(final String body, final boolean expectedAccepted) {
        final boolean parser = parserAccepts(body);
        final boolean schema = schemaAccepts(body);

        assertThat(parser)
                .as("parser and schema must agree (parser=%s, schema=%s) for body:%n%s",
                        parser, schema, body)
                .isEqualTo(schema);
        assertThat(parser)
                .as("expected the corpus case to be %s:%n%s", expectedAccepted ? "accepted" : "rejected", body)
                .isEqualTo(expectedAccepted);
    }

    // --- the corpus ------------------------------------------------------------------------

    private record CorpusCase(String name, String body, boolean accepted) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static CorpusCase accepted(final String name, final String body) {
        return new CorpusCase(name, body, true);
    }

    private static CorpusCase rejected(final String name, final String body) {
        return new CorpusCase(name, body, false);
    }

    static Stream<CorpusCase> corpus() {
        return Stream.of(
                // --- accepted -----------------------------------------------------------
                accepted("canonical body", render(canonicalFields())),
                accepted("uppercase-hex identifiers",
                        render(canonicalFields()).toUpperCase(java.util.Locale.ROOT)
                                .replace("\"SOURCE\"", "\"source\"")
                                .replace("\"REQUESTID\"", "\"requestId\"")
                                .replace("\"HEARINGID\"", "\"hearingId\"")
                                .replace("\"HEARINGDAY\"", "\"hearingDay\"")
                                .replace("\"SHAREDTIME\"", "\"sharedTime\"")
                                .replace("\"EVENTTYPE\"", "\"eventType\"")
                                .replace("\"HEARING_RESULTED\"", "\"Hearing_Resulted\"")),
                accepted("instant bearing a positive offset",
                        bodyWith("sharedTime", "\"2026-08-20T10:00:00+01:00\"")),
                accepted("instant bearing a negative offset",
                        bodyWith("sharedTime", "\"2026-08-20T04:00:00-05:00\"")),
                accepted("instant with fractional seconds",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00.123456Z\"")),
                accepted("instant with nanosecond precision",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00.123456789Z\"")),
                accepted("zero offset written out rather than as Z",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00+00:00\"")),
                // RFC 3339 permits the lower-case forms, and so must this parser: rejecting them
                // would be a new divergence from the schema, not a tightening.
                accepted("lower-case date-time separator",
                        bodyWith("sharedTime", "\"2026-08-20t09:00:00Z\"")),
                accepted("lower-case zulu designator",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00z\"")),
                accepted("leap day in a leap year", bodyWith("hearingDay", "\"2024-02-29\"")),

                // --- rejected: dates that do not exist ----------------------------------
                rejected("day beyond the month's length", bodyWith("hearingDay", "\"2026-02-30\"")),
                rejected("month beyond twelve", bodyWith("hearingDay", "\"2026-13-01\"")),
                rejected("leap day in a common year", bodyWith("hearingDay", "\"2025-02-29\"")),
                rejected("date in a non-ISO layout", bodyWith("hearingDay", "\"20/08/2026\"")),
                // Java's ISO date parser accepts a signed, expanded year; RFC 3339 does not, so the
                // schema rejects both of these and the parser must too.
                rejected("date with an expanded positive year", bodyWith("hearingDay", "\"+12026-08-20\"")),
                rejected("date with a signed negative year", bodyWith("hearingDay", "\"-0001-08-20\"")),
                rejected("date with an unpadded month", bodyWith("hearingDay", "\"2026-8-20\"")),

                // --- rejected: non-canonical identifiers --------------------------------
                rejected("braced identifier",
                        bodyWith("requestId", "\"{3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8}\"")),
                rejected("unhyphenated identifier",
                        bodyWith("hearingId", "\"1111111122224333844455555555 5555\"".replace(" ", ""))),
                rejected("identifier that is not a UUID at all",
                        bodyWith("requestId", "\"not-a-uuid\"")),

                // --- rejected: instants -------------------------------------------------
                rejected("instant with no offset at all",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00\"")),
                rejected("instant that is not a date-time", bodyWith("sharedTime", "\"yesterday\"")),
                // Java's ISO offset parser is looser than RFC 3339 in four separate ways. Each has
                // its own case, because each is a way a producer's clock library could drift out of
                // contract without anything noticing.
                rejected("offset carrying seconds",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00+01:00:30\"")),
                rejected("instant with no seconds", bodyWith("sharedTime", "\"2026-08-20T09:00Z\"")),
                rejected("instant with an expanded year",
                        bodyWith("sharedTime", "\"+12026-08-20T09:00:00Z\"")),
                rejected("negative zero offset",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00-00:00\"")),
                rejected("fractional part with no digits",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00.Z\"")),
                rejected("offset with no colon", bodyWith("sharedTime", "\"2026-08-20T09:00:00+0100\"")),
                // A second numbered 60 is only grammatical at an instant where a leap second was
                // actually inserted. These two are not such instants, and both sides refuse them —
                // which is what shows the validator consults a leap-second table rather than waving
                // through any ":60". The instants that WERE leap seconds are a documented divergence;
                // see documentedValidatorLeniency below.
                rejected("second 60 on an ordinary day",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:60Z\"")),
                rejected("second 60 at a plausible but unannounced leap instant",
                        bodyWith("sharedTime", "\"2026-06-30T23:59:60Z\"")),
                rejected("space separator with no offset",
                        bodyWith("sharedTime", "\"2026-08-20 09:00:00\"")),

                // --- rejected: empties, nulls and wrong types ---------------------------
                rejected("empty source", bodyWith("source", "\"\"")),
                rejected("empty requestId", bodyWith("requestId", "\"\"")),
                rejected("null source", bodyWith("source", "null")),
                rejected("null requestId", bodyWith("requestId", "null")),
                rejected("source as a number", bodyWith("source", "42")),
                rejected("hearingDay as a number", bodyWith("hearingDay", "20260820")),

                // --- rejected: enumerations and closedness ------------------------------
                rejected("source outside the agreed enumeration", bodyWith("source", "\"SJP\"")),
                rejected("eventType outside the agreed enumeration",
                        bodyWith("eventType", "\"SJP_Resulted\"")),
                rejected("one unknown field on an otherwise valid body",
                        render(withExtraField("courtCentreId", "\"abc\""))),

                // --- rejected: not an object at all -------------------------------------
                rejected("body that is not JSON", "this is not json"),
                rejected("body that is a JSON array", "[]"),
                rejected("body that is a JSON string", "\"hello\""));
    }

    private static Map<String, String> withExtraField(final String name, final String rawValue) {
        final Map<String, String> fields = canonicalFields();
        fields.put(name, rawValue);
        return fields;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("parser and schema agree on every boundary case")
    void parser_and_schema_should_agree(final CorpusCase corpusCase) {
        assertAgreement(corpusCase.body(), corpusCase.accepted());
    }

    // --- documented validator leniency ------------------------------------------------------

    /**
     * A form the reference validator accepts and this service's parser deliberately refuses.
     *
     * @param name      how the case reads in the test report
     * @param body      the message body
     * @param rationale why the parser stays stricter — stated per case, so the exemption cannot be
     *                  extended later without someone writing down a reason
     */
    private record LenientCase(String name, String body, String rationale) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The complete list of forms on which the parser is deliberately stricter than the reference
     * validator, each with its reason.
     *
     * <p>This is an exemption list, so it is closed by construction: {@link #corpus()} keeps the
     * strict agreement assertion, and only the bodies enumerated here are excused from it. A new
     * divergence appearing anywhere else still fails the build, and a case listed here is asserted
     * in <em>both</em> directions — validator accepts, parser rejects — so if a validator upgrade
     * changes its mind, this fails too and the case gets reclassified rather than quietly rotting.
     *
     * <p>The validator here is networknt 3.0.7, which delegates {@code date-time} to
     * {@code com.ethlo.time:itu}. Both leniencies below were confirmed by running them through it,
     * not inferred from its documentation.
     */
    static Stream<LenientCase> documentedValidatorLeniency() {
        return Stream.of(
                new LenientCase("space-separated date-time, zulu",
                        bodyWith("sharedTime", "\"2026-08-20 09:00:00Z\""),
                        "RFC 3339's grammar is date-time = full-date \"T\" full-time. The space form "
                                + "appears only in a readability note in section 5.6, which permits "
                                + "applications to accept it — it is not the ABNF, and draft-07's "
                                + "date-time format means the ABNF. Accepting it here would widen this "
                                + "service's contract past what the schema document states."),
                new LenientCase("space-separated date-time, numeric offset",
                        bodyWith("sharedTime", "\"2026-08-20 09:00:00+01:00\""),
                        "Same as above; listed separately because the separator and the offset form "
                                + "are independent, and a fix that handled one and not the other would "
                                + "otherwise go unnoticed."),
                new LenientCase("verified leap second, 31 December 2016",
                        bodyWith("sharedTime", "\"2016-12-31T23:59:60Z\""),
                        "Grammatical under RFC 3339, but unrepresentable in java.time: there is no "
                                + "Instant for a 61st second, so accepting it would mean inventing a "
                                + "value to store. Deciding it is valid also requires a third-party "
                                + "leap-second table, which is a moving dependency to put on the "
                                + "contract's hot path."),
                new LenientCase("verified leap second, 30 June 2015",
                        bodyWith("sharedTime", "\"2015-06-30T23:59:60Z\""),
                        "As above. A second verified instant is included so the case group cannot "
                                + "pass by coincidence of one date."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentedValidatorLeniency")
    @DisplayName("the parser is deliberately stricter than the validator, and only here")
    void a_documented_leniency_should_be_accepted_by_the_validator_and_refused_by_the_parser(
            final LenientCase lenientCase) {
        // Nothing the publisher can emit reaches either form: cpp-context-results serialises with
        // Instant.toString(), which always writes a T separator and never a 61st second. So the
        // divergence costs no real message, and closing it would cost either correctness (inventing
        // an Instant) or contract width (accepting a form the schema's grammar excludes).
        assertThat(schemaAccepts(lenientCase.body()))
                .as("the reference validator is expected to accept this form — %s", lenientCase.rationale())
                .isTrue();
        assertThat(parserAccepts(lenientCase.body()))
                .as("the parser is expected to refuse this form — %s", lenientCase.rationale())
                .isFalse();
    }

    @Test
    @DisplayName("the leniency list exempts nothing the agreement corpus already covers")
    void the_leniency_list_should_not_overlap_the_agreement_corpus() {
        // Keeps the exemption honest: a body cannot be asserted to agree and to diverge at once, and
        // a case cannot be moved onto the exemption list while still appearing to be covered.
        final List<String> lenientBodies = documentedValidatorLeniency().map(LenientCase::body).toList();
        final List<String> corpusBodies = corpus().map(CorpusCase::body).toList();

        assertThat(lenientBodies).isNotEmpty().doesNotContainAnyElementsOf(corpusBodies);
    }

    // --- assertions taken mechanically from the schema document -----------------------------

    private static List<String> requiredFieldNames() {
        final List<String> names = new ArrayList<>();
        SCHEMA_DOCUMENT.get("required").forEach(node -> names.add(node.stringValue()));
        return names;
    }

    private static List<String> enumValuesOf(final String field) {
        final List<String> values = new ArrayList<>();
        SCHEMA_DOCUMENT.get("properties").get(field).get("enum").forEach(node -> values.add(node.stringValue()));
        return values;
    }

    static Stream<String> requiredFields() {
        return requiredFieldNames().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredFields")
    @DisplayName("every field the schema requires is required by the parser too")
    void a_body_missing_a_required_field_should_be_rejected_by_both(final String field) {
        assertAgreement(bodyWithout(field), false);
    }

    private static final List<String> AGREED_FIELDS = List.of(
            "source", "requestId", "hearingId", "hearingDay", "sharedTime", "eventType");

    private static List<String> declaredPropertyNames() {
        return List.copyOf(SCHEMA_DOCUMENT.get("properties").propertyNames());
    }

    static Stream<String> declaredProperties() {
        return declaredPropertyNames().stream();
    }

    @Test
    @DisplayName("the schema declares exactly the six agreed fields as required")
    void the_schema_should_require_the_six_agreed_fields() {
        assertThat(requiredFieldNames()).containsExactlyInAnyOrder(AGREED_FIELDS.toArray(new String[0]));
    }

    @Test
    @DisplayName("the schema declares exactly the six agreed fields, and no seventh")
    void the_schema_should_declare_no_property_beyond_the_six() {
        // Without this, a seventh property added to the schema as optional would sail through: the
        // required-field assertions would not notice it, and the corpus only knows the unknown-field
        // names it was written with. The parser would reject a body carrying it while the schema
        // accepted one — a silent divergence, which is the exact failure this suite exists to stop.
        assertThat(declaredPropertyNames()).containsExactlyInAnyOrder(AGREED_FIELDS.toArray(new String[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredProperties")
    @DisplayName("every property the schema declares is also required by it")
    void a_declared_property_should_also_be_required(final String field) {
        assertThat(requiredFieldNames())
                .as("an optional property is a property the parser would reject and the schema accept")
                .contains(field);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredProperties")
    @DisplayName("every declared property is exercised through both validators")
    void a_declared_property_holding_a_wrong_typed_value_should_be_rejected_by_both(final String field) {
        // Drives each declared property through both validators by name rather than by a
        // hand-written list, so a property added to the schema is exercised the moment it appears.
        assertAgreement(bodyWith(field, "{}"), false);
        assertAgreement(bodyWithout(field), false);
    }

    @Test
    @DisplayName("the parser accepts every source the schema enumerates")
    void every_enumerated_source_should_be_accepted_by_both() {
        assertThat(enumValuesOf("source")).isNotEmpty();
        enumValuesOf("source").forEach(value -> assertAgreement(bodyWith("source", "\"" + value + "\""), true));
    }

    @Test
    @DisplayName("the parser accepts every eventType the schema enumerates")
    void every_enumerated_event_type_should_be_accepted_by_both() {
        assertThat(enumValuesOf("eventType")).isNotEmpty();
        enumValuesOf("eventType")
                .forEach(value -> assertAgreement(bodyWith("eventType", "\"" + value + "\""), true));
    }

    @Test
    @DisplayName("the contract is closed, and the parser enforces the closure")
    void the_schema_should_be_closed_and_the_parser_should_agree() {
        assertThat(SCHEMA_DOCUMENT.get("additionalProperties").booleanValue()).isFalse();
        assertAgreement(render(withExtraField("somethingNew", "\"value\"")), false);
    }
}
