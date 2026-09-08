package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterCaseOrApplication;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterOffence;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;

/**
 * One defendant's prosecution cases and court applications, as the register lists them.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * ProsecutionCaseOrApplicationMapper.js}. Cases come first, then applications, each looked up in the
 * hearing by the identifier the fragment-building step recorded against the defendant — an
 * identifier the hearing does not carry produces no entry rather than an empty one.
 *
 * <p><strong>Two shapes, one component.</strong> A case's reference is its URN, falling back to the
 * prosecuting authority's own reference; an application's is its {@code applicationReference}, with
 * no fallback at all. The arrest summons number is read from a different place in each, and only for
 * this defendant.
 *
 * <p><strong>The offence list is not scoped to the entry it hangs off.</strong> The legacy asks its
 * offence mapper for the defendant's whole list once per entry, passing no case or application
 * argument, so every entry receives the same offences — defect D8. It is reproduced here for the
 * reason given on {@link OffenceMapper}: correcting it changes what prosecuting authorities receive.
 *
 * <p><strong>No entries is nothing, not an empty list.</strong> As everywhere else in this tree, the
 * legacy returns {@code undefined} when it produced nothing, and the contract gives the array
 * {@code minItems: 1}, so an absent component is the valid shape and an empty one is not.
 */
// PMD.OnlyOneReturn: the early returns mirror the legacy source's own, line for line —
// funnelling them through a single exit would reshape the very control flow the parity
// harness pins (constitution Principle I, bug-for-bug parity).
@SuppressWarnings("PMD.OnlyOneReturn")
final class CaseOrApplicationMapper {

    private final JsonNode hearing;
    private final RegisterFragment fragment;
    private final RegisterDefendant defendant;
    private final ResultMapper resultMapper;

    /**
     * Creates the mapper for one defendant.
     *
     * @param hearing      the canonical hearing tree
     * @param fragment     the authority's fragment
     * @param defendant    the defendant whose cases and applications are being listed
     * @param resultMapper the mapper for each entry's own results
     */
    /* default */ CaseOrApplicationMapper(
            final JsonNode hearing,
            final RegisterFragment fragment,
            final RegisterDefendant defendant,
            final ResultMapper resultMapper) {
        this.hearing = hearing;
        this.fragment = fragment;
        this.defendant = defendant;
        this.resultMapper = resultMapper;
    }

