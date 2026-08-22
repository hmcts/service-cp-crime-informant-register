package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * One judicial result, tagged with the level and the case, offence or application it belongs to.
 *
 * <p>A port of the {@code Result} class in {@code NowsHelper/service/DefendantContextBaseService.js}.
 * The judicial result itself stays a canonical Jackson tree (constitution Principle IV): this service
 * reads it and copies it, and never builds a typed model of the hearing.
 *
 * <p><strong>Why every component is nullable.</strong> The legacy class declares all of these fields
 * and leaves the ones that do not apply as {@code undefined}, which {@code JSON.stringify} then omits
 * from the output entirely. {@code @JsonInclude(NON_NULL)} reproduces that exactly, and it is what
 * the parity goldens were captured with — an offence-level result carries no {@code applicationId},
 * so the field is absent rather than null.
 *
 * <p>{@code isApplicant} and {@code includeInNcesResult} are named with {@link JsonProperty} rather
 * than left to Jackson's bean-name inference. An accessor called {@code isApplicant()} would
 * otherwise be published as {@code applicant}, silently renaming a field the goldens match on.
 *
 * @param prosecutionCaseId   the prosecution case this result was recorded under
 * @param defendantId         the case-level defendant this result was recorded against
 * @param offenceId           the offence this result was recorded against
 * @param applicationId       the court application this result was recorded under
 * @param level               the level the result was recorded at
 * @param masterDefendantId   the defendant this result belongs to across cases
 * @param judicialResult      the judicial result itself, as a canonical tree
 * @param includeInNcesResult set only on application-level results by the legacy port
 * @param isApplicant         set only on results reached through a court application
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResult(
        String prosecutionCaseId,
        String defendantId,
        String offenceId,
        String applicationId,
        ResultLevel level,
        String masterDefendantId,
        JsonNode judicialResult,
        @JsonProperty("includeInNcesResult") Boolean includeInNcesResult,
        @JsonProperty("isApplicant") Boolean isApplicant) {
}
