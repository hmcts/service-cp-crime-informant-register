package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One prosecuting authority's register fragment, once subscription matching has run over it.
 *
 * <p>The legacy has no second type here: {@code InformantRegisterSubscriptions/index.js:37} assigns
 * {@code matchedSubscriptions} onto the fragment object it was handed, and the orchestrator calls the
 * mutated array {@code informantRegisterWithSubscriptions}
 * ({@code InformantRegisterOrchestrator/index.js:29}). A {@link RegisterFragment} cannot gain a
 * component after construction, so the step produces this instead — the same eleven members the
 * legacy object ends up with, flat, because that is the shape the goldens captured from the legacy
 * carry and the shape the aggregation mapping reads.
 *
 * <p><strong>An absent {@code matchedSubscriptions} is not an empty one.</strong> The legacy returns
 * the fragments <em>untouched</em> when reference data answers with no subscriptions at all, or with
 * none for the informant register ({@code index.js:22-33}), so the member is missing from the object
 * entirely; it is an empty array only when matching ran and found nothing. Both shapes are recorded
 * in the goldens, so {@code null} and {@link List#of()} mean different things here and
 * {@code NON_NULL} keeps them apart on the wire.
 *
 * <p>The matched subscriptions stay canonical trees: they are reference data this service reads and
 * passes on, never something it produces (constitution Principle IV).
 *
 * @param registerDate               the shared time, as a London wall-clock string
 * @param hearingDate                the hearing day matching the latest ordered date
 * @param hearingId                  the resulted hearing
 * @param prosecutionAuthorityId     the authority this fragment is for
 * @param prosecutionAuthorityCode   the authority's reference-data code
 * @param prosecutionAuthorityOuCode the authority's organisation unit code
 * @param prosecutionAuthorityName   the authority's name
 * @param majorCreditorCode          the authority's major creditor code
 * @param registerDefendants         the defendants belonging to this authority
 * @param groupId                    the group id, only ever from a group-master prosecution case
 * @param matchedSubscriptions       the subscriptions this authority matched, or {@code null} when
 *                                   matching did not run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterFragmentWithSubscriptions(
        String registerDate,
        String hearingDate,
        String hearingId,
        String prosecutionAuthorityId,
        String prosecutionAuthorityCode,
        String prosecutionAuthorityOuCode,
        String prosecutionAuthorityName,
        String majorCreditorCode,
        List<RegisterDefendant> registerDefendants,
        String groupId,
        List<JsonNode> matchedSubscriptions) {

    /**
     * Freezes the list-valued components so the fragment cannot be changed after it is built.
     */
    public RegisterFragmentWithSubscriptions {
        registerDefendants = FragmentLists.frozen(registerDefendants);
        matchedSubscriptions = FragmentLists.frozen(matchedSubscriptions);
    }

    /**
     * Carries a fragment through the matching step.
     *
     * @param fragment             the fragment as the builder produced it
     * @param matchedSubscriptions the subscriptions it matched, or {@code null} when matching did not
     *                             run
     * @return the fragment with its matched subscriptions
     */
    public static RegisterFragmentWithSubscriptions carrying(
            final RegisterFragment fragment, final List<JsonNode> matchedSubscriptions) {

        return new RegisterFragmentWithSubscriptions(
                fragment.registerDate(),
                fragment.hearingDate(),
                fragment.hearingId(),
                fragment.prosecutionAuthorityId(),
                fragment.prosecutionAuthorityCode(),
                fragment.prosecutionAuthorityOuCode(),
                fragment.prosecutionAuthorityName(),
                fragment.majorCreditorCode(),
                fragment.registerDefendants(),
                fragment.groupId(),
                matchedSubscriptions);
    }
}
