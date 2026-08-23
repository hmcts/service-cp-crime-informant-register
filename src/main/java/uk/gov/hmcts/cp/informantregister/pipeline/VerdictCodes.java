package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.Map;

/**
 * The three verdict codes the register knows how to name, and the answer for everything else.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * VerdictCodeMapping.js}, which is a three-entry lookup with a fourth entry mapping {@code None} to
 * {@code null} and an explicit {@code undefined -> null} at the end. Both of those collapse to the
 * same answer here: the code has no name.
 *
 * <p>Modelled as a lookup rather than as an enum on purpose. The contract declares no enumeration for
 * these — {@code verdictCode} and {@code verdictType} are free strings, documented by description
 * text only — so an enum would impose a validation the contract does not, and an unrecognised code
 * would become an error where the legacy simply has no name for it.
 */
final class VerdictCodes {

    private static final Map<String, String> TYPES_BY_CODE = Map.of(
            "G", "FOUND_GUILTY",
            "N", "FOUND_NOT_GUILTY",
            "PSJ", "PROVED_SJP");

    private VerdictCodes() {
    }

    /**
     * The verdict type a code names.
     *
     * @param verdictCode the code from the payload; may be {@code null}
     * @return the type, or {@code null} when the code names none
     */
    /* default */ static String typeOf(final String verdictCode) {
        return verdictCode == null ? null : TYPES_BY_CODE.get(verdictCode);
    }
}
