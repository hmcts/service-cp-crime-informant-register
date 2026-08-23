package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterOffence;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterVerdict;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;

/**
 * Every offence in the hearing that belongs to one authority <em>and</em> one defendant.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * OffenceMapper.js}. Offences are gathered from three places: the defendant's prosecution cases, the
 * cases linked to a court application, and the offences named on a court order — in that order, which
 * is the order they appear on the register.
 *
 * <p><strong>The result is per defendant, not per case, and that is defect D8.</strong> The legacy
 * caller invokes this mapper once for every case and every application the defendant appears on and
 * passes it no case argument, so each of those entries receives the same full list. A case entry
 * therefore carries offences belonging to the defendant's <em>other</em> cases, each still pointing
 * back at the case it really came from through {@code originatingCaseUrn} — so the entry contradicts
 * itself. Scoping the list to its own case is the obvious tidy design and it is exactly what must not
 * happen here: it changes what prosecuting authorities receive and needs a business decision plus a
 * deviations-register entry. The pinning case
 * {@code d08-offences-duplicated-onto-every-case} exists to fail on it.
 *
 * <p><strong>The authority filter is the only isolation there is.</strong> The check at
 * {@code OffenceMapper.js:17} is what stops one prosecutor's offences reaching another's register,
 * and the legacy suite never executes its false leg (parity-pack BS-05). It is asserted here on both
 * legs, for the authority and for the defendant.
 */
// PMD.OnlyOneReturn: the early returns mirror the legacy source's own, line for line —
// funnelling them through a single exit would reshape the very control flow the parity
// harness pins (constitution Principle I, bug-for-bug parity).
// PMD.AvoidDuplicateLiterals: the repeats are legacy JSON field names. Spelling each one at
// the site that reads it is what lets a reviewer check the line against the property access
// it ports; behind a constant the field name sits one indirection from the code being audited.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidDuplicateLiterals"})
final class OffenceMapper {

    private final JsonNode hearing;
    private final RegisterFragment fragment;
    private final RegisterDefendant defendant;
    private final ResultMapper resultMapper;

    /**
     * Creates the mapper for one authority and one defendant.
     *
     * @param hearing      the canonical hearing tree
     * @param fragment     the authority's fragment
     * @param defendant    the defendant whose offences are being gathered
     * @param resultMapper the mapper for each offence's own results
     */
    /* default */ OffenceMapper(
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
     * Gathers the offences.
     *
     * @return the offences, in the order the legacy accumulates them; never {@code null}
     */
    /* default */ List<InformantRegisterOffence> build() {
        final List<InformantRegisterOffence> offences = new ArrayList<>();
        addProsecutionCaseOffences(offences);
        addCourtApplicationOffences(offences);
        return offences;
    }

    /**
     * Adds the offences from every prosecution case belonging to this authority and defendant.
     *
     * @param offences the list being accumulated
     */
    private void addProsecutionCaseOffences(final List<InformantRegisterOffence> offences) {
        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            // `prosecutionCase.prosecutionCaseIdentifier.prosecutionAuthorityId` — dereferenced with
            // no guard (OffenceMapper.js:17), and for every case in the hearing, not only a matched
            // one, so a case with no identifier kills the hearing before any filtering happens.
            final JsonNode identifier =
                    Json.dereferenced(prosecutionCase, "prosecutionCaseIdentifier");
            if (!Objects.equals(
                    Json.text(identifier, "prosecutionAuthorityId"),
                    fragment.prosecutionAuthorityId())) {
                continue;
            }
            // `prosecutionCase.defendants.filter(...)` — dereferenced with no guard
            // (OffenceMapper.js:18), so a case without the field kills the hearing in the legacy.
            for (final JsonNode caseDefendant
                    : Json.dereferencedArray(prosecutionCase, "defendants")) {
                if (!Objects.equals(
                        Json.text(caseDefendant, "masterDefendantId"),
                        defendant.masterDefendantId())) {
                    continue;
                }
                // `.map(d => d.offences).reduce((a, b) => a.concat(b), [])` — a matching defendant
                // with no offences concatenates `undefined` into the list and the very next `.map`
                // reads a property off it, so the legacy throws here too.
                for (final JsonNode offence
                        : Json.dereferencedArray(caseDefendant, "offences")) {
                    offences.add(derive(offence, "offences", caseUrn(identifier)));
                }
            }
        }
    }

