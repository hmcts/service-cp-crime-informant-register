package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The structured verdict recorded against an offence.
 *
 * <p>The schema file is {@code verdict.json} and the results-side binding is {@code Verdict}; the
 * type is named for the tree it belongs to here, because {@code Verdict} unqualified in a shared
 * domain package would read as the service's own notion of a verdict rather than as one node of a
 * foreign document. The JSON property is still {@code verdict}, which is what the wire cares about.
 *
 * <p>All three components are free strings on the wire. The schema's descriptions name value sets —
 * {@code G}, {@code N}, {@code PSJ} for the code and {@code FOUND_GUILTY}, {@code FOUND_NOT_GUILTY},
 * {@code PROVED_SJP} for the type — but they are prose, not an {@code enum}, so no enumeration is
 * declared here either. Validating against a set the contract does not impose would reject bodies
 * the consumer accepts.
 *
 * <p>The schema declares no required component and one dependency: {@code verdictDate} present
 * requires {@code verdictCode} present. That is a rule about a whole document rather than about a
 * field, so it is asserted by the schema in test rather than enforced in this constructor.
 *
 * @param verdictCode the verdict code, as the reference data renders it
 * @param verdictDate the conviction date, rendered as the contract's descriptions expect
 * @param verdictType the verdict type resolved from reference data
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InformantRegisterVerdict(
        String verdictCode,
        String verdictDate,
        String verdictType) {
}
