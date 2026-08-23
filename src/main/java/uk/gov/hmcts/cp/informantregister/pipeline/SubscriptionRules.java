package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.informantregister.domain.SubscriptionCriteria;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.informantregister.domain.UserGroupSelection;
import uk.gov.hmcts.cp.informantregister.domain.UserGroupType;

/**
 * Decides which of a set of candidate subscriptions a register's defendants match.
 *
 * <p>A port of {@code NowsHelper/service/SubscriptionsService.js} — the shared matching kernel, used
 * by every register and NOW flow in the legacy function app and reached here through
 * {@link SubscriptionMatcher}. It is pure: criteria in, the matched subset out, no I/O and no clock
 * (constitution Principle V).
 *
 * <p><strong>The four branches are not exclusive, and the order they are written in decides the
 * answer.</strong> {@code getSubscriptions} tests a court-house match, then an informant-code match,
 * then a NOW-or-EDT match, then a prison-court-register match. The first two {@code return} out of
 * the callback when they match; the third does <em>not</em>, so a subscription that is both a NOW
 * subscription and a prison-court-register subscription is pushed <strong>twice</strong>. That is
 * reproduced rather than tidied, because the count reaches the register as the number of recipients.
 *
 * <p><strong>Behaviours preserved deliberately.</strong> Each of these looks like a defect and is
 * ported as written, under constitution Principle I:
 *
 * <ul>
 *   <li><strong>The whole vocabulary gate is skipped when {@code applySubscriptionRules} is
 *       falsy.</strong> Such a subscription matches on the strength of its branch alone. All forty
 *       real informant-register subscriptions in the captured reference data are of that kind, so in
 *       this flow the ninety-odd lines of rule checking below are, as captured, unreachable.</li>
 *   <li><strong>An {@code includedNOWS} of {@code []} rejects every NOW.</strong> The legacy tests
 *       the array for truthiness, and an empty array is truthy, so the membership test then fails for
 *       any id at all.</li>
 *   <li><strong>The major-creditor rules can never pass in this flow.</strong> The informant register
 *       builds its vocabulary with the two-argument constructor, which leaves both creditor lists
 *       empty, and {@link #creditorTypeMatches} needs a non-empty one.</li>
 *   <li><strong>Absent and null are not the same for {@code userGroupVariants}.</strong> The legacy
 *       tests {@code === undefined} there, so a subscription that declares the field as JSON
 *       {@code null} takes a different branch from one that omits it.</li>
 * </ul>
 *
 * <p><strong>A {@code null} element is refused, not treated as a non-match.</strong> Wherever the
 * legacy reads a property off an array element — a candidate subscription, a child subscription, a
 * judicial result, a judicial result prompt — a {@code null} there is a {@code TypeError} and the
 * whole hearing produces nothing. {@link Json#dereferencedElement} reproduces that reach exactly, including
 * where JavaScript's laziness means an element is never read: {@code some} and {@code find} stop at
 * the first answer, {@code filter} completes the pass, and an empty reference-data list never runs
 * its callback at all. Answering "no match" instead would emit recipients the legacy never emitted
 * ({@code doc/DEVIATIONS.md} entry 7).
 *
 * <p><strong>{@code matchCpsProsecuted} is not ported.</strong> The legacy declares it
 * ({@code SubscriptionsService.js:56-59}) and never calls it; the CPS check that does run is written
 * inline at {@code :125}. Porting the dead copy would suggest a second CPS rule exists.
 */
