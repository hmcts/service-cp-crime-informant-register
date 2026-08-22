package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.RegisterVocabulary;

/**
 * The mutable working copy of one defendant's context, while it is still being gathered.
 *
 * <p>The legacy {@code DefendantContextBase} is built by accumulation: results are concatenated on
 * as four separate passes find them, the ordered date is computed at the end, court-extract filtering
 * replaces the result list in place, and vocabulary is attached last. Modelling that as a chain of
 * immutable records would mean rebuilding the whole object five times and would make the port harder
 * to check against the source, which is the thing that has to be verifiable here.
 *
 * <p>So the accumulation stays mutable and package-private, and {@link #freeze()} converts it into
 * the immutable {@link RegisterDefendant} once. Nothing outside this package ever sees the mutable
 * form, so the fragment tree that leaves the pipeline is immutable in the way records promise.
 */
final class DefendantContext {

    private final List<String> defendantIds = new ArrayList<>();
    private final List<String> cases = new ArrayList<>();
    private final List<String> applications = new ArrayList<>();
    private List<RegisterResult> results = new ArrayList<>();
    private String masterDefendantId;
    private Boolean youthDefendant;
    private String orderedDate;
    private RegisterVocabulary vocabulary;

    List<String> defendantIds() {
        return defendantIds;
    }

    List<String> cases() {
        return cases;
    }

    List<String> applications() {
        return applications;
    }

    List<RegisterResult> results() {
        return results;
    }

    void results(final List<RegisterResult> replacement) {
        this.results = replacement;
    }

    void addResults(final List<RegisterResult> additional) {
        this.results.addAll(additional);
    }

    String masterDefendantId() {
        return masterDefendantId;
    }

    void masterDefendantId(final String value) {
        this.masterDefendantId = value;
    }

    Boolean youthDefendant() {
        return youthDefendant;
    }

    void youthDefendant(final Boolean value) {
        this.youthDefendant = value;
    }

    void orderedDate(final String value) {
        this.orderedDate = value;
    }

    void vocabulary(final RegisterVocabulary value) {
        this.vocabulary = value;
    }

    /**
     * Converts this working copy into the immutable defendant the fragment carries.
     *
     * @return the frozen defendant
     */
    RegisterDefendant freeze() {
        return new RegisterDefendant(
                defendantIds, results, cases, applications,
                masterDefendantId, youthDefendant, orderedDate, vocabulary);
    }
}
