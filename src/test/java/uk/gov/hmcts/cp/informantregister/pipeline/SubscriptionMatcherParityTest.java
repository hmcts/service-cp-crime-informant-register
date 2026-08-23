package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragmentWithSubscriptions;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.informantregister.domain.ResultLevel;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.informantregister.support.JsonParity;

/**
 * The JUnit twins of the legacy {@code InformantRegisterSubscriptions} activity.
 *
 * <p>Four groups, because the Jest suite alone would prove almost nothing here.
 *
 * <p><strong>{@link LegacyJestCases} — the three Jest cases, twinned honestly.</strong> All three
 * mock reference data with a bare array rather than a {@code {nowSubscriptions: […]}} body, so all
 * three return at {@code index.js:24} with the fragments untouched and the matching code never runs.
 * That is blind spot <strong>BS-01</strong>, rated critical in
 * {@code parity-pack/coverage/blindspots.md}: three green tests over an unexecuted function, and the
 * largest false-confidence surface in the legacy suite. The twins are kept because the early return
 * <em>is</em> observable behaviour worth pinning — the fragments must come back with no
 * {@code matchedSubscriptions} member at all — but each says what it actually proves, and none of
 * them is allowed to stand for "subscription matching works".
 *
 * <p><strong>{@link Bs01MatchingActuallyExecuted} — what BS-01 asks for.</strong> Six cases against
 * goldens captured by running the <em>real</em> legacy activity chain, so these are inherited
 * expectations rather than invented ones. Between them they drive the {@code isInformantRegisterSubscription}
 * filter, the empty-match short-circuit, {@code ouCode} coming from {@code majorCreditorCode}, and
 * {@code matchedSubscriptions} actually being set per fragment.
 *
 * <p><strong>{@link Bs01SubscriptionObjectWiring} — what the goldens cannot decide.</strong> Two of
 * the five things BS-01 names are <em>not</em> settled by those six: none of the recorded hearings
 * gives its defendants different vocabularies, and none of the recorded subscriptions depends on a
 * judicial result, so a port that pooled the vocabularies, or that collected results from the first
 * defendant only, would pass all six goldens. Those two claims — vocabulary from
 * {@code registerDefendants[0]} ({@code index.js:46}) and judicial results pooled across every
 * defendant ({@code index.js:53-64}) — are separated there, on hand-built fragments, because the
 * parity pack holds no recorded hearing that distinguishes them.
 *
 * <p><strong>{@link Bs12RegisterDateIsDereferencedUnguarded} — blind spot BS-12.</strong> The
 * register-date lookup and the {@code registerDefendants[0]} dereference are both unguarded in the
 * legacy and unexecuted by any Jest case.
 *
 * <p><strong>Provenance of the goldens.</strong> Each is the fragment array as it stood between
 * {@code InformantRegisterOrchestrator/index.js:33} and {@code :37}, captured by running
 * {@code SetInformantRegister} and {@code InformantRegisterSubscriptions} from the Node working tree
 * at commit {@code a8d3c00b92c3d4cc5da5a555699a63d30adcf8db}, across the same activity boundary the
 * Durable Task extension imposes, with the parity pack's stubs and its clock pinned to
 * {@code 2026-08-21T09:15:00.000Z}. The hearing and subscription inputs are copied verbatim from the
 * corresponding {@code parity-pack/recorded/} cases, named in each twin.
 *
 * <p><strong>These twins run two ported steps, not one.</strong> The fragments are produced by
 * {@link RegisterBuilder} rather than deserialised, because that is the seam the service will use and
 * a golden that only ever saw a hand-built fragment would not prove the two steps compose. A
 * regression in the builder therefore fails here as well as in
 * {@link RegisterBuilderParityTest}; that is the intended cost.
 */
@DisplayName("SubscriptionMatcher — parity with the legacy InformantRegisterSubscriptions")
class SubscriptionMatcherParityTest {

    /** The instant the parity pack pins for every recorded case. */
    private static final Clock PINNED =
            Clock.fixed(Instant.parse("2026-08-21T09:15:00.000Z"), ZoneOffset.UTC);

