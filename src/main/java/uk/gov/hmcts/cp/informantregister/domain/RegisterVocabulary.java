package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The vocabulary flags computed for one defendant, used later to match subscriptions.
 *
 * <p>A port of {@code VocabularyInfo} in {@code NowsHelper/service/VocabularyService.js}.
 *
 * <p><strong>The two creditor lists are always empty here, deliberately.</strong> The informant
 * register flow constructs the legacy {@code VocabularyService} with two arguments
 * ({@code SetInformantRegister/index.js:149}), leaving {@code majorCreditorMap} and
 * {@code complianceEnforcementList} undefined, and
 * {@code buildApplicableMajorCreditorList} returns {@code []} immediately when the compliance list is
 * falsy. Every creditor-typed subscription rule downstream therefore fails to match. This is a known
 * oddity of the legacy flow and is ported as-is under constitution Principle I — it is named in
 * {@code .claude/rules/design_rules.md} as intended behaviour for this flow, not a defect to fix
 * here. The fields are kept so the shape matches the goldens and so the day the call becomes the
 * four-argument form, only the producer changes.
 *
 * @param custodyLocationIsPolice     the defendant is held at a police station
 * @param custodyLocationIsPrison     the defendant is held at a prison
 * @param atleastOneCustodialResult   at least one result carries a prison prompt
 * @param allNonCustodialResults      no result carries a prison prompt
 * @param atleastOneNonCustodialResult at least one result carries a non-prison prompt
 * @param appearedInPerson            the defendant attended in person on a resulted day
 * @param appearedByVideoLink         the defendant attended by video on a resulted day
 * @param isCpsProsecuted             at least one prosecution case is CPS-prosecuted
 * @param anyAppearance               the defendant appeared at all
 * @param inCustody                   the defendant is held at a police station or a prison
 * @param youthDefendant              the defendant is a youth
 * @param adultDefendant              the defendant is not a youth
 * @param adultOrYouthDefendant       always true; the legacy disjunction of the two above
 * @param welshCourtHearing           the hearing sat at a Welsh court centre
 * @param englishCourtHearing         the hearing did not sit at a Welsh court centre
 * @param anyCourtHearing             always true; the legacy disjunction of the two above
 * @param prosecutorMajorCreditor     always empty in this flow, see above
 * @param nonProsecutorMajorCreditor  always empty in this flow, see above
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterVocabulary(
        boolean custodyLocationIsPolice,
        boolean custodyLocationIsPrison,
        boolean atleastOneCustodialResult,
        boolean allNonCustodialResults,
        boolean atleastOneNonCustodialResult,
        boolean appearedInPerson,
        boolean appearedByVideoLink,
        @JsonProperty("isCpsProsecuted") boolean isCpsProsecuted,
        boolean anyAppearance,
        boolean inCustody,
        boolean youthDefendant,
        boolean adultDefendant,
        boolean adultOrYouthDefendant,
        boolean welshCourtHearing,
        boolean englishCourtHearing,
        boolean anyCourtHearing,
        List<String> prosecutorMajorCreditor,
        List<String> nonProsecutorMajorCreditor) {

    /**
     * Freezes the two creditor lists so the vocabulary cannot be changed after it is built.
     */
    public RegisterVocabulary {
        prosecutorMajorCreditor = FragmentLists.frozen(prosecutorMajorCreditor);
        nonProsecutorMajorCreditor = FragmentLists.frozen(nonProsecutorMajorCreditor);
    }
}
