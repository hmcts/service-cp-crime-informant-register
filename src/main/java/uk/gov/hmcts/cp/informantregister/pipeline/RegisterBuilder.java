package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;

/**
 * Splits one resulted hearing into a register fragment per prosecuting authority.
 *
 * <p>A port of {@code SetInformantRegisterBuilder} in {@code SetInformantRegister/index.js} — the
 * first of the three transformation steps. It is pure: a hearing tree and a shared time in, fragments
 * out, no I/O, no reference data, and no wall clock beyond the injected one (constitution Principle
 * V), which is what lets the golden files alone decide whether the port is right.
 *
 * <p><strong>Behaviours preserved deliberately.</strong> Each of these looks like a defect and is
 * ported as written, under constitution Principle I:
 *
 * <ul>
 *   <li><strong>First occurrence wins.</strong> Authorities are de-duplicated by
 *       {@code prosecutionAuthorityId} across prosecution cases first and eligible court applications
 *       second. A later case carrying a fuller set of authority details never displaces the first
 *       one, so a fragment can be missing a code the hearing does contain.</li>
 *   <li><strong>The authority name is read from two different fields.</strong>
 *       {@code prosecutionAuthorityName} on a prosecution case, but {@code name} on an application's
 *       prosecuting authority.</li>
 *   <li><strong>{@code groupId} comes only from group-master prosecution cases.</strong> The
 *       application branch never sets one at all, even when the application belongs to a group.</li>
 *   <li><strong>Ordered dates are collected before court-extract filtering, vocabulary after.</strong>
 *       So the hearing date can be derived from a result that the very next line filters away.</li>
 *   <li><strong>Group proceedings are not skipped.</strong> The other register flows gate on
 *       {@code hearing.isGroupProceedings}; this one has never had that gate and does not gain one
 *       here.</li>
 *   <li><strong>The empty-defendants guard is dead.</strong> The legacy guards the fragment with
 *       {@code if (fragment.registerDefendants)}, which is an array and so always truthy — an
 *       authority with no matching defendants still produces a fragment. Reproduced, because the
 *       step that follows depends on the count.</li>
 * </ul>
 */
// PMD.OnlyOneReturn: the early returns mirror the legacy source's own, line for line —
// funnelling them through a single exit would reshape the very control flow the parity
// harness pins (constitution Principle I, bug-for-bug parity).
// PMD.AvoidDuplicateLiterals: the repeats are legacy JSON field names. Spelling each one at
// the site that reads it is what lets a reviewer check the line against the property access
// it ports; behind a constant the field name sits one indirection from the code being audited.
// PMD.AvoidInstantiatingObjectsInLoops: each allocation is per-iteration by necessity — one
// context per defendant, one authority per case, one result list per defendant — so hoisting
// any of them out of its loop would be a bug rather than an optimisation.
@SuppressWarnings({
    "PMD.OnlyOneReturn",
    "PMD.AvoidDuplicateLiterals",
    "PMD.AvoidInstantiatingObjectsInLoops"
})
public final class RegisterBuilder {

    private final HearingDates dates;

    /**
     * Creates the builder.
     *
     * @param dates the date service the fragments are stamped with
     */
    public RegisterBuilder(final HearingDates dates) {
        this.dates = dates;
    }

