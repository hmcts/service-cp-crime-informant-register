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
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.informantregister.support.JsonParity;

/**
 * The JUnit twins of the legacy {@code OutboundInformantRegister} activity's own Jest suite, plus the
 * recipient cases that hang off it.
 *
 * <p>Three activity twins and three recipient twins, in the order the two legacy files declare them,
 * run against byte-identical copies of the four fixtures they load. These are the only legacy cases
 * that exercise the whole mapper tree at once, so they are where the document's shape is asserted
 * rather than any single mapper's.
 *
 * <p>The pinned oddities of the top level live here too: the file name's second reading of the
 * register date, the twelve components the legacy sends against the ten its model declares, and the
 * D9 register date the file name is derived from.
 */
@DisplayName("AggregationMapper — parity with the legacy OutboundInformantRegister")
class AggregationMapperTest {

    /** No case here reads the clock; the fixed value only makes that visible. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2021-06-15T09:30:00Z"), ZoneOffset.UTC);

    private static final String ACTIVITY = "/fixtures/outboundinformantregister/activity/";
    private static final String MAPPER = "/fixtures/outboundinformantregister/mapper/";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final AggregationMapper aggregationMapper =
            new AggregationMapper(new HearingDates(FROZEN));

    @Nested
    @DisplayName("Outbound informant register correctly")
    class LegacyTwins {

        @Test
        @DisplayName("build informant register request with matched subscriptions")
        void a_fragment_with_a_matched_subscription_should_carry_its_recipient() {
            final InformantRegisterDocument document = build(
                    "hearing.json", "informantRegisterWithMatchedSubscriptions.json");

            assertThat(document.registerDate()).hasToString("2020-01-20T11:00Z");
            assertThat(document.hearingId())
                    .hasToString("1828f356-f746-4f2d-932b-79ef2df95c80");
            assertThat(document.groupId())
                    .hasToString("4828f356-f746-4f2d-932b-79ef2df95c81");
            assertThat(document.hearingDate()).hasToString("2020-01-20T10:00Z");
            assertThat(document.prosecutionAuthorityId())
                    .hasToString("31af405e-7b60-4dd8-a244-c24c2d3fa595");
            assertThat(document.prosecutionAuthorityCode()).isEqualTo("TFL");
            assertThat(document.prosecutionAuthorityOuCode()).isEqualTo("GAFTL00");
            assertThat(document.majorCreditorCode()).isEqualTo("OUCode");
            assertThat(document.prosecutionAuthorityName()).isEqualTo("Barnet county court");
            assertThat(document.fileName()).isEqualTo("InformantRegister_TFL_2020-01-20.csv");

            assertThat(document.recipients()).hasSize(1);
            assertThat(document.recipients().getFirst().recipientName()).isEqualTo("Fred Smith");
            assertThat(document.recipients().getFirst().emailAddress1())
                    .isEqualTo("test@hmcst.net");
            assertThat(document.recipients().getFirst().emailTemplateName())
                    .isEqualTo("ir_standard");

            assertDefendantsCarryOneCaseEach(document);
        }

        @Test
        @DisplayName("build informant register request without matched subscriptions")
        void a_fragment_with_no_matched_subscription_should_carry_no_recipients() {
            final InformantRegisterDocument document = build(
                    "hearing.json", "informantRegisterWithoutMatchedSubscriptions.json");

            assertThat(document.registerDate()).hasToString("2020-01-20T11:00Z");
            assertThat(document.hearingId())
                    .hasToString("1828f356-f746-4f2d-932b-79ef2df95c80");
            assertThat(document.hearingDate()).hasToString("2020-01-20T10:00Z");
            assertThat(document.prosecutionAuthorityCode()).isEqualTo("TFL");
            assertThat(document.prosecutionAuthorityOuCode()).isEqualTo("GAFTL00");
            assertThat(document.majorCreditorCode()).isEqualTo("OUCode");
            assertThat(document.prosecutionAuthorityName()).isEqualTo("Barnet county court");
            assertThat(document.fileName()).isEqualTo("InformantRegister_TFL_2020-01-20.csv");

            assertThat(document.recipients()).isNull();
            assertDefendantsCarryOneCaseEach(document);
        }

        @Test
        @DisplayName("build informant register request without masterdefandent under subject")
        void a_hearing_whose_application_has_no_master_defendant_still_maps_its_cases() {
            final InformantRegisterDocument document = build(
                    "hearing-with-application.json",
                    "informantRegisterWithoutMatchedSubscriptions.json");

            assertThat(document.registerDate()).hasToString("2020-01-20T11:00Z");
            assertThat(document.hearingId())
                    .hasToString("1828f356-f746-4f2d-932b-79ef2df95c80");
            assertThat(document.hearingDate()).hasToString("2020-01-20T10:00Z");
            assertThat(document.prosecutionAuthorityCode()).isEqualTo("TFL");
            assertThat(document.fileName()).isEqualTo("InformantRegister_TFL_2020-01-20.csv");

            assertThat(document.recipients()).isNull();
            assertDefendantsCarryOneCaseEach(document);
        }
    }

    @Nested
    @DisplayName("Golden parity — the whole document, against output captured from the legacy")
    class GoldenParity {

        /**
         * The three activity cases again, this time compared whole rather than field by field.
         *
         * <p><strong>Provenance of the goldens.</strong> Produced by invoking
         * {@code OutboundInformantRegister/index.js} directly, once per case, with the same two
         * fixtures the Jest case loads and a logging-only context. Nothing was written by hand and
         * nothing was adjusted afterwards. The transcribed assertions above check the handful of
         * fields the Jest author happened to name; these check every field of every offence of every
         * case of every defendant, which is where a port actually goes wrong.
         *
         * <p>The comparison is the repository's own — field-order-insensitive, array-order-sensitive,
         * BigDecimal-tolerant — and it reports an unexpected field as loudly as a missing one, so a
         * component this port invents fails just as a component it drops does.
         */
        @Test
        @DisplayName("with matched subscriptions")
        void case_01_matches_the_legacy_document() {
            assertGolden("case-01-with-matched-subscriptions",
                    "hearing.json", "informantRegisterWithMatchedSubscriptions.json");
        }

