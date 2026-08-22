package uk.gov.hmcts.cp.informantregister.domain;

import java.util.List;

/**
 * One offence on a defendant's prosecution case, as the register renders it.
 *
 * <p>{@code orderIndex} is a boxed {@code Integer} rather than an {@code int} for the same reason
 * every other optional component is nullable: the schema requires it, but a partially built offence
 * has to be able to say it has no index yet, and an {@code int} would silently claim {@code 0} —
 * which the schema happily accepts, being its declared minimum. A missing index must fail
 * validation, not pass as the first offence.
 *
 * <p>{@code pleaValue} is a free string. The schema declares no {@code enum} for it, so neither does
 * this record.
 *
 * @param originatingCaseUrn the URN of the case the offence came from
 * @param offenceCode        the CJS code for the offence; required by the schema
 * @param orderIndex         the offence's index within the case; required, minimum zero
 * @param offenceTitle       the title taken from reference data; required by the schema
 * @param pleaValue          the defendant's plea against the offence
 * @param verdict            the structured verdict recorded against the offence
 * @param offenceResults     results recorded against this offence
 */
public record InformantRegisterOffence(
        String originatingCaseUrn,
        String offenceCode,
        Integer orderIndex,
        String offenceTitle,
        String pleaValue,
        InformantRegisterVerdict verdict,
        List<InformantRegisterResult> offenceResults) {
}