    /**
     * Builds the register fragments for one hearing.
     *
     * <p>Returns an empty list where the legacy returns {@code undefined} — both for a hearing with
     * neither prosecution cases nor court applications, and for one that yields no fragments. The
     * distinction the legacy draws between "no fragments" and "undefined" is not one a caller can
     * act on differently, and an empty list is the shape that lets the caller treat "no authorities"
     * as the recorded business outcome it is rather than as a missing value to test for.
     *
     * @param hearing    the canonical hearing tree
     * @param sharedTime the time the hearing results were shared; may be {@code null}
     * @return one fragment per prosecuting authority, in the order the legacy produces them
     */
    public List<RegisterFragment> build(final JsonNode hearing, final String sharedTime) {
        if (!Json.truthy(hearing, "prosecutionCases")
                && !Json.truthy(hearing, "courtApplications")) {
            return List.of();
        }

        final Map<String, Authority> authorities = collectAuthorities(hearing);

        final List<DefendantContext> defendants =
                new DefendantContextBuilder(hearing, dates).build();

        // Ordered dates are read from the unfiltered results, before the court-extract filter runs.
        final String latestOrderedDate = latestOrderedDate(defendants);

        CourtExtractFilter.apply(defendants);

        final VocabularyBuilder vocabulary = new VocabularyBuilder(hearing);
        for (final DefendantContext defendant : defendants) {
            defendant.vocabulary(vocabulary.build(defendant));
        }

        final String hearingDate = hearingDate(latestOrderedDate, hearing);
        final String registerDate = dates.localDateTime(sharedTime);
        final String hearingId = Json.text(hearing, "id");

        final List<RegisterFragment> fragments = new ArrayList<>();
        for (final Authority authority : authorities.values()) {
            fragments.add(new RegisterFragment(
                    registerDate,
                    hearingDate,
                    hearingId,
                    authority.id(),
                    authority.code(),
                    authority.ouCode(),
                    authority.name(),
                    authority.majorCreditorCode(),
                    defendantsOf(hearing, defendants, authority.id()),
                    authority.groupId()));
        }
        return fragments;
    }

