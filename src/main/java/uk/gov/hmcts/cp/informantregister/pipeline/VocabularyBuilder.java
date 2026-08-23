package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.RegisterVocabulary;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * Computes the vocabulary flags for one defendant.
 *
 * <p>A port of {@code VocabularyService} in {@code NowsHelper/service/VocabularyService.js}, in the
 * <strong>two-argument form</strong> the informant register uses
 * ({@code SetInformantRegister/index.js:149}). The two arguments the register flow does not pass are
 * {@code majorCreditorMap} and {@code complianceEnforcementList}, and their absence short-circuits
 * {@code buildApplicableMajorCreditorList} to an empty list every time. The whole major-creditor
 * branch is therefore unreachable in this flow, so it is not ported: porting an unreachable branch
 * would mean inventing the reference-data lookups that feed it. The two empty lists are still
 * emitted, because the legacy output contains them.
 *
 * <p>This is a known oddity, deliberately preserved — {@code .claude/rules/design_rules.md} names
 * "the vocabulary call is the 2-arg form (major-creditor lists always empty)" as intended behaviour
 * for this flow, and constitution Principle I forbids tidying it away during the port.
 *
 * <p><strong>A missing court centre is fatal, as it is in the legacy.</strong>
 * {@code welshCourtHearing} reads {@code hearingObj.courtCentre.welshCourtCentre} with no guard, so a
 * hearing with no court centre throws. Every legacy Jest case supplies one for exactly that reason.
 * The throw is left in place: the alternative is to invent a default for a field the legacy never
 * defaults, and a hearing with no court centre is not something this service should quietly file a
 * register for.
 */
final class VocabularyBuilder {

    /** The prompt reference that marks a result as custodial. */
    private static final String PRISON_PROMPT = "prisonOrganisationName";

    /** The custody location values, from {@code NowsHelper/service/LocationTypeEnum.js}. */
    private static final String POLICE_STATION = "Police Station";

    /** The custody location value marking a prison. */
    private static final String PRISON = "Prison";

    private final JsonNode hearing;

    /**
     * Creates the builder for one hearing.
     *
     * @param hearing the canonical hearing tree
     */
    /* default */ VocabularyBuilder(final JsonNode hearing) {
        this.hearing = hearing;
    }

    /**
     * Computes the vocabulary for one defendant.
     *
     * @param defendant the defendant context, already filtered for court extract
     * @return the vocabulary flags
     */
    /* default */ RegisterVocabulary build(final DefendantContext defendant) {
        final boolean custodyIsPolice = custodyAt(defendant, POLICE_STATION);
        final boolean custodyIsPrison = custodyAt(defendant, PRISON);

        final boolean atleastOneCustodialResult = hasPromptMatching(defendant, true);
        final boolean allNonCustodialResults = !atleastOneCustodialResult;
        final boolean atleastOneNonCustodialResult =
                allNonCustodialResults || hasPromptMatching(defendant, false);

        final boolean appearedInPerson = appeared(defendant, "IN_PERSON");
        final boolean appearedByVideoLink = appeared(defendant, "BY_VIDEO");

        final boolean youthDefendant = Boolean.TRUE.equals(defendant.youthDefendant());

        final JsonNode courtCentre = Json.at(hearing, "courtCentre");
        if (courtCentre == null || courtCentre.isNull()) {
            // Classified: a hearing with no court centre reads the same on every delivery. An
            // explicit JSON null counts, because `null.welshCourtCentre` is the same TypeError as
            // `undefined.welshCourtCentre` — treating it as "not Welsh" would mark the hearing
            // English and emit a register the legacy never produced.
            throw new TransformationFailedException("hearing carries no court centre");
        }
        final boolean welshCourtHearing = Json.truthy(courtCentre, "welshCourtCentre");

        return new RegisterVocabulary(
                custodyIsPolice,
                custodyIsPrison,
                atleastOneCustodialResult,
                allNonCustodialResults,
                atleastOneNonCustodialResult,
                appearedInPerson,
                appearedByVideoLink,
                cpsProsecuted(),
                appearedByVideoLink || appearedInPerson,
                custodyIsPrison || custodyIsPolice,
                youthDefendant,
                !youthDefendant,
                true,
                welshCourtHearing,
                !welshCourtHearing,
                true,
                List.of(),
                List.of());
    }

