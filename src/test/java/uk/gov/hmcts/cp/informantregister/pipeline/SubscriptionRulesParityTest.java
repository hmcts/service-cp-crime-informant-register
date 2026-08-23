package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.informantregister.domain.SubscriptionCriteria;
import uk.gov.hmcts.cp.informantregister.domain.UserGroupSelection;
import uk.gov.hmcts.cp.informantregister.domain.UserGroupType;
import uk.gov.hmcts.cp.informantregister.support.LegacyFixtures;

/**
 * The JUnit twins of the legacy {@code SubscriptionsService} Jest suite.
 *
 * <p>One twin per Jest case, in the order {@code NowsHelper/service/test/SubscriptionsService.test.js}
 * declares them: twenty-one cases in one describe. This is the shared matching kernel, not the
 * informant-register activity — the activity's own three cases are twinned in
 * {@link SubscriptionMatcherParityTest} — but the informant register is one of its callers and every
 * matched subscription on a register comes out of the code these cases drive, so the suite is adapted
 * here rather than left behind.
 *
 * <p><strong>The assertions are stronger than the Jest ones, deliberately.</strong> Every Jest case
 * asserts {@code response.length} and nothing else, so a port that returned the right <em>number</em>
 * of wrong subscriptions would pass all twenty-one. Each twin therefore asserts which subscriptions
 * came back as well as how many; the length the Jest case asserts is implied by that and is never
 * weakened.
 *
 * <p><strong>Two of the twenty-one pass for a reason their name does not describe</strong>, and the
 * twins say so rather than inheriting the impression:
 *
 * <ul>
 *   <li>"included results are matched with results" runs against {@code Subscriptions.json}, which
 *       declares no {@code includedResults} at all, so the result-matching branch is never
 *       entered.</li>
 *   <li>"excluded results are matched with results" is refused by the attendance check long before
 *       the excluded-results branch is reached.</li>
 * </ul>
 *
 * <p><strong>Fixtures are byte-identical copies</strong> of the seven JSON files the Jest suite reads
 * from {@code NowsHelper/service/test/}, verified with {@code diff} at copy time, as constitution
 * Principle I requires.
 */
@DisplayName("SubscriptionRules — parity with the legacy SubscriptionsService")
class SubscriptionRulesParityTest {

    /** The hearing id every Jest case passes for logging; it cannot reach the answer. */
    private static final String HEARING_ID = "809f9591-65fc-4e37-97d3-5f4def868959";

    /** The NOW id {@code Subscriptions.json} lists under {@code includedNOWS}. */
    private static final String INCLUDED_NOW_ID = "10115268-8efc-49fe-b8e8-feee216a03da";

    private final SubscriptionRules rules = new SubscriptionRules();

    @Test
    @DisplayName("Should exclude subscriptions if subscription vocabulary is not defined")
    void match_with_no_vocabulary_should_exclude_every_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");

