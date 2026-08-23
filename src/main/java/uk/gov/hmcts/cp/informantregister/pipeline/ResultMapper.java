package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.ResultLevel;

/**
 * One defendant's judicial results, selected by the level they were recorded at.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * ResultMapper.js}. The defendant's results were tagged with a level and an owning identifier by the
 * fragment-building step; this mapper is the reader that picks out the ones belonging to each node of
 * the outbound document — the defendant itself, one of its cases, one of its offences, or one of its
 * applications.
 *
 * <p><strong>An empty selection is nothing, not an empty list.</strong> Every one of the four legacy
 * methods ends {@code if (results.length) { return results; }} and otherwise falls off the end
 * returning {@code undefined}, which {@code JSON.stringify} drops. That is what keeps the
 * {@code results} key off a node with no results — and it matters on the wire, because the contract
 * gives every one of these arrays {@code minItems: 1}, so an empty array would be a schema violation
 * where an absent key is valid. {@code null} is returned here for the same reason, and
 * {@code @JsonInclude(NON_NULL)} does the rest.
 *
 * <p>The four selections differ only in their filter. Each is the conjunction of the level tag and
 * the owning identifier — the tag alone is not enough, because the same identifier can appear at two
 * levels.
 */
final class ResultMapper {

    private final RegisterDefendant defendant;
    private final ResultDataMapper resultDataMapper;

    /**
     * Creates the mapper for one defendant.
     *
     * @param defendant        the defendant whose results are being read
     * @param resultDataMapper the mapper for the detail hanging off each result
     */
    ResultMapper(final RegisterDefendant defendant, final ResultDataMapper resultDataMapper) {
        this.defendant = defendant;
        this.resultDataMapper = resultDataMapper;
    }

    /**
     * The results recorded against the defendant as a whole.
     *
     * @return the results, or {@code null} when there are none
     */
    List<InformantRegisterResult> defendantLevel() {
        return select(result -> result.level() == ResultLevel.DEFENDANT);
    }

    /**
     * The results recorded against one of the defendant's prosecution cases.
     *
     * @param prosecutionCaseId the case to select
     * @return the results, or {@code null} when there are none
     */
    List<InformantRegisterResult> caseLevel(final String prosecutionCaseId) {
        return select(result -> result.level() == ResultLevel.CASE
                && Objects.equals(result.prosecutionCaseId(), prosecutionCaseId));
    }

    /**
     * The results recorded against one offence.
     *
     * <p>The offence arrives as a canonical tree rather than as its id because that is the legacy
     * signature — {@code buildOffenceLevelResults(offence)} reads {@code offence.id} itself.
     *
     * @param offence the offence to select for
     * @return the results, or {@code null} when there are none
     */
    List<InformantRegisterResult> offenceLevel(final JsonNode offence) {
        final String offenceId = Json.text(offence, "id");
        return select(result -> result.level() == ResultLevel.OFFENCE
                && Objects.equals(result.offenceId(), offenceId));
    }

    /**
     * The results recorded against one court application.
     *
     * @param applicationId the application to select
     * @return the results, or {@code null} when there are none
     */
    List<InformantRegisterResult> applicationLevel(final String applicationId) {
        return select(result -> result.level() == ResultLevel.APPLICATION
                && Objects.equals(result.applicationId(), applicationId));
    }

    /**
     * Maps the results matching one filter, in the order the defendant carries them.
     *
     * @param selector the filter
     * @return the mapped results, or {@code null} when none matched
     */
    private List<InformantRegisterResult> select(final Predicate<RegisterResult> selector) {
        final List<InformantRegisterResult> results = new ArrayList<>();
        for (final RegisterResult result : defendant.results()) {
            if (selector.test(result)) {
                results.add(map(result));
            }
        }
        return results.isEmpty() ? null : results;
    }

    /**
     * Maps one tagged result onto its outbound form.
     *
     * @param result the tagged result
     * @return the outbound result
     */
    private InformantRegisterResult map(final RegisterResult result) {
        return new InformantRegisterResult(
                Json.text(result.judicialResult(), "resultText"),
                Json.text(result.judicialResult(), "cjsCode"),
                resultDataMapper.build(result.judicialResult()));
    }
}