    /**
     * Lists the defendant's cases and applications.
     *
     * @return the entries, or {@code null} when the defendant has none the hearing carries
     */
    // Empty means "omit the array", and the outbound schema marks it optional with minItems: 1, so
    // null is the only rendering that serialises legally under @JsonInclude(NON_NULL). Returning an
    // empty list, as the rule asks, would emit [] and change outbound bytes the parity goldens pin.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    /* default */ List<InformantRegisterCaseOrApplication> build() {
        final List<InformantRegisterCaseOrApplication> entries = new ArrayList<>();
        addCases(entries);
        addApplications(entries);
        return entries.isEmpty() ? null : entries;
    }

    /**
     * Adds an entry for every one of the defendant's cases the hearing carries.
     *
     * @param entries the list being accumulated
     */
    private void addCases(final List<InformantRegisterCaseOrApplication> entries) {
        if (defendant.cases() == null || defendant.cases().isEmpty()) {
            return;
        }
        for (final String caseId : defendant.cases()) {
            final JsonNode prosecutionCase = find("prosecutionCases", caseId);
            if (prosecutionCase == null) {
                continue;
            }
            // `prosecutionCase.prosecutionCaseIdentifier.caseURN`
            // (ProsecutionCaseOrApplicationMapper.js:20-21) — dereferenced with no guard, so a
            // matched case with no identifier kills the hearing rather than yielding an entry with
            // no reference on it.
            final JsonNode identifier =
                    Json.dereferenced(prosecutionCase, "prosecutionCaseIdentifier");
            entries.add(new InformantRegisterCaseOrApplication(
                    caseReference(identifier),
                    arrestSummonsNumberOf(prosecutionCase),
                    null,
                    offences(),
                    resultMapper.caseLevel(caseId)));
        }
    }

    /**
     * Adds an entry for every one of the defendant's applications the hearing carries.
     *
     * @param entries the list being accumulated
     */
    private void addApplications(final List<InformantRegisterCaseOrApplication> entries) {
        if (defendant.applications() == null || defendant.applications().isEmpty()) {
            return;
        }
        for (final String applicationId : defendant.applications()) {
            final JsonNode application = find("courtApplications", applicationId);
            if (application == null) {
                continue;
            }
            entries.add(new InformantRegisterCaseOrApplication(
                    // No fallback on this branch, unlike a case's reference.
                    Json.text(application, "applicationReference"),
                    applicationArrestSummonsNumber(application),
                    null,
                    offences(),
                    resultMapper.applicationLevel(applicationId)));
        }
    }

    /**
     * The member of a hearing collection with the given id.
     *
     * <p>{@code find(pcase => pcase.id === caseId)} (`ProsecutionCaseOrApplicationMapper.js:55`)
     * reads {@code .id} off each member until one matches, so a null member before the match is a
     * {@code TypeError} and is refused here too.
     *
     * <p><strong>One thing this comparison cannot reproduce.</strong> The legacy's {@code ===}
     * separates {@code undefined} from {@code null}, and the fragment's id lists carry both — a case
     * with no {@code id} pushes {@code undefined}, one whose {@code id} is JSON null pushes
     * {@code null} (`DefendantContextBaseService.js:67`). The fragment models those as a
     * {@code List<String>}, where both are the same absent element, so an id list holding one form
     * can select a hearing member carrying the other. It takes a payload that uses both forms on two
     * different cases to reach, and it is deviations-register entry 12 rather than a model change
     * made in passing — the fix is upstream, in what the fragment records, and it moves the goldens.
     *
     * @param collection the hearing field to search
     * @param id         the id to find
     * @return the member, or {@code null} when the hearing carries none
     */
    private JsonNode find(final String collection, final String id) {
        for (final JsonNode candidate : Json.array(hearing, collection)) {
            final JsonNode member = Json.dereferencedElement(candidate, collection);
            if (Objects.equals(Json.text(member, "id"), id)) {
                return member;
            }
        }
        return null;
    }

    /**
     * A case's reference: its URN when it has one, and its authority reference otherwise.
     *
     * <p>The same truthiness rule {@link OffenceMapper} applies to an offence's originating case, in
     * a second place. Pinned by {@code s04-case-reference-urn-then-authority-reference}, which
     * requires both sites to be asserted.
     *
     * @param identifier the case identifier
     * @return the reference
     */
    private static String caseReference(final JsonNode identifier) {
        return Json.truthy(identifier, "caseURN")
                ? Json.text(identifier, "caseURN")
                : Json.text(identifier, "prosecutionAuthorityReference");
    }

    /**
     * The defendant's arrest summons number on one prosecution case.
     *
     * <p>The first one found wins, as {@code asn[0]} does — a defendant appearing twice on the same
     * case with two numbers reports only the first.
     *
     * @param prosecutionCase the prosecution case
     * @return the number, or {@code null} when this defendant has none on this case
     */
    private String arrestSummonsNumberOf(final JsonNode prosecutionCase) {
        // `prosecutionCase.defendants.filter(...)` — dereferenced with no guard
        // (ProsecutionCaseOrApplicationMapper.js:60), so a case without the field kills the hearing.
        for (final JsonNode caseDefendant : Json.dereferencedArray(prosecutionCase, "defendants")) {
            if (!Objects.equals(
                    Json.text(caseDefendant, "masterDefendantId"),
                    defendant.masterDefendantId())) {
                continue;
            }
            final JsonNode personDefendant = Json.at(caseDefendant, "personDefendant");
            if (Json.truthy(personDefendant) && Json.truthy(personDefendant, "arrestSummonsNumber")) {
                return Json.text(personDefendant, "arrestSummonsNumber");
            }
        }
        return null;
    }

    /**
     * The defendant's arrest summons number on one court application.
     *
     * <p>Read only when the application's subject <em>is</em> this defendant. When it is not, the
     * entry is still produced — the application was on the defendant's own list — with no number.
     *
     * @param application the court application
     * @return the number, or {@code null}
     */
    private String applicationArrestSummonsNumber(final JsonNode application) {
        // `courtApplication.subject.masterDefendant.masterDefendantId` — two unguarded dereferences
        // (ProsecutionCaseOrApplicationMapper.js:71).
        final JsonNode masterDefendant =
                Json.dereferenced(Json.dereferenced(application, "subject"), "masterDefendant");
        if (!Objects.equals(
                Json.text(masterDefendant, "masterDefendantId"), defendant.masterDefendantId())) {
            return null;
        }
        return Json.truthy(masterDefendant, "personDefendant")
                ? Json.text(Json.at(masterDefendant, "personDefendant"), "arrestSummonsNumber")
                : null;
    }

    /**
     * The defendant's whole offence list, which every entry receives — see the class documentation.
     *
     * @return the offences
     */
    private List<InformantRegisterOffence> offences() {
        return new OffenceMapper(hearing, fragment, defendant, resultMapper).build();
    }
}
