package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.ResultLevel;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * Gathers every judicial result in a hearing under the defendant it belongs to.
 *
 * <p>A port of {@code DefendantContextService} in
 * {@code NowsHelper/service/DefendantContextBaseService.js}, fixed to the configuration the informant
 * register uses: {@code new DefendantContextService(hearingObj, true, true)} — that is,
 * {@code isRegister} and {@code isInformantRegister} both true
 * ({@code SetInformantRegister/index.js:71}). The other configurations are not ported, because this
 * service never uses them and untested branches are a liability, not a feature.
 *
 * <p><strong>What that configuration changes.</strong> Two things, both load-bearing:
 * <ul>
 *   <li>An application is eligible only when it has <em>both</em> a subject master defendant and an
 *       applicant prosecuting authority. Under the register-but-not-informant-register configuration
 *       the master defendant alone is enough, and that difference is what makes
 *       {@code hearing-results-from-court-application-applicant-masterDefendant.json} produce nothing
 *       at all.</li>
 *   <li>Results found under an application's cases and court orders are tagged {@code OFFENCE}, not
 *       {@code APPLICATION}.</li>
 * </ul>
 *
 * <p><strong>Copy, do not mutate.</strong> The legacy code writes {@code level},
 * {@code prosecutionCaseId}, {@code offenceId} and {@code offenceTitle} onto the judicial results
 * inside the hearing payload itself, permanently altering the cached tree. This port copies each
 * judicial result first and annotates the copy, which is both the rule for this codebase (the core
 * never mutates a node it did not construct) and a real fix to a latent aliasing bug — the legacy
 * behaviour is only safe because nothing happens to read that tree again afterwards. The observable
 * output is identical, so this is an implementation difference and not a behaviour deviation.
 */
final class DefendantContextBuilder {

    private final JsonNode hearing;
    private final HearingDates dates;

    /**
     * Creates the builder for one hearing.
     *
     * @param hearing the canonical hearing tree
     * @param dates   the date service used to order results
     */
    /* default */ DefendantContextBuilder(final JsonNode hearing, final HearingDates dates) {
        this.hearing = hearing;
        this.dates = dates;
    }

    /**
     * Gathers the defendant contexts, in the order the legacy code produces them.
     *
     * <p>Only defendants that ended up with a master defendant id are returned, matching the legacy
     * guard — a context keyed on an absent identity is dropped rather than emitted.
     *
     * @return the defendant contexts
     */
    /* default */ List<DefendantContext> build() {
        final Map<String, DefendantContext> byMasterDefendant = new LinkedHashMap<>();

        setJudicialResultsAtDefendantAndOffenceLevel(byMasterDefendant);
        setJudicialResultsAtCourtApplicationLevel(byMasterDefendant);
        setJudicialResultsAtDefendantCaseLevel(byMasterDefendant);

        final List<DefendantContext> gathered = new ArrayList<>();
        for (final DefendantContext context : byMasterDefendant.values()) {
            if (context.masterDefendantId() != null && !context.masterDefendantId().isEmpty()) {
                context.orderedDate(latestOrderedDate(context));
                gathered.add(context);
            }
        }
        return gathered;
    }