    /** The shared time the recorded prosecution-case cases carry. */
    private static final String SHARED_TIME = "2020-06-01T10:00:00Z";

    private static final String FIXTURES = "/fixtures/informantregistersubscriptions/";

    /** The judicial result type the wiring cases build a subscription around. */
    private static final String WANTED_RESULT_TYPE_ID = "bcb5a496-f7cf-11e8-8eb2-f2801f1b9fd1";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final RegisterBuilder builder = new RegisterBuilder(new HearingDates(PINNED));

    private final SubscriptionMatcher matcher = new SubscriptionMatcher(new SubscriptionRules());

    @Nested
    @DisplayName("Informant Register Subscriptions")
    class LegacyJestCases {

        @Test
        @DisplayName("Should NOT set matchedSubscription when there is no subscription")
        void match_with_a_body_carrying_no_now_subscriptions_should_leave_the_fragments_untouched() {
            final List<RegisterFragment> fragments = List.of(jestFragment(), jestFragment());

            // Promise.resolve([]) — a bare array, which has no nowSubscriptions member. This is the
            // mis-shaped mock BS-01 is about, reproduced rather than corrected: correcting it would
            // make the twin assert something the Jest case never asserted.
            final List<RegisterFragmentWithSubscriptions> matched =
                    matcher.match(fragments, mapper.createArrayNode());

            assertThat(matched).hasSize(2);
            assertThat(matched).allSatisfy(fragment ->
                    assertThat(fragment.matchedSubscriptions()).isNull());
        }

        @Test
        @DisplayName("Should set matchedSubscription property of informant register object")
        void match_with_a_bare_array_of_subscriptions_should_still_leave_the_fragments_untouched() {
            final List<RegisterFragment> fragments = List.of(jestFragment(), jestFragment());

            final List<RegisterFragmentWithSubscriptions> matched =
                    matcher.match(fragments, mapper.createArrayNode().add(informantSubscription()));

            // The Jest case asserts only `returnInformantRegisters.length === 2` — the length of the
            // input it handed in, because the mock is a bare array here too and the activity returns
            // at index.js:24 without ever calling SubscriptionsService. Named for setting
            // matchedSubscription; proves the opposite. Pinned as the pass-through it is.
            assertThat(matched).hasSize(2);
            assertThat(matched).allSatisfy(fragment ->
                    assertThat(fragment.matchedSubscriptions()).isNull());
        }

        @Test
        @DisplayName("Should have multiple subscriptions if multiple subscription returns")
        void match_should_carry_the_register_defendants_through_the_early_return() {
            final RegisterFragment fragment = new RegisterFragment(
                    "2020-04-20", null, "e100d08a-ed4e-43a2-aae2-e9c5735713b0",
                    null, null, null, null, null, registerDefendants(), null);

            final List<RegisterFragmentWithSubscriptions> matched =
                    matcher.match(List.of(fragment), mapper.createArrayNode().add(informantSubscription()));

            assertThat(matched).hasSize(1);
            assertThat(matched.get(0).registerDefendants()).hasSize(1);
            assertThat(matched.get(0).matchedSubscriptions()).isNull();
        }
    }

    @Nested
    @DisplayName("BS-01 — subscription matching, actually executed")
    class Bs01MatchingActuallyExecuted {

        @Test
        @DisplayName("a major creditor code two subscriptions name matches both of them")
        void match_on_a_major_creditor_code_should_set_both_matching_subscriptions() {
            assertParity("case-01-matching-major-creditor-code",
                    "hearing-matching-major-creditor-code.json",
                    "now-subscriptions-ir-sample.json");
        }

        @Test
        @DisplayName("a major creditor code no subscription names matches none, and says so")
        void match_on_an_unknown_major_creditor_code_should_set_an_empty_list() {
            assertParity("case-02-unmatched-major-creditor-code",
                    "hearing-unmatched-major-creditor-code.json",
                    "now-subscriptions-ir-sample.json");
        }

