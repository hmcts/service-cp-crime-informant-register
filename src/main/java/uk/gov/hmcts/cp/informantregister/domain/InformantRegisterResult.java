package uk.gov.hmcts.cp.informantregister.domain;

/**
 * One published result as it appears in an informant register.
 *
 * <p>The same record serves all three places the contract puts results — a defendant's
 * {@code results}, a case or application's {@code results}, and an offence's
 * {@code offenceResults} — because all three are declared as arrays of
 * {@code informantRegisterResult.json}. One record for one schema, so a change to the schema cannot
 * be applied to two of the three and missed on the last.
 *
 * @param resultText    the textual description of the result; the schema's only required component
 * @param cjsResultCode the CJS code for the result
 * @param resultData    the structured detail behind the text
 */
public record InformantRegisterResult(
        String resultText,
        String cjsResultCode,
        InformantRegisterResultData resultData) {
}