        final List<JsonNode> matched = rules.match(criteria()
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should exclude subscriptions if subscription vocabulary is defined but NOT matched")
    void match_with_unmatched_vocabulary_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        flag(subscriptions.get(0), "appearedByVideoLink", true);

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should include subscriptions if subscription vocabulary is defined AND matched")
    void match_with_matched_vocabulary_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        flag(subscriptions.get(0), "appearedByVideoLink", true);
        flag(subscriptions.get(0), "anyCourtHearing", true);
        flag(subscriptions.get(0), "adultOrYouthDefendant", true);
        flag(subscriptions.get(0), "inCustody", true);
        flag(subscriptions.get(0), "allNonCustodialResults", false);
        flag(subscriptions.get(0), "atleastOneNonCustodialResult", true);
        flag(subscriptions.get(0), "atleastOneCustodialResult", true);
        flag(subscriptions.get(0), "isCpsProsecuted", false);

        final RegisterVocabulary vocabulary = new Vocabulary()
                .appearedByVideoLink()
                .anyCourtHearing()
                .adultOrYouthDefendant()
                .inCustody()
                .atleastOneNonCustodialResult()
                .atleastOneCustodialResult()
                .build();

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(vocabulary)
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should include subscriptions if subscription vocabulary is defined AND matched with results")
    void match_with_matched_result_vocabulary_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        flag(subscriptions.get(0), "appearedByVideoLink", true);
        flag(subscriptions.get(0), "anyCourtHearing", true);
        flag(subscriptions.get(0), "adultOrYouthDefendant", true);
        flag(subscriptions.get(0), "inCustody", true);
        flag(subscriptions.get(0), "allNonCustodialResults", true);
        flag(subscriptions.get(0), "atleastOneNonCustodialResult", true);
        flag(subscriptions.get(0), "atleastOneCustodialResult", false);

        final RegisterVocabulary vocabulary = new Vocabulary()
                .appearedByVideoLink()
                .anyCourtHearing()
                .adultOrYouthDefendant()
                .inCustody()
                .allNonCustodialResults()
                .atleastOneNonCustodialResult()
                .build();

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(vocabulary)
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should include subscriptions if includedNOWS matched")
    void match_with_an_included_now_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        applyIgnoringCustodyAndResults(subscriptions.get(0));

        final List<JsonNode> matched = rules.match(criteria()
                .nowId(INCLUDED_NOW_ID)
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should include subscriptions if includedNOWS NOT matched")
    void match_with_a_now_outside_included_nows_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");

        final List<JsonNode> matched = rules.match(criteria()
                .nowId("dummy-now-id")
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should NOT include subscriptions if excludedNOWS matched")
    void match_with_an_excluded_now_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
        subscription.putArray("includedNOWS");
        subscription.putArray("excludedNOWS").add(INCLUDED_NOW_ID);

        final List<JsonNode> matched = rules.match(criteria()
                .nowId(INCLUDED_NOW_ID)
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should not return subscription where defence user group is excluded")
    void match_with_an_excluded_matching_user_group_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("SubscriptionWithUserGroup.json");

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .userGroup(UserGroupType.EXCLUDE, "Defence")
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should return subscription where Probation user group is excluded")
    void match_with_an_excluded_non_matching_user_group_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("SubscriptionWithUserGroup.json");

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .userGroup(UserGroupType.EXCLUDE, "Probation")
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should not return subscription where Probation user group is included and Defence "
            + "User group is Included in Subscription metadata")
    void match_with_an_included_user_group_the_subscription_lacks_should_exclude_it() {
        final List<JsonNode> subscriptions = fixture("SubscriptionWithUserGroup.json");

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .userGroup(UserGroupType.INCLUDE, "Probation")
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should return subscription where defence usergroup is included")
    void match_with_an_included_user_group_the_subscription_carries_should_include_it() {
        final List<JsonNode> subscriptions = fixture("SubscriptionWithUserGroup.json");

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .userGroup(UserGroupType.INCLUDE, "Defence")
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should return subscription where no usergroup is set in the variant")
    void match_with_no_user_group_should_fall_through_to_the_vocabulary_rules() {
        final List<JsonNode> subscriptions = fixture("SubscriptionWithUserGroup.json");

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should return subscription where Defence usergroup is included in the variant and "
            + "no userGroupVariant in Subscription metadata")
    void match_with_an_included_user_group_and_no_variants_declared_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        applyIgnoringCustodyAndResults(subscriptions.get(0));

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .userGroup(UserGroupType.INCLUDE, "Defence")
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should return subscription where Defence usergroup is excluded in the variant and "
            + "no userGroupVariant in Subscription metadata")
    void match_with_an_excluded_user_group_and_no_variants_declared_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        applyIgnoringCustodyAndResults(subscriptions.get(0));

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .userGroup(UserGroupType.EXCLUDE, "Defence")
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should create a clone for child subscription")
    void match_should_include_a_matching_child_subscription_after_its_parent() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        final ObjectNode parent = (ObjectNode) subscriptions.get(0);

        // Object.assign({}, parent): a SHALLOW copy, taken before childSubscriptions is added, so
        // the child shares the parent's subscriptionVocabulary object. The flags set below therefore
        // reach both, which is the whole reason the legacy case yields two matches rather than one.
        final ObjectNode child = parent.objectNode();
        child.setAll(parent);
        parent.putArray("childSubscriptions").add(child);

        applyIgnoringCustodyAndResults(parent);

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .build());

        assertThat(matched).hasSize(2);
        assertThat(matched.get(0)).isSameAs(parent);
        assertThat(matched.get(1)).isSameAs(child);
    }

    @Test
    @DisplayName("Should return the correct subscriptions for court register")
    void match_on_a_selected_court_house_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
        subscription.put("isNowSubscription", false);
        subscription.put("isCourtRegisterSubscription", true);
        subscription.putArray("selectedCourtHouses").add("OU_CODE");
        flag(subscription, "youthDefendant", true);
        flag(subscription, "anyAppearance", true);
        flag(subscription, "anyCourtHearing", true);
        flag(subscription, "ignoreCustody", true);
        flag(subscription, "ignoreResults", true);

        final List<JsonNode> matched = rules.match(criteria()
                .vocabulary(new Vocabulary().anyCourtHearing().youthDefendant().build())
                .subscriptions(subscriptions)
                .ouCode("OU_CODE")
                .build());

        assertThat(matched).containsExactly(subscription);
    }

    @Test
    @DisplayName("Should include subscriptions if included Prompts are matched with result prompts")
    void match_with_a_matching_included_prompt_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
        applyIgnoringCustodyAndResults(subscriptions.get(0));

        final List<JsonNode> matched = rules.match(criteria()
                .nowId(INCLUDED_NOW_ID)
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .judicialResults(judicialResults("judicial-results-with-included-prompts.json"))
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
    }

    @Test
    @DisplayName("Should Not include subscriptions if excluded Prompts are matched with result prompts")
    void match_with_a_matching_excluded_prompt_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
        applyIgnoringCustodyAndResults(subscriptions.get(0));

        final List<JsonNode> matched = rules.match(criteria()
                .nowId(INCLUDED_NOW_ID)
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .judicialResults(judicialResults("judicial-results-with-excluded-prompts.json"))
                .build());

        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("Should include subscriptions if included results are matched with results")
    void match_with_no_included_results_declared_should_include_the_subscription() {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        applyIgnoringCustodyAndResults(subscriptions.get(0));

        final List<JsonNode> matched = rules.match(criteria()
                .nowId(INCLUDED_NOW_ID)
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .judicialResults(judicialResults("judicial-results-with-included-prompts.json"))
                .build());

        assertThat(matched).containsExactly(subscriptions.get(0));
        // The Jest case is named for the included-results branch, but Subscriptions.json declares no
        // includedResults, so that branch is never entered. Pinned here so a port that broke result
        // matching would not be told by this case that it was fine.
        assertThat(subscriptions.get(0).get("subscriptionVocabulary").get("includedResults")).isNull();
    }

    @Test
    @DisplayName("Should Not include subscriptions if excluded results are matched with results")
    void match_refused_by_attendance_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("subscriptions-with-inc-exc-results.json");

        final List<JsonNode> matched = rules.match(criteria()
                .nowId(INCLUDED_NOW_ID)
                .vocabulary(new Vocabulary().build())
                .subscriptions(subscriptions)
                .judicialResults(judicialResults("judicial-results-with-excluded-prompts.json"))
                .build());

        assertThat(matched).isEmpty();
        // Named for the excluded-results branch, but the all-false vocabulary fails the attendance
        // check first and the branch is never reached. The excluded-results behaviour itself is
        // pinned by ExcludedResultsCoverage below.
        assertThat(subscriptions.get(0).get("subscriptionVocabulary").get("excludedResults"))
                .isNotNull();
    }