        @Test
        @DisplayName("without matched subscriptions")
        void case_02_matches_the_legacy_document() {
            assertGolden("case-02-without-matched-subscriptions",
                    "hearing.json", "informantRegisterWithoutMatchedSubscriptions.json");
        }

        @Test
        @DisplayName("without a master defendant under the application's subject")
        void case_03_matches_the_legacy_document() {
            assertGolden("case-03-application-without-master-defendant",
                    "hearing-with-application.json",
                    "informantRegisterWithoutMatchedSubscriptions.json");
        }

        /**
         * Serialises the ported document and compares it with the captured legacy output.
         *
         * @param golden          the golden file name, without its extension
         * @param hearingFixture  the hearing fixture file name
         * @param fragmentFixture the fragment-array fixture file name
         */
        private void assertGolden(
                final String golden,
                final String hearingFixture,
                final String fragmentFixture) {

            final JsonNode expected =
                    fixture("/fixtures/outboundinformantregister/expected/", golden + ".json")
                            .get(0);
            final JsonNode actual = mapper.valueToTree(build(hearingFixture, fragmentFixture));

            JsonParity.assertMatches(expected, actual, golden);
        }
    }

    @Nested
    @DisplayName("Recipient Mapper should build recipients correctly")
    class RecipientTwins {

        /**
         * The legacy case passes the whole register-fragment array where the mapper expects matched
         * subscriptions, so every member fails the {@code forDistribution} test and the answer is
         * "no recipients". The twin feeds the same fixture, unchanged, and asserts the same answer —
         * the case is worth keeping precisely because it shows what the mapper does with a member
         * that names none of the three flags.
         */
        @Test
        @DisplayName("Recipient should be null when there is no recipient")
        void members_that_are_not_subscriptions_produce_no_recipients() {
            final JsonNode notSubscriptions = fixture(MAPPER, "informantRegisterSubscriptions.json");

            assertThat(new RecipientMapper(notSubscriptions.valueStream().toList()).build())
                    .isNull();
        }

        @Test
        @DisplayName("should parse recipient.emailAddress1 if emailAddress not null or empty")
        void an_address_with_trailing_whitespace_is_trimmed() {
            final List<JsonNode> subscriptions = subscriptions("""
                    [{"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"Sodexo",
                                   "emailAddress1":"BF.ReceptionSenior@sodexogov.co.uk  "}}]""");

            assertThat(new RecipientMapper(subscriptions).build().getFirst().emailAddress1())
                    .isEqualTo("BF.ReceptionSenior@sodexogov.co.uk");
        }

        @Test
        @DisplayName("should not parse recipient.emailAddress1 if emailAddress null or empty")
        void a_null_address_is_left_alone_rather_than_trimmed() {
            final List<JsonNode> subscriptions = subscriptions("""
                    [{"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"Sodexo","emailAddress1":null}}]""");

            assertThat(new RecipientMapper(subscriptions).build().getFirst().emailAddress1())
                    .isNull();
        }

        /**
         * Deviation 11, at the one component where an explicit null survives the mapper. The legacy
         * assigns the null and pushes the recipient, so {@code JSON.stringify} writes
         * {@code "emailAddress1":null} — a null inside a component the frozen contract types as a
         * required string. {@code @JsonInclude(NON_NULL)} omits the key instead, which is the same
         * choice deviation 9 records for {@code verdictType}, applied by the same annotation across
         * the whole outbound tree.
         *
         * <p>Asserted on the serialised body rather than on the record, because the record cannot
         * tell the two apart and the divergence is only visible on the wire.
         */
        @Test
        @DisplayName("deviation 11 — an explicit null address is an omitted key, not a null one")
        void a_null_address_is_omitted_from_the_wire_rather_than_written_as_null() {
            final List<JsonNode> subscriptions = subscriptions("""
                    [{"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"Sodexo","emailAddress1":null}}]""");

            final JsonNode serialised =
                    mapper.valueToTree(new RecipientMapper(subscriptions).build().getFirst());

            assertThat(serialised.has("emailAddress1")).isFalse();
            assertThat(serialised.get("organisationName")).isNull();
            assertThat(serialised.get("recipientName").stringValue()).isEqualTo("Sodexo");
        }

        /**
         * {@code subscription.forDistribution} (`RecipientMapper.js:15`) is read straight off the
         * array member, so a null in {@code matchedSubscriptions} is a {@code TypeError} and the
         * hearing produces nothing at all. Skipping the member instead would still emit a document
         * — the one direction this port must not drift in.
         */
        @Test
        @DisplayName("a null matched subscription is refused, not skipped")
        void a_null_matched_subscription_is_refused() {
            assertThatThrownBy(() -> new RecipientMapper(subscriptions("[null]")).build())
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * {@code emailAddress.trim()} is ECMAScript's trim, which strips the non-breaking space
         * {@code U+00A0}; neither {@link String#trim()} nor {@link String#strip()} does. An address
         * pasted into reference data behind one would otherwise reach GOV.UK Notify with it still
         * attached.
         */
        @Test
        @DisplayName("a non-breaking space around an address is trimmed, as ECMAScript trims it")
        void a_non_breaking_space_around_an_address_is_trimmed() {
            final String nonBreakingSpace = Character.toString(0x00A0);
            final List<JsonNode> subscriptions = subscriptions("""
                    [{"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"Sodexo",
                                   "emailAddress1":"%1$strimmed@pinning.invalid%1$s"}}]"""
                    .formatted(nonBreakingSpace));

            assertThat(new RecipientMapper(subscriptions).build().getFirst().emailAddress1())
                    .isEqualTo("trimmed@pinning.invalid");
        }
    }

    @Nested
    @DisplayName("Pinned oddities — a correction here needs a deviations-register entry first")
    class PinnedOddities {

        /**
         * Parity-pack pinning case {@code d17-letter-delivery-ignored-and-email-rules}, at this
         * mapper's own level. Five subscriptions, one rule each, and each assertion is its own so a
         * failure names itself. Two are traps: a subscription carrying <em>both</em> email and
         * first-class letter delivery is included, because the letter check sits outside the email
         * branch; and a distributing email subscription whose recipient has no address disappears
         * silently, which is the KEEP.
         */
        @Test
        @DisplayName("d17 — letter delivery is ignored, and a recipient with no address is dropped")
        void letter_delivery_is_ignored_and_an_addressless_recipient_is_dropped() {
            final List<JsonNode> subscriptions = subscriptions("""
                    [{"forDistribution":true,"emailDelivery":true,"firstClassLetterDelivery":true,
                      "emailTemplateName":"tpl_named",
                      "recipient":{"organisationName":"email-plus-first-class-letter",
                                   "emailAddress1":"first@pinning.invalid"}},
                     {"forDistribution":true,"emailDelivery":false,
                      "secondClassLetterDelivery":true,
                      "recipient":{"organisationName":"letter-only-no-email-delivery",
                                   "emailAddress1":"letter@pinning.invalid"}},
                     {"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"no-template-name",
                                   "emailAddress1":"second@pinning.invalid"}},
                     {"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"email-delivery-without-address"}},
                     {"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"trimmed-and-second-address",
                                   "emailAddress1":"  trimmed@pinning.invalid  ",
                                   "emailAddress2":" also@pinning.invalid "}}]""");

            final var recipients = new RecipientMapper(subscriptions).build();

            assertThat(recipients).hasSize(3);
            assertThat(recipients.get(0).recipientName())
                    .isEqualTo("email-plus-first-class-letter");
            assertThat(recipients.get(0).emailTemplateName()).isEqualTo("tpl_named");
            assertThat(recipients.get(0).emailAddress2()).isNull();
            assertThat(recipients.get(1).recipientName()).isEqualTo("no-template-name");
            assertThat(recipients.get(1).emailTemplateName()).isEqualTo("ir_standard");
            assertThat(recipients.get(2).recipientName())
                    .isEqualTo("trimmed-and-second-address");
            assertThat(recipients.get(2).emailAddress1())
                    .isEqualTo("trimmed@pinning.invalid");
            assertThat(recipients.get(2).emailAddress2()).isEqualTo("also@pinning.invalid");
            assertThat(recipients).extracting(recipient -> recipient.recipientName())
                    .doesNotContain("letter-only-no-email-delivery")
                    .doesNotContain("email-delivery-without-address");
        }

        /**
         * The recipient-drop guard is narrower than the design register describes: an absent address
         * drops the recipient, a null or empty one does not. Both halves asserted together, because
         * the difference between them is the whole of the finding (parity-pack BS-09).
         */
        @Test
        @DisplayName("BS-09 — only an absent address drops a recipient; a blank one does not")
        void only_an_absent_address_drops_a_recipient() {
            final List<JsonNode> subscriptions = subscriptions("""
                    [{"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"absent"}},
                     {"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"null","emailAddress1":null}},
                     {"forDistribution":true,"emailDelivery":true,
                      "recipient":{"organisationName":"empty","emailAddress1":""}}]""");

            final var recipients = new RecipientMapper(subscriptions).build();

            assertThat(recipients).hasSize(2);
            assertThat(recipients.get(0).recipientName()).isEqualTo("null");
            assertThat(recipients.get(0).emailAddress1()).isNull();
            assertThat(recipients.get(1).recipientName()).isEqualTo("empty");
            assertThat(recipients.get(1).emailAddress1()).isEmpty();
        }

        /**
         * Parity-pack pinning case {@code d09-bst-local-time-labelled-as-utc}, the file-name half.
         * A summer register date makes the register date the string {@code 2020-06-01T11:00:00Z} —
         * an hour on from the instant it names — and the file name is derived by reading that value
         * a second time as a London day.
         */
        @Test
        @DisplayName("d09 — a summer register date and the file name derived from it")
        void a_summer_register_date_and_its_file_name() {
            final RegisterFragment fragment = new RegisterFragment(
                    "2020-06-01T11:00:00Z", null, null,
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", "TFL", null, null, null,
                    List.of(), null);

            final InformantRegisterDocument document = aggregationMapper.build(
                    minimalHearing(), fragment, List.of());

            assertThat(document.registerDate()).hasToString("2020-06-01T11:00Z");
            assertThat(document.fileName()).isEqualTo("InformantRegister_TFL_2020-06-01.csv");
        }

        /**
         * An authority with no code does not produce a shorter file name. JavaScript concatenates
         * the absent value as the six letters {@code undefined}, and the register is filed under
         * that name; writing {@code null} instead — the natural Java answer — would be a different
         * file name for the same hearing.
         */
        @Test
        @DisplayName("an authority with no code is filed under the six letters JavaScript prints")
        void an_authority_without_a_code_is_filed_as_undefined() {
            final RegisterFragment fragment = new RegisterFragment(
                    "2020-06-01T11:00:00Z", null, null,
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", null, null, null, null,
                    List.of(), null);

            assertThat(aggregationMapper.build(minimalHearing(), fragment, List.of()).fileName())
                    .isEqualTo("InformantRegister_undefined_2020-06-01.csv");
        }

        /**
         * The twelve components the activity assigns, against the ten its model declares. Both of the
         * undeclared two are valid against the frozen contract, so the payload was always right and
         * the model was always incomplete — a port that trusted the model would drop them.
         */
        @Test
        @DisplayName("the two components the legacy model does not declare are still sent")
        void the_undeclared_components_are_carried() {
            final InformantRegisterDocument document = build(
                    "hearing.json", "informantRegisterWithMatchedSubscriptions.json");

            assertThat(document.majorCreditorCode()).isEqualTo("OUCode");
            assertThat(document.prosecutionAuthorityName()).isEqualTo("Barnet county court");
        }
    }

    @Nested
    @DisplayName("The typed boundary (deviations entry 10)")
    class TypedBoundary {

        /**
         * Deviation 10. The fragment's identifiers and timestamps are strings; the consumer's binding
         * types them as {@code UUID} and {@code ZonedDateTime}. A value that is not of that shape has
         * nowhere to go in the typed tree, so the delivery is parked rather than POSTed for the
         * consumer's schema to reject.
         */
        @Test
        @DisplayName("deviation 10 — a hearing id that is not a UUID is refused, not sent")
        void a_hearing_id_that_is_not_an_identifier_is_refused() {
            final RegisterFragment fragment = new RegisterFragment(
                    "2020-06-01T11:00:00Z", null, "not-a-uuid",
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", "TFL", null, null, null,
                    List.of(), null);

            assertThatThrownBy(() ->
                    aggregationMapper.build(minimalHearing(), fragment, List.of()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * Deviation 10, at the boundary {@link java.util.UUID#fromString} does not police.
         * {@code UUID.fromString("1-1-1-1-1")} parses, and renders back as
         * {@code 00000001-0001-0001-0001-000000000001} — an identifier the payload never sent, filed
         * against a hearing nobody has. The legacy forwards the value as it stands and the
         * consumer's {@code format: uuid} rejects it, so no register reaches the authority either
         * way; inventing one would be the only outcome worse than parking it.
         */
        @Test
        @DisplayName("deviation 10 — a shorthand identifier is refused, not padded out into a UUID")
        void a_shorthand_identifier_is_refused_rather_than_normalised() {
            final RegisterFragment fragment = new RegisterFragment(
                    "2020-06-01T11:00:00Z", null, "1-1-1-1-1",
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", "TFL", null, null, null,
                    List.of(), null);

            assertThatThrownBy(() ->
                    aggregationMapper.build(minimalHearing(), fragment, List.of()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * The other half of the same boundary, and the one that is a divergence rather than a
         * refusal: an upper-case identifier is well-formed, the consumer's schema accepts it, the
         * legacy forwards it unchanged — and a {@link java.util.UUID} has only one rendering, so it
         * comes back lower-cased. Recorded on deviation 10 rather than corrected, because refusing a
         * value the legacy sends successfully would lose a register.
         */
        @Test
        @DisplayName("deviation 10 — an upper-case identifier is carried, lower-cased")
        void an_upper_case_identifier_is_carried_in_lower_case() {
            final RegisterFragment fragment = new RegisterFragment(
                    "2020-06-01T11:00:00Z", null, "1828F356-F746-4F2D-932B-79EF2DF95C80",
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", "TFL", null, null, null,
                    List.of(), null);

            final InformantRegisterDocument document =
                    aggregationMapper.build(minimalHearing(), fragment, List.of());

            assertThat(document.hearingId())
                    .hasToString("1828f356-f746-4f2d-932b-79ef2df95c80");
        }

        /**
         * Deviation 10, the timestamp half. {@code "Invalid dateZ"} is exactly what
         * {@link HearingDates#localDateTime} renders for a shared time it cannot read, so this is
         * the value a real fragment carries when the payload's date is unreadable — and it is the
         * value this boundary refuses, because {@link java.time.ZonedDateTime} has nowhere to put
         * it.
         */
        @Test
        @DisplayName("deviation 10 — a register date that is not a timestamp is refused, not sent")
        void a_register_date_that_is_not_a_timestamp_is_refused() {
            final RegisterFragment fragment = new RegisterFragment(
                    "Invalid dateZ", null, null,
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", "TFL", null, null, null,
                    List.of(), null);

            assertThatThrownBy(() ->
                    aggregationMapper.build(minimalHearing(), fragment, List.of()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * An absent component stays absent. The contract requires most of these, but the legacy sends
         * a body without them when the hearing had none, and inventing a value would be a different
         * register rather than a missing one.
         */
        @Test
        @DisplayName("components the fragment does not carry are absent, not invented")
        void absent_components_stay_absent() {
            final RegisterFragment fragment = new RegisterFragment(
                    "2020-06-01T11:00:00Z", null, null,
                    "31af405e-7b60-4dd8-a244-c24c2d3fa595", "TFL", null, null, null,
                    List.of(), null);

            final InformantRegisterDocument document =
                    aggregationMapper.build(minimalHearing(), fragment, List.of());

            assertThat(document.hearingDate()).isNull();
            assertThat(document.hearingId()).isNull();
            assertThat(document.groupId()).isNull();
            assertThat(document.recipients()).isNull();
            assertThat(document.majorCreditorCode()).isNull();
        }
    }

    /**
     * Both defendants of the activity fixtures carry exactly one case, referenced the same way.
     *
     * @param document the mapped document
     */
    private static void assertDefendantsCarryOneCaseEach(
            final InformantRegisterDocument document) {
        final var defendants = document.hearingVenue().courtSessions().getFirst().defendants();
        assertThat(defendants).hasSize(2);
        assertThat(defendants.get(0).prosecutionCasesOrApplications()).hasSize(1);
        assertThat(defendants.get(0).prosecutionCasesOrApplications().getFirst()
                .caseOrApplicationReference()).isEqualTo("TFL4359536");
        assertThat(defendants.get(1).prosecutionCasesOrApplications()).hasSize(1);
        assertThat(defendants.get(1).prosecutionCasesOrApplications().getFirst()
                .caseOrApplicationReference()).isEqualTo("TFL4359536");
    }

    /**
     * Runs the mapper over one activity fixture pair, for the pair's single fragment.
     *
     * @param hearingFixture  the hearing fixture file name
     * @param fragmentFixture the fragment-array fixture file name
     * @return the mapped document
     */
    private InformantRegisterDocument build(
            final String hearingFixture, final String fragmentFixture) {
        final JsonNode fragments = fixture(ACTIVITY, fragmentFixture);
        final JsonNode declared = fragments.get(0);
        return aggregationMapper.build(
                fixture(ACTIVITY, hearingFixture),
                ModelObjects.fragmentFrom(declared),
                ModelObjects.matchedSubscriptionsFrom(declared));
    }

    /**
     * A hearing with just enough in it for the venue mapper to answer.
     *
     * @return the hearing tree
     */
    private JsonNode minimalHearing() {
        final var hearing = ModelObjects.hearing();
        hearing.set("hearingDays", ModelObjects.array());
        return hearing;
    }

    /**
     * Parses a subscription array written inline.
     *
     * @param json the array text
     * @return the subscriptions
     */
    private List<JsonNode> subscriptions(final String json) {
        return mapper.readTree(json).valueStream().toList();
    }

    /**
     * Reads one of the byte-identical fixture copies.
     *
     * @param directory the fixture directory
     * @param name      the fixture file name
     * @return the parsed tree
     */
    private JsonNode fixture(final String directory, final String name) {
        final String resource = directory + name;
        try (InputStream source = AggregationMapperTest.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return mapper.readTree(source);
        } catch (java.io.IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
