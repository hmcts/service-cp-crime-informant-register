package uk.gov.hmcts.cp.informantregister.pipeline;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterResultData;

/**
 * The structured detail hanging off one judicial result — next hearing, duration, imposed amount.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * ResultDataMapper.js}, the deepest leaf of the aggregation tree.
 *
 * <p><strong>Result data exists or it does not.</strong> The legacy {@code canCreateResultData} gate
 * runs first and answers for the whole object: unless the result carries a next hearing, a duration
 * element, or a non-empty prompt list on a financial result, nothing is created and the outbound
 * result has no {@code resultData} key at all. Note the third arm needs all three of its conditions —
 * prompts on a result that is not financial create nothing — and that it only asks whether prompts
 * <em>exist</em>, not whether any of them imposes anything. A financial result whose prompts contain
 * no imposition therefore yields result data with no amount in it, which is not the same as no result
 * data.
 *
 * <p><strong>Everything below the gate is optional and absent when unset.</strong> The legacy assigns
 * {@code undefined} rather than skipping the assignment, and {@code JSON.stringify} drops those keys;
 * {@code @JsonInclude(NON_NULL)} on {@link InformantRegisterResultData} reproduces it exactly. The
 * distinction matters on the wire, because the contract types every one of these as a string.
 *
 * <p><strong>Two dates, two different renderings.</strong> The next hearing's start goes through
 * {@link HearingDates#localDateTime}, the duration dates through
 * {@link HearingDates#formattedLocalDateTime} — which re-reads them as {@code DD/MM/YYYY} first, and
 * turns anything else into the literal {@code "Invalid dateZ"}. Both are ported as written; see the
 * two methods for why.
 */
final class ResultDataMapper {

    private final HearingDates dates;

    /**
     * Creates the mapper.
     *
     * @param dates the date service the rendered timestamps come from
     */
    ResultDataMapper(final HearingDates dates) {
        this.dates = dates;
    }

    /**
     * Builds the result data for one judicial result.
     *
     * @param judicialResult the judicial result, as a canonical tree
     * @return the result data, or {@code null} when the legacy creates none
     */
    InformantRegisterResultData build(final JsonNode judicialResult) {
        if (!canCreate(judicialResult)) {
            return null;
        }

        final JsonNode nextHearing = Json.at(judicialResult, "nextHearing");
        final boolean hasNextHearing = Json.truthy(nextHearing);
        final JsonNode durationElement = Json.at(judicialResult, "durationElement");
        final boolean hasDuration = Json.truthy(durationElement);

        return new InformantRegisterResultData(
                amount(judicialResult),
                hasNextHearing ? nextHearingDate(nextHearing) : null,
                // `nextHearing.courtCentre.name` — dereferenced with no guard on `courtCentre`
                // (ResultDataMapper.js:16), so a next hearing without one is a TypeError there.
                hasNextHearing
                        ? Json.text(Json.dereferenced(nextHearing, "courtCentre"), "name") : null,
                hasDuration ? number(durationElement, "primaryDurationValue") : null,
                hasDuration ? Json.text(durationElement, "primaryDurationUnit") : null,
                hasDuration ? durationDate(durationElement, "durationStartDate") : null,
                hasDuration ? durationDate(durationElement, "durationEndDate") : null,
                hasDuration ? number(durationElement, "secondaryDurationValue") : null,
                hasDuration ? Json.text(durationElement, "secondaryDurationUnit") : null);
    }

    /**
     * Whether the legacy would create result data at all.
     *
     * <p>Ports {@code canCreateResultData} (`ResultDataMapper.js:37-40`) including its shape: three
     * truthiness tests, the third of which is itself a conjunction of three.
     *
     * @param judicialResult the judicial result
     * @return whether result data is created
     */
    private static boolean canCreate(final JsonNode judicialResult) {
        return Json.truthy(judicialResult, "nextHearing")
                || Json.truthy(judicialResult, "durationElement")
                || Json.truthy(judicialResult, "isFinancialResult")
                && Json.nonEmptyArray(judicialResult, "judicialResultPrompts");
    }

    /**
     * The next hearing's start, rendered, or {@code null} when it has none.
     *
     * @param nextHearing the next hearing
     * @return the rendered start time, or {@code null}
     */
    private String nextHearingDate(final JsonNode nextHearing) {
        return Json.truthy(nextHearing, "listedStartDateTime")
                ? dates.localDateTime(Json.text(nextHearing, "listedStartDateTime"))
                : null;
    }

    /**
     * One duration date, rendered through the legacy's {@code DD/MM/YYYY} re-read.
     *
     * @param durationElement the duration element
     * @param field           the date field to read
     * @return the rendered date, or {@code null} when the field is falsy
     */
    private String durationDate(final JsonNode durationElement, final String field) {
        return Json.truthy(durationElement, field)
                ? dates.formattedLocalDateTime(Json.text(durationElement, field))
                : null;
    }

    /**
     * A duration value, stringified the way {@code Number.prototype.toString} would.
     *
     * <p>The legacy calls {@code .toString()} on these (`ResultDataMapper.js:21,23`) because the
     * contract types them as strings while the payload sends numbers. Trailing zeros are stripped
     * because that is what JavaScript prints — {@code (5.0).toString()} is {@code "5"} — and because
     * this service reads floating-point values as {@code BigDecimal}, which would otherwise print the
     * scale the payload happened to use.
     *
     * @param durationElement the duration element
     * @param field           the value field to read
     * @return the stringified value, or {@code null} when the field is falsy
     */
    private static String number(final JsonNode durationElement, final String field) {
        final JsonNode value = Json.at(durationElement, field);
        if (!Json.truthy(value)) {
            return null;
        }
        return value.isNumber()
                ? value.decimalValue().stripTrailingZeros().toPlainString()
                : value.asString();
    }

    /**
     * The amount imposed by the first financial-imposition prompt, if there is one.
     *
     * <p>{@code Array.prototype.find} stops at the first match, so a result carrying several
     * impositions reports only the first — not the largest, and not their total.
     *
     * @param judicialResult the judicial result
     * @return the amount, or {@code null}
     */
    private static String amount(final JsonNode judicialResult) {
        if (!Json.truthy(judicialResult, "isFinancialResult")
                || !Json.nonEmptyArray(judicialResult, "judicialResultPrompts")) {
            return null;
        }
        for (final JsonNode prompt : Json.array(judicialResult, "judicialResultPrompts")) {
            if (Json.truthy(prompt, "isFinancialImposition")) {
                return Json.text(prompt, "value");
            }
        }
        return null;
    }
}