        @Test
        @DisplayName("a reference-data body with no nowSubscriptions member leaves the fragments alone")
        void match_with_no_now_subscriptions_member_should_leave_the_fragments_untouched() {
            assertParity("case-03-no-now-subscriptions-key",
                    "hearing-no-major-creditor-code.json",
                    "now-subscriptions-no-key.json");
        }

        @Test
        @DisplayName("a reference-data body with no informant-register subscription leaves them alone")
        void match_with_no_informant_register_subscription_should_leave_the_fragments_untouched() {
            assertParity("case-04-no-informant-register-subscriptions",
                    "hearing-no-major-creditor-code.json",
                    "now-subscriptions-none-for-informant-register.json");
        }

        @Test
        @DisplayName("a duplicated subscription is matched as many times as it appears")
        void match_should_not_collapse_duplicate_subscriptions() {
            assertParity("case-05-duplicated-subscriptions",
                    "hearing-matching-major-creditor-code.json",
                    "now-subscriptions-duplicated.json");
        }

        @Test
        @DisplayName("applySubscriptionRules turns the vocabulary gate on, and it refuses")
        void match_should_apply_the_vocabulary_rules_when_the_subscription_asks_for_them() {
            assertParity("case-06-apply-subscription-rules",
                    "hearing-matching-major-creditor-code.json",
                    "now-subscriptions-apply-subscription-rules.json");
        }

        @Test
        @DisplayName("an absent reference-data answer leaves the fragments alone")
        void match_with_no_answer_at_all_should_leave_the_fragments_untouched() {
            // `!subscriptionsMetaData` (index.js:22), the half of the first guard no recorded case
            // reaches through the CLI — the oracle always answers with a body. Both Java shapes of
            // "no answer" are held to it.
            final List<RegisterFragment> fragments =
                    builder.build(hearing("hearing-matching-major-creditor-code.json"), SHARED_TIME);

            assertThat(matcher.match(fragments, null))
                    .allSatisfy(fragment -> assertThat(fragment.matchedSubscriptions()).isNull());
            assertThat(matcher.match(fragments, mapper.nullNode()))
                    .allSatisfy(fragment -> assertThat(fragment.matchedSubscriptions()).isNull());
        }
    }

    @Nested
    @DisplayName("BS-12 — the register-date lookup and the first-defendant dereference")
    class Bs12RegisterDateIsDereferencedUnguarded {

        @Test
        @DisplayName("the register date is the first one any fragment carries")
        void register_date_should_be_taken_from_the_first_fragment_that_has_one() {
            final List<RegisterFragment> fragments =
                    builder.build(hearing("hearing-matching-major-creditor-code.json"), SHARED_TIME);

            assertThat(matcher.registerDate(fragments)).isEqualTo("2020-06-01T11:00:00Z");
        }