    /**
     * Adds the offences reached through every court application belonging to this authority and
     * defendant.
     *
     * @param offences the list being accumulated
     */
    private void addCourtApplicationOffences(final List<InformantRegisterOffence> offences) {
        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            if (!belongsHere(application)) {
                continue;
            }
            if (Json.nonEmptyArray(application, "courtApplicationCases")) {
                for (final JsonNode applicationCase
                        : Json.array(application, "courtApplicationCases")) {
                    if (!Json.nonEmptyArray(applicationCase, "offences")) {
                        continue;
                    }
                    // `deriveCaseUrn(courtApplicationCase.prosecutionCaseIdentifier)` reads
                    // `.caseURN` off its argument with no guard (OffenceMapper.js:41, 80).
                    final String caseUrn = caseUrn(
                            Json.dereferenced(applicationCase, "prosecutionCaseIdentifier"));
                    for (final JsonNode offence : Json.array(applicationCase, "offences")) {
                        offences.add(derive(offence, "offences", caseUrn));
                    }
                }
            }
            if (Json.truthy(application, "courtOrder")) {
                final JsonNode courtOrder = Json.at(application, "courtOrder");
                for (final JsonNode member
                        : Json.array(courtOrder, "courtOrderOffences")) {
                    // `courtOrderOffence.offence` and `.prosecutionCaseIdentifier` are both read
                    // straight off the member (OffenceMapper.js:50-51).
                    final JsonNode courtOrderOffence =
                            Json.dereferencedElement(member, "courtOrderOffences");
                    offences.add(derive(
                            Json.at(courtOrderOffence, "offence"),
                            "courtOrderOffences",
                            caseUrn(Json.dereferenced(
                                    courtOrderOffence, "prosecutionCaseIdentifier"))));
                }
            }
        }
    }

    /**
     * Whether a court application belongs to this authority and this defendant.
     *
     * <p>All four conjuncts of {@code OffenceMapper.js:31-34}, in order. The first and third read
     * <em>through</em> {@code applicant} and {@code subject}, which the legacy does not guard.
     *
     * @param application the court application
     * @return whether its offences count towards this register
     */
    private boolean belongsHere(final JsonNode application) {
        final JsonNode authority =
                Json.at(Json.dereferenced(application, "applicant"), "prosecutingAuthority");
        if (!Json.truthy(authority) || !Objects.equals(
                Json.text(authority, "prosecutionAuthorityId"),
                fragment.prosecutionAuthorityId())) {
            return false;
        }
        final JsonNode masterDefendant =
                Json.at(Json.dereferenced(application, "subject"), "masterDefendant");
        return Json.truthy(masterDefendant) && Objects.equals(
                Json.text(masterDefendant, "masterDefendantId"), defendant.masterDefendantId());
    }

    /**
     * Maps one offence and stamps it with the case it came from.
     *
     * @param offence    the offence tree; a null one is a refusal
     * @param collection the collection it was iterated out of, for the failure message
     * @param caseUrn    the reference of the case the offence came from
     * @return the outbound offence
     */
    private InformantRegisterOffence derive(
            final JsonNode offence, final String collection, final String caseUrn) {
        // `derivedOffence.offenceCode = offence.offenceCode` (OffenceMapper.js:62) — the offence is
        // dereferenced with no guard, so a null member, or a court-order entry naming no offence,
        // kills the hearing rather than producing an offence with nothing in it.
        final JsonNode present = Json.dereferencedElement(offence, collection);
        return new InformantRegisterOffence(
                caseUrn,
                Json.text(present, "offenceCode"),
                orderIndex(present),
                Json.text(present, "offenceTitle"),
                Json.text(Json.at(present, "plea"), "pleaValue"),
                verdict(present),
                resultMapper.offenceLevel(present));
    }

    /**
     * The offence's order index.
     *
     * <p>The legacy copies {@code offence.orderIndex} across untouched (`OffenceMapper.js:63`) and
     * the contract types the component {@code integer}, so a value that is not one is a body the
     * consumer rejects — no register either way. What must not happen is the third answer: reading
     * {@code 1.5} as {@code 1}, or a value past {@link Integer#MAX_VALUE} as whatever the low bits
     * hold, produces a body that <em>passes</em> validation carrying an index the payload never
     * sent. So only an integral value that fits is carried, and anything else is absent —
     * deviations-register entry 11.
     *
     * @param offence the offence tree
     * @return the order index, or {@code null} when the payload omits it or it is not an index
     */
    private static Integer orderIndex(final JsonNode offence) {
        final JsonNode value = Json.at(offence, "orderIndex");
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue() : null;
    }

    /**
     * The offence's verdict, when the payload names a code for it.
     *
     * <p>The code lives a level deeper than its name suggests —
     * {@code offence.verdict.verdictType.verdictCode} — and it is tested for truthiness, so an empty
     * code produces no verdict at all rather than a verdict with an empty code.
     *
     * @param offence the offence tree
     * @return the verdict, or {@code null}
     */
    private static InformantRegisterVerdict verdict(final JsonNode offence) {
        final JsonNode declared = Json.at(offence, "verdict");
        final JsonNode verdictType = Json.at(declared, "verdictType");
        if (!Json.truthy(verdictType, "verdictCode")) {
            return null;
        }
        final String verdictCode = Json.text(verdictType, "verdictCode");
        return new InformantRegisterVerdict(
                verdictCode,
                Json.text(declared, "verdictDate"),
                // Absent rather than null when the code names no type — deviations entry 9. The
                // legacy writes `verdictType: null` into a field the frozen contract types as a
                // string; the typed tree cannot carry that and omits the component instead.
                VerdictCodes.typeOf(verdictCode));
    }

    /**
     * The reference of a case: its URN when it has one, and its authority reference otherwise.
     *
     * <p>Ports {@code deriveCaseUrn}. The test is truthiness, not nullness — an <em>empty</em> URN
     * falls back too — which is why this is not written as {@code requireNonNullElse}. Pinned by
     * {@code s04-case-reference-urn-then-authority-reference}.
     *
     * @param identifier the case identifier
     * @return the reference
     */
    private static String caseUrn(final JsonNode identifier) {
        return Json.truthy(identifier, "caseURN")
                ? Json.text(identifier, "caseURN")
                : Json.text(identifier, "prosecutionAuthorityReference");
    }
}
