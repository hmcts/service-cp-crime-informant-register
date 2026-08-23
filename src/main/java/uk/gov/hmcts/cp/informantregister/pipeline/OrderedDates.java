package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * Which ordered date the legacy calls the latest — and, more importantly, which ones it compares.
 *
 * <p>Both legacy call sites are the same shape, sorted descending and indexed at zero:
 *
 * <ul>
 *   <li>{@code DefendantContextBaseService.js:294-297}, per defendant, with no error handling at
 *       all (parity pin {@code s06});</li>
 *   <li>{@code RegisterFragmentService.js:30-44}, across the hearing, wrapped in a catch block that
 *       throws a {@code TypeError} of its own (defect D10).</li>
 * </ul>
 *
 * <p>Both comparators call {@code DateService.parse}, which throws on a date it cannot read, and the
 * throw is what destroys the hearing. So whether a bad ordered date is fatal is decided entirely by
 * whether {@code Array.prototype.sort} hands it to the comparator, and the specification gives two
 * rules that say when it does not:
 *
 * <ul>
 *   <li><strong>Fewer than two elements are never compared.</strong> A single unreadable ordered
 *       date passes straight through and is formatted into the register as the literal
 *       {@code "Invalid dateZ"} — which is exactly what both unmodified {@code
 *       OutboundInformantRegister} fixtures do, and what parity pin {@code s05} records.</li>
 *   <li><strong>{@code undefined} elements are moved to the end and never compared.</strong>
 *       {@code Array.prototype.sort} removes them before the comparator runs
 *       (ECMA-262, {@code SortIndexedProperties}), so a result carrying no {@code orderedDate}
 *       cannot destroy a hearing however many other results there are.</li>
 * </ul>
 *
 * <p>An <em>explicit</em> JSON null is not covered by the second rule: JavaScript {@code null} is an
 * ordinary value to {@code sort}, is passed to the comparator, and {@code moment(null, …)} is
 * invalid — so it throws. That is why the ordered dates travel through here as nodes rather than as
 * strings: {@code Json.text} collapses "absent" and "null" onto the same Java {@code null}, and the
 * two are the difference between a register and a lost hearing.
 *
 * <p>A port that simply compares everything refuses hearings the legacy files, which is the one
 * direction a bug-for-bug port must not drift in.
 */
final class OrderedDates {

    /** The size at which {@code sort} returns its input without ever calling the comparator. */
    private static final int SOLE_ELEMENT = 1;

    private OrderedDates() {
    }

    /**
     * The latest of a list of ordered-date nodes, as {@code sort(...)[0]} answers it.
     *
     * @param orderedDates the ordered-date values in the order the legacy collected them; a
     *                     {@code null} element is an absent field, JavaScript's {@code undefined}
     * @param dates        the date service whose {@code parse} the comparator uses
     * @return the latest ordered date's text, or {@code null} when there is nothing to compare
     * @throws TransformationFailedException if the comparator meets a date it cannot read
     */
    /* default */ static String latest(final List<JsonNode> orderedDates, final HearingDates dates) {
        final List<JsonNode> compared = new ArrayList<>(orderedDates.size());
        for (final JsonNode orderedDate : orderedDates) {
            if (orderedDate != null) {
                compared.add(orderedDate);
            }
        }
        if (compared.isEmpty()) {
            // Every element was `undefined`, so `sorted[0]` is `undefined` too.
            return null;
        }
        if (compared.size() == SOLE_ELEMENT) {
            // One element: `sort` returns it without ever calling the comparator, so an unreadable
            // date is carried rather than refused.
            return textOf(compared.getFirst());
        }
        return compared.stream()
                .map(OrderedDates::textOf)
                .max(Comparator.comparing(dates::orderingKey))
                .orElse(null);
    }

    /**
     * The ordered-date value as the comparator sees it.
     *
     * @param orderedDate the node
     * @return its text, or {@code null} for an explicit JSON null — which the comparator refuses
     */
    private static String textOf(final JsonNode orderedDate) {
        return orderedDate.isNull() ? null : orderedDate.stringValue();
    }
}