    /**
     * Adds the results recorded against a defendant's case and against their offences.
     *
     * @param byMasterDefendant the contexts gathered so far
     */
    private void setJudicialResultsAtDefendantAndOffenceLevel(
            final Map<String, DefendantContext> byMasterDefendant) {

        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            final String caseId = Json.text(prosecutionCase, "id");

            // `prosecutionCase.defendants.forEach` — unguarded in the legacy, so a case with no
            // defendants field ends the hearing there rather than contributing nothing.
            for (final JsonNode defendant : Json.dereferencedArray(prosecutionCase, "defendants")) {
                final String masterDefendantId = Json.text(defendant, "masterDefendantId");
                final DefendantContext context = byMasterDefendant
                        .computeIfAbsent(masterDefendantId, key -> new DefendantContext());

                context.cases().add(caseId);
                context.defendantIds().add(Json.text(defendant, "id"));

                if (Json.truthy(defendant, "defendantCaseJudicialResults")) {
                    context.addResults(caseLevelResults(prosecutionCase, defendant, caseId));
                }
                context.addResults(offenceLevelResults(defendant, caseId, masterDefendantId));

                if (context.masterDefendantId() == null || context.masterDefendantId().isEmpty()) {
                    context.masterDefendantId(masterDefendantId);
                    context.youthDefendant(booleanOrAbsent(defendant, "isYouth"));
                }
            }
        }
    }

    /**
     * Builds the results recorded against one defendant's case.
     *
     * @param prosecutionCase   the prosecution case
     * @param defendant         the defendant within it
     * @param caseId            the prosecution case id
     * @return the case-level results
     */
    private List<RegisterResult> caseLevelResults(
            final JsonNode prosecutionCase, final JsonNode defendant, final String caseId) {

        final List<RegisterResult> results = new ArrayList<>();
        for (final JsonNode judicialResult
                : Json.array(defendant, "defendantCaseJudicialResults")) {
            if (Json.truthy(judicialResult, "isDeleted")) {
                continue;
            }
            final String offenceId = Json.text(judicialResult, "offenceId");
            final ObjectNode annotated = copyOf(judicialResult);
            annotated.put("level", ResultLevel.CASE.code());
            putOrRemove(annotated, "prosecutionCaseId", caseId);

            // The legacy code looks the offence title up only when the result names an offence, and
            // only writes it when the offence actually has one — unlike the offence-level pass below,
            // which overwrites unconditionally.
            if (offenceId != null) {
                final String offenceTitle = offenceTitleOf(defendant, offenceId);
                if (offenceTitle != null) {
                    annotated.put("offenceTitle", offenceTitle);
                }
            }

            results.add(new RegisterResult(
                    caseId,
                    Json.text(defendant, "id"),
                    offenceId,
                    null,
                    ResultLevel.CASE,
                    Json.text(defendant, "masterDefendantId"),
                    annotated,
                    null,
                    null));
        }
        return results;
    }

    /**
     * Finds the title of one of a defendant's offences.
     *
     * @param defendant the defendant
     * @param offenceId the offence to look for
     * @return the offence title, or {@code null}
     */
    private String offenceTitleOf(final JsonNode defendant, final String offenceId) {
        // `defendant.offences.find(...)` — unguarded in the legacy, and reached only because the
        // case-level result named an offence.
        for (final JsonNode offence : Json.dereferencedArray(defendant, "offences")) {
            if (offenceId.equals(Json.text(offence, "id"))) {
                return Json.text(offence, "offenceTitle");
            }
        }
        return null;
    }

    /**
     * Builds the results recorded against a defendant's offences.
     *
     * @param defendant         the defendant
     * @param caseId            the prosecution case id
     * @param masterDefendantId the defendant's identity across cases
     * @return the offence-level results
     */
    private List<RegisterResult> offenceLevelResults(
            final JsonNode defendant, final String caseId, final String masterDefendantId) {

        final List<RegisterResult> results = new ArrayList<>();
        // `defendant.offences.forEach` — unguarded in the legacy.
        for (final JsonNode offence : Json.dereferencedArray(defendant, "offences")) {
            if (!Json.truthy(offence, "judicialResults")) {
                continue;
            }
            final String offenceId = Json.text(offence, "id");
            for (final JsonNode judicialResult : Json.array(offence, "judicialResults")) {
                if (Json.truthy(judicialResult, "isDeleted")) {
                    continue;
                }
                final ObjectNode annotated = copyOf(judicialResult);
                putOrRemove(annotated, "prosecutionCaseId", caseId);
                putOrRemove(annotated, "offenceId", offenceId);
                annotated.put("level", ResultLevel.OFFENCE.code());
                putOrRemove(annotated, "offenceTitle", Json.text(offence, "offenceTitle"));

                results.add(new RegisterResult(
                        caseId,
                        Json.text(defendant, "id"),
                        offenceId,
                        null,
                        ResultLevel.OFFENCE,
                        masterDefendantId,
                        annotated,
                        null,
                        null));
            }
        }
        return results;
    }

    /**
     * Adds the results reached through a court application.
     *
     * @param byMasterDefendant the contexts gathered so far
     */
    private void setJudicialResultsAtCourtApplicationLevel(
            final Map<String, DefendantContext> byMasterDefendant) {

        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            if (!isEligible(application)) {
                continue;
            }
            final JsonNode subject = Json.at(application, "subject");
            final JsonNode masterDefendant = Json.at(subject, "masterDefendant");
            final String masterDefendantId = Json.text(masterDefendant, "masterDefendantId");

            DefendantContext context = byMasterDefendant.get(masterDefendantId);
            if (context == null) {
                context = new DefendantContext();
            }

            context.defendantIds().add(masterDefendantId);
            context.applications().add(Json.text(application, "id"));

            addLinkedCases(context, application);

            context.addResults(applicationLevelResults(application, masterDefendantId));
            context.addResults(applicationCaseResults(application, masterDefendantId));
            context.addResults(courtOrderResults(application, masterDefendantId));

            if (context.masterDefendantId() == null || context.masterDefendantId().isEmpty()) {
                context.masterDefendantId(masterDefendantId);
                context.youthDefendant(booleanOrAbsent(masterDefendant, "isYouth"));
                byMasterDefendant.put(context.masterDefendantId(), context);
            }
        }
    }

    /**
     * Records the prosecution cases an application is linked to, without duplicating them.
     *
     * @param context     the defendant context being gathered
     * @param application the court application
     */
    private void addLinkedCases(final DefendantContext context, final JsonNode application) {
        for (final JsonNode applicationCase : Json.array(application, "courtApplicationCases")) {
            final String caseId = Json.text(applicationCase, "prosecutionCaseId");
            if (!context.cases().contains(caseId)) {
                context.cases().add(caseId);
            }
        }
        // The legacy `if (courtApplication.courtOrder)` guards the court order and nothing else, so
        // an order with no offences field is dereferenced anyway and ends the hearing. The court
        // order pass further down guards both and is left alone.
        if (!Json.truthy(application, "courtOrder")) {
            return;
        }
        final JsonNode courtOrder = Json.at(application, "courtOrder");
        for (final JsonNode courtOrderOffence
                : Json.dereferencedArray(courtOrder, "courtOrderOffences")) {
            final String caseId = Json.text(courtOrderOffence, "prosecutionCaseId");
            if (!context.cases().contains(caseId)) {
                context.cases().add(caseId);
            }
        }
    }

    /**
     * Whether an application contributes to an informant register at all.
     *
     * <p>Ports {@code isEligible} at the {@code (isRegister, isInformantRegister)} both-true setting:
     * a subject master defendant <em>and</em> an applicant prosecuting authority.
     *
     * @param application the court application
     * @return whether the application is eligible
     */
    private boolean isEligible(final JsonNode application) {
        final JsonNode subject = Json.at(application, "subject");
        final JsonNode applicant = Json.at(application, "applicant");
        return Json.truthy(subject, "masterDefendant")
                && Json.truthy(applicant, "prosecutingAuthority");
    }

    /**
     * Builds the results recorded directly against an application.
     *
     * @param application       the court application
     * @param masterDefendantId the subject's identity
     * @return the application-level results
     */
    private List<RegisterResult> applicationLevelResults(
            final JsonNode application, final String masterDefendantId) {

        final List<RegisterResult> results = new ArrayList<>();
        // `courtApplication.judicialResults && courtApplication.judicialResults.length > 0`. The
        // length half is why this is not an emptiness test on the iterated list: a truthy value that
        // is not an array has no length, `undefined > 0` is false, and the legacy skips this level
        // and carries on with the rest of the application rather than failing the hearing.
        if (!Json.nonEmptyArray(application, "judicialResults")) {
            return results;
        }
        for (final JsonNode judicialResult : Json.array(application, "judicialResults")) {
            if (Json.truthy(judicialResult, "isDeleted")) {
                continue;
            }
            results.add(new RegisterResult(
                    null,
                    null,
                    Json.text(judicialResult, "offenceId"),
                    Json.text(application, "id"),
                    ResultLevel.APPLICATION,
                    masterDefendantId,
                    copyOf(judicialResult),
                    Boolean.TRUE,
                    Boolean.TRUE));
        }
        return results;
    }

    /**
     * Builds the results recorded against the offences of an application's linked cases.
     *
     * @param application       the court application
     * @param masterDefendantId the subject's identity
     * @return the results, tagged at offence level
     */
    private List<RegisterResult> applicationCaseResults(
            final JsonNode application, final String masterDefendantId) {

        final List<RegisterResult> results = new ArrayList<>();
        for (final JsonNode applicationCase : Json.array(application, "courtApplicationCases")) {
            for (final JsonNode offence : Json.array(applicationCase, "offences")) {
                if (!Json.truthy(offence, "judicialResults")) {
                    continue;
                }
                final String offenceId = Json.text(offence, "id");
                for (final JsonNode judicialResult : Json.array(offence, "judicialResults")) {
                    if (Json.truthy(judicialResult, "isDeleted")) {
                        continue;
                    }
                    final ObjectNode annotated = copyOf(judicialResult);
                    putOrRemove(annotated, "offenceId", offenceId);
                    putOrRemove(annotated, "offenceTitle", Json.text(offence, "offenceTitle"));

                    results.add(new RegisterResult(
                            null,
                            null,
                            offenceId,
                            Json.text(application, "id"),
                            ResultLevel.OFFENCE,
                            masterDefendantId,
                            annotated,
                            null,
                            Boolean.TRUE));
                }
            }
        }
        return results;
    }

    /**
     * Builds the results recorded against the offences of an application's court order.
     *
     * @param application       the court application
     * @param masterDefendantId the subject's identity
     * @return the results, tagged at offence level
     */
    private List<RegisterResult> courtOrderResults(
            final JsonNode application, final String masterDefendantId) {

        final List<RegisterResult> results = new ArrayList<>();
        final JsonNode courtOrder = Json.at(application, "courtOrder");
        for (final JsonNode courtOrderOffence : Json.array(courtOrder, "courtOrderOffences")) {
            final JsonNode offence = Json.at(courtOrderOffence, "offence");
            if (!Json.truthy(offence, "judicialResults")) {
                continue;
            }
            final String offenceId = Json.text(offence, "id");
            for (final JsonNode judicialResult : Json.array(offence, "judicialResults")) {
                if (Json.truthy(judicialResult, "isDeleted")) {
                    continue;
                }
                final ObjectNode annotated = copyOf(judicialResult);
                putOrRemove(annotated, "offenceId", offenceId);

                results.add(new RegisterResult(
                        null,
                        null,
                        offenceId,
                        Json.text(application, "id"),
                        ResultLevel.OFFENCE,
                        masterDefendantId,
                        annotated,
                        null,
                        Boolean.TRUE));
            }
        }
        return results;
    }

    /**
     * Adds the results recorded against the defendant across all of their cases.
     *
     * <p>The legacy code looks the context up and concatenates onto it without checking that it
     * found one, so a hearing carrying a defendant-level result for a defendant that appears nowhere
     * else fails outright and the hearing produces nothing. That is reproduced here rather than
     * softened: swallowing it would invent a register the legacy never sends.
     *
     * @param byMasterDefendant the contexts gathered so far
     */
    private void setJudicialResultsAtDefendantCaseLevel(
            final Map<String, DefendantContext> byMasterDefendant) {

        for (final JsonNode defendantJudicialResult
                : Json.array(hearing, "defendantJudicialResults")) {

            final String masterDefendantId =
                    Json.text(defendantJudicialResult, "masterDefendantId");
            final DefendantContext context = byMasterDefendant.get(masterDefendantId);
            final JsonNode judicialResult = Json.at(defendantJudicialResult, "judicialResult");

            final List<RegisterResult> results = new ArrayList<>();
            if (!Json.truthy(judicialResult, "isDeleted")) {
                final ObjectNode annotated = copyOf(judicialResult);
                annotated.put("level", ResultLevel.DEFENDANT.code());
                results.add(new RegisterResult(
                        null,
                        null,
                        Json.text(judicialResult, "offenceId"),
                        null,
                        ResultLevel.DEFENDANT,
                        masterDefendantId,
                        annotated,
                        null,
                        null));
            }

            if (context == null) {
                // Classified at the throw site, as the error-handling rules require: no redelivery
                // will introduce the missing defendant, so the delivery is parked rather than
                // retried. The identity is not quoted — it is producer-supplied and reaches the
                // dead-letter description and the log index.
                throw new TransformationFailedException(
                        "defendant-level result names a defendant with no gathered context");
            }
            context.addResults(results);
        }
    }

    /**
     * The latest ordered date across a defendant's results.
     *
     * <p>Ports {@code DefendantContextBaseService.js:294-297}, which maps the results to their
     * ordered dates and sorts them with a comparator that parses — and, unlike its twin in
     * {@code RegisterFragmentService}, with no error handling of any kind. Whether a bad date is
     * fatal here depends on how many results the defendant happens to have, which is parity pin
     * {@code s06}; {@link OrderedDates} carries the {@code sort} rules that decide it.
     *
     * @param context the defendant context
     * @return the latest ordered date, or {@code null} when there are no results
     */
    private String latestOrderedDate(final DefendantContext context) {
        return OrderedDates.latest(
                context.results().stream()
                        .map(result -> Json.at(result.judicialResult(), "orderedDate"))
                        .toList(),
                dates);
    }

    /**
     * Reads a flag that must stay absent when the payload omits it.
     *
     * @param node  the object to read; may be {@code null}
     * @param field the field name
     * @return the flag, or {@code null} when it is not present
     */
    private static Boolean booleanOrAbsent(final JsonNode node, final String field) {
        final JsonNode value = Json.at(node, field);
        return value == null || value.isNull() ? null : value.booleanValue();
    }

    /**
     * A private, mutable copy of a judicial result.
     *
     * @param judicialResult the result to copy
     * @return a copy this class owns and may annotate
     */
    private static ObjectNode copyOf(final JsonNode judicialResult) {
        return (ObjectNode) judicialResult.deepCopy();
    }

    /**
     * Writes a field, or removes it when the value is absent.
     *
     * <p>The removal is the point. The legacy code assigns {@code undefined} when the source field is
     * missing, and a property whose value is {@code undefined} is dropped by
     * {@code JSON.stringify} — so assigning an absent offence title to a result that already had one
     * <em>deletes</em> it from the output. Writing a JSON null instead would leave a field the legacy
     * never emits.
     *
     * @param node  the node to write to
     * @param field the field name
     * @param value the value, or {@code null} to remove the field
     */
    private static void putOrRemove(
            final ObjectNode node, final String field, final String value) {
        if (value == null) {
            node.remove(field);
        } else {
            node.put(field, value);
        }
    }
}
