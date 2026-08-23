package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragmentWithSubscriptions;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.SubscriptionCriteria;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * Fills in the subscriptions each prosecuting authority's register fragment matched.
 *
 * <p>A port of {@code InformantRegisterSubscriptions/index.js} — the second of the three
 * transformation steps, between {@link RegisterBuilder} and the aggregation mapping. What it decides
 * is who receives the register: the matched subscriptions are the only input to the recipient
 * mapping.
 *
 * <p><strong>It is pure, and the legacy step was not.</strong> The legacy activity fetches the
 * now-subscriptions reference data itself ({@code index.js:20}). Here the answer is passed in, so the
 * transformation stays "JSON in, typed documents out, no I/O" (constitution Principle V) and the
 * fetch belongs to an adapter. {@link #registerDate(List)} exists for that adapter: it is the value
 * the legacy derives before it calls reference data, and the value the query is dated with.
 *
 * <p><strong>The register date is derived before the answer is looked at, deliberately.</strong> The
 * legacy dereferences it at {@code index.js:18}, before the fetch, so a fragment set carrying no
 * register date never reaches reference data at all. {@link #match} therefore derives it again even
 * though it does not use it — the refusal has to happen on this path whether or not the caller asked
 * for the date first.
 *
 * <p><strong>Behaviours preserved deliberately.</strong> Each of these looks like a defect and is
 * ported as written, under constitution Principle I:
 *
 * <ul>
 *   <li><strong>{@code ouCode} is the major creditor code, not the authority's OU code.</strong>
 *       {@code index.js:47} assigns {@code majorCreditorCode}, and the matching kernel then compares
 *       it against {@code informantCode} and against the selected court houses. Twelve of the
 *       thirteen real hearing fixtures carry no major creditor code at all, so on those hearings no
 *       subscription can ever match and no register has a recipient.</li>
 *   <li><strong>The whole register's vocabulary is the <em>first</em> defendant's.</strong>
 *       {@code index.js:46} reads {@code registerDefendants[0].vocabulary} and ignores every other
 *       defendant's, so a second defendant in custody, or a youth, cannot bring in a subscription the
 *       first defendant's flags exclude.</li>
 *   <li><strong>Judicial results are pooled across all defendants.</strong> The prompt and result
 *       rules are therefore answered by any defendant's results, not by the first one's
 *       ({@code index.js:53-64}).</li>
 *   <li><strong>Reference data that answers with nothing is not a failure.</strong> No body, no
 *       {@code nowSubscriptions} member, or no informant-register subscription among them, and the
 *       fragments are returned untouched — with no {@code matchedSubscriptions} member at all, which
 *       is a different document from one carrying an empty array
 *       ({@code index.js:22-33}).</li>
 *   <li><strong>A {@code null} among the subscriptions is not "one that matches nothing".</strong>
 *       {@code index.js:28} reads a flag off every element of {@code nowSubscriptions}, so one null
 *       entry means the legacy emits no register for any authority. Skipping it and matching the
 *       rest would hand recipients a register the legacy never sent, so it is refused
 *       ({@code doc/DEVIATIONS.md} entry 7).</li>
 * </ul>
 *
 * <p><strong>Callers must not hand this an empty fragment list.</strong> The legacy never can:
 * {@code SetInformantRegister} returns {@code undefined} rather than an empty array, and the
 * orchestrator's {@code if (informantRegisters)} skips this step entirely. The port's builder returns
 * an empty list instead ({@code doc/DEVIATIONS.md} entry 6) and the pipeline records that run
 * COMPLETED with the reason {@code no-authorities} without coming here. Reaching this method with
 * nothing to match is refused rather than answered, because the legacy's own answer to
 * {@code [].find(…).registerDate} is a {@code TypeError}.
 */
public final class SubscriptionMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionMatcher.class);

    private final SubscriptionRules rules;

    /**
     * Creates the matcher with the shared matching kernel it delegates to.
     *
     * @param rules the matching kernel
     */
    public SubscriptionMatcher(final SubscriptionRules rules) {
        this.rules = rules;
    }

    /**
     * The register date the now-subscriptions query is dated with.
     *
     * <p>Ports {@code index.js:17-18}: the first fragment that carries one wins, and the legacy
     * dereferences the result of its {@code find} without checking it, so a set in which none does is
     * a {@code TypeError} there and a refusal here ({@code doc/DEVIATIONS.md} entry 7).
     *
     * @param fragments the fragments the builder produced
     * @return the register date
     * @throws TransformationFailedException if no fragment carries one
     */
    public String registerDate(final List<RegisterFragment> fragments) {
        for (final RegisterFragment fragment : fragments) {
            final String registerDate = fragment.registerDate();
            if (registerDate != null && !registerDate.isEmpty()) {
                return registerDate;
            }
        }
        throw new TransformationFailedException("no register fragment carries a register date");
    }

    /**
     * Matches every fragment against the informant-register subscriptions in a reference-data answer.
     *
     * @param fragments             the fragments the builder produced
     * @param subscriptionsMetadata the now-subscriptions body reference data answered with; may be
     *                              {@code null}
     * @return the fragments, each carrying the subscriptions it matched, or carrying none at all
     *         where the answer had nothing to match against
     * @throws TransformationFailedException where the legacy raises a {@code TypeError}
     */
    public List<RegisterFragmentWithSubscriptions> match(
            final List<RegisterFragment> fragments, final JsonNode subscriptionsMetadata) {

        // Not used here; derived because the legacy derives — and can fail — before the fetch.
        registerDate(fragments);

        if (!Json.truthy(subscriptionsMetadata)
                || !Json.truthy(subscriptionsMetadata, "nowSubscriptions")) {
            return unmatched(fragments);
        }

        // `nowSubscriptions.filter(s => s.isInformantRegisterSubscription)` (index.js:28) reads a
        // property off every element, so a null one is a TypeError there and a refusal here.
        final List<JsonNode> informantRegisterSubscriptions =
                Json.array(subscriptionsMetadata, "nowSubscriptions").stream()
                        .filter(subscription -> Json.truthy(
                                Json.dereferencedElement(subscription, "nowSubscriptions"),
                                "isInformantRegisterSubscription"))
                        .toList();

        if (informantRegisterSubscriptions.isEmpty()) {
            return unmatched(fragments);
        }

        final List<RegisterFragmentWithSubscriptions> matched = new ArrayList<>();
        for (final RegisterFragment fragment : fragments) {
            final List<JsonNode> subscriptions =
                    rules.match(criteriaFor(fragment, informantRegisterSubscriptions));
            LOG.debug("authority {} matched {} subscription(s) on hearing {}",
                    fragment.prosecutionAuthorityId(), subscriptions.size(), fragment.hearingId());
            matched.add(RegisterFragmentWithSubscriptions.carrying(fragment, subscriptions));
        }
        return List.copyOf(matched);
    }

    /**
     * Returns the fragments as they arrived, carrying no matched subscriptions at all.
     *
     * @param fragments the fragments the builder produced
     * @return the untouched fragments
     */
    private static List<RegisterFragmentWithSubscriptions> unmatched(
            final List<RegisterFragment> fragments) {

        return fragments.stream()
                .map(fragment -> RegisterFragmentWithSubscriptions.carrying(fragment, null))
                .toList();
    }

    /**
     * Builds the criteria one fragment is matched with.
     *
     * <p>Ports {@code buildSubscription} ({@code index.js:44-51}), which reads
     * {@code registerDefendants[0].vocabulary} without a guard — so an authority that ended up with
     * no defendants is a {@code TypeError} there and a refusal here. {@code nowId} and
     * {@code userGroup} are left unset, exactly as the legacy leaves them.
     *
     * @param fragment      the fragment to match
     * @param subscriptions the informant-register subscriptions to match it against
     * @return the criteria
     */
    private static SubscriptionCriteria criteriaFor(
            final RegisterFragment fragment, final List<JsonNode> subscriptions) {

        final List<RegisterDefendant> defendants = fragment.registerDefendants();
        if (defendants == null || defendants.isEmpty()) {
            throw new TransformationFailedException(
                    "register fragment carries no defendant to read a vocabulary from");
        }
        return new SubscriptionCriteria(
                null,
                fragment.majorCreditorCode(),
                null,
                defendants.get(0).vocabulary(),
                subscriptions,
                judicialResults(defendants));
    }

    /**
     * Every judicial result across every one of the register's defendants.
     *
     * <p>Ports {@code collectJudicialResults} ({@code index.js:53-64}).
     *
     * @param defendants the register's defendants
     * @return the pooled judicial results
     */
    private static List<JsonNode> judicialResults(final List<RegisterDefendant> defendants) {
        final List<JsonNode> judicialResults = new ArrayList<>();
        for (final RegisterDefendant defendant : defendants) {
            if (defendant.results() == null || defendant.results().isEmpty()) {
                continue;
            }
            for (final RegisterResult result : defendant.results()) {
                judicialResults.add(result.judicialResult());
            }
        }
        return judicialResults;
    }
}