public final class SubscriptionRules {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRules.class);

    /** {@code NowsHelper/constants/ResultDefinitionConstants.js} {@code FCOMP}. */
    private static final String FINANCIAL_COMPENSATION = "ae89b99c-e0e3-47b5-b218-24d4fca3ca53";

    /** {@code NowsHelper/constants/PromptType.js} {@code CREDITOR_NAME}. */
    private static final String CREDITOR_NAME = "cREDNAMEOrganisationName";

    /** The prompt type the legacy matches by substring rather than by equality. */
    private static final String NAME_ADDRESS = "NAMEADDRESS";

    /** The judicial result's prompt list, read on four legacy lines between them. */
    private static final String PROMPTS = "judicialResultPrompts";

    /**
     * The subscriptions the criteria match, in the order the legacy pushes them.
     *
     * @param criteria what to match against
     * @return the matched subscriptions; the same nodes that were offered, never copies
     */
    public List<JsonNode> match(final SubscriptionCriteria criteria) {
        final List<JsonNode> matched = new ArrayList<>();

        for (final JsonNode candidate : criteria.subscriptions()) {

            // `matchCourtHouse` reads `subscription.selectedCourtHouses` before anything else
            // (SubscriptionsService.js:19, :53), so a null candidate is a TypeError there whatever
            // the criteria say, and a refusal here.
            final JsonNode subscription = Json.dereferencedElement(candidate, "subscriptions");

            if (courtHouseMatches(subscription, criteria.ouCode())
                    && vocabularyRulesMatch(subscription, criteria)) {
                matched.add(subscription);
                continue;
            }

            if (prosecutorMatches(subscription, criteria.ouCode())
                    && vocabularyRulesMatch(subscription, criteria)) {
                matched.add(subscription);
                continue;
            }

            if ((Json.truthy(subscription, "isNowSubscription")
                    || Json.truthy(subscription, "isEDTSubscription"))
                    && subscriptionRulesMatch(criteria, subscription)) {
                matched.add(subscription);
                for (final JsonNode candidateChild
                        : Json.array(subscription, "childSubscriptions")) {
                    // Every path through `matchSubscriptionRules` reads a property off the child —
                    // `excludedNOWS` when a NOW id is set (:64), `userGroupVariants` when a user
                    // group is (:99), `applySubscriptionRules` otherwise (:116) — so a null child is
                    // a TypeError on all of them.
                    final JsonNode child = Json.dereferencedElement(candidateChild, "childSubscriptions");
                    if (subscriptionRulesMatch(criteria, child)) {
                        matched.add(child);
                    }
                }
            }

            // Deliberately not an `else`: the legacy's NOW/EDT branch has no `return`, so a
            // subscription that is both is pushed twice (SubscriptionsService.js:29-41).
            if (Json.truthy(subscription, "isPrisonCourtRegisterSubscription")
                    && vocabularyRulesMatch(subscription, criteria)) {
                matched.add(subscription);
            }
        }

        return List.copyOf(matched);
    }

    /**
     * Whether the subscription names this register's code among its selected court houses.
     *
     * <p>Ports {@code matchCourtHouse} ({@code SubscriptionsService.js:52-54}). An absent code can
     * never match: JSON cannot carry {@code undefined}, so the legacy's
     * {@code selectedCourtHouses.includes(undefined)} is false for every reference-data array.
     *
     * @param subscription the candidate subscription
     * @param ouCode       the code to look for
     * @return whether the code is listed
     */
    private static boolean courtHouseMatches(final JsonNode subscription, final String ouCode) {
        if (!Json.truthy(subscription, "selectedCourtHouses") || ouCode == null) {
            return false;
        }
        return Json.array(subscription, "selectedCourtHouses").stream()
                .anyMatch(courtHouse -> courtHouse.isString()
                        && ouCode.equals(courtHouse.stringValue()));
    }

    /**
     * Whether the subscription's informant code is this register's code.
     *
     * <p>Ports {@code matchProsecutor} ({@code SubscriptionsService.js:48-50}).
     *
     * @param subscription the candidate subscription
     * @param ouCode       the code to compare against
     * @return whether the codes are the same
     */
    private static boolean prosecutorMatches(final JsonNode subscription, final String ouCode) {
        return Json.truthy(subscription, "informantCode")
                && Objects.equals(Json.text(subscription, "informantCode"), ouCode);
    }

    /**
     * The NOW-or-EDT branch's rule set: included and excluded NOWs, then user groups or vocabulary.
     *
     * <p>Ports {@code matchSubscriptionRules} ({@code SubscriptionsService.js:61-78}).
     *
     * @param criteria     what to match against
     * @param subscription the candidate subscription, which may be a child subscription
     * @return whether the subscription is matched
     */
    private static boolean subscriptionRulesMatch(
            final SubscriptionCriteria criteria, final JsonNode subscription) {

        final String nowId = criteria.nowId();
        final boolean hasNowId = nowId != null && !nowId.isEmpty();

        if (hasNowId && contains(Json.array(subscription, "excludedNOWS"), nowId)) {
            return false;
        }
        if (hasNowId && Json.truthy(subscription, "includedNOWS")
                && !contains(Json.array(subscription, "includedNOWS"), nowId)) {
            return false;
        }

        if (criteria.userGroup() != null) {
            return userGroupsMatch(subscription, criteria);
        }
        return vocabularyRulesMatch(subscription, criteria);
    }

    /**
     * Whether the variant's user groups allow this subscription, and then whether its rules do.
     *
     * <p>Ports {@code matchVariantUserGroupsWithSubscriptionMetadata}
     * ({@code SubscriptionsService.js:80-96}).
     *
     * @param subscription the candidate subscription
     * @param criteria     what to match against
     * @return whether the subscription is matched
     */
    private static boolean userGroupsMatch(
            final JsonNode subscription, final SubscriptionCriteria criteria) {

        final UserGroupSelection selection = criteria.userGroup();
        if (includedUserGroupsAreMissing(selection, subscription)
                || excludedUserGroupsArePresent(selection, subscription)) {
            return false;
        }
        return vocabularyRulesMatch(subscription, criteria);
    }

    /**
     * Whether an included-group selection names a group the subscription does not carry.
     *
     * <p>Ports {@code checkIfIncludedUserGroupsAreNotMatchingWithSubscription}
     * ({@code SubscriptionsService.js:98-106}), including the two shapes it treats differently: a
     * subscription that declares no {@code userGroupVariants} <em>at all</em> fails every included
     * selection, while one that declares it as JSON {@code null} — which is not {@code undefined} —
     * falls through the legacy's implicit {@code return undefined} and passes.
     *
     * @param selection    the variant's user groups
     * @param subscription the candidate subscription
     * @return whether the selection is unsatisfied
     */
    private static boolean includedUserGroupsAreMissing(
            final UserGroupSelection selection, final JsonNode subscription) {

        if (selection.type() != UserGroupType.INCLUDE) {
            return false;
        }
        if (Json.nonEmptyArray(subscription, "userGroupVariants")) {
            final List<JsonNode> variants = Json.array(subscription, "userGroupVariants");
            return selection.userGroups().stream()
                    .anyMatch(userGroup -> !contains(variants, userGroup));
        }
        return Json.at(subscription, "userGroupVariants") == null;
    }

    /**
     * Whether an excluded-group selection names a group the subscription carries.
     *
     * <p>Ports {@code checkIfExcludedUserGroupAreMatchingWithSubscription}
     * ({@code SubscriptionsService.js:108-112}).
     *
     * @param selection    the variant's user groups
     * @param subscription the candidate subscription
     * @return whether the selection is violated
     */
    private static boolean excludedUserGroupsArePresent(
            final UserGroupSelection selection, final JsonNode subscription) {

        if (selection.type() != UserGroupType.EXCLUDE
                || !Json.nonEmptyArray(subscription, "userGroupVariants")) {
            return false;
        }
        final List<JsonNode> variants = Json.array(subscription, "userGroupVariants");
        return selection.userGroups().stream().anyMatch(userGroup -> contains(variants, userGroup));
    }

    /**
     * The vocabulary gate: attendance, creditor type, court house, defendant, custody, results, and
     * the four prompt and result lists.
     *
     * <p>Ports {@code matchVocabularyRules} ({@code SubscriptionsService.js:114-210}).
     *
     * <p>The legacy distinguishes a vocabulary that is {@code undefined} (refused here) from one that
     * is {@code null} (which reaches the checks below and throws on the first property read). Both
     * are {@code null} in Java, and the second shape is unreachable: the only producer,
     * {@code VocabularyService.getVocabularyInfo}, always returns an object.
     *
     * @param subscription the candidate subscription
     * @param criteria     what to match against
     * @return whether the subscription's rules are satisfied
     */
    private static boolean vocabularyRulesMatch(
            final JsonNode subscription, final SubscriptionCriteria criteria) {

        if (!Json.truthy(subscription, "applySubscriptionRules")) {
            return true;
        }
        if (criteria.vocabulary() == null) {
            refused(subscription, "no vocabulary defined");
            return false;
        }

        final JsonNode rules = Json.at(subscription, "subscriptionVocabulary");
        if (!Json.truthy(rules)) {
            return true;
        }

        final RegisterVocabulary vocabulary = criteria.vocabulary();

        if (isTrue(rules, "isCpsProsecuted") && vocabulary.isCpsProsecuted()) {
            return true;
        }
        if (!attendanceMatches(rules, vocabulary)) {
            return refused(subscription, "checkIfAttendanceTypeMatch failed");
        }
        if (!creditorTypeMatches(rules, criteria)) {
            return refused(subscription, "checkIfMajorCreditorTypeMatch failed");
        }
        if (!courtHearingMatches(rules, vocabulary)) {
            return refused(subscription, "checkIfCourtHouseMatch failed");
        }
        if (!defendantMatches(rules, vocabulary)) {
            return refused(subscription, "checkIfDefendantMatch failed");
        }
        if (!custodyMatches(rules, vocabulary)) {
            return refused(subscription, "checkIfCustodyMatch failed");
        }
        if (!custodialResultMatches(rules, vocabulary)) {
            return refused(subscription, "checkIfCustodialResultMatch failed");
        }
        return promptsAndResultsMatch(rules, subscription, criteria);
    }

    /**
     * The four list checks at the end of the vocabulary gate.
     *
     * <p>Each is entered only when the subscription declares the list, and each is a plain
     * membership test — included lists must match, excluded lists must not
     * ({@code SubscriptionsService.js:171-205}).
     *
     * @param rules        the subscription's vocabulary rules
     * @param subscription the candidate subscription, for the refusal log only
     * @param criteria     what to match against
     * @return whether all four checks are satisfied
     */
    private static boolean promptsAndResultsMatch(
            final JsonNode rules,
            final JsonNode subscription,
            final SubscriptionCriteria criteria) {

        if (Json.truthy(rules, "includedPrompts")
                && !promptsMatch(criteria.judicialResults(), Json.array(rules, "includedPrompts"))) {
            return refused(subscription, "checkForMatchedPrompts failed on includedPrompts");
        }
        if (Json.truthy(rules, "excludedPrompts")
                && promptsMatch(criteria.judicialResults(), Json.array(rules, "excludedPrompts"))) {
            return refused(subscription, "checkForMatchedPrompts failed on excludedPrompts");
        }
        if (Json.truthy(rules, "includedResults")
                && !resultsMatch(criteria.judicialResults(), Json.array(rules, "includedResults"))) {
            return refused(subscription, "checkForMatchedResults failed on includedResults");
        }
        if (Json.truthy(rules, "excludedResults")
                && resultsMatch(criteria.judicialResults(), Json.array(rules, "excludedResults"))) {
            return refused(subscription, "checkForMatchedResults failed on excludedResults");
        }
        return true;
    }

    /**
     * Whether the defendant's attendance satisfies the subscription's attendance rule.
     *
     * <p>Ports {@code checkIfAttendanceTypeMatch} ({@code SubscriptionsService.js:240-256}), written
     * out in the legacy's four steps rather than reduced. The first two collapse to "any appearance
     * always matches", and stating that as one line would hide that the legacy asks the question
     * twice.
     *
     * @param rules      the subscription's vocabulary rules
     * @param vocabulary the defendant's vocabulary
     * @return whether the rule is satisfied
     */
    private static boolean attendanceMatches(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        final boolean anyAppearance = Json.truthy(rules, "anyAppearance");
        if (anyAppearance && !vocabulary.appearedByVideoLink() && !vocabulary.appearedInPerson()) {
            return true;
        }
        if (anyAppearance
                && (vocabulary.appearedByVideoLink() || vocabulary.appearedInPerson())) {
            return true;
        }
        if (Json.truthy(rules, "appearedByVideoLink") && vocabulary.appearedByVideoLink()) {
            return true;
        }
        return Json.truthy(rules, "appearedInPerson") && vocabulary.appearedInPerson();
    }

    /**
     * Whether the register's major creditors satisfy the subscription's creditor rule.
     *
     * <p>Ports {@code checkIfMajorCreditorTypeMatch} ({@code SubscriptionsService.js:258-278}). A
     * subscription that names no creditor rule at all passes; everything else needs a creditor list
     * the informant-register flow never builds.
     *
     * <p>The final clause is the legacy's loose {@code != null}, which separates an absent list from
     * an empty one: {@code undefined} fails it, {@code []} passes it.
     *
     * @param rules    the subscription's vocabulary rules
     * @param criteria what to match against
     * @return whether the rule is satisfied
     */
    private static boolean creditorTypeMatches(
            final JsonNode rules, final SubscriptionCriteria criteria) {

        final RegisterVocabulary vocabulary = criteria.vocabulary();

        if (!Json.truthy(rules, "anyMajorCreditor")
                && !Json.truthy(rules, "prosecutorMajorCreditor")
                && !Json.truthy(rules, "nonProsecutorMajorCreditor")) {
            return true;
        }
        if (Json.truthy(rules, "nonProsecutorMajorCreditor")
                && creditorNameIsListed(criteria, vocabulary.nonProsecutorMajorCreditor())) {
            return true;
        }
        if (Json.truthy(rules, "prosecutorMajorCreditor")
                && creditorNameIsListed(criteria, vocabulary.prosecutorMajorCreditor())) {
            return true;
        }
        return Json.truthy(rules, "anyMajorCreditor")
                && (vocabulary.prosecutorMajorCreditor() != null
                    || vocabulary.nonProsecutorMajorCreditor() != null);
    }

    /**
     * Whether the first creditor-name prompt on a compensation result names a listed creditor.
     *
     * <p>Ports {@code isMajorCreditorProsecutor} / {@code isMajorCreditorNonProsecutor}
     * ({@code SubscriptionsService.js:280-311}), which differ only in which list they consult. Both
     * answer on the <em>first</em> creditor-name prompt they find and stop, so a second compensation
     * result naming a listed creditor is never reached.
     *
     * <p>{@code result.judicialResultPrompts} is dereferenced without a guard in the legacy, so a
     * compensation result with no prompts is a {@code TypeError} there and a refusal here
     * ({@code doc/DEVIATIONS.md} entry 7).
     *
     * @param criteria  what to match against
     * @param creditors the creditor list to consult; may be {@code null}
     * @return whether a listed creditor is named
     */
    private static boolean creditorNameIsListed(
            final SubscriptionCriteria criteria, final List<String> creditors) {

        if (creditors == null || creditors.isEmpty()) {
            return false;
        }
        for (final JsonNode candidate : criteria.judicialResults()) {
            // `for (var result of …) result.judicialResultTypeId` (:286) reads every result up to
            // the one that answers, so a null before it is a TypeError and one after it is never
            // reached.
            final JsonNode result = Json.dereferencedElement(candidate, "judicialResults");
            if (!FINANCIAL_COMPENSATION.equals(Json.text(result, "judicialResultTypeId"))) {
                continue;
            }
            for (final JsonNode element : Json.dereferencedArray(result, PROMPTS)) {
                final JsonNode prompt = Json.dereferencedElement(element, PROMPTS);
                if (CREDITOR_NAME.equals(Json.text(prompt, "promptReference"))
                        && NAME_ADDRESS.equals(Json.text(prompt, "type"))) {
                    return creditors.contains(Json.text(prompt, "value"));
                }
            }
        }
        return false;
    }

    /**
     * Whether the hearing's court satisfies the subscription's court rule.
     *
     * <p>Ports {@code checkIfCourtHouseMatch} ({@code SubscriptionsService.js:313-326}).
     *
     * @param rules      the subscription's vocabulary rules
     * @param vocabulary the defendant's vocabulary
     * @return whether the rule is satisfied
     */
    private static boolean courtHearingMatches(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "anyCourtHearing")
                && (vocabulary.anyCourtHearing()
                    || vocabulary.englishCourtHearing()
                    || vocabulary.welshCourtHearing())) {
            return true;
        }
        if (Json.truthy(rules, "englishCourtHearing") && vocabulary.englishCourtHearing()) {
            return true;
        }
        return Json.truthy(rules, "welshCourtHearing") && vocabulary.welshCourtHearing();
    }

    /**
     * Whether the defendant's age band satisfies the subscription's defendant rule.
     *
     * <p>Ports {@code checkIfDefendantMatch} ({@code SubscriptionsService.js:328-341}).
     *
     * @param rules      the subscription's vocabulary rules
     * @param vocabulary the defendant's vocabulary
     * @return whether the rule is satisfied
     */
    private static boolean defendantMatches(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "adultOrYouthDefendant")
                && (vocabulary.adultOrYouthDefendant()
                    || vocabulary.youthDefendant()
                    || vocabulary.adultDefendant())) {
            return true;
        }
        if (Json.truthy(rules, "youthDefendant") && vocabulary.youthDefendant()) {
            return true;
        }
        return Json.truthy(rules, "adultDefendant") && vocabulary.adultDefendant();
    }

    /**
     * Whether the defendant's custody satisfies the subscription's custody rule.
     *
     * <p>Ports {@code checkIfCustodyMatch} ({@code SubscriptionsService.js:343-365}).
     *
     * @param rules      the subscription's vocabulary rules
     * @param vocabulary the defendant's vocabulary
     * @return whether the rule is satisfied
     */
    private static boolean custodyMatches(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "ignoreCustody")) {
            return true;
        }
        final boolean inCustody = Json.truthy(rules, "inCustody");
        final boolean police = Json.truthy(rules, "custodyLocationIsPolice");
        final boolean prison = Json.truthy(rules, "custodyLocationIsPrison");

        if (inCustody && !police && !prison && vocabulary.inCustody()) {
            return true;
        }
        if (inCustody && police && !prison && vocabulary.custodyLocationIsPolice()) {
            return true;
        }
        return inCustody && !police && prison && vocabulary.custodyLocationIsPrison();
    }

    /**
     * Whether the defendant's results satisfy the subscription's custodial-result rule.
     *
     * <p>Ports {@code checkIfCustodialResultMatch} ({@code SubscriptionsService.js:367-380}). The
     * custodial-result clause is the legacy's strict {@code ===} between a reference-data value and a
     * computed boolean, so a subscription that omits {@code atleastOneCustodialResult} — or declares
     * it as anything but a boolean — never satisfies it.
     *
     * @param rules      the subscription's vocabulary rules
     * @param vocabulary the defendant's vocabulary
     * @return whether the rule is satisfied
     */
    private static boolean custodialResultMatches(
            final JsonNode rules, final RegisterVocabulary vocabulary) {

        if (Json.truthy(rules, "ignoreResults")) {
            return true;
        }
        final boolean custodialAgrees =
                isBooleanEqualTo(rules, "atleastOneCustodialResult",
                        vocabulary.atleastOneCustodialResult());

        if (Json.truthy(rules, "allNonCustodialResults")
                && vocabulary.allNonCustodialResults() && custodialAgrees) {
            return true;
        }
        return Json.truthy(rules, "atleastOneNonCustodialResult")
                && vocabulary.atleastOneNonCustodialResult() && custodialAgrees;
    }

    /**
     * Whether any judicial result carries a prompt the reference data names.
     *
     * <p>Ports {@code checkForMatchedPrompts} and {@code getMatchingPrompt}
     * ({@code SubscriptionsService.js:212-230}). A {@code NAMEADDRESS} prompt matches by
     * case-insensitive <em>substring</em>; every other prompt matches by case-insensitive equality.
     *
     * <p>The legacy's {@code filter} at {@code :213} is a <strong>complete</strong> pass over the
     * judicial results before any matching runs, so a null result anywhere in the list refuses even
     * when an earlier one would have matched. The two loops below therefore stay separate rather
     * than folding into one.
     *
     * @param judicialResults the register's judicial results
     * @param wanted          the prompts the subscription names
     * @return whether any prompt matches
     */
    private static boolean promptsMatch(
            final List<JsonNode> judicialResults, final List<JsonNode> wanted) {

        final List<JsonNode> withPrompts = new ArrayList<>();
        for (final JsonNode candidate : judicialResults) {
            final JsonNode judicialResult = Json.dereferencedElement(candidate, "judicialResults");
            if (Json.truthy(judicialResult, PROMPTS)) {
                withPrompts.add(judicialResult);
            }
        }

        for (final JsonNode judicialResult : withPrompts) {
            for (final JsonNode element : Json.array(judicialResult, PROMPTS)) {
                // `getMatchingPrompt` reads `judicialPrompt.type` first (:224). The enclosing
                // `some` stops at the first match, so only the prompts actually reached are read.
                final JsonNode prompt = Json.dereferencedElement(element, PROMPTS);
                final String reference = Json.text(prompt, "promptReference");
                if (reference == null || reference.isEmpty()) {
                    continue;
                }
                final boolean nameAddress = NAME_ADDRESS.equals(Json.text(prompt, "type"));
                for (final JsonNode wantedPrompt : wanted) {
                    if (referenceMatches(reference, wantedPrompt, nameAddress)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether one prompt reference matches one reference-data prompt.
     *
     * @param reference   the prompt reference from the judicial result
     * @param candidate   the reference-data prompt
     * @param nameAddress whether the judicial prompt is of the name-and-address type
     * @return whether they match
     */
    private static boolean referenceMatches(
            final String reference, final JsonNode candidate, final boolean nameAddress) {

        final String wanted = Json.text(candidate, "resultPromptReference");
        if (wanted == null) {
            // `prompt.resultPromptReference.toLowerCase()` is unguarded in the legacy, so this is a
            // TypeError there; deviations register entry 7.
            throw new TransformationFailedException(
                    "subscription prompt has no resultPromptReference");
        }
        final String left = reference.toLowerCase(Locale.ROOT);
        final String right = wanted.toLowerCase(Locale.ROOT);
        return nameAddress ? left.contains(right) : left.equals(right);
    }

    /**
     * Whether any judicial result is of a type the reference data names.
     *
     * <p>Ports {@code checkForMatchedResults} ({@code SubscriptionsService.js:232-238}).
     *
     * <p>The legacy reads {@code judicialResult.judicialResultTypeId} from <em>inside</em> the inner
     * {@code resultsFromRefData.some(...)} callback ({@code :234-236}), so an empty reference-data
     * list never runs it and never touches a judicial result at all. An empty list is reachable —
     * the enclosing guard tests the field for truthiness, and {@code []} is truthy — so it is
     * answered without reading anything, or a register the legacy produced would be refused here.
     *
     * @param judicialResults the register's judicial results
     * @param wanted          the result type ids the subscription names
     * @return whether any result matches
     */
    private static boolean resultsMatch(
            final List<JsonNode> judicialResults, final List<JsonNode> wanted) {

        if (wanted.isEmpty()) {
            return false;
        }
        for (final JsonNode candidate : judicialResults) {
            final JsonNode judicialResult = Json.dereferencedElement(candidate, "judicialResults");
            final String typeId = Json.text(judicialResult, "judicialResultTypeId");
            if (typeId != null && contains(wanted, typeId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a reference-data array holds a given string, compared as the legacy's {@code ===} does.
     *
     * @param values the array elements
     * @param wanted the string to look for
     * @return whether it is present
     */
    private static boolean contains(final List<JsonNode> values, final String wanted) {
        return values.stream()
                .anyMatch(value -> value.isString() && wanted.equals(value.stringValue()));
    }

    /**
     * Whether a reference-data field is the boolean {@code true}, as the legacy's {@code === true}
     * asks rather than as truthiness would.
     *
     * @param node  the object to read
     * @param field the field name
     * @return whether the field is boolean {@code true}
     */
    private static boolean isTrue(final JsonNode node, final String field) {
        final JsonNode value = Json.at(node, field);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    /**
     * Whether a reference-data field is a boolean equal to a computed one, as the legacy's
     * {@code ===} asks: an absent or non-boolean field is never equal to either boolean.
     *
     * @param node     the object to read
     * @param field    the field name
     * @param expected the computed value
     * @return whether the two are strictly equal
     */
    private static boolean isBooleanEqualTo(
            final JsonNode node, final String field, final boolean expected) {

        final JsonNode value = Json.at(node, field);
        return value != null && value.isBoolean() && value.booleanValue() == expected;
    }

    /**
     * Records why a subscription was refused, and answers {@code false} so the callers read as the
     * legacy's {@code return false} does.
     *
     * <p>The subscription's name is reference data — an organisation, never a defendant — so it is
     * safe to name. It is logged at debug all the same: a hearing with forty candidate subscriptions
     * would otherwise write forty lines per authority, and no operator acts on a non-match.
     *
     * @param subscription the refused subscription
     * @param reason       the legacy check that refused it
     * @return {@code false}, always
     */
    private static boolean refused(final JsonNode subscription, final String reason) {
        LOG.debug("subscription '{}' not matched: {}", Json.text(subscription, "name"), reason);
        return false;
    }
}
