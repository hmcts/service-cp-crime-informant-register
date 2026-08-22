package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One prosecuting authority's share of a resulted hearing.
 *
 * <p>A port of {@code InformantRegisterFragment} in {@code SetInformantRegister/index.js}. This is
 * the <em>intermediate</em> of the transformation, not the outbound body: subscription matching and
 * aggregation mapping still stand between a fragment and an
 * {@link InformantRegisterDocument}. It is modelled as typed records rather than left as a tree
 * because the parity goldens compare it field by field, and because the fields are this service's
 * own — unlike the hearing payload, which stays canonical.
 *
 * <p><strong>Dates are strings here, not {@code ZonedDateTime}.</strong> The legacy builder writes
 * whatever {@code DateService.getLocalDateTime} produced, which is a London wall-clock time with a
 * literal {@code Z} appended — a string that is not the instant it claims to be. Parsing it into a
 * temporal type would either lose that or silently correct it, and correcting it is a behaviour
 * change that belongs on the deviations register, not in a field type. The strings are carried
 * verbatim and converted once, at the outbound boundary.
 *
 * <p>There is deliberately no {@code matchedSubscriptions} component. The legacy fragment declares
 * one, but nothing in this step ever sets it — it is written by the subscription-matching step that
 * follows, so at this boundary it is always {@code undefined} and absent from every golden. Adding a
 * permanently-null field now would model a value this class cannot produce.
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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterFragment(
        String registerDate,
        String hearingDate,
        String hearingId,
        String prosecutionAuthorityId,
        String prosecutionAuthorityCode,
        String prosecutionAuthorityOuCode,
        String prosecutionAuthorityName,
        String majorCreditorCode,
        List<RegisterDefendant> registerDefendants,
        String groupId) {

    /**
     * Freezes the defendant list so the fragment cannot be changed after it is built.
     */
    public RegisterFragment {
        registerDefendants = FragmentLists.frozen(registerDefendants);
    }
}