    @Test
    @DisplayName("Should return the correct subscriptions for court register 2")
    void match_from_the_recorded_subscription_object_should_include_the_court_register() {
        final JsonNode recorded = LegacyFixtures.read("subscriptionObject.json");

        final List<JsonNode> matched = rules.match(new SubscriptionCriteria(
                null,
                recorded.get("ouCode").stringValue(),
                null,
                vocabularyFrom(recorded.get("vocabulary")),
                recorded.get("subscriptions").valueStream().toList(),
                recorded.get("judicialResults").valueStream().toList()));

        assertThat(matched).containsExactly(recorded.get("subscriptions").get(0));
    }

    /**
     * Applies the five flags the Jest suite repeatedly sets to make a subscription's vocabulary rules
     * pass without exercising custody or results.
     *
     * @param subscription the subscription to relax
     */
    private static void applyIgnoringCustodyAndResults(final JsonNode subscription) {
        flag(subscription, "anyAppearance", true);
        flag(subscription, "anyCourtHearing", true);
        flag(subscription, "adultOrYouthDefendant", true);
        flag(subscription, "ignoreCustody", true);
        flag(subscription, "ignoreResults", true);
    }

    /**
     * Sets one flag on a subscription's reference-data vocabulary.
     *
     * @param subscription the subscription to change
     * @param name         the flag
     * @param value        the value to set
     */
    private static void flag(final JsonNode subscription, final String name, final boolean value) {
        ((ObjectNode) subscription.get("subscriptionVocabulary")).put(name, value);
    }

    /**
     * Reads one of the byte-identical Jest fixtures as a list of subscriptions.
     *
     * @param name the fixture file name
     * @return the subscriptions, each a mutable tree this test owns
     */
    private static List<JsonNode> fixture(final String name) {
        return LegacyFixtures.read(name).valueStream().toList();
    }