        @Test
        @DisplayName("no fragment carrying a register date is refused, not swallowed")
        void register_date_with_no_fragment_carrying_one_should_be_refused() {
            final List<RegisterFragment> fragments = List.of(new RegisterFragment(
                    null, null, "hearing-id", null, null, null, null, null, List.of(), null));

            assertThatThrownBy(() -> matcher.registerDate(fragments))
                    .isInstanceOf(TransformationFailedException.class);
            assertThatThrownBy(() -> matcher.match(fragments, mapper.createObjectNode()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("an empty fragment list is refused, not read as nothing to do")
        void register_date_with_no_fragments_at_all_should_be_refused() {
            assertThatThrownBy(() -> matcher.registerDate(List.of()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("an authority with no defendants is refused, not matched against nothing")
        void match_with_a_fragment_carrying_no_defendants_should_be_refused() {
            final List<RegisterFragment> fragments = List.of(new RegisterFragment(
                    "2020-06-01T11:00:00Z", null, "hearing-id", "authority-id",
                    null, null, null, "CDE04", List.of(), null));

            assertThatThrownBy(() ->
                    matcher.match(fragments, subscriptions("now-subscriptions-ir-sample.json")))
                    .isInstanceOf(TransformationFailedException.class);
        }
    }

    /**
     * The two wiring claims the six goldens cannot tell apart, and the {@code null} the filter reads.
     *
     * <p>None of the six recorded cases uses a result-dependent subscription or gives its defendants
     * different vocabularies, so an implementation that pooled the vocabularies, or that read only
     * the first defendant's results, would pass all six. The three cases here separate them, each
     * against the legacy line it reproduces. The fragments are hand-built rather than produced by
     * {@link RegisterBuilder} for the same reason the goldens cannot do this job: a recorded hearing
     * whose defendants differ in exactly one vocabulary flag does not exist in the parity pack.
     */
    @Nested
    @DisplayName("BS-01 — the wiring the goldens cannot distinguish")
    class Bs01SubscriptionObjectWiring {

        @Test
        @DisplayName("the vocabulary is the first defendant's, and no other defendant's can stand in")
        void match_should_read_the_vocabulary_of_the_first_defendant_only() {
            // `subscriptionObj.vocabulary = informantRegister.registerDefendants[0].vocabulary`
            // (index.js:46). The subscription below demands a youth; only one of the two defendants
            // is one, and which of them it is decides the answer.
            final JsonNode subscriptions = youthOnlySubscription();

            assertThat(matchedCount(fragment(youth(), adult()), subscriptions)).isEqualTo(1);
            assertThat(matchedCount(fragment(adult(), youth()), subscriptions)).isZero();
        }

        @Test
        @DisplayName("the judicial results are pooled, so a second defendant's result can match")
        void match_should_pool_the_judicial_results_across_every_defendant() {
            // `collectJudicialResults` walks every registerDefendant (index.js:53-64), so a result
            // carried only by the second one still answers the includedResults rule — which the
            // first defendant's own results could never do.
            final JsonNode subscriptions = resultDependentSubscription();

            assertThat(matchedCount(
                    fragment(youth(), youthWithResult(WANTED_RESULT_TYPE_ID)), subscriptions))
                    .isEqualTo(1);
            assertThat(matchedCount(
                    fragment(youth(), youthWithResult("some-other-type-id")), subscriptions))
                    .isZero();
        }

        @Test
        @DisplayName("a null among the now subscriptions is refused, as the legacy's filter is")
        void match_with_a_null_now_subscription_should_be_refused() {
            // `nowSubscriptions.filter(s => s.isInformantRegisterSubscription)` (index.js:28) reads
            // a property off every element, so this hearing produces no register at all in the
            // legacy — and skipping the null would emit one here (doc/DEVIATIONS.md entry 7).
            final ObjectNode body = mapper.createObjectNode();
            final ArrayNode nowSubscriptions = body.putArray("nowSubscriptions");
            nowSubscriptions.addNull();
            nowSubscriptions.add(informantSubscription());

            assertThatThrownBy(() -> matcher.match(List.of(fragment(youth())), body))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * How many subscriptions the fragment's one authority matched.
         *
         * @param fragment      the fragment to match
         * @param subscriptions the reference-data body
         * @return the number of matched subscriptions
         */
        private int matchedCount(final RegisterFragment fragment, final JsonNode subscriptions) {
            final List<RegisterFragmentWithSubscriptions> matched =
                    matcher.match(List.of(fragment), subscriptions);
            return matched.get(0).matchedSubscriptions().size();
        }
    }

    /**
     * Runs one golden case: build the fragments, match them, then hold the whole tree to the golden.
     *
     * @param caseName              the golden file to compare against
     * @param hearingFixture        the recorded hearing input
     * @param subscriptionsFixture  the recorded reference-data answer
     */
    private void assertParity(
            final String caseName, final String hearingFixture, final String subscriptionsFixture) {

        final List<RegisterFragment> fragments = builder.build(hearing(hearingFixture), SHARED_TIME);
        final List<RegisterFragmentWithSubscriptions> matched =
                matcher.match(fragments, subscriptions(subscriptionsFixture));

        JsonParity.assertMatches(golden(caseName), mapper.valueToTree(matched), caseName);
    }

    /**
     * The fragment the Jest suite's {@code InformantRegister} class declares: a register date, a
     * hearing id, and nothing else.
     *
     * @return the fragment
     */
    private static RegisterFragment jestFragment() {
        return new RegisterFragment(
                "2020-04-20", null, "e100d08a-ed4e-43a2-aae2-e9c5735713b0",
                null, null, null, null, null, null, null);
    }

    /**
     * The Jest suite's {@code SubscriptionMetaData}: an informant-register subscription and nothing
     * else. It never reaches the matching code, because the mock wraps it in a bare array.
     *
     * @return the subscription tree
     */
    private JsonNode informantSubscription() {
        return mapper.createObjectNode().put("isInformantRegisterSubscription", true);
    }

    /**
     * A reference-data body holding one informant-register NOW subscription that demands a youth.
     *
     * @return the body
     */
    private JsonNode youthOnlySubscription() {
        return subscriptionBody(vocabulary -> vocabulary.put("youthDefendant", true));
    }

    /**
     * A reference-data body holding one subscription that demands a named judicial result type.
     *
     * @return the body
     */
    private JsonNode resultDependentSubscription() {
        return subscriptionBody(vocabulary -> {
            vocabulary.put("youthDefendant", true);
            vocabulary.putArray("includedResults").add(WANTED_RESULT_TYPE_ID);
        });
    }

    /**
     * A reference-data body holding one informant-register NOW subscription with rules applied.
     *
     * <p>Attendance, court and custody are relaxed the way the kernel's own Jest suite relaxes them,
     * so only what the caller adds can decide the match.
     *
     * @param rules what the case wants the subscription to demand
     * @return the body
     */
    private JsonNode subscriptionBody(final Consumer<ObjectNode> rules) {
        final ObjectNode body = mapper.createObjectNode();
        final ObjectNode subscription = body.putArray("nowSubscriptions").addObject();
        subscription.put("isInformantRegisterSubscription", true);
        subscription.put("isNowSubscription", true);
        subscription.put("applySubscriptionRules", true);
        final ObjectNode vocabulary = subscription.putObject("subscriptionVocabulary");
        vocabulary.put("anyAppearance", true);
        vocabulary.put("anyCourtHearing", true);
        vocabulary.put("ignoreCustody", true);
        vocabulary.put("ignoreResults", true);
        rules.accept(vocabulary);
        return body;
    }

    /**
     * A fragment for one authority carrying the given defendants, in the given order.
     *
     * @param defendants the register's defendants; the first one's vocabulary is the register's
     * @return the fragment
     */
    private static RegisterFragment fragment(final RegisterDefendant... defendants) {
        return new RegisterFragment(
                "2020-06-01T11:00:00Z", null, "e100d08a-ed4e-43a2-aae2-e9c5735713b0",
                "authority-id", null, null, null, null, List.of(defendants), null);
    }

    /**
     * A defendant whose vocabulary says youth, carrying no results.
     *
     * @return the defendant
     */
    private static RegisterDefendant youth() {
        return defendant(true, null);
    }

    /**
     * A defendant whose vocabulary says adult, carrying no results.
     *
     * @return the defendant
     */
    private static RegisterDefendant adult() {
        return defendant(false, null);
    }

    /**
     * A youth defendant carrying one judicial result of the given type.
     *
     * @param typeId the {@code judicialResultTypeId}
     * @return the defendant
     */
    private RegisterDefendant youthWithResult(final String typeId) {
        return defendant(true, new RegisterResult(null, null, null, null, ResultLevel.DEFENDANT,
                null, mapper.createObjectNode().put("judicialResultTypeId", typeId), null, null));
    }

    /**
     * A defendant with an otherwise-empty vocabulary saying only whether they are a youth.
     *
     * @param isYouth whether the vocabulary says youth or adult
     * @param result  the one result to carry, or {@code null} for none
     * @return the defendant
     */
    private static RegisterDefendant defendant(final boolean isYouth, final RegisterResult result) {
        final RegisterVocabulary vocabulary = new RegisterVocabulary(
                false, false, false, false, false, false, false, false, false, false,
                isYouth, !isYouth, true, false, false, true, null, null);
        return new RegisterDefendant(
                null, result == null ? List.of() : List.of(result),
                null, null, null, isYouth, null, vocabulary);
    }

    /**
     * Reads {@code register-defendant.json}, the byte-identical copy of the fixture the third Jest
     * case supplies, into the typed defendants the fragment carries.
     *
     * <p>Read field by field rather than bound, because the fixture's vocabulary spells two of its
     * flags {@code atLeastOne…} where every reader spells them {@code atleastOne…}. Binding would
     * have to be told to ignore that; reading by name reproduces what the legacy does with it, which
     * is nothing.
     *
     * @return the register defendants
     */
    private List<RegisterDefendant> registerDefendants() {
        final List<RegisterDefendant> defendants = new ArrayList<>();
        for (final JsonNode defendant : read(FIXTURES + "register-defendant.json")) {
            final List<RegisterResult> results = new ArrayList<>();
            for (final JsonNode result : defendant.get("results")) {
                results.add(new RegisterResult(
                        Json.text(result, "prosecutionCaseId"),
                        Json.text(result, "defendantId"),
                        Json.text(result, "offenceId"),
                        Json.text(result, "applicationId"),
                        level(Json.text(result, "level")),
                        Json.text(result, "masterDefendantId"),
                        result.get("judicialResult"),
                        null,
                        null));
            }
            defendants.add(new RegisterDefendant(
                    null, results, null, null,
                    Json.text(defendant, "masterDefendantId"), null, null,
                    vocabularyFrom(defendant.get("vocabulary"))));
        }
        return defendants;
    }

    /**
     * Reads a level's single-letter wire form.
     *
     * @param code the letter
     * @return the level
     */
    private static ResultLevel level(final String code) {
        for (final ResultLevel candidate : ResultLevel.values()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown level " + code);
    }

    /**
     * Reads a recorded vocabulary tree into the typed record, leaving both creditor lists absent when
     * the tree omits them.
     *
     * @param vocabulary the recorded tree
     * @return the typed vocabulary
     */
    private static RegisterVocabulary vocabularyFrom(final JsonNode vocabulary) {
        return new RegisterVocabulary(
                Json.truthy(vocabulary, "custodyLocationIsPolice"),
                Json.truthy(vocabulary, "custodyLocationIsPrison"),
                Json.truthy(vocabulary, "atleastOneCustodialResult"),
                Json.truthy(vocabulary, "allNonCustodialResults"),
                Json.truthy(vocabulary, "atleastOneNonCustodialResult"),
                Json.truthy(vocabulary, "appearedInPerson"),
                Json.truthy(vocabulary, "appearedByVideoLink"),
                Json.truthy(vocabulary, "isCpsProsecuted"),
                Json.truthy(vocabulary, "anyAppearance"),
                Json.truthy(vocabulary, "inCustody"),
                Json.truthy(vocabulary, "youthDefendant"),
                Json.truthy(vocabulary, "adultDefendant"),
                Json.truthy(vocabulary, "adultOrYouthDefendant"),
                Json.truthy(vocabulary, "welshCourtHearing"),
                Json.truthy(vocabulary, "englishCourtHearing"),
                Json.truthy(vocabulary, "anyCourtHearing"),
                null,
                null);
    }

    /**
     * Loads a recorded hearing input.
     *
     * @param name the fixture file name
     * @return the hearing tree
     */
    private JsonNode hearing(final String name) {
        return read(FIXTURES + "hearings/" + name);
    }

    /**
     * Loads a recorded reference-data answer.
     *
     * @param name the fixture file name
     * @return the now-subscriptions body
     */
    private JsonNode subscriptions(final String name) {
        return read(FIXTURES + "subscriptions/" + name);
    }

    /**
     * Loads a golden captured from the legacy activity chain.
     *
     * @param caseName the case name
     * @return the golden tree
     */
    private JsonNode golden(final String caseName) {
        return read(FIXTURES + "expected/" + caseName + ".json");
    }

    /**
     * Reads a JSON resource from the test classpath.
     *
     * @param resource the absolute classpath location
     * @return the parsed tree
     */
    private JsonNode read(final String resource) {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return mapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
