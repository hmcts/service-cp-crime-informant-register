package uk.gov.hmcts.cp.informantregister.application;

import java.time.LocalDate;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;

/**
 * Where the now-subscriptions reference data a register is addressed with comes from.
 *
 * <p>The legacy fetches it inside the second activity
 * ({@code InformantRegisterSubscriptions/index.js:20} → {@code ReferenceDataService
 * .getSubscriptionsMetadata}). Here the fetch is a port and the matching is a pure function of what
 * it answers, so the transformation can be compared against the recorded goldens without a network.
 *
 * <p><strong>An answer with nothing in it is not a failure.</strong> Reference data may legitimately
 * answer with no body, a body with no {@code nowSubscriptions} member, or one with no
 * informant-register subscriptions among them; the legacy then returns the fragments untouched
 * ({@code index.js:22-33}) and the register is filed with no recipients. That is a
 * {@code null}-or-empty answer, not an exception.
 *
 * <p><strong>Not being able to ask is a failure, and it is transient.</strong> The legacy catches
 * every transport failure and returns {@code null} ({@code ReferenceDataService.js:52}), which is
 * indistinguishable on the wire from "nobody is subscribed" — so an outage silently ships a register
 * that reaches nobody. This port refuses instead; see {@code doc/DEVIATIONS.md} entry 14.
 */
public interface NowSubscriptionsSource {

    /**
     * Fetches the now-subscriptions body for a given day.
     *
     * @param on the day the query is dated with — {@code ReferenceDataService.js:38} derives it from
     *           the register date as {@code new Date(registerDate).toISOString().slice(0, 10)}
     * @return the now-subscriptions body, or {@code null} when reference data answered with none
     * @throws ReferenceDataUnavailableException if reference data could not be asked
     */
    JsonNode fetch(LocalDate on);
}
