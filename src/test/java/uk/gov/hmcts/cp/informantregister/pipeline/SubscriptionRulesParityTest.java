package uk.gov.hmcts.cp.informantregister.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;
import uk.gov.hmcts.cp.informantregister.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.informantregister.domain.SubscriptionCriteria;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
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
 * <p><strong>Three of the twenty-one pass for a reason their name does not describe</strong>, and the
 * twins say so rather than inheriting the impression:
 *
 * <ul>
 *   <li>"included results are matched with results" runs against {@code Subscriptions.json}, which
 *       declares no {@code includedResults} at all, so the result-matching branch is never
 *       entered.</li>
 *   <li>"excluded results are matched with results" is refused by the attendance check long before
 *       the excluded-results branch is reached.</li>
 *   <li>"excluded Prompts are matched with result prompts" is refused by the <em>included</em>-prompt
 *       check on the line above it: the subscription demands {@code suretyNameAndAddress} and the
 *       fixture's results carry only {@code witnessName}, so {@code excludedPrompts} is never
 *       evaluated and the case would pass with that logic deleted.</li>
 * </ul>
 *
 * <p>Each of the three is pinned with an assertion naming the branch that actually decided it, and
 * the behaviour the case is named for is driven separately in {@link BranchesNoJestCaseExecutes}.
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

    /** The service's own contract mapper, so a hand-built node reaches the port as a fetched one does. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

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
    void match_refused_by_the_included_prompt_check_should_exclude_the_subscription() {
        final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
        applyIgnoringCustodyAndResults(subscriptions.get(0));

        final List<JsonNode> matched = rules.match(criteria()
                .nowId(INCLUDED_NOW_ID)
                .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                .subscriptions(subscriptions)
                .judicialResults(judicialResults("judicial-results-with-excluded-prompts.json"))
                .build());

        assertThat(matched).isEmpty();
        // Named for the excluded-prompts branch, but the subscription first demands the included
        // prompt `suretyNameAndAddress` (SubscriptionsService.js:171) and the fixture's results carry
        // only `witnessName`, so the included check refuses at :176 and :180 is never reached. Delete
        // the excludedPrompts logic and this case still passes, so the two facts it turns on are
        // pinned here and the branch it is named for is driven in BranchesNoJestCaseExecutes.
        assertThat(subscriptions.get(0).get("subscriptionVocabulary").get("includedPrompts"))
                .isNotNull();
        assertThat(promptReferences("judicial-results-with-excluded-prompts.json"))
                .containsExactly("witnessName");
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
        // pinned by BranchesNoJestCaseExecutes below.
        assertThat(subscriptions.get(0).get("subscriptionVocabulary").get("excludedResults"))
                .isNotNull();
    }

    /**
     * Branches of the kernel that no Jest case reaches, driven from the same fixtures.
     *
     * <p>These are not twins — there is no Jest case to twin — but they are not invented inputs
     * either: each is one of the suite's own byte-identical fixtures, relaxed by the same five flags
     * the suite itself uses to get past the vocabulary gate. They exist because blind spot
     * <strong>BS-01</strong> rates the whole of subscription matching unexecuted, and two of the
     * twenty-one cases above are named for branches they never enter.
     */
    @Nested
    @DisplayName("BS-01 — branches no Jest case executes")
    class BranchesNoJestCaseExecutes {

        @Test
        @DisplayName("BS-01: an included result the register carries lets the subscription through")
        void match_with_a_matching_included_result_should_include_the_subscription() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-inc-exc-results.json");
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
        @DisplayName("BS-01: an excluded result the register carries keeps the subscription out")
        void match_with_a_matching_excluded_result_should_exclude_the_subscription() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-inc-exc-results.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));

            // The same fixture whose result type ids are fd1, fd2 and fd2: fd1 satisfies
            // includedResults, and fd2 then trips excludedResults. Both branches run, and the
            // second decides.
            final List<JsonNode> matched = rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(judicialResults("judicial-results-with-excluded-prompts.json"))
                    .build());

            assertThat(matched).isEmpty();
        }

        @Test
        @DisplayName("BS-01: a subscription that is both NOW and prison-court-register is matched twice")
        void match_should_push_a_subscription_once_per_branch_that_accepts_it() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
            subscription.put("isPrisonCourtRegisterSubscription", true);
            applyIgnoringCustodyAndResults(subscription);

            final List<JsonNode> matched = rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .build());

            // The NOW/EDT branch has no `return`, so the prison-court-register branch is evaluated
            // on the same subscription and pushes it again (SubscriptionsService.js:29-41). The
            // duplicate reaches the register as a duplicate recipient.
            assertThat(matched).containsExactly(subscription, subscription);
        }

        @Test
        @DisplayName("BS-01: an excluded prompt the register carries keeps the subscription out")
        void match_with_a_matching_excluded_prompt_should_exclude_the_subscription() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));
            // The Jest case named for this branch never reaches it, because the same subscription
            // also demands an included prompt the fixture's results do not carry. Dropping the
            // included list is the smallest change that lets `:180` decide, and it leaves the
            // excludedPrompts entry (`witnessName`) and the results exactly as the suite ships them.
            vocabularyOf(subscriptions.get(0)).remove("includedPrompts");

            final List<JsonNode> matched = rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(judicialResults("judicial-results-with-excluded-prompts.json"))
                    .build());

            assertThat(matched).isEmpty();
        }

        @Test
        @DisplayName("BS-01: an included prompt the register lacks keeps the subscription out")
        void match_with_no_matching_included_prompt_should_exclude_the_subscription() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));
            // The mirror of the case above: only the excluded list is dropped, so `:171` is the one
            // check left that can refuse, and it does.
            vocabularyOf(subscriptions.get(0)).remove("excludedPrompts");

            final List<JsonNode> matched = rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(judicialResults("judicial-results-with-excluded-prompts.json"))
                    .build());

            assertThat(matched).isEmpty();
        }

        @Test
        @DisplayName("BS-01: an included result the register lacks keeps the subscription out")
        void match_with_no_matching_included_result_should_exclude_the_subscription() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-inc-exc-results.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));

            // `judicial-results-for-court-extract.json` carries none of the two result type ids this
            // subscription lists, so `checkForMatchedResults` on includedResults answers false and
            // the subscription is refused at SubscriptionsService.js:191.
            final List<JsonNode> matched = rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(List.of(judicialResult("some-other-type-id")))
                    .build());

            assertThat(matched).isEmpty();
        }

        @Test
        @DisplayName("BS-01: an EDT subscription that is not a NOW subscription is still matched")
        void match_on_the_edt_flag_alone_should_include_the_subscription() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
            subscription.put("isNowSubscription", false);
            subscription.put("isEDTSubscription", true);
            applyIgnoringCustodyAndResults(subscription);

            // `subscription.isNowSubscription || subscription.isEDTSubscription` (:29) — the second
            // half, which no Jest case reaches.
            final List<JsonNode> matched = rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .build());

            assertThat(matched).containsExactly(subscription);
        }

        @Test
        @DisplayName("BS-01: a child subscription its own rules refuse is left behind")
        void match_should_leave_behind_a_child_subscription_whose_rules_refuse() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode parent = (ObjectNode) subscriptions.get(0);
            applyIgnoringCustodyAndResults(parent);

            // A DEEP copy this time, so the child carries a vocabulary of its own. Every flag on it
            // stays false, so the child fails the attendance check the parent passes and only the
            // parent is pushed (SubscriptionsService.js:33-35).
            final ObjectNode child = parent.deepCopy();
            ((ObjectNode) child.get("subscriptionVocabulary")).put("anyAppearance", false);
            parent.putArray("childSubscriptions").add(child);

            final List<JsonNode> matched = rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .build());

            assertThat(matched).containsExactly(parent);
        }

        @Test
        @DisplayName("BS-01: a prison-court-register subscription its vocabulary refuses is not matched")
        void match_on_the_prison_register_branch_should_honour_the_vocabulary_gate() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
            subscription.put("isNowSubscription", false);
            subscription.put("isPrisonCourtRegisterSubscription", true);

            // Only the prison-register branch can accept this one, and its second operand
            // (`matchVocabularyRules`, :39) refuses because every rule flag is still false.
            final List<JsonNode> matched = rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .build());

            assertThat(matched).isEmpty();
        }

        @Test
        @DisplayName("BS-01: an informant code equal to the register's code matches on its own")
        void match_on_an_informant_code_should_include_the_subscription() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
            subscription.put("isNowSubscription", false);
            subscription.put("informantCode", "OU_CODE");
            applyIgnoringCustodyAndResults(subscription);

            // `matchProsecutor` (:48-50). No fixture in the suite declares an informantCode, so this
            // branch — one of the two the informant register itself relies on — is otherwise unrun.
            final List<JsonNode> matched = rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .ouCode("OU_CODE")
                    .build());

            assertThat(matched).containsExactly(subscription);
        }

        @Test
        @DisplayName("BS-01: the code comparisons are case-sensitive and string-typed, as `===` is")
        void match_should_compare_codes_exactly_as_the_legacy_does() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
            subscription.put("isNowSubscription", false);
            subscription.put("informantCode", "ou_code");
            subscription.putArray("selectedCourtHouses").add("ou_code").add(1234);
            applyIgnoringCustodyAndResults(subscription);

            // `includes` and `===` are both exact: a different case does not match, and neither does
            // a number that prints the same (:49, :53).
            assertThat(rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .ouCode("OU_CODE")
                    .build())).isEmpty();

            assertThat(rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .ouCode("1234")
                    .build())).isEmpty();
        }

        @Test
        @DisplayName("BS-01: an includedNOWS of [] rejects the very NOW an absent one would allow")
        void match_should_distinguish_an_empty_included_nows_from_an_absent_one() {
            final List<JsonNode> empty = fixture("Subscriptions.json");
            applyIgnoringCustodyAndResults(empty.get(0));
            ((ObjectNode) empty.get(0)).putArray("includedNOWS");

            final List<JsonNode> absent = fixture("Subscriptions.json");
            applyIgnoringCustodyAndResults(absent.get(0));
            ((ObjectNode) absent.get(0)).remove("includedNOWS");

            // `subscription.includedNOWS && !subscription.includedNOWS.includes(nowId)` (:68). An
            // empty array is truthy, so the membership test runs and fails for every id; an absent
            // one is falsy and skips the test altogether. Same NOW id, opposite answers.
            assertThat(rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(empty)
                    .build())).isEmpty();

            assertThat(rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(absent)
                    .build())).containsExactly(absent.get(0));
        }

        @Test
        @DisplayName("BS-01: userGroupVariants of [] and of null both pass an included selection")
        void match_should_read_an_empty_and_a_null_user_group_variants_as_the_legacy_does() {
            // `subscription.userGroupVariants && subscription.userGroupVariants.length` (:99) is
            // falsy for both, and `subscription.userGroupVariants === undefined` (:103) is false for
            // both, so the function falls off its end and the selection is not treated as failed.
            for (final String shape : List.of("[]", "null")) {
                final List<JsonNode> subscriptions = fixture("SubscriptionWithUserGroup.json");
                ((ObjectNode) subscriptions.get(0))
                        .set("userGroupVariants", MAPPER.readTree(shape));

                assertThat(rules.match(criteria()
                        .vocabulary(new Vocabulary().build())
                        .subscriptions(subscriptions)
                        .userGroup(UserGroupType.INCLUDE, "Probation")
                        .build())).containsExactly(subscriptions.get(0));

                assertThat(rules.match(criteria()
                        .vocabulary(new Vocabulary().build())
                        .subscriptions(subscriptions)
                        .userGroup(UserGroupType.EXCLUDE, "Defence")
                        .build())).containsExactly(subscriptions.get(0));
            }
        }

        @Test
        @DisplayName("BS-01: a subscription with rules on but no subscriptionVocabulary is matched")
        void match_with_no_subscription_vocabulary_should_include_the_subscription() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode subscription = (ObjectNode) subscriptions.get(0);
            subscription.remove("subscriptionVocabulary");

            // `if (subscription.subscriptionVocabulary)` (:123) is the whole gate: without it the
            // ninety lines of rule checking are skipped and the function returns true at :209.
            final List<JsonNode> matched = rules.match(criteria()
                    .vocabulary(new Vocabulary().build())
                    .subscriptions(subscriptions)
                    .build());

            assertThat(matched).containsExactly(subscription);
        }

        @Test
        @DisplayName("BS-01: a CPS-prosecuted subscription short-circuits every rule below it")
        void match_on_the_cps_shortcut_should_include_the_subscription() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            flag(subscriptions.get(0), "isCpsProsecuted", true);

            // Every other rule flag is still false, so attendance would refuse at :130 — but the CPS
            // check at :125 returns true before it is reached.
            final List<JsonNode> matched = rules.match(criteria()
                    .vocabulary(new Vocabulary().isCpsProsecuted().build())
                    .subscriptions(subscriptions)
                    .build());

            assertThat(matched).containsExactly(subscriptions.get(0));
        }

        @Test
        @DisplayName("BS-01: the CPS check is `=== true`, so a truthy non-boolean does not short-circuit")
        void match_on_a_non_boolean_cps_flag_should_not_short_circuit() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            ((ObjectNode) subscriptions.get(0).get("subscriptionVocabulary"))
                    .put("isCpsProsecuted", "true");

            assertThat(rules.match(criteria()
                    .vocabulary(new Vocabulary().isCpsProsecuted().build())
                    .subscriptions(subscriptions)
                    .build())).isEmpty();
        }

        @Test
        @DisplayName("BS-01: an appearance the rule names is matched, in person and by video alike")
        void match_should_answer_every_attendance_combination_the_legacy_answers() {
            // `anyAppearance && (appearedByVideoLink || appearedInPerson)` (:246) — the second of the
            // legacy's two anyAppearance clauses, and the one no Jest case reaches: every case in the
            // suite leaves both attendance flags false and lands on the first clause instead.
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    openVocabulary().appearedInPerson())).hasSize(1);
            assertThat(matchedWith(
                    withRules("appearedInPerson", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    openVocabulary().appearedInPerson())).hasSize(1);
            assertThat(matchedWith(
                    withRules("appearedByVideoLink", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    openVocabulary().appearedInPerson())).isEmpty();
        }

        @Test
        @DisplayName("BS-01: a court-hearing rule is matched by the country it names")
        void match_should_answer_every_court_hearing_combination_the_legacy_answers() {
            // `anyCourtHearing && englishCourtHearing` and `anyCourtHearing && welshCourtHearing`
            // (:316-317) — the two disjuncts after the one the suite drives — then the direct
            // clauses at :321 and :325.
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().adultOrYouthDefendant().englishCourtHearing())).hasSize(1);
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().adultOrYouthDefendant().welshCourtHearing())).hasSize(1);
            assertThat(matchedWith(
                    withRules("anyAppearance", "englishCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().adultOrYouthDefendant().englishCourtHearing())).hasSize(1);
            assertThat(matchedWith(
                    withRules("anyAppearance", "welshCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().adultOrYouthDefendant().welshCourtHearing())).hasSize(1);
            assertThat(matchedWith(
                    withRules("anyAppearance", "welshCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().adultOrYouthDefendant().englishCourtHearing())).isEmpty();
        }

        @Test
        @DisplayName("BS-01: a defendant rule is matched by the age band it names")
        void match_should_answer_every_defendant_combination_the_legacy_answers() {
            // `adultOrYouthDefendant && adultDefendant` (:332) and the direct clause at :340.
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().anyCourtHearing().adultDefendant())).hasSize(1);
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().anyCourtHearing().adultDefendant())).hasSize(1);
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "youthDefendant",
                            "ignoreCustody", "ignoreResults"),
                    new Vocabulary().anyCourtHearing().adultDefendant())).isEmpty();
        }

        @Test
        @DisplayName("BS-01: a custody rule is matched by the location it names, and only that one")
        void match_should_answer_every_custody_combination_the_legacy_answers() {
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "inCustody", "custodyLocationIsPolice", "ignoreResults"),
                    openVocabulary().custodyLocationIsPolice())).hasSize(1);
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "inCustody", "custodyLocationIsPrison", "ignoreResults"),
                    openVocabulary().custodyLocationIsPrison())).hasSize(1);
            // `inCustody && police && !prison` and `inCustody && !police && prison` are the only two
            // located forms the legacy writes (:352-364), so naming both locations matches neither,
            // however the defendant is actually held.
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "inCustody", "custodyLocationIsPolice", "custodyLocationIsPrison",
                            "ignoreResults"),
                    openVocabulary().custodyLocationIsPolice().custodyLocationIsPrison().inCustody()))
                    .isEmpty();
        }

        @Test
        @DisplayName("BS-01: a custodial-result rule needs the custodial flag to agree exactly")
        void match_should_require_the_custodial_flag_to_agree() {
            // `subscriptionVocabulary.atleastOneCustodialResult === vocabulary.atleastOneCustodialResult`
            // (:373, :378) is strict, so a subscription that never declares the flag cannot satisfy
            // either clause however the register's own results read.
            final List<JsonNode> undeclared = withRules("anyAppearance", "anyCourtHearing",
                    "adultOrYouthDefendant", "ignoreCustody", "atleastOneNonCustodialResult");
            vocabularyOf(undeclared.get(0)).remove("atleastOneCustodialResult");
            assertThat(matchedWith(undeclared,
                    openVocabulary().atleastOneNonCustodialResult())).isEmpty();

            // Declared and agreeing, on the clause the Jest suite does not drive.
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "allNonCustodialResults"),
                    openVocabulary().allNonCustodialResults())).hasSize(1);

            // Declared and disagreeing.
            assertThat(matchedWith(
                    withRules("anyAppearance", "anyCourtHearing", "adultOrYouthDefendant",
                            "ignoreCustody", "allNonCustodialResults", "atleastOneCustodialResult"),
                    openVocabulary().allNonCustodialResults())).isEmpty();
        }
    }

    /**
     * The major-creditor rules, which the informant register can never satisfy and other flows can.
     *
     * <p>Blind spot <strong>BS-01</strong> leaves every positive path here unrun: the informant
     * register builds its vocabulary with the two-argument constructor, so both creditor lists are
     * always empty and {@code checkIfMajorCreditorTypeMatch} answers on its first clause alone. The
     * cases below supply the lists a four-argument caller would, and are read straight off
     * {@code SubscriptionsService.js:258-311}.
     */
    @Nested
    @DisplayName("BS-01 — the major-creditor rules")
    class MajorCreditorRules {

        /** {@code ResultDefinitionConstants.FCOMP}. */
        private static final String FCOMP = "ae89b99c-e0e3-47b5-b218-24d4fca3ca53";

        @Test
        @DisplayName("a prosecutor major creditor the compensation result names lets it through")
        void match_with_a_listed_prosecutor_creditor_should_include_the_subscription() {
            assertThat(rules.match(criteria()
                    .subscriptions(creditorSubscription("prosecutorMajorCreditor"))
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant()
                            .prosecutorMajorCreditor("ACME LTD").build())
                    .judicialResults(List.of(compensationNaming("ACME LTD")))
                    .build())).hasSize(1);
        }

        @Test
        @DisplayName("a non-prosecutor major creditor the compensation result names lets it through")
        void match_with_a_listed_non_prosecutor_creditor_should_include_the_subscription() {
            assertThat(rules.match(criteria()
                    .subscriptions(creditorSubscription("nonProsecutorMajorCreditor"))
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant()
                            .nonProsecutorMajorCreditor("ACME LTD").build())
                    .judicialResults(List.of(compensationNaming("ACME LTD")))
                    .build())).hasSize(1);
        }

        @Test
        @DisplayName("a creditor the compensation result does not name keeps it out")
        void match_with_an_unlisted_creditor_should_exclude_the_subscription() {
            assertThat(rules.match(criteria()
                    .subscriptions(creditorSubscription("prosecutorMajorCreditor"))
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant()
                            .prosecutorMajorCreditor("ACME LTD").build())
                    .judicialResults(List.of(compensationNaming("SOMEONE ELSE")))
                    .build())).isEmpty();
        }

        @Test
        @DisplayName("`anyMajorCreditor` asks only whether a list is present, empty or not")
        void match_on_any_major_creditor_should_turn_on_the_lists_presence() {
            // `vocabulary.prosecutorMajorCreditor != null` (:275) separates an absent list from an
            // empty one, and the informant register's own vocabulary always fails it.
            assertThat(rules.match(criteria()
                    .subscriptions(creditorSubscription("anyMajorCreditor"))
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant()
                            .prosecutorMajorCreditor().build())
                    .build())).hasSize(1);

            assertThat(rules.match(criteria()
                    .subscriptions(creditorSubscription("anyMajorCreditor"))
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .build())).isEmpty();
        }

        @Test
        @DisplayName("a compensation result carrying no prompts is refused, not read as a non-match")
        void match_with_a_compensation_result_carrying_no_prompts_should_be_refused() {
            // `for (var prompt of result.judicialResultPrompts)` (:287) is unguarded, so this is a
            // TypeError in the legacy and deviations register entry 7 here.
            assertThatThrownBy(() -> rules.match(criteria()
                    .subscriptions(creditorSubscription("prosecutorMajorCreditor"))
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant()
                            .prosecutorMajorCreditor("ACME LTD").build())
                    .judicialResults(List.of(judicialResult(FCOMP)))
                    .build()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        /**
         * A subscription whose only vocabulary rule is the named creditor one.
         *
         * @param rule the creditor flag to turn on
         * @return the subscription, in a one-element list
         */
        private List<JsonNode> creditorSubscription(final String rule) {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));
            flag(subscriptions.get(0), rule, true);
            return subscriptions;
        }

        /**
         * A financial-compensation result naming one creditor on a creditor-name prompt.
         *
         * @param creditor the creditor name the prompt carries
         * @return the judicial result
         */
        private JsonNode compensationNaming(final String creditor) {
            final ObjectNode result = (ObjectNode) judicialResult(FCOMP);
            result.putArray("judicialResultPrompts").addObject()
                    .put("promptReference", "cREDNAMEOrganisationName")
                    .put("type", "NAMEADDRESS")
                    .put("value", creditor);
            return result;
        }
    }

    /**
     * Array elements the legacy reads a property off, and the {@code TypeError} a {@code null} one
     * raises there.
     *
     * <p>Not a branch-coverage gap but a behaviour one: reading a null element as "a candidate that
     * matches nothing" would let this port emit recipients on a payload the legacy emits nothing at
     * all for. Each case names the legacy line that does the dereferencing; the refusal itself is
     * deviations register entry 7.
     */
    @Nested
    @DisplayName("null array elements are refused, as the legacy's TypeError refuses them")
    class NullElementsAreRefused {

        @Test
        @DisplayName("a null candidate subscription — `matchCourtHouse` reads it first (:19, :53)")
        void match_with_a_null_candidate_should_be_refused() {
            final List<JsonNode> subscriptions = new ArrayList<>(fixture("Subscriptions.json"));
            subscriptions.add(0, null);

            assertThatThrownBy(() -> rules.match(criteria()
                    .vocabulary(new Vocabulary().build())
                    .subscriptions(subscriptions)
                    .build()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("a null child subscription — every path through :61-78 reads it")
        void match_with_a_null_child_should_be_refused() {
            final List<JsonNode> subscriptions = fixture("Subscriptions.json");
            final ObjectNode parent = (ObjectNode) subscriptions.get(0);
            applyIgnoringCustodyAndResults(parent);
            parent.putArray("childSubscriptions").addNull();

            assertThatThrownBy(() -> rules.match(criteria()
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .build()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("a null judicial result, even behind one that already matched — `filter` (:213)")
        void prompts_match_with_a_null_judicial_result_should_be_refused() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));

            final List<JsonNode> results =
                    new ArrayList<>(judicialResults("judicial-results-with-included-prompts.json"));
            results.add(null);

            // The legacy filters the whole list before matching any of it, so the null at the end
            // still refuses although the first result would have satisfied includedPrompts.
            assertThatThrownBy(() -> rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(results)
                    .build()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("a null prompt on a result that is reached — `getMatchingPrompt` (:224)")
        void prompts_match_with_a_null_prompt_should_be_refused() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));

            final ObjectNode result = (ObjectNode) judicialResult("some-type-id");
            result.putArray("judicialResultPrompts").addNull();

            assertThatThrownBy(() -> rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(List.of(result))
                    .build()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("a null judicial result the result check reads — `some` over a non-empty list")
        void results_match_with_a_null_judicial_result_should_be_refused() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-inc-exc-results.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));

            final List<JsonNode> results = new ArrayList<>();
            results.add(null);

            assertThatThrownBy(() -> rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(results)
                    .build()))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("an EMPTY reference-data result list reads nothing, so a null is not reached")
        void results_match_with_an_empty_reference_data_list_should_read_nothing() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-inc-exc-results.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));
            // `[]` is truthy, so the legacy enters the branch — but `[].some(cb)` never runs `cb`,
            // and it is `cb` that dereferences the judicial result (:234-236). Refusing here would
            // lose a register the legacy produces, so the empty list simply answers false.
            ((ObjectNode) subscriptions.get(0).get("subscriptionVocabulary"))
                    .putArray("includedResults");
            ((ObjectNode) subscriptions.get(0).get("subscriptionVocabulary"))
                    .putArray("excludedResults");

            final List<JsonNode> results = new ArrayList<>();
            results.add(null);

            assertThat(rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(results)
                    .build())).isEmpty();
        }

        @Test
        @DisplayName("a reference-data prompt with no resultPromptReference — `.toLowerCase()` (:226)")
        void prompts_match_with_a_prompt_missing_its_reference_should_be_refused() {
            final List<JsonNode> subscriptions = fixture("subscriptions-with-prompts.json");
            applyIgnoringCustodyAndResults(subscriptions.get(0));
            ((ObjectNode) subscriptions.get(0).get("subscriptionVocabulary"))
                    .putArray("includedPrompts").addObject().put("resultPromptId", "an-id");

            assertThatThrownBy(() -> rules.match(criteria()
                    .nowId(INCLUDED_NOW_ID)
                    .vocabulary(new Vocabulary().anyCourtHearing().adultOrYouthDefendant().build())
                    .subscriptions(subscriptions)
                    .judicialResults(
                            judicialResults("judicial-results-with-excluded-prompts.json"))
                    .build()))
                    .isInstanceOf(TransformationFailedException.class);
        }
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
     * A subscription's reference-data vocabulary, as a node a case can add to or remove from.
     *
     * @param subscription the subscription to read
     * @return its {@code subscriptionVocabulary}
     */
    private static ObjectNode vocabularyOf(final JsonNode subscription) {
        return (ObjectNode) subscription.get("subscriptionVocabulary");
    }

    /**
     * {@code Subscriptions.json} with exactly the named vocabulary rules turned on.
     *
     * <p>Every flag the fixture ships is {@code false}, so a case names each gate it needs to get
     * past as well as the one it is about. That is deliberately verbose: a shared "everything on"
     * helper would silently satisfy the gate under test.
     *
     * @param flags the vocabulary flags to set true
     * @return the subscription, in the one-element list the fixture is
     */
    private static List<JsonNode> withRules(final String... flags) {
        final List<JsonNode> subscriptions = fixture("Subscriptions.json");
        for (final String name : flags) {
            flag(subscriptions.get(0), name, true);
        }
        return subscriptions;
    }

    /**
     * Matches one subscription list against one vocabulary, with no NOW id and no user group.
     *
     * @param subscriptions the candidates
     * @param vocabulary    the defendant vocabulary, still under construction
     * @return the matched subscriptions
     */
    private List<JsonNode> matchedWith(
            final List<JsonNode> subscriptions, final Vocabulary vocabulary) {

        return rules.match(criteria()
                .vocabulary(vocabulary.build())
                .subscriptions(subscriptions)
                .build());
    }

    /**
     * A vocabulary that satisfies the court and defendant gates, leaving the rest to the case.
     *
     * @return the vocabulary, still under construction
     */
    private static Vocabulary openVocabulary() {
        return new Vocabulary().anyCourtHearing().adultOrYouthDefendant();
    }

    /**
     * A bare judicial result carrying nothing but its type id.
     *
     * @param typeId the {@code judicialResultTypeId}
     * @return the result
     */
    private static JsonNode judicialResult(final String typeId) {
        return MAPPER.createObjectNode().put("judicialResultTypeId", typeId);
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
     * Every non-empty {@code promptReference} in one of the byte-identical Jest fixtures.
     *
     * @param name the fixture file name
     * @return the prompt references, in document order
     */
    private static List<String> promptReferences(final String name) {
        return judicialResults(name).stream()
                .flatMap(result -> Json.array(result, "judicialResultPrompts").stream())
                .map(prompt -> Json.text(prompt, "promptReference"))
                .filter(reference -> reference != null)
                .toList();
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
        private boolean appearedInPerson;
        private boolean isCpsProsecuted;
        private boolean inCustody;
        private boolean custodyLocationIsPolice;
        private boolean custodyLocationIsPrison;
        private boolean youthDefendant;
        private boolean adultDefendant;
        private boolean adultOrYouthDefendant;
        private boolean anyCourtHearing;
        private boolean englishCourtHearing;
        private boolean welshCourtHearing;
        private List<String> prosecutorMajorCreditor;
        private List<String> nonProsecutorMajorCreditor;

        Vocabulary appearedInPerson() {
            this.appearedInPerson = true;
            return this;
        }

        Vocabulary isCpsProsecuted() {
            this.isCpsProsecuted = true;
            return this;
        }

        Vocabulary custodyLocationIsPolice() {
            this.custodyLocationIsPolice = true;
            return this;
        }

        Vocabulary custodyLocationIsPrison() {
            this.custodyLocationIsPrison = true;
            return this;
        }

        Vocabulary adultDefendant() {
            this.adultDefendant = true;
            return this;
        }

        Vocabulary englishCourtHearing() {
            this.englishCourtHearing = true;
            return this;
        }

        Vocabulary welshCourtHearing() {
            this.welshCourtHearing = true;
            return this;
        }

        Vocabulary prosecutorMajorCreditor(final String... creditors) {
            this.prosecutorMajorCreditor = List.of(creditors);
            return this;
        }

        Vocabulary nonProsecutorMajorCreditor(final String... creditors) {
            this.nonProsecutorMajorCreditor = List.of(creditors);
            return this;
        }

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
                    custodyLocationIsPolice, custodyLocationIsPrison, atleastOneCustodialResult,
                    allNonCustodialResults, atleastOneNonCustodialResult, appearedInPerson,
                    appearedByVideoLink, isCpsProsecuted, false, inCustody, youthDefendant,
                    adultDefendant, adultOrYouthDefendant, welshCourtHearing, englishCourtHearing,
                    anyCourtHearing, prosecutorMajorCreditor, nonProsecutorMajorCreditor);
        }
    }
}
