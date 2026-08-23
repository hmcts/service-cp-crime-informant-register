package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.informantregister.support.JsonParity;
import uk.gov.hmcts.cp.informantregister.support.ParityCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The oddity pinning pack: twenty-six behaviours that look like bugs and must not be quietly fixed.
 *
 * <p>Every entry here mirrors one entry of {@code src/test/resources/parity/pinning/manifest.json},
 * by name, and asserts what that entry's {@code assertion} field prescribes. Nineteen are backed by
 * a recorded run of the real function app and assert against it; seven are outside the oracle's
 * reach and are {@link Disabled} carrying the manifest's own {@code blockedReason} and the test
 * level that <em>can</em> reach them.
 *
 * <p>The rule the pack enforces, and the reason this class is worth its length:
 *
 * <blockquote>A pinned behaviour changes only in a commit that also adds the corresponding row to
 * {@code doc/DEVIATIONS.md}.</blockquote>
 *
 * <p>Several of these behaviours are genuinely wrong. Correcting them is allowed. Correcting them
 * <strong>quietly</strong> is not — and the assertions are deliberately written from the angle of
 * "what would a competent Java developer naturally write instead, and would this catch them?".
 *
 * <p>Six of the nineteen are places where the port <em>does</em> differ, and each difference is a
 * registered deviation rather than a drift: four are hearings the legacy loses to a swallowed
 * exception (entry 7), one is a reference-data outage the legacy ships a recipient-less register
 * through (entry 14), and one is a value the typed outbound tree cannot carry (entry 10). Those are
 * asserted as the refusal the register records, not as the legacy's documents.
 *
 * <p>A seventh difference runs underneath {@link #matchesTheRecording}, on whichever of these cases
 * has a sitting day: {@code hearingStartTime} is now the London wall clock with London's true offset
 * ({@code doc/DEVIATIONS.md} entry 15), while the goldens keep the oracle's D9 rendering. The
 * comparator reconciles the two by deriving the required value from the golden rather than by
 * ignoring the component — see {@code RegisteredFieldDeviations}. Note that the {@code d09} pin
 * below is untouched by it: that entry asserts on {@code registerDate} and {@code fileName}, which
 * still carry D9's rendering.
 */
@DisplayName("Pinned oddities — a correction here needs a deviations-register entry first")
class PinnedOddityTest {

    private static final String CORPUS = "pinning";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    // --- running one pinned case -----------------------------------------------------------------

    private static ParityCase load(final String id) {
        return ParityCase.load(CORPUS, id);
    }

    private static List<InformantRegisterDocument> documents(final ParityCase parityCase) {
        return chain(parityCase).transform(
                parityCase.hearing(), parityCase.sharedTime(), CallerIdentity.SYSTEM);
    }

    /** The documents as they go on the wire, so key-absence can be asserted. */
    private static JsonNode wire(final ParityCase parityCase) {
        return MAPPER.valueToTree(documents(parityCase));
    }

    private static RegisterTransformationChain chain(final ParityCase parityCase) {
        final Clock clock = Clock.fixed(parityCase.clockPin(), ZoneOffset.UTC);
        final HearingDates dates = new HearingDates(clock);
        final NowSubscriptionsSource source = parityCase.refdata() == ParityCase.RefdataAnswer.REJECT
                ? PinnedOddityTest::refuse
                : (on, identity) -> parityCase.subscriptions();
        return new RegisterTransformationChain(
                new RegisterBuilder(dates),
                new SubscriptionMatcher(new SubscriptionRules()),
                new AggregationMapper(dates),
                source);
    }

    private static JsonNode refuse(final LocalDate on, final CallerIdentity identity) {
        throw new ReferenceDataUnavailableException(ReasonCode.REFERENCE_DATA_UNAVAILABLE);
    }

    /** Asserts the whole array against the recording, under the pack's comparator rules. */
    private static void matchesTheRecording(final ParityCase parityCase) {
        JsonParity.assertMatches(parityCase.expected(), wire(parityCase), parityCase.caseId());
    }

    /** The recipient names of one document, in order; empty when the component is absent. */
    private static List<String> recipientNames(final JsonNode document) {
        final List<String> names = new ArrayList<>();
        final JsonNode recipients = document.get("recipients");
        if (recipients != null) {
            recipients.forEach(recipient -> names.add(text(recipient, "recipientName")));
        }
        return names;
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null ? null : value.stringValue();
    }

    private static JsonNode defendants(final JsonNode document) {
        return document.get("hearingVenue").get("courtSessions").get(0).get("defendants");
    }

    // --- D3 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d03 — a reference-data outage is reported, not shipped as a register for nobody")
    void d03_refdata_failure_is_classified_rather_than_degraded() {
        final ParityCase parityCase = load("d03-refdata-failure-degrades-to-no-recipients");

        // What Node does, from the recording: two documents, for TFL and TVL, neither carrying a
        // `recipients` key at all. The outage is invisible in the outbound body.
        assertThat(parityCase.expected()).hasSize(2);
        assertThat(parityCase.expected().get(0).get("prosecutionAuthorityCode").stringValue())
                .isEqualTo("TFL");
        assertThat(parityCase.expected().get(1).get("prosecutionAuthorityCode").stringValue())
                .isEqualTo("TVL");
        assertThat(parityCase.expected().get(0).get("recipients")).isNull();
        assertThat(parityCase.expected().get(1).get("recipients")).isNull();

        // What the port does. The manifest is explicit that reproducing those documents is only
        // acceptable alongside a classified transient failure; refusing outright is the stronger
        // form of the same requirement, and it is doc/DEVIATIONS.md entry 14.
        assertThatThrownBy(() -> documents(parityCase))
                .isInstanceOf(ReferenceDataUnavailableException.class)
                .satisfies(failure -> {
                    final ReferenceDataUnavailableException outage =
                            (ReferenceDataUnavailableException) failure;
                    assertThat(outage.classification()).isEqualTo(FailureClassification.TRANSIENT);
                    assertThat(outage.reason()).isEqualTo(ReasonCode.REFERENCE_DATA_UNAVAILABLE);
                });
    }

    // --- D4 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d04 — the matcher's ouCode is the major creditor code, never the real OU code")
    void d04_subscriptions_match_on_the_major_creditor_code() {
        // THE HIGHEST-RISK PIN IN THE PACK. The field is called `ouCode`, which makes the legacy
        // look like a typo, and the natural Java correction is to match on prosecutionAuthorityOuCode
        // "because that is obviously what was meant". D4 may only change after the SIT
        // now_subscriptions verification (design doc §13 Q8) and a doc/DEVIATIONS.md entry.
        final ParityCase parityCase = load("d04-subscription-matched-on-major-creditor-code");
        final JsonNode documents = wire(parityCase);

        assertThat(recipientNames(documents.get(0)))
                .containsExactly(
                        "match-informant-code-is-major-creditor",
                        "match-court-house-is-major-creditor");
        assertThat(recipientNames(documents.get(0)))
                .doesNotContain(
                        "match-informant-code-is-ou-code", "match-court-house-is-ou-code");
        // TVL has no major creditor code, so nothing can match it.
        assertThat(documents.get(1).get("recipients")).isNull();

        matchesTheRecording(parityCase);
    }

    // --- D5 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d05 — being an informant-register subscription is not itself a reason to match")
    void d05_matching_runs_through_the_now_and_prison_branches() {
        // A Java matcher written from the field names — "this is the informant register flow, so
        // match isInformantRegisterSubscription" — gets the opposite answer on all three
        // subscriptions. The two branches that do match are authority-blind, which is why TVL, with
        // no major creditor code at all, still collects both.
        final ParityCase parityCase = load("d05-matcher-has-no-informant-register-branch");
        final JsonNode documents = wire(parityCase);

        for (final JsonNode document : documents) {
            assertThat(recipientNames(document))
                    .containsExactly(
                            "matched-via-now-branch", "matched-via-prison-court-register-branch");
            assertThat(recipientNames(document))
                    .doesNotContain("not-matched-informant-register-only");
        }

        matchesTheRecording(parityCase);
    }

    // --- D6 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d06 — one authority with no defendants still loses the register for every other")
    void d06_an_empty_defendant_fragment_loses_the_whole_hearing() {
        // TFL is untouched and on its own produces a perfectly good document. Dropping the empty TVL
        // fragment and carrying on is the FIX the design register describes (D6, priority 1); doing
        // it silently is what this pin exists to prevent. The port must produce nothing — and,
        // unlike Node, must say so (doc/DEVIATIONS.md entry 7).
        final ParityCase parityCase = load("d06-empty-defendant-fragment-loses-whole-hearing");

        assertThat(parityCase.expected().isNull()).isTrue();
        assertThat(parityCase.oracleOutcome()).isEqualTo("swallowed-exception");

        assertThatThrownBy(() -> documents(parityCase))
                .isInstanceOf(TransformationFailedException.class)
                .satisfies(failure -> assertThat(
                        ((TransformationFailedException) failure).classification())
                        .isEqualTo(FailureClassification.NON_TRANSIENT));
    }

    // --- D7 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d07 — an unparseable shared time loses the hearing, and never ships Invalid date")
    void d07_an_unparseable_shared_time_is_refused() {
        // Node reaches this three catch blocks deep: the register date becomes "Invalid dateZ",
        // `new Date(that)` raises a RangeError outside ReferenceDataService's own try, and two
        // handlers swallow in turn. Parity requirement: zero documents. The port must not get there
        // by that route, and must never emit the string "Invalid date" in a document.
        final ParityCase parityCase = load("d07-unparseable-register-date-loses-hearing");

        assertThat(parityCase.expected().isNull()).isTrue();
        assertThat(parityCase.oracleOutcome()).isEqualTo("swallowed-exception");

        assertThatThrownBy(() -> documents(parityCase))
                .isInstanceOf(TransformationFailedException.class)
                .satisfies(failure -> assertThat(
                        ((TransformationFailedException) failure).reason())
                        .isEqualTo(ReasonCode.TRANSFORMATION_FAILED));
    }

    // --- D8 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d08 — every case entry carries the defendant's whole offence list, not its own")
    void d08_offences_are_duplicated_onto_every_case() {
        // A Java OffenceMapper that takes a caseId — the obvious, tidy design — produces one offence
        // per entry and must fail this. Scoping offences to their own case changes what prosecuting
        // authorities receive and needs a business decision plus a deviations entry (D8, priority 2).
        final ParityCase parityCase = load("d08-offences-duplicated-onto-every-case");
        final JsonNode cases = defendants(wire(parityCase).get(0)).get(0)
                .get("prosecutionCasesOrApplications");

        assertThat(cases).hasSize(2);
        assertThat(text(cases.get(0), "caseOrApplicationReference")).isEqualTo("TFL4359536");
        assertThat(text(cases.get(1), "caseOrApplicationReference")).isEqualTo("TFL-SECOND-CASE");

        for (final JsonNode entry : cases) {
            final JsonNode offences = entry.get("offences");
            assertThat(offences).hasSize(2);
            assertThat(text(offences.get(0), "offenceCode")).isEqualTo("PS90010");
            assertThat(text(offences.get(0), "originatingCaseUrn")).isEqualTo("TFL4359536");
            assertThat(text(offences.get(1), "offenceCode")).isEqualTo("XX99999");
            assertThat(text(offences.get(1), "originatingCaseUrn")).isEqualTo("TFL-SECOND-CASE");
        }

        matchesTheRecording(parityCase);
    }

    // --- D9 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d09 — a summer timestamp is London wall-clock time labelled Z, an hour out")
    void d09_bst_local_time_is_labelled_as_utc() {
        // Asserted as exact strings on purpose. Parsing these into an Instant and comparing instants
        // passes for the right answer AND the wrong one, and pins nothing. A java.time port that
        // renders "2020-06-01T11:00:00+01:00" or re-renders the instant as "2020-06-01T10:00:00Z" is
        // CORRECT and must still fail here (D9, priority 2 — authorities parse these out of the CSV).
        final ParityCase parityCase = load("d09-bst-local-time-labelled-as-utc");
        final JsonNode documents = wire(parityCase);

        assertThat(text(documents.get(0), "registerDate")).isEqualTo("2020-06-01T11:00:00Z");
        assertThat(text(documents.get(1), "registerDate")).isEqualTo("2020-06-01T11:00:00Z");
        assertThat(text(documents.get(0), "fileName"))
                .isEqualTo("InformantRegister_TFL_2020-06-01.csv");
        assertThat(text(documents.get(1), "fileName"))
                .isEqualTo("InformantRegister_TVL_2020-06-01.csv");

        // THE CONTROL: the same field on a January hearing is unchanged, because London is UTC then.
        // The pair is what proves the hour is a British-Summer-Time artefact and not a constant.
        final ParityCase january = ParityCase.load("recorded", "base__outbound-hearing");
        assertThat(january.sharedTime()).isEqualTo("2020-01-20T11:00:00Z");
        assertThat(january.expected().get(0).get("registerDate").stringValue())
                .isEqualTo("2020-01-20T11:00:00Z");

        matchesTheRecording(parityCase);
    }

    // --- D10 and s06 -------------------------------------------------------------------------------

    @Test
    @DisplayName("d10 — a bad ordered date in company loses the hearing through a broken catch")
    void d10_the_latest_ordered_date_catch_block_throws() {
        // Both details of the fixture are load-bearing: one result per defendant keeps the
        // per-defendant sort below the two-element threshold at which a comparator is called, and
        // two distinct dates across defendants push the hearing-level sort above it. The port must
        // not sort defensively past a bad date and must not pick the parseable one and carry on —
        // either would be an unregistered fix.
        final ParityCase parityCase = load("d10-latest-ordered-date-catch-block-throws");

        assertThat(parityCase.expected().isNull()).isTrue();
        assertThat(parityCase.oracleOutcome()).isEqualTo("swallowed-exception");

        assertThatThrownBy(() -> documents(parityCase))
                .isInstanceOf(TransformationFailedException.class)
                .hasMessage("Invalid date format");
    }

    @Test
    @DisplayName("s06 — the same bad date, reached through the call site with no handling at all")
    void s06_the_unguarded_date_parse_in_the_defendant_context() {
        // The pair with d10 is the pin: the SAME bad date in the same fixture surfaces as a
        // TypeError from a broken catch block in one case and as a bare Error in the other, decided
        // only by how many judicial results a defendant happens to have. Asserting one and not the
        // other lets a port that guards a single call site pass while the other stays broken.
        final ParityCase parityCase = load("s06-unguarded-date-parse-in-defendant-context");

        assertThat(parityCase.expected().isNull()).isTrue();
        assertThat(parityCase.oracleOutcome()).isEqualTo("swallowed-exception");

        assertThatThrownBy(() -> documents(parityCase))
                .isInstanceOf(TransformationFailedException.class)
                .hasMessage("Invalid date format");
    }

    // --- D11 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d11 — duration dates are re-read as DD/MM/YYYY, and anything else becomes garbage")
    void d11_duration_dates_are_parsed_as_day_month_year() {
        // Assertion (2) is the one that matters: a date arriving in any other format is silently
        // converted to the literal "Invalid dateZ" and shipped into a field the frozen contract
        // types as a date-time. A java.time port using ISO_LOCAL_DATE, or a lenient multi-format
        // parser that gets both dates right, disagrees on both fields and must fail.
        final ParityCase parityCase = load("d11-duration-dates-parsed-as-ddmmyyyy");
        final JsonNode resultData = defendants(wire(parityCase).get(0)).get(0)
                .get("prosecutionCasesOrApplications").get(0)
                .get("offences").get(0)
                .get("offenceResults").get(0)
                .get("resultData");

        assertThat(text(resultData, "durationStartDate")).isEqualTo("2019-04-03T00:00:00Z");
        assertThat(text(resultData, "durationEndDate")).isEqualTo("Invalid dateZ");
        assertThat(text(resultData, "durationValue")).isEqualTo("5");
        assertThat(text(resultData, "secondaryDurationValue")).isEqualTo("2");
        assertThat(text(resultData, "durationUnit")).isEqualTo("M");
        assertThat(text(resultData, "secondaryDurationUnit")).isEqualTo("Y");

        matchesTheRecording(parityCase);
    }

    // --- D12 and o03 -------------------------------------------------------------------------------

    @Test
    @DisplayName("d12 — only a group master carries a group id, however present the field is")
    void d12_group_id_comes_only_from_a_group_master() {
        // The groupId is present in the input in BOTH halves of this pair; only isGroupMaster
        // decides whether it reaches the wire. A port that reads groupId wherever it finds one would
        // start populating group ids on ordinary cases and on applications, which looks like a
        // tidy-up and silently changes what Results receives.
        final ParityCase parityCase = load("d12-groupid-only-from-group-master-case");
        final JsonNode documents = wire(parityCase);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).get("groupId")).isNull();

        // THE POSITIVE CONTROL: the identical fixture WITH isGroupMaster, whose group id is the
        // value the function app's own Jest test asserts.
        final ParityCase master = ParityCase.load("recorded", "base__group-master-case");
        assertThat(master.expected().get(0).get("groupId").stringValue())
                .isEqualTo("47a08b62-e790-474b-94da-0fd406d2bcc8");

        matchesTheRecording(parityCase);
    }

    @Test
    @DisplayName("o03 — the authority name is read from a different field on cases and applications")
    void o03_the_authority_name_field_asymmetry() {
        // Each object carries a perfectly good authority name — in the field the OTHER branch reads.
        // A port that reads `name` as a fallback, or models both shapes with one record and one
        // field, emits names here and must fail.
        final ParityCase parityCase = load("o03-authority-name-field-asymmetry");
        final JsonNode documents = wire(parityCase);

        assertThat(documents).hasSize(3);
        for (final JsonNode document : documents) {
            assertThat(document.get("prosecutionAuthorityName")).isNull();
        }

        matchesTheRecording(parityCase);
    }

    // --- D17 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("d17 — letter delivery is logged and dropped, and email has three rules of its own")
    void d17_letter_delivery_is_ignored_and_email_rules_apply() {
        // Two traps. Entry [0] carries firstClassLetterDelivery AND email delivery and is INCLUDED —
        // a port that treats letter delivery as "not our channel, skip the subscription" drops
        // someone who should receive email. And "email-delivery-without-address" disappearing is the
        // KEEP: raising an error, substituting the second address, or emitting a null address each
        // changes who gets the register.
        final ParityCase parityCase = load("d17-letter-delivery-ignored-and-email-rules");
        final JsonNode recipients = wire(parityCase).get(0).get("recipients");

        assertThat(recipients).hasSize(3);

        assertThat(text(recipients.get(0), "recipientName"))
                .isEqualTo("email-plus-first-class-letter");
        assertThat(text(recipients.get(0), "emailTemplateName")).isEqualTo("tpl_named");
        assertThat(recipients.get(0).get("emailAddress2")).isNull();

        assertThat(text(recipients.get(1), "recipientName")).isEqualTo("no-template-name");
        assertThat(text(recipients.get(1), "emailTemplateName")).isEqualTo("ir_standard");

        assertThat(text(recipients.get(2), "recipientName")).isEqualTo("trimmed-and-second-address");
        assertThat(text(recipients.get(2), "emailAddress1"))
                .isEqualTo("trimmed-and-second-address@pinning.invalid");
        assertThat(text(recipients.get(2), "emailAddress2")).isEqualTo("second@pinning.invalid");

        assertThat(recipientNames(wire(parityCase).get(0)))
                .doesNotContain("letter-only-no-email-delivery", "email-delivery-without-address");

        matchesTheRecording(parityCase);
    }

    // --- o01 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("o01 — group proceedings are not skipped by this flow, unlike every other register")
    void o01_group_proceedings_are_not_skipped() {
        // The trap is a developer who has read one of the other register services and carries its
        // guard across "for consistency". That would stop producing informant registers for every
        // group hearing, and because the request would end COMPLETED with no authorities it would
        // look like a normal quiet day.
        final ParityCase parityCase = load("o01-group-proceedings-not-skipped");
        final ParityCase control = ParityCase.load("recorded", "base__prosecution-case");

        matchesTheRecording(parityCase);
        JsonParity.assertMatches(control.expected(), wire(parityCase),
                "o01 against the base__prosecution-case control");
    }

    // --- o02 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("o02 — a rule wanting a prosecutor major creditor can never match; wanting any can")
    void o02_the_major_creditor_vocabulary_is_always_empty() {
        // Same emptiness, opposite answers, and both halves must be asserted. The lists are always
        // empty because the vocabulary call passes two arguments; a rule that asks for a prosecutor
        // or non-prosecutor major creditor therefore never matches, while one that asks for ANY
        // matches on those same empty lists because the test is `!= null`. THE TRAP: a Java
        // VocabularyService written with all four inputs available starts matching the first, and
        // someone who receives nothing today starts receiving the register — with nothing in the
        // payload looking any different.
        final ParityCase parityCase = load("o02-major-creditor-vocabulary-always-empty");

        assertThat(recipientNames(wire(parityCase).get(0)))
                .containsExactly("permissive-control", "requires-any-major-creditor");
        assertThat(recipientNames(wire(parityCase).get(0)))
                .doesNotContain(
                        "requires-prosecutor-major-creditor",
                        "requires-nonprosecutor-major-creditor");

        matchesTheRecording(parityCase);
    }

    // --- s01 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("s01 — the court-extract filter runs at result level and again at prompt level")
    void s01_the_court_extract_filter_at_both_levels() {
        // The two that catch a well-meaning port: publishedForNows DROPS a result even though
        // isAvailableForCourtExtract is true, and at RESULT level an ABSENT flag means DROPPED — the
        // legacy `courtExtract` string fallback exists only at PROMPT level.
        final ParityCase parityCase = load("s01-court-extract-filter-result-and-prompt-level");
        final JsonNode results = defendants(wire(parityCase).get(0)).get(0)
                .get("prosecutionCasesOrApplications").get(0)
                .get("offences").get(0)
                .get("offenceResults");

        assertThat(results).hasSize(4);

        assertThat(text(results.get(0), "resultText")).isEqualTo("KEPT available-and-not-published");
        assertThat(results.get(0).get("resultData")).isNull();

        assertThat(text(results.get(1), "resultText"))
                .isEqualTo("PROMPT first-surviving-financial-prompt-wins");
        assertThat(text(results.get(1).get("resultData"), "amount")).isEqualTo("£11.00");

        assertThat(text(results.get(2), "resultText")).isEqualTo("PROMPT legacy-courtExtract-fallback");
        assertThat(text(results.get(2).get("resultData"), "amount")).isEqualTo("£22.00");

        assertThat(text(results.get(3), "resultText")).isEqualTo("PROMPT all-prompts-dropped");
        assertThat(results.get(3).get("resultData")).isNull();

        final List<String> kept = new ArrayList<>();
        results.forEach(result -> kept.add(text(result, "resultText")));
        assertThat(kept).doesNotContain(
                "DROPPED available-but-published-for-nows",
                "DROPPED not-available",
                "DROPPED flag-absent");

        matchesTheRecording(parityCase);
    }

    // --- s02 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("s02 — identifier fields are first-wins while defendants and offences are a union")
    void s02_authority_dedupe_keeps_the_first_occurrence() {
        // The document names one case in its header and carries another case's people and offences
        // in its body, with no field anywhere saying so. The two halves must be separate assertions,
        // because a port that groups by authority and merges identifiers breaks a different half
        // from one that emits a document per case.
        final ParityCase parityCase = load("s02-authority-dedupe-first-occurrence-wins");
        final JsonNode documents = wire(parityCase);

        assertThat(documents).hasSize(2);
        assertThat(text(documents.get(0), "prosecutionAuthorityCode")).isEqualTo("TFL");
        assertThat(text(documents.get(0), "majorCreditorCode")).isEqualTo("FIRST-WINS");
        assertThat(text(documents.get(0), "fileName"))
                .isEqualTo("InformantRegister_TFL_2020-06-01.csv");
        // Note the trap the manifest names: "TFL-SECOND-CASE" IS present in the body and
        // "TFL-SECOND" is NOT, so these must be two assertions rather than one substring search.
        assertThat(documents.toString()).doesNotContain("SECOND-LOSES");

        final JsonNode people = defendants(documents.get(0));
        assertThat(people).hasSize(2);
        final JsonNode loser = people.get(1);
        assertThat(text(loser, "lastName")).isEqualTo("Onlyonsecondcase");
        final JsonNode loserCase = loser.get("prosecutionCasesOrApplications").get(0);
        assertThat(text(loserCase, "caseOrApplicationReference")).isEqualTo("TFL-SECOND-CASE");
        assertThat(text(loserCase.get("offences").get(0), "originatingCaseUrn"))
                .isEqualTo("TFL-SECOND-CASE");

        matchesTheRecording(parityCase);
    }

    // --- s04 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("s04 — a case reference is its URN when truthy, and its authority reference when not")
    void s04_the_case_reference_prefers_the_urn() {
        // The same rule is applied twice, in two different mappers, and both must be pinned. Note it
        // is `truthy`, not `!= null`: an empty-string URN falls back, so
        // Objects.requireNonNullElse would diverge on a case this corpus does not contain.
        final ParityCase parityCase = load("s04-case-reference-urn-then-authority-reference");
        final JsonNode documents = wire(parityCase);

        for (final JsonNode defendant : defendants(documents.get(0))) {
            final JsonNode entry = defendant.get("prosecutionCasesOrApplications").get(0);
            assertThat(text(entry, "caseOrApplicationReference")).isEqualTo("TFL-URN-WINS");
            assertThat(text(entry.get("offences").get(0), "originatingCaseUrn"))
                    .isEqualTo("TFL-URN-WINS");
        }
        for (final JsonNode defendant : defendants(documents.get(1))) {
            final JsonNode entry = defendant.get("prosecutionCasesOrApplications").get(0);
            assertThat(text(entry, "caseOrApplicationReference")).isEqualTo("TVL298320922");
            assertThat(text(entry.get("offences").get(0), "originatingCaseUrn"))
                    .isEqualTo("TVL298320922");
        }

        matchesTheRecording(parityCase);
    }

    // --- s05 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("s05 — the literal Invalid dateZ Node ships as a timestamp is refused here instead")
    void s05_invalid_date_z_is_refused_rather_than_shipped() {
        // This entry pins an OBSERVATION, not a desired behaviour, and it is reached on an
        // UNMODIFIED real Jest fixture: Node POSTs a contract-violating body on ordinary data and
        // never learns that it did. The port cannot reproduce that — emitting it would be knowingly
        // POSTing an invalid body — and cannot drop it silently either, so it refuses and the
        // request is parked (doc/DEVIATIONS.md entry 10).
        //
        // WHICH WAY IT SHOULD GO IS STILL OPEN with the Results team (parity-pack/README.md §5
        // finding 6). The entry exists so that nobody picks quietly; the assertion below is the
        // registered choice, and it changes only in the commit that changes entry 10. The sibling
        // differential case is held back for the same reason and names this item.
        final ParityCase parityCase = load("s05-invalid-date-z-shipped-as-a-timestamp");

        assertThat(parityCase.expected()).hasSize(1);
        assertThat(parityCase.expected().get(0).get("hearingDate").stringValue())
                .isEqualTo("Invalid dateZ");

        assertThatThrownBy(() -> documents(parityCase))
                .isInstanceOf(TransformationFailedException.class)
                .hasMessageContaining("hearingDate");
    }

    // --- the seven the oracle cannot reach --------------------------------------------------------

    /**
     * The entries the transform corpus cannot demonstrate, kept by name so the pack stays complete.
     *
     * <p>Each carries the manifest's own {@code blockedReason} and names the test level that does
     * reach the behaviour. They are not gaps in rigour — they are the delivery leg, the payload
     * source, a non-behaviour and an observability waiver, and the oracle deliberately covers the
     * transform only.
     */
    @Nested
    @DisplayName("BLOCKED — outside the oracle's reach, covered at another test level")
    class OutsideTheOracle {

        @Test
        @Disabled("BLOCKED — the oracle records the array ProcessOutboundInformantRegister RECEIVES "
                + "and never runs the POST leg (oracle/lib/stubs.js makes axios.post throw), so no "
                + "input can exhibit it. Covered by ResultsRegisterSubmissionClientTest and "
                + "ResultsCommandGatewayTest (WireMock) plus DistributionPipelineTest.")
        void d01_the_final_post_is_unretried_and_swallowed() {
            throw new UnsupportedOperationException("blocked — see @Disabled");
        }

        @Test
        @Disabled("BLOCKED — the orchestrator function itself is never executed by the oracle; it "
                + "is a Durable generator needing the Functions host. The distinction D2 destroys "
                + "is exactly what meta.json's outcome field records. Covered by "
                + "DistributionPipelineTest and MessageListenerSettlementTest.")
        void d02_the_orchestrator_always_reports_success() {
            throw new UnsupportedOperationException("blocked — see @Disabled");
        }

        @Test
        @Disabled("BLOCKED — the oracle starts AFTER HearingResultedCacheQuery, so no Redis client "
                + "is ever constructed. The manifest records this as a porting instruction rather "
                + "than a behaviour: there is nothing to assert.")
        void d13_the_redis_retry_strategy_is_dead_code() {
            throw new UnsupportedOperationException("blocked — see @Disabled");
        }

        @Test
        @Disabled("BLOCKED — outside the oracle for the same reason as d13, and not observable in "
                + "register output in any case. It is doc/DEVIATIONS.md entry 1 and is asserted by "
                + "LivePayloadConfigTest.TransportSecurity.")
        void d14_redis_tls_verification_is_disabled() {
            throw new UnsupportedOperationException("blocked — see @Disabled");
        }

        @Test
        @Disabled("BLOCKED — not a behaviour. No input to the transform can exhibit it; it is a CI "
                + "secret-scanning and platform obligation.")
        void d15_committed_secrets_are_not_ported() {
            throw new UnsupportedOperationException("blocked — see @Disabled");
        }

        @Test
        @Disabled("BLOCKED — the trigger and the Durable client are not run. The corpus's nearest "
                + "evidence is the re-share-duplicate operator, which shows the transform is "
                + "idempotent but says nothing about duplicate submission. Covered by "
                + "IdempotencyGuardIT, DuplicateDetectionIT and IdempotencyCollisionIT.")
        void d16_a_duplicate_delivery_starts_duplicate_work() {
            throw new UnsupportedOperationException("blocked — see @Disabled");
        }

        @Test
        @Disabled("BLOCKED — not a transform behaviour: the oracle records documents and cannot "
                + "observe logs, metrics or alert rules. It is doc/DEVIATIONS.md entry 3, the "
                + "time-boxed alert-wiring waiver, and is covered by FailureSignalIT and "
                + "ProcessingMetricsTest.")
        void v03_the_alert_wiring_waiver() {
            throw new UnsupportedOperationException("blocked — see @Disabled");
        }
    }
}
