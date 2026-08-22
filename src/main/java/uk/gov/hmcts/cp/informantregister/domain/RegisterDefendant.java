package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One defendant's results, gathered across every case and application in the hearing.
 *
 * <p>A port of {@code DefendantContextBase} in
 * {@code NowsHelper/service/DefendantContextBaseService.js}. The legacy name is "defendant context
 * base"; the fragment field that carries these is {@code registerDefendants}, and this record is
 * named for what it is rather than for the class it came from.
 *
 * <p>{@code isYouthDefendant} is a nullable {@link Boolean} rather than a primitive on purpose. The
 * legacy class initialises it to {@code false} but then overwrites it with {@code defendant.isYouth},
 * which is {@code undefined} whenever the payload omits the flag — and an undefined value is dropped
 * from the output altogether rather than written as {@code false}. The goldens captured from the
 * legacy code contain no {@code isYouthDefendant} field at all for those defendants, so a primitive
 * would have introduced a field the legacy never emitted. The vocabulary flag derived from it is
 * still a plain boolean, because the legacy coerces with {@code !!} before using it.
 *
 * @param defendantIds      the case-level defendant ids this master defendant appears as
 * @param results           the judicial results for this defendant, after court-extract filtering
 * @param cases             the prosecution cases this defendant appears in
 * @param applications      the court applications this defendant is the subject of
 * @param masterDefendantId the identity this defendant is gathered under
 * @param isYouthDefendant  whether the defendant is a youth, absent when the payload omits it
 * @param orderedDate       the latest ordered date across this defendant's results
 * @param vocabulary        the vocabulary flags computed for this defendant
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterDefendant(
        List<String> defendantIds,
        List<RegisterResult> results,
        List<String> cases,
        List<String> applications,
        String masterDefendantId,
        @JsonProperty("isYouthDefendant") Boolean isYouthDefendant,
        String orderedDate,
        RegisterVocabulary vocabulary) {

    /**
     * Freezes the list-valued components so the defendant cannot be changed after it is built.
     */
    public RegisterDefendant {
        defendantIds = FragmentLists.frozen(defendantIds);
        results = FragmentLists.frozen(results);
        cases = FragmentLists.frozen(cases);
        applications = FragmentLists.frozen(applications);
    }
}