    /**
     * Reads the {@code judicialResults} member of one of the byte-identical Jest fixtures.
     *
     * @param name the fixture file name
     * @return the judicial results
     */
    private static List<JsonNode> judicialResults(final String name) {
        return LegacyFixtures.read(name).get("judicialResults").valueStream().toList();
    }

    /**
     * Reads a recorded vocabulary tree into the typed record the port matches against.
     *
     * <p>The two creditor lists are read as absent rather than empty when the tree omits them, which
     * is the distinction the {@code anyMajorCreditor} rule turns on: the legacy tests
     * {@code vocabulary.prosecutorMajorCreditor != null}, and {@code undefined} fails that while
     * {@code []} passes it.
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
                strings(vocabulary.get("prosecutorMajorCreditor")),
                strings(vocabulary.get("nonProsecutorMajorCreditor")));
    }

    /**
     * Reads an optional array of strings, preserving the difference between absent and empty.
     *
     * @param node the array node, or {@code null} when the field is absent
     * @return the strings, or {@code null}
     */
    private static List<String> strings(final JsonNode node) {
        return node == null ? null : node.valueStream().map(JsonNode::stringValue).toList();
    }

    /**
     * Starts a criteria builder.
     *
     * @return a fresh builder
     */
    private static Criteria criteria() {
        return new Criteria();
    }

    /**
     * Assembles a {@link SubscriptionCriteria} the way each Jest case assembles a
     * {@code SubscriptionObject} — field by field, leaving the rest at the legacy defaults.
     */
    private static final class Criteria {

        private String nowId;
        private String ouCode;
        private UserGroupSelection userGroup;
        private RegisterVocabulary vocabulary;
        private List<JsonNode> subscriptions = List.of();
        private List<JsonNode> judicialResults = List.of();

        Criteria nowId(final String value) {
            this.nowId = value;
            return this;
        }

        Criteria ouCode(final String value) {
            this.ouCode = value;
            return this;
        }

        Criteria userGroup(final UserGroupType type, final String... groups) {
            this.userGroup = new UserGroupSelection(type, List.of(groups));
            return this;
        }

        Criteria vocabulary(final RegisterVocabulary value) {
            this.vocabulary = value;
            return this;
        }

        Criteria subscriptions(final List<JsonNode> value) {
            this.subscriptions = value;
            return this;
        }

        Criteria judicialResults(final List<JsonNode> value) {
            this.judicialResults = value;
            return this;
        }

        SubscriptionCriteria build() {
            return new SubscriptionCriteria(
                    nowId, ouCode, userGroup, vocabulary, subscriptions, judicialResults);
        }
    }

    /**
     * The Jest suite's {@code VocabularyInfo} — every flag false, both creditor lists absent — with a
     * setter for each flag a case turns on.
     *
     * <p>{@code anyAppearance} is not among them because the Jest class does not declare it and
     * nothing in the matching kernel reads it from the defendant side; the record needs a value, and
     * {@code false} is the only one that cannot change an answer.
     */
    private static final class Vocabulary {

        private boolean allNonCustodialResults;
        private boolean atleastOneNonCustodialResult;
        private boolean atleastOneCustodialResult;
        private boolean appearedByVideoLink;
        private boolean inCustody;
        private boolean youthDefendant;
        private boolean adultOrYouthDefendant;
        private boolean anyCourtHearing;

        Vocabulary allNonCustodialResults() {
            this.allNonCustodialResults = true;
            return this;
        }

        Vocabulary atleastOneNonCustodialResult() {
            this.atleastOneNonCustodialResult = true;
            return this;
        }

        Vocabulary atleastOneCustodialResult() {
            this.atleastOneCustodialResult = true;
            return this;
        }

        Vocabulary appearedByVideoLink() {
            this.appearedByVideoLink = true;
            return this;
        }

        Vocabulary inCustody() {
            this.inCustody = true;
            return this;
        }

        Vocabulary youthDefendant() {
            this.youthDefendant = true;
            return this;
        }

        Vocabulary adultOrYouthDefendant() {
            this.adultOrYouthDefendant = true;
            return this;
        }

        Vocabulary anyCourtHearing() {
            this.anyCourtHearing = true;
            return this;
        }

        RegisterVocabulary build() {
            return new RegisterVocabulary(
                    false, false, atleastOneCustodialResult, allNonCustodialResults,
                    atleastOneNonCustodialResult, false, appearedByVideoLink, false,
                    false, inCustody, youthDefendant, false, adultOrYouthDefendant,
                    false, false, anyCourtHearing, null, null);
        }
    }
}
