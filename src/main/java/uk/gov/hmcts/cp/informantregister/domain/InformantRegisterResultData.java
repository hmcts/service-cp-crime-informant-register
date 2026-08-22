package uk.gov.hmcts.cp.informantregister.domain;

/**
 * The structured detail carried alongside a result's text.
 *
 * <p>Every component is a string, including the ones whose names read like dates and numbers. That
 * is the contract's own choice, not a simplification made here: {@code informantRegisterResultData
 * .json} declares all nine as {@code "type": "string"} with no {@code format}, and the results-side
 * binding {@code InformantRegisterResultData} holds all nine as {@code String}. Rendering an amount
 * or a date is the producing pipeline's business, and typing them here would quietly impose a
 * rendering the contract does not ask for.
 *
 * <p>The schema declares no required component, so a result may carry data with only one field set.
 *
 * @param amount                  the monetary amount attached to the result, as rendered
 * @param nextHearingDate         the next hearing date, as rendered
 * @param nextCourtLocation       the court the next hearing is listed at
 * @param durationValue           the primary duration's magnitude, as rendered
 * @param durationUnit            the primary duration's unit
 * @param durationStartDate       the primary duration's start date, as rendered
 * @param durationEndDate         the primary duration's end date, as rendered
 * @param secondaryDurationValue  the secondary duration's magnitude, as rendered
 * @param secondaryDurationUnit   the secondary duration's unit
 */
public record InformantRegisterResultData(
        String amount,
        String nextHearingDate,
        String nextCourtLocation,
        String durationValue,
        String durationUnit,
        String durationStartDate,
        String durationEndDate,
        String secondaryDurationValue,
        String secondaryDurationUnit) {
}