    /**
     * Collects the distinct prosecuting authorities, cases first and applications second.
     *
     * @param hearing the canonical hearing tree
     * @return the authorities, keyed by id, in first-seen order
     */
    private Map<String, Authority> collectAuthorities(final JsonNode hearing) {
        final Map<String, Authority> authorities = new LinkedHashMap<>();

        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            final JsonNode identifier = Json.at(prosecutionCase, "prosecutionCaseIdentifier");
            final String id = Json.text(identifier, "prosecutionAuthorityId");
            if (authorities.containsKey(id)) {
                continue;
            }
            authorities.put(id, new Authority(
                    id,
                    Json.text(identifier, "prosecutionAuthorityCode"),
                    Json.text(identifier, "prosecutionAuthorityOUCode"),
                    Json.text(identifier, "majorCreditorCode"),
                    Json.text(identifier, "prosecutionAuthorityName"),
                    groupIdOf(prosecutionCase)));
        }

        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            final JsonNode subject = Json.at(application, "subject");
            final JsonNode authority =
                    Json.at(Json.at(application, "applicant"), "prosecutingAuthority");
            if (!Json.truthy(authority) || !Json.truthy(subject, "masterDefendant")) {
                continue;
            }
            final String id = Json.text(authority, "prosecutionAuthorityId");
            if (authorities.containsKey(id)) {
                continue;
            }
            authorities.put(id, new Authority(
                    id,
                    Json.text(authority, "prosecutionAuthorityCode"),
                    Json.text(authority, "prosecutionAuthorityOUCode"),
                    Json.text(authority, "majorCreditorCode"),
                    // Not `prosecutionAuthorityName`, as on a prosecution case — the legacy reads
                    // `name` on this branch.
                    Json.text(authority, "name"),
                    // No group id on this branch, deliberately.
                    null));
        }
        return authorities;
    }

    /**
     * The group id of a prosecution case, which only a group master carries.
     *
     * @param prosecutionCase the prosecution case
     * @return the group id, or {@code null}
     */
    private static String groupIdOf(final JsonNode prosecutionCase) {
        return Json.truthy(prosecutionCase, "isGroupMaster")
                ? Json.text(prosecutionCase, "groupId")
                : null;
    }

    /**
     * The latest ordered date across every result of every defendant, before filtering.
     *
     * <p>Ports {@code SetInformantRegister/index.js:109-119} feeding
     * {@code RegisterFragmentService.getLatestOrderedDate}. The de-duplication is the legacy's own —
     * it collects into a {@code Set} before spreading and sorting — and it is kept because it
     * changes how many elements the sort has, which is what decides whether an unreadable date is
     * ever compared ({@link OrderedDates}).
     *
     * @param defendants the gathered defendant contexts
     * @return the latest ordered date, or {@code null} when there are no results
     */
    private String latestOrderedDate(final List<DefendantContext> defendants) {
        final Set<JsonNode> orderedDates = new LinkedHashSet<>();
        for (final DefendantContext defendant : defendants) {
            for (final RegisterResult result : defendant.results()) {
                orderedDates.add(Json.at(result.judicialResult(), "orderedDate"));
            }
        }
        return OrderedDates.latest(new ArrayList<>(orderedDates), dates);
    }

    /**
     * The hearing date, taken from the sitting day matching the latest ordered date.
     *
     * <p>Ports {@code getHearingDate} in {@code NowsHelper/service/RegisterFragmentService.js},
     * including two details that decide the answer. A hearing with no {@code hearingDays} field at
     * all yields no hearing date, and the fragment simply has none. A hearing whose
     * {@code hearingDays} is an <em>empty array</em> takes the other branch, because an empty array
     * is truthy in JavaScript, finds no matching day, and falls back to formatting the ordered date
     * itself — which is what the prosecution-case fixture does.
     *
     * @param orderedDate the latest ordered date
     * @param hearing     the canonical hearing tree
     * @return the hearing date, or {@code null}
     */
    private String hearingDate(final String orderedDate, final JsonNode hearing) {
        if (!Json.truthy(hearing, "hearingDays")) {
            return null;
        }
        for (final JsonNode hearingDay : Json.array(hearing, "hearingDays")) {
            final String sittingDay = Json.text(hearingDay, "sittingDay");
            if (dates.localDate(sittingDay).equals(orderedDate)) {
                return dates.localDateTime(sittingDay);
            }
        }
        return dates.localDateTime(orderedDate);
    }

    /**
     * The defendants belonging to one authority.
     *
     * <p>The set of identities is built from the authority's prosecution cases and from its eligible
     * applications, then the gathered contexts are filtered to it — so a defendant reached through
     * both a case and an application appears once.
     *
     * @param hearing                the canonical hearing tree
     * @param defendants             the gathered defendant contexts
     * @param prosecutionAuthorityId the authority to select for
     * @return the authority's defendants, frozen
     */
    private static List<RegisterDefendant> defendantsOf(
            final JsonNode hearing,
            final List<DefendantContext> defendants,
            final String prosecutionAuthorityId) {

        final Set<String> identities = new LinkedHashSet<>();

        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            final JsonNode identifier = Json.at(prosecutionCase, "prosecutionCaseIdentifier");
            if (!Objects.equals(
                    Json.text(identifier, "prosecutionAuthorityId"), prosecutionAuthorityId)) {
                continue;
            }
            // The guarded form, deliberately: the legacy dereference here
            // (`getDefendantsFromContext`, SetInformantRegister/index.js:125) is unreachable with a
            // missing `defendants`, because the defendant-context pass ran first and already
            // refused the same case. Both sides agree; nothing here decides the outcome.
            for (final JsonNode defendant : Json.array(prosecutionCase, "defendants")) {
                identities.add(Json.text(defendant, "masterDefendantId"));
            }
        }

        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            final JsonNode authority =
                    Json.at(Json.at(application, "applicant"), "prosecutingAuthority");
            if (!Json.truthy(authority) || !Objects.equals(
                    Json.text(authority, "prosecutionAuthorityId"), prosecutionAuthorityId)) {
                continue;
            }
            final JsonNode masterDefendant =
                    Json.at(Json.at(application, "subject"), "masterDefendant");
            if (masterDefendant != null) {
                identities.add(Json.text(masterDefendant, "masterDefendantId"));
            }
        }

        final List<RegisterDefendant> selected = new ArrayList<>();
        for (final DefendantContext defendant : defendants) {
            if (identities.contains(defendant.masterDefendantId())) {
                selected.add(defendant.freeze());
            }
        }
        return selected;
    }

    /**
     * One prosecuting authority's identifying details, as read from the first place they appeared.
     *
     * @param id                the authority id
     * @param code              the authority's reference-data code
     * @param ouCode            the authority's organisation unit code
     * @param majorCreditorCode the authority's major creditor code
     * @param name              the authority's name
     * @param groupId           the group id, only ever from a group-master prosecution case
     */
    private record Authority(
            String id,
            String code,
            String ouCode,
            String majorCreditorCode,
            String name,
            String groupId) {
    }
}