    /**
     * Whether this defendant is held at the given kind of location.
     *
     * @param defendant the defendant context
     * @param location  the custody location to look for
     * @return whether the defendant is held there
     */
    private boolean custodyAt(final DefendantContext defendant, final String location) {
        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            // `prosecutionCase.defendants.forEach` — unguarded in the legacy here too.
            for (final JsonNode caseDefendant
                    : Json.dereferencedArray(prosecutionCase, "defendants")) {
                if (matches(defendant, Json.text(caseDefendant, "masterDefendantId"))
                        && custodyIs(caseDefendant, location)) {
                    return true;
                }
            }
        }
        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            final JsonNode masterDefendant =
                    Json.at(Json.at(application, "subject"), "masterDefendant");
            if (masterDefendant != null
                    && matches(defendant, Json.text(masterDefendant, "masterDefendantId"))
                    && custodyIs(masterDefendant, location)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a defendant node's custodial establishment is the given location.
     *
     * @param defendantNode the defendant or master defendant node
     * @param location      the custody location to look for
     * @return whether the custody matches
     */
    private static boolean custodyIs(final JsonNode defendantNode, final String location) {
        final JsonNode personDefendant = Json.at(defendantNode, "personDefendant");
        final JsonNode custodialEstablishment = Json.at(personDefendant, "custodialEstablishment");
        return custodialEstablishment != null
                && location.equals(Json.text(custodialEstablishment, "custody"));
    }

    /**
     * Whether an identity is the one this defendant context is gathered under.
     *
     * @param defendant         the defendant context
     * @param masterDefendantId the identity to compare
     * @return whether they match
     */
    private static boolean matches(
            final DefendantContext defendant, final String masterDefendantId) {
        return masterDefendantId != null
                && masterDefendantId.equals(defendant.masterDefendantId());
    }

    /**
     * Whether any of this defendant's results carries a prison prompt, or any non-prison prompt.
     *
     * @param defendant the defendant context
     * @param custodial whether to look for the prison prompt or for anything but it
     * @return whether such a prompt was found
     */
    private static boolean hasPromptMatching(
            final DefendantContext defendant, final boolean custodial) {

        for (final RegisterResult result : defendant.results()) {
            final JsonNode prompts = Json.at(result.judicialResult(), "judicialResultPrompts");
            if (!Json.truthy(prompts)) {
                continue;
            }
            for (final JsonNode prompt : Json.array(result.judicialResult(),
                    "judicialResultPrompts")) {
                final String reference = Json.text(prompt, "promptReference");
                if (reference == null || reference.isEmpty()) {
                    continue;
                }
                if (custodial == PRISON_PROMPT.equals(reference)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the defendant attended in the given way, on a day one of their results was ordered.
     *
     * @param defendant      the defendant context
     * @param attendanceType the attendance type to look for
     * @return whether the defendant attended that way
     */
    private boolean appeared(final DefendantContext defendant, final String attendanceType) {
        for (final JsonNode attendance : Json.array(hearing, "defendantAttendance")) {
            if (!defendant.defendantIds().contains(Json.text(attendance, "defendantId"))) {
                continue;
            }
            // `defendantAttendance.attendanceDays.forEach` — unguarded in the legacy.
            for (final JsonNode attendanceDay
                    : Json.dereferencedArray(attendance, "attendanceDays")) {
                final String day = Json.text(attendanceDay, "day");
                if (orderedOn(defendant, day)
                        && attendanceType.equals(Json.text(attendanceDay, "attendanceType"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any of this defendant's results was ordered on the given day.
     *
     * @param defendant the defendant context
     * @param day       the day to look for
     * @return whether a result was ordered then
     */
    private static boolean orderedOn(final DefendantContext defendant, final String day) {
        for (final RegisterResult result : defendant.results()) {
            final String orderedDate = Json.text(result.judicialResult(), "orderedDate");
            if (orderedDate == null ? day == null : orderedDate.equals(day)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any prosecution case in the hearing is CPS-prosecuted.
     *
     * @return whether a CPS prosecutor was found
     */
    private boolean cpsProsecuted() {
        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            final JsonNode cpsFlag = Json.at(Json.at(prosecutionCase, "prosecutor"), "isCps");
            // The legacy test is `=== true`, so only a real boolean true counts — a truthy
            // string or 1 does not.
            if (cpsFlag != null && cpsFlag.isBoolean() && cpsFlag.booleanValue()) {
                return true;
            }
        }
        return false;
    }
}
